package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.dto.SkillRowView;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.SlotSkillForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.SkillService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * ManagerSkillController（SM-04）の結合テスト（20-SM-04 6章 No.1〜7、97_テスト計画書 4.2）。
 *
 * <p>
 * SkillService はモックにし、MockMvc（standalone）で、入力チェック・画面の振り分け・フラッシュ・リダイレクト先を確かめる。
 * 画面（HTML）は表示しない。前後の空白を取る処理（StringTrimmerEditor）は、本物の GlobalModelAdvice を組み込んで確かめる。
 * </p>
 *
 * <pre>
 * メソッド           | 判定                               | テスト
 * create             | bindingResult.hasErrors()          | 真：一覧を入れ直して再表示、偽：サービスを呼ぶ
 * create             | catch BusinessException            | 例外あり：E2404 をフラッシュ、なし：I2402
 * update             | bindingResult.hasErrors()          | 真：最初のエラーのコードでリダイレクト、偽：サービスを呼ぶ
 * update             | catch BusinessException            | 例外あり：E2404 をフラッシュ、なし：I2403
 * saveSlotRequired   | catch BusinessException            | 例外あり：E2405 をフラッシュ、なし：I2405
 * </pre>
 */
class ManagerSkillControllerTests {

    private static final String VIEW = "manager/skills";

    private SkillService skillService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        NotificationService notificationService = mock(NotificationService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        when(skillService.findRows()).thenReturn(List.of(new SkillRowView(1L, "レジ", "会計", 2L)));
        when(skillService.getSlotSkillForm()).thenReturn(new SlotSkillForm());
        mvc = MockMvcBuilders.standaloneSetup(new ManagerSkillController(skillService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, notificationService))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        User u = new User();
        u.setId(9L);
        u.setLoginId("boss");
        u.setRole(Role.MANAGER);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private static List<String> codes(MvcResult result, String field) {
        BindingResult binding = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "skillForm");
        return binding.getFieldErrors(field).stream().map(e -> e.getCode()).toList();
    }

    // ---------------------------------------------------------------- 表示

    @Test
    void 表示_一覧と空のフォームと枠の必要職能を持って職能管理画面へ() throws Exception {
        mvc.perform(get("/manager/skills"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attributeExists("skills", "skillForm", "slotSkillForm"));
    }

    // ---------------------------------------------------------------- 追加

    @Test
    void 追加_No1_名前が空ならE2401で一覧を入れ直して再表示しサービスは呼ばない() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "").param("description", ""))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attributeExists("skills", "slotSkillForm"))
                .andReturn();

        assertThat(codes(result, "name")).containsExactly("NotBlank");
        verify(skillService, never()).create(anyString(), anyString(), anyLong());
    }

    @Test
    void 追加_No1_名前が51文字ならE2402で再表示() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "あ".repeat(51)))
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(codes(result, "name")).containsExactly("Size");
        verify(skillService, never()).create(any(), any(), anyLong());
    }

    @Test
    void 追加_名前が50文字と説明が200文字は通る() throws Exception {
        String name = "あ".repeat(50);
        String description = "い".repeat(200);

        mvc.perform(post("/manager/skills").param("name", name).param("description", description))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2402"));

        verify(skillService).create(name, description, 9L);
    }

    @Test
    void 追加_説明が201文字ならE2403で再表示() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "レジ")
                .param("description", "い".repeat(201)))
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(codes(result, "description")).containsExactly("Size");
    }

    @Test
    void 追加_前後の空白は取って渡し_説明が空ならnullで渡す() throws Exception {
        mvc.perform(post("/manager/skills").param("name", "  レジ  ").param("description", "   "))
                .andExpect(redirectedUrl("/manager/skills"));

        verify(skillService).create("レジ", null, 9L);
    }

    @Test
    void 追加_No2_同じ名前ならE2404をフラッシュして一覧へ() throws Exception {
        doThrow(new BusinessException("E2404")).when(skillService).create("レジ", null, 9L);

        mvc.perform(post("/manager/skills").param("name", "レジ"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2404"));
    }

    // ---------------------------------------------------------------- 変更

    @Test
    void 変更_名前が空なら最初のエラーのE2401でリダイレクトしサービスは呼ばない() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", ""))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2401"));

        verify(skillService, never()).update(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 変更_名前が51文字ならE2402でリダイレクト() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "あ".repeat(51)))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2402"));
    }

    @Test
    void 変更_説明が201文字ならE2403でリダイレクト() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "レジ").param("description", "い".repeat(201)))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2403"));
    }

    @Test
    void 変更_No3_成功するとI2403で一覧へ() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "レジ").param("description", "会計"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2403"));

        verify(skillService).update(5L, "レジ", "会計", 9L);
    }

    @Test
    void 変更_No2_ほかと同じ名前ならE2404をフラッシュ() throws Exception {
        doThrow(new BusinessException("E2404")).when(skillService).update(5L, "レジ", null, 9L);

        mvc.perform(post("/manager/skills/5").param("name", "レジ"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2404"));
    }

    @Test
    void 変更_IDが数字でなければ400() throws Exception {
        mvc.perform(post("/manager/skills/abc").param("name", "レジ"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 削除

    @Test
    void 削除_No4_成功するとI2404で一覧へ() throws Exception {
        mvc.perform(post("/manager/skills/5/delete"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2404"));

        verify(skillService).delete(5L, 9L);
    }

    // ---------------------------------------------------------------- 枠の必要職能

    @Test
    void 枠の必要職能_No5_チェックした職能のIDを渡しI2405で一覧へ() throws Exception {
        mvc.perform(post("/manager/skills/slot-required")
                .param("daySkillIds", "1", "2").param("eveningSkillIds", "2"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2405"));

        verify(skillService).saveSlotRequired(List.of(1L, 2L), List.of(2L), 9L);
    }

    @Test
    void 枠の必要職能_No6_何もチェックしなければ空のリストを渡す() throws Exception {
        mvc.perform(post("/manager/skills/slot-required"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2405"));

        verify(skillService).saveSlotRequired(List.of(), List.of(), 9L);
    }

    @Test
    void 枠の必要職能_No7_存在しない職能ならE2405をフラッシュ() throws Exception {
        doThrow(new BusinessException("E2405")).when(skillService)
                .saveSlotRequired(List.of(99L), List.of(), 9L);

        mvc.perform(post("/manager/skills/slot-required").param("daySkillIds", "99"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2405"));
    }
}

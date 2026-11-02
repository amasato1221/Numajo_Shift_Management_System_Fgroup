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
 * 判定                                      | テスト
 * create     bindingResult.hasErrors()      | 真：一覧を入れ直して再表示、偽：サービスを呼ぶ
 * create     catch BusinessException        | 例外あり：E2404 をフラッシュ、なし：I2402
 * update     catch BusinessException        | 例外あり：E2404 をフラッシュ、なし：I2403
 * saveSlotRequired  catch BusinessException | 例外あり：E2405 をフラッシュ、なし：I2405
 *
 * 組み合わせ表：入力チェック（名前のエラー × 説明のエラー。97_テスト計画書 3.2）
 * No. | 名前 | 説明 | 追加の結果                 | 変更の結果
 *  1  | なし | なし | サービスを呼ぶ（I2402）     | サービスを呼ぶ（I2403）
 *  2  | あり | なし | 名前のエラーで再表示        | 名前のエラー（E2401・E2402）でリダイレクト
 *  3  | なし | あり | 説明のエラーで再表示        | 説明のエラー（E2403）でリダイレクト
 *  4  | あり | あり | 両方のエラーで再表示        | 名前のエラーを先に出す（E2401・E2402）
 *
 * 各テスト：No.1＝追加で名前が50文字と説明が200文字／変更に成功する
 *          No.2＝追加・変更で名前が空・51文字    No.3＝追加・変更で説明が201文字
 *          No.4＝追加で名前が空かつ説明が201文字／変更で名前が空かつ説明が201文字・名前が51文字かつ説明が201文字
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
    void 画面を表示する_一覧と空のフォームと枠の必要職能を持って職能管理画面になる() throws Exception {
        mvc.perform(get("/manager/skills"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attributeExists("skills", "skillForm", "slotSkillForm"));
    }

    // ---------------------------------------------------------------- 追加

    @Test
    void 追加で名前が空_E2401で一覧を入れ直して再表示しサービスを呼ばない() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "").param("description", ""))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attributeExists("skills", "slotSkillForm"))
                .andReturn();

        assertThat(codes(result, "name")).containsExactly("NotBlank");
        verify(skillService, never()).create(anyString(), anyString(), anyLong());
    }

    @Test
    void 追加で名前が51文字_E2402で再表示する() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "あ".repeat(51)))
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(codes(result, "name")).containsExactly("Size");
        verify(skillService, never()).create(any(), any(), anyLong());
    }

    @Test
    void 追加で名前が50文字と説明が200文字_通ってI2402で一覧へ戻る() throws Exception {
        String name = "あ".repeat(50);
        String description = "い".repeat(200);

        mvc.perform(post("/manager/skills").param("name", name).param("description", description))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2402"));

        verify(skillService).create(name, description, 9L);
    }

    @Test
    void 追加で説明が201文字_E2403で再表示する() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "レジ")
                .param("description", "い".repeat(201)))
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(codes(result, "description")).containsExactly("Size");
    }

    @Test
    void 追加で名前が空かつ説明が201文字_両方のエラーで再表示する() throws Exception {
        MvcResult result = mvc.perform(post("/manager/skills").param("name", "")
                .param("description", "い".repeat(201)))
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(codes(result, "name")).containsExactly("NotBlank");
        assertThat(codes(result, "description")).containsExactly("Size");
        verify(skillService, never()).create(any(), any(), anyLong());
    }

    @Test
    void 追加で前後に空白がある_取って渡し説明が空ならnullで渡す() throws Exception {
        mvc.perform(post("/manager/skills").param("name", "  レジ  ").param("description", "   "))
                .andExpect(redirectedUrl("/manager/skills"));

        verify(skillService).create("レジ", null, 9L);
    }

    @Test
    void 追加で同じ名前_E2404をフラッシュして一覧へ戻る() throws Exception {
        doThrow(new BusinessException("E2404")).when(skillService).create("レジ", null, 9L);

        mvc.perform(post("/manager/skills").param("name", "レジ"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2404"));
    }

    // ---------------------------------------------------------------- 変更

    @Test
    void 変更で名前が空_E2401でリダイレクトしサービスを呼ばない() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", ""))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2401"));

        verify(skillService, never()).update(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 変更で名前が51文字_E2402でリダイレクトする() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "あ".repeat(51)))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2402"));
    }

    @Test
    void 変更で説明が201文字_E2403でリダイレクトする() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "レジ").param("description", "い".repeat(201)))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2403"));
    }

    @Test
    void 変更で名前が空かつ説明が201文字_名前のE2401を先に出す() throws Exception {
        // エラーの並び順が決まっていないと、送るたびに E2401 と E2403 が入れ替わるので、何回か送って確かめる
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/manager/skills/5").param("name", "").param("description", "い".repeat(201)))
                    .andExpect(redirectedUrl("/manager/skills"))
                    .andExpect(flash().attribute("errorMessage", "E2401"));
        }

        verify(skillService, never()).update(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 変更で名前が51文字かつ説明が201文字_名前のE2402を先に出す() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/manager/skills/5").param("name", "あ".repeat(51))
                    .param("description", "い".repeat(201)))
                    .andExpect(redirectedUrl("/manager/skills"))
                    .andExpect(flash().attribute("errorMessage", "E2402"));
        }
    }

    @Test
    void 変更に成功する_I2403で一覧へ戻る() throws Exception {
        mvc.perform(post("/manager/skills/5").param("name", "レジ").param("description", "会計"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2403"));

        verify(skillService).update(5L, "レジ", "会計", 9L);
    }

    @Test
    void 変更でほかと同じ名前_E2404をフラッシュする() throws Exception {
        doThrow(new BusinessException("E2404")).when(skillService).update(5L, "レジ", null, 9L);

        mvc.perform(post("/manager/skills/5").param("name", "レジ"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2404"));
    }

    @Test
    void 変更のIDが数字でない_400になる() throws Exception {
        mvc.perform(post("/manager/skills/abc").param("name", "レジ"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 削除

    @Test
    void 削除に成功する_I2404で一覧へ戻る() throws Exception {
        mvc.perform(post("/manager/skills/5/delete"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2404"));

        verify(skillService).delete(5L, 9L);
    }

    // ---------------------------------------------------------------- 枠の必要職能

    @Test
    void 枠の必要職能をチェックして保存する_チェックした職能IDを渡しI2405で一覧へ戻る() throws Exception {
        mvc.perform(post("/manager/skills/slot-required")
                .param("daySkillIds", "1", "2").param("eveningSkillIds", "2"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2405"));

        verify(skillService).saveSlotRequired(List.of(1L, 2L), List.of(2L), 9L);
    }

    @Test
    void 枠の必要職能を何もチェックせず保存する_空のリストを渡す() throws Exception {
        mvc.perform(post("/manager/skills/slot-required"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2405"));

        verify(skillService).saveSlotRequired(List.of(), List.of(), 9L);
    }

    @Test
    void 枠の必要職能に存在しない職能を送る_E2405をフラッシュする() throws Exception {
        doThrow(new BusinessException("E2405")).when(skillService)
                .saveSlotRequired(List.of(99L), List.of(), 9L);

        mvc.perform(post("/manager/skills/slot-required").param("daySkillIds", "99"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2405"));
    }
}

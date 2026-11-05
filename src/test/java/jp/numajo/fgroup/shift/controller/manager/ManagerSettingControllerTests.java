package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.form.StoreSettingForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * ManagerSettingController（SM-09）の単体テスト（20-SM-09 6章 No.1・2・6、97_テスト計画書 4.2）。
 *
 * <p>
 * StoreSettingService はモックにし、MockMvc（standalone）で、入力チェック・画面の振り分け・フラッシュ・リダイレクト先を確かめる。
 * 入力の前後の空白を取る処理（StringTrimmerEditor）は、本物の GlobalModelAdvice を組み込んで確かめる。
 * 画面（HTML）の描画・DB への保存・操作ログ・権限は、ManagerSettingFlowTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定                            | 条件                                         | テスト
 * bindingResult.hasErrors()       | 真：入力エラーで再表示、偽：保存してリダイレクト | 下の表の4行
 *   （条件）@NotBlank に違反       | 店舗名が null・空・空白だけ                   |
 *   （条件）@Size(max=50) に違反   | 店舗名が51文字以上                            |
 *
 * 複数条件（NotBlank違反, Size違反） | 結果                    | テスト
 * （偽, 偽）                        | 保存（50文字・1文字）    | 店舗名が50文字・1文字_保存できる
 * （偽, 真）                        | E2902                   | 店舗名が51文字_E2902
 * （真, 偽）                        | E2901                   | 店舗名が空・空白だけ・送られない_E2901
 * （真, 真）                        | 起きない（空白を取ると0文字になり51文字以上にならない） | 空白だけ51文字_E2901だけになる
 * </pre>
 */
class ManagerSettingControllerTests {

    private static final String FORM = "storeSettingForm";

    private StoreSettingService storeSettingService;
    private MockMvc mvc;
    private StoreSetting setting;

    @BeforeEach
    void setUp() {
        storeSettingService = mock(StoreSettingService.class);
        NotificationService notificationService = mock(NotificationService.class);
        setting = new StoreSetting();
        setting.setId((byte) 1);
        setting.setStoreName("テスト店");
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        mvc = MockMvcBuilders.standaloneSetup(new ManagerSettingController(storeSettingService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, notificationService))
                .build();
        User u = new User();
        u.setId(5L);
        u.setLoginId("manager1");
        u.setRole(Role.MANAGER);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 部品

    private static List<String> codes(MvcResult result) {
        BindingResult binding = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + FORM);
        return binding.getFieldErrors("storeName").stream().map(e -> e.getCode()).toList();
    }

    private void assertNotSaved(MvcResult result) {
        assertThat(result.getModelAndView().getViewName()).isEqualTo("manager/settings");
        assertThat(result.getModelAndView().getModel().get("setting")).isSameAs(setting);
        verify(storeSettingService, never()).update(anyString(), anyBoolean(), anyLong());
    }

    // ---------------------------------------------------------------- 2.1 初期表示

    @ParameterizedTest(name = "表示する設定が {0} のとき、スイッチも {0}")
    @CsvSource({ "true", "false" })
    void 画面を表示する_今の設定をフォームに入れて店舗設定画面になる(boolean show) throws Exception {
        setting.setShowOthersShifts(show);

        MvcResult result = mvc.perform(get("/manager/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/settings"))
                .andExpect(model().attribute("setting", setting))
                .andReturn();

        StoreSettingForm form = (StoreSettingForm) result.getModelAndView().getModel().get(FORM);
        assertThat(form.getStoreName()).isEqualTo("テスト店");
        assertThat(form.isShowOthersShifts()).isEqualTo(show);
    }

    // ---------------------------------------------------------------- No.1 店舗名が空・空白だけ

    @ParameterizedTest(name = "No.1 店舗名「{0}」は E2901")
    @CsvSource(value = { "''", "'   '", "'　'" }, quoteCharacter = '\'')
    void 店舗名が空や空白だけ_E2901で再表示し保存しない(String name) throws Exception {
        MvcResult result = mvc.perform(post("/manager/settings").param("storeName", name)
                .param("showOthersShifts", "true"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(codes(result)).containsExactly("NotBlank");
        assertNotSaved(result);
    }

    @Test
    void 店舗名が送られない_E2901で再表示し保存しない() throws Exception {
        MvcResult result = mvc.perform(post("/manager/settings").param("_showOthersShifts", "on"))
                .andReturn();

        assertThat(codes(result)).containsExactly("NotBlank");
        assertNotSaved(result);
    }

    @Test
    void 空白だけ51文字_空白を取るとE2901だけになる() throws Exception {
        MvcResult result = mvc.perform(post("/manager/settings").param("storeName", " ".repeat(51)))
                .andReturn();

        assertThat(codes(result)).containsExactly("NotBlank");
        assertNotSaved(result);
    }

    // ---------------------------------------------------------------- No.2 50文字／51文字

    @ParameterizedTest(name = "No.2 店舗名が{0}文字なら保存できる")
    @CsvSource({ "1", "50" })
    void 店舗名が50文字と1文字_保存してI2901で店舗設定へ戻る(int length) throws Exception {
        String name = "あ".repeat(length);

        mvc.perform(post("/manager/settings").param("storeName", name).param("showOthersShifts", "true")
                .param("_showOthersShifts", "on"))
                .andExpect(redirectedUrl("/manager/settings"))
                .andExpect(flash().attribute("successMessage", "I2901"));

        verify(storeSettingService).update(name, true, 5L);
    }

    @Test
    void 店舗名が51文字_E2902で再表示し保存しない() throws Exception {
        MvcResult result = mvc.perform(post("/manager/settings").param("storeName", "あ".repeat(51)))
                .andReturn();

        assertThat(codes(result)).containsExactly("Size");
        assertNotSaved(result);
    }

    // ---------------------------------------------------------------- スイッチ・空白を取る

    @Test
    void スイッチを外して保存する_hiddenだけ送られてfalseで保存する() throws Exception {
        mvc.perform(post("/manager/settings").param("storeName", "新しい店").param("_showOthersShifts", "on"))
                .andExpect(redirectedUrl("/manager/settings"));

        verify(storeSettingService).update("新しい店", false, 5L);
    }

    @Test
    void 店舗名の前後に半角空白がある_空白を取って保存する() throws Exception {
        mvc.perform(post("/manager/settings").param("storeName", "  新しい店  ").param("showOthersShifts", "true"))
                .andExpect(redirectedUrl("/manager/settings"));

        verify(storeSettingService).update("新しい店", true, 5L);
    }

    // ---------------------------------------------------------------- No.6 何も変えずに保存

    @Test
    void 何も変えずに保存する_I2901は出る() throws Exception {
        mvc.perform(post("/manager/settings").param("storeName", "テスト店").param("showOthersShifts", "true"))
                .andExpect(redirectedUrl("/manager/settings"))
                .andExpect(flash().attribute("successMessage", "I2901"));

        // 同じ値のときに操作ログを残さないことは StoreSettingServiceTests と ManagerSettingFlowTests で確かめる
        verify(storeSettingService).update("テスト店", true, 5L);
    }
}

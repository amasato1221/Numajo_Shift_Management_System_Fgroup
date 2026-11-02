package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.UserService;

/**
 * AccountController（SC-03）の結合テスト（20-SC-03 6章 No.1〜7、97_テスト計画書 4.2）。
 *
 * <p>
 * UserService はモックにし、MockMvc（standalone）で、入力チェック・画面の振り分け・フラッシュ・リダイレクト先を確かめる。
 * 画面（HTML）は表示しない（テンプレートは④b の layout/base.html が入ってから総合テストで確かめる）。
 * 入力の前後の空白を取る StringTrimmerEditor は、④b の GlobalModelAdvice が行う処理を、
 * このクラスの {@link TrimAdvice} で再現している（④b がマージされたら、本物に差し替えてよい）。
 * </p>
 *
 * <pre>
 * 判定                                      | テスト
 * bindingResult.hasErrors()                 | 真：入力エラーで再表示、偽：サービスを呼ぶ
 * try { changePassword } catch (Business)   | 例外あり：E0305〜E0307 をフラッシュへ、なし：成功
 * </pre>
 */
class AccountControllerTests {

    private static final String FORM = "passwordChangeForm";

    private UserService userService;
    private MockMvc mvc;
    private LoginUser loginUser;

    @ControllerAdvice
    static class TrimAdvice {
        @InitBinder
        void initBinder(WebDataBinder binder) {
            binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
        }
    }

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(userService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new TrimAdvice())
                .build();
        login(Role.STAFF);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 部品

    private void login(Role role) {
        User u = new User();
        u.setId(7L);
        u.setLoginId("alice");
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(true);
        loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private MockHttpServletRequestBuilder change(String current, String newPw, String confirm) {
        MockHttpServletRequestBuilder request = post("/account/password");
        if (current != null) {
            request.param("currentPassword", current);
        }
        if (newPw != null) {
            request.param("newPassword", newPw);
        }
        if (confirm != null) {
            request.param("confirmPassword", confirm);
        }
        return request;
    }

    private static List<String> codes(MvcResult result, String field) {
        BindingResult binding = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + FORM);
        return binding.getFieldErrors(field).stream().map(e -> e.getCode()).toList();
    }

    // ---------------------------------------------------------------- 表示

    @Test
    void 表示_空のフォームを持ってパスワード変更画面へ() throws Exception {
        mvc.perform(get("/account/password"))
                .andExpect(status().isOk())
                .andExpect(view().name("common/account-password"))
                .andExpect(model().attributeExists(FORM));
    }

    // ---------------------------------------------------------------- No.1〜3 入力チェック

    @Test
    void No1_3項目とも空ならE0301_E0302_E0304の3つだけ() throws Exception {
        MvcResult result = mvc.perform(change("", "", ""))
                .andExpect(view().name("common/account-password"))
                .andReturn();

        assertThat(codes(result, "currentPassword")).containsExactly("NotBlank");
        assertThat(codes(result, "newPassword")).containsExactly("NotBlank");
        assertThat(codes(result, "confirmPassword")).containsExactly("NotBlank");
        verify(userService, never()).changePassword(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void No2_新しいパスワードが7文字ならE0303() throws Exception {
        MvcResult result = mvc.perform(change("cur", "a".repeat(7), "a".repeat(7)))
                .andExpect(view().name("common/account-password"))
                .andReturn();

        assertThat(codes(result, "newPassword")).containsExactly("Pattern");
    }

    @ParameterizedTest(name = "No.2 新しいパスワードが{0}文字ならOK")
    @CsvSource({ "8", "64" })
    void No2_新しいパスワードが8文字と64文字は通る(int length) throws Exception {
        String pw = "a".repeat(length);

        mvc.perform(change("cur", pw, pw)).andExpect(status().is3xxRedirection());

        verify(userService).changePassword(7L, "cur", pw, pw);
    }

    @Test
    void No2_新しいパスワードが65文字ならE0303() throws Exception {
        MvcResult result = mvc.perform(change("cur", "a".repeat(65), "a".repeat(65)))
                .andExpect(view().name("common/account-password"))
                .andReturn();

        assertThat(codes(result, "newPassword")).containsExactly("Pattern");
    }

    @ParameterizedTest(name = "No.3 「{0}」を含むとE0303")
    @CsvSource({ "あいうえおかきくけこ", "pass word12", "ｐａｓｓｗｏｒｄ１２" })
    void No3_全角文字や空白を含むとE0303(String pw) throws Exception {
        MvcResult result = mvc.perform(change("cur", pw, pw))
                .andExpect(view().name("common/account-password"))
                .andReturn();

        assertThat(codes(result, "newPassword")).containsExactly("Pattern");
    }

    @Test
    void 入力エラーで再表示するときは3つの値をnullに戻す() throws Exception {
        MvcResult result = mvc.perform(change("cur", "short", "short"))
                .andExpect(view().name("common/account-password"))
                .andReturn();

        Object form = result.getModelAndView().getModel().get(FORM);
        assertThat(form).hasFieldOrPropertyWithValue("currentPassword", null)
                .hasFieldOrPropertyWithValue("newPassword", null)
                .hasFieldOrPropertyWithValue("confirmPassword", null);
    }

    // ---------------------------------------------------------------- No.4〜6 業務エラー

    @ParameterizedTest(name = "No.4〜6 {0} のときはフラッシュに入れて画面へ戻る")
    @CsvSource({ "E0305", "E0306", "E0307" })
    void 業務エラーはフラッシュに入れてパスワード変更画面へ戻る(String code) throws Exception {
        doThrow(new BusinessException(code)).when(userService)
                .changePassword(7L, "cur", "NewPassw0rd!", "NewPassw0rd!");

        mvc.perform(change("cur", "NewPassw0rd!", "NewPassw0rd!"))
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(flash().attribute("errorMessage", code));

        assertThat(loginUser.isMustChangePassword()).isTrue();
    }

    // ---------------------------------------------------------------- No.7 成功

    @ParameterizedTest(name = "No.7 {0} は {1} へ")
    @CsvSource({ "ADMIN,/admin/home", "MANAGER,/manager/home", "STAFF,/staff/home" })
    void No7_変更できたらI0301でホームへ移り変更必須が外れる(Role role, String home) throws Exception {
        login(role);

        mvc.perform(change("cur", "NewPassw0rd!", "NewPassw0rd!"))
                .andExpect(redirectedUrl(home))
                .andExpect(flash().attribute("successMessage", "I0301"));

        verify(userService).changePassword(7L, "cur", "NewPassw0rd!", "NewPassw0rd!");
        assertThat(loginUser.isMustChangePassword()).isFalse();
    }
}

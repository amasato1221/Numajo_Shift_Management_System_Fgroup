package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * パスワード変更の流れの結合テスト（20-SC-03 6章 No.4・6・7・8、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・UserService を使う。成功・業務エラーはリダイレクトだけを確かめる。
 * CSRF トークンなしの POST（94_セキュリティ設計書 6章 No.9）では、先に画面（HTML）を開くので、
 * layout/base.html の描画も通る。入力エラーの再表示は、AccountControllerTests で確かめる。
 * {@code @Transactional} は付けず、ログインID が「fp」で始まるデータを各テストの前後で消す。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class PasswordChangeFlowTests {

    private static final String OLD = "OldPassw0rd!";
    private static final String NEW = "NewPassw0rd!";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        User u = new User();
        u.setLoginId("fpstaff");
        u.setPasswordHash(passwordEncoder.encode(OLD));
        u.setName("テストfpstaff");
        u.setRole(Role.STAFF);
        u.setActive(true);
        u.setMustChangePassword(true);
        userRepository.save(u);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs WHERE target LIKE 'loginId:fp%' "
                + "OR user_id IN (SELECT id FROM users WHERE login_id LIKE 'fp%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fp%'");
    }

    private User find() {
        return userRepository.findByLoginId("fpstaff").orElseThrow();
    }

    // ---------------------------------------------------------------- No.7

    @Test
    void 初回の変更に成功する_ホームへ移り変更必須が外れてほかの画面を開ける() throws Exception {
        LoginUser loginUser = new LoginUser(find(), false);
        // 変更前は、ほかの画面を開いてもパスワード変更画面へ戻される
        mvc.perform(get("/staff/home").with(user(loginUser))).andExpect(redirectedUrl("/account/password"));

        mvc.perform(post("/account/password").with(csrf()).with(user(loginUser))
                .param("currentPassword", OLD).param("newPassword", NEW).param("confirmPassword", NEW))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(find().isMustChangePassword()).isFalse();
        assertThat(loginUser.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches(NEW, find().getPasswordHash())).isTrue();
        Long logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_logs WHERE action = 'PASSWORD_CHANGE' "
                        + "AND user_id = (SELECT id FROM users WHERE login_id = 'fpstaff')", Long.class);
        assertThat(logs).isEqualTo(1);
        // 変更後は、同じ利用者がほかの画面を開いてもパスワード変更画面へ戻されない
        String redirected = mvc.perform(get("/staff/home").with(user(loginUser)))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirected).isNull();
    }

    // ---------------------------------------------------------------- No.8

    @Test
    void 変更後にログインする_新しいパスワードで入れ古いパスワードでは入れない() throws Exception {
        LoginUser loginUser = new LoginUser(find(), false);
        mvc.perform(post("/account/password").with(csrf()).with(user(loginUser))
                .param("currentPassword", OLD).param("newPassword", NEW).param("confirmPassword", NEW))
                .andExpect(redirectedUrl("/staff/home"));

        mvc.perform(post("/login").with(csrf()).param("loginId", "fpstaff").param("password", OLD))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("loginId", "fpstaff").param("password", NEW))
                .andExpect(redirectedUrl("/staff/home"));
    }

    // ---------------------------------------------------------------- CSRF

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        LoginUser loginUser = new LoginUser(find(), false);
        String before = find().getPasswordHash();
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/account/password").with(user(loginUser)).session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/account/password").with(user(loginUser)).session(session)
                .param("currentPassword", OLD).param("newPassword", NEW).param("confirmPassword", NEW))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(find().getPasswordHash()).isEqualTo(before);
        assertThat(find().isMustChangePassword()).isTrue();
    }

    // ---------------------------------------------------------------- No.4・6 業務エラーでDBは変わらない

    @Test
    void 確認用が一致しない_DBは変わらずパスワード変更画面へ戻る() throws Exception {
        LoginUser loginUser = new LoginUser(find(), false);
        String before = find().getPasswordHash();

        mvc.perform(post("/account/password").with(csrf()).with(user(loginUser))
                .param("currentPassword", OLD).param("newPassword", NEW).param("confirmPassword", NEW + "x"))
                .andExpect(redirectedUrl("/account/password"));

        assertThat(find().getPasswordHash()).isEqualTo(before);
        assertThat(find().isMustChangePassword()).isTrue();
    }

    @Test
    void 新しいパスワードが今と同じ_DBは変わらずパスワード変更画面へ戻る() throws Exception {
        LoginUser loginUser = new LoginUser(find(), false);
        String before = find().getPasswordHash();

        mvc.perform(post("/account/password").with(csrf()).with(user(loginUser))
                .param("currentPassword", OLD).param("newPassword", OLD).param("confirmPassword", OLD))
                .andExpect(redirectedUrl("/account/password"));

        assertThat(find().getPasswordHash()).isEqualTo(before);
    }
}

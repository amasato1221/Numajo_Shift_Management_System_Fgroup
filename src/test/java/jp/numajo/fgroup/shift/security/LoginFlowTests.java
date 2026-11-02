package jp.numajo.fgroup.shift.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * ログインの流れの結合テスト（94_セキュリティ設計書 6章 No.1〜8、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と、本物の SecurityConfig を使い、MockMvc で画面の動きを確かめる。
 * 画面（HTML）は表示せず、リダイレクト先と操作ログを確かめる。
 * このクラスの {@code @Transactional} は付けない（失敗ハンドラーが別トランザクションで操作ログを残すため）。
 * かわりに、各テストの前後で「ft」で始まるログインIDのデータを消す。
 * </p>
 *
 * <pre>
 * No.（94 の6章） | このクラスのテスト
 *  1 | 未ログインで /staff/home → /login
 *  2 | バイトで /manager/home・/admin/home → /staff/home
 *  3 | 店長で /staff/home、管理者で /manager/home → 自分のホーム
 *  4 | 4回目まで error、5回目で locked、LOGIN_LOCKED が1件
 *  5 | ロック中は正しいパスワードでも入れない（LOGIN_FAILURE）／解除時刻を過ぎたら入れる
 *  6 | 存在しない ID・無効なアカウント → error
 *  7 | 初回ログイン → /account/password、ほかの画面も /account/password
 *  8 | ログイン中に無効にされる → /login?disabled（W0102 が画面に出るかはブラウザで手で確かめる）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class LoginFlowTests {

    private static final String PASSWORD = "Passw0rd!x";
    private static final String WRONG = "wrong-password";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        createUser("ftstaff", Role.STAFF, true, false);
        createUser("ftmgr", Role.MANAGER, true, false);
        createUser("ftadmin", Role.ADMIN, true, false);
        createUser("ftmust", Role.STAFF, true, true);
        createUser("ftoff", Role.STAFF, false, false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ---------------------------------------------------------------- 部品

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs WHERE target LIKE 'loginId:ft%' "
                + "OR user_id IN (SELECT id FROM users WHERE login_id LIKE 'ft%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'ft%'");
    }

    private void createUser(String loginId, Role role, boolean active, boolean mustChange) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(mustChange);
        userRepository.save(u);
    }

    private User find(String loginId) {
        return userRepository.findByLoginId(loginId).orElseThrow();
    }

    private LoginUser loginUser(String loginId) {
        return new LoginUser(find(loginId), false);
    }

    private MockHttpServletRequestBuilder login(String loginId, String password) {
        return post("/login").with(csrf()).param("loginId", loginId).param("password", password);
    }

    private long count(String action, String target) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_logs WHERE action = ? AND target = ?", Long.class, action, target);
        return n == null ? 0 : n;
    }

    private long countByUser(String action, String loginId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_logs WHERE action = ? "
                        + "AND user_id = (SELECT id FROM users WHERE login_id = ?)",
                Long.class, action, loginId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- No.1〜3 権限

    @Test
    void No1_未ログインで保護された画面を開くとログイン画面へ() throws Exception {
        mvc.perform(get("/staff/home"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void No2_バイトが店長と管理者の画面を開くと自分のホームへ() throws Exception {
        LoginUser staff = loginUser("ftstaff");

        mvc.perform(get("/manager/home").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(get("/admin/home").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
    }

    @Test
    void No3_店長と管理者は権限のない画面から自分のホームへ() throws Exception {
        mvc.perform(get("/staff/home").with(user(loginUser("ftmgr")))).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(get("/manager/home").with(user(loginUser("ftadmin")))).andExpect(redirectedUrl("/admin/home"));
    }

    // ---------------------------------------------------------------- ログイン成功・ログアウト

    @Test
    void ログイン成功_ホームへ移り操作ログが残り失敗回数が戻る() throws Exception {
        mvc.perform(login("ftstaff", WRONG)).andExpect(redirectedUrl("/login?error"));
        mvc.perform(login("ftstaff", WRONG)).andExpect(redirectedUrl("/login?error"));
        assertThat(find("ftstaff").getFailedLoginCount()).isEqualTo(2);

        mvc.perform(login("ftstaff", PASSWORD)).andExpect(redirectedUrl("/staff/home"));

        assertThat(find("ftstaff").getFailedLoginCount()).isZero();
        assertThat(countByUser("LOGIN_SUCCESS", "ftstaff")).isEqualTo(1);
    }

    @Test
    void ログイン成功_店長と管理者はそれぞれのホームへ() throws Exception {
        mvc.perform(login("ftmgr", PASSWORD)).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(login("ftadmin", PASSWORD)).andExpect(redirectedUrl("/admin/home"));
    }

    @Test
    void ログアウト_ログイン画面へ戻り操作ログが残る() throws Exception {
        mvc.perform(post("/logout").with(csrf()).with(user(loginUser("ftstaff"))))
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(countByUser("LOGOUT", "ftstaff")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- No.4 ロック

    @Test
    void No4_4回目まではerror_5回目でlocked_LOGIN_LOCKEDが1件() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mvc.perform(login("ftstaff", WRONG)).andExpect(redirectedUrl("/login?error"));
        }
        assertThat(count("LOGIN_LOCKED", "loginId:ftstaff")).isZero();

        mvc.perform(login("ftstaff", WRONG)).andExpect(redirectedUrl("/login?locked"));

        assertThat(count("LOGIN_FAILURE", "loginId:ftstaff")).isEqualTo(4);
        assertThat(count("LOGIN_LOCKED", "loginId:ftstaff")).isEqualTo(1);
        assertThat(find("ftstaff").getLockedUntil()).isNotNull();
        assertThat(find("ftstaff").getFailedLoginCount()).isZero();
    }

    // ---------------------------------------------------------------- No.5 ロック中

    @Test
    void No5_ロック中は正しいパスワードでも入れずLOGIN_FAILUREで残る() throws Exception {
        User u = find("ftstaff");
        u.setLockedUntil(LocalDateTime.now(TOKYO).plusMinutes(10));
        userRepository.save(u);

        mvc.perform(login("ftstaff", PASSWORD)).andExpect(redirectedUrl("/login?locked"));

        assertThat(count("LOGIN_FAILURE", "loginId:ftstaff")).isEqualTo(1);
        assertThat(count("LOGIN_LOCKED", "loginId:ftstaff")).isZero();
        assertThat(countByUser("LOGIN_SUCCESS", "ftstaff")).isZero();
    }

    @Test
    void No5_解除時刻を過ぎたら正しいパスワードで入れてロックが消える() throws Exception {
        User u = find("ftstaff");
        u.setLockedUntil(LocalDateTime.now(TOKYO).minusMinutes(1));
        userRepository.save(u);

        mvc.perform(login("ftstaff", PASSWORD)).andExpect(redirectedUrl("/staff/home"));

        assertThat(find("ftstaff").getLockedUntil()).isNull();
    }

    // ---------------------------------------------------------------- No.6 存在しない・無効

    @Test
    void No6_存在しないIDはerrorで利用者なしのログが残る() throws Exception {
        mvc.perform(login("ftnone", PASSWORD)).andExpect(redirectedUrl("/login?error"));

        assertThat(count("LOGIN_FAILURE", "loginId:ftnone")).isEqualTo(1);
    }

    @Test
    void No6_無効なアカウントは正しいパスワードでもerror() throws Exception {
        mvc.perform(login("ftoff", PASSWORD)).andExpect(redirectedUrl("/login?error"));

        assertThat(countByUser("LOGIN_SUCCESS", "ftoff")).isZero();
    }

    // ---------------------------------------------------------------- No.7 初回ログイン

    @Test
    void No7_初回ログインはパスワード変更画面へ() throws Exception {
        mvc.perform(login("ftmust", PASSWORD)).andExpect(redirectedUrl("/account/password"));
    }

    @Test
    void No7_変更前はほかの画面を開いてもパスワード変更画面へ戻る() throws Exception {
        mvc.perform(get("/staff/home").with(user(loginUser("ftmust")))).andExpect(redirectedUrl("/account/password"));
    }

    // ---------------------------------------------------------------- No.8 無効にされた

    @Test
    void No8_ログイン中に無効にされたら次の操作でdisabledへ() throws Exception {
        LoginUser staff = loginUser("ftstaff");
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE login_id = 'ftstaff'");

        mvc.perform(get("/staff/home").with(user(staff))).andExpect(redirectedUrl("/login?disabled"));
    }

    @Test
    void No8_有効なままなら無効扱いにされない() throws Exception {
        String redirected = mvc.perform(get("/staff/home").with(user(loginUser("ftstaff"))))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirected).isNull();
    }

    // ---------------------------------------------------------------- SC-01 No.9・ルート

    @Test
    void SC01_No9_ログイン済みでログイン画面を開くとホームへ() throws Exception {
        mvc.perform(get("/login").with(user(loginUser("ftstaff")))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(get("/login").with(user(loginUser("ftmgr")))).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(get("/login").with(user(loginUser("ftadmin")))).andExpect(redirectedUrl("/admin/home"));
    }

    @Test
    void ルート_未ログインで開くとログイン画面へ() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void ルート_ログイン済みで開くとそれぞれのホームへ() throws Exception {
        mvc.perform(get("/").with(user(loginUser("ftstaff")))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(get("/").with(user(loginUser("ftmgr")))).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(get("/").with(user(loginUser("ftadmin")))).andExpect(redirectedUrl("/admin/home"));
    }
}

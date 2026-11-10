package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * スタッフ登録・編集の画面の描画・権限・CSRF の結合テスト
 * （20-SM-03、94_セキュリティ設計書 6章 No.9、97_テスト計画書 3.3・4.2、92 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・StaffService を使う。{@code @Transactional} は付けず、
 * ログインID が「fq」で始まるデータを各テストの前後で消す。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerStaffFlowTests {

    private static final String OLD_HASH = "fq-old-hash";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser manager;
    private LoginUser staff;
    private User target;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        manager = new LoginUser(save("fqmanager", Role.MANAGER, "テストfqmanager"), false);
        staff = new LoginUser(save("fqstaff", Role.STAFF, "テストfqstaff"), false);
        target = save("fqtarget", Role.STAFF, "対象 太郎");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private User save(String loginId, Role role, String name) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(role == Role.STAFF && loginId.equals("fqtarget") ? OLD_HASH
                : passwordEncoder.encode("Passw0rd!Passw0rd!"));
        u.setName(name);
        u.setNameKana("たいしょう たろう");
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'fq%')");
        jdbcTemplate.update("DELETE FROM user_skills "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'fq%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fq%'");
    }

    private long countByLoginId(String loginId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private String targetName() {
        return jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = ?", String.class, target.getId());
    }

    private String targetHash() {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class,
                target.getId());
    }

    private String staffPath() {
        return "/manager/staff/" + target.getId();
    }

    // ---------------------------------------------------------------- 画面の描画（92 4.2）

    @Test
    void 登録画面を開く_入力欄と職能と保存が描かれ状態の欄はない() throws Exception {
        String html = mvc.perform(get("/manager/staff/new").with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>スタッフの登録｜シフト管理システム</title>");
        assertThat(html).contains("name=\"name\"").contains("name=\"nameKana\"").contains("name=\"loginId\"");
        assertThat(html).contains("職能").contains("研修中").contains("保存");
        assertThat(html).contains("action=\"/manager/staff\"");
        assertThat(html).contains("name=\"_csrf\"");
        assertThat(html).doesNotContain("id=\"activeTrue\"").doesNotContain("reset-password");
    }

    @Test
    void 編集画面を開く_今の値と状態の欄とパスワード初期化が描かれる() throws Exception {
        String html = mvc.perform(get(staffPath()).with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>スタッフの編集｜シフト管理システム</title>");
        assertThat(html).contains("value=\"対象 太郎\"").contains("value=\"fqtarget\"");
        assertThat(html).contains("id=\"activeTrue\"").contains("id=\"activeFalse\"");
        assertThat(html).contains("action=\"" + staffPath() + "\"");
        assertThat(html).contains(staffPath() + "/reset-password").contains("パスワード初期化");
        assertThat(html).doesNotContain("ロック中").doesNotContain("初期パスワードは");
    }

    @Test
    void ロック中のスタッフの編集画面_ロック中と解除の時刻が描かれる() throws Exception {
        LocalDateTime lockedUntil = LocalDateTime.now().plusHours(1);
        jdbcTemplate.update("UPDATE users SET locked_until = ? WHERE id = ?", lockedUntil, target.getId());

        String html = mvc.perform(get(staffPath()).with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("ロック中（" + DateTimeUtil.time(lockedUntil.toLocalTime()) + " まで）");
    }

    @Test
    void 初期パスワードがあるとき_編集画面に初期パスワードのアラートが描かれる() throws Exception {
        String html = mvc.perform(get(staffPath()).with(user(manager)).flashAttr("initialPassword", "Ab3dEf7hJk"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("alert-success").contains("初期パスワードは「Ab3dEf7hJk」です。");
    }

    // ---------------------------------------------------------------- 権限（97 3.3）

    @Test
    void バイトが開く_自分のホームへ戻され登録も更新も初期化もされない() throws Exception {
        mvc.perform(get("/manager/staff/new").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(get(staffPath()).with(user(staff))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post("/manager/staff").with(csrf()).with(user(staff))
                .param("name", "新規 花子").param("nameKana", "しんき はなこ").param("loginId", "fqnew"))
                .andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post(staffPath()).with(csrf()).with(user(staff))
                .param("name", "変更後").param("nameKana", "へんこう").param("loginId", "fqtarget")
                .param("active", "true"))
                .andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post(staffPath() + "/reset-password").with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(countByLoginId("fqnew")).isZero();
        assertThat(targetName()).isEqualTo("対象 太郎");
        assertThat(targetHash()).isEqualTo(OLD_HASH);
    }

    // ---------------------------------------------------------------- CSRF（94 6章 No.9）

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get(staffPath()).with(user(manager)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/manager/staff").with(user(manager)).session(session)
                .param("name", "新規 花子").param("nameKana", "しんき はなこ").param("loginId", "fqnew"))
                .andExpect(redirectedUrl("/manager/home"));
        mvc.perform(post(staffPath()).with(user(manager)).session(session)
                .param("name", "変更後").param("nameKana", "へんこう").param("loginId", "fqtarget")
                .param("active", "true"))
                .andExpect(redirectedUrl("/manager/home"));
        mvc.perform(post(staffPath() + "/reset-password").with(user(manager)).session(session))
                .andExpect(redirectedUrl("/manager/home"));

        assertThat(countByLoginId("fqnew")).isZero();
        assertThat(targetName()).isEqualTo("対象 太郎");
        assertThat(targetHash()).isEqualTo(OLD_HASH);
    }
}

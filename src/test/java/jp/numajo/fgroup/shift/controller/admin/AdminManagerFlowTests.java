package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 店長アカウント管理（SA-02）の描画・権限・CSRF・DB の結合テスト
 * （20-SA-02 6章、94_セキュリティ設計書 6章、97_テスト計画書 3.3・4.2、92 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・ManagerAccountService を使う。{@code @Transactional} は付けず、
 * ログインID が「fam」で始まるデータを各テストの前後で消す。
 * </p>
 *
 * <pre>
 * 20-SA-02 6章の観点との対応
 *  1 | 作成 → 作成と初期パスワードを1回だけ出す・操作ログ
 *  2 | バイトのログインIDと同じ → E4207
 *  3 | ふりがな空で作成 → 作成できる
 *  4 | 停止 → ログインできない・ログイン中なら次の操作でログイン画面へ
 *  5 | 停止中を停止・有効を再開 → E4208・E4209
 *  6 | バイト・管理者のIDで停止・再開・初期化 → 404
 *  7 | ロック中の店長を初期化 → ロック解除
 *  8 | 有効な店長が1人だけ → 停止の確認が C4203
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class AdminManagerFlowTests {

    private static final String PASSWORD = "Passw0rd!Passw0rd!";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private User adminUser;
    private LoginUser admin;
    private LoginUser staff;
    private User target;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        adminUser = save("famadmin", Role.ADMIN, "テスト管理者", true);
        admin = new LoginUser(adminUser, false);
        staff = new LoginUser(save("famstaff", Role.STAFF, "テストバイト", true), false);
        target = save("famtarget", Role.MANAGER, "対象 店長", true);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private User save(String loginId, Role role, String name, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setName(name);
        u.setNameKana("たいしょう");
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'fam%') "
                + "OR target LIKE 'loginId:fam%'");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fam%'");
    }

    private boolean isActive(Long id) {
        return jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, id);
    }

    private long countLogs(String action, Long targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_logs WHERE action = ? AND target = ? AND user_id = ?",
                Long.class, action, "user:" + targetId, adminUser.getId());
    }

    private String path(Long id, String action) {
        return "/admin/managers/" + id + "/" + action;
    }

    // ---------------------------------------------------------------- 画面の描画（92 4.2）

    @Test
    void 一覧を開く_一覧と作成フォームと操作ボタンが描かれる() throws Exception {
        String html = mvc.perform(get("/admin/managers").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>店長アカウント管理｜シフト管理システム</title>");
        assertThat(html).contains("対象 店長").contains("famtarget").contains("有効");
        assertThat(html).contains(path(target.getId(), "stop")).contains(path(target.getId(), "reset-password"));
        assertThat(html).doesNotContain(path(target.getId(), "resume"));
        assertThat(html).contains("action=\"/admin/managers\"").contains("name=\"name\"")
                .contains("name=\"nameKana\"").contains("name=\"loginId\"");
        assertThat(html).contains("大文字・小文字は区別しません");
        assertThat(html).contains("name=\"_csrf\"");
        assertThat(html).contains("対象 店長さんのパスワードを初期化します。よろしいですか？");
        assertThat(html).doesNotContain("初期パスワードは");
    }

    @Test
    void 停止中の人には再開が出て停止は出ない_ロック中の人にはロック中が出る() throws Exception {
        User stopped = save("famstopped", Role.MANAGER, "停止 店長", false);
        target.setLockedUntil(LocalDateTime.now(TOKYO).plusMinutes(10));
        userRepository.save(target);

        String html = mvc.perform(get("/admin/managers").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(path(stopped.getId(), "resume")).doesNotContain(path(stopped.getId(), "stop"));
        assertThat(html).contains("停止").contains("ロック中");
    }

    // ---------------------------------------------------------------- 6章 No.8 最後の店長の確認文

    @Test
    void No8_有効な店長が1人だけなら停止の確認がC4203() throws Exception {
        assumeTrue(userRepository.countByRoleAndActive(Role.MANAGER, true) == 1, "DB に他の有効な店長がいる");

        String html = mvc.perform(get("/admin/managers").with(user(admin)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("停止すると、有効な店長がいなくなります。本当に停止しますか？")
                .doesNotContain("対象 店長さんのアカウントを停止します。");
    }

    @Test
    void No8_有効な店長が2人ならC4201() throws Exception {
        save("famsecond", Role.MANAGER, "二人目 店長", true);

        String html = mvc.perform(get("/admin/managers").with(user(admin)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("対象 店長さんのアカウントを停止します。よろしいですか？")
                .doesNotContain("有効な店長がいなくなります");
    }

    // ---------------------------------------------------------------- 6章 No.1・No.3 作成

    @Test
    void No1_No3_ふりがな空で作成でき_初期パスワードは次の1回だけ出て操作ログが残る() throws Exception {
        MvcResult result = mvc.perform(post("/admin/managers").with(csrf()).with(user(admin))
                .param("name", "新規 店長").param("nameKana", "").param("loginId", "famnew"))
                .andExpect(redirectedUrl("/admin/managers"))
                .andReturn();

        User created = userRepository.findByLoginId("famnew").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.MANAGER);
        assertThat(created.getNameKana()).isNull();
        assertThat(created.isMustChangePassword()).isTrue();
        String password = (String) result.getFlashMap().get("initialPassword");
        assertThat(passwordEncoder.matches(password, created.getPasswordHash())).isTrue();
        assertThat(countLogs("MANAGER_CREATE", created.getId())).isEqualTo(1);

        // リダイレクト先（フラッシュあり）では初期パスワードが出る
        String html = mvc.perform(get("/admin/managers").with(user(admin)).flashAttrs(result.getFlashMap()))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("新規 店長さんの初期パスワードは「" + password + "」です。")
                .contains("店長アカウントを作成しました。");
        // リロード（フラッシュなし）では出ない
        String reloaded = mvc.perform(get("/admin/managers").with(user(admin)))
                .andReturn().getResponse().getContentAsString();
        assertThat(reloaded).doesNotContain(password);
    }

    @Test
    void No2_バイトのログインIDと同じなら大文字小文字が違ってもE4207で入力を残して再表示() throws Exception {
        String html = mvc.perform(post("/admin/managers").with(csrf()).with(user(admin))
                .param("name", "重複 店長").param("nameKana", "").param("loginId", "FAMSTAFF"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("このログインIDはすでに使われています。").contains("value=\"重複 店長\"");
        assertThat(userRepository.findByRoleForAdmin(Role.MANAGER))
                .extracting(User::getName).doesNotContain("重複 店長");
    }

    @Test
    void 入力チェックのエラーなら再表示して保存しない() throws Exception {
        String html = mvc.perform(post("/admin/managers").with(csrf()).with(user(admin))
                .param("name", "").param("nameKana", "カナ").param("loginId", "fam"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("氏名を入力してください。").contains("ふりがなは、ひらがなで入力してください。")
                .contains("ログインIDは半角英数字4〜20文字で入力してください。").contains("対象 店長");
        assertThat(userRepository.findByLoginId("fam")).isEmpty();
    }

    // ---------------------------------------------------------------- 6章 No.4・No.5 停止・再開

    @Test
    void No4_停止するとログイン中の次の操作でdisabledへ_ログインもできない_再開すると入れる() throws Exception {
        LoginUser targetLogin = new LoginUser(target, false);

        mvc.perform(post(path(target.getId(), "stop")).with(csrf()).with(user(admin)))
                .andExpect(redirectedUrl("/admin/managers"));

        assertThat(isActive(target.getId())).isFalse();
        assertThat(countLogs("MANAGER_STOP", target.getId())).isEqualTo(1);
        mvc.perform(get("/manager/home").with(user(targetLogin))).andExpect(redirectedUrl("/login?disabled"));
        mvc.perform(post("/login").with(csrf()).param("loginId", "famtarget").param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));

        mvc.perform(post(path(target.getId(), "resume")).with(csrf()).with(user(admin)))
                .andExpect(redirectedUrl("/admin/managers"));

        assertThat(isActive(target.getId())).isTrue();
        assertThat(countLogs("MANAGER_RESUME", target.getId())).isEqualTo(1);
        mvc.perform(post("/login").with(csrf()).param("loginId", "famtarget").param("password", PASSWORD))
                .andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void No4_停止の完了メッセージに氏名が入る() throws Exception {
        MvcResult result = mvc.perform(post(path(target.getId(), "stop")).with(csrf()).with(user(admin)))
                .andReturn();

        String html = mvc.perform(get("/admin/managers").with(user(admin)).flashAttrs(result.getFlashMap()))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("対象 店長さんのアカウントを停止しました。");
    }

    @Test
    void No5_停止中を停止するとE4208_有効を再開するとE4209() throws Exception {
        mvc.perform(post(path(target.getId(), "resume")).with(csrf()).with(user(admin)))
                .andExpect(redirectedUrl("/admin/managers"))
                .andExpect(result -> assertThat(result.getFlashMap().get("errorMessage")).isEqualTo("E4209"));

        mvc.perform(post(path(target.getId(), "stop")).with(csrf()).with(user(admin)));
        mvc.perform(post(path(target.getId(), "stop")).with(csrf()).with(user(admin)))
                .andExpect(redirectedUrl("/admin/managers"))
                .andExpect(result -> assertThat(result.getFlashMap().get("errorMessage")).isEqualTo("E4208"));

        assertThat(countLogs("MANAGER_STOP", target.getId())).isEqualTo(1);
        assertThat(countLogs("MANAGER_RESUME", target.getId())).isZero();
    }

    // ---------------------------------------------------------------- 6章 No.6 店長でない人

    @Test
    void No6_バイトと管理者のIDで停止_再開_初期化はどれも404() throws Exception {
        for (Long id : new Long[] { staff.getUserId(), adminUser.getId(), 999_999_999L }) {
            for (String action : new String[] { "stop", "resume", "reset-password" }) {
                mvc.perform(post(path(id, action)).with(csrf()).with(user(admin)))
                        .andExpect(status().isNotFound());
            }
        }
        assertThat(isActive(staff.getUserId())).isTrue();
        assertThat(isActive(adminUser.getId())).isTrue();
    }

    // ---------------------------------------------------------------- 6章 No.7 初期化

    @Test
    void No7_ロック中の店長を初期化するとロックが解け新しいパスワードで入れる() throws Exception {
        target.setLockedUntil(LocalDateTime.now(TOKYO).plusMinutes(10));
        target.setFailedLoginCount(5);
        userRepository.save(target);

        MvcResult result = mvc.perform(post(path(target.getId(), "reset-password")).with(csrf()).with(user(admin)))
                .andExpect(redirectedUrl("/admin/managers"))
                .andReturn();

        User after = userRepository.findById(target.getId()).orElseThrow();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedLoginCount()).isZero();
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(countLogs("MANAGER_PASSWORD_RESET", target.getId())).isEqualTo(1);
        String password = (String) result.getFlashMap().get("initialPassword");
        assertThat(result.getFlashMap().get("initialPasswordName")).isEqualTo("対象 店長");
        assertThat(passwordEncoder.matches(password, after.getPasswordHash())).isTrue();

        String html = mvc.perform(get("/admin/managers").with(user(admin)).flashAttrs(result.getFlashMap()))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("対象 店長さんの初期パスワードは「" + password + "」です。")
                .contains("パスワードを初期化しました。");
    }

    // ---------------------------------------------------------------- 権限（94 6章）

    @Test
    void 店長とバイトは開けず自分のホームへ戻る() throws Exception {
        LoginUser manager = new LoginUser(target, false);
        mvc.perform(get("/admin/managers").with(user(manager))).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(get("/admin/managers").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post("/admin/managers").with(csrf()).with(user(manager))
                .param("name", "x").param("loginId", "famxxxx"))
                .andExpect(redirectedUrl("/manager/home"));
        mvc.perform(post(path(target.getId(), "stop")).with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(userRepository.findByLoginId("famxxxx")).isEmpty();
        assertThat(isActive(target.getId())).isTrue();
    }

    @Test
    void ログインしていなければログイン画面へ() throws Exception {
        mvc.perform(get("/admin/managers")).andExpect(redirectedUrl("/login"));
    }

    // ---------------------------------------------------------------- CSRF（94 6章 No.9）

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/admin/managers").with(user(admin)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/managers").with(user(admin)).session(session)
                .param("name", "新規").param("loginId", "famnew"))
                .andExpect(redirectedUrl("/admin/home"));
        mvc.perform(post(path(target.getId(), "stop")).with(user(admin)).session(session))
                .andExpect(redirectedUrl("/admin/home"));
        mvc.perform(post(path(target.getId(), "reset-password")).with(user(admin)).session(session))
                .andExpect(redirectedUrl("/admin/home"));

        assertThat(userRepository.findByLoginId("famnew")).isEmpty();
        assertThat(isActive(target.getId())).isTrue();
        assertThat(countLogs("MANAGER_PASSWORD_RESET", target.getId())).isZero();
    }
}

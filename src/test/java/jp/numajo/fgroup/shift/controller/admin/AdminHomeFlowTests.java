package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ManagerCountView;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SA-01 管理メニューの結合テスト（20-SA-01 6章 No.1〜5、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service・テンプレート（admin/home.html・layout/base.html）を使う。
 * テストごとにロールバックする。ほかのテストや初期データの影響を受けないよう、最初に店長・操作ログ・
 * バックアップを消す（ロールバックで戻る）。今の日時は本物の Clock なので、No.4 は今から1時間ずつ余裕を
 * 持たせた日時で入れる。168時間ちょうどの境目は AdminHomeControllerTests で確かめている。
 * </p>
 *
 * <pre>
 * No. | 状況                                        | 期待結果
 *  1  | 店長が有効2・停止1                             | 「有効 2人／停止 1人」
 *  2  | 操作ログが6件                                  | 新しい5件だけ、新しい順
 *  3  | バックアップなし                                | W4101
 *  4  | 最新が6日23時間前の成功／7日1時間前の成功／1時間前の失敗 | 警告なし／W4101／W4101
 *  5  | 店長でURLを開く                                 | SM-01 へリダイレクト
 * ほか | 操作ログが0件                                   | I4101
 * ほか | バイトでURLを開く                               | SB-01 へリダイレクト
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class AdminHomeFlowTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final String W4101 = "最近7日間に成功したバックアップがありません。";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser admin;
    private Long adminId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("DELETE FROM operation_logs");
        jdbcTemplate.update("DELETE FROM backups");
        jdbcTemplate.update("UPDATE users SET role = 'STAFF' WHERE role = 'MANAGER'");
        User u = saveUser("fadmin", Role.ADMIN, true);
        adminId = u.getId();
        admin = new LoginUser(u, false);
    }

    // ---------------------------------------------------------------- 準備

    private User saveUser(String loginId, Role role, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void log(String action, String createdAt) {
        jdbcTemplate.update("INSERT INTO operation_logs (user_id, action, created_at) VALUES (?, ?, ?)",
                adminId, action, createdAt);
    }

    private void backup(String status, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO backups (file_name, file_size, status, created_by, created_at) "
                + "VALUES ('shift.sql', 1024, ?, ?, ?)", status, adminId, createdAt);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(TOKYO).withNano(0);
    }

    private MvcResult open() throws Exception {
        return mvc.perform(get("/admin/home").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static Object attr(MvcResult result, String name) {
        return result.getModelAndView().getModel().get(name);
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 店長アカウント

    @Test
    void No1_店長が有効2人と停止1人_有効2人停止1人と出る() throws Exception {
        saveUser("fmgr1", Role.MANAGER, true);
        saveUser("fmgr2", Role.MANAGER, true);
        saveUser("fmgr3", Role.MANAGER, false);
        saveUser("fstaff", Role.STAFF, false); // バイトは数えない

        MvcResult result = open();

        assertThat(attr(result, "managerCount")).isEqualTo(new ManagerCountView(2, 1));
        assertThat(html(result)).contains("有効 2人／停止 1人", "href=\"/admin/managers\"");
    }

    // ---------------------------------------------------------------- 最近の操作

    @Test
    @SuppressWarnings("unchecked")
    void No2_操作ログが6件_新しい5件だけ新しい順に出る() throws Exception {
        log("LOGIN_SUCCESS", "2099-01-01 09:00:00");
        log("LOGOUT", "2099-01-02 09:00:00");
        log("STAFF_CREATE", "2099-01-03 09:00:00");
        log("SKILL_CREATE", "2099-01-04 09:00:00");
        log("BACKUP_CREATE", "2099-01-05 09:00:00");
        log("SHIFT_PUBLISH", "2099-01-06 18:30:00");

        MvcResult result = open();

        List<OperationLogRow> logs = (List<OperationLogRow>) attr(result, "recentLogs");
        assertThat(logs).extracting(OperationLogRow::actionLabel)
                .containsExactly("シフトの公開", "バックアップ作成", "職能の追加", "スタッフの登録", "ログアウト");
        assertThat(html(result)).contains("2099/01/06 18:30:00", "テストfadmin（シフトの公開）",
                "href=\"/admin/logs\"").doesNotContain("（ログイン）", "操作ログはまだありません。");
    }

    @Test
    void 操作ログが0件_I4101() throws Exception {
        MvcResult result = open();

        assertThat(html(result)).contains("操作ログはまだありません。");
    }

    // ---------------------------------------------------------------- バックアップ

    @Test
    void No3_バックアップなし_W4101で最後のバックアップは出ない() throws Exception {
        MvcResult result = open();

        assertThat(attr(result, "backupWarning")).isEqualTo(true);
        assertThat(html(result)).contains(W4101, "href=\"/admin/backups\"").doesNotContain("最後のバックアップ");
    }

    @Test
    void No4_6日23時間前の成功_警告なし() throws Exception {
        LocalDateTime at = now().minusDays(6).minusHours(23);
        backup("FAILED", at.minusDays(1)); // 古い失敗は見ない
        backup("SUCCESS", at);

        MvcResult result = open();

        assertThat(attr(result, "backupWarning")).isEqualTo(false);
        assertThat(html(result)).contains("最後のバックアップ：" + at.toString().substring(0, 10).replace('-', '/'))
                .contains("成功").doesNotContain(W4101);
    }

    @Test
    void No4_7日1時間前の成功_W4101() throws Exception {
        backup("SUCCESS", now().minusDays(7).minusHours(1));

        MvcResult result = open();

        assertThat(attr(result, "backupWarning")).isEqualTo(true);
        assertThat(html(result)).contains(W4101);
    }

    @Test
    void No4_1時間前の失敗_W4101で失敗と出る() throws Exception {
        backup("SUCCESS", now().minusDays(1)); // 前に成功があっても最新が失敗なら警告
        backup("FAILED", now().minusHours(1));

        MvcResult result = open();

        assertThat(attr(result, "backupWarning")).isEqualTo(true);
        assertThat(html(result)).contains(W4101, "失敗");
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void No5_店長で開く_店長のホームへ戻す() throws Exception {
        LoginUser manager = new LoginUser(saveUser("fmgr", Role.MANAGER, true), false);

        mvc.perform(get("/admin/home").with(user(manager))).andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void バイトで開く_バイトのホームへ戻す() throws Exception {
        LoginUser staff = new LoginUser(saveUser("fstaff", Role.STAFF, true), false);

        mvc.perform(get("/admin/home").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
    }
}

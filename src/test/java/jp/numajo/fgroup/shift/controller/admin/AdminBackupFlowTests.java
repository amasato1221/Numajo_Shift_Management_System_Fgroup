package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ProcessResult;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.util.ProcessRunner;
import lombok.RequiredArgsConstructor;

/**
 * バックアップの流れの結合テスト（20-SA-04 6章 No.1〜3・5・8、94_セキュリティ設計書、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・BackupService・BackupRecorder を使い、画面（HTML）の描画も通す。
 * mysqldump は動かさず、ProcessRunner をモックにしてファイルを書く。
 * {@code @Transactional} は付けず、ログインID が「fa4」で始まるデータと、その人が作った backups・操作ログを、各テストの前後で消す。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class AdminBackupFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties props;

    @MockitoBean
    private ProcessRunner processRunner;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        save("fa4admin", Role.ADMIN);
        save("fa4manager", Role.MANAGER);
        save("fa4staff", Role.STAFF);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        String users = "(SELECT id FROM (SELECT id FROM users WHERE login_id LIKE 'fa4%') t)";
        jdbcTemplate.update("DELETE FROM operation_logs WHERE user_id IN " + users);
        jdbcTemplate.update("DELETE FROM backups WHERE created_by IN " + users);
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fa4%'");
    }

    private void save(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("dummy");
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        userRepository.save(u);
    }

    private LoginUser login(String loginId) {
        return new LoginUser(userRepository.findByLoginId(loginId).orElseThrow(), false);
    }

    private String page() throws Exception {
        return mvc.perform(get("/admin/backups").with(user(login("fa4admin"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** mysqldump の代わり：--result-file のファイルに content を書き（null なら書かない）、結果を返す。 */
    @SuppressWarnings("unchecked")
    private void dumpWrites(String content, ProcessResult result) {
        when(processRunner.run(any(), any(), any())).thenAnswer(inv -> {
            List<String> command = inv.getArgument(0);
            if (content != null) {
                String arg = command.stream().filter(a -> a.startsWith("--result-file=")).findFirst().orElseThrow();
                Files.writeString(Path.of(arg.substring("--result-file=".length())), content);
            }
            return result;
        });
    }

    private Map<String, Object> lastBackup() {
        return jdbcTemplate.queryForMap("SELECT b.id, b.file_name, b.file_size, b.status, u.login_id "
                + "FROM backups b JOIN users u ON u.id = b.created_by WHERE u.login_id = 'fa4admin' "
                + "ORDER BY b.id DESC LIMIT 1");
    }

    // ---------------------------------------------------------------- 2.1 初期表示

    @Test
    void 管理者が画面を開く_保存先とボタンと戻し方が出る() throws Exception {
        String html = page();

        assertThat(html).contains("保存先：" + props.getBackup().getDir())
                .contains("data-confirm=\"バックアップを作成します。よろしいですか？\"")
                .contains("data-once")
                .contains("--default-character-set=utf8mb4 shift_management &lt; ファイル名")
                .contains("<a href=\"/admin/backups\" class=\"active\">💾 バックアップ</a>");
    }

    @Test
    void 履歴が0件_I4401が出る() throws Exception {
        // shift_test に backups の行が残っていないときだけ確かめる
        assumeThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backups", Long.class)).isZero();

        assertThat(page()).contains("バックアップはまだありません。").doesNotContain("backups-table");
    }

    // ---------------------------------------------------------------- 2.2 作成（No.1・8）

    @Test
    void 作成に成功_backupsと操作ログに残り履歴に成功と大きさが出る() throws Exception {
        dumpWrites("x".repeat(1536), new ProcessResult(0, false, ""));

        mvc.perform(post("/admin/backups").with(csrf()).with(user(login("fa4admin"))))
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attribute("successMessage", "I4402"));

        Map<String, Object> row = lastBackup();
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("file_size")).longValue()).isEqualTo(1536L);
        String fileName = (String) row.get("file_name");
        assertThat(fileName).matches("backup_\\d{8}_\\d{6}\\.sql");
        assertThat(Path.of(props.getBackup().getDir()).resolve(fileName)).exists();
        String log = jdbcTemplate.queryForObject("SELECT CONCAT(target, '|', detail) FROM operation_logs "
                + "WHERE action = 'BACKUP_CREATE' "
                + "AND user_id = (SELECT id FROM users WHERE login_id = 'fa4admin')", String.class);
        assertThat(log).isEqualTo("backup:" + row.get("id") + "|file=" + fileName + " status=SUCCESS")
                .doesNotContain("testpw");
        assertThat(page()).contains(fileName).contains("1.5 KB")
                .contains("<span class=\"backup-success\">成功</span>").contains("テストfa4admin");
        Files.deleteIfExists(Path.of(props.getBackup().getDir()).resolve(fileName));
    }

    // ---------------------------------------------------------------- 2.2 作成（No.2・3）

    @Test
    void 作成に失敗_FAILEDで大きさなし_E4401で履歴に失敗が出る() throws Exception {
        dumpWrites(null, new ProcessResult(2, false, "mysqldump: Got error: 1045"));

        mvc.perform(post("/admin/backups").with(csrf()).with(user(login("fa4admin"))))
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attribute("errorMessage", "E4401"));

        Map<String, Object> row = lastBackup();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("file_size")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT detail FROM operation_logs WHERE action = 'BACKUP_CREATE' "
                + "AND user_id = (SELECT id FROM users WHERE login_id = 'fa4admin')", String.class))
                .endsWith("status=FAILED");
        assertThat(page()).contains("<span class=\"backup-failed\">失敗</span>").contains("－");
    }

    // ---------------------------------------------------------------- 権限・CSRF

    @Test
    void 店長が画面を開く_自分のホームへ戻される() throws Exception {
        mvc.perform(get("/admin/backups").with(user(login("fa4manager"))))
                .andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void バイトが作成を押す_自分のホームへ戻されコマンドは動かない() throws Exception {
        mvc.perform(post("/admin/backups").with(csrf()).with(user(login("fa4staff"))))
                .andExpect(redirectedUrl("/staff/home"));
        verify(processRunner, never()).run(any(), any(), any());
    }

    @Test
    void CSRFトークンなしでPOSTする_管理メニューへ戻りコマンドは動かない() throws Exception {
        LoginUser admin = login("fa4admin");
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/admin/backups").with(user(admin)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/backups").with(user(admin)).session(session))
                .andExpect(redirectedUrl("/admin/home"));
        verify(processRunner, never()).run(any(), any(), any());
    }
}

package jp.numajo.fgroup.shift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

import lombok.RequiredArgsConstructor;

/**
 * ①プロジェクト設定（pom.xml・application.yml・起動クラス・Flyway・messages.properties）の起動テスト。
 *
 * <p>
 * ①には判定のあるコードがない（起動クラスは97_テスト計画書 2.2 でカバレッジの対象外）ため、
 * 命令網羅・判定条件網羅・条件網羅・複数条件網羅の対象はない。かわりに、設定どおりに起動できることを確かめる。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ShiftManagementApplicationTests {

    private final Flyway flyway;

    private final JdbcTemplate jdbcTemplate;

    private final MessageSource messageSource;

    @Test
    void 起動する_JVMのタイムゾーンが日本時間になる() {
        assertEquals("Asia/Tokyo", TimeZone.getDefault().getID());
    }

    @Test
    void 起動する_FlywayでV1とV2が成功している() {
        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(List.of("1", "2"),
                List.of(applied).stream().map(info -> info.getVersion().getVersion()).toList());
        assertTrue(List.of(applied).stream().allMatch(info -> info.getState().isApplied()));
    }

    @Test
    void 起動する_V1で19のテーブルができる() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class);
        assertEquals(List.of("absences", "backups", "calendar_feeds", "chat_messages", "notification_settings",
                "notifications", "operation_logs", "request_submissions", "required_staff_defaults",
                "required_staff_overrides", "shift_assignments", "shift_periods", "shift_requests", "skills",
                "slot_required_skills", "slots", "store_settings", "user_skills", "users"), tables);
    }

    @Test
    void 起動する_V2の初期データが入る() {
        assertEquals(List.of("DAY", "EVENING"),
                jdbcTemplate.queryForList("SELECT code FROM slots ORDER BY id", String.class));
        assertEquals("セブンイレブン 三島駅西通り店",
                jdbcTemplate.queryForObject("SELECT store_name FROM store_settings WHERE id = 1", String.class));
        assertEquals("ADMIN",
                jdbcTemplate.queryForObject("SELECT role FROM users WHERE login_id = 'admin'", String.class));
        assertEquals(14, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM required_staff_defaults WHERE required_count = 0", Integer.class));
    }

    @Test
    void 起動する_messagesの日本語と引数が読める() {
        assertEquals("ログインIDまたはパスワードが違います。",
                messageSource.getMessage("E0101", null, Locale.JAPAN));
        String withArgs = messageSource.getMessage("E2605", new Object[] { "山田", "11月1日" }, Locale.JAPAN);
        assertTrue(withArgs.contains("山田") && withArgs.contains("11月1日"), withArgs);
    }
}

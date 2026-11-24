package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.dto.BackupRow;
import jp.numajo.fgroup.shift.dto.ManagerCountView;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.service.BackupService;
import jp.numajo.fgroup.shift.service.ManagerAccountService;
import jp.numajo.fgroup.shift.service.OperationLogService;

/**
 * AdminHomeController（SA-01）のテスト（20-SA-01 2.1、6章 No.3・No.4）。
 *
 * <p>
 * Service はモックにし、MockMvc（standalone）で model と画面名を確かめる。今の日時は Clock で
 * 2026/11/24 12:00 に固定する。警告の判定（isBackupWarning）は直接呼んで、境目と条件の組み合わせを確かめる。
 * </p>
 *
 * <pre>
 * isBackupWarning の条件：A＝最新がない、B＝status が FAILED、C＝created_at が今から168時間より前
 *
 * 命令網羅・判定条件網羅：
 *   if (latest.isEmpty())  真：No.3（なし）→ true ／ 偽：以下
 *   return B || C          真：失敗・7日1時間前 ／ 偽：6日23時間前の成功
 *
 * 条件網羅・複数条件網羅（A が偽のときの B と C の4通り。A が真のときは B・C を見ない）：
 *   A | B | C | 結果 | テスト
 *   真| - | - | 真   | バックアップなし
 *   偽| 偽| 偽| 偽   | 成功・6日23時間前（No.4）、成功・ちょうど168時間前（境目）、成功・1時間前
 *   偽| 偽| 真| 真   | 成功・7日1時間前（No.4）、成功・168時間と1秒前（境目）
 *   偽| 真| 偽| 真   | 失敗・1時間前（No.4）
 *   偽| 真| 真| 真   | 失敗・8日前
 * </pre>
 */
class AdminHomeControllerTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-24T12:00:00");

    private ManagerAccountService managerAccountService;
    private OperationLogService operationLogService;
    private BackupService backupService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        managerAccountService = mock(ManagerAccountService.class);
        operationLogService = mock(OperationLogService.class);
        backupService = mock(BackupService.class);
        Clock clock = Clock.fixed(NOW.atZone(TOKYO).toInstant(), TOKYO);
        mvc = MockMvcBuilders
                .standaloneSetup(new AdminHomeController(managerAccountService, operationLogService, backupService,
                        clock))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private static BackupRow backup(BackupStatus status, LocalDateTime createdAt) {
        return new BackupRow(1L, createdAt, "", "shift_20261124.sql", 1024L, status, "管理者");
    }

    // ---------------------------------------------------------------- 初期表示

    @Test
    void 初期表示_3つのServiceの結果をmodelに入れ管理メニュー画面になる() throws Exception {
        ManagerCountView count = new ManagerCountView(2, 1);
        List<OperationLogRow> logs = List.of(new OperationLogRow(1L, NOW, "2026/11/24 12:00:00", "新井",
                "シフトの公開", null, null, "127.0.0.1"));
        BackupRow latest = backup(BackupStatus.SUCCESS, NOW.minusHours(1));
        when(managerAccountService.count()).thenReturn(count);
        when(operationLogService.findRecent(5)).thenReturn(logs);
        when(backupService.findLatest()).thenReturn(Optional.of(latest));

        mvc.perform(get("/admin/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/home"))
                .andExpect(model().attribute("managerCount", count))
                .andExpect(model().attribute("recentLogs", logs))
                .andExpect(model().attribute("latestBackup", latest))
                .andExpect(model().attribute("backupWarning", false));
        verify(operationLogService).findRecent(5);
    }

    @Test
    void No3_バックアップなし_latestBackupはnullで警告あり() throws Exception {
        when(managerAccountService.count()).thenReturn(new ManagerCountView(0, 0));
        when(operationLogService.findRecent(5)).thenReturn(List.of());
        when(backupService.findLatest()).thenReturn(Optional.empty());

        mvc.perform(get("/admin/home"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("latestBackup", (Object) null))
                .andExpect(model().attribute("backupWarning", true));
    }

    // ---------------------------------------------------------------- 警告の判定

    @Test
    void 条件A真_最新がない_警告あり() {
        assertThat(AdminHomeController.isBackupWarning(Optional.empty(), NOW)).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}時間{2}秒前 → {3}")
    @CsvSource({
            // B偽・C偽
            "SUCCESS, 167, 0, false", // No.4 6日23時間前の成功
            "SUCCESS, 168, 0, false", // 境目：ちょうど168時間前は「より前」ではない
            "SUCCESS, 1, 0, false",
            // B偽・C真
            "SUCCESS, 169, 0, true", // No.4 7日1時間前の成功
            "SUCCESS, 168, 1, true", // 境目：168時間と1秒前
            // B真・C偽
            "FAILED, 1, 0, true", // No.4 1時間前の失敗
            // B真・C真
            "FAILED, 192, 0, true",
    })
    void 条件BとCの組み合わせ(BackupStatus status, long hoursAgo, long secondsAgo, boolean expected) {
        BackupRow row = backup(status, NOW.minusHours(hoursAgo).minusSeconds(secondsAgo));

        assertThat(AdminHomeController.isBackupWarning(Optional.of(row), NOW)).isEqualTo(expected);
    }
}

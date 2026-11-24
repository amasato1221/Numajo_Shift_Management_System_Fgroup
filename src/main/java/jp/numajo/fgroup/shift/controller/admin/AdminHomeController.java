package jp.numajo.fgroup.shift.controller.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.dto.BackupRow;
import jp.numajo.fgroup.shift.service.BackupService;
import jp.numajo.fgroup.shift.service.ManagerAccountService;
import jp.numajo.fgroup.shift.service.OperationLogService;
import lombok.RequiredArgsConstructor;

/**
 * SA-01 管理メニュー。20-SA-01 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/home")
public class AdminHomeController {

    /** 最近の操作に出す件数 */
    static final int RECENT_LOG_LIMIT = 5;

    /** これより前のバックアップしかないときは警告を出す時間（7日＝168時間） */
    static final long BACKUP_WARNING_HOURS = 168;

    private final ManagerAccountService managerAccountService;
    private final OperationLogService operationLogService;
    private final BackupService backupService;
    private final Clock clock;

    /**
     * 初期表示（20-SA-01 2.1）。
     */
    @GetMapping
    public String home(Model model) {
        Optional<BackupRow> latestBackup = backupService.findLatest();
        model.addAttribute("managerCount", managerAccountService.count());
        model.addAttribute("recentLogs", operationLogService.findRecent(RECENT_LOG_LIMIT));
        model.addAttribute("latestBackup", latestBackup.orElse(null));
        model.addAttribute("backupWarning", isBackupWarning(latestBackup, LocalDateTime.now(clock)));
        return "admin/home";
    }

    /**
     * バックアップの警告（W4101）を出すか（20-SA-01 2.1-4）。
     * 最新がない、または失敗、または作った日時が今から7日（168時間）より前なら true。
     */
    static boolean isBackupWarning(Optional<BackupRow> latest, LocalDateTime now) {
        if (latest.isEmpty()) {
            return true;
        }
        BackupRow row = latest.get();
        return row.status() == BackupStatus.FAILED
                || row.createdAt().isBefore(now.minusHours(BACKUP_WARNING_HOURS));
    }
}

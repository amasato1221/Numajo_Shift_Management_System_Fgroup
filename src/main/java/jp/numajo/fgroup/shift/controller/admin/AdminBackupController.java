package jp.numajo.fgroup.shift.controller.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.dto.BackupResult;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.BackupService;
import lombok.RequiredArgsConstructor;

/**
 * SA-04 バックアップ。20-SA-04 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/backups")
public class AdminBackupController {

    /** 履歴に出す件数 */
    static final int LIST_LIMIT = 50;

    private final BackupService backupService;
    private final AppProperties props;

    /** 初期表示（20-SA-04 2.1）。新しい50件の履歴と保存先を表示する。 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("backups", backupService.findRecent(LIST_LIMIT));
        model.addAttribute("backupDir", props.getBackup().getDir());
        return "admin/backups";
    }

    /** 作成（20-SA-04 2.2）。成功は I4402、失敗は E4401、作成中は E4402 を出して一覧へ戻る。 */
    @PostMapping
    public String create(@AuthenticationPrincipal LoginUser loginUser, RedirectAttributes redirectAttributes) {
        try {
            BackupResult result = backupService.create(loginUser.getUserId());
            if (result.status() == BackupStatus.SUCCESS) {
                redirectAttributes.addFlashAttribute("successMessage", "I4402");
                redirectAttributes.addFlashAttribute("successArgs", new Object[] { result.fileName() });
            } else {
                redirectAttributes.addFlashAttribute("errorMessage", "E4401");
            }
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
        }
        return "redirect:/admin/backups";
    }
}

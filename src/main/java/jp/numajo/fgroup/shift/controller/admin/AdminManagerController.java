package jp.numajo.fgroup.shift.controller.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ManagerForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ManagerAccountService;
import lombok.RequiredArgsConstructor;

/**
 * 店長アカウント管理（SA-02）。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/managers")
public class AdminManagerController {

    private static final String VIEW = "admin/managers";
    private static final String REDIRECT = "redirect:/admin/managers";

    private final ManagerAccountService managerAccountService;

    /**
     * 一覧と作成フォームを表示する（20-SA-02 2.1）。
     *
     * @param model 画面に渡す値
     * @return テンプレート名
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("managers", managerAccountService.findAll());
        model.addAttribute("managerForm", new ManagerForm());
        return VIEW;
    }

    /**
     * 店長アカウントを作る（20-SA-02 2.2）。入力チェックのエラーとログインIDの重複（E4207）は、入力を残したまま再表示する。
     *
     * @param form               入力
     * @param bindingResult      入力チェックの結果
     * @param loginUser          操作した管理者
     * @param model              画面に渡す値
     * @param redirectAttributes リダイレクト先に渡す値
     * @return テンプレート名またはリダイレクト先
     */
    @PostMapping
    public String create(@Validated @ModelAttribute("managerForm") ManagerForm form, BindingResult bindingResult,
            @AuthenticationPrincipal LoginUser loginUser, Model model, RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            try {
                String password = managerAccountService.create(form, loginUser.getUserId());
                redirectAttributes.addFlashAttribute("initialPassword", password);
                redirectAttributes.addFlashAttribute("initialPasswordName", form.getName());
                redirectAttributes.addFlashAttribute("successMessage", "I4202");
                return REDIRECT;
            } catch (BusinessException e) {
                bindingResult.rejectValue("loginId", e.getMessageCode());
            }
        }
        model.addAttribute("managers", managerAccountService.findAll());
        return VIEW;
    }

    /**
     * 店長アカウントを停止する（20-SA-02 2.3）。すでに停止中（E4208）なら赤のアラートを出す。
     *
     * @param id                 ユーザーID
     * @param loginUser          操作した管理者
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/stop")
    public String stop(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        String name = managerAccountService.getName(id);
        try {
            managerAccountService.stop(id, loginUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "I4204");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] { name });
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
        }
        return REDIRECT;
    }

    /**
     * 店長アカウントを再開する（20-SA-02 2.4）。すでに有効（E4209）なら赤のアラートを出す。
     *
     * @param id                 ユーザーID
     * @param loginUser          操作した管理者
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/resume")
    public String resume(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        try {
            managerAccountService.resume(id, loginUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "I4205");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
        }
        return REDIRECT;
    }

    /**
     * パスワードを初期化する（20-SA-02 2.5）。ロックの解除も兼ねる。
     *
     * @param id                 ユーザーID
     * @param loginUser          操作した管理者
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        String name = managerAccountService.getName(id);
        String password = managerAccountService.resetPassword(id, loginUser.getUserId());
        redirectAttributes.addFlashAttribute("initialPassword", password);
        redirectAttributes.addFlashAttribute("initialPasswordName", name);
        redirectAttributes.addFlashAttribute("successMessage", "I4206");
        return REDIRECT;
    }
}

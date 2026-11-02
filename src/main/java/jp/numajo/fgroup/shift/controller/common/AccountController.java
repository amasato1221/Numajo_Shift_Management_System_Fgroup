package jp.numajo.fgroup.shift.controller.common;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.PasswordChangeForm;
import jp.numajo.fgroup.shift.security.HomeUrl;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.UserService;
import lombok.RequiredArgsConstructor;

/**
 * SC-03 アカウント設定（パスワード変更）。20-SC-03 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/account/password")
public class AccountController {

    private final UserService userService;

    @GetMapping
    public String show(Model model) {
        model.addAttribute("passwordChangeForm", new PasswordChangeForm());
        return "common/account-password";
    }

    @PostMapping
    public String change(@Validated @ModelAttribute("passwordChangeForm") PasswordChangeForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal LoginUser loginUser,
                         Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setCurrentPassword(null);
            form.setNewPassword(null);
            form.setConfirmPassword(null);
            return "common/account-password";
        }
        try {
            userService.changePassword(loginUser.getUserId(), form.getCurrentPassword(),
                    form.getNewPassword(), form.getConfirmPassword());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return "redirect:/account/password";
        }
        loginUser.passwordChanged();
        redirectAttributes.addFlashAttribute("successMessage", "I0301");
        return "redirect:" + HomeUrl.of(loginUser.getRole());
    }
}

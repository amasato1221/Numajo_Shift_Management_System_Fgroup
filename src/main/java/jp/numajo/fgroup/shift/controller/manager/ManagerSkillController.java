package jp.numajo.fgroup.shift.controller.manager;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.ConstraintViolation;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.SkillForm;
import jp.numajo.fgroup.shift.form.SlotSkillForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.SkillService;
import lombok.RequiredArgsConstructor;

/**
 * SM-04 職能管理。20-SM-04 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/skills")
public class ManagerSkillController {

    private static final String VIEW = "manager/skills";
    private static final String REDIRECT = "redirect:/manager/skills";

    private final SkillService skillService;

    /** 初期表示（20-SM-04 2.1）。 */
    @GetMapping
    public String show(Model model) {
        model.addAttribute("skillForm", new SkillForm());
        addListValues(model, skillService.getSlotSkillForm());
        return VIEW;
    }

    /** 追加（20-SM-04 2.2）。入力チェックのエラーは再表示する。 */
    @PostMapping
    public String create(@Validated @ModelAttribute("skillForm") SkillForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal LoginUser loginUser,
                         Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addListValues(model, skillService.getSlotSkillForm());
            return VIEW;
        }
        try {
            skillService.create(form.getName(), form.getDescription(), loginUser.getUserId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return REDIRECT;
        }
        redirectAttributes.addFlashAttribute("successMessage", "I2402");
        return REDIRECT;
    }

    /** 変更（20-SM-04 2.3）。入力チェックのエラーも、最初のエラーのコードを出してリダイレクトする。 */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Validated @ModelAttribute("skillForm") SkillForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstErrorCode(bindingResult));
            return REDIRECT;
        }
        try {
            skillService.update(id, form.getName(), form.getDescription(), loginUser.getUserId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return REDIRECT;
        }
        redirectAttributes.addFlashAttribute("successMessage", "I2403");
        return REDIRECT;
    }

    /** 削除（20-SM-04 2.4）。 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes redirectAttributes) {
        skillService.delete(id, loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I2404");
        return REDIRECT;
    }

    /** 枠ごとの必要な職能の保存（20-SM-04 2.5）。 */
    @PostMapping("/slot-required")
    public String saveSlotRequired(@ModelAttribute("slotSkillForm") SlotSkillForm form,
                                   @AuthenticationPrincipal LoginUser loginUser,
                                   RedirectAttributes redirectAttributes) {
        try {
            skillService.saveSlotRequired(form.getDaySkillIds(), form.getEveningSkillIds(),
                    loginUser.getUserId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return REDIRECT;
        }
        redirectAttributes.addFlashAttribute("successMessage", "I2405");
        return REDIRECT;
    }

    /** 一覧と、枠ごとの必要な職能を model に入れる（20-SM-04 2.1 の 1・2）。 */
    private void addListValues(Model model, SlotSkillForm slotSkillForm) {
        model.addAttribute("skills", skillService.findRows());
        model.addAttribute("slotSkillForm", slotSkillForm);
    }

    /** 最初の入力チェックのエラーのメッセージコード（"{E2401}" の E2401）。 */
    private static String firstErrorCode(BindingResult bindingResult) {
        FieldError error = bindingResult.getFieldErrors().get(0);
        ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
        String template = violation.getMessageTemplate();
        return template.substring(1, template.length() - 1);
    }
}

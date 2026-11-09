package jp.numajo.fgroup.shift.controller.manager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.ConstraintViolation;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.RequiredDefaultForm;
import jp.numajo.fgroup.shift.form.RequiredOverrideForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.RequiredStaffService;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * SM-05 必要人数設定。20-SM-05 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/required")
public class ManagerRequiredStaffController {

    private static final String VIEW = "manager/required";
    private static final String REDIRECT = "redirect:/manager/required";

    /** 上書きのフォームの項目の並び（画面の左から。20-00 2.2 パターン B の最初のエラー） */
    private static final List<String> OVERRIDE_FIELDS = List.of("workDate", "slotId", "requiredCount");

    /** 値の形が違う（日付でない・数字でない）ときに出すコード。Bean Validation のエラーではないため、項目で決める */
    private static final Map<String, String> TYPE_MISMATCH_CODES =
            Map.of("workDate", "E2503", "slotId", "E2504", "requiredCount", "E2502");

    private final RequiredStaffService requiredStaffService;
    private final Clock clock;

    /** 初期表示（20-SM-05 2.1）。 */
    @GetMapping
    public String show(@RequestParam(required = false) String ym, Model model) {
        model.addAttribute("requiredDefaultForm", requiredStaffService.getDefaultForm());
        addMonthValues(model, parseYm(ym));
        return VIEW;
    }

    /** 曜日ごとの既定値の保存（20-SM-05 2.2）。入力チェックのエラーは再表示する。 */
    @PostMapping("/defaults")
    public String saveDefaults(@Validated @ModelAttribute("requiredDefaultForm") RequiredDefaultForm form,
                               BindingResult bindingResult,
                               @AuthenticationPrincipal LoginUser loginUser,
                               Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addMonthValues(model, YearMonth.now(clock));
            return VIEW;
        }
        requiredStaffService.saveDefaults(form.getDayCounts(), form.getEveningCounts(), loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I2501");
        return REDIRECT;
    }

    /** 日付ごとの上書きの保存（20-SM-05 2.3）。入力チェックのエラーも、最初のエラーのコードを出してリダイレクトする。 */
    @PostMapping("/overrides")
    public String saveOverride(@Validated @ModelAttribute("requiredOverrideForm") RequiredOverrideForm form,
                               BindingResult bindingResult,
                               @RequestParam(required = false) String ym,
                               @AuthenticationPrincipal LoginUser loginUser,
                               RedirectAttributes redirectAttributes) {
        String back = REDIRECT + "?ym=" + parseYm(ym);
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstErrorCode(bindingResult));
            return back;
        }
        try {
            requiredStaffService.saveOverride(form.getWorkDate(), form.getSlotId(), form.getRequiredCount(),
                    loginUser.getUserId());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return back;
        }
        redirectAttributes.addFlashAttribute("successMessage", "I2502");
        return REDIRECT + "?ym=" + YearMonth.from(form.getWorkDate());
    }

    /** 日付ごとの上書きを戻す（20-SM-05 2.4）。 */
    @PostMapping("/overrides/delete")
    public String deleteOverride(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate workDate,
                                 @RequestParam Byte slotId,
                                 @RequestParam(required = false) String ym,
                                 @AuthenticationPrincipal LoginUser loginUser,
                                 RedirectAttributes redirectAttributes) {
        requiredStaffService.deleteOverride(workDate, slotId, loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I2503");
        return REDIRECT + "?ym=" + parseYm(ym);
    }

    /** 上書きのフォーム（初期値）と、月の一覧を model に入れる（20-SM-05 2.1-3・2.1-4）。 */
    private void addMonthValues(Model model, YearMonth month) {
        RequiredOverrideForm overrideForm = new RequiredOverrideForm();
        overrideForm.setSlotId(SlotIds.DAY);
        model.addAttribute("requiredOverrideForm", overrideForm);
        model.addAttribute("days", requiredStaffService.getMonth(month));
        model.addAttribute("ym", month);
        model.addAttribute("ymLabel", DateTimeUtil.yearMonth(month));
        model.addAttribute("prevYm", month.minusMonths(1));
        model.addAttribute("nextYm", month.plusMonths(1));
    }

    /** 年月（yyyy-MM）。null・形式違いは今月（20-SM-05 2.1-1、30-06 2章）。 */
    private YearMonth parseYm(String ym) {
        if (ym == null) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(ym);
        } catch (DateTimeParseException e) {
            return YearMonth.now(clock);
        }
    }

    /**
     * 上書きのフォームの最初の入力チェックのエラーのメッセージコード（"{E2501}" の E2501）。
     * 項目の順番は、画面の左から順（日付 → 枠 → 人数）に固定する（20-00 2.2 パターン B）。
     * 値の形が違うエラー（typeMismatch）は Bean Validation のエラーではないため、項目ごとに決めたコードにする。
     */
    private static String firstErrorCode(BindingResult bindingResult) {
        FieldError error = OVERRIDE_FIELDS.stream()
                .map(bindingResult::getFieldError)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        if (!error.contains(ConstraintViolation.class)) {
            return TYPE_MISMATCH_CODES.get(error.getField());
        }
        String template = error.unwrap(ConstraintViolation.class).getMessageTemplate();
        return template.substring(1, template.length() - 1);
    }
}

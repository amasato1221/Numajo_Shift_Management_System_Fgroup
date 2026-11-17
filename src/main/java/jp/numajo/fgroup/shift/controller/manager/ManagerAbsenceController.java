package jp.numajo.fgroup.shift.controller.manager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.dto.AbsenceConfirmView;
import jp.numajo.fgroup.shift.dto.AbsenceDayView;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.AbsenceForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.AbsenceService;
import lombok.RequiredArgsConstructor;

/**
 * SM-07 欠勤登録（欠員通知）。20-SM-07 3章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/absences")
public class ManagerAbsenceController {

    private static final String VIEW = "manager/absence";
    private static final String REDIRECT = "redirect:/manager/absences";

    private final AbsenceService absenceService;
    private final Clock clock;

    /**
     * 初期表示（20-SM-07 3.1・3.2）。assignmentId があれば「② 内容を確かめて登録する」、なければ
     * 「① 日付と人を選ぶ」を出す。
     */
    @GetMapping
    public String show(@RequestParam(required = false) String date,
                       @RequestParam(required = false) Long assignmentId,
                       Model model, RedirectAttributes redirectAttributes) {
        if (assignmentId == null) {
            addDayValues(model, date);
            return VIEW;
        }
        try {
            AbsenceConfirmView confirm = absenceService.getConfirm(assignmentId);
            AbsenceForm form = new AbsenceForm();
            form.setAssignmentId(assignmentId);
            form.setNoticeMessage(confirm.defaultMessage());
            model.addAttribute("absenceForm", form);
            addConfirmValues(model, confirm);
            return VIEW;
        } catch (BusinessException e) {
            return backToDay(assignmentId, e, redirectAttributes);
        }
    }

    /** 登録（20-SM-07 3.3）。入力チェックのエラーは「② 内容を確かめて登録する」を入れ直して再表示する。 */
    @PostMapping
    public String register(@Validated @ModelAttribute("absenceForm") AbsenceForm form,
                           BindingResult bindingResult,
                           @AuthenticationPrincipal LoginUser loginUser,
                           Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasFieldErrors("assignmentId")) {
            // 割当が分からないと「② 内容を確かめて登録する」を出せないため、「① 日付と人を選ぶ」に戻す
            redirectAttributes.addFlashAttribute("errorMessage", "E2701");
            return REDIRECT;
        }
        if (bindingResult.hasErrors()) {
            try {
                addConfirmValues(model, absenceService.getConfirm(form.getAssignmentId()));
            } catch (BusinessException e) {
                return backToDay(form.getAssignmentId(), e, redirectAttributes);
            }
            return VIEW;
        }
        int notified;
        try {
            notified = absenceService.register(form.getAssignmentId(), form.getReason(), form.getNoticeMessage(),
                    loginUser.getUserId());
        } catch (BusinessException e) {
            return backToDay(form.getAssignmentId(), e, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "I2703");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] { notified });
        if (notified == 0) {
            redirectAttributes.addFlashAttribute("warningMessage", "W2701");
        }
        return "redirect:/manager/periods/" + absenceService.findPeriodId(form.getAssignmentId());
    }

    /**
     * 「① 日付と人を選ぶ」の値を model に入れる（20-SM-07 3.1）。日付が空・形式違いなら今日、
     * 今日より前ならエラー（E2702）を出して今日にする。
     */
    private void addDayValues(Model model, String date) {
        LocalDate today = LocalDate.now(clock);
        LocalDate target = parseDate(date, today);
        if (target.isBefore(today)) {
            model.addAttribute("errorMessage", "E2702");
            target = today;
        }
        AbsenceDayView day = absenceService.getDay(target);
        model.addAttribute("date", day.date());
        model.addAttribute("dateLabel", day.dateLabel());
        model.addAttribute("today", today);
        model.addAttribute("candidatesOfDay", day.candidatesOfDay());
        model.addAttribute("absentsOfDay", day.absentsOfDay());
    }

    /** 「② 内容を確かめて登録する」の表示用の値を model に入れる（20-SM-07 3.2）。 */
    private void addConfirmValues(Model model, AbsenceConfirmView confirm) {
        model.addAttribute("target", confirm);
        model.addAttribute("recipients", confirm.recipients());
    }

    /** 日付（yyyy-MM-dd）。null・形式違いは fallback。 */
    private static LocalDate parseDate(String date, LocalDate fallback) {
        if (date == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /** 業務エラーを赤のアラートにして、割当の日付の「① 日付と人を選ぶ」に戻る（20-SM-07 3.2・3.3）。 */
    private String backToDay(Long assignmentId, BusinessException e, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
        redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
        return REDIRECT + "?date=" + absenceService.findWorkDate(assignmentId);
    }
}

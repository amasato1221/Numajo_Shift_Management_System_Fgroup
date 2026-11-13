package jp.numajo.fgroup.shift.controller.manager;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import jp.numajo.fgroup.shift.dto.PublishResult;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.AssignmentToggleForm;
import jp.numajo.fgroup.shift.form.TimeAssignmentForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.AssignmentService;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * シフト作成（SM-06：希望一覧・割当・公開）。20-SM-06。
 *
 * <p>
 * 希望の代理修正（SM-06-1）と印刷用シフト表（SM-08）は、同じ URL（/manager/periods）の下に、あとからこのクラスへ足す。
 * </p>
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/periods")
public class ManagerPeriodController {

    private static final String VIEW = "manager/period";
    private static final String REDIRECT_PREFIX = "redirect:/manager/periods/";

    /** セルの指定「ユーザーID:日付:枠ID」（20-SM-06 3.3-1）。枠IDは 1（日勤）か 2（夕勤）。 */
    private static final Pattern CELL_PATTERN = Pattern.compile("^(\\d+):(\\d{4}-\\d{2}-\\d{2}):([12])$");

    /** 時間指定のフォームの項目の並び（画面の左から。20-00 2.2 パターン B の最初のエラー） */
    private static final List<String> TIME_FORM_FIELDS = List.of("userId", "workDate", "startTime", "endTime");

    /** 値の形が違う（数字・日付・時刻でない）ときに出すコード。Bean Validation のエラーではないため、項目で決める */
    private static final Map<String, String> TYPE_MISMATCH_CODES = Map.of("userId", "E2606", "workDate", "E2606",
            "startTime", "E2607", "endTime", "E2607");

    private final PeriodService periodService;
    private final AssignmentService assignmentService;

    /**
     * 期間を選ばずに開いたとき、表示する期間へ移る（20-SM-06 3.1）。
     *
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @GetMapping
    public String redirectDefault(RedirectAttributes redirectAttributes) {
        ShiftPeriod period = periodService.defaultPeriodForManager();
        if (period == null) {
            redirectAttributes.addFlashAttribute("warningMessage", "I2605");
            return "redirect:/manager/home";
        }
        return REDIRECT_PREFIX + period.getId();
    }

    /**
     * シフト作成画面を表示する（20-SM-06 3.2）。
     *
     * @param id    期間のID
     * @param model 画面に渡す値
     * @return テンプレート名
     */
    @GetMapping("/{id}")
    public String show(@PathVariable Long id, Model model) {
        model.addAttribute("page", assignmentService.getPeriodPage(id));
        model.addAttribute("timeAssignmentForm", new TimeAssignmentForm());
        model.addAttribute("timeOptions", ShiftTimeUtil.timeSelectOptions());
        model.addAttribute("staffOptions", assignmentService.findStaffOptions());
        return VIEW;
    }

    /**
     * セルの割当を切り替える（20-SM-06 3.3）。成功のメッセージは出さない（表が変わるため）。
     *
     * @param id                 期間のID
     * @param form               押したセル
     * @param loginUser          操作した店長
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/assignments/toggle")
    public String toggle(@PathVariable Long id, @ModelAttribute AssignmentToggleForm form,
            @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes redirectAttributes) {
        try {
            Matcher matcher = matchCell(form.getCell());
            assignmentService.toggleSlot(id, Long.valueOf(matcher.group(1)), LocalDate.parse(matcher.group(2)),
                    Byte.valueOf(matcher.group(3)), loginUser.getUserId());
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        } catch (NumberFormatException | DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "E2601");
        }
        return REDIRECT_PREFIX + id;
    }

    /**
     * 時間指定で割り当てる（20-SM-06 3.4）。入力チェックのエラーも、最初のエラーのコードを出してリダイレクトする。
     *
     * @param id                 期間のID
     * @param form               入力
     * @param bindingResult      入力チェックの結果
     * @param loginUser          操作した店長
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/assignments/time")
    public String assignTime(@PathVariable Long id, @Validated @ModelAttribute TimeAssignmentForm form,
            BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstErrorCode(bindingResult));
            return REDIRECT_PREFIX + id;
        }
        try {
            assignmentService.assignTime(id, form.getUserId(), form.getWorkDate(), form.getStartTime(),
                    form.getEndTime(), loginUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "I2606");
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        }
        return REDIRECT_PREFIX + id;
    }

    /**
     * 割当を外す（20-SM-06 3.5）。
     *
     * @param id                 期間のID
     * @param assignmentId       割当のID
     * @param loginUser          操作した店長
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/assignments/{assignmentId}/delete")
    public String deleteAssignment(@PathVariable Long id, @PathVariable Long assignmentId,
            @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes redirectAttributes) {
        try {
            assignmentService.delete(id, assignmentId, loginUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "I2607");
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        }
        return REDIRECT_PREFIX + id;
    }

    /**
     * シフトを公開する（20-SM-06 3.6）。警告が残っていたら W2606 も出す（公開はする）。
     *
     * @param id                 期間のID
     * @param loginUser          操作した店長
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/publish")
    public String publish(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        try {
            PublishResult result = assignmentService.publish(id, loginUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "I2608");
            if (result.hasWarnings()) {
                redirectAttributes.addFlashAttribute("warningMessage", "W2606");
            }
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        }
        return REDIRECT_PREFIX + id;
    }

    /** セルの指定を正規表現で分ける。null・形式違いは E2601。 */
    private static Matcher matchCell(String cell) {
        if (cell == null) {
            throw new BusinessException("E2601");
        }
        Matcher matcher = CELL_PATTERN.matcher(cell);
        if (!matcher.matches()) {
            throw new BusinessException("E2601");
        }
        return matcher;
    }

    /** 業務のエラーを、リダイレクト先で出すエラーにする。 */
    private static void addError(RedirectAttributes redirectAttributes, BusinessException e) {
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
        redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
    }

    /**
     * 時間指定のフォームの、最初の入力チェックのエラーのメッセージコード（"{E2606}" の E2606）。
     * 項目の順番は、画面の左から順（スタッフ → 日付 → 開始 → 終了）に固定する。
     * 値の形が違うエラー（typeMismatch）は Bean Validation のエラーではないため、項目ごとに決めたコードにする。
     */
    private static String firstErrorCode(BindingResult bindingResult) {
        FieldError error = TIME_FORM_FIELDS.stream()
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

package jp.numajo.fgroup.shift.controller.staff;

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

import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftRequestService;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望の提出（SB-03）。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/requests")
public class StaffRequestController {

    private static final String VIEW = "staff/requests";

    private final ShiftRequestService shiftRequestService;
    private final PeriodService periodService;

    /**
     * 提出画面を表示する（20-SB-03 3.1）。
     *
     * @param periodId  期間のID。なければ、受付中で期限が一番早い期間
     * @param loginUser ログイン中のバイト
     * @param model     画面に渡す値
     * @return テンプレート名
     */
    @GetMapping
    public String show(@RequestParam(required = false) Long periodId, @AuthenticationPrincipal LoginUser loginUser,
            Model model) {
        RequestPageView page = shiftRequestService.getRequestPage(loginUser.getUserId(), periodId);
        ShiftRequestForm form = page == null
                ? new ShiftRequestForm()
                : shiftRequestService.toForm(loginUser.getUserId(), periodService.getPeriod(page.period().id()));

        model.addAttribute("page", page);
        model.addAttribute("shiftRequestForm", form);
        model.addAttribute("timeOptions", ShiftTimeUtil.timeSelectOptions());
        return VIEW;
    }

    /**
     * 希望を提出する（20-SB-03 3.2）。
     *
     * @param form               入力した値
     * @param bindingResult      入力チェックの結果
     * @param loginUser          ログイン中のバイト
     * @param model              画面に渡す値
     * @param redirectAttributes リダイレクト先に渡す値
     * @return 入力エラーなら提出画面、それ以外は提出画面へのリダイレクト
     */
    @PostMapping
    public String submit(@Validated @ModelAttribute("shiftRequestForm") ShiftRequestForm form,
            BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser, Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            // 送られたフォームのまま、画面の表示に必要な値だけを入れ直す
            model.addAttribute("page", shiftRequestService.getRequestPage(loginUser.getUserId(), form.getPeriodId()));
            model.addAttribute("timeOptions", ShiftTimeUtil.timeSelectOptions());
            return VIEW;
        }
        try {
            shiftRequestService.submit(loginUser.getUserId(), form);
            redirectAttributes.addFlashAttribute("successMessage", "I1302");
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        }
        return redirectTo(form.getPeriodId());
    }

    /**
     * 提出を取り消す（20-SB-03 3.3）。
     *
     * @param periodId           期間のID
     * @param loginUser          ログイン中のバイト
     * @param redirectAttributes リダイレクト先に渡す値
     * @return 提出画面へのリダイレクト
     */
    @PostMapping("/cancel")
    public String cancel(@RequestParam Long periodId, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        try {
            shiftRequestService.cancel(loginUser.getUserId(), periodId);
            redirectAttributes.addFlashAttribute("successMessage", "I1303");
        } catch (BusinessException e) {
            addError(redirectAttributes, e);
        }
        return redirectTo(periodId);
    }

    private void addError(RedirectAttributes redirectAttributes, BusinessException e) {
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
        redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
    }

    private String redirectTo(Long periodId) {
        return "redirect:/staff/requests?periodId=" + periodId;
    }
}

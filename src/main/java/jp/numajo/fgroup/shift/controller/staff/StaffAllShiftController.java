package jp.numajo.fgroup.shift.controller.staff;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftTableService;
import jp.numajo.fgroup.shift.service.StoreSettingService;
import lombok.RequiredArgsConstructor;

/**
 * SB-04 みんなのシフト。20-SB-04 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/all-shifts")
public class StaffAllShiftController {

    private static final String VIEW = "staff/all-shifts";

    private final StoreSettingService storeSettingService;
    private final ShiftTableService shiftTableService;

    /**
     * 初期表示・期間の切り替え（20-SB-04 2.1）。
     * 店舗設定で表示しない設定ならホームへ戻す。公開済みの期間がないときは period・table を null にする（I1401）。
     * periodId が選択肢にないときは ShiftTableService が ResourceNotFoundException を投げ、404 になる。
     */
    @GetMapping
    public String allShifts(@RequestParam(required = false) Long periodId,
                            @AuthenticationPrincipal LoginUser loginUser,
                            Model model, RedirectAttributes redirectAttributes) {
        if (!storeSettingService.get().isShowOthersShifts()) {
            redirectAttributes.addFlashAttribute("warningMessage", "W1401");
            return "redirect:/staff/home";
        }

        List<ShiftPeriod> periods = shiftTableService.findPublishedPeriods();
        ShiftPeriod period = shiftTableService.choosePeriod(periods, periodId);

        model.addAttribute("periodOptions", periods.stream().map(PeriodService::toOption).toList());
        model.addAttribute("period", period == null ? null : PeriodService.toOption(period));
        model.addAttribute("table", period == null ? null : shiftTableService.build(period, false));
        model.addAttribute("myUserId", loginUser.getUserId());
        return VIEW;
    }
}

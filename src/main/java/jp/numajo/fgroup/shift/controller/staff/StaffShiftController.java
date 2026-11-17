package jp.numajo.fgroup.shift.controller.staff;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jp.numajo.fgroup.shift.dto.PeriodCalendar;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftCalendarService;
import lombok.RequiredArgsConstructor;

/**
 * SB-02 シフトカレンダー。20-SB-02 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff")
public class StaffShiftController {

    private static final String VIEW = "staff/shifts";

    private final PeriodService periodService;
    private final ShiftCalendarService shiftCalendarService;

    /**
     * 初期表示（20-SB-02 2.1）。選択肢が0件のときは、期間とカレンダーを入れずに表示する（テンプレートが I1202 を出す）。
     * periodId が選択肢にないときは、PeriodService が ResourceNotFoundException を投げ、404 になる。
     */
    @GetMapping("/shifts")
    public String shifts(@RequestParam(required = false) Long periodId,
                         @AuthenticationPrincipal LoginUser loginUser, Model model) {
        List<PeriodOption> options = periodService.findOptionsForStaff();
        ShiftPeriod period = periodService.resolveDefault(options, periodId);
        model.addAttribute("periodOptions", options);
        if (period == null) {
            return VIEW;
        }
        PeriodCalendar calendar = shiftCalendarService.buildPeriod(loginUser.getUserId(), period);
        model.addAttribute("period", calendar.period());
        model.addAttribute("calendar", calendar);
        model.addAttribute("canEditRequest", periodService.isAcceptingRequests(period));
        return VIEW;
    }
}

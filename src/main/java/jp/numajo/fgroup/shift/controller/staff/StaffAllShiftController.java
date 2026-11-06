package jp.numajo.fgroup.shift.controller.staff;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
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
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final ShiftTableService shiftTableService;
    private final Clock clock;

    /**
     * 初期表示・期間の切り替え（20-SB-04 2.1）。
     * 店舗設定で表示しない設定ならホームへ戻す。公開済みの期間がないときは period・table を null にする（I1401）。
     */
    @GetMapping
    public String allShifts(@RequestParam(required = false) Long periodId,
                            @AuthenticationPrincipal LoginUser loginUser,
                            Model model, RedirectAttributes redirectAttributes) {
        if (!storeSettingService.get().isShowOthersShifts()) {
            redirectAttributes.addFlashAttribute("warningMessage", "W1401");
            return "redirect:/staff/home";
        }

        LocalDate today = LocalDate.now(clock);
        List<ShiftPeriod> periods = shiftPeriodRepository.findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(
                PeriodStatus.PUBLISHED, today.minusMonths(3).withDayOfMonth(1));
        ShiftPeriod period = choosePeriod(periods, periodId, today);

        model.addAttribute("periodOptions", periods.stream().map(PeriodService::toOption).toList());
        model.addAttribute("period", period == null ? null : PeriodService.toOption(period));
        model.addAttribute("table", period == null ? null : shiftTableService.build(period, false));
        model.addAttribute("myUserId", loginUser.getUserId());
        return VIEW;
    }

    /**
     * 表示する期間を決める（20-SB-04 2.1-3）。
     *
     * @param periods  選択肢（新しい順）
     * @param periodId 指定された期間のID（なければ null）
     * @param today    今日
     * @return 指定の期間 → 今日を含む期間 → 一番新しい期間の順で決めた期間。選択肢が0件なら null
     * @throws ResourceNotFoundException periodId が選択肢にないとき（未公開・古すぎる・存在しない）
     */
    static ShiftPeriod choosePeriod(List<ShiftPeriod> periods, Long periodId, LocalDate today) {
        if (periodId != null) {
            return periods.stream().filter(p -> p.getId().equals(periodId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("period id=" + periodId));
        }
        if (periods.isEmpty()) {
            return null;
        }
        for (ShiftPeriod p : periods) {
            if (!p.getStartDate().isAfter(today) && !p.getEndDate().isBefore(today)) {
                return p;
            }
        }
        return periods.get(0);
    }
}

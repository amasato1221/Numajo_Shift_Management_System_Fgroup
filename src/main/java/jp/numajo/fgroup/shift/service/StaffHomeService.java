package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.dto.MonthCalendar;
import jp.numajo.fgroup.shift.dto.NextShiftView;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.StaffHomeView;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * バイトのホーム（SB-01）。30-05 2.1。
 *
 * <p>
 * 30-05 の依存に SlotRepository を足している。次の出勤が時間指定のとき、重なる枠のアイコン（☀☾）を
 * ShiftCalendarService#toBadge で決めるのに全部の枠が要るため。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffHomeService {

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final SlotRepository slotRepository;
    private final NotificationService notificationService;
    private final ShiftCalendarService shiftCalendarService;
    private final Clock clock;

    /**
     * ホームに出す値を作る（20-SB-01 2.1）。
     *
     * @param userId バイトのユーザーID
     * @param ym     カレンダーに出す月
     * @return 次の出勤・未提出の期間・新しいお知らせ・今月のシフト
     */
    public StaffHomeView getHome(Long userId, YearMonth ym) {
        LocalDateTime now = LocalDateTime.now(clock);
        NextShiftView nextShift = findNextShift(userId, now);
        List<PeriodOption> unsubmitted = shiftPeriodRepository.findUnsubmitted(userId, now).stream()
                .map(PeriodService::toOption)
                .toList();
        List<NoticeRow> notices = notificationService.findUnreadTop3(userId);
        MonthCalendar calendar = shiftCalendarService.buildMonth(userId, ym);
        return new StaffHomeView(nextShift, unsubmitted, notices, calendar);
    }

    /** 次の出勤（20-SB-01 2.1-1）。公開済みの期間の ASSIGNED のうち、今より後に終わる一番近い1件。なければ null。 */
    private NextShiftView findNextShift(Long userId, LocalDateTime now) {
        List<ShiftAssignment> next = shiftAssignmentRepository.findNextShifts(userId, now.toLocalDate(),
                now.toLocalTime(), PageRequest.of(0, 1));
        if (next.isEmpty()) {
            return null;
        }
        ShiftAssignment a = next.get(0);
        return new NextShiftView(a.getWorkDate(), DateTimeUtil.monthDay(a.getWorkDate()),
                ShiftCalendarService.toBadge(a, slotRepository.findAll(Sort.by("id"))),
                a.getStartTime(), a.getEndTime(), DateTimeUtil.timeRange(a.getStartTime(), a.getEndTime()));
    }
}

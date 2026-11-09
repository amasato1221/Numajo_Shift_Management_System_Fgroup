package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.dto.DayColumn;
import jp.numajo.fgroup.shift.dto.ShiftBadge;
import jp.numajo.fgroup.shift.dto.ShiftTable;
import jp.numajo.fgroup.shift.dto.ShiftTableRow;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 期間のシフト表（SB-04 みんなのシフト、SM-08 印刷用シフト表）。30-05 2.3。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftTableService {

    /** スタッフの並び：ふりがな昇順（null は最後）、同じなら id 昇順（20-SB-04 2.1-4） */
    private static final Comparator<User> STAFF_ORDER = Comparator
            .comparing(User::getNameKana, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(User::getId);

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final UserRepository userRepository;
    private final SlotRepository slotRepository;
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final Clock clock;

    /**
     * SB-04 の期間の選択肢（20-SB-04 2.1-2）。公開済みで、開始日が今日の3か月前の1日以降。新しい順。
     *
     * @return 公開済みの期間（新しい順）
     */
    public List<ShiftPeriod> findPublishedPeriods() {
        LocalDate from = LocalDate.now(clock).minusMonths(3).withDayOfMonth(1);
        return shiftPeriodRepository.findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(
                PeriodStatus.PUBLISHED, from);
    }

    /**
     * SB-04 で表示する期間を決める（20-SB-04 2.1-3）。
     *
     * @param periods  {@link #findPublishedPeriods()} の結果（新しい順）
     * @param periodId 指定された期間のID（なければ null）
     * @return 指定の期間 → 今日を含む期間 → 一番新しい期間の順で決めた期間。選択肢が0件なら null
     * @throws ResourceNotFoundException periodId が選択肢にないとき（未公開・古すぎる・存在しない）
     */
    public ShiftPeriod choosePeriod(List<ShiftPeriod> periods, Long periodId) {
        if (periodId != null) {
            return periods.stream().filter(p -> p.getId().equals(periodId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("period id=" + periodId));
        }
        if (periods.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now(clock);
        for (ShiftPeriod p : periods) {
            if (!p.getStartDate().isAfter(today) && !p.getEndDate().isBefore(today)) {
                return p;
            }
        }
        return periods.get(0);
    }

    /**
     * 期間のシフト表を作る（20-SB-04 2.1-4、20-SM-08 3.1-2）。
     * 行に出すのは、有効なバイト全員（割当がなくても出す）と、その期間に割当がある無効なバイト。
     * 欠勤を出さないときは、欠勤を除いた後の割当で「割当がある」かを決める。
     *
     * @param period        募集期間
     * @param includeAbsent 欠勤（ABSENT）の割当も出すか（SB-04 は false、SM-08 は true）
     * @return シフト表
     */
    public ShiftTable build(ShiftPeriod period, boolean includeAbsent) {
        List<Slot> slots = slotRepository.findAll(Sort.by("id"));
        List<ShiftAssignment> assignments = new ArrayList<>();
        for (ShiftAssignment a : shiftAssignmentRepository.findByPeriodIdWithUser(period.getId())) {
            if (includeAbsent || a.getStatus() != AssignmentStatus.ABSENT) {
                assignments.add(a);
            }
        }

        // 有効なバイト＋割当のある人（同じ人は1行）
        Map<Long, User> staff = new LinkedHashMap<>();
        for (User u : userRepository.findActiveStaff()) {
            staff.put(u.getId(), u);
        }
        for (ShiftAssignment a : assignments) {
            staff.putIfAbsent(a.getUser().getId(), a.getUser());
        }
        List<User> users = new ArrayList<>(staff.values());
        users.sort(STAFF_ORDER);

        List<ShiftTableRow> rows = new ArrayList<>();
        for (User u : users) {
            rows.add(toRow(u, assignments, slots));
        }
        return new ShiftTable(daysOf(period), rows);
    }

    /**
     * 日付・枠ごとの出勤の人数を数える（20-SM-08 3.1-3）。
     * ShiftWarningService#countFor と同じ数え方で、欠勤を除いた割当（日勤・夕勤・時間指定）を、時間が重なる枠ごとに数える。
     *
     * @param table {@link #build} で作ったシフト表
     * @return 日付 → （枠のID → 人数）。期間の全部の日・全部の枠を入れる（いなければ 0）
     */
    public Map<LocalDate, Map<Byte, Integer>> slotCounts(ShiftTable table) {
        List<Slot> slots = slotRepository.findAll(Sort.by("id"));
        Map<LocalDate, Map<Byte, Integer>> counts = new TreeMap<>();
        for (DayColumn col : table.days()) {
            LocalDate day = col.date();
            Map<Byte, Integer> perSlot = new LinkedHashMap<>();
            for (Slot s : slots) {
                perSlot.put(s.getId(), 0);
            }
            counts.put(day, perSlot);
        }
        for (ShiftTableRow row : table.rows()) {
            for (Map.Entry<LocalDate, List<ShiftBadge>> cell : row.cells().entrySet()) {
                Map<Byte, Integer> perSlot = counts.get(cell.getKey());
                for (ShiftBadge badge : cell.getValue()) {
                    if (badge.type() == BadgeType.ABSENT) {
                        continue;
                    }
                    for (Slot s : slots) {
                        if (ShiftTimeUtil.overlaps(badge.startTime(), badge.endTime(), s.getStartTime(),
                                s.getEndTime())) {
                            perSlot.merge(s.getId(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return counts;
    }

    /** 1人分の行を作る。バッジは開始時刻の順、勤務日数は出勤の割当がある日の数。 */
    private static ShiftTableRow toRow(User u, List<ShiftAssignment> assignments, List<Slot> slots) {
        Map<LocalDate, List<ShiftBadge>> cells = new TreeMap<>();
        Set<LocalDate> workDates = new HashSet<>();
        for (ShiftAssignment a : assignments) {
            if (!a.getUser().getId().equals(u.getId())) {
                continue;
            }
            cells.computeIfAbsent(a.getWorkDate(), d -> new ArrayList<>()).add(ShiftCalendarService.toBadge(a, slots));
            if (a.getStatus() == AssignmentStatus.ASSIGNED) {
                workDates.add(a.getWorkDate());
            }
        }
        cells.values().forEach(list -> list.sort(Comparator.comparing(ShiftBadge::startTime)));
        return new ShiftTableRow(u.getId(), u.getName(), u.isTrainee(), cells, workDates.size());
    }

    /** 期間の開始日から終了日までの日付。 */
    private static List<DayColumn> daysOf(ShiftPeriod period) {
        return period.getStartDate().datesUntil(period.getEndDate().plusDays(1))
                .map(ShiftTableService::toDayColumn)
                .toList();
    }

    /**
     * 表の日付の列を作る（30-07 3章：見出しの文字は Service が DateTimeUtil で作る）。
     * SM-06 の AssignmentService からも使えるように public にしている。
     *
     * @param date 日付
     * @return 日付と、見出し用の〜Label（例：11/1、1、日、11/1(日)）
     */
    public static DayColumn toDayColumn(LocalDate date) {
        return new DayColumn(date, DateTimeUtil.shortDate(date), DateTimeUtil.day(date),
                DateTimeUtil.dayOfWeek(date), DateTimeUtil.shortDateWithDay(date));
    }
}

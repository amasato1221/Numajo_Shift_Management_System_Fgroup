package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.CalendarDay;
import jp.numajo.fgroup.shift.dto.MonthCalendar;
import jp.numajo.fgroup.shift.dto.PeriodCalendar;
import jp.numajo.fgroup.shift.dto.ShiftBadge;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.ShiftRequest;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.ShiftRequestRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * バイトのシフトカレンダー（SB-01 の今月のシフト、SB-02 のシフトカレンダー）。30-05 2.2。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftCalendarService {

    /** 日勤のアイコン（20-00 4章） */
    static final String DAY_ICON = "☀";
    /** 夕勤のアイコン（20-00 4章） */
    static final String EVENING_ICON = "☾";

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ShiftRequestRepository shiftRequestRepository;
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final SlotRepository slotRepository;
    private final Clock clock;

    /**
     * 1か月のカレンダーを作る（20-SB-01 2.2）。
     *
     * @param userId バイトのユーザーID
     * @param ym     表示する月
     * @return その月の1日を含む週の月曜から、末日を含む週の日曜までのカレンダー
     */
    public MonthCalendar buildMonth(Long userId, YearMonth ym) {
        LocalDate from = weekStart(ym.atDay(1));
        LocalDate to = weekEnd(ym.atEndOfMonth());
        Map<LocalDate, List<ShiftBadge>> badges = buildBadges(userId, from, to);
        List<ShiftPeriod> periods = shiftPeriodRepository.findOverlapping(from, to);
        LocalDate today = LocalDate.now(clock);

        List<List<CalendarDay>> weeks = buildWeeks(from, to, date -> {
            boolean inMonth = YearMonth.from(date).equals(ym);
            return new CalendarDay(date, inMonth, date.equals(today), badgesOf(badges, date, inMonth),
                    periodIdOf(periods, date));
        });
        return new MonthCalendar(ym, weeks, ym.minusMonths(1), ym.plusMonths(1));
    }

    /**
     * 募集期間のカレンダーを作る（20-SB-02 2.1-3）。期間外の日は inMonth を false にし、バッジを出さない。
     *
     * @param userId バイトのユーザーID
     * @param period 表示する募集期間
     * @return 期間の始まりの週の月曜から、終わりの週の日曜までのカレンダー
     */
    public PeriodCalendar buildPeriod(Long userId, ShiftPeriod period) {
        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();
        Map<LocalDate, List<ShiftBadge>> badges = buildBadges(userId, start, end);
        LocalDate today = LocalDate.now(clock);

        List<List<CalendarDay>> weeks = buildWeeks(weekStart(start), weekEnd(end), date -> {
            boolean inPeriod = contains(start, end, date);
            return new CalendarDay(date, inPeriod, date.equals(today), badgesOf(badges, date, inPeriod),
                    inPeriod ? period.getId() : null);
        });
        return new PeriodCalendar(PeriodService.toOption(period), weeks);
    }

    /**
     * 日付ごとのバッジを作る（20-SB-01 2.3）。公開済みの期間は自分の割当、未公開の期間は自分の希望（○と時間指定）を出す。
     *
     * @param userId バイトのユーザーID
     * @param from   始まりの日（この日を含む）
     * @param to     終わりの日（この日を含む）
     * @return 日付ごとのバッジ（開始時刻の順）。バッジのない日は入らない
     */
    public Map<LocalDate, List<ShiftBadge>> buildBadges(Long userId, LocalDate from, LocalDate to) {
        List<Slot> slots = slotRepository.findAll(Sort.by("id"));
        Map<LocalDate, List<ShiftBadge>> result = new TreeMap<>();

        for (ShiftAssignment a : shiftAssignmentRepository.findByUserIdAndWorkDateBetween(userId, from, to)) {
            if (a.getPeriod().getStatus() == PeriodStatus.PUBLISHED) {
                result.computeIfAbsent(a.getWorkDate(), d -> new ArrayList<>()).add(toBadge(a, slots));
            }
        }
        for (ShiftRequest r : shiftRequestRepository.findByUserIdAndWorkDateBetween(userId, from, to)) {
            // 公開前の店長の下書きは見せず、代わりに自分の希望をグレーで出す（20-00 4章）
            if (r.getPeriod().getStatus() != PeriodStatus.PUBLISHED
                    && (r.getAvailability() == Availability.OK || r.getAvailability() == Availability.TIME)) {
                result.computeIfAbsent(r.getWorkDate(), d -> new ArrayList<>()).add(toPendingBadge(r, slots));
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(ShiftBadge::startTime)));
        return result;
    }

    /**
     * 割当をバッジにする（日勤・夕勤・時間指定、欠勤は ABSENT）。
     *
     * @param a     割当
     * @param slots 全部の枠（時間指定のアイコンを決めるのに使う）
     * @return バッジ
     */
    public static ShiftBadge toBadge(ShiftAssignment a, List<Slot> slots) {
        Slot slot = a.getSlot();
        BadgeType type;
        if (a.getStatus() == AssignmentStatus.ABSENT) {
            type = BadgeType.ABSENT;
        } else if (slot == null) {
            type = BadgeType.TIME;
        } else {
            type = isEvening(slot) ? BadgeType.EVENING : BadgeType.DAY;
        }
        return badge(type, slot, a.getStartTime(), a.getEndTime(), slots);
    }

    /**
     * 希望を未確定のバッジにする。○は枠の時刻、時間指定は希望の時刻。
     *
     * @param r     希望（○か時間指定）
     * @param slots 全部の枠（時間指定のアイコンを決めるのに使う）
     * @return 種類が PENDING のバッジ
     */
    public static ShiftBadge toPendingBadge(ShiftRequest r, List<Slot> slots) {
        Slot slot = r.getSlot();
        if (slot != null) {
            return badge(BadgeType.PENDING, slot, slot.getStartTime(), slot.getEndTime(), slots);
        }
        return badge(BadgeType.PENDING, null, r.getStartTime(), r.getEndTime(), slots);
    }

    /** 枠のシフトは枠のアイコンだけ、時間指定は重なる枠のアイコンと時間を出す。 */
    private static ShiftBadge badge(BadgeType type, Slot slot, LocalTime start, LocalTime end, List<Slot> slots) {
        if (slot != null) {
            return new ShiftBadge(type, iconOf(slot), null, start, end);
        }
        return new ShiftBadge(type, overlappingIcons(start, end, slots), DateTimeUtil.shortRange(start, end), start,
                end);
    }

    /**
     * 時間指定と重なる枠のアイコンを枠の順に並べる（例：13:00-22:00 は ☀☾）。
     * 20-SB-01 2.3 の ShiftTimeUtil#overlappingSlotCodes にあたる。ShiftTimeUtil は共有のファイルで変えないため、ここに書く。
     */
    private static String overlappingIcons(LocalTime start, LocalTime end, List<Slot> slots) {
        StringBuilder icons = new StringBuilder();
        for (Slot s : slots) {
            if (ShiftTimeUtil.overlaps(start, end, s.getStartTime(), s.getEndTime())) {
                icons.append(iconOf(s));
            }
        }
        return icons.toString();
    }

    private static String iconOf(Slot slot) {
        return isEvening(slot) ? EVENING_ICON : DAY_ICON;
    }

    private static boolean isEvening(Slot slot) {
        return slot.getId() == SlotIds.EVENING;
    }

    /** 範囲外の日はバッジを出さない（SB-01 の前月・翌月の日、SB-02 の期間外の日）。 */
    private static List<ShiftBadge> badgesOf(Map<LocalDate, List<ShiftBadge>> badges, LocalDate date, boolean inRange) {
        return inRange ? badges.getOrDefault(date, List.of()) : List.of();
    }

    private static Long periodIdOf(List<ShiftPeriod> periods, LocalDate date) {
        for (ShiftPeriod p : periods) {
            if (contains(p.getStartDate(), p.getEndDate(), date)) {
                return p.getId();
            }
        }
        return null;
    }

    private static boolean contains(LocalDate start, LocalDate end, LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate weekEnd(LocalDate date) {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /** from（月曜）から to（日曜）までを7日ずつの週に分ける。 */
    private static List<List<CalendarDay>> buildWeeks(LocalDate from, LocalDate to,
            Function<LocalDate, CalendarDay> toDay) {
        List<List<CalendarDay>> weeks = new ArrayList<>();
        List<CalendarDay> week = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            week.add(toDay.apply(date));
            if (week.size() == 7) {
                weeks.add(week);
                week = new ArrayList<>();
            }
        }
        return weeks;
    }
}

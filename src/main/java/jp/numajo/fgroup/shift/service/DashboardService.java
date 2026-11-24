package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.constant.WarningType;
import jp.numajo.fgroup.shift.dto.DashboardView;
import jp.numajo.fgroup.shift.dto.PeriodRowView;
import jp.numajo.fgroup.shift.dto.ShiftWarning;
import jp.numajo.fgroup.shift.dto.ShortageView;
import jp.numajo.fgroup.shift.dto.SubmissionStatusView;
import jp.numajo.fgroup.shift.dto.VacancyView;
import jp.numajo.fgroup.shift.entity.Absence;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.AbsenceRepository;
import jp.numajo.fgroup.shift.repository.RequestSubmissionRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 店長のダッシュボード（SM-01）。30-05 3.1、20-SM-01 2.1。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    /** 人数が足りない枠を出す最大の件数（20-SM-01 1章 No.2） */
    static final int SHORTAGE_LIMIT = 10;

    /** 募集期間の表に出す期間：終わってから何日以内か（20-SM-01 1章 No.4） */
    static final int PERIOD_DAYS = 31;

    /** 時間指定の割当の枠の表示の頭（SM-07 と同じ） */
    static final String TIME_LABEL_PREFIX = "時間指定 ";

    /** SHORTAGE の警告（W2601）の引数の位置：今の人数・必要人数（ShiftWarningService#addWarnings） */
    private static final int ARG_ASSIGNED = 2;
    private static final int ARG_REQUIRED = 3;

    private final ShiftPeriodRepository shiftPeriodRepository;
    private final UserRepository userRepository;
    private final RequestSubmissionRepository requestSubmissionRepository;
    private final AbsenceRepository absenceRepository;
    private final ShiftWarningService shiftWarningService;
    private final RequiredStaffService requiredStaffService;
    private final SlotRepository slotRepository;
    private final Clock clock;

    /**
     * ダッシュボードに出す値を作る（20-SM-01 2.1）。
     *
     * @return 提出状況・人数不足・欠員・募集期間
     */
    public DashboardView getDashboard() {
        LocalDate today = LocalDate.now(clock);
        List<User> activeStaff = userRepository.findActiveStaff();
        // 期間ID → 提出した人のID。提出状況と募集期間の表の両方で同じ期間を数えるので、1期間1回だけ取る
        Map<Long, Set<Long>> submittedCache = new HashMap<>();

        List<SubmissionStatusView> statuses = buildSubmissionStatuses(activeStaff, submittedCache);
        List<ShortageView> allShortages = buildShortages();
        List<ShortageView> shortages = allShortages.stream().limit(SHORTAGE_LIMIT).toList();
        int moreShortages = allShortages.size() - shortages.size();
        List<VacancyView> vacancies = buildVacancies(today);
        List<PeriodRowView> periods = buildPeriods(today, activeStaff, submittedCache);
        return new DashboardView(statuses, shortages, moreShortages, vacancies, periods);
    }

    /** 1. 提出状況（20-SM-01 2.1-1）。受付中の期間ごとに、有効なバイトの提出数と未提出者（ふりがな順）。 */
    private List<SubmissionStatusView> buildSubmissionStatuses(List<User> activeStaff,
            Map<Long, Set<Long>> submittedCache) {
        List<SubmissionStatusView> statuses = new ArrayList<>();
        for (ShiftPeriod period : shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN)) {
            Set<Long> submitted = submittedIds(period, submittedCache);
            List<String> unsubmittedNames = activeStaff.stream()
                    .filter(u -> !submitted.contains(u.getId()))
                    .map(User::getName)
                    .toList();
            statuses.add(new SubmissionStatusView(PeriodService.toOption(period),
                    activeStaff.size() - unsubmittedNames.size(), activeStaff.size(), unsubmittedNames));
        }
        return statuses;
    }

    /** 2. 人数不足（20-SM-01 2.1-2）。作成中の期間の SHORTAGE の警告を、日付・枠IDの順にすべて返す。 */
    private List<ShortageView> buildShortages() {
        List<ShortageView> shortages = new ArrayList<>();
        for (ShiftPeriod period : shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)) {
            for (ShiftWarning w : shiftWarningService.calculate(period)) {
                if (w.type() == WarningType.SHORTAGE) {
                    shortages.add(toShortage(period.getId(), w));
                }
            }
        }
        shortages.sort(Comparator.comparing(ShortageView::workDate));
        return shortages;
    }

    /** SHORTAGE の警告を1行にする。引数は W2601 の {日付, 枠名, 今の人数, 必要人数}。 */
    static ShortageView toShortage(Long periodId, ShiftWarning w) {
        Object[] args = w.args();
        String slotName = icon(w.slotId()) + args[1];
        return new ShortageView(periodId, w.workDate(), w.workDateLabel(), slotName,
                ((Number) args[ARG_ASSIGNED]).intValue(), ((Number) args[ARG_REQUIRED]).intValue());
    }

    /** 3. 欠員（20-SM-01 2.1-3）。今日以降の欠勤と、その枠の今の人数・必要人数。 */
    private List<VacancyView> buildVacancies(LocalDate today) {
        List<Absence> absences = absenceRepository.findUpcoming(today);
        if (absences.isEmpty()) {
            return List.of();
        }
        List<Slot> slots = slotRepository.findAll(Sort.by("id"));
        List<VacancyView> vacancies = new ArrayList<>();
        for (Absence absence : absences) {
            vacancies.add(toVacancy(absence, countSlot(absence.getAssignment(), slots)));
        }
        return vacancies;
    }

    /**
     * 人数を数える枠。枠の割当はその枠、時間指定は重なる枠のうち最初の枠（枠ID順）。重なる枠がなければ null。
     *
     * @param assignment 欠勤になった割当
     * @param slots      全部の枠（ID順）
     * @return 数える枠。なければ null
     */
    static Slot countSlot(ShiftAssignment assignment, List<Slot> slots) {
        if (assignment.getSlot() != null) {
            return assignment.getSlot();
        }
        for (Slot slot : slots) {
            if (ShiftTimeUtil.overlaps(assignment.getStartTime(), assignment.getEndTime(), slot.getStartTime(),
                    slot.getEndTime())) {
                return slot;
            }
        }
        return null;
    }

    /** 欠勤を1行にする。数える枠が null なら人数を出さない（hasCount = false）。 */
    private VacancyView toVacancy(Absence absence, Slot countSlot) {
        ShiftAssignment a = absence.getAssignment();
        LocalDateTime notifiedAt = absence.getNotifiedAt();
        String notifiedAtLabel = notifiedAt == null ? null : DateTimeUtil.shortDateTime(notifiedAt);
        boolean hasCount = countSlot != null;
        int assigned = hasCount ? shiftWarningService.countFor(a.getWorkDate(), countSlot) : 0;
        int required = hasCount ? requiredStaffService.requiredCount(a.getWorkDate(), countSlot.getId()) : 0;
        return new VacancyView(a.getWorkDate(), DateTimeUtil.monthDay(a.getWorkDate()), slotLabel(a),
                a.getUser().getName(), notifiedAt, notifiedAtLabel, hasCount, assigned, required);
    }

    /** 枠の表示（☀日勤・☾夕勤。時間指定は「時間指定 13:00〜22:00」）。 */
    static String slotLabel(ShiftAssignment assignment) {
        Slot slot = assignment.getSlot();
        if (slot == null) {
            return TIME_LABEL_PREFIX + DateTimeUtil.timeRange(assignment.getStartTime(), assignment.getEndTime());
        }
        return icon(slot.getId()) + slot.getName();
    }

    /** 枠のアイコン（夕勤は ☾、それ以外は ☀）。 */
    static String icon(Byte slotId) {
        return slotId != null && slotId == SlotIds.EVENING ? ShiftCalendarService.EVENING_ICON
                : ShiftCalendarService.DAY_ICON;
    }

    /** 4. 募集期間（20-SM-01 2.1-4）。今日の31日前以降に終わる期間を開始日の順に。提出数は 1 と同じ数え方。 */
    private List<PeriodRowView> buildPeriods(LocalDate today, List<User> activeStaff,
            Map<Long, Set<Long>> submittedCache) {
        List<PeriodRowView> rows = new ArrayList<>();
        for (ShiftPeriod period : shiftPeriodRepository
                .findByEndDateGreaterThanEqualOrderByStartDateAsc(today.minusDays(PERIOD_DAYS))) {
            Set<Long> submitted = submittedIds(period, submittedCache);
            int count = (int) activeStaff.stream().filter(u -> submitted.contains(u.getId())).count();
            rows.add(new PeriodRowView(PeriodService.toOption(period), count, activeStaff.size()));
        }
        return rows;
    }

    /** 期間の提出した人のID。同じ期間は1回だけ取る。 */
    private Set<Long> submittedIds(ShiftPeriod period, Map<Long, Set<Long>> cache) {
        return cache.computeIfAbsent(period.getId(),
                id -> new HashSet<>(requestSubmissionRepository.findUserIdsByPeriodId(id)));
    }
}

package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.WarningType;
import jp.numajo.fgroup.shift.dto.AssignmentMatrix;
import jp.numajo.fgroup.shift.dto.CountCell;
import jp.numajo.fgroup.shift.dto.DayColumn;
import jp.numajo.fgroup.shift.dto.MatrixCell;
import jp.numajo.fgroup.shift.dto.MatrixCell.AssignMark;
import jp.numajo.fgroup.shift.dto.MatrixCell.RequestMark;
import jp.numajo.fgroup.shift.dto.MatrixRow;
import jp.numajo.fgroup.shift.dto.PeriodPageView;
import jp.numajo.fgroup.shift.dto.PublishResult;
import jp.numajo.fgroup.shift.dto.ShiftWarning;
import jp.numajo.fgroup.shift.dto.TimeAssignmentRow;
import jp.numajo.fgroup.shift.dto.TimeRequestRow;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.ShiftRequest;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.RequestSubmissionRepository;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.ShiftRequestRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * シフト作成（SM-06：希望一覧・割当・公開）の業務処理。20-SM-06 3章。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssignmentService {

    private final ShiftPeriodRepository shiftPeriodRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ShiftRequestRepository shiftRequestRepository;
    private final RequestSubmissionRepository requestSubmissionRepository;
    private final UserRepository userRepository;
    private final SlotRepository slotRepository;
    private final RequiredStaffService requiredStaffService;
    private final ShiftWarningService shiftWarningService;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;
    private final PeriodService periodService;
    private final MessageSource messageSource;
    private final Clock clock;

    /** 同じ人・日付・開始・終了の時間指定（「割当済み」の判定に使う）。 */
    private record TimeKey(Long userId, LocalDate workDate, LocalTime startTime, LocalTime endTime) {
    }

    /** 希望の記号と、記号に付ける説明。 */
    private record RequestInfo(RequestMark mark, String title) {
    }

    // ------------------------------------------------------------------ 表示

    /**
     * シフト作成画面に出す値を作る（20-SM-06 3.2）。SQLは6回（スタッフ・希望・提出・割当・枠・必要人数）。
     *
     * @param periodId 期間のID
     * @return 画面に出す値
     * @throws ResourceNotFoundException 期間がないとき
     */
    public PeriodPageView getPeriodPage(Long periodId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        List<User> staff = userRepository.findStaffForPeriod(periodId);
        List<ShiftRequest> requests = shiftRequestRepository.findByPeriodId(periodId);
        Set<Long> submittedIds = new HashSet<>(requestSubmissionRepository.findUserIdsByPeriodId(periodId));
        List<ShiftAssignment> assignments = shiftAssignmentRepository.findByPeriodIdWithUser(periodId);
        List<Slot> slots = slotRepository.findAllWithRequiredSkills().stream()
                .sorted(Comparator.comparing(Slot::getId))
                .toList();
        Map<LocalDate, Map<Byte, Integer>> required = requiredStaffService.requiredCounts(period.getStartDate(),
                period.getEndDate());
        List<ShiftWarning> warnings = shiftWarningService.calculate(period, slots, assignments, required,
                LocalDate.now(clock));

        PeriodStatus status = period.getStatus();
        boolean traineeOnly = warnings.stream().anyMatch(w -> w.type() == WarningType.TRAINEE_ONLY);
        AssignmentMatrix matrix = buildMatrix(period, staff, requests, submittedIds, assignments, slots, required);
        return new PeriodPageView(PeriodService.toOption(period), period.getPublishedAt(),
                period.getPublishedAt() == null ? null : DateTimeUtil.dateTime(period.getPublishedAt()),
                periodService.findOptionsForManager(), matrix, warnings, findTimeRequests(requests, assignments),
                findTimeAssignments(assignments), status != PeriodStatus.OPEN,
                status == PeriodStatus.CLOSED && !traineeOnly, notPublishableCode(status, traineeOnly));
    }

    /**
     * 時間指定の割当の「スタッフ」のプルダウンに出す、有効なバイト（ふりがな順）。
     *
     * @return 有効なバイト
     */
    public List<User> findStaffOptions() {
        return userRepository.findActiveStaff();
    }

    // ------------------------------------------------------------------ 更新

    /**
     * セルの割当を切り替える。割当があれば外し、なければ付ける（20-SM-06 3.3）。
     *
     * @param periodId   期間のID
     * @param userId     スタッフのユーザーID
     * @param workDate   日付
     * @param slotId     枠のID
     * @param operatorId 操作した店長のID
     * @throws BusinessException         期間が受付中（E2602）、期間外の日付・存在しない枠（E2601）、欠勤の割当（E2603）、
     *                                   無効なスタッフ（E2604）、時間が重なる（E2605）
     * @throws ResourceNotFoundException 期間・スタッフがない
     */
    @Transactional
    public void toggleSlot(Long periodId, Long userId, LocalDate workDate, Byte slotId, Long operatorId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        User user = findTarget(period, userId, workDate);
        Slot slot = slotRepository.findById(slotId).orElseThrow(() -> new BusinessException("E2601"));

        Optional<ShiftAssignment> cell = shiftAssignmentRepository.findCell(periodId, userId, workDate, slotId);
        if (cell.isPresent()) {
            remove(period, user, cell.get(), operatorId);
            return;
        }
        add(period, user, workDate, slot, slot.getStartTime(), slot.getEndTime(), operatorId);
    }

    /**
     * 時間指定で割り当てる（20-SM-06 3.4）。
     *
     * @param periodId   期間のID
     * @param userId     スタッフのユーザーID
     * @param workDate   日付
     * @param start      開始時刻
     * @param end        終了時刻
     * @param operatorId 操作した店長のID
     * @throws BusinessException         期間が受付中（E2602）、期間外の日付（E2601）、無効なスタッフ（E2604）、
     *                                   時間が8:00〜22:00の30分単位でない（E2608）、開始が終了より後（E2609）、
     *                                   時間が重なる（E2605）
     * @throws ResourceNotFoundException 期間・スタッフがない
     */
    @Transactional
    public void assignTime(Long periodId, Long userId, LocalDate workDate, LocalTime start, LocalTime end,
            Long operatorId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        User user = findTarget(period, userId, workDate);
        if (!ShiftTimeUtil.isOnGrid(start) || !ShiftTimeUtil.isOnGrid(end)) {
            throw new BusinessException("E2608");
        }
        if (!start.isBefore(end)) {
            throw new BusinessException("E2609");
        }
        add(period, user, workDate, null, start, end, operatorId);
    }

    /**
     * 割当を外す（20-SM-06 3.5）。時間指定の割当を外すときに使う。
     *
     * @param periodId     期間のID
     * @param assignmentId 割当のID
     * @param operatorId   操作した店長のID
     * @throws BusinessException         期間が受付中（E2602）、欠勤の割当（E2603）
     * @throws ResourceNotFoundException 期間・割当がない（別の期間の割当も含む）
     */
    @Transactional
    public void delete(Long periodId, Long assignmentId, Long operatorId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        ShiftAssignment assignment = shiftAssignmentRepository.findByIdAndPeriodId(assignmentId, periodId)
                .orElseThrow(() -> new ResourceNotFoundException("assignment id=" + assignmentId));
        checkEditable(period);
        remove(period, assignment.getUser(), assignment, operatorId);
    }

    /**
     * シフトを公開する（20-SM-06 3.6）。有効なバイト全員にお知らせを出す。
     *
     * @param periodId   期間のID
     * @param operatorId 操作した店長のID
     * @return 公開した割当の数と、警告が残っているか
     * @throws BusinessException         作成中でない（E2610）、研修中の人だけの枠がある（E2611）
     * @throws ResourceNotFoundException 期間がない
     */
    @Transactional
    public PublishResult publish(Long periodId, Long operatorId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        if (period.getStatus() != PeriodStatus.CLOSED) {
            throw new BusinessException("E2610");
        }
        List<ShiftWarning> warnings = shiftWarningService.calculate(period);
        long traineeOnly = warnings.stream().filter(w -> w.type() == WarningType.TRAINEE_ONLY).count();
        if (traineeOnly > 0) {
            throw new BusinessException("E2611", traineeOnly);
        }

        period.setStatus(PeriodStatus.PUBLISHED);
        period.setPublishedAt(LocalDateTime.now(clock));
        shiftPeriodRepository.save(period);
        notificationService.notifyPublished(period);

        int count = (int) shiftAssignmentRepository.countByPeriodIdAndStatus(periodId, AssignmentStatus.ASSIGNED);
        operationLogService.record(operatorId, OperationAction.SHIFT_PUBLISH, "period:" + periodId,
                period.getStartDate() + "〜" + period.getEndDate() + " assignments=" + count);
        return new PublishResult(count, !warnings.isEmpty());
    }

    // ------------------------------------------------------------------ 更新の部品

    /** 割当を変えられる期間か（作成中・公開済み）。 */
    private static void checkEditable(ShiftPeriod period) {
        if (period.getStatus() == PeriodStatus.OPEN) {
            throw new BusinessException("E2602");
        }
    }

    /** 割当の対象を調べて、スタッフを返す。期間が変えられるか、日付が期間内か、STAFF のユーザーがいるか。 */
    private User findTarget(ShiftPeriod period, Long userId, LocalDate workDate) {
        checkEditable(period);
        if (workDate.isBefore(period.getStartDate()) || workDate.isAfter(period.getEndDate())) {
            throw new BusinessException("E2601");
        }
        return userRepository.findByIdAndRole(userId, Role.STAFF)
                .orElseThrow(() -> new ResourceNotFoundException("staff id=" + userId));
    }

    /** 割当を外す。公開済みなら本人にお知らせを出す。 */
    private void remove(ShiftPeriod period, User user, ShiftAssignment assignment, Long operatorId) {
        if (assignment.getStatus() == AssignmentStatus.ABSENT) {
            throw new BusinessException("E2603");
        }
        shiftAssignmentRepository.delete(assignment);
        if (period.getStatus() == PeriodStatus.PUBLISHED) {
            notificationService.notifyChanged(user, period,
                    message("N1004", DateTimeUtil.monthDay(assignment.getWorkDate()), slotLabel(assignment)));
        }
        operationLogService.record(operatorId, OperationAction.ASSIGNMENT_REMOVE, "assignment:" + assignment.getId(),
                logDetail(user.getId(), assignment));
    }

    /** 割当を1行足す（slot が null なら時間指定）。公開済みなら本人にお知らせを出す。 */
    private void add(ShiftPeriod period, User user, LocalDate workDate, Slot slot, LocalTime start, LocalTime end,
            Long operatorId) {
        if (!user.isActive()) {
            throw new BusinessException("E2604");
        }
        checkNoOverlap(user, workDate, start, end);

        ShiftAssignment row = new ShiftAssignment();
        row.setPeriod(period);
        row.setUser(user);
        row.setWorkDate(workDate);
        row.setSlot(slot);
        row.setStartTime(start);
        row.setEndTime(end);
        row.setStatus(AssignmentStatus.ASSIGNED);
        ShiftAssignment saved = shiftAssignmentRepository.save(row);

        if (period.getStatus() == PeriodStatus.PUBLISHED) {
            notificationService.notifyChanged(user, period,
                    message("N1003", DateTimeUtil.monthDay(workDate), slotLabel(saved)));
        }
        operationLogService.record(operatorId, OperationAction.ASSIGNMENT_ADD, "assignment:" + saved.getId(),
                logDetail(user.getId(), saved));
    }

    /** 同じ人・同じ日に、時間が重なる出勤の割当があれば E2605。 */
    private void checkNoOverlap(User user, LocalDate date, LocalTime start, LocalTime end) {
        boolean overlapped = shiftAssignmentRepository
                .findByUserIdAndWorkDateAndStatus(user.getId(), date, AssignmentStatus.ASSIGNED).stream()
                .anyMatch(a -> ShiftTimeUtil.overlaps(start, end, a.getStartTime(), a.getEndTime()));
        if (overlapped) {
            throw new BusinessException("E2605", user.getName(), DateTimeUtil.monthDay(date));
        }
    }

    /** お知らせに入れる枠名。時間指定は「時間指定 13:00〜22:00」（N1006）。 */
    private String slotLabel(ShiftAssignment assignment) {
        if (assignment.getSlot() == null) {
            return message("N1006", DateTimeUtil.time(assignment.getStartTime()),
                    DateTimeUtil.time(assignment.getEndTime()));
        }
        return assignment.getSlot().getName();
    }

    /** 操作ログの詳細。例：user=5 date=2026-11-01 slot=DAY、時間指定は slot=TIME 13:00-22:00。 */
    private static String logDetail(Long userId, ShiftAssignment assignment) {
        String slotText = assignment.getSlot() == null
                ? "TIME " + DateTimeUtil.time(assignment.getStartTime()) + "-"
                        + DateTimeUtil.time(assignment.getEndTime())
                : assignment.getSlot().getCode();
        return "user=" + userId + " date=" + assignment.getWorkDate() + " slot=" + slotText;
    }

    private String message(String code, Object... args) {
        return messageSource.getMessage(code, args, Locale.JAPAN);
    }

    // ------------------------------------------------------------------ 表示の部品

    /** 公開できない理由のコード。受付中は W2604、作成中で研修中のみの枠があれば W2605。 */
    private static String notPublishableCode(PeriodStatus status, boolean traineeOnly) {
        if (status == PeriodStatus.OPEN) {
            return "W2604";
        }
        if (status == PeriodStatus.CLOSED && traineeOnly) {
            return "W2605";
        }
        return null;
    }

    /** 割当表を作る（20-SM-06 2.1）。 */
    private AssignmentMatrix buildMatrix(ShiftPeriod period, List<User> staff, List<ShiftRequest> requests,
            Set<Long> submittedIds, List<ShiftAssignment> assignments, List<Slot> slots,
            Map<LocalDate, Map<Byte, Integer>> required) {
        List<LocalDate> dates = period.getStartDate().datesUntil(period.getEndDate().plusDays(1)).toList();
        List<DayColumn> days = dates.stream().map(ShiftTableService::toDayColumn).toList();
        Map<Long, List<ShiftRequest>> requestsByUser = requests.stream()
                .collect(Collectors.groupingBy(r -> r.getUser().getId()));
        Map<Long, List<ShiftAssignment>> assignmentsByUser = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));

        List<MatrixRow> rows = new ArrayList<>();
        for (User user : staff) {
            List<ShiftRequest> userRequests = requestsByUser.getOrDefault(user.getId(), List.of());
            List<ShiftAssignment> userAssignments = assignmentsByUser.getOrDefault(user.getId(), List.of());
            boolean submitted = submittedIds.contains(user.getId());
            Map<LocalDate, Map<Byte, MatrixCell>> cells = new LinkedHashMap<>();
            for (LocalDate day : dates) {
                Map<Byte, MatrixCell> cellsOfDay = new LinkedHashMap<>();
                for (Slot slot : slots) {
                    cellsOfDay.put(slot.getId(),
                            toCell(user, submitted, day, slot, userRequests, userAssignments, period.getStatus()));
                }
                cells.put(day, cellsOfDay);
            }
            rows.add(new MatrixRow(user.getId(), user.getName(), user.isTrainee(), submitted, cells));
        }
        return new AssignmentMatrix(days, slots, rows, buildCounts(dates, slots, assignments, required));
    }

    /** 最下行の「割当数/必要数」。数えるのは出勤だけ。 */
    private Map<LocalDate, Map<Byte, CountCell>> buildCounts(List<LocalDate> days, List<Slot> slots,
            List<ShiftAssignment> assignments, Map<LocalDate, Map<Byte, Integer>> required) {
        Map<LocalDate, List<ShiftAssignment>> assignedByDate = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ASSIGNED)
                .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate));
        Map<LocalDate, Map<Byte, CountCell>> counts = new LinkedHashMap<>();
        for (LocalDate day : days) {
            Map<Byte, CountCell> countsOfDay = new LinkedHashMap<>();
            for (Slot slot : slots) {
                List<ShiftAssignment> covering = assignedByDate.getOrDefault(day, List.of()).stream()
                        .filter(a -> ShiftWarningService.covers(a, slot))
                        .toList();
                int requiredCount = required.getOrDefault(day, Map.of()).getOrDefault(slot.getId(), 0);
                countsOfDay.put(slot.getId(), new CountCell(covering.size(), requiredCount,
                        covering.size() < requiredCount, ShiftWarningService.isTraineeOnly(covering)));
            }
            counts.put(day, countsOfDay);
        }
        return counts;
    }

    /** セル1つ（1人・1日・1枠）を作る。 */
    private MatrixCell toCell(User user, boolean submitted, LocalDate day, Slot slot, List<ShiftRequest> requests,
            List<ShiftAssignment> assignments, PeriodStatus status) {
        Set<Long> ownSkillIds = user.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        Set<Long> requiredSkillIds = slot.getRequiredSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        boolean hasSkill = ownSkillIds.containsAll(requiredSkillIds);
        RequestInfo request = requestInfo(hasSkill, submitted, day, slot, requests);

        List<ShiftAssignment> covering = assignments.stream()
                .filter(a -> a.getWorkDate().equals(day) && ShiftWarningService.covers(a, slot))
                .toList();
        Optional<ShiftAssignment> slotAssignment = covering.stream().filter(a -> a.getSlot() != null).findFirst();
        Optional<ShiftAssignment> assignment = slotAssignment.or(() -> covering.stream().findFirst());
        if (assignment.isEmpty()) {
            return new MatrixCell(request.mark(), request.title(), AssignMark.NONE, null,
                    confirmCodeForEmpty(hasSkill, request.mark()), false);
        }
        ShiftAssignment found = assignment.get();
        if (found.getStatus() == AssignmentStatus.ABSENT) {
            return new MatrixCell(request.mark(), request.title(), AssignMark.ABSENT, found.getId(), null, true);
        }
        if (found.getSlot() == null) {
            return new MatrixCell(request.mark(), request.title(), AssignMark.TIME, found.getId(), null, true);
        }
        String confirmCode = status == PeriodStatus.PUBLISHED ? "C2604" : null;
        return new MatrixCell(request.mark(), request.title(), AssignMark.SLOT, found.getId(), confirmCode, false);
    }

    /** 割当がないセルの確認：職能がなければ C2603、希望が×か未提出なら C2602。 */
    private static String confirmCodeForEmpty(boolean hasSkill, RequestMark mark) {
        if (!hasSkill) {
            return "C2603";
        }
        if (mark == RequestMark.NG || mark == RequestMark.NOT_SUBMITTED) {
            return "C2602";
        }
        return null;
    }

    /** 希望の記号。職能がなければ －、未提出なら ・、枠の希望があれば ○×、時間指定が重なれば 時。提出済みで枠の希望も重なる時間指定もなければ ×。 */
    private static RequestInfo requestInfo(boolean hasSkill, boolean submitted, LocalDate day, Slot slot,
            List<ShiftRequest> requests) {
        if (!hasSkill) {
            return new RequestInfo(RequestMark.NO_SKILL, null);
        }
        if (!submitted) {
            return new RequestInfo(RequestMark.NOT_SUBMITTED, null);
        }
        List<ShiftRequest> ofDay = requests.stream().filter(r -> r.getWorkDate().equals(day)).toList();
        Optional<ShiftRequest> slotRequest = ofDay.stream()
                .filter(r -> r.getSlot() != null && r.getSlot().getId().equals(slot.getId()))
                .findFirst();
        if (slotRequest.isPresent()) {
            boolean ok = slotRequest.get().getAvailability() == Availability.OK;
            return new RequestInfo(ok ? RequestMark.OK : RequestMark.NG, null);
        }
        Optional<ShiftRequest> timeRequest = ofDay.stream()
                .filter(r -> r.getSlot() == null
                        && ShiftTimeUtil.overlaps(r.getStartTime(), r.getEndTime(), slot.getStartTime(),
                                slot.getEndTime()))
                .findFirst();
        return timeRequest
                .map(r -> new RequestInfo(RequestMark.TIME, DateTimeUtil.time(r.getStartTime()) + "-"
                        + DateTimeUtil.time(r.getEndTime())))
                .orElseGet(() -> new RequestInfo(RequestMark.NG, null));
    }

    /** 時間指定の希望の一覧（日付・ふりがな順）。同じ時間の出勤の割当があれば「割当済み」。 */
    private List<TimeRequestRow> findTimeRequests(List<ShiftRequest> requests, List<ShiftAssignment> assignments) {
        Set<TimeKey> assigned = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ASSIGNED && a.getSlot() == null)
                .map(a -> new TimeKey(a.getUser().getId(), a.getWorkDate(), a.getStartTime(), a.getEndTime()))
                .collect(Collectors.toSet());
        return requests.stream()
                .filter(r -> r.getAvailability() == Availability.TIME)
                .sorted(Comparator.comparing(ShiftRequest::getWorkDate)
                        .thenComparing(ShiftRequest::getUser, ShiftWarningService.BY_KANA))
                .map(r -> new TimeRequestRow(r.getUser().getId(), r.getUser().getName(), r.getWorkDate(),
                        DateTimeUtil.monthDay(r.getWorkDate()), r.getStartTime(), r.getEndTime(),
                        DateTimeUtil.timeRange(r.getStartTime(), r.getEndTime()),
                        assigned.contains(new TimeKey(r.getUser().getId(), r.getWorkDate(), r.getStartTime(),
                                r.getEndTime()))))
                .toList();
    }

    /** 時間指定の割当の一覧（日付・開始・ふりがな順。欠勤も含む）。 */
    private List<TimeAssignmentRow> findTimeAssignments(List<ShiftAssignment> assignments) {
        return assignments.stream()
                .filter(a -> a.getSlot() == null)
                .sorted(Comparator.comparing(ShiftAssignment::getWorkDate)
                        .thenComparing(ShiftAssignment::getStartTime)
                        .thenComparing(ShiftAssignment::getUser, ShiftWarningService.BY_KANA))
                .map(a -> new TimeAssignmentRow(a.getId(), a.getUser().getName(), a.getWorkDate(),
                        DateTimeUtil.monthDay(a.getWorkDate()), a.getStartTime(), a.getEndTime(),
                        DateTimeUtil.timeRange(a.getStartTime(), a.getEndTime()), a.getStatus()))
                .toList();
    }
}

package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.AbsenceConfirmView;
import jp.numajo.fgroup.shift.dto.AbsenceDayView;
import jp.numajo.fgroup.shift.dto.AbsenceTargetRow;
import jp.numajo.fgroup.shift.dto.VacancyRecipientRow;
import jp.numajo.fgroup.shift.entity.Absence;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.AbsenceRepository;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 欠勤登録と欠員通知（SM-07）。30-05 3.8、20-SM-07。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AbsenceService {

    /** 時間指定の枠の表示（N1006 と同じ形。画面の枠の列に使う） */
    private static final String TIME_LABEL_PREFIX = "時間指定 ";
    /** 欠勤する人の表示で、時間帯と氏名の間に入れる全角スペース（20-SM-07 1.2 No.5） */
    private static final String NAME_SEPARATOR = "　";
    /** 操作ログの slot の値（時間指定のとき） */
    private static final String LOG_SLOT_TIME = "TIME";

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final AbsenceRepository absenceRepository;
    private final UserRepository userRepository;
    private final SlotRepository slotRepository;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;
    private final MessageSource messageSource;
    private final Clock clock;

    /**
     * 「① 日付と人を選ぶ」の表示（20-SM-07 3.1）。公開済みの期間のその日の割当を、出勤者と欠勤の人に分ける。
     *
     * @param date 日付
     * @return その日の出勤者・すでに欠勤の人
     */
    public AbsenceDayView getDay(LocalDate date) {
        List<ShiftAssignment> assignments = shiftAssignmentRepository.findPublishedByWorkDate(date);
        List<ShiftAssignment> absents = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ABSENT)
                .toList();
        Map<Long, Absence> absenceByAssignment = findAbsences(absents);

        List<AbsenceTargetRow> candidates = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ASSIGNED)
                .map(a -> toRow(a, null))
                .toList();
        List<AbsenceTargetRow> absentRows = absents.stream()
                .map(a -> toRow(a, absenceByAssignment.get(a.getId())))
                .toList();
        return new AbsenceDayView(date, DateTimeUtil.monthDay(date), candidates, absentRows);
    }

    /**
     * 「② 内容を確かめて登録する」の表示（20-SM-07 3.2）。
     *
     * @param assignmentId 割当ID
     * @return 欠勤する人・通知する相手・通知する文の既定文
     * @throws ResourceNotFoundException 割当がない
     * @throws BusinessException         E2706（公開済みでない）、E2707（すでに欠勤）、E2702（過去の日付）
     */
    public AbsenceConfirmView getConfirm(Long assignmentId) {
        ShiftAssignment assignment = findAssignment(assignmentId);
        checkRegistrable(assignment);

        List<VacancyRecipientRow> recipients = findVacancyRecipients(assignment).stream()
                .map(u -> new VacancyRecipientRow(u.getId(), u.getName(), u.isTrainee()))
                .toList();
        LocalDate workDate = assignment.getWorkDate();
        String defaultMessage = message("I2702", workDate.getMonthValue(), workDate.getDayOfMonth(),
                noticeSlotName(assignment));
        return new AbsenceConfirmView(assignment.getId(), workDate, DateTimeUtil.monthDay(workDate),
                targetLabel(assignment), recipients, defaultMessage);
    }

    /**
     * 割当の日付を返す。欠勤登録で業務エラーになったとき、元の日付の画面に戻るために使う（20-SM-07 3.2・3.3）。
     *
     * @param assignmentId 割当ID
     * @return 割当の日付
     * @throws ResourceNotFoundException 割当がない
     */
    public LocalDate findWorkDate(Long assignmentId) {
        return findAssignment(assignmentId).getWorkDate();
    }

    /**
     * 割当の期間IDを返す。欠勤を登録したあと、その期間のシフト作成の画面に戻るために使う（20-SM-07 3.3-4）。
     *
     * @param assignmentId 割当ID
     * @return 期間ID
     * @throws ResourceNotFoundException 割当がない
     */
    public Long findPeriodId(Long assignmentId) {
        return findAssignment(assignmentId).getPeriod().getId();
    }

    /**
     * 欠勤を登録し、欠員を知らせる（20-SM-07 3.3）。
     *
     * @param assignmentId 欠勤にする割当ID
     * @param reason       理由（任意。空なら保存しない）
     * @param noticeMessage 通知する文
     * @param operatorId   操作した店長のユーザーID
     * @return 欠員通知を送った人数
     * @throws ResourceNotFoundException 割当がない
     * @throws BusinessException         E2706（公開済みでない）、E2707（すでに欠勤）、E2702（過去の日付）
     */
    @Transactional
    public int register(Long assignmentId, String reason, String noticeMessage, Long operatorId) {
        ShiftAssignment assignment = findAssignment(assignmentId);
        checkRegistrable(assignment);

        assignment.setStatus(AssignmentStatus.ABSENT);

        Absence absence = new Absence();
        absence.setAssignment(assignment);
        absence.setRegisteredBy(userRepository.getReferenceById(operatorId));
        absence.setReason(reason == null || reason.isBlank() ? null : reason);
        absence.setNoticeMessage(noticeMessage);
        absence.setNotifiedAt(LocalDateTime.now(clock));
        absenceRepository.save(absence);

        List<User> recipients = findVacancyRecipients(assignment);
        notificationService.notifyVacancy(recipients, assignment, noticeMessage);
        notificationService.notifyChanged(assignment.getUser(), assignment.getPeriod(),
                message("N1005", DateTimeUtil.monthDay(assignment.getWorkDate()), slotName(assignment)));

        operationLogService.record(operatorId, OperationAction.ABSENCE_REGISTER, "assignment:" + assignment.getId(),
                "user=" + assignment.getUser().getId() + " date=" + assignment.getWorkDate()
                        + " slot=" + slotCode(assignment) + " recipients=" + recipients.size());
        return recipients.size();
    }

    /**
     * 欠員通知の相手を選ぶ（20-SM-07 3.4）。有効なバイトのうち、欠勤する本人でなく、対象の枠の必要職能を
     * すべて持ち、その日の欠勤する時間に重なる出勤がない人。研修中の人も含む。並びはふりがな順。
     *
     * @param assignment 欠勤にする割当
     * @return 通知の相手
     */
    public List<User> findVacancyRecipients(ShiftAssignment assignment) {
        Set<Long> requiredSkillIds = requiredSkillIds(assignment);
        Set<Long> busyUserIds = findBusyUserIds(assignment);
        Long absentUserId = assignment.getUser().getId();

        return userRepository.findActiveStaffWithSkills().stream()
                .filter(u -> !u.getId().equals(absentUserId))
                .filter(u -> skillIdsOf(u).containsAll(requiredSkillIds))
                .filter(u -> !busyUserIds.contains(u.getId()))
                .toList();
    }

    /** 対象の枠の必要職能（枠の割当はその枠だけ、時間指定は時間が重なる枠すべて）。 */
    private Set<Long> requiredSkillIds(ShiftAssignment assignment) {
        Set<Long> ids = new HashSet<>();
        for (Slot slot : slotRepository.findAllWithRequiredSkills()) {
            if (isTarget(assignment, slot)) {
                slot.getRequiredSkills().forEach(skill -> ids.add(skill.getId()));
            }
        }
        return ids;
    }

    private static boolean isTarget(ShiftAssignment assignment, Slot slot) {
        if (assignment.getSlot() != null) {
            return assignment.getSlot().getId().equals(slot.getId());
        }
        return ShiftTimeUtil.overlaps(assignment.getStartTime(), assignment.getEndTime(),
                slot.getStartTime(), slot.getEndTime());
    }

    /** その日に、欠勤する時間と重なる出勤がある人のID。 */
    private Set<Long> findBusyUserIds(ShiftAssignment assignment) {
        return shiftAssignmentRepository.findByWorkDateAndStatus(assignment.getWorkDate(), AssignmentStatus.ASSIGNED)
                .stream()
                .filter(a -> ShiftTimeUtil.overlaps(a.getStartTime(), a.getEndTime(),
                        assignment.getStartTime(), assignment.getEndTime()))
                .map(a -> a.getUser().getId())
                .collect(Collectors.toSet());
    }

    private static Set<Long> skillIdsOf(User user) {
        return user.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());
    }

    private ShiftAssignment findAssignment(Long assignmentId) {
        return shiftAssignmentRepository.findDetailById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("assignment " + assignmentId));
    }

    /** 欠勤にできる割当か確かめる（20-SM-07 3.3 の 2-2〜2-4。3.2 も同じ）。 */
    private void checkRegistrable(ShiftAssignment assignment) {
        if (assignment.getPeriod().getStatus() != PeriodStatus.PUBLISHED) {
            throw new BusinessException("E2706");
        }
        if (assignment.getStatus() == AssignmentStatus.ABSENT) {
            throw new BusinessException("E2707");
        }
        if (assignment.getWorkDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("E2702");
        }
    }

    /** ABSENT の割当に、欠勤の登録（登録日時・通知日時）を付けて取る。 */
    private Map<Long, Absence> findAbsences(List<ShiftAssignment> absents) {
        if (absents.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = absents.stream().map(ShiftAssignment::getId).toList();
        return absenceRepository.findByAssignmentIdIn(ids).stream()
                .collect(Collectors.toMap(b -> b.getAssignment().getId(), Function.identity()));
    }

    private static AbsenceTargetRow toRow(ShiftAssignment assignment, Absence absence) {
        LocalDateTime registeredAt = absence == null ? null : absence.getCreatedAt();
        LocalDateTime notifiedAt = absence == null ? null : absence.getNotifiedAt();
        return new AbsenceTargetRow(assignment.getId(), slotLabel(assignment), assignment.getUser().getName(),
                registeredAt, registeredAt == null ? null : DateTimeUtil.dateTime(registeredAt),
                notifiedAt, notifiedAt == null ? null : DateTimeUtil.dateTime(notifiedAt));
    }

    /** 画面の枠の列（☀日勤・☾夕勤。時間指定は「時間指定 13:00〜22:00」）。 */
    private static String slotLabel(ShiftAssignment assignment) {
        Slot slot = assignment.getSlot();
        if (slot == null) {
            return TIME_LABEL_PREFIX + DateTimeUtil.timeRange(assignment.getStartTime(), assignment.getEndTime());
        }
        String icon = slot.getId() == SlotIds.EVENING ? ShiftCalendarService.EVENING_ICON
                : ShiftCalendarService.DAY_ICON;
        return icon + slot.getName();
    }

    /** 欠勤する人の表示。例：10月24日(土) ☀日勤 8:00〜17:00　山田 太郎。 */
    private static String targetLabel(ShiftAssignment assignment) {
        String date = DateTimeUtil.monthDay(assignment.getWorkDate());
        String name = assignment.getUser().getName();
        if (assignment.getSlot() == null) {
            return date + " " + slotLabel(assignment) + NAME_SEPARATOR + name;
        }
        return date + " " + slotLabel(assignment) + " "
                + DateTimeUtil.timeRange(assignment.getStartTime(), assignment.getEndTime()) + NAME_SEPARATOR + name;
    }

    /** お知らせの枠名（N1005）。枠の割当は枠の名前、時間指定は「時間指定 13:00〜22:00」（N1006）。 */
    private String slotName(ShiftAssignment assignment) {
        if (assignment.getSlot() == null) {
            return message("N1006", DateTimeUtil.time(assignment.getStartTime()),
                    DateTimeUtil.time(assignment.getEndTime()));
        }
        return assignment.getSlot().getName();
    }

    /** 通知する文の既定文（I2702）の枠名。枠の割当は枠の名前、時間指定は「13:00〜22:00」。 */
    private static String noticeSlotName(ShiftAssignment assignment) {
        if (assignment.getSlot() == null) {
            return DateTimeUtil.timeRange(assignment.getStartTime(), assignment.getEndTime());
        }
        return assignment.getSlot().getName();
    }

    /** 操作ログの slot の値（DAY・EVENING。時間指定は TIME）。 */
    private static String slotCode(ShiftAssignment assignment) {
        return assignment.getSlot() == null ? LOG_SLOT_TIME : assignment.getSlot().getCode();
    }

    private String message(String code, Object... args) {
        return messageSource.getMessage(code, args, Locale.JAPAN);
    }
}

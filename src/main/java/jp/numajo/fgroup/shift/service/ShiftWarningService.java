package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.WarningType;
import jp.numajo.fgroup.shift.dto.ShiftWarning;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * シフト作成の警告の計算（20-SM-06 4章）。SM-06 と SM-01 が使う。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftWarningService {

    /** スタッフをふりがな順（なければ最後）、同じならIDの順に並べる。 */
    static final Comparator<User> BY_KANA = Comparator
            .comparing(User::getNameKana, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(User::getId);

    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final SlotRepository slotRepository;
    private final RequiredStaffService requiredStaffService;
    private final Clock clock;

    /**
     * 期間の警告を計算する。必要なデータを取ってから、計算だけの版を呼ぶ。
     *
     * @param period 期間
     * @return 警告（日付 → 枠 → 種類の順）
     */
    public List<ShiftWarning> calculate(ShiftPeriod period) {
        List<Slot> slots = slotRepository.findAllWithRequiredSkills();
        List<ShiftAssignment> assignments = shiftAssignmentRepository.findByPeriodIdWithUser(period.getId());
        Map<LocalDate, Map<Byte, Integer>> required = requiredStaffService.requiredCounts(period.getStartDate(),
                period.getEndDate());
        return calculate(period, slots, assignments, required, LocalDate.now(clock));
    }

    /**
     * 警告を計算する（DBを使わない）。
     *
     * @param period      期間
     * @param slots       枠（必要職能付き）
     * @param assignments 期間の割当（欠勤も含んでよい。数えるのは出勤だけ）
     * @param required    日付 →（枠ID → 必要人数）
     * @param today       今日（無効なスタッフの警告は、今日以降の日だけ）
     * @return 警告（日付 → 枠 → 種類の順）
     */
    public List<ShiftWarning> calculate(ShiftPeriod period, List<Slot> slots, List<ShiftAssignment> assignments,
            Map<LocalDate, Map<Byte, Integer>> required, LocalDate today) {
        List<Slot> orderedSlots = slots.stream().sorted(Comparator.comparing(Slot::getId)).toList();
        Map<LocalDate, List<ShiftAssignment>> assignedByDate = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ASSIGNED)
                .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate));

        List<ShiftWarning> warnings = new ArrayList<>();
        for (LocalDate date = period.getStartDate(); !date.isAfter(period.getEndDate()); date = date.plusDays(1)) {
            List<ShiftAssignment> assignedOfDay = assignedByDate.getOrDefault(date, List.of());
            for (Slot slot : orderedSlots) {
                List<ShiftAssignment> covering = assignedOfDay.stream().filter(a -> covers(a, slot)).toList();
                int requiredCount = required.getOrDefault(date, Map.of()).getOrDefault(slot.getId(), 0);
                addWarnings(warnings, date, slot, covering, requiredCount, today);
            }
        }
        return warnings;
    }

    /**
     * その日・その枠と重なる出勤の数（SM-01 で使う。数え方は calculate と同じ）。
     *
     * @param date 日付
     * @param slot 枠
     * @return 出勤の数
     */
    public int countFor(LocalDate date, Slot slot) {
        return (int) shiftAssignmentRepository.findAssignedByWorkDate(date).stream()
                .filter(a -> covers(a, slot))
                .count();
    }

    /**
     * 割当がその枠に数えられるか。枠の割当はその枠だけ、時間指定の割当は時間が重なる枠すべて（日勤と夕勤の両方もある）。
     *
     * @param assignment 割当
     * @param slot       枠
     * @return 数えるなら true
     */
    static boolean covers(ShiftAssignment assignment, Slot slot) {
        if (assignment.getSlot() != null) {
            return assignment.getSlot().getId().equals(slot.getId());
        }
        return ShiftTimeUtil.overlaps(assignment.getStartTime(), assignment.getEndTime(), slot.getStartTime(),
                slot.getEndTime());
    }

    /** 全員が研修中か（0人は false）。 */
    static boolean isTraineeOnly(List<ShiftAssignment> covering) {
        return !covering.isEmpty() && covering.stream().allMatch(a -> a.getUser().isTrainee());
    }

    /** 1つの日・枠の警告を、TRAINEE_ONLY、SHORTAGE、SKILL、INACTIVE_STAFF の順に足す。 */
    private void addWarnings(List<ShiftWarning> warnings, LocalDate date, Slot slot, List<ShiftAssignment> covering,
            int requiredCount, LocalDate today) {
        String dateLabel = DateTimeUtil.monthDay(date);
        String slotName = slot.getName();
        if (isTraineeOnly(covering)) {
            warnings.add(new ShiftWarning(WarningType.TRAINEE_ONLY, date, slot.getId(), "W2603",
                    new Object[] { dateLabel, slotName }));
        }
        if (covering.size() < requiredCount) {
            warnings.add(new ShiftWarning(WarningType.SHORTAGE, date, slot.getId(), "W2601",
                    new Object[] { dateLabel, slotName, covering.size(), requiredCount }));
        }
        List<User> users = covering.stream().map(ShiftAssignment::getUser).sorted(BY_KANA).toList();
        Set<Long> requiredSkillIds = slot.getRequiredSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        for (User user : users) {
            Set<Long> ownSkillIds = user.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());
            if (!ownSkillIds.containsAll(requiredSkillIds)) {
                warnings.add(new ShiftWarning(WarningType.SKILL, date, slot.getId(), "W2602",
                        new Object[] { dateLabel, slotName, user.getName() }));
            }
        }
        if (!date.isBefore(today)) {
            for (User user : users) {
                if (!user.isActive()) {
                    warnings.add(new ShiftWarning(WarningType.INACTIVE_STAFF, date, slot.getId(), "W2607",
                            new Object[] { dateLabel, slotName, user.getName() }));
                }
            }
        }
    }
}

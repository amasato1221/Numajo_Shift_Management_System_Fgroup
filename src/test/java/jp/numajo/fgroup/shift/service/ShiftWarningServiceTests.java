package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

/**
 * ShiftWarningService（SM-06：警告の計算）のテスト。
 *
 * <p>
 * 期間は 2099/11/1（日）〜11/3（火）の3日間。枠は日勤（8:00-17:00）と夕勤（17:00-22:00）。
 * 「今日」は、無効なスタッフの警告を見るテストでは 11/2 にする。20-SM-06 4章に対応する。
 * </p>
 *
 * <table>
 * <caption>1つの日・枠の判定（出勤の人数を count、必要人数を required とする）</caption>
 * <tr><th>count</th><th>全員が研修中</th><th>count &lt; required</th><th>職能が足りない人</th><th>無効な人（今日以降）</th>
 * <th>出る警告</th></tr>
 * <tr><td>0</td><td>（0人は対象外）</td><td>いいえ</td><td>なし</td><td>なし</td><td>なし</td></tr>
 * <tr><td>0</td><td>（0人は対象外）</td><td>はい</td><td>なし</td><td>なし</td><td>SHORTAGE</td></tr>
 * <tr><td>1以上</td><td>はい</td><td>いいえ</td><td>なし</td><td>なし</td><td>TRAINEE_ONLY</td></tr>
 * <tr><td>2</td><td>いいえ（研修中と通常）</td><td>いいえ</td><td>なし</td><td>なし</td><td>なし</td></tr>
 * <tr><td>1</td><td>いいえ</td><td>いいえ</td><td>いる</td><td>なし</td><td>SKILL</td></tr>
 * <tr><td>1</td><td>いいえ</td><td>いいえ</td><td>なし</td><td>いる</td><td>INACTIVE_STAFF</td></tr>
 * <tr><td>1</td><td>はい</td><td>はい</td><td>いる</td><td>いる</td><td>4種類すべて（この順）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class ShiftWarningServiceTests {

    private static final LocalDate D1 = LocalDate.of(2099, 11, 1);
    private static final LocalDate D2 = LocalDate.of(2099, 11, 2);
    private static final LocalDate D3 = LocalDate.of(2099, 11, 3);
    private static final byte DAY = 1;
    private static final byte EVENING = 2;

    @Mock
    private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private RequiredStaffService requiredStaffService;

    private ShiftWarningService service;
    private ShiftPeriod period;
    private Slot daySlot;
    private Slot eveningSlot;
    private Skill skill1;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(LocalDateTime.of(2099, 11, 2, 10, 0).atZone(zone).toInstant(), zone);
        service = new ShiftWarningService(shiftAssignmentRepository, slotRepository, requiredStaffService, clock);
        period = new ShiftPeriod();
        period.setId(1L);
        period.setStartDate(D1);
        period.setEndDate(D3);
        skill1 = skill(1L);
        daySlot = slot(DAY, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0));
        eveningSlot = slot(EVENING, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0));
    }

    // ===== 部品 =====

    private static Skill skill(Long id) {
        Skill s = new Skill();
        s.setId(id);
        return s;
    }

    private static Slot slot(byte id, String name, LocalTime start, LocalTime end, Skill... required) {
        Slot s = new Slot();
        s.setId(id);
        s.setName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setRequiredSkills(new HashSet<>(Set.of(required)));
        return s;
    }

    private static User user(Long id, String name, String kana, boolean trainee, boolean active, Skill... skills) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setNameKana(kana);
        u.setTrainee(trainee);
        u.setActive(active);
        u.setSkills(new HashSet<>(Set.of(skills)));
        return u;
    }

    private static User regular(Long id, String name) {
        return user(id, name, name, false, true);
    }

    private static ShiftAssignment assigned(User user, LocalDate date, Slot slot) {
        ShiftAssignment a = new ShiftAssignment();
        a.setUser(user);
        a.setWorkDate(date);
        a.setSlot(slot);
        a.setStartTime(slot.getStartTime());
        a.setEndTime(slot.getEndTime());
        a.setStatus(AssignmentStatus.ASSIGNED);
        return a;
    }

    private static ShiftAssignment timeAssigned(User user, LocalDate date, LocalTime start, LocalTime end) {
        ShiftAssignment a = new ShiftAssignment();
        a.setUser(user);
        a.setWorkDate(date);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(AssignmentStatus.ASSIGNED);
        return a;
    }

    private static Map<LocalDate, Map<Byte, Integer>> required(int day, int evening) {
        return Map.of(D1, Map.of(DAY, day, EVENING, evening), D2, Map.of(DAY, day, EVENING, evening),
                D3, Map.of(DAY, day, EVENING, evening));
    }

    private List<ShiftWarning> calculate(List<ShiftAssignment> assignments, Map<LocalDate, Map<Byte, Integer>> req,
            LocalDate today) {
        return service.calculate(period, List.of(daySlot, eveningSlot), assignments, req, today);
    }

    private static List<WarningType> types(List<ShiftWarning> warnings) {
        return warnings.stream().map(ShiftWarning::type).toList();
    }

    // ===== calculate（DBを使わない版） =====

    @Test
    void calculate_必要人数が足りていれば警告なし() {
        List<ShiftAssignment> assignments = List.of(assigned(regular(1L, "a"), D1, daySlot),
                assigned(regular(2L, "b"), D1, eveningSlot));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 1, EVENING, 1)), D1);

        assertThat(actual).isEmpty();
    }

    @Test
    void calculate_必要人数の指定がない日と枠は0人として扱う() {
        List<ShiftWarning> actual = calculate(List.of(), Map.of(D1, Map.of(DAY, 0)), D1);

        assertThat(actual).isEmpty();
    }

    @Test
    void calculate_人数が足りなければSHORTAGEで日付と枠名と人数を渡す() {
        List<ShiftAssignment> assignments = List.of(assigned(regular(1L, "a"), D1, daySlot));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 2, EVENING, 0)), D1);

        assertThat(actual).hasSize(1);
        ShiftWarning warning = actual.get(0);
        assertThat(warning.type()).isEqualTo(WarningType.SHORTAGE);
        assertThat(warning.messageCode()).isEqualTo("W2601");
        assertThat(warning.workDate()).isEqualTo(D1);
        assertThat(warning.workDateLabel()).isEqualTo("11月1日(日)");
        assertThat(warning.slotId()).isEqualTo(DAY);
        assertThat(warning.args()).containsExactly("11月1日(日)", "日勤", 1, 2);
    }

    @Test
    void calculate_欠勤の割当は人数に数えない() {
        ShiftAssignment absent = assigned(regular(1L, "a"), D1, daySlot);
        absent.setStatus(AssignmentStatus.ABSENT);

        List<ShiftWarning> actual = calculate(List.of(absent), Map.of(D1, Map.of(DAY, 1, EVENING, 0)), D1);

        assertThat(types(actual)).containsExactly(WarningType.SHORTAGE);
        assertThat(actual.get(0).args()).containsExactly("11月1日(日)", "日勤", 0, 1);
    }

    @Test
    void calculate_研修中の人だけならTRAINEE_ONLY() {
        User trainee = user(1L, "a", "a", true, true);

        List<ShiftWarning> actual = calculate(List.of(assigned(trainee, D1, daySlot)),
                Map.of(D1, Map.of(DAY, 1, EVENING, 0)), D1);

        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).type()).isEqualTo(WarningType.TRAINEE_ONLY);
        assertThat(actual.get(0).messageCode()).isEqualTo("W2603");
        assertThat(actual.get(0).args()).containsExactly("11月1日(日)", "日勤");
    }

    @Test
    void calculate_研修中と通常の2人ならTRAINEE_ONLYにならない() {
        List<ShiftAssignment> assignments = List.of(assigned(user(1L, "a", "a", true, true), D1, daySlot),
                assigned(regular(2L, "b"), D1, daySlot));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 2, EVENING, 0)), D1);

        assertThat(actual).isEmpty();
    }

    @Test
    void calculate_必要な職能がない人はSKILLで氏名のふりがな順に出す() {
        Slot skilled = slot(DAY, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0), skill1);
        User noKana = user(3L, "ゼロ", null, false, true);
        User second = user(2L, "ベータ", "べーた", false, true);
        User first = user(1L, "アルファ", "あるふぁ", false, true);
        User skilledUser = user(4L, "ガンマ", "がんま", false, true, skill1);
        List<ShiftAssignment> assignments = List.of(assigned(noKana, D1, skilled), assigned(second, D1, skilled),
                assigned(skilledUser, D1, skilled), assigned(first, D1, skilled));

        List<ShiftWarning> actual = service.calculate(period, List.of(skilled, eveningSlot), assignments,
                Map.of(D1, Map.of(DAY, 0, EVENING, 0)), D1);

        assertThat(types(actual)).containsExactly(WarningType.SKILL, WarningType.SKILL, WarningType.SKILL);
        assertThat(actual).extracting(w -> w.args()[2]).containsExactly("アルファ", "ベータ", "ゼロ");
        assertThat(actual.get(0).messageCode()).isEqualTo("W2602");
    }

    @Test
    void calculate_ふりがなが同じならIDの順() {
        Slot skilled = slot(DAY, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0), skill1);
        User later = user(9L, "後", "おなじ", false, true);
        User earlier = user(2L, "先", "おなじ", false, true);

        List<ShiftWarning> actual = service.calculate(period, List.of(skilled, eveningSlot),
                List.of(assigned(later, D1, skilled), assigned(earlier, D1, skilled)),
                Map.of(D1, Map.of(DAY, 0, EVENING, 0)), D1);

        assertThat(actual).extracting(w -> w.args()[2]).containsExactly("先", "後");
    }

    @Test
    void calculate_時間指定の13時から22時は日勤と夕勤の両方に数える() {
        List<ShiftAssignment> assignments = List
                .of(timeAssigned(regular(1L, "a"), D1, LocalTime.of(13, 0), LocalTime.of(22, 0)));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 1, EVENING, 1)), D1);

        assertThat(actual).isEmpty();
    }

    @Test
    void calculate_時間指定の17時から22時は夕勤だけに数える() {
        List<ShiftAssignment> assignments = List
                .of(timeAssigned(regular(1L, "a"), D1, LocalTime.of(17, 0), LocalTime.of(22, 0)));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 1, EVENING, 1)), D1);

        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).type()).isEqualTo(WarningType.SHORTAGE);
        assertThat(actual.get(0).slotId()).isEqualTo(DAY);
    }

    @Test
    void calculate_ほかの枠の割当はその枠に数えない() {
        List<ShiftAssignment> assignments = List.of(assigned(regular(1L, "a"), D1, eveningSlot));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 1, EVENING, 1)), D1);

        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).slotId()).isEqualTo(DAY);
    }

    @Test
    void calculate_無効なスタッフは今日以降の日だけINACTIVE_STAFF() {
        User inactive = user(1L, "辞めた人", "やめた", false, false);
        List<ShiftAssignment> assignments = List.of(assigned(inactive, D1, daySlot), assigned(inactive, D2, daySlot),
                assigned(inactive, D3, daySlot));

        List<ShiftWarning> actual = calculate(assignments, Map.of(D1, Map.of(DAY, 1, EVENING, 0),
                D2, Map.of(DAY, 1, EVENING, 0), D3, Map.of(DAY, 1, EVENING, 0)), D2);

        assertThat(actual).extracting(ShiftWarning::workDate).containsExactly(D2, D3);
        assertThat(types(actual)).containsOnly(WarningType.INACTIVE_STAFF);
        assertThat(actual.get(0).messageCode()).isEqualTo("W2607");
        assertThat(actual.get(0).args()).containsExactly("11月2日(月)", "日勤", "辞めた人");
    }

    @Test
    void calculate_有効なスタッフにはINACTIVE_STAFFを出さない() {
        List<ShiftWarning> actual = calculate(List.of(assigned(regular(1L, "a"), D2, daySlot)),
                Map.of(D2, Map.of(DAY, 1, EVENING, 0)), D2);

        assertThat(actual).isEmpty();
    }

    @Test
    void calculate_1つの枠で4種類そろったら種類の順に並べる() {
        Slot skilled = slot(DAY, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0), skill1);
        User trainee = user(1L, "研修生", "けんしゅう", true, false);

        List<ShiftWarning> actual = service.calculate(period, List.of(skilled, eveningSlot),
                List.of(assigned(trainee, D1, skilled)), Map.of(D1, Map.of(DAY, 2, EVENING, 0)), D1);

        assertThat(types(actual)).containsExactly(WarningType.TRAINEE_ONLY, WarningType.SHORTAGE,
                WarningType.SKILL, WarningType.INACTIVE_STAFF);
    }

    @Test
    void calculate_日付と枠の順に並べる_枠は渡した順でなくIDの順() {
        Map<LocalDate, Map<Byte, Integer>> req = required(1, 1);

        List<ShiftWarning> actual = service.calculate(period, List.of(eveningSlot, daySlot), List.of(), req, D1);

        assertThat(actual).extracting(ShiftWarning::workDate)
                .containsExactly(D1, D1, D2, D2, D3, D3);
        assertThat(actual).extracting(ShiftWarning::slotId)
                .containsExactly(DAY, EVENING, DAY, EVENING, DAY, EVENING);
    }

    // ===== calculate（DBから取る版） =====

    @Test
    void calculate_期間を渡すと割当と必要人数を取って計算する() {
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));
        when(shiftAssignmentRepository.findByPeriodIdWithUser(1L)).thenReturn(List.of());
        when(requiredStaffService.requiredCounts(D1, D3)).thenReturn(required(1, 0));

        List<ShiftWarning> actual = service.calculate(period);

        assertThat(actual).hasSize(3);
        assertThat(types(actual)).containsOnly(WarningType.SHORTAGE);
        assertThat(actual).extracting(ShiftWarning::slotId).containsOnly(DAY);
    }

    // ===== countFor =====

    @Test
    void countFor_その日その枠と重なる出勤を数える() {
        ShiftAssignment inDay = assigned(regular(1L, "a"), D1, daySlot);
        ShiftAssignment inEvening = assigned(regular(2L, "b"), D1, eveningSlot);
        ShiftAssignment timeBoth = timeAssigned(regular(3L, "c"), D1, LocalTime.of(13, 0), LocalTime.of(22, 0));
        ShiftAssignment timeMorning = timeAssigned(regular(4L, "d"), D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(shiftAssignmentRepository.findAssignedByWorkDate(D1))
                .thenReturn(List.of(inDay, inEvening, timeBoth, timeMorning));

        assertThat(service.countFor(D1, daySlot)).isEqualTo(3);
        assertThat(service.countFor(D1, eveningSlot)).isEqualTo(2);
    }
}

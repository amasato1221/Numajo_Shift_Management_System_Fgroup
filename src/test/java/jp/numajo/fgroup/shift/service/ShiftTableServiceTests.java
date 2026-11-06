package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.ShiftBadge;
import jp.numajo.fgroup.shift.dto.ShiftTable;
import jp.numajo.fgroup.shift.dto.ShiftTableRow;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * ShiftTableService と ShiftTable#namesOn の単体テスト（20-SB-04 2.1-4、20-SM-08 3.1、97_テスト計画書 3.1）。
 *
 * <p>
 * Repository はモックにする。期間は 2026-11-01〜11-03（3日）、枠は日勤 8:00-17:00・夕勤 17:00-22:00。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   build の割当の絞り込み ：I＝includeAbsent、A＝割当が欠勤。条件は「I真 または A偽」
 *   build の行            ：有効なバイト（findActiveStaff）に、割当のある人を足す（putIfAbsent：もういる／いない）
 *   並び                  ：K＝ふりがなが null、同じふりがなは id 順
 *   toRow                ：U＝割当がその人のもの、S＝出勤（ASSIGNED）
 *   slotCounts           ：B＝バッジが欠勤、O＝枠と時間が重なる
 *   namesOn              ：Y＝バッジの種類が同じ、T＝時間の表示がある（時間指定）
 *
 * No. | メソッド    | 判定の値                                   | 結果
 *  1  | build      | I偽 A偽 ／ I偽 A真                          | 出勤だけ出す、欠勤は出さない
 *  2  | build      | I真 A偽 ／ I真 A真                          | 両方出す（欠勤は ABSENT のバッジ）
 *  3  | build      | 割当のない有効なバイト                         | 行は出る（cells は空、勤務日数 0）
 *  4  | build      | 割当のある無効なバイト（putIfAbsent：いない）     | 行を足す
 *  5  | build      | 割当のある有効なバイト（putIfAbsent：もういる）   | 1行だけ
 *  6  | build      | 欠勤だけの無効なバイト、I偽                    | 行を出さない
 *  7  | build      | 欠勤だけの無効なバイト、I真                    | 行を出す
 *  8  | build      | K：ふりがな順、同じなら id 順、null は最後        | 並び
 *  9  | build      | U真・U偽（2人の割当が混ざる）、同じ日に2つ（逆順） | 自分の割当だけ、開始時刻の順
 * 10  | build      | S真の日2つ＋同じ日に2つ／S偽の日（I真）          | 勤務日数は出勤のある日の数（2）
 * 11  | build      | －                                         | days は開始日〜終了日、行の値（氏名・研修中）、Repository の呼び方
 * 12  | slotCounts | B偽 O：日勤だけ真／夕勤だけ真                    | その枠だけ数える
 * 13  | slotCounts | B偽 時間指定 13:00-22:00（O：両方真）            | 両方の枠に数える
 * 14  | slotCounts | B偽 時間指定 8:00-12:00（O：日勤真 夕勤偽）       | 日勤だけ
 * 15  | slotCounts | B真                                       | 数えない
 * 16  | slotCounts | 2人が同じ日・同じ枠／割当のない日                  | 足し合わせる／全部の日・枠に 0
 * 17  | namesOn    | Y真 T偽 ／ Y真 T真 ／ Y偽                       | 名前／名前＋時間／入れない
 * 18  | namesOn    | その日の割当がない人・誰もいない日                  | 空
 *
 * 除外：A の値は ASSIGNED と ABSENT の2つだけなので、「A偽」は出勤と同じ。
 * slotCounts で表の外の日のバッジは、build が期間の割当しか入れないのでありえない。
 * </pre>
 */
class ShiftTableServiceTests {

    private static final Long PERIOD_ID = 5L;

    private final ShiftAssignmentRepository assignmentRepository = mock(ShiftAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);

    private final Slot daySlot = slot(SlotIds.DAY, "08:00", "17:00");
    private final Slot eveningSlot = slot(SlotIds.EVENING, "17:00", "22:00");

    private final ShiftPeriod period = period();

    private ShiftTableService service;

    @BeforeEach
    void setUp() {
        service = new ShiftTableService(assignmentRepository, userRepository, slotRepository);
        when(slotRepository.findAll(any(Sort.class))).thenReturn(List.of(daySlot, eveningSlot));
        when(userRepository.findActiveStaff()).thenReturn(List.of());
        when(assignmentRepository.findByPeriodIdWithUser(PERIOD_ID)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- build：欠勤の絞り込み

    @Test
    void 欠勤を出さない_出勤だけ出して欠勤は出さない() {
        User a = user(1L, "あ", true);
        givenActive(a);
        givenAssignments(assign(a, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-02", eveningSlot, AssignmentStatus.ABSENT));

        ShiftTable table = service.build(period, false);

        ShiftTableRow row = table.rows().get(0);
        assertThat(row.cells()).containsOnlyKeys(d("2026-11-01"));
        assertThat(row.cells().get(d("2026-11-01"))).extracting(ShiftBadge::type).containsExactly(BadgeType.DAY);
    }

    @Test
    void 欠勤も出す_出勤と欠勤の両方を出す() {
        User a = user(1L, "あ", true);
        givenActive(a);
        givenAssignments(assign(a, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-02", eveningSlot, AssignmentStatus.ABSENT));

        ShiftTable table = service.build(period, true);

        ShiftTableRow row = table.rows().get(0);
        assertThat(row.cells()).containsOnlyKeys(d("2026-11-01"), d("2026-11-02"));
        assertThat(row.cells().get(d("2026-11-02"))).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.ABSENT);
    }

    // ---------------------------------------------------------------- build：行に出す人

    @Test
    void 割当のない有効なバイト_行は出て中は空() {
        givenActive(user(1L, "あ", true));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).cells()).isEmpty();
        assertThat(table.rows().get(0).workDays()).isZero();
    }

    @Test
    void 割当のある無効なバイト_行を足す() {
        User active = user(1L, "あ", true);
        User inactive = user(2L, "い", false);
        givenActive(active);
        givenAssignments(assign(inactive, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows()).extracting(ShiftTableRow::userId).containsExactly(1L, 2L);
    }

    @Test
    void 割当のある有効なバイト_同じ人は1行だけ() {
        User a = user(1L, "あ", true);
        givenActive(a);
        givenAssignments(assign(a, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-02", daySlot, AssignmentStatus.ASSIGNED));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows()).extracting(ShiftTableRow::userId).containsExactly(1L);
    }

    @Test
    void 欠勤だけの無効なバイトで欠勤を出さない_行を出さない() {
        User inactive = user(2L, "い", false);
        givenAssignments(assign(inactive, "2026-11-01", daySlot, AssignmentStatus.ABSENT));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows()).isEmpty();
    }

    @Test
    void 欠勤だけの無効なバイトで欠勤も出す_行を出す() {
        User inactive = user(2L, "い", false);
        givenAssignments(assign(inactive, "2026-11-01", daySlot, AssignmentStatus.ABSENT));

        ShiftTable table = service.build(period, true);

        assertThat(table.rows()).extracting(ShiftTableRow::userId).containsExactly(2L);
        assertThat(table.rows().get(0).workDays()).isZero();
    }

    @Test
    void 並び_ふりがな順で同じなら_id順_ふりがながnullの人は最後() {
        User noKana = user(1L, null, true);
        User sato2 = user(4L, "さとう", true);
        User sato1 = user(3L, "さとう", true);
        User aoki = user(5L, "あおき", false);
        givenActive(noKana, sato2, sato1);
        givenAssignments(assign(aoki, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows()).extracting(ShiftTableRow::userId).containsExactly(5L, 3L, 4L, 1L);
    }

    @Test
    void セル_自分の割当だけを開始時刻の順に入れる() {
        User a = user(1L, "あ", true);
        User b = user(2L, "い", true);
        givenActive(a, b);
        givenAssignments(assign(a, "2026-11-01", eveningSlot, AssignmentStatus.ASSIGNED),
                assign(b, "2026-11-01", null, "13:00", "22:00", AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED));

        ShiftTable table = service.build(period, false);

        assertThat(table.rows().get(0).cells().get(d("2026-11-01"))).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.DAY, BadgeType.EVENING);
        assertThat(table.rows().get(1).cells().get(d("2026-11-01"))).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.TIME);
    }

    @Test
    void 勤務日数_出勤のある日の数で同じ日の2つは1日_欠勤だけの日は数えない() {
        User a = user(1L, "あ", true);
        givenActive(a);
        givenAssignments(assign(a, "2026-11-01", daySlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-01", eveningSlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-02", daySlot, AssignmentStatus.ASSIGNED),
                assign(a, "2026-11-03", daySlot, AssignmentStatus.ABSENT));

        ShiftTable table = service.build(period, true);

        assertThat(table.rows().get(0).workDays()).isEqualTo(2);
    }

    @Test
    void 日付と行の値_開始日から終了日までと氏名と研修中() {
        User a = user(1L, "あ", true);
        a.setTrainee(true);
        givenActive(a);

        ShiftTable table = service.build(period, false);

        assertThat(table.days()).containsExactly(d("2026-11-01"), d("2026-11-02"), d("2026-11-03"));
        ShiftTableRow row = table.rows().get(0);
        assertThat(row.name()).isEqualTo("氏名1");
        assertThat(row.trainee()).isTrue();
        verify(assignmentRepository).findByPeriodIdWithUser(PERIOD_ID);
        verify(slotRepository).findAll(Sort.by("id"));
    }

    // ---------------------------------------------------------------- slotCounts

    @Test
    void 人数_日勤と夕勤はその枠だけ数える() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00"),
                badge(BadgeType.EVENING, "17:00", "22:00")));

        Map<LocalDate, Map<Byte, Integer>> counts = service.slotCounts(table);

        assertThat(counts.get(d("2026-11-01"))).containsEntry(SlotIds.DAY, 1).containsEntry(SlotIds.EVENING, 1);
    }

    @Test
    void 人数_時間指定が両方の枠と重なる_両方に数える() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.TIME, "13:00", "22:00")));

        Map<LocalDate, Map<Byte, Integer>> counts = service.slotCounts(table);

        assertThat(counts.get(d("2026-11-01"))).containsEntry(SlotIds.DAY, 1).containsEntry(SlotIds.EVENING, 1);
    }

    @Test
    void 人数_時間指定が日勤とだけ重なる_日勤だけ数える() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.TIME, "08:00", "12:00")));

        Map<LocalDate, Map<Byte, Integer>> counts = service.slotCounts(table);

        assertThat(counts.get(d("2026-11-01"))).containsEntry(SlotIds.DAY, 1).containsEntry(SlotIds.EVENING, 0);
    }

    @Test
    void 人数_欠勤は数えない() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.ABSENT, "08:00", "17:00")));

        Map<LocalDate, Map<Byte, Integer>> counts = service.slotCounts(table);

        assertThat(counts.get(d("2026-11-01"))).containsEntry(SlotIds.DAY, 0).containsEntry(SlotIds.EVENING, 0);
    }

    @Test
    void 人数_2人を足し合わせ_割当のない日も全部の枠を0で入れる() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00")),
                row(2L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00")));

        Map<LocalDate, Map<Byte, Integer>> counts = service.slotCounts(table);

        assertThat(counts).containsOnlyKeys(d("2026-11-01"), d("2026-11-02"), d("2026-11-03"));
        assertThat(counts.get(d("2026-11-01"))).containsEntry(SlotIds.DAY, 2).containsEntry(SlotIds.EVENING, 0);
        assertThat(counts.get(d("2026-11-02"))).containsExactly(Map.entry(SlotIds.DAY, 0),
                Map.entry(SlotIds.EVENING, 0));
    }

    // ---------------------------------------------------------------- ShiftTable#namesOn

    @Test
    void 名前_種類が同じ人だけ並べ時間指定は時間を付ける() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00")),
                row(2L, "2026-11-01", new ShiftBadge(BadgeType.TIME, "☀☾", "13-22", t("13:00"), t("22:00"))),
                row(3L, "2026-11-01", badge(BadgeType.EVENING, "17:00", "22:00")),
                row(4L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00")));

        assertThat(table.namesOn(d("2026-11-01"), BadgeType.DAY)).containsExactly("氏名1", "氏名4");
        assertThat(table.namesOn(d("2026-11-01"), BadgeType.TIME)).containsExactly("氏名2 13-22");
        assertThat(table.namesOn(d("2026-11-01"), BadgeType.EVENING)).containsExactly("氏名3");
    }

    @Test
    void 名前_その日の割当がない_空() {
        ShiftTable table = table(row(1L, "2026-11-01", badge(BadgeType.DAY, "08:00", "17:00")));

        assertThat(table.namesOn(d("2026-11-02"), BadgeType.DAY)).isEmpty();
    }

    // ---------------------------------------------------------------- 準備

    private void givenActive(User... users) {
        when(userRepository.findActiveStaff()).thenReturn(Arrays.asList(users));
    }

    private void givenAssignments(ShiftAssignment... assignments) {
        when(assignmentRepository.findByPeriodIdWithUser(PERIOD_ID)).thenReturn(Arrays.asList(assignments));
    }

    private static Slot slot(byte id, String start, String end) {
        Slot s = new Slot();
        s.setId(id);
        s.setStartTime(t(start));
        s.setEndTime(t(end));
        return s;
    }

    private static ShiftPeriod period() {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(PERIOD_ID);
        p.setStartDate(d("2026-11-01"));
        p.setEndDate(d("2026-11-03"));
        p.setStatus(PeriodStatus.PUBLISHED);
        return p;
    }

    private static User user(Long id, String kana, boolean active) {
        User u = new User();
        u.setId(id);
        u.setName("氏名" + id);
        u.setNameKana(kana);
        u.setRole(Role.STAFF);
        u.setActive(active);
        return u;
    }

    private ShiftAssignment assign(User u, String date, Slot slot, AssignmentStatus status) {
        return assign(u, date, slot, slot.getStartTime().toString(), slot.getEndTime().toString(), status);
    }

    private ShiftAssignment assign(User u, String date, Slot slot, String start, String end,
            AssignmentStatus status) {
        ShiftAssignment a = new ShiftAssignment();
        a.setPeriod(period);
        a.setUser(u);
        a.setWorkDate(d(date));
        a.setSlot(slot);
        a.setStartTime(t(start));
        a.setEndTime(t(end));
        a.setStatus(status);
        return a;
    }

    private ShiftTable table(ShiftTableRow... rows) {
        return new ShiftTable(List.of(d("2026-11-01"), d("2026-11-02"), d("2026-11-03")),
                new ArrayList<>(Arrays.asList(rows)));
    }

    private static ShiftTableRow row(Long userId, String date, ShiftBadge... badges) {
        return new ShiftTableRow(userId, "氏名" + userId, false, Map.of(d(date), List.of(badges)), 1);
    }

    private static ShiftBadge badge(BadgeType type, String start, String end) {
        return new ShiftBadge(type, "☀", null, t(start), t(end));
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static LocalTime t(String s) {
        return LocalTime.parse(s);
    }
}

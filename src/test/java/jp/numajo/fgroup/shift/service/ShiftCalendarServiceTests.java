package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

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

/**
 * ShiftCalendarService の単体テスト（20-SB-01 2.2・2.3、20-SB-02 2.1-3、20-00 4章）。
 *
 * <p>
 * Repository はモックにする。今日は Clock を 2026-10-21（水）に固定する（97_テスト計画書 3.1）。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   buildBadges の割当   ：P＝期間が公開済み
 *   buildBadges の希望   ：P＝期間が公開済み、O＝○（OK）、T＝時間指定（TIME）。条件は「P偽 かつ （O真 または T真）」
 *   toBadge             ：A＝欠勤、N＝枠がない（時間指定）、E＝枠が夕勤
 *   toPendingBadge      ：S＝枠がある（○）、E＝枠が夕勤
 *   重なる枠のアイコン    ：D＝日勤と重なる、V＝夕勤と重なる
 *   日付が範囲内か        ：F＝始まりの日以後、L＝終わりの日以前
 *   periodIdOf          ：F・L（上と同じ）を期間ごとに見る
 *
 * No. | メソッド        | 判定の値                         | 結果
 *  1  | buildBadges    | 割当 P真                          | 割当のバッジを出す
 *  2  | buildBadges    | 割当 P偽                          | 出さない
 *  3  | buildBadges    | 希望 P偽 O真 T偽                   | 未確定のバッジを出す
 *  4  | buildBadges    | 希望 P偽 O偽 T真                   | 未確定のバッジを出す
 *  5  | buildBadges    | 希望 P偽 O偽 T偽（×）              | 出さない
 *  6  | buildBadges    | 希望 P真 O真 T偽                   | 出さない（公開済みは割当を出す）
 *  7  | buildBadges    | 希望 P真 O偽 T真                   | 出さない
 *  8  | buildBadges    | 希望 P真 O偽 T偽                   | 出さない
 *  9  | buildBadges    | 同じ日に3つ（逆順で返る）            | 開始時刻の順、枠は id 順で取る
 * 10  | buildBadges    | 割当も希望もない                    | 空
 * 11  | toBadge        | A偽 N偽 E偽                       | DAY ☀、時間なし
 * 12  | toBadge        | A偽 N偽 E真                       | EVENING ☾、時間なし
 * 13  | toBadge        | A偽 N真（13:00-22:00）             | TIME ☀☾ 13-22
 * 14  | toBadge        | A真 N偽 E偽                       | ABSENT ☀、時間なし
 * 15  | toBadge        | A真 N偽 E真                       | ABSENT ☾、時間なし
 * 16  | toBadge        | A真 N真（17:30-22:00）             | ABSENT ☾ 17:30-22
 * 17  | toPendingBadge | S真 E偽                           | PENDING ☀、日勤の時刻 8:00-17:00
 * 18  | toPendingBadge | S真 E真                           | PENDING ☾、夕勤の時刻 17:00-22:00
 * 19  | toPendingBadge | S偽（13:30-22:00）                 | PENDING ☀☾ 13:30-22、希望の時刻
 * 20  | 重なる枠        | D真 V偽（8:00-12:30）              | ☀
 * 21  | 重なる枠        | D偽 V真（17:00-22:00、17時ちょうど） | ☾（終わりと始まりが同じ時刻は重ならない）
 * 22  | 重なる枠        | D偽 V偽（枠がない）                 | アイコンなし
 * 23  | buildMonth     | 2026年10月（1日が木曜、末日が土曜）   | 9/28〜11/1 の5週、範囲、前月・翌月
 * 24  | buildMonth     | 月の中の日／前月・翌月の日            | inMonth、範囲外はバッジを出さない
 * 25  | buildMonth     | 今日／今日でない                     | today
 * 26  | buildMonth     | periodIdOf F真L真 ／ F偽 ／ L偽       | 含む期間のID ／ 次の期間を見る ／ null
 * 27  | buildMonth     | 2026年6月（1日が月曜）、5月（末日が日曜）| 前後に足さない
 * 28  | buildMonth     | 2026年12月                         | 翌月は 2027年1月、前月は11月
 * 29  | buildPeriod    | 11/1（日）〜11/15（日）              | 10/26〜11/15 の3週、F偽の日・F真L真の日
 * 30  | buildPeriod    | 10/16（金）〜10/31（土）、今日10/21   | 10/12〜11/1、L偽の日、today、範囲外のバッジは出さない
 * 31  | buildPeriod    | －                                 | period は toOption の形（表示名・状態）
 *
 * 除外：日付の範囲で F偽 かつ L偽（始まりより前で、終わりより後）は、始まり ≦ 終わり なのでありえない。
 * 希望の O真 かつ T真 は、availability が1つの値なのでありえない。
 * buildWeeks の「7日たまらないまま終わる」は、始まりが月曜・終わりが日曜なのでありえない。
 * </pre>
 */
class ShiftCalendarServiceTests {

    private static final Long USER_ID = 10L;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final ShiftAssignmentRepository assignmentRepository = mock(ShiftAssignmentRepository.class);
    private final ShiftRequestRepository requestRepository = mock(ShiftRequestRepository.class);
    private final ShiftPeriodRepository periodRepository = mock(ShiftPeriodRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);

    private final Slot daySlot = slot(SlotIds.DAY, "DAY", "08:00", "17:00");
    private final Slot eveningSlot = slot(SlotIds.EVENING, "EVENING", "17:00", "22:00");
    private final List<Slot> slots = List.of(daySlot, eveningSlot);

    private ShiftCalendarService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(LocalDateTime.parse("2026-10-21T09:00:00").atZone(TOKYO).toInstant(), TOKYO);
        service = new ShiftCalendarService(assignmentRepository, requestRepository, periodRepository, slotRepository,
                clock);
        when(slotRepository.findAll(any(Sort.class))).thenReturn(slots);
    }

    // ---------- buildBadges ----------

    @Test
    void 公開済みの期間の割当_バッジを出す() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        givenAssignments(assignment(published, "2026-10-22", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-16"), d("2026-10-31"));

        assertThat(actual).containsOnlyKeys(d("2026-10-22"));
        assertThat(actual.get(d("2026-10-22"))).extracting(ShiftBadge::type).containsExactly(BadgeType.DAY);
        verify(assignmentRepository).findByUserIdAndWorkDateBetween(USER_ID, d("2026-10-16"), d("2026-10-31"));
        verify(requestRepository).findByUserIdAndWorkDateBetween(USER_ID, d("2026-10-16"), d("2026-10-31"));
    }

    @Test
    void 公開前の期間の割当_下書きなので出さない() {
        ShiftPeriod closed = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.CLOSED);
        givenAssignments(assignment(closed, "2026-11-02", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-11-01"), d("2026-11-15"));

        assertThat(actual).isEmpty();
    }

    @Test
    void 公開前の期間の希望が丸_未確定を出す() {
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);
        givenRequests(request(open, "2026-11-02", eveningSlot, Availability.OK, null, null));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-11-01"), d("2026-11-15"));

        assertThat(actual.get(d("2026-11-02"))).extracting(ShiftBadge::type).containsExactly(BadgeType.PENDING);
    }

    @Test
    void 公開前の期間の希望が時間指定_未確定を出す() {
        ShiftPeriod closed = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.CLOSED);
        givenRequests(request(closed, "2026-11-03", null, Availability.TIME, "13:00", "22:00"));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-11-01"), d("2026-11-15"));

        assertThat(actual.get(d("2026-11-03"))).extracting(ShiftBadge::type).containsExactly(BadgeType.PENDING);
    }

    @Test
    void 公開前の期間の希望がバツ_出さない() {
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);
        givenRequests(request(open, "2026-11-02", daySlot, Availability.NG, null, null));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-11-01"), d("2026-11-15"));

        assertThat(actual).isEmpty();
    }

    @Test
    void 公開済みの期間の希望が丸_出さない() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        givenRequests(request(published, "2026-10-22", daySlot, Availability.OK, null, null));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-16"), d("2026-10-31"));

        assertThat(actual).isEmpty();
    }

    @Test
    void 公開済みの期間の希望が時間指定_出さない() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        givenRequests(request(published, "2026-10-22", null, Availability.TIME, "13:00", "22:00"));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-16"), d("2026-10-31"));

        assertThat(actual).isEmpty();
    }

    @Test
    void 公開済みの期間の希望がバツ_出さない() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        givenRequests(request(published, "2026-10-22", daySlot, Availability.NG, null, null));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-16"), d("2026-10-31"));

        assertThat(actual).isEmpty();
    }

    @Test
    void 同じ日に3つ_開始時刻の順に並べる() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);
        givenAssignments(
                assignment(published, "2026-10-31", eveningSlot, "17:00", "22:00", AssignmentStatus.ASSIGNED),
                assignment(published, "2026-10-31", null, "10:00", "14:00", AssignmentStatus.ASSIGNED),
                assignment(published, "2026-10-31", daySlot, "08:00", "17:00", AssignmentStatus.ABSENT));
        givenRequests(request(open, "2026-11-01", daySlot, Availability.OK, null, null));

        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-26"), d("2026-11-01"));

        assertThat(actual).containsOnlyKeys(d("2026-10-31"), d("2026-11-01"));
        assertThat(actual.get(d("2026-10-31"))).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.ABSENT, BadgeType.TIME, BadgeType.EVENING);
        verify(slotRepository).findAll(Sort.by("id"));
    }

    @Test
    void 割当も希望もない_空() {
        Map<LocalDate, List<ShiftBadge>> actual = service.buildBadges(USER_ID, d("2026-10-16"), d("2026-10-31"));

        assertThat(actual).isEmpty();
    }

    // ---------- toBadge ----------

    @Test
    void 出勤で日勤_日勤のバッジ() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.DAY, "☀", null, t("08:00"), t("17:00")));
    }

    @Test
    void 出勤で夕勤_夕勤のバッジ() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", eveningSlot, "17:00", "22:00", AssignmentStatus.ASSIGNED), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.EVENING, "☾", null, t("17:00"), t("22:00")));
    }

    @Test
    void 出勤で時間指定_重なる枠のアイコンと時間() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-23", null, "13:00", "22:00", AssignmentStatus.ASSIGNED), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.TIME, "☀☾", "13-22", t("13:00"), t("22:00")));
    }

    @Test
    void 欠勤で日勤_欠勤のバッジに日勤のアイコン() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", daySlot, "08:00", "17:00", AssignmentStatus.ABSENT), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.ABSENT, "☀", null, t("08:00"), t("17:00")));
    }

    @Test
    void 欠勤で夕勤_欠勤のバッジに夕勤のアイコン() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", eveningSlot, "17:00", "22:00", AssignmentStatus.ABSENT), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.ABSENT, "☾", null, t("17:00"), t("22:00")));
    }

    @Test
    void 欠勤で時間指定_欠勤のバッジに時間() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", null, "17:30", "22:00", AssignmentStatus.ABSENT), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.ABSENT, "☾", "17:30-22", t("17:30"), t("22:00")));
    }

    // ---------- toPendingBadge ----------

    @Test
    void 希望が日勤の丸_日勤の時刻の未確定() {
        ShiftBadge actual = ShiftCalendarService.toPendingBadge(
                request(null, "2026-11-02", daySlot, Availability.OK, null, null), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.PENDING, "☀", null, t("08:00"), t("17:00")));
    }

    @Test
    void 希望が夕勤の丸_夕勤の時刻の未確定() {
        ShiftBadge actual = ShiftCalendarService.toPendingBadge(
                request(null, "2026-11-02", eveningSlot, Availability.OK, null, null), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.PENDING, "☾", null, t("17:00"), t("22:00")));
    }

    @Test
    void 希望が時間指定_希望の時刻の未確定() {
        ShiftBadge actual = ShiftCalendarService.toPendingBadge(
                request(null, "2026-11-02", null, Availability.TIME, "13:30", "22:00"), slots);

        assertThat(actual).isEqualTo(new ShiftBadge(BadgeType.PENDING, "☀☾", "13:30-22", t("13:30"), t("22:00")));
    }

    // ---------- 重なる枠のアイコン ----------

    @Test
    void 日勤だけと重なる時間指定_日勤のアイコン() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", null, "08:00", "12:30", AssignmentStatus.ASSIGNED), slots);

        assertThat(actual.icons()).isEqualTo("☀");
        assertThat(actual.timeText()).isEqualTo("8-12:30");
    }

    @Test
    void 十七時ちょうどから_日勤とは重ならず夕勤のアイコン() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", null, "17:00", "22:00", AssignmentStatus.ASSIGNED), slots);

        assertThat(actual.icons()).isEqualTo("☾");
    }

    @Test
    void 重なる枠がない_アイコンなし() {
        ShiftBadge actual = ShiftCalendarService.toBadge(
                assignment(null, "2026-10-22", null, "13:00", "22:00", AssignmentStatus.ASSIGNED), List.of());

        assertThat(actual.icons()).isEmpty();
    }

    // ---------- buildMonth ----------

    @Test
    void 十月_前後の週を足して5週() {
        MonthCalendar actual = service.buildMonth(USER_ID, YearMonth.of(2026, 10));

        assertThat(actual.ym()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(actual.ymLabel()).isEqualTo("2026年10月");
        assertThat(actual.prevYm()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(actual.nextYm()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(actual.weeks()).hasSize(5).allSatisfy(week -> assertThat(week).hasSize(7));
        assertThat(actual.weeks().get(0).get(0).date()).isEqualTo(d("2026-09-28"));
        assertThat(actual.weeks().get(4).get(6).date()).isEqualTo(d("2026-11-01"));
        // 日付の数字の〜Label（前月・翌月の日も入れる）
        assertThat(actual.weeks().get(0).get(0).dayLabel()).isEqualTo("28");
        assertThat(actual.weeks().get(4).get(6).dayLabel()).isEqualTo("1");
        verify(assignmentRepository).findByUserIdAndWorkDateBetween(USER_ID, d("2026-09-28"), d("2026-11-01"));
        verify(periodRepository).findOverlapping(d("2026-09-28"), d("2026-11-01"));
    }

    @Test
    void 十月_前月翌月の日はバッジを出さない() {
        ShiftPeriod published = period(1L, "2026-09-16", "2026-10-31", PeriodStatus.PUBLISHED);
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);
        givenAssignments(
                assignment(published, "2026-09-28", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED),
                assignment(published, "2026-10-01", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED));
        givenRequests(request(open, "2026-11-01", daySlot, Availability.OK, null, null));

        MonthCalendar actual = service.buildMonth(USER_ID, YearMonth.of(2026, 10));

        CalendarDay sep28 = day(actual.weeks(), d("2026-09-28"));
        CalendarDay oct1 = day(actual.weeks(), d("2026-10-01"));
        CalendarDay nov1 = day(actual.weeks(), d("2026-11-01"));
        assertThat(sep28.inMonth()).isFalse();
        assertThat(sep28.badges()).isEmpty();
        assertThat(oct1.inMonth()).isTrue();
        assertThat(oct1.badges()).hasSize(1);
        assertThat(nov1.inMonth()).isFalse();
        assertThat(nov1.badges()).isEmpty();
        assertThat(day(actual.weeks(), d("2026-10-02")).badges()).isEmpty();
    }

    @Test
    void 十月_今日だけ今日の印() {
        MonthCalendar actual = service.buildMonth(USER_ID, YearMonth.of(2026, 10));

        assertThat(day(actual.weeks(), d("2026-10-21")).today()).isTrue();
        assertThat(day(actual.weeks(), d("2026-10-22")).today()).isFalse();
    }

    @Test
    void 十月_その日を含む期間のID() {
        when(periodRepository.findOverlapping(any(), any())).thenReturn(List.of(
                period(1L, "2026-10-01", "2026-10-15", PeriodStatus.PUBLISHED),
                period(2L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED)));

        MonthCalendar actual = service.buildMonth(USER_ID, YearMonth.of(2026, 10));

        // F真L真：1つ目に入る
        assertThat(day(actual.weeks(), d("2026-10-15")).periodId()).isEqualTo(1L);
        // 1つ目で L偽、2つ目で F真L真
        assertThat(day(actual.weeks(), d("2026-10-16")).periodId()).isEqualTo(2L);
        // 1つ目で F偽（始まりより前）、2つ目でも F偽 → null
        assertThat(day(actual.weeks(), d("2026-09-30")).periodId()).isNull();
        // 2つ目で L偽 → null
        assertThat(day(actual.weeks(), d("2026-11-01")).periodId()).isNull();
    }

    @Test
    void 一日が月曜の月と末日が日曜の月_前後に週を足さない() {
        MonthCalendar june = service.buildMonth(USER_ID, YearMonth.of(2026, 6));
        MonthCalendar may = service.buildMonth(USER_ID, YearMonth.of(2026, 5));

        assertThat(june.weeks().get(0).get(0).date()).isEqualTo(d("2026-06-01"));
        assertThat(june.weeks().get(0).get(0).inMonth()).isTrue();
        assertThat(may.weeks().get(may.weeks().size() - 1).get(6).date()).isEqualTo(d("2026-05-31"));
        assertThat(may.weeks().get(may.weeks().size() - 1).get(6).inMonth()).isTrue();
    }

    @Test
    void 十二月_翌月は次の年の一月() {
        MonthCalendar actual = service.buildMonth(USER_ID, YearMonth.of(2026, 12));

        assertThat(actual.prevYm()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(actual.nextYm()).isEqualTo(YearMonth.of(2027, 1));
    }

    // ---------- buildPeriod ----------

    @Test
    void 十一月前半_前の週の月曜から3週() {
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);

        PeriodCalendar actual = service.buildPeriod(USER_ID, open);

        assertThat(actual.weeks()).hasSize(3).allSatisfy(week -> assertThat(week).hasSize(7));
        assertThat(actual.weeks().get(0).get(0).date()).isEqualTo(d("2026-10-26"));
        assertThat(actual.weeks().get(2).get(6).date()).isEqualTo(d("2026-11-15"));
        assertThat(actual.weeks().get(0).get(0).dayLabel()).isEqualTo("26");
        assertThat(actual.weeks().get(2).get(6).dayLabel()).isEqualTo("15");
        // F偽：期間より前の日
        CalendarDay oct31 = day(actual.weeks(), d("2026-10-31"));
        assertThat(oct31.inMonth()).isFalse();
        assertThat(oct31.periodId()).isNull();
        // F真L真：期間の始まりと終わりの日
        assertThat(day(actual.weeks(), d("2026-11-01")).inMonth()).isTrue();
        assertThat(day(actual.weeks(), d("2026-11-01")).periodId()).isEqualTo(2L);
        assertThat(day(actual.weeks(), d("2026-11-15")).inMonth()).isTrue();
        verify(assignmentRepository).findByUserIdAndWorkDateBetween(USER_ID, d("2026-11-01"), d("2026-11-15"));
    }

    @Test
    void 十月後半_期間の後の日はバッジを出さず今日に印() {
        ShiftPeriod published = period(1L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED);
        ShiftPeriod next = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.PUBLISHED);
        // モックなので範囲外の日も返せる。期間外の日に出ないことを確かめる
        givenAssignments(
                assignment(published, "2026-10-21", daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED),
                assignment(next, "2026-11-01", eveningSlot, "17:00", "22:00", AssignmentStatus.ASSIGNED));

        PeriodCalendar actual = service.buildPeriod(USER_ID, published);

        assertThat(actual.weeks().get(0).get(0).date()).isEqualTo(d("2026-10-12"));
        CalendarDay oct21 = day(actual.weeks(), d("2026-10-21"));
        assertThat(oct21.today()).isTrue();
        assertThat(oct21.badges()).extracting(ShiftBadge::type).containsExactly(BadgeType.DAY);
        // L偽：期間より後の日
        CalendarDay nov1 = day(actual.weeks(), d("2026-11-01"));
        assertThat(nov1.inMonth()).isFalse();
        assertThat(nov1.badges()).isEmpty();
        assertThat(nov1.periodId()).isNull();
        assertThat(day(actual.weeks(), d("2026-10-22")).today()).isFalse();
    }

    @Test
    void 期間の選択肢の形で返す() {
        ShiftPeriod open = period(2L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN);

        PeriodCalendar actual = service.buildPeriod(USER_ID, open);

        assertThat(actual.period().id()).isEqualTo(2L);
        assertThat(actual.period().status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(actual.period().label()).isEqualTo("11/1〜11/15（期限 10/15）");
    }

    // ---------- テスト用の部品 ----------

    private void givenAssignments(ShiftAssignment... assignments) {
        when(assignmentRepository.findByUserIdAndWorkDateBetween(any(), any(), any())).thenReturn(List.of(assignments));
    }

    private void givenRequests(ShiftRequest... requests) {
        when(requestRepository.findByUserIdAndWorkDateBetween(any(), any(), any())).thenReturn(List.of(requests));
    }

    private static CalendarDay day(List<List<CalendarDay>> weeks, LocalDate date) {
        return weeks.stream().flatMap(List::stream).filter(x -> x.date().equals(date)).findFirst().orElseThrow();
    }

    private static Slot slot(byte id, String code, String start, String end) {
        Slot s = new Slot();
        s.setId(id);
        s.setCode(code);
        s.setStartTime(t(start));
        s.setEndTime(t(end));
        return s;
    }

    private static ShiftPeriod period(Long id, String start, String end, PeriodStatus status) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStartDate(d(start));
        p.setEndDate(d(end));
        p.setStatus(status);
        p.setDeadlineAt(PeriodService.deadlineOf(d(start)));
        return p;
    }

    private static ShiftAssignment assignment(ShiftPeriod period, String date, Slot slot, String start, String end,
            AssignmentStatus status) {
        ShiftAssignment a = new ShiftAssignment();
        a.setPeriod(period);
        a.setWorkDate(d(date));
        a.setSlot(slot);
        a.setStartTime(t(start));
        a.setEndTime(t(end));
        a.setStatus(status);
        return a;
    }

    private static ShiftRequest request(ShiftPeriod period, String date, Slot slot, Availability availability,
            String start, String end) {
        ShiftRequest r = new ShiftRequest();
        r.setPeriod(period);
        r.setWorkDate(d(date));
        r.setSlot(slot);
        r.setAvailability(availability);
        r.setStartTime(start == null ? null : t(start));
        r.setEndTime(end == null ? null : t(end));
        return r;
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static LocalTime t(String s) {
        return LocalTime.parse(s);
    }
}

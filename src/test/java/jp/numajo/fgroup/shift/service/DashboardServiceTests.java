package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Sort;

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

/**
 * DashboardService の単体テスト（20-SM-01 2.1、6章 No.1〜9）。
 *
 * <p>
 * Repository・ShiftWarningService・RequiredStaffService はモックにする。今日は Clock で 2026-11-24 に固定する
 * （97_テスト計画書 3.1）。有効なバイトの絞り込み（No.2 の SQL 側）・昨日の欠勤（No.6）・32日前に終わった期間（No.7）は
 * Repository の条件なので、渡す引数（今日・今日−31日）を確かめ、DB を使った確認は ManagerHomeFlowTests で行う。
 * </p>
 *
 * <pre>
 * 命令網羅・判定条件網羅（判定ごとに真・偽の両方を通す）：
 *  No | 判定                                              | 真                               | 偽
 *  1  | 提出状況：OPEN の期間の for                        | 2期間（No.1）                     | 0期間（I2101）
 *  2  | 提出状況：!submitted.contains(id)（未提出か）       | 未提出の人                        | 提出した人・全員提出（No.3）
 *  3  | 人数不足：CLOSED の期間の for                      | 1〜2期間                          | 0期間（I2102）
 *  4  | 人数不足：w.type() == SHORTAGE                     | SHORTAGE                          | SKILL など（No.5 の作成中だけは引数で）
 *  5  | 人数不足：10件を超えるか（limit・moreShortages）    | 11件 → 10件＋1（No.4）            | 10件 → 10件＋0
 *  6  | 欠員：absences.isEmpty()                           | 0件 → 枠を取らない（I2103）       | 1件以上
 *  7  | countSlot：assignment.getSlot() != null            | 枠の割当 → その枠                 | 時間指定
 *  8  | countSlot：重なる枠の for・overlaps                | 重なる枠がある → 最初の枠（No.8） | どの枠とも重ならない → null（No.9）
 *  9  | toVacancy：notifiedAt == null                      | 通知していない → Label も null    | 通知済み → 短い日時
 * 10  | toVacancy：hasCount（countSlot != null）            | 人数を数える                      | 0／0 で countFor を呼ばない
 * 11  | slotLabel：slot == null                            | 「時間指定 13:00〜22:00」          | ☀日勤・☾夕勤
 * 12  | 募集期間の for・submitted.contains(id)             | 提出した人を数える                | 0期間・数えない人
 * 13  | submittedIds：同じ期間の2回目（computeIfAbsent）    | キャッシュを使う（1回だけ取る）   | 初めて → 取る
 *
 * 条件網羅・複数条件網羅（icon の slotId != null &amp;&amp; slotId == EVENING）：
 *   A（!= null）| B（== EVENING）| 結果 | テスト
 *   偽          | －（見ない）   | ☀    | icon_null
 *   真          | 真             | ☾    | icon_夕勤
 *   真          | 偽             | ☀    | icon_日勤
 * </pre>
 */
class DashboardServiceTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-24");

    private final ShiftPeriodRepository periodRepository = mock(ShiftPeriodRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RequestSubmissionRepository submissionRepository = mock(RequestSubmissionRepository.class);
    private final AbsenceRepository absenceRepository = mock(AbsenceRepository.class);
    private final ShiftWarningService warningService = mock(ShiftWarningService.class);
    private final RequiredStaffService requiredStaffService = mock(RequiredStaffService.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);

    private final Slot daySlot = slot(SlotIds.DAY, "日勤", "08:00", "17:00");
    private final Slot eveningSlot = slot(SlotIds.EVENING, "夕勤", "17:00", "22:00");

    private DashboardService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(TOKYO).toInstant(), TOKYO);
        service = new DashboardService(periodRepository, userRepository, submissionRepository, absenceRepository,
                warningService, requiredStaffService, slotRepository, clock);
        when(userRepository.findActiveStaff()).thenReturn(List.of());
        when(periodRepository.findByStatusOrderByStartDateAsc(any())).thenReturn(List.of());
        when(periodRepository.findByEndDateGreaterThanEqualOrderByStartDateAsc(any())).thenReturn(List.of());
        when(absenceRepository.findUpcoming(any())).thenReturn(List.of());
        when(slotRepository.findAll(any(Sort.class))).thenReturn(List.of(daySlot, eveningSlot));
    }

    // ---------------------------------------------------------------- 準備

    private static Slot slot(byte id, String name, String start, String end) {
        Slot s = new Slot();
        s.setId(id);
        s.setName(name);
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        return s;
    }

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        return u;
    }

    private static ShiftPeriod period(long id, String start, String end, PeriodStatus status) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStartDate(LocalDate.parse(start));
        p.setEndDate(LocalDate.parse(end));
        p.setDeadlineAt(LocalDate.parse(start).minusDays(17).atTime(23, 59));
        p.setStatus(status);
        return p;
    }

    private static ShiftWarning warning(WarningType type, String date, byte slotId, String slotName, int assigned,
            int required) {
        LocalDate d = LocalDate.parse(date);
        return new ShiftWarning(type, d, "L" + date, slotId, "W2601",
                new Object[] { "L" + date, slotName, assigned, required });
    }

    private static Absence absence(String date, Slot slot, String start, String end, String name,
            LocalDateTime notifiedAt) {
        ShiftAssignment a = new ShiftAssignment();
        a.setWorkDate(LocalDate.parse(date));
        a.setSlot(slot);
        a.setStartTime(LocalTime.parse(start));
        a.setEndTime(LocalTime.parse(end));
        a.setUser(user(99L, name));
        Absence b = new Absence();
        b.setAssignment(a);
        b.setNotifiedAt(notifiedAt);
        return b;
    }

    // ---------------------------------------------------------------- 1 提出状況（判定 No.1・2）

    @Test
    void すべて0件_空のリストとほか0件で枠を取らない() {
        DashboardView actual = service.getDashboard();

        assertThat(actual.submissionStatuses()).isEmpty();
        assertThat(actual.shortages()).isEmpty();
        assertThat(actual.moreShortages()).isZero();
        assertThat(actual.vacancies()).isEmpty();
        assertThat(actual.periods()).isEmpty();
        verify(absenceRepository).findUpcoming(TODAY);
        verify(slotRepository, never()).findAll(any(Sort.class));
        verify(warningService, never()).calculate(any(ShiftPeriod.class));
    }

    @Test
    void No1_No2_受付中が2期間_期間ごとに提出数と未提出者がふりがな順() {
        // findActiveStaff は有効なバイトだけ・ふりがな順で返す（無効なバイトは入らない）
        when(userRepository.findActiveStaff()).thenReturn(
                List.of(user(1L, "相田"), user(2L, "伊藤"), user(3L, "上野")));
        ShiftPeriod p1 = period(10L, "2026-12-01", "2026-12-15", PeriodStatus.OPEN);
        ShiftPeriod p2 = period(11L, "2026-12-16", "2026-12-31", PeriodStatus.OPEN);
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN)).thenReturn(List.of(p1, p2));
        // 9 は無効になったバイト（提出はあるが数えない）
        when(submissionRepository.findUserIdsByPeriodId(10L)).thenReturn(List.of(2L, 9L));
        when(submissionRepository.findUserIdsByPeriodId(11L)).thenReturn(List.of());

        List<SubmissionStatusView> actual = service.getDashboard().submissionStatuses();

        assertThat(actual).hasSize(2);
        assertThat(actual.get(0).period().id()).isEqualTo(10L);
        assertThat(actual.get(0).period().periodLabel()).isEqualTo("12/1〜12/15");
        assertThat(actual.get(0).period().deadlineShortLabel()).isEqualTo("11/14 23:59");
        assertThat(actual.get(0).submitted()).isEqualTo(1);
        assertThat(actual.get(0).total()).isEqualTo(3);
        assertThat(actual.get(0).unsubmittedNames()).containsExactly("相田", "上野");
        assertThat(actual.get(1).submitted()).isZero();
        assertThat(actual.get(1).unsubmittedNames()).containsExactly("相田", "伊藤", "上野");
    }

    @Test
    void No3_全員提出_未提出者は空で提出数は全員() {
        when(userRepository.findActiveStaff()).thenReturn(List.of(user(1L, "相田"), user(2L, "伊藤")));
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN))
                .thenReturn(List.of(period(10L, "2026-12-01", "2026-12-15", PeriodStatus.OPEN)));
        when(submissionRepository.findUserIdsByPeriodId(10L)).thenReturn(List.of(1L, 2L));

        SubmissionStatusView actual = service.getDashboard().submissionStatuses().get(0);

        assertThat(actual.submitted()).isEqualTo(2);
        assertThat(actual.total()).isEqualTo(2);
        assertThat(actual.unsubmittedNames()).isEmpty();
    }

    // ---------------------------------------------------------------- 2 人数不足（判定 No.3〜5）

    @Test
    void No5_人数不足は作成中の期間だけ取りSHORTAGE以外は捨てる() {
        ShiftPeriod closed = period(20L, "2026-11-16", "2026-11-30", PeriodStatus.CLOSED);
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)).thenReturn(List.of(closed));
        when(warningService.calculate(closed)).thenReturn(List.of(
                warning(WarningType.TRAINEE_ONLY, "2026-11-20", SlotIds.DAY, "日勤", 1, 1),
                warning(WarningType.SHORTAGE, "2026-11-20", SlotIds.EVENING, "夕勤", 1, 2),
                warning(WarningType.SKILL, "2026-11-21", SlotIds.DAY, "日勤", 2, 2)));

        DashboardView actual = service.getDashboard();

        assertThat(actual.shortages()).containsExactly(
                new ShortageView(20L, LocalDate.parse("2026-11-20"), "L2026-11-20", "☾夕勤", 1, 2));
        assertThat(actual.moreShortages()).isZero();
        verify(periodRepository).findByStatusOrderByStartDateAsc(PeriodStatus.CLOSED);
        verify(periodRepository, never()).findByStatusOrderByStartDateAsc(PeriodStatus.PUBLISHED);
    }

    @Test
    void No4_人数不足が11件_2期間をまたいで日付順の10件とほか1件() {
        ShiftPeriod late = period(21L, "2026-12-01", "2026-12-15", PeriodStatus.CLOSED);
        ShiftPeriod early = period(20L, "2026-11-16", "2026-11-30", PeriodStatus.CLOSED);
        // 開始日の順で返るが、日付順に並べ直すことも確かめるため、わざと逆に返す
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)).thenReturn(List.of(late, early));
        List<ShiftWarning> lateWarnings = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lateWarnings.add(warning(WarningType.SHORTAGE, "2026-12-0" + i, SlotIds.DAY, "日勤", 0, 1));
        }
        List<ShiftWarning> earlyWarnings = new ArrayList<>();
        for (int i = 20; i <= 22; i++) {
            earlyWarnings.add(warning(WarningType.SHORTAGE, "2026-11-" + i, SlotIds.DAY, "日勤", 1, 2));
            earlyWarnings.add(warning(WarningType.SHORTAGE, "2026-11-" + i, SlotIds.EVENING, "夕勤", 0, 2));
        }
        when(warningService.calculate(late)).thenReturn(lateWarnings);
        when(warningService.calculate(early)).thenReturn(earlyWarnings);

        DashboardView actual = service.getDashboard();

        assertThat(actual.shortages()).hasSize(10);
        assertThat(actual.moreShortages()).isEqualTo(1);
        assertThat(actual.shortages().get(0).periodId()).isEqualTo(20L);
        assertThat(actual.shortages()).extracting(s -> s.workDate().toString() + s.slotName()).containsExactly(
                "2026-11-20☀日勤", "2026-11-20☾夕勤", "2026-11-21☀日勤", "2026-11-21☾夕勤",
                "2026-11-22☀日勤", "2026-11-22☾夕勤",
                "2026-12-01☀日勤", "2026-12-02☀日勤", "2026-12-03☀日勤", "2026-12-04☀日勤");
    }

    @Test
    void 人数不足がちょうど10件_10件とほか0件() {
        ShiftPeriod closed = period(20L, "2026-11-16", "2026-11-30", PeriodStatus.CLOSED);
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)).thenReturn(List.of(closed));
        List<ShiftWarning> warnings = new ArrayList<>();
        for (int i = 16; i <= 25; i++) {
            warnings.add(warning(WarningType.SHORTAGE, "2026-11-" + i, SlotIds.DAY, "日勤", 0, 1));
        }
        when(warningService.calculate(closed)).thenReturn(warnings);

        DashboardView actual = service.getDashboard();

        assertThat(actual.shortages()).hasSize(10);
        assertThat(actual.moreShortages()).isZero();
    }

    // ---------------------------------------------------------------- 3 欠員（判定 No.6〜11）

    @Test
    void 欠員_枠の割当で通知済み_枠の人数と必要人数と通知日時() {
        LocalDateTime notified = LocalDateTime.parse("2026-11-23T09:12:00");
        when(absenceRepository.findUpcoming(TODAY)).thenReturn(
                List.of(absence("2026-11-28", daySlot, "08:00", "17:00", "山田", notified)));
        when(warningService.countFor(LocalDate.parse("2026-11-28"), daySlot)).thenReturn(1);
        when(requiredStaffService.requiredCount(LocalDate.parse("2026-11-28"), SlotIds.DAY)).thenReturn(2);

        List<VacancyView> actual = service.getDashboard().vacancies();

        assertThat(actual).containsExactly(new VacancyView(LocalDate.parse("2026-11-28"), "11月28日(土)", "☀日勤",
                "山田", notified, "11/23 9:12", true, 1, 2));
    }

    @Test
    void 欠員_夕勤で通知していない_通知日時はnull() {
        when(absenceRepository.findUpcoming(TODAY)).thenReturn(
                List.of(absence("2026-11-24", eveningSlot, "17:00", "22:00", "佐藤", null)));
        when(warningService.countFor(TODAY, eveningSlot)).thenReturn(0);
        when(requiredStaffService.requiredCount(TODAY, SlotIds.EVENING)).thenReturn(2);

        VacancyView actual = service.getDashboard().vacancies().get(0);

        assertThat(actual.slotLabel()).isEqualTo("☾夕勤");
        assertThat(actual.notifiedAt()).isNull();
        assertThat(actual.notifiedAtLabel()).isNull();
        assertThat(actual.hasCount()).isTrue();
        assertThat(actual.assigned()).isZero();
        assertThat(actual.required()).isEqualTo(2);
    }

    @Test
    void No8_時間指定の欠勤_表示は時間指定で人数は重なる最初の枠の日勤で数える() {
        when(absenceRepository.findUpcoming(TODAY)).thenReturn(
                List.of(absence("2026-11-28", null, "13:00", "22:00", "鈴木", null)));
        when(warningService.countFor(LocalDate.parse("2026-11-28"), daySlot)).thenReturn(3);
        when(requiredStaffService.requiredCount(LocalDate.parse("2026-11-28"), SlotIds.DAY)).thenReturn(4);

        VacancyView actual = service.getDashboard().vacancies().get(0);

        assertThat(actual.slotLabel()).isEqualTo("時間指定 13:00〜22:00");
        assertThat(actual.hasCount()).isTrue();
        assertThat(actual.assigned()).isEqualTo(3);
        assertThat(actual.required()).isEqualTo(4);
        verify(warningService, never()).countFor(any(), eq(eveningSlot));
    }

    @Test
    void 時間指定の欠勤で夕勤とだけ重なる_最初の日勤は飛ばして夕勤で数える() {
        when(absenceRepository.findUpcoming(TODAY)).thenReturn(
                List.of(absence("2026-11-28", null, "17:00", "21:00", "鈴木", null)));
        when(warningService.countFor(LocalDate.parse("2026-11-28"), eveningSlot)).thenReturn(1);
        when(requiredStaffService.requiredCount(LocalDate.parse("2026-11-28"), SlotIds.EVENING)).thenReturn(2);

        VacancyView actual = service.getDashboard().vacancies().get(0);

        assertThat(actual.hasCount()).isTrue();
        assertThat(actual.assigned()).isEqualTo(1);
        assertThat(actual.required()).isEqualTo(2);
    }

    @Test
    void No9_時間指定の欠勤でどの枠とも重ならない_人数は出さず数えない() {
        // 7:00〜8:00 は日勤（8:00〜）とも夕勤とも重ならない（終わりと始まりが同じ時刻は重ならない）
        when(absenceRepository.findUpcoming(TODAY)).thenReturn(
                List.of(absence("2026-11-28", null, "07:00", "08:00", "鈴木", null)));

        VacancyView actual = service.getDashboard().vacancies().get(0);

        assertThat(actual.slotLabel()).isEqualTo("時間指定 7:00〜8:00");
        assertThat(actual.hasCount()).isFalse();
        assertThat(actual.assigned()).isZero();
        assertThat(actual.required()).isZero();
        verify(warningService, never()).countFor(any(), any());
        verify(requiredStaffService, never()).requiredCount(any(), any());
    }

    // ---------------------------------------------------------------- 4 募集期間（判定 No.12・13）

    @Test
    void No7_募集期間_今日の31日前以降に終わる期間で提出数を数え同じ期間は1回だけ取る() {
        when(userRepository.findActiveStaff()).thenReturn(List.of(user(1L, "相田"), user(2L, "伊藤")));
        ShiftPeriod open = period(10L, "2026-12-01", "2026-12-15", PeriodStatus.OPEN);
        ShiftPeriod published = period(5L, "2026-11-01", "2026-11-15", PeriodStatus.PUBLISHED);
        when(periodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN)).thenReturn(List.of(open));
        when(periodRepository.findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.parse("2026-10-24")))
                .thenReturn(List.of(published, open));
        when(submissionRepository.findUserIdsByPeriodId(10L)).thenReturn(List.of(1L));
        when(submissionRepository.findUserIdsByPeriodId(5L)).thenReturn(List.of(1L, 2L, 9L));

        List<PeriodRowView> actual = service.getDashboard().periods();

        assertThat(actual).extracting(r -> r.period().id(), PeriodRowView::submitted, PeriodRowView::total)
                .containsExactly(
                        tuple(5L, 2, 2),
                        tuple(10L, 1, 2));
        assertThat(actual.get(0).period().deadlineLabel()).isEqualTo("2026/10/15 23:59");
        assertThat(actual.get(0).period().status()).isEqualTo(PeriodStatus.PUBLISHED);
        verify(periodRepository).findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.parse("2026-10-24"));
        // 受付中の期間は提出状況と表の両方で使うが、提出者は1回だけ取る
        verify(submissionRepository, times(1)).findUserIdsByPeriodId(10L);
        verify(submissionRepository, times(1)).findUserIdsByPeriodId(5L);
    }

    // ---------------------------------------------------------------- 部品（条件網羅・複数条件網羅）

    @ParameterizedTest(name = "slotId={0} → {1}")
    @CsvSource({ "1, ☀", "2, ☾" })
    void icon_日勤と夕勤(byte slotId, String expected) {
        assertThat(DashboardService.icon(slotId)).isEqualTo(expected);
    }

    @Test
    void icon_null_日勤のアイコン() {
        assertThat(DashboardService.icon(null)).isEqualTo("☀");
    }

    @Test
    void countSlot_枠の割当_その枠を返す() {
        ShiftAssignment a = absence("2026-11-28", eveningSlot, "17:00", "22:00", "x", null).getAssignment();

        assertThat(DashboardService.countSlot(a, List.of(daySlot, eveningSlot))).isSameAs(eveningSlot);
    }

    @Test
    void countSlot_時間指定で枠が0件_null() {
        ShiftAssignment a = absence("2026-11-28", null, "13:00", "22:00", "x", null).getAssignment();

        assertThat(DashboardService.countSlot(a, List.of())).isNull();
    }
}

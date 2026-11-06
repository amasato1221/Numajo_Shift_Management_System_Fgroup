package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.MonthCalendar;
import jp.numajo.fgroup.shift.dto.NextShiftView;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.StaffHomeView;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;

/**
 * StaffHomeService の単体テスト（20-SB-01 2.1、6章 No.2・5）。
 *
 * <p>
 * Repository・NotificationService・ShiftCalendarService はモックにする。今は Clock を 2026-10-21 16:59 に固定する
 * （97_テスト計画書 3.1）。次の出勤の絞り込み（未公開・欠勤・終了時刻ちょうど。No.1・3・4）は findNextShifts の
 * 条件なので RepositoryQueryTests と StaffHomeFlowTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * - findNextShift：next.isEmpty()（真 → null で枠を取らない／偽 → 1件目を NextShiftView にする）
 * - 次の出勤のバッジ（ShiftCalendarService#toBadge に渡す枠）：日勤の枠／夕勤の枠／枠なし（時間指定）
 * - 未提出の期間の繰り返し：0件／2件（期限の早い順のまま、toOption の表示名）
 * 渡す値：findNextShifts に今日・今の時刻・PageRequest.of(0, 1)、findUnsubmitted に今の日時、buildMonth に ym。
 * </pre>
 */
class StaffHomeServiceTests {

    private static final Long USER_ID = 10L;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-21T16:59:00");
    private static final YearMonth YM = YearMonth.of(2026, 10);

    private final ShiftAssignmentRepository assignmentRepository = mock(ShiftAssignmentRepository.class);
    private final ShiftPeriodRepository periodRepository = mock(ShiftPeriodRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ShiftCalendarService calendarService = mock(ShiftCalendarService.class);

    private final Slot daySlot = slot(SlotIds.DAY, "08:00", "17:00");
    private final Slot eveningSlot = slot(SlotIds.EVENING, "17:00", "22:00");
    private final MonthCalendar calendar = new MonthCalendar(YM, List.of(), YM.minusMonths(1), YM.plusMonths(1));

    private StaffHomeService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TOKYO).toInstant(), TOKYO);
        service = new StaffHomeService(assignmentRepository, periodRepository, slotRepository, notificationService,
                calendarService, clock);
        when(slotRepository.findAll(any(Sort.class))).thenReturn(List.of(daySlot, eveningSlot));
        when(calendarService.buildMonth(USER_ID, YM)).thenReturn(calendar);
        givenNext();
        when(periodRepository.findUnsubmitted(USER_ID, NOW)).thenReturn(List.of());
        when(notificationService.findUnreadTop3(USER_ID)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 次の出勤

    @Test
    void No2_次の割当がない_nextShiftはnullで枠を取らない() {
        StaffHomeView actual = service.getHome(USER_ID, YM);

        assertThat(actual.nextShift()).isNull();
        verify(assignmentRepository).findNextShifts(USER_ID, LocalDate.parse("2026-10-21"),
                LocalTime.parse("16:59"), PageRequest.of(0, 1));
        verify(slotRepository, never()).findAll(any(Sort.class));
    }

    @Test
    void 次の割当が日勤_日付と時刻と日勤のバッジ() {
        givenNext(assignment("2026-10-21", daySlot, "08:00", "17:00"));

        NextShiftView actual = service.getHome(USER_ID, YM).nextShift();

        assertThat(actual.workDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(actual.startTime()).isEqualTo(LocalTime.parse("08:00"));
        assertThat(actual.endTime()).isEqualTo(LocalTime.parse("17:00"));
        assertThat(actual.badge().type()).isEqualTo(BadgeType.DAY);
        assertThat(actual.badge().icons()).isEqualTo("☀");
        assertThat(actual.badge().timeText()).isNull();
    }

    @Test
    void 次の割当が夕勤_夕勤のバッジ() {
        givenNext(assignment("2026-10-22", eveningSlot, "17:00", "22:00"));

        NextShiftView actual = service.getHome(USER_ID, YM).nextShift();

        assertThat(actual.badge().type()).isEqualTo(BadgeType.EVENING);
        assertThat(actual.badge().icons()).isEqualTo("☾");
    }

    @Test
    void 次の割当が時間指定_重なる枠のアイコンと時間() {
        givenNext(assignment("2026-10-23", null, "13:00", "22:00"));

        NextShiftView actual = service.getHome(USER_ID, YM).nextShift();

        assertThat(actual.badge().type()).isEqualTo(BadgeType.TIME);
        assertThat(actual.badge().icons()).isEqualTo("☀☾");
        assertThat(actual.badge().timeText()).isEqualTo("13-22");
    }

    // ---------------------------------------------------------------- 未提出の期間

    @Test
    void 未提出の期間がない_空のリスト() {
        assertThat(service.getHome(USER_ID, YM).unsubmittedPeriods()).isEmpty();
        verify(periodRepository).findUnsubmitted(USER_ID, NOW);
    }

    @Test
    void No5_未提出の期間が2つ_同じ順で表示名付きの選択肢にする() {
        when(periodRepository.findUnsubmitted(USER_ID, NOW)).thenReturn(List.of(
                period(3L, "2026-11-01", "2026-11-15"), period(4L, "2026-11-16", "2026-11-30")));

        List<PeriodOption> actual = service.getHome(USER_ID, YM).unsubmittedPeriods();

        assertThat(actual).extracting(PeriodOption::id).containsExactly(3L, 4L);
        assertThat(actual.get(0).label()).isEqualTo("11/1〜11/15（期限 10/15）");
        assertThat(actual.get(0).deadlineAt()).isEqualTo(LocalDateTime.parse("2026-10-15T23:59:59"));
    }

    // ---------------------------------------------------------------- お知らせ・カレンダー

    @Test
    void お知らせとカレンダー_受け取ったものをそのまま入れる() {
        List<NoticeRow> notices = List.of(new NoticeRow(1L, NotificationType.CHAT, "チャット", "件名", "本文",
                NOW, true));
        when(notificationService.findUnreadTop3(USER_ID)).thenReturn(notices);

        StaffHomeView actual = service.getHome(USER_ID, YM);

        assertThat(actual.latestNotices()).isSameAs(notices);
        assertThat(actual.calendar()).isSameAs(calendar);
        verify(calendarService).buildMonth(USER_ID, YM);
    }

    // ---------------------------------------------------------------- 準備

    private void givenNext(ShiftAssignment... assignments) {
        when(assignmentRepository.findNextShifts(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(assignments));
    }

    private static Slot slot(byte id, String start, String end) {
        Slot s = new Slot();
        s.setId(id);
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        return s;
    }

    private static ShiftPeriod period(Long id, String start, String end) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStartDate(LocalDate.parse(start));
        p.setEndDate(LocalDate.parse(end));
        p.setStatus(PeriodStatus.OPEN);
        p.setDeadlineAt(PeriodService.deadlineOf(LocalDate.parse(start)));
        return p;
    }

    private static ShiftAssignment assignment(String date, Slot slot, String start, String end) {
        ShiftAssignment a = new ShiftAssignment();
        a.setWorkDate(LocalDate.parse(date));
        a.setSlot(slot);
        a.setStartTime(LocalTime.parse(start));
        a.setEndTime(LocalTime.parse(end));
        a.setStatus(AssignmentStatus.ASSIGNED);
        return a;
    }
}

package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.RequestMode;
import jp.numajo.fgroup.shift.constant.SubmissionState;
import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.entity.RequestSubmission;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.ShiftRequest;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.entity.id.RequestSubmissionId;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.DayRequestForm;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.repository.RequestSubmissionRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.ShiftRequestRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * ShiftRequestService（SB-03：シフト希望提出）のテスト。
 *
 * <p>
 * 期間は 2099/11/1（日）〜11/3（火）の3日間、「今」は 2099/10/10 10:00。枠は日勤（8:00-17:00）と夕勤（17:00-22:00）。
 * 20-SB-03 8章のテストの観点に対応する。
 * </p>
 *
 * <table>
 * <caption>validateDays の時間指定の判定（上から順に調べ、最初に当てはまったところで止まる）</caption>
 * <tr><th>開始・終了</th><th>30分刻みで8:00〜22:00</th><th>開始＜終了</th><th>職能のない枠と重なる</th><th>結果</th></tr>
 * <tr><td>どちらかが空</td><td>-</td><td>-</td><td>-</td><td>E1305</td></tr>
 * <tr><td>入っている</td><td>いいえ</td><td>-</td><td>-</td><td>E1306</td></tr>
 * <tr><td>入っている</td><td>はい</td><td>いいえ</td><td>-</td><td>E1307</td></tr>
 * <tr><td>入っている</td><td>はい</td><td>はい</td><td>はい</td><td>E1308</td></tr>
 * <tr><td>入っている</td><td>はい</td><td>はい</td><td>いいえ</td><td>OK</td></tr>
 * </table>
 *
 * <table>
 * <caption>validateDays の「枠ごと」の判定（同じ日の中では E1304 を先に、そのあと E1302）</caption>
 * <tr><th>職能のある枠が未選択</th><th>職能のない枠に値あり、または値が TIME</th><th>結果</th></tr>
 * <tr><td>いいえ</td><td>いいえ</td><td>OK</td></tr>
 * <tr><td>はい</td><td>いいえ</td><td>E1304</td></tr>
 * <tr><td>いいえ</td><td>はい</td><td>E1302</td></tr>
 * <tr><td>はい</td><td>はい</td><td>E1304（先に調べる）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class ShiftRequestServiceTests {

    /** テストの「今」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 10, 10, 10, 0);
    private static final LocalDate D1 = LocalDate.of(2099, 11, 1);
    private static final LocalDate D2 = LocalDate.of(2099, 11, 2);
    private static final LocalDate D3 = LocalDate.of(2099, 11, 3);
    private static final Long USER_ID = 5L;
    private static final Set<Byte> BOTH = Set.of((byte) 1, (byte) 2);
    private static final Set<Byte> DAY_ONLY = Set.of((byte) 1);
    private static final Set<Byte> EVENING_ONLY = Set.of((byte) 2);

    @Mock
    private ShiftPeriodRepository shiftPeriodRepository;
    @Mock
    private ShiftRequestRepository shiftRequestRepository;
    @Mock
    private RequestSubmissionRepository requestSubmissionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private PeriodService periodService;

    private ShiftRequestService service;
    private ShiftPeriod period;
    private Slot daySlot;
    private Slot eveningSlot;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new ShiftRequestService(shiftPeriodRepository, shiftRequestRepository,
                requestSubmissionRepository, userRepository, slotRepository, periodService, clock);
        period = period(1L, D1, D3, LocalDateTime.of(2099, 10, 15, 23, 59, 59), PeriodStatus.OPEN);
        daySlot = slot(1, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0));
        eveningSlot = slot(2, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0));
    }

    // ===== 部品 =====

    private ShiftPeriod period(Long id, LocalDate start, LocalDate end, LocalDateTime deadline,
            PeriodStatus status) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStartDate(start);
        p.setEndDate(end);
        p.setDeadlineAt(deadline);
        p.setStatus(status);
        return p;
    }

    private Slot slot(int id, String name, LocalTime start, LocalTime end, Skill... required) {
        Slot s = new Slot();
        s.setId((byte) id);
        s.setName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setRequiredSkills(new HashSet<>(Set.of(required)));
        return s;
    }

    private Skill skill(long id) {
        Skill s = new Skill();
        s.setId(id);
        return s;
    }

    private User user(Skill... skills) {
        User u = new User();
        u.setId(USER_ID);
        u.setName("山田 太郎");
        u.setSkills(new HashSet<>(Set.of(skills)));
        return u;
    }

    private DayRequestForm slotDay(LocalDate date, Availability day, Availability evening) {
        DayRequestForm f = new DayRequestForm();
        f.setWorkDate(date);
        f.setMode(RequestMode.SLOT);
        f.setDayShift(day);
        f.setEveningShift(evening);
        return f;
    }

    private DayRequestForm timeDay(LocalDate date, LocalTime start, LocalTime end) {
        DayRequestForm f = new DayRequestForm();
        f.setWorkDate(date);
        f.setMode(RequestMode.TIME);
        f.setStartTime(start);
        f.setEndTime(end);
        return f;
    }

    /** 3日分、すべて○の入力。 */
    private List<DayRequestForm> allOk() {
        return new ArrayList<>(List.of(slotDay(D1, Availability.OK, Availability.OK),
                slotDay(D2, Availability.OK, Availability.OK), slotDay(D3, Availability.OK, Availability.OK)));
    }

    private void givenSlots() {
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));
    }

    private BusinessException catchError(List<DayRequestForm> days, Set<Byte> qualified) {
        return (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.validateDays(period, days, qualified));
    }

    private ShiftRequest row(LocalDate date, Slot slot, Availability availability, LocalTime start,
            LocalTime end) {
        ShiftRequest r = new ShiftRequest();
        r.setWorkDate(date);
        r.setSlot(slot);
        r.setAvailability(availability);
        r.setStartTime(start);
        r.setEndTime(end);
        return r;
    }

    // ===== validateDays：日付 =====

    @Test
    void validateDays_全日全枠に値があればエラーなし() {
        givenSlots();

        assertThatCode(() -> service.validateDays(period, allOk(), BOTH)).doesNotThrowAnyException();
    }

    @Test
    void validateDays_日付が1日足りなければE1302() {
        List<DayRequestForm> days = allOk();
        days.remove(2);

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_日付が1日多ければE1302() {
        List<DayRequestForm> days = allOk();
        days.add(slotDay(LocalDate.of(2099, 11, 4), Availability.OK, Availability.OK));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_日付が重複していればE1302() {
        List<DayRequestForm> days = allOk();
        days.set(2, slotDay(D2, Availability.OK, Availability.OK));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_期間外の日付があればE1302() {
        List<DayRequestForm> days = allOk();
        days.set(2, slotDay(LocalDate.of(2099, 12, 1), Availability.OK, Availability.OK));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_日付がnullならE1302() {
        List<DayRequestForm> days = allOk();
        days.get(0).setWorkDate(null);

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_入れ方がnullならE1302() {
        List<DayRequestForm> days = allOk();
        days.get(0).setMode(null);

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_日の一覧がnullならE1302() {
        assertThat(catchError(null, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    // ===== validateDays：枠ごと =====

    @Test
    void validateDays_職能のある枠が未選択ならE1304で日付と枠名を渡す() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.get(1).setEveningShift(null);

        BusinessException actual = catchError(days, BOTH);

        assertThat(actual.getMessageCode()).isEqualTo("E1304");
        assertThat(actual.getArgs()).containsExactly("11月2日(月)", "夕勤");
    }

    @Test
    void validateDays_日勤が未選択ならE1304で日勤を渡す() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.get(0).setDayShift(null);

        BusinessException actual = catchError(days, BOTH);

        assertThat(actual.getMessageCode()).isEqualTo("E1304");
        assertThat(actual.getArgs()).containsExactly("11月1日(日)", "日勤");
    }

    @Test
    void validateDays_職能のない枠に値を送るとE1302() {
        givenSlots();

        assertThat(catchError(allOk(), DAY_ONLY).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_職能のない枠がnullならエラーなし() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.forEach(d -> d.setEveningShift(null));

        assertThatCode(() -> service.validateDays(period, days, DAY_ONLY)).doesNotThrowAnyException();
    }

    @Test
    void validateDays_枠ごとの値にTIMEを入れるとE1302() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.get(0).setDayShift(Availability.TIME);

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_同じ日にE1304とE1302があればE1304を先に返す() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        // 夕勤だけ職能がある。日勤（職能なし）に値があり（E1302）、夕勤（職能あり）が未選択（E1304）
        days.get(0).setEveningShift(null);

        BusinessException actual = catchError(days, EVENING_ONLY);

        assertThat(actual.getMessageCode()).isEqualTo("E1304");
        assertThat(actual.getArgs()).containsExactly("11月1日(日)", "夕勤");
    }

    @Test
    void validateDays_時間指定の日に枠の値としてTIMEが入っていればE1302() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        DayRequestForm timeDay = timeDay(D1, LocalTime.of(13, 0), LocalTime.of(15, 0));
        timeDay.setDayShift(Availability.TIME);
        days.set(0, timeDay);

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1302");
    }

    @Test
    void validateDays_枠ごとの日の時間は見ない() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.get(0).setStartTime(LocalTime.of(7, 30));
        days.get(0).setEndTime(LocalTime.of(7, 0));

        assertThatCode(() -> service.validateDays(period, days, BOTH)).doesNotThrowAnyException();
    }

    // ===== validateDays：時間指定 =====

    @Test
    void validateDays_時間指定の13時から22時はエラーなし() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(22, 0)));

        assertThatCode(() -> service.validateDays(period, days, BOTH)).doesNotThrowAnyException();
    }

    @Test
    void validateDays_時間指定の開始が空ならE1305() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, null, LocalTime.of(22, 0)));

        BusinessException actual = catchError(days, BOTH);

        assertThat(actual.getMessageCode()).isEqualTo("E1305");
        assertThat(actual.getArgs()).containsExactly("11月1日(日)");
    }

    @Test
    void validateDays_時間指定の終了が空ならE1305() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 0), null));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1305");
    }

    @Test
    void validateDays_時間指定が7時30分始まりならE1306() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(7, 30), LocalTime.of(12, 0)));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1306");
    }

    @Test
    void validateDays_時間指定が22時30分終わりならE1306() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(22, 30)));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1306");
    }

    @Test
    void validateDays_時間指定が13時15分始まりならE1306() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 15), LocalTime.of(18, 0)));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1306");
    }

    @Test
    void validateDays_時間指定の開始と終了が同じならE1307() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(13, 0)));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1307");
    }

    @Test
    void validateDays_時間指定の終了が開始より前ならE1307() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(22, 0), LocalTime.of(8, 0)));

        assertThat(catchError(days, BOTH).getMessageCode()).isEqualTo("E1307");
    }

    @Test
    void validateDays_夕勤の職能がなくて13時から17時ならエラーなし() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.forEach(d -> d.setEveningShift(null));
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(17, 0)));

        assertThatCode(() -> service.validateDays(period, days, DAY_ONLY)).doesNotThrowAnyException();
    }

    @Test
    void validateDays_夕勤の職能がなくて13時から17時30分ならE1308で枠名を渡す() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.forEach(d -> d.setEveningShift(null));
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(17, 30)));

        BusinessException actual = catchError(days, DAY_ONLY);

        assertThat(actual.getMessageCode()).isEqualTo("E1308");
        assertThat(actual.getArgs()).containsExactly("11月1日(日)", "夕勤");
    }

    @Test
    void validateDays_日勤の職能がなくて8時から9時ならE1308で日勤を渡す() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.forEach(d -> d.setDayShift(null));
        days.set(0, timeDay(D1, LocalTime.of(8, 0), LocalTime.of(9, 0)));

        BusinessException actual = catchError(days, EVENING_ONLY);

        assertThat(actual.getMessageCode()).isEqualTo("E1308");
        assertThat(actual.getArgs()).containsExactly("11月1日(日)", "日勤");
    }

    @Test
    void validateDays_日付の早い順に調べて最初の誤りで止まる() {
        givenSlots();
        List<DayRequestForm> days = allOk();
        days.get(2).setEveningShift(null);
        days.set(0, timeDay(D1, null, LocalTime.of(22, 0)));
        java.util.Collections.reverse(days);

        BusinessException actual = catchError(days, BOTH);

        assertThat(actual.getMessageCode()).isEqualTo("E1305");
    }

    // ===== qualifiedSlotIds =====

    @Test
    void qualifiedSlotIds_必要な職能をすべて持つ枠だけ返す() {
        Skill s1 = skill(1);
        Skill s2 = skill(2);
        Slot day = slot(1, "日勤", LocalTime.of(8, 0), LocalTime.of(17, 0), s1);
        Slot evening = slot(2, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0), s1, s2);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(s1)));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day, evening));

        Set<Byte> actual = service.qualifiedSlotIds(USER_ID);

        assertThat(actual).containsExactly((byte) 1);
    }

    @Test
    void qualifiedSlotIds_必要な職能がない枠は誰でも入れる() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));

        Set<Byte> actual = service.qualifiedSlotIds(USER_ID);

        assertThat(actual).containsExactlyInAnyOrder((byte) 1, (byte) 2);
    }

    @Test
    void qualifiedSlotIds_職能が足りなければ空() {
        Slot evening = slot(2, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0), skill(2));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(skill(1))));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(evening));

        Set<Byte> actual = service.qualifiedSlotIds(USER_ID);

        assertThat(actual).isEmpty();
    }

    @Test
    void qualifiedSlotIds_ユーザーがいなければ404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.qualifiedSlotIds(USER_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ===== getRequestPage =====

    private void givenPageBasics(boolean editable) {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(editable);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();
    }

    @Test
    void getRequestPage_未提出なら未提出と日ごとの行を返す() {
        givenPageBasics(true);
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.empty());

        RequestPageView actual = service.getRequestPage(USER_ID, 1L);

        assertThat(actual.period().id()).isEqualTo(1L);
        assertThat(actual.editable()).isTrue();
        assertThat(actual.submissionState()).isEqualTo(SubmissionState.NOT_SUBMITTED);
        assertThat(actual.statusLabel()).isEqualTo("未提出");
        assertThat(actual.submittedAt()).isNull();
        assertThat(actual.staffName()).isEqualTo("山田 太郎");
        assertThat(actual.days()).extracting("workDate").containsExactly(D1, D2, D3);
        assertThat(actual.days()).extracting("dateLabel").containsExactly("11月1日(日)", "11月2日(月)", "11月3日(火)");
        assertThat(actual.days()).allMatch(r -> r.dayQualified() && r.eveningQualified());
        assertThat(actual.deadlineLabel()).isEqualTo("2099/10/15 23:59");
        assertThat(actual.submittedAtLabel()).isNull();
    }

    @Test
    void getRequestPage_提出済みなら提出済みと提出日時を返す() {
        givenPageBasics(true);
        RequestSubmission submission = new RequestSubmission();
        submission.setSubmittedAt(NOW.minusDays(1));
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.of(submission));

        RequestPageView actual = service.getRequestPage(USER_ID, 1L);

        assertThat(actual.submissionState()).isEqualTo(SubmissionState.SUBMITTED);
        assertThat(actual.statusLabel()).isEqualTo("提出済み");
        assertThat(actual.submittedAt()).isEqualTo(NOW.minusDays(1));
        assertThat(actual.submittedAtLabel()).isEqualTo("2099/10/09 10:00");
    }

    @Test
    void getRequestPage_受付終了なら提出済みでも受付終了() {
        givenPageBasics(false);
        RequestSubmission submission = new RequestSubmission();
        submission.setSubmittedAt(NOW.minusDays(1));
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.of(submission));

        RequestPageView actual = service.getRequestPage(USER_ID, 1L);

        assertThat(actual.editable()).isFalse();
        assertThat(actual.submissionState()).isEqualTo(SubmissionState.CLOSED);
        assertThat(actual.statusLabel()).isEqualTo("受付終了");
    }

    @Test
    void getRequestPage_職能がない枠の行は職能なしになる() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(skill(1))));
        Slot evening = slot(2, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0), skill(2));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, evening));

        RequestPageView actual = service.getRequestPage(USER_ID, 1L);

        assertThat(actual.days()).allMatch(r -> r.dayQualified() && !r.eveningQualified());
    }

    @Test
    void getRequestPage_存在しない期間のIDは404() {
        when(periodService.getPeriod(99L)).thenThrow(new ResourceNotFoundException("期間がない"));

        assertThatThrownBy(() -> service.getRequestPage(USER_ID, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getRequestPage_期間を指定しなくて受付中の期間がなければnull() {
        when(shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN)).thenReturn(List.of());

        RequestPageView actual = service.getRequestPage(USER_ID, null);

        assertThat(actual).isNull();
    }

    @Test
    void getRequestPage_期間を指定しなければ期限が一番早い受付中の期間を選ぶ() {
        ShiftPeriod late = period(2L, D1.plusMonths(1), D3.plusMonths(1), LocalDateTime.of(2099, 11, 20, 0, 0),
                PeriodStatus.OPEN);
        ShiftPeriod early = period(3L, D1.plusMonths(2), D3.plusMonths(2), LocalDateTime.of(2099, 10, 20, 0, 0),
                PeriodStatus.OPEN);
        when(shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN))
                .thenReturn(List.of(late, early));
        when(periodService.isAcceptingRequests(early)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();

        RequestPageView actual = service.getRequestPage(USER_ID, null);

        assertThat(actual.period().id()).isEqualTo(3L);
        assertThat(actual.periodOptions()).extracting("id").containsExactly(3L, 2L);
    }

    @Test
    void getRequestPage_期限が過ぎた期間は自動では選ばない() {
        ShiftPeriod expired = period(2L, D1, D3, NOW.minusMinutes(1), PeriodStatus.OPEN);
        when(shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN))
                .thenReturn(List.of(expired));

        RequestPageView actual = service.getRequestPage(USER_ID, null);

        assertThat(actual).isNull();
    }

    @Test
    void getRequestPage_期限がちょうど今の期間は自動で選ぶ() {
        ShiftPeriod justNow = period(2L, D1, D3, NOW, PeriodStatus.OPEN);
        when(shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN))
                .thenReturn(List.of(justNow));
        when(periodService.isAcceptingRequests(justNow)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();

        RequestPageView actual = service.getRequestPage(USER_ID, null);

        assertThat(actual.period().id()).isEqualTo(2L);
    }

    @Test
    void getRequestPage_表示中の期間が受付中でなければ選択肢の先頭に入れる() {
        ShiftPeriod closed = period(7L, D1, D3, NOW.minusDays(1), PeriodStatus.CLOSED);
        ShiftPeriod open = period(8L, D1.plusMonths(1), D3.plusMonths(1), NOW.plusDays(3), PeriodStatus.OPEN);
        when(periodService.getPeriod(7L)).thenReturn(closed);
        when(periodService.isAcceptingRequests(closed)).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();
        when(shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN)).thenReturn(List.of(open));

        RequestPageView actual = service.getRequestPage(USER_ID, 7L);

        assertThat(actual.periodOptions()).extracting("id").containsExactly(7L, 8L);
    }

    // ===== toForm =====

    @Test
    void toForm_希望がなければ全日が枠ごとで未選択() {
        when(shiftRequestRepository.findByPeriodIdAndUserId(1L, USER_ID)).thenReturn(List.of());

        ShiftRequestForm actual = service.toForm(USER_ID, period);

        assertThat(actual.getPeriodId()).isEqualTo(1L);
        assertThat(actual.getDays()).extracting(DayRequestForm::getWorkDate).containsExactly(D1, D2, D3);
        assertThat(actual.getDays()).allMatch(d -> d.getMode() == RequestMode.SLOT && d.getDayShift() == null
                && d.getEveningShift() == null);
    }

    @Test
    void toForm_枠ごとの希望を日勤と夕勤に入れる() {
        when(shiftRequestRepository.findByPeriodIdAndUserId(1L, USER_ID)).thenReturn(List.of(
                row(D1, daySlot, Availability.OK, null, null), row(D1, eveningSlot, Availability.NG, null, null)));

        ShiftRequestForm actual = service.toForm(USER_ID, period);

        DayRequestForm first = actual.getDays().get(0);
        assertThat(first.getMode()).isEqualTo(RequestMode.SLOT);
        assertThat(first.getDayShift()).isEqualTo(Availability.OK);
        assertThat(first.getEveningShift()).isEqualTo(Availability.NG);
        assertThat(actual.getDays().get(1).getDayShift()).isNull();
    }

    @Test
    void toForm_時間指定の希望は時間指定として入れる() {
        when(shiftRequestRepository.findByPeriodIdAndUserId(1L, USER_ID)).thenReturn(List.of(
                row(D2, null, Availability.TIME, LocalTime.of(13, 0), LocalTime.of(22, 0))));

        ShiftRequestForm actual = service.toForm(USER_ID, period);

        DayRequestForm second = actual.getDays().get(1);
        assertThat(second.getMode()).isEqualTo(RequestMode.TIME);
        assertThat(second.getStartTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(second.getEndTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(second.getDayShift()).isNull();
        assertThat(actual.getDays().get(0).getMode()).isEqualTo(RequestMode.SLOT);
    }

    // ===== submit =====

    private void givenSubmittable() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.empty());
    }

    private ShiftRequestForm form(List<DayRequestForm> days) {
        ShiftRequestForm form = new ShiftRequestForm();
        form.setPeriodId(1L);
        form.setDays(days);
        return form;
    }

    @SuppressWarnings("unchecked")
    private List<ShiftRequest> savedRows() {
        ArgumentCaptor<List<ShiftRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(shiftRequestRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void submit_全日全枠に値を付けると枠ごとの行ができて提出済みになる() {
        givenSubmittable();
        when(slotRepository.getReferenceById((byte) 1)).thenReturn(daySlot);
        when(slotRepository.getReferenceById((byte) 2)).thenReturn(eveningSlot);
        List<DayRequestForm> days = allOk();
        days.get(1).setEveningShift(Availability.NG);

        service.submit(USER_ID, form(days));

        List<ShiftRequest> rows = savedRows();
        assertThat(rows).hasSize(6);
        assertThat(rows).allMatch(r -> r.getStartTime() == null && r.getEndTime() == null);
        assertThat(rows).filteredOn(r -> r.getWorkDate().equals(D2) && r.getSlot().getId() == 2)
                .extracting(ShiftRequest::getAvailability).containsExactly(Availability.NG);
        ArgumentCaptor<RequestSubmission> captor = ArgumentCaptor.forClass(RequestSubmission.class);
        verify(requestSubmissionRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(new RequestSubmissionId(1L, USER_ID));
        assertThat(captor.getValue().getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void submit_前の希望を消してから新しい希望を入れる() {
        givenSubmittable();
        when(slotRepository.getReferenceById((byte) 1)).thenReturn(daySlot);
        when(slotRepository.getReferenceById((byte) 2)).thenReturn(eveningSlot);

        service.submit(USER_ID, form(allOk()));

        InOrder order = inOrder(shiftRequestRepository);
        order.verify(shiftRequestRepository).deleteByPeriodIdAndUserId(1L, USER_ID);
        order.verify(shiftRequestRepository).saveAll(any());
    }

    @Test
    void submit_時間指定の日は枠の行を作らず時間指定の1行だけ作る() {
        givenSubmittable();
        when(slotRepository.getReferenceById((byte) 1)).thenReturn(daySlot);
        when(slotRepository.getReferenceById((byte) 2)).thenReturn(eveningSlot);
        List<DayRequestForm> days = allOk();
        days.set(0, timeDay(D1, LocalTime.of(13, 0), LocalTime.of(22, 0)));
        days.get(0).setDayShift(Availability.OK);

        service.submit(USER_ID, form(days));

        List<ShiftRequest> rows = savedRows();
        assertThat(rows).filteredOn(r -> r.getWorkDate().equals(D1)).hasSize(1);
        ShiftRequest time = rows.stream().filter(r -> r.getWorkDate().equals(D1)).findFirst().orElseThrow();
        assertThat(time.getSlot()).isNull();
        assertThat(time.getAvailability()).isEqualTo(Availability.TIME);
        assertThat(time.getStartTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(time.getEndTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(rows).hasSize(5);
    }

    @Test
    void submit_職能のない枠は行を作らない() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);
        Slot evening = slot(2, "夕勤", LocalTime.of(17, 0), LocalTime.of(22, 0), skill(2));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(skill(1))));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, evening));
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.empty());
        when(slotRepository.getReferenceById((byte) 1)).thenReturn(daySlot);
        List<DayRequestForm> days = allOk();
        days.forEach(d -> d.setEveningShift(null));

        service.submit(USER_ID, form(days));

        assertThat(savedRows()).hasSize(3).allMatch(r -> r.getSlot().getId() == 1);
    }

    @Test
    void submit_再提出すると提出日時が更新される() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        givenSlots();
        RequestSubmission existing = new RequestSubmission();
        existing.setId(new RequestSubmissionId(1L, USER_ID));
        existing.setSubmittedAt(NOW.minusDays(2));
        when(requestSubmissionRepository.findById(new RequestSubmissionId(1L, USER_ID)))
                .thenReturn(Optional.of(existing));
        when(slotRepository.getReferenceById((byte) 1)).thenReturn(daySlot);
        when(slotRepository.getReferenceById((byte) 2)).thenReturn(eveningSlot);

        service.submit(USER_ID, form(allOk()));

        verify(requestSubmissionRepository).save(existing);
        assertThat(existing.getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void submit_受付が終わっていればE1303で何も変えない() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_ID, form(allOk())))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E1303");

        verify(shiftRequestRepository, never()).deleteByPeriodIdAndUserId(any(), any());
        verify(requestSubmissionRepository, never()).save(any());
    }

    @Test
    void submit_入力に誤りがあれば希望を消さずにエラーにする() {
        givenSlots();
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        List<DayRequestForm> days = allOk();
        days.get(0).setDayShift(null);

        assertThatThrownBy(() -> service.submit(USER_ID, form(days)))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E1304");

        verify(shiftRequestRepository, never()).deleteByPeriodIdAndUserId(any(), any());
        verify(requestSubmissionRepository, never()).save(any());
    }

    @Test
    void submit_存在しない期間は404() {
        when(periodService.getPeriod(1L)).thenThrow(new ResourceNotFoundException("期間がない"));

        assertThatThrownBy(() -> service.submit(USER_ID, form(allOk())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ===== cancel =====

    @Test
    void cancel_希望と提出状況を消す() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(true);

        service.cancel(USER_ID, 1L);

        verify(shiftRequestRepository).deleteByPeriodIdAndUserId(1L, USER_ID);
        verify(requestSubmissionRepository).deleteByPeriodIdAndUserId(1L, USER_ID);
    }

    @Test
    void cancel_受付が終わっていればE1303で消さない() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(periodService.isAcceptingRequests(period)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(USER_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E1303");

        verify(shiftRequestRepository, never()).deleteByPeriodIdAndUserId(any(), any());
        verify(requestSubmissionRepository, never()).deleteByPeriodIdAndUserId(any(), any());
    }

    @Test
    void cancel_存在しない期間は404() {
        when(periodService.getPeriod(99L)).thenThrow(new ResourceNotFoundException("期間がない"));

        assertThatThrownBy(() -> service.cancel(USER_ID, 99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}

package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.WarningType;
import jp.numajo.fgroup.shift.dto.DayColumn;
import jp.numajo.fgroup.shift.dto.MatrixCell;
import jp.numajo.fgroup.shift.dto.MatrixCell.AssignMark;
import jp.numajo.fgroup.shift.dto.MatrixCell.RequestMark;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.PeriodPageView;
import jp.numajo.fgroup.shift.dto.PublishResult;
import jp.numajo.fgroup.shift.dto.ShiftWarning;
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

/**
 * AssignmentService（SM-06：シフト作成）のテスト。
 *
 * <p>
 * 期間は 2099/11/1（日）〜11/3（火）の3日間、「今」は 2099/10/10 10:00。枠は日勤（8:00-17:00）と夕勤（17:00-22:00、
 * 職能2が必要）。お知らせ・メッセージは、呼ばれたときの引数を確かめる（メッセージは「コード:[引数]」の文字にする）。
 * 20-SM-06 8章のテストの観点に対応する。
 * </p>
 *
 * <table>
 * <caption>セルの切替（toggleSlot）の判定（上から順に調べ、最初に当てはまったところで止まる）</caption>
 * <tr><th>期間の状態</th><th>日付</th><th>割当</th><th>スタッフ</th><th>重なり</th><th>結果</th></tr>
 * <tr><td>受付中</td><td>-</td><td>-</td><td>-</td><td>-</td><td>E2602</td></tr>
 * <tr><td>作成中・公開済み</td><td>期間外</td><td>-</td><td>-</td><td>-</td><td>E2601</td></tr>
 * <tr><td>作成中・公開済み</td><td>期間内</td><td>欠勤</td><td>-</td><td>-</td><td>E2603</td></tr>
 * <tr><td>作成中</td><td>期間内</td><td>出勤</td><td>-</td><td>-</td><td>外す（お知らせなし）</td></tr>
 * <tr><td>公開済み</td><td>期間内</td><td>出勤</td><td>-</td><td>-</td><td>外す（お知らせ N1004）</td></tr>
 * <tr><td>作成中・公開済み</td><td>期間内</td><td>なし</td><td>無効</td><td>-</td><td>E2604</td></tr>
 * <tr><td>作成中・公開済み</td><td>期間内</td><td>なし</td><td>有効</td><td>あり</td><td>E2605</td></tr>
 * <tr><td>作成中</td><td>期間内</td><td>なし</td><td>有効</td><td>なし（接するだけ）</td><td>付ける（お知らせなし）</td></tr>
 * <tr><td>公開済み</td><td>期間内</td><td>なし</td><td>有効</td><td>なし</td><td>付ける（お知らせ N1003）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class AssignmentServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2099, 10, 10, 10, 0);
    private static final LocalDate D1 = LocalDate.of(2099, 11, 1);
    private static final LocalDate D2 = LocalDate.of(2099, 11, 2);
    private static final LocalDate D3 = LocalDate.of(2099, 11, 3);
    private static final byte DAY = 1;
    private static final byte EVENING = 2;
    private static final Long USER_ID = 5L;
    private static final Long OPERATOR_ID = 99L;

    @Mock
    private ShiftPeriodRepository shiftPeriodRepository;
    @Mock
    private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock
    private ShiftRequestRepository shiftRequestRepository;
    @Mock
    private RequestSubmissionRepository requestSubmissionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private RequiredStaffService requiredStaffService;
    @Mock
    private ShiftWarningService shiftWarningService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private PeriodService periodService;
    @Mock
    private MessageSource messageSource;

    private AssignmentService service;
    private ShiftPeriod period;
    private Skill skill2;
    private Slot daySlot;
    private Slot eveningSlot;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new AssignmentService(shiftPeriodRepository, shiftAssignmentRepository, shiftRequestRepository,
                requestSubmissionRepository, userRepository, slotRepository, requiredStaffService,
                shiftWarningService, notificationService, operationLogService, periodService, messageSource,
                clock);
        period = period(PeriodStatus.CLOSED);
        skill2 = skill(2L);
        daySlot = slot(DAY, "日勤", "DAY", LocalTime.of(8, 0), LocalTime.of(17, 0));
        eveningSlot = slot(EVENING, "夕勤", "EVENING", LocalTime.of(17, 0), LocalTime.of(22, 0), skill2);
        lenient().when(messageSource.getMessage(anyString(), any(Object[].class), eq(Locale.JAPAN)))
                .thenAnswer(invocation -> invocation.getArgument(0) + ":"
                        + Arrays.toString(invocation.getArgument(1, Object[].class)));
    }

    // ===== 部品 =====

    private static ShiftPeriod period(PeriodStatus status) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(1L);
        p.setStartDate(D1);
        p.setEndDate(D3);
        p.setDeadlineAt(LocalDateTime.of(2099, 10, 15, 23, 59, 59));
        p.setStatus(status);
        return p;
    }

    private static Skill skill(Long id) {
        Skill s = new Skill();
        s.setId(id);
        return s;
    }

    private static Slot slot(byte id, String name, String code, LocalTime start, LocalTime end, Skill... required) {
        Slot s = new Slot();
        s.setId(id);
        s.setName(name);
        s.setCode(code);
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
        u.setRole(Role.STAFF);
        u.setSkills(new HashSet<>(Set.of(skills)));
        return u;
    }

    private static ShiftAssignment assignment(Long id, User user, LocalDate date, Slot slot, LocalTime start,
            LocalTime end, AssignmentStatus status) {
        ShiftAssignment a = new ShiftAssignment();
        a.setId(id);
        a.setUser(user);
        a.setWorkDate(date);
        a.setSlot(slot);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(status);
        return a;
    }

    private static ShiftAssignment slotAssignment(Long id, User user, LocalDate date, Slot slot,
            AssignmentStatus status) {
        return assignment(id, user, date, slot, slot.getStartTime(), slot.getEndTime(), status);
    }

    private static ShiftAssignment timeAssignment(Long id, User user, LocalDate date, LocalTime start,
            LocalTime end, AssignmentStatus status) {
        return assignment(id, user, date, null, start, end, status);
    }

    private static ShiftRequest request(User user, LocalDate date, Slot slot, Availability availability,
            LocalTime start, LocalTime end) {
        ShiftRequest r = new ShiftRequest();
        r.setUser(user);
        r.setWorkDate(date);
        r.setSlot(slot);
        r.setAvailability(availability);
        r.setStartTime(start);
        r.setEndTime(end);
        return r;
    }

    private static Map<LocalDate, Map<Byte, Integer>> required(int day, int evening) {
        return Map.of(D1, Map.of(DAY, day, EVENING, evening), D2, Map.of(DAY, day, EVENING, evening),
                D3, Map.of(DAY, day, EVENING, evening));
    }

    private static LocalTime time(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    /** 画面を作るときに読む値を用意する。 */
    private void givenPage(ShiftPeriod target, List<User> staff, List<ShiftRequest> requests,
            List<Long> submittedIds, List<ShiftAssignment> assignments, List<ShiftWarning> warnings) {
        when(periodService.getPeriod(1L)).thenReturn(target);
        when(userRepository.findStaffForPeriod(1L)).thenReturn(staff);
        when(shiftRequestRepository.findByPeriodId(1L)).thenReturn(requests);
        when(requestSubmissionRepository.findUserIdsByPeriodId(1L)).thenReturn(submittedIds);
        when(shiftAssignmentRepository.findByPeriodIdWithUser(1L)).thenReturn(assignments);
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(eveningSlot, daySlot));
        when(requiredStaffService.requiredCounts(D1, D3)).thenReturn(required(1, 1));
        when(shiftWarningService.calculate(any(), any(), any(), any(), any())).thenReturn(warnings);
        when(periodService.findOptionsForManager()).thenReturn(List.of());
    }

    private static MatrixCell cell(PeriodPageView page, Long userId, LocalDate date, byte slotId) {
        return page.matrix().rows().stream().filter(row -> row.userId().equals(userId)).findFirst().orElseThrow()
                .cells().get(date).get(slotId);
    }

    private static ShiftWarning warning(WarningType type) {
        return new ShiftWarning(type, D1, "11月1日(日)", DAY, "W2603", new Object[] { "11月1日(日)", "日勤" });
    }

    // ===== getPeriodPage：作成中の割当表 =====

    private PeriodPageView scenarioPage() {
        User a = user(11L, "山田", "やまだ", false, true, skill2);
        User b = user(12L, "鈴木", "すずき", false, true, skill2);
        User c = user(13L, "佐藤", "さとう", true, true);
        User d = user(14L, "田中", "たなか", false, true, skill2);
        List<ShiftRequest> requests = List.of(
                request(a, D1, daySlot, Availability.OK, null, null),
                request(a, D1, eveningSlot, Availability.NG, null, null),
                request(a, D2, null, Availability.TIME, time(13, 0), time(22, 0)),
                request(a, D3, eveningSlot, Availability.OK, null, null),
                request(c, D1, daySlot, Availability.OK, null, null),
                request(d, D1, null, Availability.TIME, time(8, 0), time(12, 0)));
        List<ShiftAssignment> assignments = List.of(
                slotAssignment(101L, a, D1, daySlot, AssignmentStatus.ASSIGNED),
                timeAssignment(102L, a, D2, time(13, 0), time(22, 0), AssignmentStatus.ASSIGNED),
                slotAssignment(103L, c, D1, daySlot, AssignmentStatus.ABSENT),
                slotAssignment(104L, a, D3, daySlot, AssignmentStatus.ASSIGNED),
                timeAssignment(105L, a, D3, time(8, 0), time(12, 0), AssignmentStatus.ASSIGNED),
                timeAssignment(106L, d, D2, time(13, 0), time(22, 0), AssignmentStatus.ABSENT),
                slotAssignment(107L, c, D3, eveningSlot, AssignmentStatus.ASSIGNED));
        givenPage(period, List.of(a, b, c, d), requests, List.of(11L, 13L, 14L), assignments, List.of());
        return service.getPeriodPage(1L);
    }

    @Test
    void getPeriodPage_期間と状態と選択肢と公開の可否を返す() {
        PeriodPageView page = scenarioPage();

        assertThat(page.period().id()).isEqualTo(1L);
        assertThat(page.period().label()).isEqualTo("11/1〜11/3（期限 10/15）");
        assertThat(page.editable()).isTrue();
        assertThat(page.publishable()).isTrue();
        assertThat(page.notPublishableCode()).isNull();
        assertThat(page.publishedAt()).isNull();
        assertThat(page.publishedAtLabel()).isNull();
        assertThat(page.warnings()).isEmpty();
    }

    @Test
    void getPeriodPage_行は渡したスタッフの順で列は日付と枠のID順() {
        PeriodPageView page = scenarioPage();

        assertThat(page.matrix().days()).extracting(DayColumn::date).containsExactly(D1, D2, D3);
        assertThat(page.matrix().slots()).extracting(Slot::getId).containsExactly(DAY, EVENING);
        assertThat(page.matrix().rows()).extracting("userId").containsExactly(11L, 12L, 13L, 14L);
        assertThat(page.matrix().rows()).extracting("submitted").containsExactly(true, false, true, true);
        assertThat(page.matrix().rows()).extracting("trainee").containsExactly(false, false, true, false);
        // 見出しの文字は Service が作る（91 5.1）
        DayColumn first = page.matrix().days().get(0);
        assertThat(first.dateLabel()).isEqualTo("11/1");
        assertThat(first.dayOfWeekLabel()).isEqualTo("日");
        assertThat(first.dateWithDayLabel()).isEqualTo("11/1(日)");
    }

    @Test
    void getPeriodPage_希望がOKで割当があればOKと割当済みのセルになる() {
        MatrixCell cell = cell(scenarioPage(), 11L, D1, DAY);

        assertThat(cell.request()).isEqualTo(RequestMark.OK);
        assertThat(cell.assign()).isEqualTo(AssignMark.SLOT);
        assertThat(cell.assignmentId()).isEqualTo(101L);
        assertThat(cell.confirmCode()).isNull();
        assertThat(cell.disabled()).isFalse();
    }

    @Test
    void getPeriodPage_希望がNGで割当がなければC2602の確認を付ける() {
        MatrixCell cell = cell(scenarioPage(), 11L, D1, EVENING);

        assertThat(cell.request()).isEqualTo(RequestMark.NG);
        assertThat(cell.assign()).isEqualTo(AssignMark.NONE);
        assertThat(cell.assignmentId()).isNull();
        assertThat(cell.confirmCode()).isEqualTo("C2602");
        assertThat(cell.disabled()).isFalse();
    }

    @Test
    void getPeriodPage_希望がOKで割当がなければ確認なし() {
        MatrixCell cell = cell(scenarioPage(), 11L, D3, EVENING);

        assertThat(cell.request()).isEqualTo(RequestMark.OK);
        assertThat(cell.assign()).isEqualTo(AssignMark.NONE);
        assertThat(cell.confirmCode()).isNull();
    }

    @Test
    void getPeriodPage_未提出の人はNOT_SUBMITTEDでC2602の確認を付ける() {
        MatrixCell cell = cell(scenarioPage(), 12L, D1, DAY);

        assertThat(cell.request()).isEqualTo(RequestMark.NOT_SUBMITTED);
        assertThat(cell.assign()).isEqualTo(AssignMark.NONE);
        assertThat(cell.confirmCode()).isEqualTo("C2602");
    }

    @Test
    void getPeriodPage_提出済みで希望の行がなければNG() {
        MatrixCell cell = cell(scenarioPage(), 11L, D3, DAY);

        assertThat(cell.request()).isEqualTo(RequestMark.NG);
    }

    @Test
    void getPeriodPage_職能がない枠はNO_SKILLでC2603の確認を付ける() {
        MatrixCell cell = cell(scenarioPage(), 13L, D1, EVENING);

        assertThat(cell.request()).isEqualTo(RequestMark.NO_SKILL);
        assertThat(cell.assign()).isEqualTo(AssignMark.NONE);
        assertThat(cell.confirmCode()).isEqualTo("C2603");
    }

    @Test
    void getPeriodPage_職能がなくても割当済みならSLOTのままで確認は付けない() {
        MatrixCell cell = cell(scenarioPage(), 13L, D3, EVENING);

        assertThat(cell.request()).isEqualTo(RequestMark.NO_SKILL);
        assertThat(cell.assign()).isEqualTo(AssignMark.SLOT);
        assertThat(cell.assignmentId()).isEqualTo(107L);
        assertThat(cell.confirmCode()).isNull();
    }

    @Test
    void getPeriodPage_時間指定の希望が枠と重なればTIMEで時間を添える() {
        MatrixCell day = cell(scenarioPage(), 14L, D1, DAY);
        MatrixCell evening = cell(scenarioPage(), 14L, D1, EVENING);

        assertThat(day.request()).isEqualTo(RequestMark.TIME);
        assertThat(day.requestTitle()).isEqualTo("8:00-12:00");
        assertThat(day.assign()).isEqualTo(AssignMark.NONE);
        assertThat(day.confirmCode()).isNull();
        // 提出済みで、時間指定の時間が夕勤と重ならないので ×
        assertThat(evening.request()).isEqualTo(RequestMark.NG);
        assertThat(evening.requestTitle()).isNull();
        assertThat(evening.confirmCode()).isEqualTo("C2602");
    }

    @Test
    void getPeriodPage_時間指定の割当は日勤と夕勤の両方でTIMEになりボタンを押せない() {
        PeriodPageView page = scenarioPage();
        MatrixCell day = cell(page, 11L, D2, DAY);
        MatrixCell evening = cell(page, 11L, D2, EVENING);

        assertThat(day.request()).isEqualTo(RequestMark.TIME);
        assertThat(day.requestTitle()).isEqualTo("13:00-22:00");
        assertThat(day.assign()).isEqualTo(AssignMark.TIME);
        assertThat(day.assignmentId()).isEqualTo(102L);
        assertThat(day.disabled()).isTrue();
        assertThat(day.confirmCode()).isNull();
        assertThat(evening.assign()).isEqualTo(AssignMark.TIME);
        assertThat(evening.assignmentId()).isEqualTo(102L);
    }

    @Test
    void getPeriodPage_欠勤の割当はABSENTでボタンを押せない() {
        PeriodPageView page = scenarioPage();
        MatrixCell slot = cell(page, 13L, D1, DAY);
        MatrixCell time = cell(page, 14L, D2, DAY);

        assertThat(slot.assign()).isEqualTo(AssignMark.ABSENT);
        assertThat(slot.assignmentId()).isEqualTo(103L);
        assertThat(slot.disabled()).isTrue();
        assertThat(slot.confirmCode()).isNull();
        assertThat(time.assign()).isEqualTo(AssignMark.ABSENT);
        assertThat(time.assignmentId()).isEqualTo(106L);
        assertThat(time.disabled()).isTrue();
    }

    @Test
    void getPeriodPage_枠の割当と時間指定の割当が重なれば枠の割当を見せる() {
        MatrixCell cell = cell(scenarioPage(), 11L, D3, DAY);

        assertThat(cell.assign()).isEqualTo(AssignMark.SLOT);
        assertThat(cell.assignmentId()).isEqualTo(104L);
    }

    @Test
    void getPeriodPage_最下行は出勤の割当数と必要数と不足と研修中のみを返す() {
        PeriodPageView page = scenarioPage();

        // 11/1 日勤：山田（出勤）1人。佐藤は欠勤なので数えない
        assertThat(page.matrix().counts().get(D1).get(DAY).assigned()).isEqualTo(1);
        assertThat(page.matrix().counts().get(D1).get(DAY).required()).isEqualTo(1);
        assertThat(page.matrix().counts().get(D1).get(DAY).shortage()).isFalse();
        assertThat(page.matrix().counts().get(D1).get(DAY).traineeOnly()).isFalse();
        // 11/1 夕勤：0人
        assertThat(page.matrix().counts().get(D1).get(EVENING).shortage()).isTrue();
        assertThat(page.matrix().counts().get(D1).get(EVENING).traineeOnly()).isFalse();
        // 11/2 日勤・夕勤：山田の時間指定（出勤）が両方に数えられ、田中の欠勤は数えない
        assertThat(page.matrix().counts().get(D2).get(DAY).assigned()).isEqualTo(1);
        assertThat(page.matrix().counts().get(D2).get(EVENING).assigned()).isEqualTo(1);
        // 11/3 日勤：枠の割当と時間指定の2人分
        assertThat(page.matrix().counts().get(D3).get(DAY).assigned()).isEqualTo(2);
        assertThat(page.matrix().counts().get(D3).get(DAY).shortage()).isFalse();
        // 11/3 夕勤：研修中の佐藤だけ
        assertThat(page.matrix().counts().get(D3).get(EVENING).assigned()).isEqualTo(1);
        assertThat(page.matrix().counts().get(D3).get(EVENING).traineeOnly()).isTrue();
    }

    @Test
    void getPeriodPage_時間指定の希望は日付とふりがなの順で割当済みを区別する() {
        PeriodPageView page = scenarioPage();

        assertThat(page.timeRequests()).hasSize(2);
        assertThat(page.timeRequests().get(0).userId()).isEqualTo(14L);
        assertThat(page.timeRequests().get(0).name()).isEqualTo("田中");
        assertThat(page.timeRequests().get(0).workDate()).isEqualTo(D1);
        assertThat(page.timeRequests().get(0).workDateLabel()).isEqualTo("11月1日(日)");
        assertThat(page.timeRequests().get(0).timeLabel()).isEqualTo("8:00〜12:00");
        assertThat(page.timeRequests().get(0).startTime()).isEqualTo(time(8, 0));
        assertThat(page.timeRequests().get(0).endTime()).isEqualTo(time(12, 0));
        assertThat(page.timeRequests().get(0).assigned()).isFalse();
        assertThat(page.timeRequests().get(1).userId()).isEqualTo(11L);
        assertThat(page.timeRequests().get(1).assigned()).isTrue();
    }

    @Test
    void getPeriodPage_時間指定の割当は欠勤も含めて日付と開始とふりがなの順に並べる() {
        PeriodPageView page = scenarioPage();

        assertThat(page.timeAssignments()).extracting("assignmentId").containsExactly(106L, 102L, 105L);
        // 同じ日の同じ開始時刻（11/2 13:00）は、ふりがなの順（たなか → やまだ）
        assertThat(page.timeAssignments().get(0).name()).isEqualTo("田中");
        assertThat(page.timeAssignments().get(0).status()).isEqualTo(AssignmentStatus.ABSENT);
        assertThat(page.timeAssignments().get(0).workDateLabel()).isEqualTo("11月2日(月)");
        assertThat(page.timeAssignments().get(0).timeLabel()).isEqualTo("13:00〜22:00");
        assertThat(page.timeAssignments().get(1).name()).isEqualTo("山田");
    }

    @Test
    void getPeriodPage_同じ時間指定の割当でも欠勤なら割当済みにしない() {
        User d = user(14L, "田中", "たなか", false, true, skill2);
        List<ShiftRequest> requests = List
                .of(request(d, D2, null, Availability.TIME, time(13, 0), time(22, 0)));
        List<ShiftAssignment> assignments = List
                .of(timeAssignment(106L, d, D2, time(13, 0), time(22, 0), AssignmentStatus.ABSENT));
        givenPage(period, List.of(d), requests, List.of(14L), assignments, List.of());

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.timeRequests()).hasSize(1);
        assertThat(page.timeRequests().get(0).assigned()).isFalse();
    }

    @Test
    void getPeriodPage_時間指定でない希望は時間指定の一覧に入れない() {
        User a = user(11L, "山田", "やまだ", false, true, skill2);
        givenPage(period, List.of(a), List.of(request(a, D1, daySlot, Availability.OK, null, null)), List.of(11L),
                List.of(slotAssignment(101L, a, D1, daySlot, AssignmentStatus.ASSIGNED)), List.of());

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.timeRequests()).isEmpty();
        assertThat(page.timeAssignments()).isEmpty();
    }

    // ===== getPeriodPage：状態ごとの編集・公開の可否 =====

    @Test
    void getPeriodPage_受付中は編集も公開もできずW2604() {
        givenPage(period(PeriodStatus.OPEN), List.of(), List.of(), List.of(), List.of(), List.of());

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.editable()).isFalse();
        assertThat(page.publishable()).isFalse();
        assertThat(page.notPublishableCode()).isEqualTo("W2604");
    }

    @Test
    void getPeriodPage_作成中で研修中のみの枠があれば公開できずW2605() {
        givenPage(period, List.of(), List.of(), List.of(), List.of(), List.of(warning(WarningType.TRAINEE_ONLY)));

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.editable()).isTrue();
        assertThat(page.publishable()).isFalse();
        assertThat(page.notPublishableCode()).isEqualTo("W2605");
        assertThat(page.warnings()).hasSize(1);
    }

    @Test
    void getPeriodPage_作成中で研修中のみ以外の警告なら公開できる() {
        givenPage(period, List.of(), List.of(), List.of(), List.of(), List.of(warning(WarningType.SHORTAGE)));

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.publishable()).isTrue();
        assertThat(page.notPublishableCode()).isNull();
    }

    @Test
    void getPeriodPage_公開済みは編集できて公開済みの確認C2604を付け公開の理由はない() {
        ShiftPeriod published = period(PeriodStatus.PUBLISHED);
        published.setPublishedAt(NOW.minusDays(1));
        User a = user(11L, "山田", "やまだ", false, true, skill2);
        givenPage(published, List.of(a), List.of(), List.of(11L),
                List.of(slotAssignment(101L, a, D1, daySlot, AssignmentStatus.ASSIGNED)),
                List.of(warning(WarningType.TRAINEE_ONLY)));

        PeriodPageView page = service.getPeriodPage(1L);

        assertThat(page.editable()).isTrue();
        assertThat(page.publishable()).isFalse();
        assertThat(page.notPublishableCode()).isNull();
        assertThat(page.publishedAt()).isEqualTo(NOW.minusDays(1));
        assertThat(page.publishedAtLabel()).isEqualTo("2099/10/09 10:00");
        assertThat(cell(page, 11L, D1, DAY).confirmCode()).isEqualTo("C2604");
    }

    @Test
    void getPeriodPage_存在しない期間は404() {
        when(periodService.getPeriod(99L)).thenThrow(new ResourceNotFoundException("期間がない"));

        assertThatThrownBy(() -> service.getPeriodPage(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findStaffOptions_有効なバイトを返す() {
        List<User> staff = List.of(user(11L, "山田", "やまだ", false, true));
        when(userRepository.findActiveStaff()).thenReturn(staff);

        assertThat(service.findStaffOptions()).isEqualTo(staff);
    }

    // ===== toggleSlot =====

    private User givenToggleTarget(ShiftPeriod target, boolean active) {
        User staff = user(USER_ID, "山田", "やまだ", false, active, skill2);
        when(periodService.getPeriod(1L)).thenReturn(target);
        when(userRepository.findByIdAndRole(USER_ID, Role.STAFF)).thenReturn(Optional.of(staff));
        return staff;
    }

    private void givenSave() {
        when(shiftAssignmentRepository.save(any(ShiftAssignment.class))).thenAnswer(invocation -> {
            ShiftAssignment saved = invocation.getArgument(0);
            saved.setId(201L);
            return saved;
        });
    }

    private BusinessException catchBusiness(Runnable action) {
        return (BusinessException) org.assertj.core.api.Assertions.catchThrowable(action::run);
    }

    @Test
    void toggleSlot_受付中の期間ならE2602() {
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.OPEN));

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2602");
        verify(shiftAssignmentRepository, never()).save(any());
    }

    @Test
    void toggleSlot_期間より前の日付ならE2601() {
        when(periodService.getPeriod(1L)).thenReturn(period);

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D1.minusDays(1), DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2601");
    }

    @Test
    void toggleSlot_期間より後の日付ならE2601() {
        when(periodService.getPeriod(1L)).thenReturn(period);

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D3.plusDays(1), DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2601");
    }

    @Test
    void toggleSlot_期間の初日と最終日は期間内() {
        givenToggleTarget(period, true);
        givenSave();
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D1, DAY)).thenReturn(Optional.empty());
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D3, DAY)).thenReturn(Optional.empty());
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(any(), any(), any())).thenReturn(List.of());

        service.toggleSlot(1L, USER_ID, D1, DAY, OPERATOR_ID);
        service.toggleSlot(1L, USER_ID, D3, DAY, OPERATOR_ID);

        verify(shiftAssignmentRepository, org.mockito.Mockito.times(2)).save(any(ShiftAssignment.class));
    }

    @Test
    void toggleSlot_スタッフがいなければ404() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(userRepository.findByIdAndRole(USER_ID, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void toggleSlot_存在しない枠ならE2601() {
        givenToggleTarget(period, true);
        when(slotRepository.findById((byte) 9)).thenReturn(Optional.empty());

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D2, (byte) 9, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2601");
    }

    @Test
    void toggleSlot_欠勤の割当のセルはE2603で変えない() {
        givenToggleTarget(period, true);
        User staff = user(USER_ID, "山田", "やまだ", false, true);
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY))
                .thenReturn(Optional.of(slotAssignment(101L, staff, D2, daySlot, AssignmentStatus.ABSENT)));

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2603");
        verify(shiftAssignmentRepository, never()).delete(any());
    }

    @Test
    void toggleSlot_作成中に割当を外すとお知らせは出さず操作ログを残す() {
        User staff = givenToggleTarget(period, true);
        ShiftAssignment existing = slotAssignment(101L, staff, D2, daySlot, AssignmentStatus.ASSIGNED);
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY)).thenReturn(Optional.of(existing));

        service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID);

        verify(shiftAssignmentRepository).delete(existing);
        verify(notificationService, never()).notifyChanged(any(), any(), any());
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_REMOVE, "assignment:101",
                "user=5 date=2099-11-02 slot=DAY");
    }

    @Test
    void toggleSlot_公開済みで割当を外すと本人にN1004のお知らせを出す() {
        User staff = givenToggleTarget(period(PeriodStatus.PUBLISHED), true);
        ShiftAssignment existing = slotAssignment(101L, staff, D2, daySlot, AssignmentStatus.ASSIGNED);
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY)).thenReturn(Optional.of(existing));

        service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID);

        verify(shiftAssignmentRepository).delete(existing);
        verify(notificationService).notifyChanged(eq(staff), any(ShiftPeriod.class), eq("N1004:[11月2日(月), 日勤]"));
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_REMOVE, "assignment:101",
                "user=5 date=2099-11-02 slot=DAY");
    }

    @Test
    void toggleSlot_無効なスタッフには割り当てられずE2604() {
        givenToggleTarget(period, false);
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY)).thenReturn(Optional.empty());

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2604");
        verify(shiftAssignmentRepository, never()).save(any());
    }

    @Test
    void toggleSlot_同じ日に時間が重なる割当があればE2605で氏名と日付を渡す() {
        User staff = givenToggleTarget(period, true);
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY)).thenReturn(Optional.empty());
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of(timeAssignment(300L, staff, D2, time(13, 0), time(22, 0),
                        AssignmentStatus.ASSIGNED)));

        BusinessException actual = catchBusiness(
                () -> service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2605");
        assertThat(actual.getArgs()).containsExactly("山田", "11月2日(月)");
        verify(shiftAssignmentRepository, never()).save(any());
    }

    @Test
    void toggleSlot_作成中に割当を付けるとお知らせは出さず夕勤と接するだけなら付けられる() {
        User staff = givenToggleTarget(period, true);
        givenSave();
        when(slotRepository.findById((byte) DAY)).thenReturn(Optional.of(daySlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, DAY)).thenReturn(Optional.empty());
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of(slotAssignment(300L, staff, D2, eveningSlot, AssignmentStatus.ASSIGNED)));

        service.toggleSlot(1L, USER_ID, D2, DAY, OPERATOR_ID);

        ArgumentCaptor<ShiftAssignment> captor = ArgumentCaptor.forClass(ShiftAssignment.class);
        verify(shiftAssignmentRepository).save(captor.capture());
        ShiftAssignment saved = captor.getValue();
        assertThat(saved.getPeriod()).isSameAs(period);
        assertThat(saved.getUser()).isSameAs(staff);
        assertThat(saved.getWorkDate()).isEqualTo(D2);
        assertThat(saved.getSlot()).isSameAs(daySlot);
        assertThat(saved.getStartTime()).isEqualTo(time(8, 0));
        assertThat(saved.getEndTime()).isEqualTo(time(17, 0));
        assertThat(saved.getStatus()).isEqualTo(AssignmentStatus.ASSIGNED);
        verify(notificationService, never()).notifyChanged(any(), any(), any());
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_ADD, "assignment:201",
                "user=5 date=2099-11-02 slot=DAY");
    }

    @Test
    void toggleSlot_公開済みで割当を付けると本人にN1003のお知らせを出す() {
        User staff = givenToggleTarget(period(PeriodStatus.PUBLISHED), true);
        givenSave();
        when(slotRepository.findById((byte) EVENING)).thenReturn(Optional.of(eveningSlot));
        when(shiftAssignmentRepository.findCell(1L, USER_ID, D2, EVENING)).thenReturn(Optional.empty());
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of());

        service.toggleSlot(1L, USER_ID, D2, EVENING, OPERATOR_ID);

        verify(notificationService).notifyChanged(eq(staff), any(ShiftPeriod.class), eq("N1003:[11月2日(月), 夕勤]"));
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_ADD, "assignment:201",
                "user=5 date=2099-11-02 slot=EVENING");
    }

    // ===== assignTime =====

    @Test
    void assignTime_受付中の期間ならE2602() {
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.OPEN));

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(13, 0), time(22, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2602");
    }

    @Test
    void assignTime_開始が7時30分ならE2608() {
        givenToggleTarget(period, true);

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(7, 30), time(12, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2608");
    }

    @Test
    void assignTime_終了が22時30分ならE2608() {
        givenToggleTarget(period, true);

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(13, 0), time(22, 30), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2608");
    }

    @Test
    void assignTime_開始と終了が同じならE2609() {
        givenToggleTarget(period, true);

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(13, 0), time(13, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2609");
    }

    @Test
    void assignTime_終了が開始より前ならE2609() {
        givenToggleTarget(period, true);

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(22, 0), time(8, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2609");
    }

    @Test
    void assignTime_無効なスタッフならE2604() {
        givenToggleTarget(period, false);

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(13, 0), time(22, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2604");
    }

    @Test
    void assignTime_時間が重なる割当があればE2605() {
        User staff = givenToggleTarget(period, true);
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of(slotAssignment(300L, staff, D2, daySlot, AssignmentStatus.ASSIGNED)));

        BusinessException actual = catchBusiness(
                () -> service.assignTime(1L, USER_ID, D2, time(13, 0), time(22, 0), OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2605");
        assertThat(actual.getArgs()).containsExactly("山田", "11月2日(月)");
    }

    @Test
    void assignTime_作成中に時間指定で割り当てるとお知らせは出さず操作ログを残す() {
        User staff = givenToggleTarget(period, true);
        givenSave();
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of());

        service.assignTime(1L, USER_ID, D2, time(13, 0), time(22, 0), OPERATOR_ID);

        ArgumentCaptor<ShiftAssignment> captor = ArgumentCaptor.forClass(ShiftAssignment.class);
        verify(shiftAssignmentRepository).save(captor.capture());
        ShiftAssignment saved = captor.getValue();
        assertThat(saved.getSlot()).isNull();
        assertThat(saved.getUser()).isSameAs(staff);
        assertThat(saved.getStartTime()).isEqualTo(time(13, 0));
        assertThat(saved.getEndTime()).isEqualTo(time(22, 0));
        assertThat(saved.getStatus()).isEqualTo(AssignmentStatus.ASSIGNED);
        verify(notificationService, never()).notifyChanged(any(), any(), any());
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_ADD, "assignment:201",
                "user=5 date=2099-11-02 slot=TIME 13:00-22:00");
    }

    @Test
    void assignTime_公開済みなら時間指定の枠名でN1003のお知らせを出す() {
        User staff = givenToggleTarget(period(PeriodStatus.PUBLISHED), true);
        givenSave();
        when(shiftAssignmentRepository.findByUserIdAndWorkDateAndStatus(USER_ID, D2, AssignmentStatus.ASSIGNED))
                .thenReturn(List.of());

        service.assignTime(1L, USER_ID, D2, time(8, 0), time(12, 30), OPERATOR_ID);

        verify(notificationService).notifyChanged(eq(staff), any(ShiftPeriod.class),
                eq("N1003:[11月2日(月), N1006:[8:00, 12:30]]"));
    }

    // ===== delete =====

    @Test
    void delete_割当がなければ404() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftAssignmentRepository.findByIdAndPeriodId(101L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L, 101L, OPERATOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_受付中の期間ならE2602() {
        User staff = user(USER_ID, "山田", "やまだ", false, true);
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.OPEN));
        when(shiftAssignmentRepository.findByIdAndPeriodId(101L, 1L)).thenReturn(Optional
                .of(timeAssignment(101L, staff, D2, time(13, 0), time(22, 0), AssignmentStatus.ASSIGNED)));

        BusinessException actual = catchBusiness(() -> service.delete(1L, 101L, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2602");
        verify(shiftAssignmentRepository, never()).delete(any());
    }

    @Test
    void delete_欠勤の割当はE2603() {
        User staff = user(USER_ID, "山田", "やまだ", false, true);
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftAssignmentRepository.findByIdAndPeriodId(101L, 1L)).thenReturn(Optional
                .of(timeAssignment(101L, staff, D2, time(13, 0), time(22, 0), AssignmentStatus.ABSENT)));

        BusinessException actual = catchBusiness(() -> service.delete(1L, 101L, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2603");
        verify(shiftAssignmentRepository, never()).delete(any());
    }

    @Test
    void delete_作成中に外すとお知らせは出さず操作ログを残す() {
        User staff = user(USER_ID, "山田", "やまだ", false, true);
        ShiftAssignment existing = slotAssignment(101L, staff, D2, daySlot, AssignmentStatus.ASSIGNED);
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftAssignmentRepository.findByIdAndPeriodId(101L, 1L)).thenReturn(Optional.of(existing));

        service.delete(1L, 101L, OPERATOR_ID);

        verify(shiftAssignmentRepository).delete(existing);
        verify(notificationService, never()).notifyChanged(any(), any(), any());
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_REMOVE, "assignment:101",
                "user=5 date=2099-11-02 slot=DAY");
    }

    @Test
    void delete_公開済みで時間指定の割当を外すと時間指定の枠名でN1004のお知らせを出す() {
        User staff = user(USER_ID, "山田", "やまだ", false, true);
        ShiftAssignment existing = timeAssignment(101L, staff, D2, time(13, 0), time(22, 0),
                AssignmentStatus.ASSIGNED);
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.PUBLISHED));
        when(shiftAssignmentRepository.findByIdAndPeriodId(101L, 1L)).thenReturn(Optional.of(existing));

        service.delete(1L, 101L, OPERATOR_ID);

        verify(notificationService).notifyChanged(eq(staff), any(ShiftPeriod.class),
                eq("N1004:[11月2日(月), N1006:[13:00, 22:00]]"));
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ASSIGNMENT_REMOVE, "assignment:101",
                "user=5 date=2099-11-02 slot=TIME 13:00-22:00");
    }

    // ===== publish =====

    @Test
    void publish_受付中ならE2610() {
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.OPEN));

        BusinessException actual = catchBusiness(() -> service.publish(1L, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2610");
    }

    @Test
    void publish_公開済みにもう一度公開するとE2610() {
        when(periodService.getPeriod(1L)).thenReturn(period(PeriodStatus.PUBLISHED));

        BusinessException actual = catchBusiness(() -> service.publish(1L, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2610");
        verify(notificationService, never()).notifyPublished(any());
    }

    @Test
    void publish_研修中のみの枠があればE2611で件数を渡す() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftWarningService.calculate(period)).thenReturn(List.of(warning(WarningType.TRAINEE_ONLY),
                warning(WarningType.SHORTAGE), warning(WarningType.TRAINEE_ONLY)));

        BusinessException actual = catchBusiness(() -> service.publish(1L, OPERATOR_ID));

        assertThat(actual.getMessageCode()).isEqualTo("E2611");
        assertThat(actual.getArgs()).containsExactly(2L);
        assertThat(period.getStatus()).isEqualTo(PeriodStatus.CLOSED);
        verify(notificationService, never()).notifyPublished(any());
    }

    @Test
    void publish_警告がなければ公開してお知らせと操作ログを残す() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftWarningService.calculate(period)).thenReturn(List.of());
        when(shiftAssignmentRepository.countByPeriodIdAndStatus(1L, AssignmentStatus.ASSIGNED)).thenReturn(5L);

        PublishResult actual = service.publish(1L, OPERATOR_ID);

        assertThat(actual.assignmentCount()).isEqualTo(5);
        assertThat(actual.hasWarnings()).isFalse();
        assertThat(period.getStatus()).isEqualTo(PeriodStatus.PUBLISHED);
        assertThat(period.getPublishedAt()).isEqualTo(NOW);
        verify(shiftPeriodRepository).save(period);
        verify(notificationService).notifyPublished(period);
        verify(operationLogService).record(OPERATOR_ID, OperationAction.SHIFT_PUBLISH, "period:1",
                "2099-11-01〜2099-11-03 assignments=5");
    }

    @Test
    void publish_人数不足などの警告が残っていても公開してhasWarningsを返す() {
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftWarningService.calculate(period)).thenReturn(List.of(warning(WarningType.SHORTAGE)));
        when(shiftAssignmentRepository.countByPeriodIdAndStatus(1L, AssignmentStatus.ASSIGNED)).thenReturn(0L);

        PublishResult actual = service.publish(1L, OPERATOR_ID);

        assertThat(actual.hasWarnings()).isTrue();
        assertThat(period.getStatus()).isEqualTo(PeriodStatus.PUBLISHED);
    }
}

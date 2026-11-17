package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.AbsenceConfirmView;
import jp.numajo.fgroup.shift.dto.AbsenceDayView;
import jp.numajo.fgroup.shift.dto.AbsenceTargetRow;
import jp.numajo.fgroup.shift.entity.Absence;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.AbsenceRepository;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * AbsenceService（SM-07：欠勤登録・欠員通知）の単体テスト（20-SM-07 7章、97_テスト計画書 3.2）。
 *
 * <p>
 * Repository・NotificationService・OperationLogService はモックにする。メッセージは本物の messages.properties を使う。
 * テストの「今」は 2026/11/10 10:00、割当の日付は 11/12(木)。
 * </p>
 *
 * <pre>
 * 組み合わせ表1：欠勤にできる割当か（checkRegistrable。getConfirm・register で同じ。MCC：2³＝8通りすべて）
 *     A：期間が公開済み、B：割当が出勤（ASSIGNED）、C：日付が今日以降
 * No. | A  | B  | C  | 結果                  | テストの値（期間、割当、今日から何日後）
 *  1  | 偽 | 真 | 真 | E2706                 | CLOSED、ASSIGNED、2日後
 *  2  | 偽 | 真 | 偽 | E2706                 | CLOSED、ASSIGNED、昨日
 *  3  | 偽 | 偽 | 真 | E2706                 | CLOSED、ABSENT、2日後
 *  4  | 偽 | 偽 | 偽 | E2706（A が先に判定） | CLOSED、ABSENT、昨日
 *  5  | 真 | 偽 | 真 | E2707                 | PUBLISHED、ABSENT、2日後
 *  6  | 真 | 偽 | 偽 | E2707（B が先に判定） | PUBLISHED、ABSENT、昨日
 *  7  | 真 | 真 | 偽 | E2702                 | PUBLISHED、ASSIGNED、昨日
 *  8  | 真 | 真 | 真 | 欠勤にできる           | PUBLISHED、ASSIGNED、当日（境界）と翌日
 *
 * 組み合わせ表2：欠員通知の相手に入るか（findVacancyRecipients）
 *     A：欠勤する本人、B：必要職能をすべて持つ、C：その時間に重なる出勤がある
 * No. | A  | B  | C  | 相手に入るか | テスト
 *  1  | 偽 | 真 | 偽 | 入る         | 条件に合う人_相手に入る
 *  2  | 真 | 真 | 偽 | 入らない     | 欠勤する本人_相手に入らない
 *  3  | 偽 | 偽 | 偽 | 入らない     | 職能が足りない人_相手に入らない
 *  4  | 偽 | 真 | 真 | 入らない     | その時間に出勤がある人_相手に入らない
 *  （A 真のとき B・C は問わない。本人は必ず除く）
 *
 * 判定：対象の枠（isTarget）  割当に枠がある → 枠のIDが同じ／ない → 時間が重なる
 * No. | 割当の枠 | 枠のID・時間 | 結果 | テスト
 *  1  | あり     | 同じ         | 対象   | 日勤の割当_日勤の必要職能だけが条件になる
 *  2  | あり     | 違う         | 対象外 | 同上（夕勤の必要職能は条件にならない）
 *  3  | なし     | 重なる       | 対象   | 時間指定13時から22時_日勤と夕勤の必要職能がどちらも条件になる
 *  4  | なし     | 重ならない   | 対象外 | 時間指定で朝だけ_日勤の必要職能だけが条件になる（夕勤は対象外）
 *
 * 判定：理由の保存（reason == null || reason.isBlank()）
 * No. | null | 空白だけ | 保存する値 | テスト
 *  1  | 真   | －       | null       | 理由の保存_なし_空白だけ_ありで保存する値が決まる（1行目）
 *  2  | 偽   | 真       | null       | 同（2行目。空白2文字）
 *  3  | 偽   | 偽       | そのまま   | 同（3行目。体調不良）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class AbsenceServiceTests {

    /** テストの「今」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);
    /** 割当の日付（木曜日）。 */
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 11, 12);
    private static final Long OPERATOR_ID = 99L;
    private static final Long ASSIGNMENT_ID = 10L;
    private static final String NOTICE = "入れる方は連絡してください。";

    @Mock
    private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock
    private AbsenceRepository absenceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private OperationLogService operationLogService;

    private AbsenceService service;

    private final Skill register = skill(1L, "レジ");
    private final Skill stocking = skill(2L, "品出し");
    private final Slot daySlot = slot(SlotIds.DAY, "DAY", "日勤", "08:00", "17:00");
    private final Slot eveningSlot = slot(SlotIds.EVENING, "EVENING", "夕勤", "17:00", "22:00");

    private final ShiftPeriod published = period(5L, PeriodStatus.PUBLISHED);
    private final User absentUser = user(1L, "山田 太郎", "やまだ たろう", false);

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new AbsenceService(shiftAssignmentRepository, absenceRepository, userRepository, slotRepository,
                notificationService, operationLogService, messageSource, clock);
    }

    // ---------------------------------------------------------------- 部品

    private static Skill skill(Long id, String name) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private static Slot slot(byte id, String code, String name, String start, String end) {
        Slot s = new Slot();
        s.setId(id);
        s.setCode(code);
        s.setName(name);
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        s.setRequiredSkills(new HashSet<>());
        return s;
    }

    private static ShiftPeriod period(Long id, PeriodStatus status) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStatus(status);
        return p;
    }

    private static User user(Long id, String name, String kana, boolean trainee, Skill... skills) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setNameKana(kana);
        u.setRole(Role.STAFF);
        u.setActive(true);
        u.setTrainee(trainee);
        u.setSkills(new HashSet<>(List.of(skills)));
        return u;
    }

    private static ShiftAssignment assignment(Long id, ShiftPeriod period, User user, LocalDate date, Slot slot,
            String start, String end, AssignmentStatus status) {
        ShiftAssignment a = new ShiftAssignment();
        a.setId(id);
        a.setPeriod(period);
        a.setUser(user);
        a.setWorkDate(date);
        a.setSlot(slot);
        a.setStartTime(LocalTime.parse(start));
        a.setEndTime(LocalTime.parse(end));
        a.setStatus(status);
        return a;
    }

    /** 公開済みの期間の、欠勤する本人の日勤の割当（11/12 8:00〜17:00、出勤）。 */
    private ShiftAssignment dayAssignment() {
        return assignment(ASSIGNMENT_ID, published, absentUser, WORK_DATE, daySlot, "08:00", "17:00",
                AssignmentStatus.ASSIGNED);
    }

    /** 公開済みの期間の、欠勤する本人の時間指定 13:00〜22:00 の割当（出勤）。 */
    private ShiftAssignment timeAssignment() {
        return assignment(ASSIGNMENT_ID, published, absentUser, WORK_DATE, null, "13:00", "22:00",
                AssignmentStatus.ASSIGNED);
    }

    /** 通知の相手を選ぶために読むデータ（枠・有効なバイト・その日の出勤）をモックに入れる。 */
    private void stubRecipientSources(List<User> staff, List<ShiftAssignment> assignedThatDay) {
        lenient().when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));
        lenient().when(userRepository.findActiveStaffWithSkills()).thenReturn(staff);
        lenient().when(shiftAssignmentRepository.findByWorkDateAndStatus(WORK_DATE, AssignmentStatus.ASSIGNED))
                .thenReturn(assignedThatDay);
    }

    private void stubFind(ShiftAssignment assignment) {
        when(shiftAssignmentRepository.findDetailById(assignment.getId())).thenReturn(Optional.of(assignment));
    }

    private static List<Long> ids(List<User> users) {
        return users.stream().map(User::getId).toList();
    }

    private static Absence absence(ShiftAssignment assignment, LocalDateTime createdAt, LocalDateTime notifiedAt) {
        Absence b = new Absence();
        b.setAssignment(assignment);
        b.setCreatedAt(createdAt);
        b.setNotifiedAt(notifiedAt);
        return b;
    }

    // ---------------------------------------------------------------- 3.1 getDay

    @Test
    void 出勤者と欠勤が混ざる日_出勤者とすでに欠勤に分けて登録日時と通知日時を付ける() {
        User tanaka = user(2L, "田中 花子", "たなか はなこ", false);
        User sato = user(3L, "佐藤 次郎", "さとう じろう", false);
        ShiftAssignment dayAssigned = assignment(11L, published, tanaka, WORK_DATE, daySlot, "08:00", "17:00",
                AssignmentStatus.ASSIGNED);
        ShiftAssignment absent = assignment(12L, published, sato, WORK_DATE, eveningSlot, "17:00", "22:00",
                AssignmentStatus.ABSENT);
        when(shiftAssignmentRepository.findPublishedByWorkDate(WORK_DATE)).thenReturn(List.of(dayAssigned, absent));
        when(absenceRepository.findByAssignmentIdIn(List.of(12L))).thenReturn(List.of(
                absence(absent, LocalDateTime.of(2026, 11, 10, 9, 5), LocalDateTime.of(2026, 11, 10, 9, 6))));

        AbsenceDayView view = service.getDay(WORK_DATE);

        assertThat(view.date()).isEqualTo(WORK_DATE);
        assertThat(view.dateLabel()).isEqualTo("11月12日(木)");
        assertThat(view.candidatesOfDay()).containsExactly(
                new AbsenceTargetRow(11L, "☀日勤", "田中 花子", null, null, null, null));
        assertThat(view.absentsOfDay()).containsExactly(
                new AbsenceTargetRow(12L, "☾夕勤", "佐藤 次郎",
                        LocalDateTime.of(2026, 11, 10, 9, 5), "2026/11/10 09:05",
                        LocalDateTime.of(2026, 11, 10, 9, 6), "2026/11/10 09:06"));
    }

    @Test
    void 欠勤の人がいない日_欠勤の登録を調べない() {
        when(shiftAssignmentRepository.findPublishedByWorkDate(WORK_DATE)).thenReturn(List.of(dayAssignment()));

        AbsenceDayView view = service.getDay(WORK_DATE);

        assertThat(view.candidatesOfDay()).hasSize(1);
        assertThat(view.absentsOfDay()).isEmpty();
        verify(absenceRepository, never()).findByAssignmentIdIn(any());
    }

    @Test
    void 公開済みの割当がない日_どちらも空() {
        when(shiftAssignmentRepository.findPublishedByWorkDate(WORK_DATE)).thenReturn(List.of());

        AbsenceDayView view = service.getDay(WORK_DATE);

        assertThat(view.candidatesOfDay()).isEmpty();
        assertThat(view.absentsOfDay()).isEmpty();
    }

    @Test
    void 時間指定の出勤者_枠の列は時間指定と時間帯になる() {
        ShiftAssignment time = timeAssignment();
        when(shiftAssignmentRepository.findPublishedByWorkDate(WORK_DATE)).thenReturn(List.of(time));

        AbsenceDayView view = service.getDay(WORK_DATE);

        assertThat(view.candidatesOfDay()).extracting(AbsenceTargetRow::slotLabel)
                .containsExactly("時間指定 13:00〜22:00");
    }

    @Test
    void 欠勤の登録日時がまだない_日時の表示はnullになる() {
        ShiftAssignment absent = assignment(12L, published, absentUser, WORK_DATE, daySlot, "08:00", "17:00",
                AssignmentStatus.ABSENT);
        when(shiftAssignmentRepository.findPublishedByWorkDate(WORK_DATE)).thenReturn(List.of(absent));
        when(absenceRepository.findByAssignmentIdIn(List.of(12L))).thenReturn(List.of(absence(absent, null, null)));

        AbsenceDayView view = service.getDay(WORK_DATE);

        AbsenceTargetRow row = view.absentsOfDay().get(0);
        assertThat(row.registeredAt()).isNull();
        assertThat(row.registeredAtLabel()).isNull();
        assertThat(row.notifiedAt()).isNull();
        assertThat(row.notifiedAtLabel()).isNull();
    }

    // ---------------------------------------------------------------- 3.2 getConfirm

    @Test
    void 日勤の割当_表示と既定の通知文と相手を返す() {
        ShiftAssignment target = dayAssignment();
        User tanaka = user(2L, "田中 花子", "たなか はなこ", true);
        stubFind(target);
        stubRecipientSources(List.of(absentUser, tanaka), List.of());

        AbsenceConfirmView view = service.getConfirm(ASSIGNMENT_ID);

        assertThat(view.assignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(view.workDate()).isEqualTo(WORK_DATE);
        assertThat(view.workDateLabel()).isEqualTo("11月12日(木)");
        assertThat(view.targetLabel()).isEqualTo("11月12日(木) ☀日勤 8:00〜17:00　山田 太郎");
        assertThat(view.defaultMessage())
                .isEqualTo("11月12日の日勤に1人欠員が出ました。入れる方は店舗に連絡してください。");
        assertThat(view.recipients()).hasSize(1);
        assertThat(view.recipients().get(0).userId()).isEqualTo(2L);
        assertThat(view.recipients().get(0).name()).isEqualTo("田中 花子");
        assertThat(view.recipients().get(0).trainee()).isTrue();
    }

    @Test
    void 夕勤の割当_夕勤のアイコンと時間帯で表示する() {
        ShiftAssignment target = assignment(ASSIGNMENT_ID, published, absentUser, WORK_DATE, eveningSlot, "17:00",
                "22:00", AssignmentStatus.ASSIGNED);
        stubFind(target);
        stubRecipientSources(List.of(absentUser), List.of());

        AbsenceConfirmView view = service.getConfirm(ASSIGNMENT_ID);

        assertThat(view.targetLabel()).isEqualTo("11月12日(木) ☾夕勤 17:00〜22:00　山田 太郎");
        assertThat(view.defaultMessage()).startsWith("11月12日の夕勤に1人欠員が出ました。");
        assertThat(view.recipients()).isEmpty();
    }

    @Test
    void 時間指定の割当_表示と既定の通知文に時間帯を入れる() {
        stubFind(timeAssignment());
        stubRecipientSources(List.of(absentUser), List.of());

        AbsenceConfirmView view = service.getConfirm(ASSIGNMENT_ID);

        assertThat(view.targetLabel()).isEqualTo("11月12日(木) 時間指定 13:00〜22:00　山田 太郎");
        assertThat(view.defaultMessage()).startsWith("11月12日の13:00〜22:00に1人欠員が出ました。");
    }

    @Test
    void 確認の画面で割当がない_ResourceNotFoundExceptionになる() {
        when(shiftAssignmentRepository.findDetailById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConfirm(404L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest(name = "期間 {0}、割当 {1}、今日から {2} 日後 → {3}")
    @CsvSource({
            "CLOSED,    ASSIGNED, 2,  E2706",
            "CLOSED,    ASSIGNED, -1, E2706",
            "CLOSED,    ABSENT,   2,  E2706",
            "CLOSED,    ABSENT,   -1, E2706",
            "PUBLISHED, ABSENT,   2,  E2707",
            "PUBLISHED, ABSENT,   -1, E2707",
            "PUBLISHED, ASSIGNED, -1, E2702" })
    void 確認の画面で欠勤にできない割当_業務エラーになる(PeriodStatus periodStatus, AssignmentStatus status, long daysFromToday,
            String code) {
        stubFind(assignment(ASSIGNMENT_ID, period(5L, periodStatus), absentUser,
                NOW.toLocalDate().plusDays(daysFromToday), daySlot, "08:00", "17:00", status));

        assertThatThrownBy(() -> service.getConfirm(ASSIGNMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo(code);
    }

    @ParameterizedTest(name = "今日から {0} 日後 → 確認の画面を出せる")
    @CsvSource({ "0", "1" })
    void 確認の画面で当日と翌日_欠勤にできる(long daysFromToday) {
        stubFind(assignment(ASSIGNMENT_ID, published, absentUser, NOW.toLocalDate().plusDays(daysFromToday),
                daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED));
        lenient().when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));
        lenient().when(userRepository.findActiveStaffWithSkills()).thenReturn(List.of());
        lenient().when(shiftAssignmentRepository.findByWorkDateAndStatus(any(), any())).thenReturn(List.of());

        AbsenceConfirmView view = service.getConfirm(ASSIGNMENT_ID);

        assertThat(view.workDate()).isEqualTo(NOW.toLocalDate().plusDays(daysFromToday));
    }

    // ---------------------------------------------------------------- 割当の日付・期間ID

    @Test
    void 割当の日付を返す() {
        stubFind(dayAssignment());

        assertThat(service.findWorkDate(ASSIGNMENT_ID)).isEqualTo(WORK_DATE);
    }

    @Test
    void 割当の期間IDを返す() {
        stubFind(dayAssignment());

        assertThat(service.findPeriodId(ASSIGNMENT_ID)).isEqualTo(5L);
    }

    @Test
    void 日付を調べる割当がない_ResourceNotFoundExceptionになる() {
        when(shiftAssignmentRepository.findDetailById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findWorkDate(404L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- 3.3 register

    @Test
    void 日勤の人を欠勤にする_欠勤と通知と操作ログが残り通知した人数を返す() {
        ShiftAssignment target = dayAssignment();
        User tanaka = user(2L, "田中 花子", "たなか はなこ", false);
        User sato = user(3L, "佐藤 次郎", "さとう じろう", true);
        User operator = user(OPERATOR_ID, "店長", "てんちょう", false);
        stubFind(target);
        stubRecipientSources(List.of(absentUser, tanaka, sato), List.of());
        when(userRepository.getReferenceById(OPERATOR_ID)).thenReturn(operator);

        int count = service.register(ASSIGNMENT_ID, "体調不良", NOTICE, OPERATOR_ID);

        assertThat(count).isEqualTo(2);
        assertThat(target.getStatus()).isEqualTo(AssignmentStatus.ABSENT);
        ArgumentCaptor<Absence> saved = ArgumentCaptor.forClass(Absence.class);
        verify(absenceRepository).save(saved.capture());
        assertThat(saved.getValue().getAssignment()).isSameAs(target);
        assertThat(saved.getValue().getRegisteredBy()).isSameAs(operator);
        assertThat(saved.getValue().getReason()).isEqualTo("体調不良");
        assertThat(saved.getValue().getNoticeMessage()).isEqualTo(NOTICE);
        assertThat(saved.getValue().getNotifiedAt()).isEqualTo(NOW);
        verify(notificationService).notifyVacancy(List.of(tanaka, sato), target, NOTICE);
        verify(notificationService).notifyChanged(absentUser, published, "11月12日(木) 日勤 が欠勤になりました。");
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ABSENCE_REGISTER, "assignment:10",
                "user=1 date=2026-11-12 slot=DAY recipients=2");
    }

    @Test
    void 時間指定の人を欠勤にする_本人へのお知らせと操作ログに時間指定を使う() {
        ShiftAssignment target = timeAssignment();
        stubFind(target);
        stubRecipientSources(List.of(absentUser), List.of());
        when(userRepository.getReferenceById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "店長", "てんちょう", false));

        int count = service.register(ASSIGNMENT_ID, null, NOTICE, OPERATOR_ID);

        assertThat(count).isZero();
        verify(notificationService).notifyChanged(absentUser, published,
                "11月12日(木) 時間指定 13:00〜22:00 が欠勤になりました。");
        verify(operationLogService).record(OPERATOR_ID, OperationAction.ABSENCE_REGISTER, "assignment:10",
                "user=1 date=2026-11-12 slot=TIME recipients=0");
    }

    @Test
    void 通知できる人がいない_登録はでき0人を返す() {
        ShiftAssignment target = dayAssignment();
        stubFind(target);
        stubRecipientSources(List.of(absentUser), List.of());
        when(userRepository.getReferenceById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "店長", "てんちょう", false));

        int count = service.register(ASSIGNMENT_ID, "私用", NOTICE, OPERATOR_ID);

        assertThat(count).isZero();
        assertThat(target.getStatus()).isEqualTo(AssignmentStatus.ABSENT);
        verify(notificationService).notifyVacancy(List.of(), target, NOTICE);
    }

    @ParameterizedTest(name = "理由が [{0}] のとき、保存する値は [{1}]")
    @CsvSource(value = { "null,null", "'  ',null", "体調不良,体調不良" }, nullValues = "null")
    void 理由の保存_なし_空白だけ_ありで保存する値が決まる(String reason, String expected) {
        stubFind(dayAssignment());
        stubRecipientSources(List.of(absentUser), List.of());
        when(userRepository.getReferenceById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "店長", "てんちょう", false));

        service.register(ASSIGNMENT_ID, reason, NOTICE, OPERATOR_ID);

        ArgumentCaptor<Absence> saved = ArgumentCaptor.forClass(Absence.class);
        verify(absenceRepository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo(expected);
    }

    @Test
    void 登録で割当がない_ResourceNotFoundExceptionになり何も保存しない() {
        when(shiftAssignmentRepository.findDetailById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(404L, null, NOTICE, OPERATOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(absenceRepository, never()).save(any());
    }

    @ParameterizedTest(name = "期間 {0}、割当 {1}、今日から {2} 日後 → {3}")
    @CsvSource({
            "CLOSED,    ASSIGNED, 2,  E2706",
            "CLOSED,    ASSIGNED, -1, E2706",
            "CLOSED,    ABSENT,   2,  E2706",
            "CLOSED,    ABSENT,   -1, E2706",
            "PUBLISHED, ABSENT,   2,  E2707",
            "PUBLISHED, ABSENT,   -1, E2707",
            "PUBLISHED, ASSIGNED, -1, E2702" })
    void 登録で欠勤にできない割当_業務エラーになり何も保存しない(PeriodStatus periodStatus, AssignmentStatus status, long daysFromToday,
            String code) {
        ShiftAssignment target = assignment(ASSIGNMENT_ID, period(5L, periodStatus), absentUser,
                NOW.toLocalDate().plusDays(daysFromToday), daySlot, "08:00", "17:00", status);
        stubFind(target);

        assertThatThrownBy(() -> service.register(ASSIGNMENT_ID, null, NOTICE, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo(code);
        assertThat(target.getStatus()).isEqualTo(status);
        verify(absenceRepository, never()).save(any());
        verify(notificationService, never()).notifyVacancy(any(), any(), any());
        verify(notificationService, never()).notifyChanged(any(), any(), any());
        verify(operationLogService, never()).record(anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "今日から {0} 日後 → 欠勤にできる")
    @CsvSource({ "0", "1" })
    void 登録で当日と翌日_欠勤にできる(long daysFromToday) {
        ShiftAssignment target = assignment(ASSIGNMENT_ID, published, absentUser,
                NOW.toLocalDate().plusDays(daysFromToday), daySlot, "08:00", "17:00", AssignmentStatus.ASSIGNED);
        stubFind(target);
        lenient().when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(daySlot, eveningSlot));
        lenient().when(userRepository.findActiveStaffWithSkills()).thenReturn(List.of());
        lenient().when(shiftAssignmentRepository.findByWorkDateAndStatus(any(), any())).thenReturn(List.of());
        when(userRepository.getReferenceById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "店長", "てんちょう", false));

        int count = service.register(ASSIGNMENT_ID, null, NOTICE, OPERATOR_ID);

        assertThat(count).isZero();
        assertThat(target.getStatus()).isEqualTo(AssignmentStatus.ABSENT);
    }

    // ---------------------------------------------------------------- 3.4 findVacancyRecipients

    @Test
    void 条件に合う人_相手に入る() {
        User tanaka = user(2L, "田中 花子", "たなか はなこ", false);
        stubRecipientSources(List.of(absentUser, tanaka), List.of());

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(2L);
    }

    @Test
    void 欠勤する本人_相手に入らない() {
        stubRecipientSources(List.of(absentUser), List.of());

        assertThat(service.findVacancyRecipients(dayAssignment())).isEmpty();
    }

    @Test
    void 職能が足りない人_相手に入らない() {
        daySlot.getRequiredSkills().add(register);
        User withoutSkill = user(2L, "田中 花子", "たなか はなこ", false, stocking);
        User withSkill = user(3L, "佐藤 次郎", "さとう じろう", false, register, stocking);
        stubRecipientSources(List.of(absentUser, withoutSkill, withSkill), List.of());

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(3L);
    }

    @Test
    void その時間に出勤がある人_相手に入らない() {
        User busy = user(2L, "田中 花子", "たなか はなこ", false);
        User free = user(3L, "佐藤 次郎", "さとう じろう", false);
        ShiftAssignment busyAssignment = assignment(20L, published, busy, WORK_DATE, daySlot, "08:00", "17:00",
                AssignmentStatus.ASSIGNED);
        stubRecipientSources(List.of(absentUser, busy, free), List.of(busyAssignment));

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(3L);
    }

    @Test
    void 同じ日の夕勤に入っている人_日勤の欠員の相手に入る() {
        User evening = user(2L, "田中 花子", "たなか はなこ", false);
        ShiftAssignment eveningAssignment = assignment(20L, published, evening, WORK_DATE, eveningSlot, "17:00",
                "22:00", AssignmentStatus.ASSIGNED);
        stubRecipientSources(List.of(absentUser, evening), List.of(eveningAssignment));

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(2L);
    }

    @Test
    void 同じ日の13時から22時に入っている人_日勤の欠員の相手に入らない() {
        User late = user(2L, "田中 花子", "たなか はなこ", false);
        ShiftAssignment lateAssignment = assignment(20L, published, late, WORK_DATE, null, "13:00", "22:00",
                AssignmentStatus.ASSIGNED);
        stubRecipientSources(List.of(absentUser, late), List.of(lateAssignment));

        assertThat(service.findVacancyRecipients(dayAssignment())).isEmpty();
    }

    @Test
    void 日勤の割当_日勤の必要職能だけが条件になる() {
        daySlot.getRequiredSkills().add(register);
        eveningSlot.getRequiredSkills().add(stocking);
        User registerOnly = user(2L, "田中 花子", "たなか はなこ", false, register);
        User stockingOnly = user(3L, "佐藤 次郎", "さとう じろう", false, stocking);
        stubRecipientSources(List.of(absentUser, registerOnly, stockingOnly), List.of());

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(2L);
    }

    @Test
    void 時間指定13時から22時_日勤と夕勤の必要職能がどちらも条件になる() {
        daySlot.getRequiredSkills().add(register);
        eveningSlot.getRequiredSkills().add(stocking);
        User registerOnly = user(2L, "田中 花子", "たなか はなこ", false, register);
        User both = user(3L, "佐藤 次郎", "さとう じろう", false, register, stocking);
        stubRecipientSources(List.of(absentUser, registerOnly, both), List.of());

        List<User> recipients = service.findVacancyRecipients(timeAssignment());

        assertThat(ids(recipients)).containsExactly(3L);
    }

    @Test
    void 時間指定で朝だけ_日勤の必要職能だけが条件になる() {
        daySlot.getRequiredSkills().add(register);
        eveningSlot.getRequiredSkills().add(stocking);
        ShiftAssignment morning = assignment(ASSIGNMENT_ID, published, absentUser, WORK_DATE, null, "08:00", "12:30",
                AssignmentStatus.ASSIGNED);
        User registerOnly = user(2L, "田中 花子", "たなか はなこ", false, register);
        User stockingOnly = user(3L, "佐藤 次郎", "さとう じろう", false, stocking);
        stubRecipientSources(List.of(absentUser, registerOnly, stockingOnly), List.of());

        List<User> recipients = service.findVacancyRecipients(morning);

        assertThat(ids(recipients)).containsExactly(2L);
    }

    @Test
    void 必要職能がない枠_職能のない人も相手に入る() {
        User noSkill = user(2L, "田中 花子", "たなか はなこ", false);
        stubRecipientSources(List.of(absentUser, noSkill), List.of());

        assertThat(ids(service.findVacancyRecipients(dayAssignment()))).containsExactly(2L);
    }

    @Test
    void 研修中の人_相手に入る() {
        User trainee = user(2L, "田中 花子", "たなか はなこ", true);
        stubRecipientSources(List.of(absentUser, trainee), List.of());

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(2L);
        assertThat(recipients.get(0).isTrainee()).isTrue();
    }

    @Test
    void 有効なバイトの並び_リポジトリの並びのまま返す() {
        User first = user(3L, "佐藤 次郎", "さとう じろう", false);
        User second = user(2L, "田中 花子", "たなか はなこ", false);
        stubRecipientSources(List.of(first, second, absentUser), List.of());

        assertThat(ids(service.findVacancyRecipients(dayAssignment()))).containsExactly(3L, 2L);
    }

    @Test
    void 欠勤する時間の終わりと同じ時刻から始まる出勤_重ならないので相手に入る() {
        User eveningStart = user(2L, "田中 花子", "たなか はなこ", false);
        ShiftAssignment startsAtEnd = assignment(20L, published, eveningStart, WORK_DATE, null, "17:00", "20:00",
                AssignmentStatus.ASSIGNED);
        stubRecipientSources(List.of(absentUser, eveningStart), List.of(startsAtEnd));

        assertThat(ids(service.findVacancyRecipients(dayAssignment()))).containsExactly(2L);
    }

    @Test
    void 欠勤する時間と一部だけ重なる出勤_相手に入らない() {
        User partly = user(2L, "田中 花子", "たなか はなこ", false);
        ShiftAssignment overlapping = assignment(20L, published, partly, WORK_DATE, null, "16:30", "20:00",
                AssignmentStatus.ASSIGNED);
        stubRecipientSources(List.of(absentUser, partly), List.of(overlapping));

        assertThat(service.findVacancyRecipients(dayAssignment())).isEmpty();
    }

    @Test
    void 必要職能が2つある枠_2つともある人だけ相手に入る() {
        daySlot.getRequiredSkills().addAll(List.of(register, stocking));
        User both = user(2L, "田中 花子", "たなか はなこ", false, register, stocking);
        User oneOnly = user(3L, "佐藤 次郎", "さとう じろう", false, register);
        stubRecipientSources(List.of(absentUser, both, oneOnly), List.of());

        List<User> recipients = service.findVacancyRecipients(dayAssignment());

        assertThat(ids(recipients)).containsExactly(2L);
    }
}

package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.entity.CalendarFeed;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.CalendarFeedRepository;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.TokenGenerator;

/**
 * CalendarFeedService の単体テスト（30-05 1.4、20-SB-05 2.1・2.3・2.4、20-IC-01 2〜3）。
 *
 * <p>
 * 今は 2026-11-16 9:00（日本時間）＝ 2026-11-16 00:00 UTC に固定する。
 * 「未公開の期間・欠勤・32日前の割当を出さない」はリポジトリの条件なので、
 * NotificationCalendarServiceIntegrationTests で本物の DB を使って確かめる。
 * </p>
 *
 * <pre>
 * 命令網羅：すべての public・private メソッドの文を通る（下の各テスト）
 *
 * 判定条件網羅・条件網羅：
 * - getOrCreate：行がある／ない
 * - reissue：行がある／ない
 * - newToken の for と if existsByToken：1回目で空き／2回目で空き／3回とも使用中（例外）
 * - webcalUrl：https:// で始まる／http:// で始まる／どちらでもない
 * - buildIcs：トークンがある／ない
 * - buildIcs の for：割当0件／2件以上
 * - summary：枠がある（日勤・夕勤）／ない（時間指定）
 *
 * 組み合わせ表（複数条件網羅）：buildIcs のユーザーの判定
 * 判定：A user == null || B !user.isActive()
 * No. | A | B | 結果
 *  1  | 真| － | 404（短絡評価で B は評価されない）
 *  2  | 偽| 真| 404（無効なユーザー）
 *  3  | 偽| 偽| ICS を返す
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class CalendarFeedServiceTests {

    private static final String TOKEN = "a".repeat(43);
    private static final String TOKEN2 = "b".repeat(43);
    private static final String TOKEN3 = "c".repeat(43);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 9, 0);

    @Mock
    private CalendarFeedRepository calendarFeedRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock
    private StoreSettingService storeSettingService;
    @Mock
    private TokenGenerator tokenGenerator;

    private AppProperties appProperties;
    private CalendarFeedService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), JST);
        service = new CalendarFeedService(calendarFeedRepository, userRepository, shiftAssignmentRepository,
                storeSettingService, tokenGenerator, appProperties, clock);
    }

    private static CalendarFeed feed(long userId, String token) {
        CalendarFeed f = new CalendarFeed();
        f.setUserId(userId);
        f.setToken(token);
        f.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        return f;
    }

    private static User user(long id, String name, boolean active) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setActive(active);
        return u;
    }

    private static Slot slot(String name) {
        Slot s = new Slot();
        s.setName(name);
        return s;
    }

    private static ShiftAssignment assignment(long id, LocalDate date, Slot slot, LocalTime start, LocalTime end) {
        ShiftAssignment a = new ShiftAssignment();
        a.setId(id);
        a.setWorkDate(date);
        a.setSlot(slot);
        a.setStartTime(start);
        a.setEndTime(end);
        return a;
    }

    private void saveReturnsArgument() {
        when(calendarFeedRepository.save(any(CalendarFeed.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** buildIcs の準備（有効なユーザー・店舗名・割当）。 */
    private void prepareIcs(String userName, String storeName, List<ShiftAssignment> assignments) {
        when(calendarFeedRepository.findByToken(TOKEN)).thenReturn(Optional.of(feed(5L, TOKEN)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, userName, true)));
        when(shiftAssignmentRepository.findPublishedForFeed(5L, LocalDate.of(2026, 10, 16))).thenReturn(assignments);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName(storeName);
        when(storeSettingService.get()).thenReturn(setting);
    }

    // ---------------------------------------------------------------- getOrCreate

    @Test
    void getOrCreate_行があればそのまま返し作らない() {
        CalendarFeed existing = feed(5L, TOKEN);
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.of(existing));

        CalendarFeed actual = service.getOrCreate(5L);

        assertThat(actual).isSameAs(existing);
        assertThat(actual.getToken()).isEqualTo(TOKEN);
        verify(calendarFeedRepository, never()).save(any());
        verify(tokenGenerator, never()).generate();
    }

    @Test
    void getOrCreate_行がなければトークンと今の日時で作る() {
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn(TOKEN);
        when(calendarFeedRepository.existsByToken(TOKEN)).thenReturn(false);
        saveReturnsArgument();

        CalendarFeed actual = service.getOrCreate(5L);

        assertThat(actual.getUserId()).isEqualTo(5L);
        assertThat(actual.getToken()).isEqualTo(TOKEN);
        assertThat(actual.getCreatedAt()).isEqualTo(NOW);
        verify(calendarFeedRepository).save(actual);
    }

    // ---------------------------------------------------------------- reissue・newToken

    @Test
    void reissue_行があればトークンと発行日時を新しくする() {
        CalendarFeed existing = feed(5L, TOKEN);
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(tokenGenerator.generate()).thenReturn(TOKEN2);
        when(calendarFeedRepository.existsByToken(TOKEN2)).thenReturn(false);
        saveReturnsArgument();

        CalendarFeed actual = service.reissue(5L);

        assertThat(actual).isSameAs(existing);
        assertThat(actual.getToken()).isEqualTo(TOKEN2);
        assertThat(actual.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void reissue_行がなければ作る() {
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn(TOKEN2);
        when(calendarFeedRepository.existsByToken(TOKEN2)).thenReturn(false);
        saveReturnsArgument();

        CalendarFeed actual = service.reissue(5L);

        assertThat(actual.getUserId()).isEqualTo(5L);
        assertThat(actual.getToken()).isEqualTo(TOKEN2);
        assertThat(actual.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void トークンが使われていたら作り直す_2回目で空き() {
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn(TOKEN, TOKEN2);
        when(calendarFeedRepository.existsByToken(TOKEN)).thenReturn(true);
        when(calendarFeedRepository.existsByToken(TOKEN2)).thenReturn(false);
        saveReturnsArgument();

        assertThat(service.getOrCreate(5L).getToken()).isEqualTo(TOKEN2);
        verify(tokenGenerator, times(2)).generate();
    }

    @Test
    void トークンが3回とも使われていたら例外で保存しない() {
        when(calendarFeedRepository.findById(5L)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn(TOKEN, TOKEN2, TOKEN3);
        when(calendarFeedRepository.existsByToken(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.reissue(5L)).isInstanceOf(IllegalStateException.class);
        verify(tokenGenerator, times(3)).generate();
        verify(calendarFeedRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- feedUrl・webcalUrl

    @Test
    void feedUrl_baseUrlとicalとトークン() {
        assertThat(service.feedUrl(TOKEN)).isEqualTo("http://localhost:8080/ical/" + TOKEN + ".ics");
    }

    @Test
    void webcalUrl_httpはwebcalにする() {
        assertThat(service.webcalUrl(TOKEN)).isEqualTo("webcal://localhost:8080/ical/" + TOKEN + ".ics");
    }

    @Test
    void webcalUrl_httpsもwebcalにする() {
        appProperties.setBaseUrl("https://shift.example.jp");

        assertThat(service.webcalUrl(TOKEN)).isEqualTo("webcal://shift.example.jp/ical/" + TOKEN + ".ics");
    }

    @Test
    void webcalUrl_httpでもhttpsでもなければそのまま() {
        appProperties.setBaseUrl("ftp://shift.example.jp");

        assertThat(service.webcalUrl(TOKEN)).isEqualTo("ftp://shift.example.jp/ical/" + TOKEN + ".ics");
    }

    // ---------------------------------------------------------------- buildIcs

    @Test
    void buildIcs_トークンがなければ404() {
        when(calendarFeedRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildIcs(TOKEN)).isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void buildIcs_組み合わせ1_ユーザーがいなければ404() {
        when(calendarFeedRepository.findByToken(TOKEN)).thenReturn(Optional.of(feed(5L, TOKEN)));
        when(userRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildIcs(TOKEN)).isInstanceOf(ResourceNotFoundException.class);
        verify(shiftAssignmentRepository, never()).findPublishedForFeed(any(), any());
    }

    @Test
    void buildIcs_組み合わせ2_無効なユーザーは404() {
        when(calendarFeedRepository.findByToken(TOKEN)).thenReturn(Optional.of(feed(5L, TOKEN)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, "山田 太郎", false)));

        assertThatThrownBy(() -> service.buildIcs(TOKEN)).isInstanceOf(ResourceNotFoundException.class);
        verify(shiftAssignmentRepository, never()).findPublishedForFeed(any(), any());
    }

    @Test
    void buildIcs_組み合わせ3_割当0件はカレンダーの枠だけ() {
        prepareIcs("山田 太郎", "沼津店", List.of());

        String ics = service.buildIcs(TOKEN);

        assertThat(ics).isEqualTo("BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Fgroup//Shift Management System//JA\r\n"
                + "CALSCALE:GREGORIAN\r\n"
                + "METHOD:PUBLISH\r\n"
                + "X-WR-CALNAME:シフト（山田 太郎）\r\n"
                + "X-WR-TIMEZONE:Asia/Tokyo\r\n"
                + "END:VCALENDAR\r\n");
    }

    @Test
    void buildIcs_日勤夕勤時間指定の件名と時刻がUTCで正しい() {
        LocalDate d = LocalDate.of(2026, 11, 16);
        prepareIcs("山田 太郎", "沼津店", List.of(
                assignment(11L, d, slot("日勤"), LocalTime.of(8, 0), LocalTime.of(17, 0)),
                assignment(12L, d.plusDays(1), slot("夕勤"), LocalTime.of(17, 0), LocalTime.of(22, 0)),
                assignment(13L, d.plusDays(2), null, LocalTime.of(13, 0), LocalTime.of(22, 0))));

        String ics = service.buildIcs(TOKEN);

        assertThat(ics).contains("BEGIN:VEVENT\r\n"
                + "UID:shift-11@shift-management-system\r\n"
                + "DTSTAMP:20261116T000000Z\r\n"
                + "DTSTART:20261115T230000Z\r\n"
                + "DTEND:20261116T080000Z\r\n"
                + "SUMMARY:日勤 8:00-17:00\r\n"
                + "LOCATION:沼津店\r\n"
                + "END:VEVENT\r\n");
        assertThat(ics).contains("DTSTART:20261117T080000Z\r\nDTEND:20261117T130000Z\r\nSUMMARY:夕勤 17:00-22:00\r\n");
        assertThat(ics).contains("DTSTART:20261118T040000Z\r\nDTEND:20261118T130000Z\r\nSUMMARY:時間指定 13:00-22:00\r\n");
        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(4);
        assertThat(ics.indexOf("shift-11@")).isLessThan(ics.indexOf("shift-12@"));
        assertThat(ics.indexOf("shift-12@")).isLessThan(ics.indexOf("shift-13@"));
        assertThat(ics).endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n");
    }

    @Test
    void buildIcs_31日前からの割当を取る() {
        prepareIcs("山田 太郎", "沼津店", List.of());

        service.buildIcs(TOKEN);

        verify(shiftAssignmentRepository).findPublishedForFeed(5L, LocalDate.of(2026, 10, 16));
    }

    @Test
    void buildIcs_店舗名と氏名の記号はエスケープする() {
        prepareIcs("山田,太郎", "沼津店;本店,2号\\館", List.of(
                assignment(11L, LocalDate.of(2026, 11, 16), slot("日勤"), LocalTime.of(8, 0), LocalTime.of(17, 0))));

        String ics = service.buildIcs(TOKEN);

        assertThat(ics).contains("X-WR-CALNAME:シフト（山田\\,太郎）\r\n");
        assertThat(ics).contains("LOCATION:沼津店\\;本店\\,2号\\\\館\r\n");
    }

    @Test
    void buildIcs_75バイトを超える行は折り返す() {
        String longName = "沼".repeat(30);
        prepareIcs("山田 太郎", longName, List.of(
                assignment(11L, LocalDate.of(2026, 11, 16), slot("日勤"), LocalTime.of(8, 0), LocalTime.of(17, 0))));

        String ics = service.buildIcs(TOKEN);

        assertThat(ics).contains("LOCATION:" + "沼".repeat(22) + "\r\n " + "沼".repeat(8) + "\r\n");
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    // ---------------------------------------------------------------- summary

    @Test
    void summary_枠があれば枠名と時刻() {
        ShiftAssignment a = assignment(1L, LocalDate.of(2026, 11, 16), slot("夕勤"),
                LocalTime.of(17, 0), LocalTime.of(22, 0));

        assertThat(CalendarFeedService.summary(a)).isEqualTo("夕勤 17:00-22:00");
    }

    @Test
    void summary_枠がなければ時間指定_時は0埋めしない() {
        ShiftAssignment a = assignment(1L, LocalDate.of(2026, 11, 16), null,
                LocalTime.of(8, 30), LocalTime.of(9, 30));

        assertThat(CalendarFeedService.summary(a)).isEqualTo("時間指定 8:30-9:30");
    }
}

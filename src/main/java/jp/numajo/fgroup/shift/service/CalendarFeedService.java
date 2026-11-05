package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.entity.CalendarFeed;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.CalendarFeedRepository;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.IcsUtil;
import jp.numajo.fgroup.shift.util.TokenGenerator;
import lombok.RequiredArgsConstructor;

/**
 * カレンダー購読（SB-05、IC-01、SM-03）。
 *
 * <p>
 * ICS の件名・カレンダー名の文字は 20-IC-01 3章で形が決めてあるため、ここに定数で書く。
 * 日勤・夕勤の名前は slots.name を使う。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarFeedService {

    /** トークンを作り直す最大の回数（20-SB-05 2.4） */
    static final int MAX_TOKEN_ATTEMPTS = 3;
    /** 何日前までの割当を出すか（20-IC-01 2-2-3） */
    static final int FEED_PAST_DAYS = 31;

    /** カレンダー名（%s＝氏名） */
    static final String CALENDAR_NAME = "シフト（%s）";
    /** 時間指定の件名の頭 */
    static final String TIME_SPECIFIED = "時間指定";
    /** 予定の UID のドメイン */
    static final String UID_DOMAIN = "@shift-management-system";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final String WEBCAL = "webcal://";

    private final CalendarFeedRepository calendarFeedRepository;
    private final UserRepository userRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final StoreSettingService storeSettingService;
    private final TokenGenerator tokenGenerator;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * 購読の行を返す。なければ作る（20-SB-05 2.1-2、20-SM-03 2.2-7）。
     *
     * @param userId ユーザーID
     * @return 購読の行
     */
    @Transactional
    public CalendarFeed getOrCreate(Long userId) {
        return calendarFeedRepository.findById(userId).orElseGet(() -> {
            CalendarFeed feed = new CalendarFeed();
            feed.setUserId(userId);
            return issue(feed);
        });
    }

    /**
     * 購読URLのトークンを新しくする（20-SB-05 2.3）。古いURLは使えなくなる。行がなければ作る。
     *
     * @param userId ユーザーID
     * @return 新しいトークンの行
     */
    @Transactional
    public CalendarFeed reissue(Long userId) {
        CalendarFeed feed = calendarFeedRepository.findById(userId).orElseGet(() -> {
            CalendarFeed f = new CalendarFeed();
            f.setUserId(userId);
            return f;
        });
        return issue(feed);
    }

    /**
     * 購読用URL（例：http://localhost:8080/ical/{token}.ics）。
     *
     * @param token トークン
     * @return 購読用URL
     */
    public String feedUrl(String token) {
        return appProperties.getBaseUrl() + "/ical/" + token + ".ics";
    }

    /**
     * iPhone・Mac で開くURL。feedUrl の先頭の http://・https:// を webcal:// にする。
     *
     * @param token トークン
     * @return webcal:// で始まるURL
     */
    public String webcalUrl(String token) {
        String url = feedUrl(token);
        if (url.startsWith(HTTPS)) {
            return WEBCAL + url.substring(HTTPS.length());
        }
        if (url.startsWith(HTTP)) {
            return WEBCAL + url.substring(HTTP.length());
        }
        return url;
    }

    /**
     * そのトークンの人の確定シフトを iCalendar の文字列にする（20-IC-01 2〜3）。
     *
     * @param token トークン
     * @return iCalendar の文字列（改行は CRLF）
     * @throws ResourceNotFoundException トークンがない、ユーザーがいない・無効
     */
    public String buildIcs(String token) {
        CalendarFeed feed = calendarFeedRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("calendar feed"));
        User user = userRepository.findById(feed.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            throw new ResourceNotFoundException("calendar feed user");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<ShiftAssignment> assignments = shiftAssignmentRepository.findPublishedForFeed(user.getId(),
                now.toLocalDate().minusDays(FEED_PAST_DAYS));
        String storeName = storeSettingService.get().getStoreName();
        String stamp = IcsUtil.utc(now.toLocalDate(), now.toLocalTime().withNano(0));

        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//Fgroup//Shift Management System//JA");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + IcsUtil.escape(String.format(CALENDAR_NAME, user.getName())));
        line(sb, "X-WR-TIMEZONE:Asia/Tokyo");
        for (ShiftAssignment a : assignments) {
            LocalDate date = a.getWorkDate();
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:shift-" + a.getId() + UID_DOMAIN);
            line(sb, "DTSTAMP:" + stamp);
            line(sb, "DTSTART:" + IcsUtil.utc(date, a.getStartTime()));
            line(sb, "DTEND:" + IcsUtil.utc(date, a.getEndTime()));
            line(sb, "SUMMARY:" + IcsUtil.escape(summary(a)));
            line(sb, "LOCATION:" + IcsUtil.escape(storeName));
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** 件名（日勤 8:00-17:00、夕勤 17:00-22:00、時間指定 13:00-22:00）。 */
    static String summary(ShiftAssignment a) {
        String label = a.getSlot() != null ? a.getSlot().getName() : TIME_SPECIFIED;
        return label + " " + a.getStartTime().format(TIME_FORMAT) + "-" + a.getEndTime().format(TIME_FORMAT);
    }

    /** 1行を折り返して CRLF を付ける。 */
    private static void line(StringBuilder sb, String text) {
        sb.append(IcsUtil.fold(text)).append(IcsUtil.CRLF);
    }

    /** 使われていないトークンと今の日時を入れて保存する。 */
    private CalendarFeed issue(CalendarFeed feed) {
        feed.setToken(newToken());
        feed.setCreatedAt(LocalDateTime.now(clock).withNano(0));
        return calendarFeedRepository.save(feed);
    }

    /**
     * まだ使われていないトークンを作る（20-SB-05 2.4）。
     *
     * @throws IllegalStateException 3回作っても使われているトークンだった
     */
    private String newToken() {
        for (int i = 0; i < MAX_TOKEN_ATTEMPTS; i++) {
            String token = tokenGenerator.generate();
            if (!calendarFeedRepository.existsByToken(token)) {
                return token;
            }
        }
        throw new IllegalStateException("購読トークンを" + MAX_TOKEN_ATTEMPTS + "回作りましたが、すべて使われていました");
    }
}

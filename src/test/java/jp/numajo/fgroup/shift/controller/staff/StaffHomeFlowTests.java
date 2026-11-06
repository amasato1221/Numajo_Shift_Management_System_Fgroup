package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.CalendarDay;
import jp.numajo.fgroup.shift.dto.MonthCalendar;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.ShiftBadge;
import jp.numajo.fgroup.shift.dto.StaffHomeView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SB-01 ホームの結合テスト（20-SB-01 6章 No.2〜10、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service・テンプレート（staff/home.html・layout/base.html）を使う。
 * テストごとにロールバックする。今の日時は本物の Clock なので、期間は今日と重ならない 2099年（と 2000年の期限切れ）に作る。
 * No.1（今日の 16:59／17:00）は Clock を固定する必要があるので、RepositoryQueryTests の findNextShifts で確かめている。
 * </p>
 *
 * <pre>
 * No. | 状況                                               | 期待結果
 *  2  | 割当がない                                          | I1101、未提出のカードなし、I1102
 *  3  | 次の割当が未公開の期間だけ                             | I1101
 *  4  | 一番近い割当が欠勤                                    | 飛ばして次の出勤（3/2 日勤）
 *  5  | 未提出の期間が2つ                                     | 期限の早い順に2行、「希望を出す」のリンク
 *  6  | 提出済み・期限切れの期間                               | 出ない
 *  8  | 公開済みの期間（日勤・欠勤）                            | 日勤の色、欠勤は取り消し線のクラス
 *  9  | 未公開の期間で ○・×・時間指定                          | ○と時間指定だけグレー
 * 10  | 2026年11月                                         | 1行目は 10/26〜11/1、10/26〜10/31 は範囲外でバッジなし
 * ほか | 未読4件・既読1件                                    | 未読の新しい3件だけ
 * ほか | 期間のある日／ない日                                  | /staff/shifts?periodId= のリンク／リンクなし
 * ほか | 店長で開く                                           | 店長のホームへ戻す（SecurityConfig）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class StaffHomeFlowTests {

    private static final String YM = "2099-03";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser staff;
    private Long staffId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User u = saveUser("fhstaff", Role.STAFF);
        staffId = u.getId();
        staff = new LoginUser(u, false);
    }

    // ---------------------------------------------------------------- 準備

    private User saveUser(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private Long period(String start, String end, String deadline, String status) {
        jdbcTemplate.update("INSERT INTO shift_periods (start_date, end_date, deadline_at, status) VALUES (?, ?, ?, ?)",
                start, end, deadline, status);
        return jdbcTemplate.queryForObject("SELECT id FROM shift_periods WHERE start_date = ?", Long.class, start);
    }

    private void assignment(Long periodId, String date, Integer slotId, String start, String end, String status) {
        jdbcTemplate.update("INSERT INTO shift_assignments (period_id, user_id, work_date, slot_id, start_time, "
                + "end_time, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                periodId, staffId, date, slotId, start, end, status);
    }

    private void request(Long periodId, String date, Integer slotId, String availability, String start,
            String end) {
        jdbcTemplate.update("INSERT INTO shift_requests (period_id, user_id, work_date, slot_id, availability, "
                + "start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                periodId, staffId, date, slotId, availability, start, end);
    }

    private void notice(String title, String createdAt, String readAt) {
        jdbcTemplate.update("INSERT INTO notifications (user_id, type, title, body, link_url, read_at, created_at) "
                + "VALUES (?, 'CHAT', ?, '本文', '/staff/chat', ?, ?)", staffId, title, readAt, createdAt);
    }

    private MvcResult open(String ym) throws Exception {
        return mvc.perform(get("/staff/home").param("ym", ym).with(user(staff)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static StaffHomeView home(MvcResult result) {
        return (StaffHomeView) result.getModelAndView().getModel().get("home");
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static CalendarDay day(MonthCalendar calendar, String date) {
        LocalDate d = LocalDate.parse(date);
        return calendar.weeks().stream().flatMap(List::stream).filter(x -> x.date().equals(d)).findFirst()
                .orElseThrow();
    }

    // ---------------------------------------------------------------- 次の出勤

    @Test
    void No2_割当も未提出もお知らせもない_それぞれ0件の表示() throws Exception {
        MvcResult result = open(YM);

        StaffHomeView home = home(result);
        assertThat(home.nextShift()).isNull();
        assertThat(home.unsubmittedPeriods()).isEmpty();
        assertThat(home.latestNotices()).isEmpty();
        assertThat(html(result)).contains("予定はありません。", "新しいお知らせはありません。", "2099年3月",
                "href=\"/notices\"", "href=\"/staff/home?ym=2099-02\"", "href=\"/staff/home?ym=2099-04\"")
                .doesNotContain("希望が未提出です");
    }

    @Test
    void No3_次の割当が未公開の期間だけ_I1101() throws Exception {
        Long closed = period("2099-03-01", "2099-03-15", "2099-02-15 23:59:00", "CLOSED");
        assignment(closed, "2099-03-02", 1, "08:00", "17:00", "ASSIGNED");

        MvcResult result = open(YM);

        assertThat(home(result).nextShift()).isNull();
        assertThat(html(result)).contains("予定はありません。");
    }

    @Test
    void No4とNo8_一番近い割当が欠勤_飛ばして次の日勤を出し_カレンダーは日勤の色と欠勤の取り消し線() throws Exception {
        Long published = period("2099-03-01", "2099-03-15", "2099-02-15 23:59:00", "PUBLISHED");
        assignment(published, "2099-03-01", 2, "17:00", "22:00", "ABSENT");
        assignment(published, "2099-03-02", 1, "08:00", "17:00", "ASSIGNED");

        MvcResult result = open(YM);

        StaffHomeView home = home(result);
        assertThat(home.nextShift().workDate()).isEqualTo(LocalDate.parse("2099-03-02"));
        assertThat(home.nextShift().badge().type()).isEqualTo(BadgeType.DAY);
        assertThat(day(home.calendar(), "2099-03-01").badges()).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.ABSENT);
        assertThat(day(home.calendar(), "2099-03-02").badges()).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.DAY);
        assertThat(html(result)).contains("3月2日(月)", "8:00〜17:00", "shift-badge badge-day",
                "shift-badge badge-absent");
    }

    // ---------------------------------------------------------------- カレンダー

    @Test
    void No9_未公開の期間で丸とバツと時間指定_丸と時間指定だけグレー() throws Exception {
        Long open = period("2099-03-16", "2099-03-31", "2099-02-28 23:59:00", "CLOSED");
        request(open, "2099-03-17", 1, "OK", null, null);
        request(open, "2099-03-18", 1, "NG", null, null);
        request(open, "2099-03-19", null, "TIME", "13:00", "22:00");

        MvcResult result = open(YM);

        MonthCalendar calendar = home(result).calendar();
        assertThat(day(calendar, "2099-03-17").badges()).extracting(ShiftBadge::type)
                .containsExactly(BadgeType.PENDING);
        assertThat(day(calendar, "2099-03-18").badges()).isEmpty();
        assertThat(day(calendar, "2099-03-19").badges()).extracting(ShiftBadge::timeText).containsExactly("13-22");
        assertThat(html(result)).contains("shift-badge badge-pending");
    }

    @Test
    void 期間のある日はシフトカレンダーへのリンク_ない日はリンクにしない() throws Exception {
        Long p = period("2099-03-16", "2099-03-31", "2099-02-28 23:59:00", "PUBLISHED");

        MvcResult result = open(YM);

        MonthCalendar calendar = home(result).calendar();
        assertThat(day(calendar, "2099-03-16").periodId()).isEqualTo(p);
        assertThat(day(calendar, "2099-03-15").periodId()).isNull();
        assertThat(html(result)).contains("href=\"/staff/shifts?periodId=" + p + "\"");
    }

    @Test
    void No10_2026年11月_1行目は10月26日から11月1日で前月の日は範囲外() throws Exception {
        MvcResult result = open("2026-11");

        MonthCalendar calendar = home(result).calendar();
        assertThat(calendar.ym()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(calendar.weeks().get(0)).extracting(CalendarDay::date)
                .first().isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(calendar.weeks().get(0)).extracting(CalendarDay::date)
                .last().isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(calendar.weeks().get(0)).extracting(CalendarDay::inMonth)
                .containsExactly(false, false, false, false, false, false, true);
        assertThat(html(result)).contains("2026年11月", "is-outside");
    }

    // ---------------------------------------------------------------- 未提出・お知らせ

    @Test
    void No5とNo6_未提出の期間が2つ_期限の早い順で提出済みと期限切れは出ない() throws Exception {
        Long later = period("2099-04-16", "2099-04-30", "2099-03-31 23:59:00", "OPEN");
        Long earlier = period("2099-04-01", "2099-04-15", "2099-03-15 23:59:00", "OPEN");
        Long submitted = period("2099-05-01", "2099-05-15", "2099-04-15 23:59:00", "OPEN");
        jdbcTemplate.update("INSERT INTO request_submissions (period_id, user_id, submitted_at) "
                + "VALUES (?, ?, '2099-01-01 00:00:00')", submitted, staffId);
        period("2000-01-01", "2000-01-15", "1999-12-15 23:59:00", "OPEN");

        MvcResult result = open(YM);

        assertThat(home(result).unsubmittedPeriods()).extracting(PeriodOption::id).containsExactly(earlier, later);
        assertThat(html(result)).contains("希望が未提出です", "4/1〜4/15 の希望がまだです（期限 3/15 23:59）",
                "4/16〜4/30 の希望がまだです（期限 3/31 23:59）", "href=\"/staff/requests?periodId=" + earlier + "\"");
    }

    @Test
    void 未読4件と既読1件_未読の新しい3件だけを開くボタンで出す() throws Exception {
        notice("fh古い", "2099-01-01 09:00:00", null);
        notice("fh2番目", "2099-01-02 09:00:00", null);
        notice("fh3番目", "2099-01-03 09:00:00", null);
        notice("fh新しい", "2099-01-04 09:00:00", null);
        notice("fh既読", "2099-01-05 09:00:00", "2099-01-05 10:00:00");

        MvcResult result = open(YM);

        assertThat(home(result).latestNotices()).extracting(NoticeRow::title)
                .containsExactly("fh新しい", "fh3番目", "fh2番目");
        assertThat(html(result)).contains("fh新しい", "2099/01/04 09:00", "action=\"/notices/")
                .doesNotContain("fh古い", "fh既読", "新しいお知らせはありません。");
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void 店長で開く_店長のホームへ戻す() throws Exception {
        LoginUser manager = new LoginUser(saveUser("fhmgr", Role.MANAGER), false);

        mvc.perform(get("/staff/home").with(user(manager))).andExpect(redirectedUrl("/manager/home"));
    }
}

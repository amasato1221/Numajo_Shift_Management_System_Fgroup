package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

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

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.DashboardView;
import jp.numajo.fgroup.shift.dto.ShortageView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * SM-01 ダッシュボードの結合テスト（20-SM-01 6章 No.1〜9、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・DashboardService・ShiftWarningService・テンプレート
 * （manager/home.html・layout/base.html）を使う。テストごとにロールバックする。ほかのテストや初期データの影響を
 * 受けないよう、最初に募集期間（割当・提出・欠勤は一緒に消える）を消し、今のバイトを全員無効にする（ロールバックで戻る）。
 * 今日は本物の Clock なので、日付は今日からの相対で作る。必要人数は上書き（required_staff_overrides）で決める。
 * 分岐の細かい組み合わせは DashboardServiceTests で確かめる。
 * </p>
 *
 * <pre>
 * データ（T＝今日）
 * バイト：A（あ・有効）、B（い・有効）、C（う・無効）
 * 期間：
 *   旧1  T−60〜T−32 公開済み   … 表に出ない（No.7）
 *   旧2  T−31〜T−31 公開済み   … 表に出る（境目）
 *   今   T−1〜T+10  公開済み   … 日勤の必要2人・夕勤1人（人数不足でも出ない。No.5）
 *   作成 T+11〜T+25 作成中     … 日勤の必要1人を最初の11日、ほかは0（人数不足11件。No.4）
 *   受付1 T+26〜T+40 受付中    … A と C が提出
 *   受付2 T+41〜T+55 受付中    … A・B が提出（全員提出。No.3）
 * 欠勤（期間「今」）：
 *   T−1 日勤 A（No.6 出ない）、T+2 日勤 A（通知済み。B が出勤で 1人／2人）、
 *   T+3 時間指定 13:00〜22:00 B（No.8 日勤で数える 0人／2人）、T+4 時間指定 7:00〜8:00 B（No.9 人数なし）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerHomeFlowTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LocalDate today;
    private User manager;
    private User a;
    private User b;
    private User c;
    private long currentId;
    private long closedId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        today = LocalDate.now(TOKYO);
        jdbcTemplate.update("DELETE FROM shift_periods");
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE role = 'STAFF'");
        manager = save("fdash_mgr", "店長テスト", "てんちょう", Role.MANAGER, true);
        a = save("fdash_a", "相田テスト", "あ", Role.STAFF, true);
        b = save("fdash_b", "伊藤テスト", "い", Role.STAFF, true);
        c = save("fdash_c", "上野無効テスト", "う", Role.STAFF, false);
    }

    // ---------------------------------------------------------------- 準備

    private User save(String loginId, String name, String kana, Role role, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("dummy");
        u.setName(name);
        u.setNameKana(kana);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private long period(int from, int to, String status) {
        LocalDate start = today.plusDays(from);
        jdbcTemplate.update("INSERT INTO shift_periods (start_date, end_date, deadline_at, status) VALUES (?, ?, ?, ?)",
                start, today.plusDays(to), start.minusDays(14).atTime(23, 59), status);
        return jdbcTemplate.queryForObject("SELECT id FROM shift_periods WHERE start_date = ?", Long.class, start);
    }

    private void submit(long periodId, User u) {
        jdbcTemplate.update("INSERT INTO request_submissions (period_id, user_id, submitted_at) VALUES (?, ?, NOW())",
                periodId, u.getId());
    }

    /** 必要人数をその日・その枠だけ上書きする。 */
    private void required(int day, int slotId, int count) {
        LocalDate d = today.plusDays(day);
        jdbcTemplate.update("DELETE FROM required_staff_overrides WHERE work_date = ? AND slot_id = ?", d, slotId);
        jdbcTemplate.update("INSERT INTO required_staff_overrides (work_date, slot_id, required_count) VALUES (?, ?, ?)",
                d, slotId, count);
    }

    private long assign(long periodId, User u, int day, Integer slotId, String start, String end, String status) {
        LocalDate d = today.plusDays(day);
        jdbcTemplate.update("INSERT INTO shift_assignments (period_id, user_id, work_date, slot_id, start_time, "
                + "end_time, status) VALUES (?, ?, ?, ?, ?, ?, ?)", periodId, u.getId(), d, slotId, start, end, status);
        return jdbcTemplate.queryForObject("SELECT id FROM shift_assignments WHERE user_id = ? AND work_date = ? "
                + "AND start_time = ?", Long.class, u.getId(), d, start);
    }

    private void absent(long assignmentId, boolean notified) {
        jdbcTemplate.update("INSERT INTO absences (assignment_id, registered_by, notice_message, notified_at) "
                + "VALUES (?, ?, 'テスト', " + (notified ? "'2026-11-23 09:12:00'" : "NULL") + ")",
                assignmentId, manager.getId());
    }

    /** 全部のデータを入れる（データの表）。 */
    private void givenAll() {
        period(-60, -32, "PUBLISHED");
        period(-31, -31, "PUBLISHED");
        currentId = period(-1, 10, "PUBLISHED");
        closedId = period(11, 25, "CLOSED");
        long open1 = period(26, 40, "OPEN");
        long open2 = period(41, 55, "OPEN");
        submit(open1, a);
        submit(open1, c);
        submit(open2, a);
        submit(open2, b);
        for (int d = -1; d <= 10; d++) {
            required(d, 1, 2);
            required(d, 2, 1);
        }
        for (int d = 11; d <= 25; d++) {
            required(d, 1, d <= 21 ? 1 : 0);
            required(d, 2, 0);
        }
        absent(assign(currentId, a, -1, 1, "08:00", "17:00", "ABSENT"), true);
        absent(assign(currentId, a, 2, 1, "08:00", "17:00", "ABSENT"), true);
        assign(currentId, b, 2, 1, "08:00", "17:00", "ASSIGNED");
        absent(assign(currentId, b, 3, null, "13:00", "22:00", "ABSENT"), false);
        absent(assign(currentId, b, 4, null, "07:00", "08:00", "ABSENT"), false);
    }

    private MvcResult open() throws Exception {
        LoginUser login = new LoginUser(manager, false);
        return mvc.perform(get("/manager/home").with(user(login))).andExpect(status().isOk()).andReturn();
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static DashboardView dashboard(MvcResult result) {
        return (DashboardView) result.getModelAndView().getModel().get("dashboard");
    }

    private String label(int day) {
        return DateTimeUtil.monthDay(today.plusDays(day));
    }

    private String periodLabel(int from, int to) {
        return DateTimeUtil.period(today.plusDays(from), today.plusDays(to));
    }

    // ---------------------------------------------------------------- 何もないとき

    @Test
    void データなし_I2101_I2102_I2103が出て表は空() throws Exception {
        MvcResult result = open();

        assertThat(html(result)).contains("受付中の期間はありません。", "人数が足りない枠はありません。",
                "今日以降の欠勤はありません。", "href=\"/manager/absences\"").doesNotContain("シフト作成へ");
        assertThat(dashboard(result).periods()).isEmpty();
    }

    // ---------------------------------------------------------------- 1 提出状況

    @Test
    void No1_No2_No3_受付中の2期間の提出状況_無効なバイトは数えない_全員提出() throws Exception {
        givenAll();

        MvcResult result = open();

        DashboardView d = dashboard(result);
        assertThat(d.submissionStatuses()).hasSize(2);
        assertThat(d.submissionStatuses().get(0).submitted()).isEqualTo(1);
        assertThat(d.submissionStatuses().get(0).total()).isEqualTo(2);
        assertThat(d.submissionStatuses().get(0).unsubmittedNames()).containsExactly("伊藤テスト");
        assertThat(d.submissionStatuses().get(1).unsubmittedNames()).isEmpty();
        assertThat(html(result)).contains(periodLabel(26, 40), periodLabel(41, 55), "提出 1人／2人",
                "提出 2人／2人", "未提出：伊藤テスト", "全員提出済み", "width: 50%", "width: 100%")
                .doesNotContain("上野無効テスト");
    }

    // ---------------------------------------------------------------- 2 人数不足

    @Test
    void No4_No5_作成中の人数不足11件_10件とほか1件で公開済みは出ない() throws Exception {
        givenAll();

        MvcResult result = open();

        DashboardView d = dashboard(result);
        assertThat(d.shortages()).hasSize(10);
        assertThat(d.moreShortages()).isEqualTo(1);
        assertThat(d.shortages()).extracting(ShortageView::periodId).containsOnly(closedId);
        assertThat(d.shortages().get(0).workDate()).isEqualTo(today.plusDays(11));
        assertThat(html(result)).contains(label(11) + " ☀日勤 0人／1人", "ほか1件",
                "href=\"/manager/periods/" + closedId + "\"");
    }

    // ---------------------------------------------------------------- 3 欠員

    @Test
    void No6_No8_No9_欠員_昨日は出ず時間指定は日勤で数え重ならなければ人数なし() throws Exception {
        givenAll();

        MvcResult result = open();

        DashboardView d = dashboard(result);
        assertThat(d.vacancies()).extracting(v -> v.workDate()).containsExactly(
                today.plusDays(2), today.plusDays(3), today.plusDays(4));
        assertThat(d.vacancies().get(2).hasCount()).isFalse();
        assertThat(html(result))
                .contains(label(2) + " ☀日勤 相田テスト（欠勤）", "通知済み 11/23 9:12", "今の人数 1人／2人")
                .contains(label(3) + " 時間指定 13:00〜22:00 伊藤テスト（欠勤）", "今の人数 0人／2人")
                .contains(label(4) + " 時間指定 7:00〜8:00 伊藤テスト（欠勤）")
                .doesNotContain(label(-1) + " ☀日勤", "今の人数 0人／0人");
    }

    // ---------------------------------------------------------------- 4 募集期間

    @Test
    void No7_募集期間_32日前に終わった期間は出ず31日前は出る() throws Exception {
        givenAll();

        MvcResult result = open();

        DashboardView d = dashboard(result);
        assertThat(d.periods()).extracting(r -> r.period().startDate()).containsExactly(
                today.minusDays(31), today.minusDays(1), today.plusDays(11), today.plusDays(26),
                today.plusDays(41));
        assertThat(html(result)).contains("公開済み", "作成中", "受付中", "1人／2人",
                "href=\"/manager/periods/" + currentId + "\"")
                .doesNotContain(periodLabel(-60, -32));
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void バイトで開く_バイトのホームへ戻す() throws Exception {
        LoginUser staff = new LoginUser(a, false);

        mvc.perform(get("/manager/home").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
    }

    @Test
    void ログインしていない_ログイン画面へ() throws Exception {
        mvc.perform(get("/manager/home")).andExpect(status().is3xxRedirection());
    }
}

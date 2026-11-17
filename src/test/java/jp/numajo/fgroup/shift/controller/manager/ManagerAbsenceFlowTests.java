package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.SkillRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 欠勤登録の流れの結合テスト（20-SM-07 7章 No.1〜13、94_セキュリティ設計書、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・AbsenceService・NotificationService を使い、画面（HTML）の描画も通す。
 * {@code @Transactional} は付けず、ログインID が「faabs」で始まるユーザー・職能と、2098年・2020年の期間を、
 * 各テストの前後で消す。枠の必要職能（slot_required_skills）は、テストの前に控えて空にし、後で戻す。
 * 分岐の細かい組み合わせは AbsenceServiceTests・ManagerAbsenceControllerTests で確かめる。
 * 20-SM-07 7章 No.14（欠勤後の SM-06 のセル「欠」・人数不足の警告）は、SM-06 ができたあとに SM-06 の側で確かめる。
 * </p>
 *
 * <pre>
 * データ（日付 D＝2098/3/5、公開済みの期間 3/1〜3/15。日勤の必要職能はレジ）
 * 割当：欠勤する人（日勤 8-17）、F（日勤 8-17）、G（夕勤 17-22）、H（日勤 8-17、すでに欠勤）
 * 有効なバイトで、欠勤する人を除く：
 *   A（レジ）、B（レジ・研修中）、G（レジ・夕勤に出勤）、H（レジ・欠勤の割当）、D（レジ・ふりがななし）
 *   F（レジ・日勤に出勤）、E（レジ・無効）、I（職能なし）、M（レジ・店長）
 * 相手になるのは A・B・G・H・D の5人（並びはふりがな順、なしは最後）。F は時間が重なる、E は無効、I は職能なし、M は店長。
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerAbsenceFlowTests {

    private static final LocalDate DAY = LocalDate.of(2098, 3, 5);
    private static final LocalDate TIME_DAY = LocalDate.of(2098, 3, 6);
    private static final LocalDate CLOSED_DAY = LocalDate.of(2098, 3, 20);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 1, 10);
    private static final String NOTICE = "入れる方は店舗に連絡してください（テスト）";
    private static final String TARGET_NAME = "欠勤する人テスト";
    private static final String DAY_NAME = DateTimeUtil.monthDay(DAY);

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private MockMvc mvc;
    private List<Map<String, Object>> savedSlotSkills;
    private Skill registerSkill;
    private Skill otherSkill;
    private User manager;
    private User target;
    private long publishedPeriodId;
    private long closedPeriodId;
    private long mainAssignmentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        savedSlotSkills = jdbcTemplate.queryForList("SELECT slot_id, skill_id FROM slot_required_skills");
        jdbcTemplate.update("DELETE FROM slot_required_skills");

        registerSkill = skillRepository.save(skill("faabsレジ"));
        otherSkill = skillRepository.save(skill("faabs品出し"));
        jdbcTemplate.update("INSERT INTO slot_required_skills (slot_id, skill_id) VALUES (1, ?)",
                registerSkill.getId());

        manager = save("faabsmgr", "店長テスト", "てんちょう", Role.MANAGER, true, false);
        target = save("faabs01", TARGET_NAME, "けっきん", Role.STAFF, true, false);
        User f = save("faabsf", "重なる人テスト", "えお", Role.STAFF, true, false, registerSkill);
        User g = save("faabsg", "夕勤の人テスト", "うえだ", Role.STAFF, true, false, registerSkill);
        User h = save("faabsh", "欠勤済みの人テスト", "えとう", Role.STAFF, true, false, registerSkill);
        save("faabsa", "相手Aテスト", "あきら", Role.STAFF, true, false, registerSkill);
        save("faabsb", "相手Bテスト", "いちろう", Role.STAFF, true, true, registerSkill);
        save("faabsd", "ふりがななしテスト", null, Role.STAFF, true, false, registerSkill);
        save("faabse", "無効な人テスト", "あああ", Role.STAFF, false, false, registerSkill);
        save("faabsi", "職能なしテスト", "あいう", Role.STAFF, true, false);
        save("faabsm", "店長2テスト", "ああい", Role.MANAGER, true, false, registerSkill);

        publishedPeriodId = savePeriod(LocalDate.of(2098, 3, 1), LocalDate.of(2098, 3, 15), "PUBLISHED");
        closedPeriodId = savePeriod(LocalDate.of(2098, 3, 16), LocalDate.of(2098, 3, 31), "CLOSED");
        long pastPeriodId = savePeriod(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 15), "PUBLISHED");

        mainAssignmentId = saveAssignment(publishedPeriodId, target, DAY, 1, "08:00", "17:00", "ASSIGNED");
        saveAssignment(publishedPeriodId, f, DAY, 1, "08:00", "17:00", "ASSIGNED");
        saveAssignment(publishedPeriodId, g, DAY, 2, "17:00", "22:00", "ASSIGNED");
        long absentId = saveAssignment(publishedPeriodId, h, DAY, 1, "08:00", "17:00", "ABSENT");
        jdbcTemplate.update("INSERT INTO absences (assignment_id, registered_by, reason, notice_message, notified_at) "
                + "VALUES (?, ?, '体調不良', '前の通知', NOW())", absentId, manager.getId());
        saveAssignment(closedPeriodId, target, CLOSED_DAY, 1, "08:00", "17:00", "ASSIGNED");
        saveAssignment(pastPeriodId, target, PAST_DAY, 1, "08:00", "17:00", "ASSIGNED");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        jdbcTemplate.update("DELETE FROM slot_required_skills");
        for (Map<String, Object> row : savedSlotSkills) {
            jdbcTemplate.update("INSERT INTO slot_required_skills (slot_id, skill_id) VALUES (?, ?)",
                    row.get("slot_id"), row.get("skill_id"));
        }
    }

    // ---------------------------------------------------------------- 準備・後片付け

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs WHERE user_id IN "
                + "(SELECT id FROM (SELECT id FROM users WHERE login_id LIKE 'faabs%') t)");
        // 割当は期間を消すと消え、欠勤（absences）も割当と一緒に消える
        jdbcTemplate.update("DELETE FROM shift_periods WHERE start_date IN ('2098-03-01', '2098-03-16', '2020-01-01')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'faabs%'");
        jdbcTemplate.update("DELETE FROM skills WHERE name LIKE 'faabs%'");
    }

    private static Skill skill(String name) {
        Skill s = new Skill();
        s.setName(name);
        return s;
    }

    private User save(String loginId, String name, String kana, Role role, boolean active, boolean trainee,
            Skill... skills) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("dummy");
        u.setName(name);
        u.setNameKana(kana);
        u.setRole(role);
        u.setActive(active);
        u.setTrainee(trainee);
        u.setMustChangePassword(false);
        Set<Skill> set = new HashSet<>(List.of(skills));
        u.setSkills(set);
        return userRepository.save(u);
    }

    private long savePeriod(LocalDate start, LocalDate end, String status) {
        jdbcTemplate.update("INSERT INTO shift_periods (start_date, end_date, deadline_at, status) "
                + "VALUES (?, ?, ?, ?)", start, end, start.minusDays(14).atTime(23, 59), status);
        return jdbcTemplate.queryForObject("SELECT id FROM shift_periods WHERE start_date = ?", Long.class, start);
    }

    private long saveAssignment(long periodId, User user, LocalDate date, Integer slotId, String start, String end,
            String status) {
        jdbcTemplate.update("INSERT INTO shift_assignments (period_id, user_id, work_date, slot_id, start_time, "
                + "end_time, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                periodId, user.getId(), date, slotId, start, end, status);
        return jdbcTemplate.queryForObject("SELECT id FROM shift_assignments WHERE user_id = ? AND work_date = ? "
                + "AND start_time = ?", Long.class, user.getId(), date, start);
    }

    private LoginUser login(User u) {
        return new LoginUser(userRepository.findByLoginId(u.getLoginId()).orElseThrow(), false);
    }

    private String html(String url, String... params) throws Exception {
        var request = get(url).with(user(login(manager)));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private MvcResult register(long assignmentId, String reason, String message) throws Exception {
        return mvc.perform(post("/manager/absences").with(csrf()).with(user(login(manager)))
                .param("assignmentId", String.valueOf(assignmentId))
                .param("reason", reason).param("noticeMessage", message))
                .andReturn();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String statusOf(long assignmentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM shift_assignments WHERE id = ?", String.class,
                assignmentId);
    }

    private List<String> recipientNames(String type) {
        return jdbcTemplate.queryForList("SELECT u.name FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE n.type = ? AND u.login_id LIKE 'faabs%' ORDER BY u.name", String.class, type);
    }

    private long assignmentIdOf(LocalDate date, User user) {
        return jdbcTemplate.queryForObject("SELECT id FROM shift_assignments WHERE user_id = ? AND work_date = ?",
                Long.class, user.getId(), date);
    }

    // ---------------------------------------------------------------- ① 日付と人を選ぶ（No.1）

    @Test
    void 公開済みの期間の日を開く_出勤者とすでに欠勤の人が出る() throws Exception {
        String page = html("/manager/absences", "date", "2098-03-05");

        assertThat(page).contains("value=\"2098-03-05\"").contains("data-auto-submit")
                .containsPattern("min=\"\\d{4}-\\d{2}-\\d{2}\"")
                .contains(DAY_NAME + " の出勤者")
                .contains(TARGET_NAME).contains("重なる人テスト").contains("夕勤の人テスト")
                .contains("☀日勤").contains("☾夕勤").contains("この人を欠勤にする")
                .contains("href=\"/manager/absences?assignmentId=" + mainAssignmentId + "\"")
                .contains("すでに欠勤の人").contains("欠勤済みの人テスト")
                .containsPattern("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}")
                .doesNotContain("この日に公開済みのシフトはありません。");
    }

    @Test
    void 作成中の期間の日を開く_割当は出ずI2701が出る() throws Exception {
        String page = html("/manager/absences", "date", "2098-03-20");

        assertThat(page).contains("この日に公開済みのシフトはありません。").doesNotContain(TARGET_NAME)
                .doesNotContain("すでに欠勤の人");
    }

    @Test
    void 昨日の日付を送る_E2702で今日を表示する() throws Exception {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        String page = html("/manager/absences", "date", yesterday.toString());

        assertThat(page).contains("今日より前の日付は選べません。").contains("value=\"" + LocalDate.now(clock) + "\"");
    }

    @Test
    void パラメーターなし_今日を表示する() throws Exception {
        String page = html("/manager/absences");

        assertThat(page).contains("value=\"" + LocalDate.now(clock) + "\"").doesNotContain("今日より前の日付は選べません。");
    }

    // ---------------------------------------------------------------- ② 内容を確かめる（No.3〜10）

    @Test
    void 日勤の人を選ぶ_確認の画面に表示と既定の通知文と相手が出る() throws Exception {
        String page = html("/manager/absences", "assignmentId", String.valueOf(mainAssignmentId));

        assertThat(page).contains(DAY_NAME + " ☀日勤 8:00〜17:00　" + TARGET_NAME)
                .contains(DAY.getMonthValue() + "月" + DAY.getDayOfMonth()
                        + "日の日勤に1人欠員が出ました。入れる方は店舗に連絡してください。")
                .contains("name=\"assignmentId\"").contains("value=\"" + mainAssignmentId + "\"")
                .contains("欠勤を登録し、5人にお知らせします。登録は取り消せません。よろしいですか？")
                .contains("登録して通知する").contains("戻る")
                .contains("href=\"/manager/absences?date=2098-03-05\"")
                .contains("研修中")
                .doesNotContain("無効な人テスト").doesNotContain("重なる人テスト")
                .doesNotContain("職能なしテスト").doesNotContain("店長2テスト");
        assertThat(page.indexOf("相手Aテスト")).isPositive()
                .isLessThan(page.indexOf("相手Bテスト"))
                .isLessThan(page.indexOf("夕勤の人テスト"));
        assertThat(page.indexOf("相手Bテスト")).isLessThan(page.indexOf("夕勤の人テスト"));
        assertThat(page.indexOf("夕勤の人テスト")).isLessThan(page.indexOf("欠勤済みの人テスト"));
        assertThat(page.indexOf("欠勤済みの人テスト")).isLessThan(page.indexOf("ふりがななしテスト"));
    }

    @Test
    void 時間指定13時から22時を選ぶ_日勤と夕勤の必要職能を持つ人がいなければ通知できる人がいない() throws Exception {
        jdbcTemplate.update("INSERT INTO slot_required_skills (slot_id, skill_id) VALUES (2, ?)", otherSkill.getId());
        long timeId = saveAssignment(publishedPeriodId, target, TIME_DAY, null, "13:00", "22:00", "ASSIGNED");

        String page = html("/manager/absences", "assignmentId", String.valueOf(timeId));

        assertThat(page).contains(DateTimeUtil.monthDay(TIME_DAY) + " 時間指定 13:00〜22:00　" + TARGET_NAME)
                .contains("通知できる人がいません。")
                .contains("の13:00〜22:00に1人欠員が出ました。")
                .contains("欠勤を登録し、0人にお知らせします。");
    }

    // ---------------------------------------------------------------- 登録（No.3〜8、10、11）

    @Test
    void 日勤の人を欠勤にする_欠勤と通知と操作ログがDBに残る() throws Exception {
        MvcResult result = register(mainAssignmentId, "体調不良", NOTICE);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/manager/periods/" + publishedPeriodId);
        assertThat((Map<String, Object>) result.getFlashMap()).containsEntry("successMessage", "I2703")
                .doesNotContainKey("warningMessage");
        assertThat((Object[]) result.getFlashMap().get("successArgs")).containsExactly(5);

        assertThat(statusOf(mainAssignmentId)).isEqualTo("ABSENT");
        Map<String, Object> absence = jdbcTemplate.queryForMap(
                "SELECT reason, notice_message, registered_by, notified_at FROM absences WHERE assignment_id = ?",
                mainAssignmentId);
        assertThat(absence).containsEntry("reason", "体調不良").containsEntry("notice_message", NOTICE)
                .containsEntry("registered_by", manager.getId());
        assertThat(absence.get("notified_at")).isNotNull();

        assertThat(recipientNames("VACANCY")).containsExactlyInAnyOrder("相手Aテスト", "相手Bテスト", "夕勤の人テスト",
                "欠勤済みの人テスト", "ふりがななしテスト");
        Map<String, Object> vacancy = jdbcTemplate.queryForMap(
                "SELECT n.title, n.body, n.link_url FROM notifications n JOIN users u ON u.id = n.user_id "
                        + "WHERE n.type = 'VACANCY' AND u.login_id = 'faabsa'");
        assertThat(vacancy).containsEntry("title", "欠員が出ました（" + DAY_NAME + " 日勤）")
                .containsEntry("body", NOTICE).containsEntry("link_url", "/staff/home");

        assertThat(recipientNames("CHANGED")).containsExactly(TARGET_NAME);
        Map<String, Object> changed = jdbcTemplate.queryForMap(
                "SELECT n.title, n.body, n.link_url FROM notifications n JOIN users u ON u.id = n.user_id "
                        + "WHERE n.type = 'CHANGED' AND u.login_id = 'faabs01'");
        assertThat(changed).containsEntry("title", "シフトが変更されました")
                .containsEntry("body", DAY_NAME + " 日勤 が欠勤になりました。")
                .containsEntry("link_url", "/staff/shifts?periodId=" + publishedPeriodId);

        Map<String, Object> log = jdbcTemplate.queryForMap("SELECT target, detail FROM operation_logs "
                + "WHERE action = 'ABSENCE_REGISTER' AND user_id = ?", manager.getId());
        assertThat(log).containsEntry("target", "assignment:" + mainAssignmentId)
                .containsEntry("detail", "user=" + target.getId() + " date=2098-03-05 slot=DAY recipients=5");
    }

    @Test
    void 欠勤にしたあと同じ日を開く_欠勤にした人がすでに欠勤の表に移り通知日時が出る() throws Exception {
        register(mainAssignmentId, "", NOTICE);

        String page = html("/manager/absences", "date", "2098-03-05");

        assertThat(page).doesNotContain("href=\"/manager/absences?assignmentId=" + mainAssignmentId + "\"");
        assertThat(page.indexOf("すでに欠勤の人")).isLessThan(page.indexOf(TARGET_NAME));
        assertThat(count("SELECT COUNT(*) FROM absences WHERE assignment_id = ? AND reason IS NULL",
                mainAssignmentId)).isEqualTo(1);
    }

    @Test
    void 時間指定の人を欠勤にする_誰にも通知されず登録はでき警告が出る() throws Exception {
        jdbcTemplate.update("INSERT INTO slot_required_skills (slot_id, skill_id) VALUES (2, ?)", otherSkill.getId());
        long timeId = saveAssignment(publishedPeriodId, target, TIME_DAY, null, "13:00", "22:00", "ASSIGNED");

        MvcResult result = register(timeId, "私用", NOTICE);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/manager/periods/" + publishedPeriodId);
        assertThat((Map<String, Object>) result.getFlashMap()).containsEntry("successMessage", "I2703")
                .containsEntry("warningMessage", "W2701");
        assertThat((Object[]) result.getFlashMap().get("successArgs")).containsExactly(0);
        assertThat(statusOf(timeId)).isEqualTo("ABSENT");
        assertThat(recipientNames("VACANCY")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE n.type = 'CHANGED' AND u.login_id = 'faabs01'", String.class))
                .isEqualTo(DateTimeUtil.monthDay(TIME_DAY) + " 時間指定 13:00〜22:00 が欠勤になりました。");
        assertThat(jdbcTemplate.queryForObject("SELECT detail FROM operation_logs "
                + "WHERE action = 'ABSENCE_REGISTER' AND user_id = ?", String.class, manager.getId()))
                .isEqualTo("user=" + target.getId() + " date=2098-03-06 slot=TIME recipients=0");
    }

    @Test
    void 同じ割当をもう一度登録する_E2707で通知は増えない() throws Exception {
        register(mainAssignmentId, "", NOTICE);
        int vacancies = count("SELECT COUNT(*) FROM notifications WHERE type = 'VACANCY'");

        MvcResult second = register(mainAssignmentId, "", NOTICE);

        assertThat(second.getResponse().getRedirectedUrl()).isEqualTo("/manager/absences?date=2098-03-05");
        assertThat((Map<String, Object>) second.getFlashMap()).containsEntry("errorMessage", "E2707");
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE type = 'VACANCY'")).isEqualTo(vacancies);
        assertThat(count("SELECT COUNT(*) FROM absences WHERE assignment_id = ?", mainAssignmentId)).isEqualTo(1);
    }

    @Test
    void 作成中の期間の割当IDを送る_E2706で何も変わらない() throws Exception {
        long closedAssignmentId = assignmentIdOf(CLOSED_DAY, target);

        MvcResult result = register(closedAssignmentId, "", NOTICE);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/manager/absences?date=2098-03-20");
        assertThat((Map<String, Object>) result.getFlashMap()).containsEntry("errorMessage", "E2706");
        assertThat(statusOf(closedAssignmentId)).isEqualTo("ASSIGNED");
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE type IN ('VACANCY', 'CHANGED') "
                + "AND user_id IN (SELECT id FROM users WHERE login_id LIKE 'faabs%')")).isZero();
    }

    @Test
    void 過去の日付の割当を送る_E2702で何も変わらない() throws Exception {
        long pastAssignmentId = assignmentIdOf(PAST_DAY, target);

        MvcResult result = register(pastAssignmentId, "", NOTICE);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/manager/absences?date=2020-01-10");
        assertThat((Map<String, Object>) result.getFlashMap()).containsEntry("errorMessage", "E2702");
        assertThat(statusOf(pastAssignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void 過去の日付の割当の確認の画面を開く_E2702で日付の画面へ戻る() throws Exception {
        long pastAssignmentId = assignmentIdOf(PAST_DAY, target);

        mvc.perform(get("/manager/absences").with(user(login(manager)))
                .param("assignmentId", String.valueOf(pastAssignmentId)))
                .andExpect(redirectedUrl("/manager/absences?date=2020-01-10"))
                .andExpect(flash().attribute("errorMessage", "E2702"));
    }

    // ---------------------------------------------------------------- 入力チェック（No.13）

    @Test
    void 通知する文が空_E2704で確認の画面を再表示し何も変わらない() throws Exception {
        MvcResult result = register(mainAssignmentId, "体調不良", "");
        String page = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(page).contains("通知する文を入力してください。").contains(TARGET_NAME).contains("相手Aテスト")
                .contains("value=\"体調不良\"");
        assertThat(statusOf(mainAssignmentId)).isEqualTo("ASSIGNED");
        assertThat(count("SELECT COUNT(*) FROM absences WHERE assignment_id = ?", mainAssignmentId)).isZero();
    }

    @Test
    void 通知する文が501文字_E2705で再表示し送った文のまま出す() throws Exception {
        String message = "あ".repeat(501);

        MvcResult result = register(mainAssignmentId, "", message);
        String page = result.getResponse().getContentAsString();

        assertThat(page).contains("通知する文は500文字以内で入力してください。").contains(message);
        assertThat(statusOf(mainAssignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void 理由が201文字_E2703で再表示する() throws Exception {
        MvcResult result = register(mainAssignmentId, "あ".repeat(201), NOTICE);

        assertThat(result.getResponse().getContentAsString()).contains("理由は200文字以内で入力してください。");
        assertThat(statusOf(mainAssignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void 割当IDがない_E2701で日付と人を選ぶ画面へ戻る() throws Exception {
        mvc.perform(post("/manager/absences").with(csrf()).with(user(login(manager)))
                .param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/absences"))
                .andExpect(flash().attribute("errorMessage", "E2701"));
    }

    // ---------------------------------------------------------------- 404・権限

    @Test
    void 存在しない割当ID_404になる() throws Exception {
        mvc.perform(get("/manager/absences").with(user(login(manager))).param("assignmentId", "999999999"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/manager/absences").with(csrf()).with(user(login(manager)))
                .param("assignmentId", "999999999").param("noticeMessage", NOTICE))
                .andExpect(status().isNotFound());
    }

    @Test
    void 割当IDが数字でない_404になる() throws Exception {
        mvc.perform(get("/manager/absences").with(user(login(manager))).param("assignmentId", "abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void バイトが開く_自分のホームへ戻される() throws Exception {
        LoginUser staff = login(userRepository.findByLoginId("faabsa").orElseThrow());

        mvc.perform(get("/manager/absences").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post("/manager/absences").with(csrf()).with(user(staff))
                .param("assignmentId", String.valueOf(mainAssignmentId)).param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/staff/home"));
        assertThat(statusOf(mainAssignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void ログインしていない_ログイン画面へ送る() throws Exception {
        mvc.perform(get("/manager/absences")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}

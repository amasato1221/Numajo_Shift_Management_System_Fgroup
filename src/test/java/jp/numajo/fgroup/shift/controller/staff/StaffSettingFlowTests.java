package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.form.StaffForm;
import jp.numajo.fgroup.shift.repository.CalendarFeedRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.StaffService;
import lombok.RequiredArgsConstructor;

/**
 * 設定（SB-05）の流れの結合テスト（20-SB-05 6章 No.1〜7、94_セキュリティ設計書、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service を使い、画面（HTML）の描画も通す。
 * {@code @Transactional} は付けず、ログインID が「sb5」で始まるデータを各テストの前後で消す。
 * バイトは StaffService#create で登録し、登録直後の状態（前日・3日前、購読の行あり）から始める。
 * </p>
 *
 * <p>
 * 6章 No.6 の「古いURLは404、新しいURLは200」は IC-01（/ical/{token}.ics）ができてから確かめる。
 * ここでは「トークンが変わり、古いトークンでは探せず、新しいトークンで探せる」までを確かめる。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class StaffSettingFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final CalendarFeedRepository calendarFeedRepository;
    private final StaffService staffService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private Long staffId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        User manager = new User();
        manager.setLoginId("sb5manager");
        manager.setPasswordHash("dummy");
        manager.setName("テスト店長");
        manager.setRole(Role.MANAGER);
        manager.setActive(true);
        manager.setMustChangePassword(false);
        userRepository.save(manager);

        StaffForm form = new StaffForm();
        form.setName("テストバイト");
        form.setNameKana("てすとばいと");
        form.setLoginId("sb5staff");
        form.setSkillIds(List.of());
        form.setActive(true);
        staffId = staffService.create(form, manager.getId()).userId();
        jdbcTemplate.update("UPDATE users SET must_change_password = FALSE WHERE id = ?", staffId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs WHERE user_id IN "
                + "(SELECT id FROM (SELECT id FROM users WHERE login_id LIKE 'sb5%') t)");
        // notification_settings・calendar_feeds は users の ON DELETE CASCADE で消える
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'sb5%'");
    }

    private LoginUser login(String loginId) {
        return new LoginUser(userRepository.findByLoginId(loginId).orElseThrow(), false);
    }

    private List<Integer> days() {
        return jdbcTemplate.queryForList(
                "SELECT days_before FROM notification_settings WHERE user_id = ? ORDER BY days_before",
                Integer.class, staffId);
    }

    private String token() {
        return jdbcTemplate.queryForObject("SELECT token FROM calendar_feeds WHERE user_id = ?", String.class,
                staffId);
    }

    private String body() throws Exception {
        return mvc.perform(get("/staff/settings").with(user(login("sb5staff"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- No.1 初期状態

    @Test
    void No1_登録直後に画面を開く_前日と3日前にチェックがある() throws Exception {
        String html = body();

        assertThat(html).containsPattern("value=\"1\"[^>]*checked")
                .containsPattern("value=\"3\"[^>]*checked")
                .doesNotContainPattern("value=\"0\"[^>]*checked")
                .doesNotContainPattern("value=\"7\"[^>]*checked")
                .contains("当日").contains("前日").contains("3日前").contains("7日前")
                .contains("希望の提出期限が近づいたら、お知らせで知らせます。未提出の期間があるときだけ届きます。")
                .contains("data-copy-target=\"feedUrl\"")
                .contains("data-confirm=\"URLを再発行すると、今のURLは使えなくなります。よろしいですか？\"")
                .contains("<details>").contains("TimeTree");
    }

    @Test
    void 購読の行がないとき画面を開く_行を作ってURLを出す() throws Exception {
        jdbcTemplate.update("DELETE FROM calendar_feeds WHERE user_id = ?", staffId);

        String html = body();

        assertThat(html).contains("/ical/" + token() + ".ics");
    }

    // ---------------------------------------------------------------- No.2 全部外す

    @Test
    void No2_全部外して保存する_行が0件でI1501とW1501が出る() throws Exception {
        LoginUser staff = login("sb5staff");
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/staff/settings/notifications").with(csrf()).with(user(staff)).session(session)
                .param("_daysBefore", "on"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1501"))
                .andExpect(flash().attribute("warningMessage", "W1501"));

        assertThat(days()).isEmpty();
        String html = mvc.perform(get("/staff/settings").with(user(staff))
                .flashAttr("successMessage", "I1501").flashAttr("warningMessage", "W1501"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("締切通知の設定を保存しました。").contains("締切通知は届きません。")
                .doesNotContainPattern("name=\"daysBefore\"[^>]*checked");
    }

    // ---------------------------------------------------------------- No.3 当日・7日前

    @Test
    void No3_当日と7日前を保存する_0と7の2行になる() throws Exception {
        mvc.perform(post("/staff/settings/notifications").with(csrf()).with(user(login("sb5staff")))
                .param("daysBefore", "0", "7").param("_daysBefore", "on"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1501"));

        assertThat(days()).containsExactly(0, 7);
        assertThat(body()).containsPattern("value=\"0\"[^>]*checked").containsPattern("value=\"7\"[^>]*checked")
                .doesNotContainPattern("value=\"1\"[^>]*checked");
    }

    // ---------------------------------------------------------------- No.4 改ざん

    @Test
    void No4_値2を送る_E1501でDBは変わらない() throws Exception {
        mvc.perform(post("/staff/settings/notifications").with(csrf()).with(user(login("sb5staff")))
                .param("daysBefore", "0", "2"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("errorMessage", "E1501"));

        assertThat(days()).containsExactly(1, 3);
        String html = mvc.perform(get("/staff/settings").with(user(login("sb5staff")))
                .flashAttr("errorMessage", "E1501"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("締切通知のタイミングが正しくありません。");
    }

    // ---------------------------------------------------------------- No.5 重複

    @Test
    void No5_同じ値を2回送る_1行だけになる() throws Exception {
        mvc.perform(post("/staff/settings/notifications").with(csrf()).with(user(login("sb5staff")))
                .param("daysBefore", "3", "3"))
                .andExpect(redirectedUrl("/staff/settings"));

        assertThat(days()).containsExactly(3);
    }

    // ---------------------------------------------------------------- No.6 再発行

    @Test
    void No6_再発行する_トークンが変わり古いトークンでは探せない() throws Exception {
        String before = token();

        mvc.perform(post("/staff/settings/calendar/reissue").with(csrf()).with(user(login("sb5staff"))))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1502"));

        String after = token();
        assertThat(after).isNotEqualTo(before).hasSize(43);
        assertThat(calendarFeedRepository.findByToken(before)).isEmpty();
        assertThat(calendarFeedRepository.findByToken(after)).isPresent();
        assertThat(body()).contains("/ical/" + after + ".ics").doesNotContain(before);
    }

    // ---------------------------------------------------------------- No.7 購読URLの形

    @Test
    void No7_購読URL_base_urlから始まりトークンは43文字() throws Exception {
        String token = token();
        String html = body();

        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(html).contains("value=\"" + appProperties.getBaseUrl() + "/ical/" + token + ".ics\"");
        String webcal = "webcal://" + appProperties.getBaseUrl().replaceFirst("^https?://", "") + "/ical/" + token
                + ".ics";
        assertThat(html).contains("href=\"" + webcal + "\"");
    }

    // ---------------------------------------------------------------- 権限・CSRF

    @Test
    void 店長が画面を開く_自分のホームへ戻される() throws Exception {
        mvc.perform(get("/staff/settings").with(user(login("sb5manager"))))
                .andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void CSRFトークンなしで保存する_自分のホームへ戻りDBは変わらない() throws Exception {
        LoginUser staff = login("sb5staff");
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/staff/settings").with(user(staff)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/staff/settings/notifications").with(user(staff)).session(session)
                .param("daysBefore", "0"))
                .andExpect(redirectedUrl("/staff/home"));
        String before = token();
        mvc.perform(post("/staff/settings/calendar/reissue").with(user(staff)).session(session))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(days()).containsExactly(1, 3);
        assertThat(token()).isEqualTo(before);
    }
}

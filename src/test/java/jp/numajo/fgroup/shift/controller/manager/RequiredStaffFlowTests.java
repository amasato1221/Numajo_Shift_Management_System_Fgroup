package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.RequiredStaffService;
import lombok.RequiredArgsConstructor;

/**
 * 必要人数設定の流れの結合テスト（20-SM-05 6章 No.2〜5、94_セキュリティ設計書 6章 No.9、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・RequiredStaffService を使い、画面（HTML）の描画、DB の値、
 * 操作ログ、権限、CSRF を確かめる。{@code @Transactional} は付けず、各テストの前後で
 * 既定値を0人に戻し、2099年の上書きと、ログインID が「rq」で始まるデータを消す。
 * 2099-03-01 は日曜日、2099-03-03 は火曜日。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class RequiredStaffFlowTests {

    private static final LocalDate SUN = LocalDate.of(2099, 3, 1);
    private static final LocalDate TUE = LocalDate.of(2099, 3, 3);

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final RequiredStaffService requiredStaffService;

    private MockMvc mvc;
    private LoginUser manager;
    private LoginUser staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        manager = new LoginUser(save("rqmanager", Role.MANAGER), false);
        staff = new LoginUser(save("rqstaff", Role.STAFF), false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private User save(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(passwordEncoder.encode("Passw0rd!x"));
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void cleanUp() {
        jdbcTemplate.update("UPDATE required_staff_defaults SET required_count = 0");
        jdbcTemplate.update("DELETE FROM required_staff_overrides WHERE work_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM operation_logs WHERE user_id IN "
                + "(SELECT id FROM users WHERE login_id LIKE 'rq%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'rq%'");
    }

    private int defaultCount(int dayOfWeek, int slotId) {
        return jdbcTemplate.queryForObject("SELECT required_count FROM required_staff_defaults "
                + "WHERE day_of_week = ? AND slot_id = ?", Integer.class, dayOfWeek, slotId);
    }

    private long overrideRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM required_staff_overrides WHERE work_date >= '2099-01-01'", Long.class);
    }

    private long logs(String detail) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_logs WHERE action = 'REQUIRED_UPDATE' "
                + "AND detail = ? AND user_id = ?", Long.class, detail, manager.getUserId());
    }

    /** 既定値の保存。日勤は曜日＋1人、夕勤は日曜だけ 4人・ほかは 0人。 */
    private MockHttpServletRequestBuilder saveDefaults() {
        return saveDefaults("3");
    }

    /** 既定値の保存。火曜（添字2）の日勤だけ値を指定する。 */
    private MockHttpServletRequestBuilder saveDefaults(String tuesdayDay) {
        MockHttpServletRequestBuilder request = post("/manager/required/defaults").with(user(manager));
        for (int i = 0; i < 7; i++) {
            request.param("dayCounts[" + i + "]", i == 2 ? tuesdayDay : String.valueOf(i + 1));
            request.param("eveningCounts[" + i + "]", i == 0 ? "4" : "0");
        }
        return request;
    }

    // ---------------------------------------------------------------- 画面の描画

    @Test
    void 画面を開く_既定値と月の一覧と上書きの印が描かれる() throws Exception {
        mvc.perform(saveDefaults().with(csrf())).andExpect(redirectedUrl("/manager/required"));
        mvc.perform(post("/manager/required/overrides").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "2").param("requiredCount", "9")
                .param("ym", "2099-03"))
                .andExpect(redirectedUrl("/manager/required?ym=2099-03"));

        String html = mvc.perform(get("/manager/required").param("ym", "2099-03").with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>必要人数設定｜シフト管理システム</title>");
        assertThat(html).contains("2099年3月");
        assertThat(html).contains("name=\"dayCounts[6]\"").contains("value=\"7\"");
        assertThat(html).contains("3月3日(火)").contains("9人").contains("上書き").contains("戻す");
        assertThat(html).contains("/manager/required?ym=2099-02").contains("/manager/required?ym=2099-04");
        assertThat(html).contains("name=\"_csrf\"");
    }

    @Test
    void 既定値が範囲の外_再表示でメッセージが項目の下に出る() throws Exception {
        String html = mvc.perform(saveDefaults("21").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("必要人数は0〜20の数字で入力してください。");
        assertThat(defaultCount(2, SlotIds.DAY)).isZero();
    }

    // ---------------------------------------------------------------- 6章 No.2〜5

    @Test
    void 既定値と上書きを保存して戻す_必要人数が上書き既定値の順で決まる() throws Exception {
        mvc.perform(saveDefaults().with(csrf())).andExpect(redirectedUrl("/manager/required"));
        assertThat(defaultCount(0, SlotIds.DAY)).isEqualTo(1);
        assertThat(defaultCount(6, SlotIds.DAY)).isEqualTo(7);
        assertThat(logs("defaults")).isEqualTo(1);
        // No.5：日曜日は day_of_week＝0 の値
        assertThat(requiredStaffService.requiredCount(SUN, SlotIds.DAY)).isEqualTo(1);
        assertThat(requiredStaffService.requiredCount(SUN, SlotIds.EVENING)).isEqualTo(4);

        // No.2・3：上書きを0人にする → 0（既定値 3 は使わない）
        mvc.perform(post("/manager/required/overrides").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "1").param("requiredCount", "0"))
                .andExpect(redirectedUrl("/manager/required?ym=2099-03"));
        assertThat(requiredStaffService.requiredCount(TUE, SlotIds.DAY)).isZero();
        assertThat(logs("override 2099-03-03 DAY=0")).isEqualTo(1);

        // 同じ日付・枠をもう一度保存すると、行は増えずに値が変わる（UPSERT）
        mvc.perform(post("/manager/required/overrides").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "1").param("requiredCount", "8"));
        assertThat(overrideRows()).isEqualTo(1);
        assertThat(requiredStaffService.requiredCounts(TUE, TUE).get(TUE)).containsEntry(SlotIds.DAY, 8);

        // No.4：戻す → 曜日の既定値（火曜＝3）
        mvc.perform(post("/manager/required/overrides/delete").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "1").param("ym", "2099-03"))
                .andExpect(redirectedUrl("/manager/required?ym=2099-03"));
        assertThat(overrideRows()).isZero();
        assertThat(requiredStaffService.requiredCount(TUE, SlotIds.DAY)).isEqualTo(3);
        assertThat(logs("override delete 2099-03-03 DAY")).isEqualTo(1);

        // 行がないのにもう一度戻す → 何も起きず、操作ログも増えない
        mvc.perform(post("/manager/required/overrides/delete").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "1").param("ym", "2099-03"))
                .andExpect(redirectedUrl("/manager/required?ym=2099-03"));
        assertThat(logs("override delete 2099-03-03 DAY")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 6章 No.6

    @Test
    void 枠3を送る_E2504で保存しない() throws Exception {
        mvc.perform(post("/manager/required/overrides").with(csrf()).with(user(manager))
                .param("workDate", "2099-03-03").param("slotId", "3").param("requiredCount", "2")
                .param("ym", "2099-03"))
                .andExpect(redirectedUrl("/manager/required?ym=2099-03"))
                .andExpect(result -> assertThat(result.getFlashMap().get("errorMessage")).isEqualTo("E2504"));

        assertThat(overrideRows()).isZero();
    }

    // ---------------------------------------------------------------- 権限・CSRF

    @Test
    void バイトが開く_自分のホームへ戻される() throws Exception {
        mvc.perform(get("/manager/required").with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));
        mvc.perform(saveDefaults().with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(defaultCount(0, SlotIds.DAY)).isZero();
    }

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/manager/required").with(user(manager)).session(session))
                .andExpect(status().isOk());

        mvc.perform(saveDefaults().session(session)).andExpect(redirectedUrl("/manager/home"));
        mvc.perform(post("/manager/required/overrides").with(user(manager)).session(session)
                .param("workDate", "2099-03-03").param("slotId", "1").param("requiredCount", "2"))
                .andExpect(redirectedUrl("/manager/home"));

        assertThat(defaultCount(0, SlotIds.DAY)).isZero();
        assertThat(overrideRows()).isZero();
    }
}

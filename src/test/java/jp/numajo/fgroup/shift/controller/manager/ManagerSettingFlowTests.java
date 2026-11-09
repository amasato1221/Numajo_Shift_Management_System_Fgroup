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

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 店舗設定の流れの結合テスト（20-SM-09 6章 No.1・3・6、94_セキュリティ設計書、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・StoreSettingService を使い、画面（HTML）の描画も通す。
 * {@code @Transactional} は付けず、ログインID が「fs9」で始まるデータと店舗設定を、各テストの前後で元に戻す。
 * 入力チェックの細かい組み合わせは、ManagerSettingControllerTests で確かめる。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerSettingFlowTests {

    private static final String ORIGINAL = "テスト元の店";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private String savedName;
    private Boolean savedShow;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        savedName = jdbcTemplate.queryForObject("SELECT store_name FROM store_settings WHERE id = 1", String.class);
        savedShow = jdbcTemplate.queryForObject("SELECT show_others_shifts FROM store_settings WHERE id = 1",
                Boolean.class);
        cleanUp();
        jdbcTemplate.update("UPDATE store_settings SET store_name = ?, show_others_shifts = TRUE WHERE id = 1",
                ORIGINAL);
        save("fs9manager", Role.MANAGER);
        save("fs9staff", Role.STAFF);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        jdbcTemplate.update("UPDATE store_settings SET store_name = ?, show_others_shifts = ? WHERE id = 1",
                savedName, savedShow);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs WHERE user_id IN "
                + "(SELECT id FROM (SELECT id FROM users WHERE login_id LIKE 'fs9%') t)");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fs9%'");
    }

    private void save(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("dummy");
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        userRepository.save(u);
    }

    private LoginUser login(String loginId) {
        return new LoginUser(userRepository.findByLoginId(loginId).orElseThrow(), false);
    }

    private long logCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_logs WHERE action = 'STORE_SETTING_UPDATE' "
                + "AND user_id = (SELECT id FROM users WHERE login_id = 'fs9manager')", Long.class);
    }

    private String body(LoginUser loginUser) throws Exception {
        return mvc.perform(get("/manager/settings").with(user(loginUser)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- 2.1 初期表示

    @Test
    void 店長が画面を開く_今の店舗名とスイッチと最終更新が出る() throws Exception {
        String html = body(login("fs9manager"));

        assertThat(html).contains("value=\"" + ORIGINAL + "\"")
                .contains("name=\"_showOthersShifts\"")
                .containsPattern("id=\"showOthersShifts\"[^>]*checked")
                .contains("OFF にすると、バイトは自分のシフトだけ見られます。")
                .containsPattern("最終更新 \\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}");
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void バイトが画面を開く_自分のホームへ戻される() throws Exception {
        mvc.perform(get("/manager/settings").with(user(login("fs9staff"))))
                .andExpect(redirectedUrl("/staff/home"));
    }

    // ---------------------------------------------------------------- No.3 店舗名を変える

    @Test
    void 店舗名を変えて表示をOFFにする_DBと操作ログに残り次の画面のヘッダーが変わる() throws Exception {
        LoginUser manager = login("fs9manager");

        mvc.perform(post("/manager/settings").with(csrf()).with(user(manager))
                .param("storeName", "新しい店").param("_showOthersShifts", "on"))
                .andExpect(redirectedUrl("/manager/settings"))
                .andExpect(flash().attribute("successMessage", "I2901"));

        assertThat(jdbcTemplate.queryForObject("SELECT store_name FROM store_settings WHERE id = 1", String.class))
                .isEqualTo("新しい店");
        assertThat(jdbcTemplate.queryForObject("SELECT show_others_shifts FROM store_settings WHERE id = 1",
                Boolean.class)).isFalse();
        assertThat(logCount()).isEqualTo(1);
        String detail = jdbcTemplate.queryForObject("SELECT CONCAT(target, '|', detail) FROM operation_logs "
                + "WHERE action = 'STORE_SETTING_UPDATE' "
                + "AND user_id = (SELECT id FROM users WHERE login_id = 'fs9manager')", String.class);
        assertThat(detail).isEqualTo("store_settings:1|storeName=新しい店, showOthersShifts=false");
        String html = body(manager);
        assertThat(html).contains("新しい店").doesNotContain(ORIGINAL)
                .doesNotContainPattern("id=\"showOthersShifts\"[^>]*checked");
    }

    // ---------------------------------------------------------------- No.6 何も変えずに保存

    @Test
    void 何も変えずに保存する_操作ログは増えずI2901は出る() throws Exception {
        mvc.perform(post("/manager/settings").with(csrf()).with(user(login("fs9manager")))
                .param("storeName", ORIGINAL).param("showOthersShifts", "true").param("_showOthersShifts", "on"))
                .andExpect(redirectedUrl("/manager/settings"))
                .andExpect(flash().attribute("successMessage", "I2901"));

        assertThat(logCount()).isZero();
    }

    // ---------------------------------------------------------------- No.1 入力エラーの再表示

    @Test
    void 店舗名を空にして保存する_E2901が画面に出てDBは変わらない() throws Exception {
        String html = mvc.perform(post("/manager/settings").with(csrf()).with(user(login("fs9manager")))
                .param("storeName", "").param("_showOthersShifts", "on"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("店舗名を入力してください。")
                .containsPattern("最終更新 \\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}");
        assertThat(jdbcTemplate.queryForObject("SELECT store_name FROM store_settings WHERE id = 1", String.class))
                .isEqualTo(ORIGINAL);
        assertThat(logCount()).isZero();
    }

    // ---------------------------------------------------------------- CSRF

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        LoginUser manager = login("fs9manager");
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/manager/settings").with(user(manager)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/manager/settings").with(user(manager)).session(session)
                .param("storeName", "新しい店").param("_showOthersShifts", "on"))
                .andExpect(redirectedUrl("/manager/home"));

        assertThat(jdbcTemplate.queryForObject("SELECT store_name FROM store_settings WHERE id = 1", String.class))
                .isEqualTo(ORIGINAL);
    }
}

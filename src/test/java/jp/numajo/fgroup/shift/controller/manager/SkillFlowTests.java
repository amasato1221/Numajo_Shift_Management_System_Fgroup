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

import java.util.List;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 職能管理の流れの結合テスト（20-SM-04 6章 No.2〜7、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・SkillService を使う。成功・業務エラーはリダイレクトだけを確かめる。
 * CSRF トークンなしの POST（94_セキュリティ設計書 6章 No.9）では、先に画面（HTML）を開くので、
 * skills.html と layout/base.html の描画も通る。{@code @Transactional} は付けず、ログインID が「fk」で始まる店長と、
 * 職能名が「fk」で始まる職能を、各テストの前後で消す。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class SkillFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser manager;
    private LoginUser staff;
    private Long staffId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        manager = new LoginUser(saveUser("fkmgr", Role.MANAGER), false);
        User staffUser = saveUser("fkstaff", Role.STAFF);
        staffId = staffUser.getId();
        staff = new LoginUser(staffUser, false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private User saveUser(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(passwordEncoder.encode("Passw0rd!Passw0rd!"));
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM slot_required_skills WHERE skill_id IN "
                + "(SELECT id FROM skills WHERE name LIKE 'fk%')");
        jdbcTemplate.update("DELETE FROM skills WHERE name LIKE 'fk%'");
        jdbcTemplate.update("DELETE FROM operation_logs "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'fk%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fk%'");
    }

    private Long skillId(String name) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM skills WHERE name = ?", Long.class, name);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long logCount(String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_logs WHERE action = ? "
                        + "AND user_id = (SELECT id FROM users WHERE login_id = 'fkmgr')",
                Long.class, action);
        return count == null ? 0 : count;
    }

    private void addSkill(String name) throws Exception {
        mvc.perform(post("/manager/skills").with(csrf()).with(user(manager))
                .param("name", name).param("description", "説明"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2402"));
    }

    // ---------------------------------------------------------------- 追加・変更

    @Test
    void 職能を追加する_1行増えて操作ログSKILL_CREATEが残る() throws Exception {
        addSkill("fkレジ");

        assertThat(skillId("fkレジ")).isNotNull();
        assertThat(logCount("SKILL_CREATE")).isEqualTo(1);
    }

    @Test
    void 同じ名前で追加する_E2404になり増えない() throws Exception {
        addSkill("fkレジ");

        mvc.perform(post("/manager/skills").with(csrf()).with(user(manager)).param("name", "fkレジ"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2404"));

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM skills WHERE name = 'fkレジ'", Long.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void ほかと同じ名前への変更と説明だけの変更_前者はE2404で後者は通る() throws Exception {
        addSkill("fkレジ");
        addSkill("fk調理");
        Long cooking = skillId("fk調理");

        mvc.perform(post("/manager/skills/" + cooking).with(csrf()).with(user(manager))
                .param("name", "fkレジ"))
                .andExpect(flash().attribute("errorMessage", "E2404"));
        mvc.perform(post("/manager/skills/" + cooking).with(csrf()).with(user(manager))
                .param("name", "fk調理").param("description", "新しい説明"))
                .andExpect(flash().attribute("successMessage", "I2403"));

        String description = jdbcTemplate.queryForObject("SELECT description FROM skills WHERE id = ?",
                String.class, cooking);
        assertThat(description).isEqualTo("新しい説明");
        assertThat(logCount("SKILL_UPDATE")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 削除

    @Test
    void 持っている人がいる職能を削除する_職能と持っている人の行が消える() throws Exception {
        addSkill("fkレジ");
        Long skillId = skillId("fkレジ");
        jdbcTemplate.update("INSERT INTO user_skills (user_id, skill_id) VALUES (?, ?)", staffId, skillId);

        mvc.perform(post("/manager/skills/" + skillId + "/delete").with(csrf()).with(user(manager)))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("successMessage", "I2404"));

        assertThat(skillId("fkレジ")).isNull();
        Long holders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_skills WHERE skill_id = ?",
                Long.class, skillId);
        assertThat(holders).isZero();
        assertThat(logCount("SKILL_DELETE")).isEqualTo(1);
    }

    @Test
    void 存在しない職能を削除する_404になる() throws Exception {
        mvc.perform(post("/manager/skills/999999999/delete").with(csrf()).with(user(manager)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 枠の必要職能

    @Test
    void 枠の必要職能を保存して全部外す_保存した値になり外すと空になる() throws Exception {
        addSkill("fkレジ");
        Long skillId = skillId("fkレジ");

        mvc.perform(post("/manager/skills/slot-required").with(csrf()).with(user(manager))
                .param("daySkillIds", String.valueOf(skillId)))
                .andExpect(flash().attribute("successMessage", "I2405"));

        assertThat(requiredSlotIds(skillId)).containsExactly(1);

        mvc.perform(post("/manager/skills/slot-required").with(csrf()).with(user(manager)))
                .andExpect(flash().attribute("successMessage", "I2405"));

        assertThat(requiredSlotIds(skillId)).isEmpty();
        assertThat(logCount("SLOT_SKILL_UPDATE")).isEqualTo(2);
    }

    @Test
    void 存在しない職能IDで枠の必要職能を保存する_E2405になりDBは変わらない() throws Exception {
        mvc.perform(post("/manager/skills/slot-required").with(csrf()).with(user(manager))
                .param("daySkillIds", "999999999"))
                .andExpect(redirectedUrl("/manager/skills"))
                .andExpect(flash().attribute("errorMessage", "E2405"));

        assertThat(logCount("SLOT_SKILL_UPDATE")).isZero();
    }

    private List<Integer> requiredSlotIds(Long skillId) {
        return jdbcTemplate.queryForList("SELECT slot_id FROM slot_required_skills WHERE skill_id = ?",
                Integer.class, skillId);
    }

    // ---------------------------------------------------------------- CSRF

    @Test
    void CSRFトークンなしでPOSTする_自分のホームへ戻りDBは変わらない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/manager/skills").with(user(manager)).session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/manager/skills").with(user(manager)).session(session).param("name", "fkレジ"))
                .andExpect(redirectedUrl("/manager/home"));

        assertThat(skillId("fkレジ")).isNull();
        assertThat(logCount("SKILL_CREATE")).isZero();
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void バイトが職能管理を操作する_自分のホームへ戻され職能は増えない() throws Exception {
        mvc.perform(post("/manager/skills").with(csrf()).with(user(staff)).param("name", "fkレジ"))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(skillId("fkレジ")).isNull();
    }
}

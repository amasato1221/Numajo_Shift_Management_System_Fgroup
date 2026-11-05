package jp.numajo.fgroup.shift.controller.manager;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.StaffService;
import lombok.RequiredArgsConstructor;

@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerStaffControllerMvcTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StaffService staffService;

    private MockMvc mvc;
    private LoginUser manager;
    private LoginUser staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        manager = new LoginUser(saveUser("fsmgr", Role.MANAGER), false);
        staff = new LoginUser(saveUser("fsstaff", Role.STAFF), false);
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
        jdbcTemplate.update("DELETE FROM operation_logs "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'fs%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fs%'");
    }

    @Test
    void 一覧_店長なら状態の既定をACTIVEにして一覧画面を表示する() throws Exception {
        mvc.perform(get("/manager/staff").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/staff-list"))
                .andExpect(model().attribute("status", "ACTIVE"));

        verify(staffService).search(isNull(), eq(StaffStatusFilter.ACTIVE));
    }

    @Test
    void 一覧_検索文字と状態を受け取ってServiceへ渡す() throws Exception {
        mvc.perform(get("/manager/staff").param("keyword", "やま").param("status", "INACTIVE")
                .with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("keyword", "やま"))
                .andExpect(model().attribute("status", "INACTIVE"));

        verify(staffService).search(eq("やま"), eq(StaffStatusFilter.INACTIVE));
    }

    @Test
    void 一覧_バイトなら自分のホームへ戻る() throws Exception {
        mvc.perform(get("/manager/staff").with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));
    }
}
package jp.numajo.fgroup.shift.controller.staff;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SubmissionState;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftRequestService;
import lombok.RequiredArgsConstructor;

/**
 * StaffRequestController（SB-03：シフト希望提出）の画面のテスト。URL・権限・CSRF・入力チェック・行き先を、MockMvc で
 * 確かめる（97_テスト計画書 3.3）。業務の中身は ShiftRequestService のテストで確かめるので、ここでは Service をモックにする。
 *
 * <p>ログインする人は、本物の users に保存してから使う（UserActiveCheckFilter が毎回 DB で有効かどうかを調べるため。
 * 保存していないと /login?disabled に飛ばされる）。権限がないとき・CSRF トークンがないときは、403 ではなく
 * 自分のホームへのリダイレクトになる（HomeRedirectAccessDeniedHandler）。CSRF トークンなしのテストは、セッションにトークンが
 * ないと /login?expired へ行くので、先に画面を開いてトークンを作っておく。
 *
 * <pre>
 * 組み合わせ表：POST の受け付け（ログインした人の role × CSRF トークン）
 * No. | role  | CSRF トークン | 結果
 *  1  | STAFF | あり          | 受け付ける（入力チェックへ）
 *  2  | STAFF | なし          | 自分のホーム（/staff/home）へ戻す（Service を呼ばない）
 *  3  | 店長  | あり          | 自分のホーム（/manager/home）へ戻す
 *  4  | 店長  | なし          | 自分のホーム（/manager/home）へ戻す
 *
 * 組み合わせ表：提出（POST /staff/requests）
 * No. | 入力チェック | Service の結果 | 結果
 *  1  | エラー       | （呼ばない）   | 提出画面を再表示
 *  2  | OK           | 提出できた     | 提出画面へリダイレクト（I1302）
 *  3  | OK           | E1303（受付終了）| 提出画面へリダイレクト（errorMessage）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class StaffRequestControllerMvcTests {

    private final MockMvc mvc;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ShiftRequestService shiftRequestService;
    @MockitoBean
    private PeriodService periodService;

    private LoginUser staff;
    private LoginUser manager;
    private Long staffId;

    @BeforeEach
    void setUp() {
        cleanUp();
        staff = new LoginUser(saveUser("rqstaff", Role.STAFF), false);
        manager = new LoginUser(saveUser("rqmgr", Role.MANAGER), false);
        staffId = staff.getUserId();
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
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'rq%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'rq%'");
    }

    private static RequestPostProcessor login(LoginUser loginUser) {
        return SecurityMockMvcRequestPostProcessors.user(loginUser);
    }

    private static RequestPostProcessor csrf() {
        return SecurityMockMvcRequestPostProcessors.csrf();
    }

    /** 1日分の正しい入力を付ける。 */
    private static MockHttpServletRequestBuilder withValidInput(MockHttpServletRequestBuilder builder, long periodId) {
        return builder.param("periodId", String.valueOf(periodId))
                .param("days[0].workDate", "2099-11-01").param("days[0].mode", "SLOT")
                .param("days[0].dayShift", "OK").param("days[0].eveningShift", "NG");
    }

    private static RequestPageView page() {
        PeriodOption option = new PeriodOption(1L, LocalDate.of(2099, 11, 1), LocalDate.of(2099, 11, 15),
                LocalDateTime.of(2099, 10, 15, 23, 59), PeriodStatus.OPEN, "11/1〜11/15（期限 10/15）");
        return new RequestPageView(option, List.of(option), "2099/10/15 23:59", true, null, null,
                SubmissionState.NOT_SUBMITTED, "未提出", List.of(), "山田 太郎");
    }

    private void givenPage() {
        ShiftRequestForm form = new ShiftRequestForm();
        form.setPeriodId(1L);
        when(periodService.getPeriod(1L)).thenReturn(new ShiftPeriod());
        when(shiftRequestService.toForm(any(), any())).thenReturn(form);
    }

    /** 先に提出画面を開いて、セッションに CSRF トークンを作っておく（ないと /login?expired へ行くため）。 */
    private MockHttpSession openPageSession() throws Exception {
        givenPage();
        when(shiftRequestService.getRequestPage(staffId, null)).thenReturn(page());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/staff/requests").with(login(staff)).session(session)).andExpect(status().isOk());
        return session;
    }

    @Test
    void 提出画面_バイトなら表示する() throws Exception {
        givenPage();
        when(shiftRequestService.getRequestPage(staffId, null)).thenReturn(page());

        mvc.perform(get("/staff/requests").with(login(staff)))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/requests"))
                .andExpect(model().attributeExists("page", "shiftRequestForm", "timeOptions"));
    }

    @Test
    void 提出画面_受付中の期間がなければpageなしで表示する() throws Exception {
        when(shiftRequestService.getRequestPage(staffId, null)).thenReturn(null);

        mvc.perform(get("/staff/requests").with(login(staff)))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/requests"))
                .andExpect(model().attribute("page", (Object) null));
    }

    @Test
    void 提出画面_店長なら自分のホームへ戻る() throws Exception {
        mvc.perform(get("/staff/requests").with(login(manager)))
                .andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void 提出_CSRFトークンがなければホームへ戻してServiceを呼ばない() throws Exception {
        MockHttpSession session = openPageSession();

        mvc.perform(withValidInput(post("/staff/requests"), 1L).with(login(staff)).session(session))
                .andExpect(redirectedUrl("/staff/home"));

        verify(shiftRequestService, never()).submit(any(), any());
    }

    @Test
    void 提出_店長なら自分のホームへ戻してServiceを呼ばない() throws Exception {
        mvc.perform(withValidInput(post("/staff/requests"), 1L).with(login(manager)).with(csrf()))
                .andExpect(redirectedUrl("/manager/home"));

        verify(shiftRequestService, never()).submit(any(), any());
    }

    @Test
    void 提出_入力が空なら提出画面を再表示してServiceを呼ばない() throws Exception {
        mvc.perform(post("/staff/requests").with(login(staff)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/requests"))
                .andExpect(model().attributeHasFieldErrors("shiftRequestForm", "periodId", "days"));

        verify(shiftRequestService, never()).submit(any(), any());
    }

    @Test
    void 提出_成功したら提出画面へリダイレクトしてI1302を渡す() throws Exception {
        mvc.perform(withValidInput(post("/staff/requests"), 1L).with(login(staff)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/requests?periodId=1"))
                .andExpect(flash().attribute("successMessage", "I1302"));

        verify(shiftRequestService).submit(eq(staffId), any(ShiftRequestForm.class));
    }

    @Test
    void 提出_受付終了ならE1303を渡して提出画面へ戻す() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException("E1303"))
                .when(shiftRequestService).submit(eq(staffId), any(ShiftRequestForm.class));

        mvc.perform(withValidInput(post("/staff/requests"), 1L).with(login(staff)).with(csrf()))
                .andExpect(redirectedUrl("/staff/requests?periodId=1"))
                .andExpect(flash().attribute("errorMessage", "E1303"));
    }

    @Test
    void 取り消し_CSRFトークンがなければホームへ戻してServiceを呼ばない() throws Exception {
        MockHttpSession session = openPageSession();

        mvc.perform(post("/staff/requests/cancel").param("periodId", "1").with(login(staff)).session(session))
                .andExpect(redirectedUrl("/staff/home"));

        verify(shiftRequestService, never()).cancel(any(), any());
    }

    @Test
    void 取り消し_成功したら提出画面へリダイレクトしてI1303を渡す() throws Exception {
        mvc.perform(post("/staff/requests/cancel").param("periodId", "1").with(login(staff)).with(csrf()))
                .andExpect(redirectedUrl("/staff/requests?periodId=1"))
                .andExpect(flash().attribute("successMessage", "I1303"));

        verify(shiftRequestService).cancel(staffId, 1L);
    }
}
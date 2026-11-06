package jp.numajo.fgroup.shift.controller.staff;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.dto.MonthCalendar;
import jp.numajo.fgroup.shift.dto.StaffHomeView;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.StaffHomeService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * StaffHomeController（SB-01）の結合テスト（20-SB-01 2.1、6章 No.7、30-06 2章）。
 *
 * <p>
 * StaffHomeService はモックにし、MockMvc（standalone）で、ym の読み方と model・画面名を確かめる。今月は Clock で
 * 2026年10月に固定する。前後の空白を取る処理（StringTrimmerEditor）は本物の GlobalModelAdvice で動かす。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * toYearMonth  ym == null           | 真：今月（ym なし、空文字・空白だけは null になる）／偽：parse する
 *              DateTimeParseException | あり：今月（2026-13・abc・2026/09・2026-9）／なし：その月（2026-09・2027-01）
 * </pre>
 */
class StaffHomeControllerTests {

    private static final Long USER_ID = 7L;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 10);

    private StaffHomeService staffHomeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        staffHomeService = mock(StaffHomeService.class);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        Clock clock = Clock.fixed(LocalDateTime.parse("2026-10-31T23:59:00").atZone(TOKYO).toInstant(), TOKYO);
        mvc = MockMvcBuilders.standaloneSetup(new StaffHomeController(staffHomeService, clock))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, mock(NotificationService.class)))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        User u = new User();
        u.setId(USER_ID);
        u.setLoginId("staff");
        u.setRole(Role.STAFF);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private StaffHomeView viewOf(YearMonth ym) {
        StaffHomeView view = new StaffHomeView(null, List.of(), List.of(),
                new MonthCalendar(ym, List.of(), ym.minusMonths(1), ym.plusMonths(1)));
        when(staffHomeService.getHome(USER_ID, ym)).thenReturn(view);
        return view;
    }

    @Test
    void ymなし_今月で作りhomeに入れてホーム画面になる() throws Exception {
        StaffHomeView expected = viewOf(THIS_MONTH);

        mvc.perform(get("/staff/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/home"))
                .andExpect(model().attribute("home", expected));
        verify(staffHomeService).getHome(USER_ID, THIS_MONTH);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void ymが空文字か空白だけ_nullになり今月(String ym) throws Exception {
        viewOf(THIS_MONTH);

        mvc.perform(get("/staff/home").param("ym", ym)).andExpect(status().isOk());
        verify(staffHomeService).getHome(USER_ID, THIS_MONTH);
    }

    @ParameterizedTest
    @ValueSource(strings = { "2026-09", "2027-01" })
    void ymが正しい形_その月(String ym) throws Exception {
        YearMonth expected = YearMonth.parse(ym);
        viewOf(expected);

        mvc.perform(get("/staff/home").param("ym", ym)).andExpect(status().isOk());
        verify(staffHomeService).getHome(USER_ID, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "2026-13", "abc", "2026/09", "2026-9" })
    void No7_ymの形が違う_今月(String ym) throws Exception {
        viewOf(THIS_MONTH);

        mvc.perform(get("/staff/home").param("ym", ym)).andExpect(status().isOk());
        verify(staffHomeService).getHome(USER_ID, THIS_MONTH);
    }
}

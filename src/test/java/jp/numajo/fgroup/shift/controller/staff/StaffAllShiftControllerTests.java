package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.ShiftTable;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.GlobalExceptionHandler;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ShiftTableService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * StaffAllShiftController（SB-04）のテスト（20-SB-04 6章 No.1・2・5、97_テスト計画書 4.2）。
 *
 * <p>
 * Service はモックにし、MockMvc（standalone）で、振り分け・フラッシュ・model・リダイレクト先を確かめる。
 * 期間の選び方は ShiftTableService のテスト（ShiftTableServiceTests）で確かめる。画面（HTML）は表示しない（AllShiftsFlowTests）。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   S＝店舗設定で「ほかの人のシフトを表示する」が ON
 *   P＝表示する期間がある（choosePeriod の戻り値が null でない）
 *
 * No. | 判定の値                          | 結果
 *  1  | S偽                              | redirect:/staff/home、W1401、選択肢を取らない
 *  2  | S真 P真                           | 選択肢・期間・欠勤を出さない表・myUserId を model に入れる
 *  3  | S真 P偽                           | period・table は null（I1401）、表を作らない
 *  4  | S真 choosePeriod が例外             | 404（error/404）
 * </pre>
 */
class StaffAllShiftControllerTests {

    private static final String VIEW = "staff/all-shifts";
    private static final Long MY_ID = 7L;

    private final StoreSettingService storeSettingService = mock(StoreSettingService.class);
    private final ShiftTableService shiftTableService = mock(ShiftTableService.class);

    private final ShiftPeriod p1 = period(1L, "2026-10-16", "2026-10-31");
    private final ShiftPeriod p2 = period(2L, "2026-11-01", "2026-11-15");
    private final ShiftTable table = new ShiftTable(List.of(), List.of());

    private final StoreSetting setting = new StoreSetting();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        when(shiftTableService.findPublishedPeriods()).thenReturn(List.of(p2, p1));
        when(shiftTableService.choosePeriod(List.of(p2, p1), null)).thenReturn(p2);
        when(shiftTableService.build(any(), anyBoolean())).thenReturn(table);
        mvc = MockMvcBuilders.standaloneSetup(new StaffAllShiftController(storeSettingService, shiftTableService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 店舗設定がOFF_ホームへW1401で戻し選択肢を取らない() throws Exception {
        setting.setShowOthersShifts(false);

        mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/home"))
                .andExpect(flash().attribute("warningMessage", "W1401"));

        verify(shiftTableService, never()).findPublishedPeriods();
        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    @Test
    void 表示する期間がある_選択肢と期間と欠勤を出さない表と自分のIDを入れる() throws Exception {
        MvcResult result = mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("table", table))
                .andExpect(model().attribute("myUserId", MY_ID))
                .andReturn();

        PeriodOption period = (PeriodOption) result.getModelAndView().getModel().get("period");
        assertThat(period.id()).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<PeriodOption> options = (List<PeriodOption>) result.getModelAndView().getModel().get("periodOptions");
        assertThat(options).extracting(PeriodOption::id).containsExactly(2L, 1L);
        assertThat(options.get(0).label()).isEqualTo("11/1〜11/15（期限 10/17）");
        verify(shiftTableService).build(p2, false);
    }

    @Test
    void 公開済みの期間がない_periodとtableはnullで表を作らない() throws Exception {
        when(shiftTableService.findPublishedPeriods()).thenReturn(List.of());
        when(shiftTableService.choosePeriod(List.of(), null)).thenReturn(null);

        mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("periodOptions", List.of()))
                .andExpect(model().attribute("period", (Object) null))
                .andExpect(model().attribute("table", (Object) null));

        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    @Test
    void 選択肢にない期間を指定_404() throws Exception {
        when(shiftTableService.choosePeriod(anyList(), any()))
                .thenThrow(new ResourceNotFoundException("period id=99"));

        mvc.perform(get("/staff/all-shifts").param("periodId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));

        verify(shiftTableService).choosePeriod(List.of(p2, p1), 99L);
        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    // ---------------------------------------------------------------- 準備

    private static ShiftPeriod period(Long id, String start, String end) {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(id);
        p.setStartDate(LocalDate.parse(start));
        p.setEndDate(LocalDate.parse(end));
        p.setDeadlineAt(LocalDate.parse(start).minusDays(15).atTime(23, 59));
        p.setStatus(PeriodStatus.PUBLISHED);
        return p;
    }

    private static void login() {
        User u = new User();
        u.setId(MY_ID);
        u.setLoginId("staff7");
        u.setRole(Role.STAFF);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}

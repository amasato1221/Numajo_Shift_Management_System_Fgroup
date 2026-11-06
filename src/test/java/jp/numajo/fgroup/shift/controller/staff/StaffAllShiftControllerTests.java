package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ShiftTableService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * StaffAllShiftController（SB-04）のテスト（20-SB-04 6章 No.1・2・5、97_テスト計画書 4.2）。
 *
 * <p>
 * Service・Repository はモックにし、MockMvc（standalone）で、振り分け・フラッシュ・model・リダイレクト先を確かめる。
 * 画面（HTML）は表示しない（表示は AllShiftsFlowTests）。今日は Clock を 2026-11-06（金）に固定する。
 * 選択肢は新しい順に、11/16〜11/30（P3）、11/1〜11/15（P2、今日を含む）、10/16〜10/31（P1）。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   S＝店舗設定で「ほかの人のシフトを表示する」が ON
 *   I＝periodId がある、L＝periodId が選択肢にある、E＝選択肢が0件
 *   F＝期間の開始日が今日以前、T＝期間の終了日が今日以後（今日を含む＝F真 かつ T真）
 *
 * No. | 判定の値                                        | 結果
 *  1  | S偽                                            | redirect:/staff/home、W1401、選択肢を取らない
 *  2  | S真 I真 L真                                     | その期間（P1）
 *  3  | S真 I真 L偽                                     | 404（ResourceNotFoundException）
 *  4  | S真 I真 L偽 E真                                  | 404
 *  5  | S真 I偽 E真                                     | period・table は null（I1401）、表を作らない
 *  6  | S真 I偽 E偽：P3 F偽 → P2 F真 T真                  | 今日を含む P2
 *  7  | S真 I偽 E偽：P2 F真 T偽 → P1 F真 T偽（含む期間なし） | 先頭（一番新しい P2）
 *  8  | －                                             | 選択肢の範囲（今日の3か月前の1日＝8/1、公開済み）、myUserId、表は欠勤を出さない
 * </pre>
 */
class StaffAllShiftControllerTests {

    private static final String VIEW = "staff/all-shifts";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Long MY_ID = 7L;

    private final StoreSettingService storeSettingService = mock(StoreSettingService.class);
    private final ShiftPeriodRepository periodRepository = mock(ShiftPeriodRepository.class);
    private final ShiftTableService shiftTableService = mock(ShiftTableService.class);

    private final ShiftPeriod p1 = period(1L, "2026-10-16", "2026-10-31");
    private final ShiftPeriod p2 = period(2L, "2026-11-01", "2026-11-15");
    private final ShiftPeriod p3 = period(3L, "2026-11-16", "2026-11-30");
    private final ShiftTable table = new ShiftTable(List.of(), List.of());

    private final StoreSetting setting = new StoreSetting();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(LocalDateTime.parse("2026-11-06T09:00:00").atZone(TOKYO).toInstant(), TOKYO);
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        when(shiftTableService.build(any(), anyBoolean())).thenReturn(table);
        givenPeriods(p3, p2, p1);
        mvc = MockMvcBuilders.standaloneSetup(
                new StaffAllShiftController(storeSettingService, periodRepository, shiftTableService, clock))
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

        verify(periodRepository, never()).findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(any(), any());
        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    @Test
    void 選択肢にある期間を指定_その期間を出す() throws Exception {
        MvcResult result = mvc.perform(get("/staff/all-shifts").param("periodId", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(periodOf(result).id()).isEqualTo(1L);
        verify(shiftTableService).build(p1, false);
    }

    @Test
    void 選択肢にない期間を指定_404() throws Exception {
        mvc.perform(get("/staff/all-shifts").param("periodId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));

        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    @Test
    void 選択肢が0件で期間を指定_404() throws Exception {
        givenPeriods();

        mvc.perform(get("/staff/all-shifts").param("periodId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void 公開済みの期間がない_periodとtableはnullで表を作らない() throws Exception {
        givenPeriods();

        mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("periodOptions", List.of()))
                .andExpect(model().attribute("period", (Object) null))
                .andExpect(model().attribute("table", (Object) null));

        verify(shiftTableService, never()).build(any(), anyBoolean());
    }

    @Test
    void 期間の指定なし_今日を含む期間を出す() throws Exception {
        MvcResult result = mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(periodOf(result).id()).isEqualTo(2L);
    }

    @Test
    void 期間の指定なしで今日を含む期間がない_一番新しい期間を出す() throws Exception {
        ShiftPeriod older = period(4L, "2026-10-01", "2026-10-15");
        ShiftPeriod newest = period(5L, "2026-10-16", "2026-11-05");
        givenPeriods(newest, older);

        MvcResult result = mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(periodOf(result).id()).isEqualTo(5L);
    }

    @Test
    void モデル_選択肢の範囲と公開済み_自分のID_欠勤を出さない表() throws Exception {
        MvcResult result = mvc.perform(get("/staff/all-shifts"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("myUserId", MY_ID))
                .andExpect(model().attribute("table", table))
                .andReturn();

        verify(periodRepository).findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(PeriodStatus.PUBLISHED,
                LocalDate.parse("2026-08-01"));
        verify(shiftTableService).build(eq(p2), eq(false));
        @SuppressWarnings("unchecked")
        List<PeriodOption> options = (List<PeriodOption>) result.getModelAndView().getModel().get("periodOptions");
        assertThat(options).extracting(PeriodOption::id).containsExactly(3L, 2L, 1L);
        assertThat(options.get(0).label()).isEqualTo("11/16〜11/30（期限 11/1）");
    }

    // ---------------------------------------------------------------- 準備

    private void givenPeriods(ShiftPeriod... periods) {
        when(periodRepository.findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(any(), any()))
                .thenReturn(List.of(periods));
    }

    private static PeriodOption periodOf(MvcResult result) {
        return (PeriodOption) result.getModelAndView().getModel().get("period");
    }

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

package jp.numajo.fgroup.shift.controller.staff;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.dto.CalendarDay;
import jp.numajo.fgroup.shift.dto.PeriodCalendar;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.GlobalExceptionHandler;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftCalendarService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * StaffShiftController（SB-02）の結合テスト（20-SB-02 6章 No.1〜4・8、97_テスト計画書 4.2）。
 *
 * <p>
 * PeriodService と ShiftCalendarService はモックにし、MockMvc（standalone）で、画面の振り分け・モデルの中身・404 を確かめる。
 * 画面（HTML）は表示しない。HTML の中身（ボタン・I1201・空白の日）は StaffShiftViewTests で確かめる。
 * 期間の決め方そのもの（20-SB-02 6章 No.1〜4）は PeriodServiceTests で確かめ、ここでは periodId を
 * PeriodService にそのまま渡すことを確かめる。
 * </p>
 *
 * <pre>
 * 判定                          | テスト
 * shifts  period == null        | 真：期間とカレンダーを入れず、画面だけ返す、偽：モデルに期間・カレンダー・希望ボタンの可否を入れる
 * shifts  resolveDefault が例外 | 例外あり：404（ResourceNotFoundException）、なし：画面
 *
 * 組み合わせ表：periodId の有無 × 表示する期間の有無
 * No. | periodId | 期間が決まる | 結果
 *  1  | あり     | はい         | periodId を渡して、モデルに期間・カレンダー・希望ボタンの可否を入れる
 *  2  | なし     | はい         | null を渡して、同じモデルを入れる
 *  3  | なし     | いいえ（選択肢が0件） | 期間・カレンダーを入れず、選択肢（空）だけ入れる
 *  4  | あり     | 例外（選択肢にない） | 404
 *  （periodId があって期間が決まらない＝null は、PeriodService が例外にするので、ありえない）
 *
 * 各テスト：No.1＝periodId を付けて開く／No.2＝periodId なしで開く／No.3＝選択肢が0件／No.4＝選択肢にない periodId
 * 希望ボタンの可否：isAcceptingRequests の結果（真・偽）をそのままモデルに入れる
 * </pre>
 */
class StaffShiftControllerTests {

    private static final String VIEW = "staff/shifts";
    private static final Long STAFF_ID = 7L;

    private PeriodService periodService;
    private ShiftCalendarService shiftCalendarService;
    private MockMvc mvc;

    private final PeriodOption option = new PeriodOption(5L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15),
            "11/1〜11/15", LocalDateTime.of(2026, 10, 15, 23, 59, 59), "2026/10/15 23:59", "10/15 23:59",
            PeriodStatus.OPEN, "11/1〜11/15（期限 10/15）");
    private final ShiftPeriod period = period();
    private final PeriodCalendar calendar = new PeriodCalendar(option, List.<List<CalendarDay>>of());

    @BeforeEach
    void setUp() {
        periodService = mock(PeriodService.class);
        shiftCalendarService = mock(ShiftCalendarService.class);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        NotificationService notificationService = mock(NotificationService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        when(periodService.findOptionsForStaff()).thenReturn(List.of(option));
        when(periodService.resolveDefault(List.of(option), 5L)).thenReturn(period);
        when(periodService.resolveDefault(List.of(option), null)).thenReturn(period);
        when(shiftCalendarService.buildPeriod(STAFF_ID, period)).thenReturn(calendar);
        mvc = MockMvcBuilders.standaloneSetup(new StaffShiftController(periodService, shiftCalendarService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, notificationService),
                        new GlobalExceptionHandler())
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
        u.setId(STAFF_ID);
        u.setLoginId("staff");
        u.setRole(Role.STAFF);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private ShiftPeriod period() {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(5L);
        p.setStartDate(option.startDate());
        p.setEndDate(option.endDate());
        p.setDeadlineAt(option.deadlineAt());
        p.setStatus(PeriodStatus.OPEN);
        return p;
    }

    @Test
    void periodIdを付けて開く_その期間のカレンダーと希望ボタンの可否を持ってシフトカレンダー画面になる() throws Exception {
        when(periodService.isAcceptingRequests(period)).thenReturn(true);

        mvc.perform(get("/staff/shifts").param("periodId", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("periodOptions", List.of(option)))
                .andExpect(model().attribute("period", option))
                .andExpect(model().attribute("calendar", calendar))
                .andExpect(model().attribute("canEditRequest", true));
        verify(periodService).resolveDefault(List.of(option), 5L);
    }

    @Test
    void periodIdなしで開く_nullを渡して決まった期間のカレンダーを出す() throws Exception {
        when(periodService.isAcceptingRequests(period)).thenReturn(false);

        mvc.perform(get("/staff/shifts"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("period", option))
                .andExpect(model().attribute("calendar", calendar))
                .andExpect(model().attribute("canEditRequest", false));
        verify(periodService).resolveDefault(List.of(option), null);
    }

    @Test
    void 選択肢が0件_期間とカレンダーを入れずに画面だけ返す() throws Exception {
        when(periodService.findOptionsForStaff()).thenReturn(List.of());
        when(periodService.resolveDefault(List.of(), null)).thenReturn(null);

        mvc.perform(get("/staff/shifts"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andExpect(model().attribute("periodOptions", List.of()))
                .andExpect(model().attributeDoesNotExist("period", "calendar", "canEditRequest"));
        verify(shiftCalendarService, never()).buildPeriod(STAFF_ID, period);
    }

    @Test
    void 選択肢にないperiodId_404のエラー画面になる() throws Exception {
        when(periodService.resolveDefault(List.of(option), 99L))
                .thenThrow(new ResourceNotFoundException("period id=99"));

        mvc.perform(get("/staff/shifts").param("periodId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }
}

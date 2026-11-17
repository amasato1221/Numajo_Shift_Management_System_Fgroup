package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.entity.CalendarFeed;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.NotificationSettingForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.CalendarFeedService;
import jp.numajo.fgroup.shift.service.NotificationSettingService;

/**
 * StaffSettingController（SB-05）の単体テスト（20-SB-05 2章、97_テスト計画書 4.2）。
 *
 * <p>
 * NotificationSettingService・CalendarFeedService はモックにし、MockMvc（standalone）で、
 * model・Service に渡す値・フラッシュ・リダイレクト先を確かめる。
 * 画面（HTML）の描画・DB・権限・CSRF は StaffSettingFlowTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定（saveNotifications）           | 条件                         | テスト
 * form.getDaysBefore() == null       | 真：空のリスト、偽：送られた値  | 全部外す・値を送る
 * save が BusinessException を投げる  | 真：E1501、偽：I1501          | 改ざん・値を送る
 * days.isEmpty()                     | 真：W1501 も出す、偽：出さない  | 全部外す・空リスト・値を送る
 *
 * 複数条件（daysBefore が null, 例外, 空）| 結果                  | テスト
 * （真, 偽, 真）                       | I1501＋W1501          | 全部外して保存する
 * （偽, 偽, 真）                       | I1501＋W1501          | 空のリストが届く（hidden の _daysBefore だけ）
 * （偽, 偽, 偽）                       | I1501 だけ            | 当日と7日前を保存する
 * （偽, 真, －）                       | E1501 だけ            | 値2を送る
 * （真, 真, －）                       | 起きない（空のリストは Service で例外にならない）
 * show・reissue は分岐なし（1本ずつ通す）。
 * </pre>
 */
class StaffSettingControllerTests {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "a".repeat(43);

    private NotificationSettingService notificationSettingService;
    private CalendarFeedService calendarFeedService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        notificationSettingService = mock(NotificationSettingService.class);
        calendarFeedService = mock(CalendarFeedService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StaffSettingController(notificationSettingService, calendarFeedService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        User u = new User();
        u.setId(USER_ID);
        u.setLoginId("staff1");
        u.setRole(Role.STAFF);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 2.1 初期表示

    @Test
    void 画面を表示する_保存済みの値と購読URLをmodelに入れる() throws Exception {
        CalendarFeed feed = new CalendarFeed();
        feed.setUserId(USER_ID);
        feed.setToken(TOKEN);
        when(notificationSettingService.findDays(USER_ID)).thenReturn(List.of(1, 3));
        when(calendarFeedService.getOrCreate(USER_ID)).thenReturn(feed);
        when(calendarFeedService.feedUrl(TOKEN)).thenReturn("http://localhost:8080/ical/" + TOKEN + ".ics");
        when(calendarFeedService.webcalUrl(TOKEN)).thenReturn("webcal://localhost:8080/ical/" + TOKEN + ".ics");

        MvcResult result = mvc.perform(get("/staff/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/settings"))
                .andExpect(model().attribute("feedUrl", "http://localhost:8080/ical/" + TOKEN + ".ics"))
                .andExpect(model().attribute("webcalUrl", "webcal://localhost:8080/ical/" + TOKEN + ".ics"))
                .andReturn();

        NotificationSettingForm form = (NotificationSettingForm) result.getModelAndView().getModel()
                .get("notificationSettingForm");
        assertThat(form.getDaysBefore()).containsExactly(1, 3);
        @SuppressWarnings("unchecked")
        List<SelectOption> options = (List<SelectOption>) result.getModelAndView().getModel().get("dayOptions");
        assertThat(options).extracting(SelectOption::value).containsExactly("0", "1", "3", "7");
        assertThat(options).extracting(SelectOption::label).containsExactly("当日", "前日", "3日前", "7日前");
    }

    @Test
    void 選択肢の値はServiceの選択肢と同じ() {
        assertThat(StaffSettingController.DAY_OPTIONS).extracting(o -> Integer.valueOf(o.value()))
                .containsExactlyElementsOf(NotificationSettingService.DAYS_OPTIONS);
    }

    // ---------------------------------------------------------------- 2.2 締切通知の保存

    @Test
    void 全部外して保存する_空のリストで保存しI1501とW1501() throws Exception {
        mvc.perform(post("/staff/settings/notifications"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1501"))
                .andExpect(flash().attribute("warningMessage", "W1501"))
                .andExpect(flash().attributeCount(2));

        verify(notificationSettingService).save(USER_ID, List.of());
    }

    @Test
    void 空のリストが届く_I1501とW1501() throws Exception {
        // th:field の hidden（_daysBefore）だけが届くと、Spring は空のリストを入れる
        mvc.perform(post("/staff/settings/notifications").param("_daysBefore", "on"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1501"))
                .andExpect(flash().attribute("warningMessage", "W1501"));

        verify(notificationSettingService).save(USER_ID, List.of());
    }

    @Test
    void 当日と7日前を保存する_その値で保存しI1501だけ() throws Exception {
        mvc.perform(post("/staff/settings/notifications").param("daysBefore", "0", "7"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1501"))
                .andExpect(flash().attributeCount(1));

        verify(notificationSettingService).save(USER_ID, List.of(0, 7));
    }

    @Test
    void 値2を送る_E1501の赤のアラートで戻る() throws Exception {
        doThrow(new BusinessException("E1501")).when(notificationSettingService).save(eq(USER_ID), anyList());

        MvcResult result = mvc.perform(post("/staff/settings/notifications").param("daysBefore", "2"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("errorMessage", "E1501"))
                .andReturn();

        assertThat((java.util.Map<String, Object>) result.getFlashMap()).doesNotContainKeys("successMessage", "warningMessage")
                .containsKey("errorArgs");
        verify(notificationSettingService).save(USER_ID, List.of(2));
    }

    // ---------------------------------------------------------------- 2.3 再発行

    @Test
    void 再発行する_自分のIDで再発行しI1502() throws Exception {
        mvc.perform(post("/staff/settings/calendar/reissue"))
                .andExpect(redirectedUrl("/staff/settings"))
                .andExpect(flash().attribute("successMessage", "I1502"));

        verify(calendarFeedService).reissue(USER_ID);
    }
}

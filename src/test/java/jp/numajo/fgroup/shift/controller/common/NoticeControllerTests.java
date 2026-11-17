package jp.numajo.fgroup.shift.controller.common;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;

/**
 * NoticeController（SC-02）の単体テスト（20-SC-02 2章、30-06 1.3）。
 *
 * <p>
 * NotificationService はモックにし、MockMvc（standalone）で、ログイン中の人のIDで Service を呼ぶこと・model・画面名・
 * リダイレクト先・フラッシュを確かめる。コントローラーに分岐はないので、メソッドごとに1件ずつ（命令網羅）。
 * 移動先の判定・404・画面の表示は NotificationServiceNoticeRowTests と NoticeFlowTests で確かめる。
 * </p>
 */
class NoticeControllerTests {

    private static final Long USER_ID = 7L;

    private NotificationService notificationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NoticeController(notificationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        User u = new User();
        u.setId(USER_ID);
        u.setLoginId("staff");
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

    @Test
    void list_自分の100件をnoticesに入れて一覧画面になる() throws Exception {
        List<NoticeRow> rows = List.of(new NoticeRow(1L, NotificationType.CHAT, "チャット", "件名", "本文", null, null,
                true));
        when(notificationService.findLatest(USER_ID, 100)).thenReturn(rows);

        mvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(view().name("common/notices"))
                .andExpect(model().attribute("notices", rows));
        verify(notificationService).findLatest(USER_ID, 100);
    }

    @Test
    void open_Serviceが返した移動先へリダイレクト() throws Exception {
        when(notificationService.open(USER_ID, 5L)).thenReturn("/staff/shifts?periodId=7");

        mvc.perform(post("/notices/5/open"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/shifts?periodId=7"));
        verify(notificationService).open(USER_ID, 5L);
    }

    @Test
    void readAll_すべて既読にして一覧へ戻りI0202() throws Exception {
        mvc.perform(post("/notices/read-all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/notices"))
                .andExpect(flash().attribute("successMessage", "I0202"));
        verify(notificationService).readAll(USER_ID);
    }
}

package jp.numajo.fgroup.shift.controller.staff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ChatService;

/**
 * StaffChatController（SB-06）のテスト（20-SB-06 2.1・2.2、6章 No.1・2）。
 *
 * <p>
 * ChatService はモックにし、MockMvc（standalone）で、画面名・model・リダイレクト先・入力チェックを確かめる。
 * 会話の相手も送る人も、ログイン中のバイト自身のユーザーIDになることを見る。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * send  入力チェックのエラー：あり（再表示。送らない）／なし（送って /staff/chat#latest へ）
 * </pre>
 */
class StaffChatControllerTests {

    private static final Long STAFF_ID = 5L;

    private ChatService chatService;
    private MockMvc mvc;
    private List<ChatMessageView> messages;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StaffChatController(chatService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        User staff = new User();
        staff.setId(STAFF_ID);
        staff.setLoginId("staff");
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        LoginUser loginUser = new LoginUser(staff, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));

        messages = List.of();
        when(chatService.getConversation(STAFF_ID, STAFF_ID)).thenReturn(messages);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 開く_自分の会話と空のフォームを入れてチャット画面() throws Exception {
        mvc.perform(get("/staff/chat"))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/chat"))
                .andExpect(model().attribute("messages", messages))
                .andExpect(model().attributeExists("chatForm"));

        verify(chatService).getConversation(STAFF_ID, STAFF_ID);
    }

    @Test
    void No1_空の本文_入力チェックのエラーで再表示し送らない() throws Exception {
        mvc.perform(post("/staff/chat").param("body", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/chat"))
                .andExpect(model().attributeHasFieldErrors("chatForm", "body"))
                .andExpect(model().attribute("messages", messages));

        verify(chatService, never()).send(any(), any(), any());
    }

    @Test
    void No2_501文字_入力チェックのエラーで再表示し送らない() throws Exception {
        mvc.perform(post("/staff/chat").param("body", "あ".repeat(501)))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/chat"))
                .andExpect(model().attributeHasFieldErrors("chatForm", "body"));

        verify(chatService, never()).send(any(), any(), any());
    }

    @Test
    void No2_500文字_自分の会話に自分から送って一番下へリダイレクト() throws Exception {
        String max = "あ".repeat(500);

        mvc.perform(post("/staff/chat").param("body", max))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/chat#latest"));

        verify(chatService).send(STAFF_ID, STAFF_ID, max);
    }
}

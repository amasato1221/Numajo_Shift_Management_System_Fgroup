package jp.numajo.fgroup.shift.controller.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatListRow;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ChatService;

/**
 * ManagerChatController（SM-10・SM-11）の結合テスト（20-SM-10 2.1、20-SM-11 2.1・2.2、6章 No.5・6）。
 *
 * <p>
 * ChatService はモックにし、MockMvc（standalone）で、画面名・model・リダイレクト先・入力チェックを確かめる。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * send  入力チェックのエラー：あり（再表示。送らない）／なし
 * send  業務エラー（BusinessException）：あり（フラッシュ errorMessage で同じURLへ）／なし（#latest へ）
 *
 * 組み合わせ表：send
 * 判定：A 入力チェックのエラー｜B 業務エラー（A が偽のときだけ評価）
 * No. | A | B | 結果
 *  1  | 真| － | chat 画面を再表示（送らない）
 *  2  | 偽| 真| 同じURLへリダイレクト＋errorMessage（E3101）
 *  3  | 偽| 偽| /manager/chats/{id}#latest へリダイレクト
 * </pre>
 */
class ManagerChatControllerTests {

    private static final Long MANAGER_ID = 1L;
    private static final Long STAFF_ID = 5L;

    private ChatService chatService;
    private MockMvc mvc;
    private User staff;
    private List<ChatMessageView> messages;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ManagerChatController(chatService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        User m = new User();
        m.setId(MANAGER_ID);
        m.setLoginId("manager");
        m.setRole(Role.MANAGER);
        m.setActive(true);
        LoginUser loginUser = new LoginUser(m, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));

        staff = new User();
        staff.setId(STAFF_ID);
        staff.setName("山田 太郎");
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        messages = List.of();
        when(chatService.getStaffForChat(STAFF_ID)).thenReturn(staff);
        when(chatService.getConversation(STAFF_ID, MANAGER_ID)).thenReturn(messages);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 一覧_rowsを入れてチャット一覧の画面() throws Exception {
        List<ChatListRow> rows = List.of(new ChatListRow(5L, "山", "山田 太郎", null, null, 0L));
        when(chatService.getChatList()).thenReturn(rows);

        mvc.perform(get("/manager/chats"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/chat-list"))
                .andExpect(model().attribute("rows", rows));
    }

    @Test
    void 開く_staffとmessagesと空のフォームを入れてチャット画面() throws Exception {
        mvc.perform(get("/manager/chats/5"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/chat"))
                .andExpect(model().attribute("staff", staff))
                .andExpect(model().attribute("messages", messages))
                .andExpect(model().attributeExists("chatForm"));
    }

    @Test
    void No1_空の本文_入力チェックのエラーで再表示し送らない() throws Exception {
        mvc.perform(post("/manager/chats/5").param("body", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/chat"))
                .andExpect(model().attributeHasFieldErrors("chatForm", "body"))
                .andExpect(model().attribute("staff", staff));
        verify(chatService, never()).send(any(), any(), any());
    }

    @Test
    void No1_501文字_入力チェックのエラーで再表示し入力を残す() throws Exception {
        String over = "あ".repeat(501);

        mvc.perform(post("/manager/chats/5").param("body", over))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/chat"))
                .andExpect(model().attributeHasFieldErrors("chatForm", "body"));
        verify(chatService, never()).send(any(), any(), any());
    }

    @Test
    void No3_500文字_送って一番下へリダイレクト() throws Exception {
        String max = "あ".repeat(500);

        mvc.perform(post("/manager/chats/5").param("body", max))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/chats/5#latest"));
        verify(chatService).send(STAFF_ID, MANAGER_ID, max);
    }

    @Test
    void No2_無効なバイトへ送る_E3101をフラッシュに入れて同じURLへ() throws Exception {
        doThrow(new BusinessException("E3101")).when(chatService).send(STAFF_ID, MANAGER_ID, "こんにちは");

        mvc.perform(post("/manager/chats/5").param("body", "こんにちは"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/chats/5"))
                .andExpect(flash().attribute("errorMessage", "E3101"));
    }
}

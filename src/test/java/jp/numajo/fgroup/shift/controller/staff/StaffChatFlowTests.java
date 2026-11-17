package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SB-06 チャット（店長と）の結合テスト（20-SB-06 6章、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・ChatService・テンプレートを使う。テストごとにロールバックする。
 * ほかのテストが作った店長が DB にあっても崩れないように、自分で作った店長（ログインIDが fb で始まる）だけを見る。
 * </p>
 *
 * <pre>
 * No. | 状況                       | 期待結果
 *  1  | 空・空白だけ                | E1601（送らない）
 *  2  | 500文字／501文字            | 送れる／E1602（入力を残して再表示）
 *  3  | 送信                       | 1行増え、有効な店長全員にお知らせ、無効な店長と自分には届かない
 *  4  | 開いたとき                  | 店長からの未読が既読になる、自分の発言の read_at は変わらない
 *  5  | &lt;script&gt; を送る       | 文字のまま表示される
 *  6  | 改行を含む                  | 改行がそのまま入る（吹き出しは white-space: pre-wrap）
 * ほか：0件で I1601、CSRF トークンなしの送信は自分のホームへ戻り増えない、店長は開けない
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class StaffChatFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser staff;
    private LoginUser manager;
    private Long staffId;
    private Long managerId;
    private Long inactiveManagerId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User s = saveUser("fbstaff", "チャット太郎", "ちゃっとたろう", Role.STAFF, true);
        User m = saveUser("fbmgr", "店長A", "てんちょうえー", Role.MANAGER, true);
        staffId = s.getId();
        managerId = m.getId();
        inactiveManagerId = saveUser("fbmgroff", "店長B", "てんちょうびー", Role.MANAGER, false).getId();
        staff = new LoginUser(s, false);
        manager = new LoginUser(m, false);
    }

    // ---------------------------------------------------------------- 準備

    private User saveUser(String loginId, String name, String kana, Role role, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName(name);
        u.setNameKana(kana);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void chat(Long sender, String body, String createdAt) {
        jdbcTemplate.update("INSERT INTO chat_messages (staff_user_id, sender_user_id, body, read_at, created_at) "
                + "VALUES (?, ?, ?, NULL, ?)", staffId, sender, body, createdAt);
    }

    private MvcResult open() throws Exception {
        return mvc.perform(get("/staff/chat").with(user(staff))).andExpect(status().isOk()).andReturn();
    }

    private MvcResult send(String body) throws Exception {
        return mvc.perform(post("/staff/chat").with(csrf()).with(user(staff)).param("body", body)).andReturn();
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessageView> messages(MvcResult result) {
        return (List<ChatMessageView>) result.getModelAndView().getModel().get("messages");
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private int messageCount() {
        return count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ?", staffId);
    }

    // ---------------------------------------------------------------- 表示

    @Test
    void 開く_0件ならI1601で見出しと送信の欄を出す() throws Exception {
        String page = html(open());

        assertThat(page).contains("店長とのチャット", "まだメッセージはありません。", "action=\"/staff/chat\"",
                "maxlength=\"500\"");
    }

    @Test
    void No4_開く_店長からの未読が既読になり自分の発言は変わらない() throws Exception {
        chat(managerId, "店長から", "2000-01-01 10:00:00");
        chat(staffId, "自分から", "2000-01-01 10:05:00");

        MvcResult result = open();

        assertThat(messages(result)).extracting(ChatMessageView::body).containsExactly("店長から", "自分から");
        assertThat(messages(result)).extracting(ChatMessageView::mine).containsExactly(false, true);
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ? AND sender_user_id = ? "
                + "AND read_at IS NOT NULL", staffId, managerId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ? AND sender_user_id = ? "
                + "AND read_at IS NULL", staffId, staffId)).isEqualTo(1);
        assertThat(html(result)).contains("chat-row chat-other", "chat-row chat-mine", "店長A", "1/1 10:00",
                "id=\"latest\"");
    }

    @Test
    void No5_本文にscript_文字のまま表示される() throws Exception {
        chat(staffId, "<script>alert(1)</script>", "2000-01-01 10:00:00");

        String page = html(open());

        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void No6_改行を含む_改行がそのまま吹き出しに入る() throws Exception {
        chat(staffId, "1行目\n2行目", "2000-01-01 10:00:00");

        String page = html(open());

        assertThat(page).contains("1行目\n2行目");
    }

    // ---------------------------------------------------------------- 送信

    @Test
    void No1_空と空白だけ_E1601で送らない() throws Exception {
        MvcResult empty = send("");
        MvcResult blank = send("   ");

        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(empty)).contains("メッセージを入力してください。");
        assertThat(html(blank)).contains("メッセージを入力してください。");
        assertThat(messageCount()).isZero();
    }

    @Test
    void No2_501文字_E1602で入力を残して送らない() throws Exception {
        MvcResult result = send("あ".repeat(501));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(result)).contains("メッセージは500文字以内で入力してください。", "あ".repeat(501));
        assertThat(messageCount()).isZero();
    }

    @Test
    void No2_500文字_送れて一番下へ戻る() throws Exception {
        mvc.perform(post("/staff/chat").with(csrf()).with(user(staff)).param("body", "あ".repeat(500)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/chat#latest"));

        assertThat(messageCount()).isEqualTo(1);
    }

    @Test
    void No3_送信_1行増え有効な店長にだけお知らせ() throws Exception {
        send("シフトの相談です");

        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ? AND sender_user_id = ? "
                + "AND body = 'シフトの相談です'", staffId, staffId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'CHAT' "
                + "AND title = 'チャット太郎さんから新しいメッセージ' AND body = 'シフトの相談です' "
                + "AND link_url = ?", managerId, "/manager/chats/" + staffId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id IN (?, ?) AND type = 'CHAT'",
                inactiveManagerId, staffId)).isZero();
    }

    // ---------------------------------------------------------------- 権限・CSRF

    @Test
    void CSRFトークンなしで送る_自分のホームへ戻り増えない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/staff/chat").with(user(staff)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/staff/chat").with(user(staff)).session(session).param("body", "こんにちは"))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(messageCount()).isZero();
    }

    @Test
    void 店長が開く_自分のホームへ戻る() throws Exception {
        mvc.perform(get("/staff/chat").with(user(manager)))
                .andExpect(redirectedUrl("/manager/home"));
    }
}

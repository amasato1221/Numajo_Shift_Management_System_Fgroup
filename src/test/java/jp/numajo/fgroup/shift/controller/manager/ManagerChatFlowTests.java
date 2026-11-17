package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatListRow;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SM-10 チャット一覧・SM-11 チャット（バイトごと）の結合テスト（20-SM-10 6章、20-SM-11 6章、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service・テンプレートを使う。テストごとにロールバックする。
 * 一覧は 2000年のメッセージと今日のメッセージを混ぜて並びを確かめる。ほかのテストが作ったバイトが DB にあっても
 * 崩れないように、自分で作ったバイト（ログインIDが fc で始まる）だけを見る。
 * </p>
 *
 * <pre>
 * SM-10
 * No. | 状況                                     | 期待結果
 *  1  | 未読3件のバイトと、未読なしでメッセージありのバイト | 未読のバイトが上、バッジ「3」
 *  2  | 店長が送っただけ                             | 未読0（バッジなし）
 *  3  | 別の店長が開いて既読にした                    | 未読0
 *  4  | メッセージがないバイトが2人                    | 「まだメッセージはありません。」、ふりがな順
 *  5  | 無効なバイト：メッセージあり／なし              | 「（無効）」付きで灰色（text-muted）、有効は黒（text-dark）で出る／出ない
 *  6  | 本文に &lt;b&gt;                           | 文字のまま表示される
 *  7  | 有効なバイトが0人                            | I3001
 * SM-11
 *  8  | 店長・管理者のuserIdを staffId に            | 404
 *  9  | 開く                                     | バイトの未読が既読、店長が送ったものの read_at は変わらない
 * 10  | 送信                                     | 1行増え、そのバイトにだけお知らせ（店長には届かない）
 * 11  | 店長Aが送ったものを店長Bが見る                | 右側（chat-mine）に「店長A」の名前付き
 * 12  | 無効なバイト                               | 送信ボタン・入力欄が disabled、W3101。改ざんで送ると E3101で増えない
 * 13  | 空・501文字                               | E1601・E1602（入力を残して再表示）
 * 14  | 500文字                                   | 送れる
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerChatFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser managerA;
    private LoginUser managerB;
    private Long managerAId;
    private Long managerBId;
    private Long staffId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User a = saveUser("fcmgra", "店長A", "てんちょうえー", Role.MANAGER, true);
        User b = saveUser("fcmgrb", "店長B", "てんちょうびー", Role.MANAGER, true);
        managerAId = a.getId();
        managerBId = b.getId();
        managerA = new LoginUser(a, false);
        managerB = new LoginUser(b, false);
        staffId = saveUser("fcstaff", "チャット太郎", "ちゃっとたろう", Role.STAFF, true).getId();
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

    private void chat(Long staff, Long sender, String body, String createdAt, String readAt) {
        jdbcTemplate.update("INSERT INTO chat_messages (staff_user_id, sender_user_id, body, read_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", staff, sender, body, readAt, createdAt);
    }

    private MvcResult open(String url, LoginUser who) throws Exception {
        return mvc.perform(get(url).with(user(who))).andExpect(status().isOk()).andReturn();
    }

    @SuppressWarnings("unchecked")
    private static List<ChatListRow> rows(MvcResult result) {
        return (List<ChatListRow>) result.getModelAndView().getModel().get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessageView> messages(MvcResult result) {
        return (List<ChatMessageView>) result.getModelAndView().getModel().get("messages");
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ChatListRow row(MvcResult result, Long id) {
        return rows(result).stream().filter(r -> r.staffId().equals(id)).findFirst().orElseThrow();
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    // ---------------------------------------------------------------- SM-10

    @Test
    void No1_未読3件のバイトと未読なしのバイト_未読が上でバッジ3_メッセージなしは最後() throws Exception {
        Long read = saveUser("fcstaff2", "既読花子", "きどくはなこ", Role.STAFF, true).getId();
        Long none = saveUser("fcstaff3", "無言次郎", "むごんじろう", Role.STAFF, true).getId();
        chat(read, read, "既読の最後", "2000-01-02 10:00:00", "2000-01-02 11:00:00");
        chat(staffId, staffId, "未読1", "2000-01-01 10:00:00", null);
        chat(staffId, staffId, "未読2", "2000-01-01 10:01:00", null);
        chat(staffId, staffId, "未読3", "2000-01-01 10:02:00", null);

        MvcResult result = open("/manager/chats", managerA);

        List<Long> ids = rows(result).stream().map(ChatListRow::staffId).toList();
        assertThat(ids.indexOf(staffId)).isLessThan(ids.indexOf(read));
        assertThat(ids.indexOf(read)).isLessThan(ids.indexOf(none));
        assertThat(row(result, staffId).unreadCount()).isEqualTo(3L);
        assertThat(row(result, staffId).lastBody()).isEqualTo("未読3");
        assertThat(row(result, staffId).lastAtLabel()).isEqualTo("2000/1/1");
        assertThat(html(result)).contains("チャット太郎", "href=\"/manager/chats/" + staffId + "\"",
                "rounded-pill bg-danger");
    }

    @Test
    void No2_店長が送っただけ_未読0でバッジなし() throws Exception {
        chat(staffId, managerAId, "店長から", "2000-01-01 10:00:00", null);

        MvcResult result = open("/manager/chats", managerA);

        assertThat(row(result, staffId).unreadCount()).isZero();
        assertThat(html(result)).doesNotContain("rounded-pill bg-danger");
    }

    @Test
    void No3_別の店長が開いて既読にした_未読0() throws Exception {
        chat(staffId, staffId, "未読", "2000-01-01 10:00:00", null);
        open("/manager/chats/" + staffId, managerB);

        MvcResult result = open("/manager/chats", managerA);

        assertThat(row(result, staffId).unreadCount()).isZero();
    }

    @Test
    void No4_メッセージがないバイトが2人_メッセージなしの表示でふりがな順() throws Exception {
        Long first = saveUser("fcstaff2", "あおき", "あおきあああ", Role.STAFF, true).getId();
        Long second = saveUser("fcstaff3", "いとう", "いとういいい", Role.STAFF, true).getId();

        MvcResult result = open("/manager/chats", managerA);

        List<Long> ids = rows(result).stream().map(ChatListRow::staffId).toList();
        assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(second));
        assertThat(row(result, first).lastBody()).isNull();
        assertThat(html(result)).contains("まだメッセージはありません。");
    }

    @Test
    void No5_無効なバイト_メッセージありは無効付きで出て_なしは出ない() throws Exception {
        Long withMsg = saveUser("fcstaff2", "有り無効", "ありむこう", Role.STAFF, false).getId();
        Long noMsg = saveUser("fcstaff3", "無し無効", "なしむこう", Role.STAFF, false).getId();
        chat(withMsg, withMsg, "さようなら", "2000-01-01 10:00:00", "2000-01-01 11:00:00");

        MvcResult result = open("/manager/chats", managerA);

        assertThat(row(result, withMsg).staffName()).isEqualTo("有り無効（無効）");
        assertThat(row(result, withMsg).active()).isFalse();
        assertThat(html(result)).contains("chat-list-name text-muted\">有り無効（無効）<")
                .contains("chat-list-name text-dark\">チャット太郎<")
                .doesNotContain("chat-list-name text-muted\">チャット太郎<");
        assertThat(rows(result)).extracting(ChatListRow::staffId).doesNotContain(noMsg);
    }

    @Test
    void No6_本文にタグ_文字のまま表示される() throws Exception {
        chat(staffId, staffId, "<b>強調</b>", "2000-01-01 10:00:00", null);

        String page = html(open("/manager/chats", managerA));

        assertThat(page).contains("&lt;b&gt;強調&lt;/b&gt;").doesNotContain("<b>強調</b>");
    }

    @Test
    void No7_有効なバイトがいない_I3001() throws Exception {
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE role = 'STAFF'");

        MvcResult result = open("/manager/chats", managerA);

        assertThat(rows(result)).isEmpty();
        assertThat(html(result)).contains("バイトが登録されていません。");
    }

    // ---------------------------------------------------------------- SM-11

    @Test
    void No8_店長のuserIdをstaffIdに_404() throws Exception {
        mvc.perform(get("/manager/chats/" + managerBId).with(user(managerA))).andExpect(status().isNotFound());
        mvc.perform(post("/manager/chats/" + managerBId).with(csrf()).with(user(managerA)).param("body", "x"))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ?", managerBId)).isZero();
    }

    @Test
    void No9_開く_バイトの未読が既読になり_店長が送ったものは変わらない() throws Exception {
        chat(staffId, staffId, "バイトから", "2000-01-01 10:00:00", null);
        chat(staffId, managerAId, "店長から", "2000-01-01 10:05:00", null);

        MvcResult result = open("/manager/chats/" + staffId, managerA);

        assertThat(messages(result)).extracting(ChatMessageView::body).containsExactly("バイトから", "店長から");
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE sender_user_id = ? AND read_at IS NOT NULL",
                staffId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE sender_user_id = ? AND read_at IS NULL",
                managerAId)).isEqualTo(1);
        assertThat(html(result)).contains("チャット太郎さんとのチャット", "← 一覧へ", "href=\"/manager/chats\"");
    }

    @Test
    void No10_送信_1行増えてそのバイトにだけお知らせ() throws Exception {
        mvc.perform(post("/manager/chats/" + staffId).with(csrf()).with(user(managerA)).param("body", "お疲れさま"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/chats/" + staffId + "#latest"));

        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ? AND sender_user_id = ?",
                staffId, managerAId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'CHAT' "
                + "AND link_url = '/staff/chat' AND body = 'お疲れさま'", staffId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id IN (?, ?) AND type = 'CHAT'",
                managerAId, managerBId)).isZero();
    }

    @Test
    void No11_店長Aが送ったものを店長Bが見る_右側に店長Aの名前付きで出る() throws Exception {
        chat(staffId, managerAId, "Aからです", "2000-01-01 10:00:00", null);
        chat(staffId, staffId, "バイトからです", "2000-01-01 10:01:00", null);

        MvcResult result = open("/manager/chats/" + staffId, managerB);

        assertThat(messages(result)).extracting(ChatMessageView::mine).containsExactly(true, false);
        assertThat(html(result)).contains("chat-row chat-mine", "chat-row chat-other", "店長A", "チャット太郎",
                "id=\"latest\"");
    }

    @Test
    void No12_無効なバイト_送信できず改ざんして送ってもE3101で増えない() throws Exception {
        Long inactive = saveUser("fcstaff2", "無効さん", "むこうさん", Role.STAFF, false).getId();

        MvcResult result = open("/manager/chats/" + inactive, managerA);

        assertThat(html(result)).contains("無効さん（無効）さんとのチャット", "このスタッフは無効になっています。メッセージは送れません。",
                "disabled");
        mvc.perform(post("/manager/chats/" + inactive).with(csrf()).with(user(managerA)).param("body", "x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/chats/" + inactive))
                .andExpect(flash().attribute("errorMessage", "E3101"));
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ?", inactive)).isZero();
    }

    @Test
    void No13_空と501文字_エラーを出して入力を残し送らない() throws Exception {
        String empty = html(mvc.perform(post("/manager/chats/" + staffId).with(csrf()).with(user(managerA))
                .param("body", "  ")).andExpect(status().isOk()).andReturn());
        String over = html(mvc.perform(post("/manager/chats/" + staffId).with(csrf()).with(user(managerA))
                .param("body", "あ".repeat(501))).andExpect(status().isOk()).andReturn());

        assertThat(empty).contains("メッセージを入力してください。");
        assertThat(over).contains("メッセージは500文字以内で入力してください。", "あ".repeat(501));
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ?", staffId)).isZero();
    }

    @Test
    void No14_500文字_送れる() throws Exception {
        mvc.perform(post("/manager/chats/" + staffId).with(csrf()).with(user(managerA))
                .param("body", "あ".repeat(500)))
                .andExpect(status().is3xxRedirection());

        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE staff_user_id = ?", staffId)).isEqualTo(1);
    }
}

package jp.numajo.fgroup.shift.controller.common;

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
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SC-02 お知らせ一覧の結合テスト（20-SC-02 7章 No.1〜8、94 6章 No.10、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service・テンプレート（common/notices.html・layout/base.html）を使う。
 * テストごとにロールバックする。お知らせは JdbcTemplate で入れ、created_at は 2099年にして並びを決める。
 * </p>
 *
 * <pre>
 * No. | 状況                                         | 期待結果
 *  1  | 0件                                          | I0201、「すべて既読にする」は disabled
 *  2  | 101件ある                                     | 新しい100件だけ（一番古い1件は出ない）
 *  3  | 他人のお知らせIDで open                         | 404（他人の read_at は変わらない）
 *  4  | 未読を open                                    | read_at が入り link_url へ。ヘッダーの未読数が 2→1
 *  5  | 既読を open                                    | read_at は変わらない
 *  6  | link_url が null／https://…／//evil              | /notices へ
 *  7  | すべて既読                                     | 自分の未読だけ既読（他人のは変わらない）、I0202
 *  8  | 本文が100文字ちょうど／101文字                    | そのまま／100文字＋「…」
 * ほか | 未読と既読が混ざる                                | 未読の印・太字のクラス、種類のバッジの色、日時の表示、ボタンは押せる
 * ほか | 店長で開く                                     | 店長も見られる（ログインしている人全員）
 * ほか | ログインしていない                                | ログイン画面へ
 * ほか | CSRF トークンなしで POST                          | 自分のホームへ戻り、既読にならない（94 2.3）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class NoticeFlowTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    private MockMvc mvc;
    private LoginUser staff;
    private Long staffId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User u = saveUser("ntstaff", Role.STAFF);
        staffId = u.getId();
        staff = new LoginUser(u, false);
        otherId = saveUser("ntother", Role.STAFF).getId();
    }

    // ---------------------------------------------------------------- 準備

    private User saveUser(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    /** お知らせを1件入れて ID を返す。 */
    private Long notice(Long userId, String type, String title, String body, String linkUrl, String readAt,
            String createdAt) {
        jdbcTemplate.update("INSERT INTO notifications (user_id, type, title, body, link_url, read_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", userId, type, title, body, linkUrl, readAt, createdAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM notifications", Long.class);
    }

    private Long unread(Long userId, String title, String linkUrl) {
        return notice(userId, "CHAT", title, "本文", linkUrl, null, "2099-03-01 09:00:00");
    }

    /** read_at を DB から読む。open の変更（JPA の変更検知）はコミットまで書かれないので、先に flush する。 */
    private LocalDateTime readAtOf(Long id) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class, id);
    }

    private MvcResult list() throws Exception {
        return mvc.perform(get("/notices").with(user(staff)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private static List<NoticeRow> notices(MvcResult result) {
        return (List<NoticeRow>) result.getModelAndView().getModel().get("notices");
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 初期表示

    @Test
    void No1_0件_I0201ですべて既読は押せない() throws Exception {
        unread(otherId, "他人のお知らせ", "/staff/chat");

        MvcResult result = list();

        assertThat(notices(result)).isEmpty();
        assertThat(html(result)).contains("お知らせはありません。", "<title>お知らせ｜シフト管理システム</title>")
                .containsPattern("<button type=\"submit\" class=\"btn btn-outline-secondary btn-sm\"\\s+disabled")
                .doesNotContain("他人のお知らせ", "notices-table");
    }

    @Test
    void No2_101件ある_新しい100件だけ() throws Exception {
        LocalDateTime base = LocalDateTime.of(2099, 3, 1, 0, 0);
        for (int i = 0; i < 101; i++) {
            notice(staffId, "CHAT", "件名" + i, "本文", "/staff/chat", null, base.plusMinutes(i).toString());
        }

        List<NoticeRow> rows = notices(list());

        assertThat(rows).hasSize(100);
        assertThat(rows.get(0).title()).isEqualTo("件名100");
        assertThat(rows.get(99).title()).isEqualTo("件名1");
    }

    @Test
    void 作った日時が同じ_IDの大きい方が先() throws Exception {
        Long first = notice(staffId, "CHAT", "先に作った", "本文", "/staff/chat", null, "2099-03-01 09:00:00");
        Long second = notice(staffId, "CHAT", "後に作った", "本文", "/staff/chat", null, "2099-03-01 09:00:00");

        assertThat(notices(list())).extracting(NoticeRow::id).containsExactly(second, first);
    }

    @Test
    void 未読と既読と種類_印とバッジと日時を出す() throws Exception {
        notice(staffId, "DEADLINE", "締切の件名", "締切の本文", "/staff/requests?periodId=1", null,
                "2099-03-01 09:05:00");
        notice(staffId, "PUBLISHED", "公開の件名", "公開の本文", "/staff/shifts?periodId=1",
                "2099-03-02 10:00:00", "2099-03-01 09:04:00");
        notice(staffId, "CHANGED", "変更の件名", "変更の本文", "/staff/shifts?periodId=1", "2099-03-02 10:00:00",
                "2099-03-01 09:03:00");
        notice(staffId, "VACANCY", "欠員の件名", "欠員の本文", "/staff/home", "2099-03-02 10:00:00",
                "2099-03-01 09:02:00");
        notice(staffId, "CHAT", "チャットの件名", "<b>本文</b>\n2行目", "/staff/chat", "2099-03-02 10:00:00",
                "2099-03-01 09:01:00");

        MvcResult result = list();

        assertThat(notices(result)).extracting(NoticeRow::unread).containsExactly(true, false, false, false, false);
        String html = html(result);
        assertThat(html).contains("notice-type-deadline", "notice-type-published", "notice-type-changed",
                "notice-type-vacancy", "notice-type-chat", ">締切<", ">公開<", ">変更<", ">欠員<", ">チャット<",
                "2099/03/01 09:05", "2099/03/01 09:01", "notice-unread", ">未読<", "締切の件名",
                "&lt;b&gt;本文&lt;/b&gt;", "action=\"/notices/read-all\"")
                .doesNotContain("<b>本文</b>", "2行目<br")
                .doesNotContainPattern("btn-sm\"\\s+disabled");
        // 未読の行は表とカードで2つずつ（太字のクラス・未読バッジ）
        assertThat(html.split("notice-unread", -1)).hasSize(3);
    }

    @Test
    void No8_本文が100文字ちょうどと101文字_そのままと100文字と三点リーダー() throws Exception {
        notice(staffId, "CHAT", "ちょうど", "あ".repeat(100), "/staff/chat", null, "2099-03-01 09:01:00");
        notice(staffId, "CHAT", "超える", "い".repeat(101), "/staff/chat", null, "2099-03-01 09:00:00");

        MvcResult result = list();

        assertThat(notices(result)).extracting(NoticeRow::body)
                .containsExactly("あ".repeat(100), "い".repeat(100) + "…");
        assertThat(html(result)).contains("い".repeat(100) + "…").doesNotContain("い".repeat(101));
    }

    @Test
    void 店長でも開ける() throws Exception {
        User m = saveUser("ntmanager", Role.MANAGER);
        notice(m.getId(), "CHAT", "店長あて", "本文", "/manager/chats/" + staffId, null, "2099-03-01 09:00:00");

        MvcResult result = mvc.perform(get("/notices").with(user(new LoginUser(m, false))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(html(result)).contains("店長あて");
    }

    @Test
    void ログインしていない_ログイン画面へ() throws Exception {
        mvc.perform(get("/notices")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---------------------------------------------------------------- 開く

    @Test
    void No3_他人のお知らせIDでopen_404で既読にならない() throws Exception {
        Long others = unread(otherId, "他人の", "/staff/chat");

        mvc.perform(post("/notices/{id}/open", others).with(csrf()).with(user(staff)))
                .andExpect(status().isNotFound());

        assertThat(readAtOf(others)).isNull();
    }

    @Test
    void ないお知らせIDでopen_404() throws Exception {
        mvc.perform(post("/notices/{id}/open", 999_999_999L).with(csrf()).with(user(staff)))
                .andExpect(status().isNotFound());
    }

    @Test
    void No4_未読をopen_既読になり移動先へ_ヘッダーの未読数が1減る() throws Exception {
        Long id = unread(staffId, "開く", "/staff/shifts?periodId=7");
        unread(staffId, "そのまま", "/staff/chat");
        assertThat(html(list())).contains("<span class=\"app-bell-count\">2</span>");

        mvc.perform(post("/notices/{id}/open", id).with(csrf()).with(user(staff)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/shifts?periodId=7"));

        assertThat(readAtOf(id)).isNotNull();
        assertThat(html(list())).contains("<span class=\"app-bell-count\">1</span>");
    }

    @Test
    void No5_既読をopen_read_atは変わらない() throws Exception {
        Long id = notice(staffId, "CHAT", "既読", "本文", "/staff/chat", "2099-03-02 10:00:00",
                "2099-03-01 09:00:00");

        mvc.perform(post("/notices/{id}/open", id).with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/chat"));

        assertThat(readAtOf(id)).isEqualTo(LocalDateTime.of(2099, 3, 2, 10, 0));
    }

    @Test
    void No6_link_urlがnull_お知らせ一覧へ() throws Exception {
        Long id = unread(staffId, "リンクなし", null);

        mvc.perform(post("/notices/{id}/open", id).with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/notices"));

        assertThat(readAtOf(id)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://evil.example/", "//evil.example" })
    void No6_link_urlが外のサイト_お知らせ一覧へ(String linkUrl) throws Exception {
        Long id = unread(staffId, "外へ", linkUrl);

        mvc.perform(post("/notices/{id}/open", id).with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/notices"));
    }

    @Test
    void CSRFトークンなしでopen_自分のホームへ戻り既読にならない() throws Exception {
        Long id = unread(staffId, "開く", "/staff/chat");
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/notices").with(user(staff)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/notices/{id}/open", id).with(user(staff)).session(session))
                .andExpect(redirectedUrl("/staff/home"));

        assertThat(readAtOf(id)).isNull();
    }

    // ---------------------------------------------------------------- すべて既読

    @Test
    void No7_すべて既読_自分の未読だけ既読でI0202() throws Exception {
        Long mine1 = unread(staffId, "自分1", "/staff/chat");
        Long mine2 = unread(staffId, "自分2", "/staff/chat");
        Long read = notice(staffId, "CHAT", "既読", "本文", "/staff/chat", "2099-03-02 10:00:00",
                "2099-03-01 09:00:00");
        Long others = unread(otherId, "他人の", "/staff/chat");

        mvc.perform(post("/notices/read-all").with(csrf()).with(user(staff)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/notices"))
                .andExpect(flash().attribute("successMessage", "I0202"));

        assertThat(readAtOf(mine1)).isNotNull();
        assertThat(readAtOf(mine2)).isNotNull();
        assertThat(readAtOf(read)).isEqualTo(LocalDateTime.of(2099, 3, 2, 10, 0));
        assertThat(readAtOf(others)).isNull();
    }

    @Test
    void すべて既読のあと一覧_I0202を出しボタンは押せない() throws Exception {
        unread(staffId, "自分", "/staff/chat");
        mvc.perform(post("/notices/read-all").with(csrf()).with(user(staff)));

        MvcResult result = mvc.perform(get("/notices").with(user(staff)).flashAttr("successMessage", "I0202"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(html(result)).contains("すべて既読にしました。")
                .containsPattern("btn-sm\"\\s+disabled")
                .doesNotContain("notice-unread");
    }
}

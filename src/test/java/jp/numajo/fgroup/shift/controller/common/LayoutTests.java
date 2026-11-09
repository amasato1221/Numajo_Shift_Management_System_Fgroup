package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 共通レイアウト（layout/base.html・fragments）とエラー画面の表示テスト（20-00 3章・4章、10_基本設計書 5.1）。
 *
 * <p>
 * テスト用の画面 test/layout.html（src/test/resources）を、本物の SecurityConfig・GlobalModelAdvice を通して描画し、
 * 出てきた HTML の文字列で確かめる。app.js の動きは総合テストで確かめる（97_テスト計画書 2.3）。
 * </p>
 *
 * <pre>
 * 組み合わせ表1：メニューの出し分け（役割 × showOthersShifts）
 * No. | 役割    | showOthersShifts | 結果
 *  1  | STAFF   | 真               | バイトの8項目（みんなのシフトあり）、役割ラベル「バイト」
 *  2  | STAFF   | 偽               | バイトの7項目（みんなのシフトなし）
 *  3  | MANAGER | 真               | 店長の10項目、役割ラベル「店長」（showOthersShifts は関係しない）
 *  4  | ADMIN   | 真               | 管理者の6項目、役割ラベル「管理者」
 *
 * 判定条件網羅：
 * - ヘッダーの未読数：unreadCount &gt; 0（数字を出す／出さない）
 * - メニューの active：menu の値と一致する項目だけ
 * - メッセージ：successMessage・warningMessage・errorMessage（ある／ない）
 * - バッジ：timeText（ある／ない）
 * - チャット：自分の発言か（右・緑／左・白で送信者名）、0件（I1601）、最後の吹き出しの id="latest"
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class LayoutTests {

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        createUser("ltstaff", "山田 太郎", Role.STAFF);
        createUser("ltmgr", "店長 花子", Role.MANAGER);
        createUser("ltadmin", "管理 次郎", Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        jdbcTemplate.update("UPDATE store_settings SET show_others_shifts = TRUE WHERE id = 1");
    }

    // ---------------------------------------------------------------- 部品

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id IN "
                + "(SELECT id FROM users WHERE login_id LIKE 'lt%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'lt%'");
    }

    private void createUser(String loginId, String name, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName(name);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        userRepository.save(u);
    }

    private LoginUser loginUser(String loginId) {
        return new LoginUser(userRepository.findByLoginId(loginId).orElseThrow(), false);
    }

    private String page(String url, String loginId) throws Exception {
        return mvc.perform(get(url).with(user(loginUser(loginId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 正規表現の1つ目のグループを、出てきた順にすべて返す。 */
    private static List<String> all(String html, String regex) {
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(html);
        while (m.find()) {
            list.add(m.group(1).trim());
        }
        return list;
    }

    /** メニューの項目の文字（表示順）。 */
    private static List<String> menuTexts(String html) {
        return all(html, "<li[^>]*>\\s*<a href=\"[^\"]*\"[^>]*>([^<]*)</a>");
    }

    /** active の項目の文字。 */
    private static List<String> activeTexts(String html) {
        return all(html, "<a href=\"[^\"]*\" class=\"active\">([^<]*)</a>");
    }

    private String message(String code) {
        return context.getMessage(code, null, Locale.JAPAN);
    }

    // ---------------------------------------------------------------- タイトル・ヘッダー

    @Test
    void タイトルは画面名とシステム名() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(html).contains("<title>テスト画面｜シフト管理システム</title>");
        assertThat(html).contains("<h1>テストの本文</h1>");
    }

    @Test
    void ヘッダーに店舗名と名前と頭文字とログアウト() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(all(html, "class=\"app-store[^\"]*\">([^<]*)<")).containsExactly("セブンイレブン 三島駅西通り店");
        assertThat(all(html, "class=\"app-user-name[^\"]*\">([^<]*)<")).containsExactly("山田 太郎");
        assertThat(all(html, "class=\"app-avatar\">([^<]*)<")).containsExactly("山");
        assertThat(all(html, "<form[^>]*action=\"(/logout)\"")).hasSize(2);
        assertThat(html).contains("name=\"_csrf\"");
        assertThat(all(html, "class=\"(stripe)\"")).hasSize(1);
    }

    @Test
    void 未読が0件なら数字を出さない() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(html).doesNotContain("app-bell-count");
    }

    @Test
    void 未読があれば数字を出す() throws Exception {
        jdbcTemplate.update("INSERT INTO notifications (user_id, type, title, body, link_url) "
                + "SELECT id, 'CHAT', 't', 'b', '/staff/chat' FROM users WHERE login_id = 'ltstaff'");
        jdbcTemplate.update("INSERT INTO notifications (user_id, type, title, body, link_url) "
                + "SELECT id, 'CHAT', 't', 'b', '/staff/chat' FROM users WHERE login_id = 'ltstaff'");
        jdbcTemplate.update("INSERT INTO notifications (user_id, type, title, body, link_url, read_at) "
                + "SELECT id, 'CHAT', 't', 'b', '/staff/chat', NOW() FROM users WHERE login_id = 'ltstaff'");

        String html = page("/test/layout", "ltstaff");

        assertThat(all(html, "class=\"app-bell-count\">([^<]*)<")).containsExactly("2");
    }

    // ---------------------------------------------------------------- メニュー

    @Test
    void No1_バイトでほかの人のシフトを表示するなら8項目() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(all(html, "class=\"role-label[^\"]*\">([^<]*)<")).containsExactly("バイト");
        assertThat(html).contains("role-label role-staff");
        assertThat(menuTexts(html)).containsExactly("🏠 ホーム", "📅 シフトカレンダー", "✏ 希望を出す",
                "👥 みんなのシフト", "💬 チャット", "🔔 お知らせ", "⚙ 設定", "👤 アカウント設定");
    }

    @Test
    void No2_バイトでほかの人のシフトを表示しないならみんなのシフトがない() throws Exception {
        jdbcTemplate.update("UPDATE store_settings SET show_others_shifts = FALSE WHERE id = 1");

        String html = page("/test/layout", "ltstaff");

        assertThat(menuTexts(html)).hasSize(7).doesNotContain("👥 みんなのシフト");
    }

    @Test
    void No3_店長は10項目() throws Exception {
        String html = page("/test/layout", "ltmgr");

        assertThat(all(html, "class=\"role-label[^\"]*\">([^<]*)<")).containsExactly("店長");
        assertThat(html).contains("role-label role-manager");
        assertThat(menuTexts(html)).containsExactly("🏠 ダッシュボード", "📅 シフト作成", "⚠ 欠勤登録",
                "👥 スタッフ一覧", "🎓 職能管理", "🔢 必要人数設定", "⚙ 店舗設定", "💬 チャット", "🔔 お知らせ",
                "👤 アカウント設定");
    }

    @Test
    void No4_管理者は6項目() throws Exception {
        String html = page("/test/layout", "ltadmin");

        assertThat(all(html, "class=\"role-label[^\"]*\">([^<]*)<")).containsExactly("管理者");
        assertThat(html).contains("role-label role-admin");
        assertThat(menuTexts(html)).containsExactly("🏠 管理メニュー", "👤 店長アカウント管理", "📄 操作ログ",
                "💾 バックアップ", "🔔 お知らせ", "👤 アカウント設定");
    }

    @Test
    void 今いる画面の項目だけactive() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(activeTexts(html)).containsExactly("🔔 お知らせ");
        assertThat(html).contains("<a href=\"/notices\" class=\"active\">");
    }

    // ---------------------------------------------------------------- メッセージ

    @Test
    void メッセージがなければアラートを出さない() throws Exception {
        String html = page("/test/layout", "ltstaff");

        assertThat(html).doesNotContain("alert-success").doesNotContain("alert-warning")
                .doesNotContain("alert-danger");
    }

    @Test
    void 成功と警告とエラーのメッセージを引数つきで出す() throws Exception {
        String html = mvc.perform(get("/test/layout").with(user(loginUser("ltstaff")))
                .flashAttr("successMessage", "I2901")
                .flashAttr("warningMessage", "W0101")
                .flashAttr("errorMessage", "N0005")
                .flashAttr("errorArgs", new Object[] { "山田" }))
                .andReturn().getResponse().getContentAsString();

        assertThat(all(html, "alert-success\" role=\"alert\">([^<]*)<")).containsExactly(message("I2901"));
        assertThat(all(html, "alert-warning\" role=\"alert\">([^<]*)<")).containsExactly(message("W0101"));
        assertThat(all(html, "alert-danger\" role=\"alert\">([^<]*)<")).containsExactly("山田さんから新しいメッセージ");
    }

    // ---------------------------------------------------------------- 部品（バッジ・チャット）

    @Test
    void バッジは種類のCSSクラスとアイコンと時間() throws Exception {
        String html = page("/test/layout?parts=true", "ltstaff");

        assertThat(all(html, "class=\"shift-badge ([^\"]*)\"")).containsExactly("badge-day", "badge-time");
        assertThat(html).contains("<span class=\"shift-badge badge-day\"><span>☀</span></span>");
        assertThat(html).contains("<span class=\"shift-badge badge-time\"><span>☀☾</span>"
                + "<span class=\"shift-badge-time\">13-22</span></span>");
    }

    @Test
    void チャットは自分が右で相手が左_最後にlatest() throws Exception {
        String html = page("/test/layout?parts=true", "ltstaff");

        assertThat(all(html, "class=\"chat-row (chat-[a-z]+)\"")).containsExactly("chat-other", "chat-mine");
        assertThat(all(html, "class=\"chat-sender\">([^<]*)<")).containsExactly("店長 花子");
        assertThat(all(html, "class=\"chat-bubble\">([^<]*)<")).containsExactly("明日は来られますか", "行けます");
        assertThat(all(html, "class=\"chat-time\">([^<]*)<")).containsExactly("10/15 9:05", "10/15 9:10");
        assertThat(all(html, "class=\"chat-row chat-mine\" id=\"(latest)\"")).hasSize(1);
        assertThat(all(html, "class=\"chat-row chat-other\"( id)")).isEmpty();
    }

    @Test
    void チャットが0件ならI1601() throws Exception {
        String html = page("/test/layout?parts=empty", "ltstaff");

        assertThat(html).contains(message("I1601"));
        assertThat(html).doesNotContain("chat-row");
    }

    // ---------------------------------------------------------------- 入力の空白・エラー画面・トランザクション

    @Test
    void 入力の前後の空白を取る() throws Exception {
        String html = mvc.perform(get("/test/layout").param("keyword", "  山田  ")
                .with(user(loginUser("ltstaff"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<p id=\"trimmed\">[山田]</p>");
    }

    @Test
    void 見つからないときはヘッダーなしの404画面() throws Exception {
        String html = mvc.perform(get("/test/not-found").with(user(loginUser("ltstaff"))))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>ページが見つかりません｜シフト管理システム</title>");
        assertThat(html).contains("ページが見つかりません（404）");
        assertThat(html).contains("href=\"/\"");
        assertThat(html).doesNotContain("app-header");
    }

    @Test
    void 予期しないエラーはヘッダーなしの500画面() throws Exception {
        String html = mvc.perform(get("/test/error").with(user(loginUser("ltstaff"))))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>エラーが発生しました｜シフト管理システム</title>");
        assertThat(html).contains("エラーが発生しました（500）");
        assertThat(html).doesNotContain("app-header");
    }

    @Test
    void お知らせを作るメソッドは業務処理のトランザクションの外では動かない() {
        ShiftPeriod period = new ShiftPeriod();
        User staff = userRepository.findByLoginId("ltstaff").orElseThrow();

        assertThatThrownBy(() -> notificationService.notifyPublished(period))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> notificationService.notifyChanged(staff, period, "本文"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> notificationService.notifyVacancy(List.of(staff), null, "本文"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> notificationService.notifyChat(staff.getId(), staff, "本文"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- テスト用の画面

    /** テスト用の画面のコントローラーを登録する。 */
    @TestConfiguration
    static class Config {

        @Bean
        LayoutTestController layoutTestController() {
            return new LayoutTestController();
        }
    }

    /** テスト用のバッジ（30-07 ShiftBadge と同じ形）。 */
    public record TestBadge(BadgeType type, String icons, String timeText, LocalTime startTime,
            LocalTime endTime) {
    }

    /** テスト用のチャットのメッセージ（30-07 ChatMessageView と同じ形）。 */
    public record TestChatMessage(Long id, String senderName, String body, LocalDateTime createdAt,
            String createdAtLabel, boolean mine) {
    }

    /** 空白の取り方を確かめるためのフォーム。 */
    @Getter
    @Setter
    public static class KeywordForm {
        private String keyword;
    }

    /** テスト用の画面。 */
    @Controller
    static class LayoutTestController {

        @GetMapping("/test/layout")
        String layout(@RequestParam(required = false) String parts, @ModelAttribute KeywordForm form,
                Model model) {
            if ("true".equals(parts)) {
                model.addAttribute("badges", List.of(
                        new TestBadge(BadgeType.DAY, "☀", null, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                        new TestBadge(BadgeType.TIME, "☀☾", "13-22", LocalTime.of(13, 0), LocalTime.of(22, 0))));
                model.addAttribute("messages", List.of(
                        new TestChatMessage(1L, "店長 花子", "明日は来られますか", LocalDateTime.of(2026, 10, 15, 9, 5),
                                "10/15 9:05", false),
                        new TestChatMessage(2L, "山田 太郎", "行けます", LocalDateTime.of(2026, 10, 15, 9, 10),
                                "10/15 9:10", true)));
            } else if ("empty".equals(parts)) {
                model.addAttribute("messages", List.of());
            }
            model.addAttribute("trimmed", "[" + form.getKeyword() + "]");
            return "test/layout";
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new ResourceNotFoundException("test");
        }

        @GetMapping("/test/error")
        String error() {
            throw new IllegalStateException("test");
        }
    }
}

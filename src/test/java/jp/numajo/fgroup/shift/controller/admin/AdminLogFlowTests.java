package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SA-03 操作ログの結合テスト（20-SA-03 6章 No.1〜10、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・Service・Repository・テンプレート（admin/logs.html・layout/base.html）を使う。
 * テストごとにロールバックする。ログは JdbcTemplate で created_at を決めて入れる。ほかのテストのログ（ログインなどは
 * 別トランザクションでコミットされる）と混ざらないよう、今日を使う観点は利用者で絞り、それ以外は 2099年の日付を使う。
 * </p>
 *
 * <pre>
 * No. | 観点                                      | 期待結果
 *  1  | 初期表示（条件なし。利用者だけ絞る）              | 今日の6日前 0:00:00〜今日の分、新しい順。7日前 23:59:59 は入らない
 *  2  | from＝to＝今日                               | 今日の 0:00:00〜23:59:59 の分（前日・翌日は入らない）
 *  3  | 23:59:59 のログ／翌日 0:00:00 のログ              | 入る／入らない
 *  4  | from &gt; to                                   | E4301、0件（I4301）
 *  5  | 367日の範囲                                  | E4302、0件
 *  6  | 利用者と操作の両方で絞り込み                      | 両方に合うものだけ。プルダウンは選んだものが selected
 *  7  | 51件                                       | 2ページ。2ページ目に1件。件数「51件中 1〜50件」、ページ送りは条件を引き継ぐ
 *  8  | page＝99                                    | 最後のページ（2ページ目）
 *  9  | action＝ABC（存在しない）                       | 「すべて」として検索
 * 10  | 利用者が NULL のログ（存在しない ID でのログイン失敗）   | 利用者「－」
 * ほか | 利用者のプルダウン                               | 無効の人も含む、「氏名（ログインID・役割）」
 * ほか | 店長・バイトで開く／ログインしていない                 | 自分のホームへ／ログイン画面へ
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class AdminLogFlowTests {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private LoginUser admin;
    private Long adminId;
    private Long staffId;
    private LoginUser manager;
    private LoginUser staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User a = saveUser("lgadmin", "ろぐかんり", Role.ADMIN, true);
        adminId = a.getId();
        admin = new LoginUser(a, false);
        User s = saveUser("lgstaff", "ろぐばいと", Role.STAFF, true);
        staffId = s.getId();
        staff = new LoginUser(s, false);
        manager = new LoginUser(saveUser("lgmanager", "ろぐてんちょう", Role.MANAGER, true), false);
        saveUser("lgstopped", "ろぐていし", Role.MANAGER, false);
    }

    // ---------------------------------------------------------------- 準備

    private User saveUser(String loginId, String nameKana, Role role, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName("テスト" + loginId);
        u.setNameKana(nameKana);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    /** ログを1件入れる。createdAt は「yyyy-MM-dd HH:mm:ss」。 */
    private void log(Long userId, String action, String target, String createdAt) {
        jdbcTemplate.update("INSERT INTO operation_logs (user_id, action, target, detail, ip_address, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", userId, action, target, "内容", "192.0.2.1", createdAt);
    }

    private MvcResult open(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user(admin)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private static Page<OperationLogRow> logs(MvcResult result) {
        return (Page<OperationLogRow>) result.getModelAndView().getModel().get("logs");
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Tokyo"));
    }

    private static String at(LocalDate date, String time) {
        return date.format(ISO) + " " + time;
    }

    // ---------------------------------------------------------------- 期間

    @Test
    void No1_初期表示は今日を含む7日分を新しい順() throws Exception {
        LocalDate today = today();
        log(adminId, "LOGIN_SUCCESS", "古すぎ", at(today.minusDays(7), "23:59:59"));
        log(adminId, "LOGIN_SUCCESS", "6日前", at(today.minusDays(6), "00:00:00"));
        log(adminId, "LOGIN_SUCCESS", "今日", at(today, "00:00:01"));
        log(adminId, "LOGIN_SUCCESS", "3日前", at(today.minusDays(3), "12:00:00"));

        MvcResult result = open(get("/admin/logs").param("userId", adminId.toString()));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target)
                .containsExactly("今日", "3日前", "6日前");
        assertThat(html(result)).contains("<title>操作ログ｜シフト管理システム</title>",
                "value=\"" + today.minusDays(6).format(ISO) + "\"", "value=\"" + today.format(ISO) + "\"",
                "3件中 1〜3件")
                .doesNotContain("古すぎ", "pagination");
    }

    @Test
    void No2_No3_fromとtoが今日なら今日の0時から23時59分59秒までで前日と翌日は入らない() throws Exception {
        LocalDate today = today();
        log(adminId, "LOGOUT", "前日", at(today.minusDays(1), "23:59:59"));
        log(adminId, "LOGOUT", "今日0時", at(today, "00:00:00"));
        log(adminId, "LOGOUT", "今日23時59分59秒", at(today, "23:59:59"));
        log(adminId, "LOGOUT", "翌日0時", at(today.plusDays(1), "00:00:00"));

        MvcResult result = open(get("/admin/logs").param("from", today.format(ISO)).param("to", today.format(ISO))
                .param("userId", adminId.toString()));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target)
                .containsExactly("今日23時59分59秒", "今日0時");
    }

    @Test
    void No3_toの23時59分59秒は入り翌日0時は入らない() throws Exception {
        log(adminId, "LOGOUT", "入る", "2099-03-01 23:59:59");
        log(adminId, "LOGOUT", "入らない", "2099-03-02 00:00:00");

        MvcResult result = open(get("/admin/logs").param("from", "2099-03-01").param("to", "2099-03-01"));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target).containsExactly("入る");
        assertThat(html(result)).contains("2099/03/01 23:59:59", "ログアウト", "192.0.2.1");
    }

    @Test
    void No4_fromがtoより後ならE4301で0件() throws Exception {
        log(adminId, "LOGOUT", "対象X", "2099-03-01 12:00:00");

        MvcResult result = open(get("/admin/logs").param("from", "2099-03-02").param("to", "2099-03-01"));

        assertThat(logs(result).getTotalElements()).isZero();
        assertThat(html(result)).contains("期間の「から」は「まで」と同じか、より前の日付にしてください。",
                "該当する操作ログはありません。")
                .doesNotContain("対象X");
    }

    @Test
    void No5_367日の範囲ならE4302で0件() throws Exception {
        log(adminId, "LOGOUT", "対象X", "2099-03-01 12:00:00");

        MvcResult result = open(get("/admin/logs").param("from", "2098-02-27").param("to", "2099-03-01"));

        assertThat(logs(result).getTotalElements()).isZero();
        assertThat(html(result)).contains("期間は1年以内にしてください。").doesNotContain("対象X");
    }

    @Test
    void No5_366日の範囲なら検索する() throws Exception {
        log(adminId, "LOGOUT", "対象X", "2099-03-01 12:00:00");

        MvcResult result = open(get("/admin/logs").param("from", "2098-02-28").param("to", "2099-03-01"));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target).containsExactly("対象X");
        assertThat(html(result)).doesNotContain("期間は1年以内にしてください。");
    }

    // ---------------------------------------------------------------- 絞り込み

    @Test
    void No6_利用者と操作の両方に合うものだけ() throws Exception {
        log(adminId, "SHIFT_PUBLISH", "両方に合う", "2099-04-01 10:00:00");
        log(adminId, "LOGOUT", "操作が違う", "2099-04-01 10:00:01");
        log(staffId, "SHIFT_PUBLISH", "利用者が違う", "2099-04-01 10:00:02");

        MvcResult result = open(get("/admin/logs").param("from", "2099-04-01").param("to", "2099-04-01")
                .param("userId", adminId.toString()).param("action", "SHIFT_PUBLISH"));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target).containsExactly("両方に合う");
        assertThat(html(result))
                .containsPattern("<option value=\"" + adminId + "\"\\s+selected=\"selected\">テストlgadmin（lgadmin・管理者）</option>")
                .containsPattern("<option value=\"SHIFT_PUBLISH\"\\s+selected=\"selected\">シフトの公開</option>")
                .contains("シフトの公開", "テストlgadmin");
    }

    @Test
    void 利用者のプルダウンは無効の人も含み氏名とログインIDと役割で出す() throws Exception {
        MvcResult result = open(get("/admin/logs"));

        assertThat(html(result)).contains("テストlgstaff（lgstaff・バイト）", "テストlgmanager（lgmanager・店長）",
                "テストlgstopped（lgstopped・店長）",
                "テストlgadmin（lgadmin・管理者）", "<option value=\"\">すべて</option>", ">ログイン失敗</option>");
    }

    // ---------------------------------------------------------------- ページ送り

    private void log51() {
        for (int i = 0; i < 51; i++) {
            log(adminId, "LOGIN_SUCCESS", "No" + i, String.format("2099-05-01 10:00:%02d", i));
        }
    }

    @Test
    void No7_51件なら2ページで1ページ目は新しい50件_2ページ目は1件() throws Exception {
        log51();

        MvcResult first = open(get("/admin/logs").param("from", "2099-05-01").param("to", "2099-05-01")
                .param("action", "LOGIN_SUCCESS"));
        MvcResult second = open(get("/admin/logs").param("from", "2099-05-01").param("to", "2099-05-01")
                .param("action", "LOGIN_SUCCESS").param("page", "1"));

        assertThat(logs(first).getTotalPages()).isEqualTo(2);
        assertThat(logs(first).getContent()).hasSize(50);
        assertThat(logs(first).getContent().get(0).target()).isEqualTo("No50");
        assertThat(html(first)).contains("51件中 1〜50件", "pagination", ">前へ<", ">次へ<",
                "/admin/logs?from=2099-05-01&amp;to=2099-05-01", "action=LOGIN_SUCCESS&amp;page=1");
        assertThat(logs(second).getContent()).extracting(OperationLogRow::target).containsExactly("No0");
        assertThat(html(second)).contains("51件中 51〜51件");
    }

    @Test
    void No8_page99なら最後のページ() throws Exception {
        log51();

        MvcResult result = open(get("/admin/logs").param("from", "2099-05-01").param("to", "2099-05-01")
                .param("action", "LOGIN_SUCCESS").param("page", "99"));

        assertThat(logs(result).getNumber()).isEqualTo(1);
        assertThat(logs(result).getContent()).extracting(OperationLogRow::target).containsExactly("No0");
    }

    // ---------------------------------------------------------------- 値

    @Test
    void No9_存在しないactionはすべてとして検索する() throws Exception {
        log(adminId, "LOGOUT", "ログアウト", "2099-06-01 10:00:00");
        log(adminId, "SHIFT_PUBLISH", "公開", "2099-06-01 10:00:01");

        MvcResult result = open(get("/admin/logs").param("from", "2099-06-01").param("to", "2099-06-01")
                .param("action", "ABC"));

        assertThat(logs(result).getContent()).extracting(OperationLogRow::target).containsExactly("公開", "ログアウト");
    }

    @Test
    void No10_利用者がNULLのログイン失敗は利用者がハイフン() throws Exception {
        log(null, "LOGIN_FAILURE", "loginId:nobody", "2099-07-01 10:00:00");

        MvcResult result = open(get("/admin/logs").param("from", "2099-07-01").param("to", "2099-07-01"));

        assertThat(logs(result).getContent()).singleElement()
                .satisfies(row -> assertThat(row.userName()).isEqualTo("－"));
        assertThat(html(result)).contains("<td class=\"text-nowrap\">－</td>", "ログイン失敗", "loginId:nobody");
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void 店長が開くと店長のホームへ() throws Exception {
        mvc.perform(get("/admin/logs").with(user(manager))).andExpect(redirectedUrl("/manager/home"));
    }

    @Test
    void バイトが開くとバイトのホームへ() throws Exception {
        mvc.perform(get("/admin/logs").with(user(staff))).andExpect(redirectedUrl("/staff/home"));
    }

    @Test
    void ログインしていなければログイン画面へ() throws Exception {
        mvc.perform(get("/admin/logs")).andExpect(redirectedUrl("/login"));
    }
}

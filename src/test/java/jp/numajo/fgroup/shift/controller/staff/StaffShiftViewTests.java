package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * シフトカレンダー画面（staff/shifts.html）の表示の結合テスト（20-SB-02 6章 No.3〜8、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・PeriodService・ShiftCalendarService・テンプレートを使う。
 * 期間は、今日の月の1日を基準に「2か月前・1か月前・今月・来月・再来月・4か月前」の1日はじまり（15日まで）を、
 * 各テストの前に作り、後に消す（start_date が重複しないよう、同じ開始日の期間は先に消す）。
 * バイトのログインID は「fk」で始め、各テストの前後で消す。{@code @Transactional} は付けない。
 * </p>
 *
 * <pre>
 * 組み合わせ表：希望ボタン（状態 × 提出期限）と I1201（状態）
 * No. | 状態      | 提出期限     | 「希望を出す・直す」 | I1201（未公開の注意）
 *  1  | OPEN      | 未来         | 出る                 | 出る
 *  2  | OPEN      | 過去         | 出ない               | 出る
 *  3  | CLOSED    | 未来         | 出ない               | 出る
 *  4  | PUBLISHED | 未来         | 出ない               | 出ない
 *
 * 各テスト：No.1＝OPEN・期限が明日／No.2＝OPEN・期限が昨日／No.3＝CLOSED／No.4＝PUBLISHED
 * ほか：期間外の日は空白（日付を出さず、is-outside が付く）、存在しない periodId・4か月前の期間は 404
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class StaffShiftViewTests {

    private static final String BUTTON = "希望を出す・直す";
    private static final String I1201 = "この期間はまだ公開されていません。グレーは自分が出した希望です。";

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private final LocalDate base = LocalDate.now().withDayOfMonth(1);

    private MockMvc mvc;
    private LoginUser staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        User u = new User();
        u.setLoginId("fkshiftstaff");
        u.setPasswordHash(passwordEncoder.encode("Passw0rd!Passw0rd!"));
        u.setName("テストバイト");
        u.setRole(Role.STAFF);
        u.setActive(true);
        u.setMustChangePassword(false);
        staff = new LoginUser(userRepository.save(u), false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private List<LocalDate> starts() {
        return List.of(base.minusMonths(4), base.minusMonths(2), base.minusMonths(1), base,
                base.plusMonths(1), base.plusMonths(2));
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'fk%'");
        for (LocalDate start : starts()) {
            jdbcTemplate.update("DELETE FROM shift_periods WHERE start_date = ?", start);
        }
    }

    private ShiftPeriod savePeriod(LocalDate start, PeriodStatus status, LocalDateTime deadline) {
        ShiftPeriod p = new ShiftPeriod();
        p.setStartDate(start);
        p.setEndDate(start.withDayOfMonth(15));
        p.setDeadlineAt(deadline);
        p.setStatus(status);
        return shiftPeriodRepository.save(p);
    }

    private String html(Long periodId) throws Exception {
        return mvc.perform(get("/staff/shifts").param("periodId", String.valueOf(periodId)).with(user(staff)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String word) {
        return text.split(java.util.regex.Pattern.quote(word), -1).length - 1;
    }

    @Test
    void 受付中で期限が明日_希望を出す直すボタンとI1201が出る() throws Exception {
        ShiftPeriod p = savePeriod(base, PeriodStatus.OPEN, LocalDateTime.now().plusDays(1));

        String page = html(p.getId());

        assertThat(page).contains(BUTTON).contains(I1201).contains("受付中").contains("提出期限：");
        assertThat(page).contains("/staff/requests?periodId=" + p.getId());
    }

    @Test
    void 受付中で期限が昨日_希望を出す直すボタンが出ずI1201は出る() throws Exception {
        ShiftPeriod p = savePeriod(base, PeriodStatus.OPEN, LocalDateTime.now().minusDays(1));

        String page = html(p.getId());

        assertThat(page).doesNotContain(BUTTON).contains(I1201);
    }

    @Test
    void 作成中の期間_希望を出す直すボタンが出ずI1201は出る() throws Exception {
        ShiftPeriod p = savePeriod(base, PeriodStatus.CLOSED, LocalDateTime.now().plusDays(1));

        String page = html(p.getId());

        assertThat(page).doesNotContain(BUTTON).contains(I1201).contains("作成中");
    }

    @Test
    void 公開済みの期間_希望を出す直すボタンもI1201も出ない() throws Exception {
        ShiftPeriod p = savePeriod(base, PeriodStatus.PUBLISHED, LocalDateTime.now().plusDays(1));

        String page = html(p.getId());

        assertThat(page).doesNotContain(BUTTON).doesNotContain(I1201).contains("公開済み");
    }

    @Test
    void 期間の途中から終わりまで_期間外の日は空白で期間の15日分だけ日付が出る() throws Exception {
        ShiftPeriod p = savePeriod(base, PeriodStatus.PUBLISHED, LocalDateTime.now().plusDays(1));
        int before = base.getDayOfWeek().getValue() - 1;
        int after = DayOfWeek.SUNDAY.getValue() - base.withDayOfMonth(15).getDayOfWeek().getValue();

        String page = html(p.getId());

        assertThat(count(page, "shift-calendar-day is-outside")).isEqualTo(before + after);
        assertThat(count(page, "class=\"shift-calendar-date")).isEqualTo(15);
    }

    @Test
    void プルダウンの選択肢_範囲内の期間だけが並び表示中の期間が選ばれている() throws Exception {
        ShiftPeriod old = savePeriod(base.minusMonths(4), PeriodStatus.PUBLISHED, LocalDateTime.now().minusMonths(4));
        ShiftPeriod shown = savePeriod(base.minusMonths(1), PeriodStatus.PUBLISHED, LocalDateTime.now().minusDays(20));
        ShiftPeriod next = savePeriod(base.plusMonths(1), PeriodStatus.OPEN, LocalDateTime.now().plusDays(1));

        String page = html(shown.getId());

        assertThat(page).contains("value=\"" + shown.getId() + "\" selected");
        assertThat(page).contains("value=\"" + next.getId() + "\"");
        assertThat(page).doesNotContain("value=\"" + old.getId() + "\"");
    }

    @Test
    void 存在しないperiodId_404になる() throws Exception {
        mvc.perform(get("/staff/shifts").param("periodId", "999999999").with(user(staff)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 四か月前の期間のperiodId_選択肢にないので404になる() throws Exception {
        ShiftPeriod old = savePeriod(base.minusMonths(4), PeriodStatus.PUBLISHED, LocalDateTime.now().minusMonths(4));

        mvc.perform(get("/staff/shifts").param("periodId", String.valueOf(old.getId())).with(user(staff)))
                .andExpect(status().isNotFound());
    }
}

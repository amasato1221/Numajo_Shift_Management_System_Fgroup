package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * NotificationService#sendDeadlineReminders（BT-03 締切のお知らせ）を本物の DB で動かすテスト
 * （95_通知・定期処理設計書 4章・6.3、97_テスト計画書 4.1）。
 *
 * <p>
 * リポジトリの絞り込み（期限の範囲・状態・設定・提出・送信済み）まで確かめるため、Mockito ではなく DB を使う。
 * テストごとにロールバックする。日付は他のデータと重ならないよう、遠い先の日（TODAY）を引数で渡す。
 * お知らせの created_at は DB の既定値（今の日時）になるため、「今日すでに送った」「昨日送った」は
 * created_at を指定して SQL で入れる。
 * </p>
 *
 * <pre>
 * 複数条件網羅（95 6.3 No.1〜16）：4つの条件の組み合わせ 2⁴＝16通り → combinations（CsvSource の行の順が No.）
 *   A 未提出か / B その日を選んでいるか / C 有効なバイトか / D 今日まだ送っていないか
 *   A・B・C・D がすべて真のときだけ1件作る。
 *
 * 条件網羅（有効なバイトか ＝ C1 role == STAFF &amp;&amp; C2 active）：
 * No. | C1 | C2 | 結果                 | テスト
 *  a  | 真 | 真 | 作る                 | combinations No.1
 *  b  | 真 | 偽 | 作らない（無効）     | combinations No.3
 *  c  | 偽 | 真 | 作らない（店長）     | No.17
 *  d  | 偽 | 偽 | 作らない（無効な店長）| No.17-2
 *
 * 判定条件網羅（そのほか）：
 *  - その日が期限の受付中の期間がない → 次の d へ（d＝2 を含め、ほとんどのテストの d＝0・3・7）
 *  - 期間はあるが、その日を選んでいる人がいない → 次の d へ（No.23）
 *  - 期限の範囲（0・1・3・7 日後のその日の 0:00〜23:59:59）→ No.18、No.24（範囲の境目）
 *  - 期間が CLOSED → No.19
 *  - 昨日送った人 → No.20、本文・リンク → No.21、提出を取り消した人 → No.22
 *  - 同じ条件で2回呼ぶと2回目は 0 件 → No.1-2
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class NotificationServiceDeadlineTests {

    /** 今日（テストで渡す日）。他のテストのデータと重ならない遠い先の日にする。 */
    private static final LocalDate TODAY = LocalDate.of(2031, 5, 10);

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------- 95 6.3 No.1〜16

    @ParameterizedTest(name = "No.{index} 未提出={0} 選んでいる={1} 有効なバイト={2} 今日まだ送っていない={3} → {4}件")
    @CsvSource({
            "true,  true,  true,  true,  1",
            "true,  true,  true,  false, 0",
            "true,  true,  false, true,  0",
            "true,  true,  false, false, 0",
            "true,  false, true,  true,  0",
            "true,  false, true,  false, 0",
            "true,  false, false, true,  0",
            "true,  false, false, false, 0",
            "false, true,  true,  true,  0",
            "false, true,  true,  false, 0",
            "false, true,  false, true,  0",
            "false, true,  false, false, 0",
            "false, false, true,  true,  0",
            "false, false, true,  false, 0",
            "false, false, false, true,  0",
            "false, false, false, false, 0",
    })
    void combinations_4つの条件がすべて真のときだけ作る(boolean unsubmitted, boolean selected, boolean activeStaff,
            boolean notSentToday, int expected) {
        ShiftPeriod period = savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User staff = saveUser("bt03-c", Role.STAFF, activeStaff);
        // 選んでいない人は、期間のない「7日前」を選んでいる
        saveSetting(staff, selected ? 1 : 7);
        if (!unsubmitted) {
            saveSubmission(period, staff);
        }
        if (!notSentToday) {
            saveDeadlineNotice(staff, period, TODAY.atTime(9, 0));
        }
        long before = countDeadline(staff);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(expected);
        assertThat(countDeadline(staff)).isEqualTo(before + expected);
    }

    @Test
    void No1_2_同じ日に2回呼ぶと2回目は作らない() {
        ShiftPeriod period = savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User staff = saveUser("bt03-twice", Role.STAFF, true);
        saveSetting(staff, 1);

        int first = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();
        // 1回目のお知らせを「今日 9:00 に作った」ことにする（DB の既定値は実際の今の日時のため）
        entityManager.createNativeQuery("UPDATE notifications SET created_at = ? WHERE user_id = ?")
                .setParameter(1, TODAY.atTime(9, 0)).setParameter(2, staff.getId()).executeUpdate();
        int second = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(deadlineLinks(staff)).containsExactly(link(period));
    }

    // ---------------------------------------------------------------- 95 6.3 No.17〜22

    @Test
    void No17_有効な店長は設定の行があっても作らない() {
        savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User manager = saveUser("bt03-mgr", Role.MANAGER, true);
        saveSetting(manager, 1);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isZero();
        assertThat(countDeadline(manager)).isZero();
    }

    @Test
    void No17_2_無効な店長も作らない() {
        savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User manager = saveUser("bt03-mgr2", Role.MANAGER, false);
        saveSetting(manager, 1);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isZero();
        assertThat(countDeadline(manager)).isZero();
    }

    @Test
    void No18_0日1日3日7日後が期限の期間にそれぞれ作り2日後には作らない() {
        ShiftPeriod d0 = savePeriod(LocalDate.of(2031, 6, 1), TODAY, PeriodStatus.OPEN);
        ShiftPeriod d1 = savePeriod(LocalDate.of(2031, 6, 16), TODAY.plusDays(1), PeriodStatus.OPEN);
        ShiftPeriod d2 = savePeriod(LocalDate.of(2031, 7, 1), TODAY.plusDays(2), PeriodStatus.OPEN);
        ShiftPeriod d3 = savePeriod(LocalDate.of(2031, 7, 16), TODAY.plusDays(3), PeriodStatus.OPEN);
        ShiftPeriod d7 = savePeriod(LocalDate.of(2031, 8, 1), TODAY.plusDays(7), PeriodStatus.OPEN);
        User staff = saveUser("bt03-days", Role.STAFF, true);
        saveSetting(staff, 0, 1, 3, 7);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(4);
        assertThat(deadlineLinks(staff)).containsExactlyInAnyOrder(link(d0), link(d1), link(d3), link(d7))
                .doesNotContain(link(d2));
    }

    @Test
    void No19_期間がCLOSEDなら作らない() {
        savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.CLOSED);
        User staff = saveUser("bt03-closed", Role.STAFF, true);
        saveSetting(staff, 1);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isZero();
        assertThat(countDeadline(staff)).isZero();
    }

    @Test
    void No20_昨日送った人にも今日また作る() {
        ShiftPeriod period = savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User staff = saveUser("bt03-yday", Role.STAFF, true);
        saveSetting(staff, 1);
        // 同じ期間のお知らせを、昨日の 23:59:59（今日の 0:00 の1秒前）に送っていた
        saveDeadlineNotice(staff, period, TODAY.minusDays(1).atTime(23, 59, 59));

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(1);
        assertThat(countDeadline(staff)).isEqualTo(2);
    }

    @Test
    void No21_件名と本文とリンク() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(LocalDate.of(2026, 11, 16));
        period.setEndDate(LocalDate.of(2026, 11, 30));
        period.setDeadlineAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        period.setStatus(PeriodStatus.OPEN);
        entityManager.persist(period);
        User staff = saveUser("bt03-body", Role.STAFF, true);
        saveSetting(staff, 1);

        int count = notificationService.sendDeadlineReminders(today);
        flushAndClear();

        assertThat(count).isEqualTo(1);
        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT title, body, link_url, read_at FROM notifications WHERE user_id = ? AND type = 'DEADLINE'")
                .setParameter(1, staff.getId()).getSingleResult();
        assertThat(row[0]).isEqualTo("シフト希望の提出期限が近づいています");
        assertThat(row[1]).isEqualTo("11/16〜11/30 の希望がまだ提出されていません。期限は 2026/10/31 23:59 です。");
        assertThat(row[2]).isEqualTo("/staff/requests?periodId=" + period.getId());
        assertThat(row[3]).isNull();
    }

    @Test
    void No22_提出を取り消した人には作る() {
        ShiftPeriod period = savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User staff = saveUser("bt03-cancel", Role.STAFF, true);
        saveSetting(staff, 1);
        saveSubmission(period, staff);
        // 提出の取り消し＝request_submissions の行を消す（20-SB-03）
        entityManager.createNativeQuery("DELETE FROM request_submissions WHERE period_id = ? AND user_id = ?")
                .setParameter(1, period.getId()).setParameter(2, staff.getId()).executeUpdate();

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(1);
        assertThat(countDeadline(staff)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 判定条件網羅のための追加

    @Test
    void No23_期間はあるがその日を選んでいる人がいなければ作らない() {
        savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        User staff = saveUser("bt03-none", Role.STAFF, true);
        // 設定の行がない（SB-05 で全部外した）

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isZero();
        assertThat(countDeadline(staff)).isZero();
    }

    @Test
    void No24_期限の範囲はその日の0時0分0秒から23時59分59秒まで() {
        LocalDate target = TODAY.plusDays(1);
        ShiftPeriod first = savePeriodAt(LocalDate.of(2031, 6, 1), target.atStartOfDay());
        ShiftPeriod last = savePeriodAt(LocalDate.of(2031, 6, 16), target.atTime(23, 59, 59));
        ShiftPeriod before = savePeriodAt(LocalDate.of(2031, 7, 1), target.minusDays(1).atTime(23, 59, 59));
        ShiftPeriod after = savePeriodAt(LocalDate.of(2031, 7, 16), target.plusDays(1).atStartOfDay());
        User staff = saveUser("bt03-range", Role.STAFF, true);
        saveSetting(staff, 1);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(2);
        assertThat(deadlineLinks(staff)).containsExactlyInAnyOrder(link(first), link(last))
                .doesNotContain(link(before), link(after));
    }

    @Test
    void No25_複数の人と複数の期間では未提出の組み合わせだけ作る() {
        ShiftPeriod p1 = savePeriod(LocalDate.of(2031, 6, 1), TODAY.plusDays(1), PeriodStatus.OPEN);
        ShiftPeriod p2 = savePeriod(LocalDate.of(2031, 6, 16), TODAY.plusDays(1), PeriodStatus.OPEN);
        User a = saveUser("bt03-a", Role.STAFF, true);
        User b = saveUser("bt03-b", Role.STAFF, true);
        saveSetting(a, 1);
        saveSetting(b, 1);
        saveSubmission(p1, a);

        int count = notificationService.sendDeadlineReminders(TODAY);
        flushAndClear();

        assertThat(count).isEqualTo(3);
        assertThat(deadlineLinks(a)).containsExactly(link(p2));
        assertThat(deadlineLinks(b)).containsExactlyInAnyOrder(link(p1), link(p2));
    }

    // ---------------------------------------------------------------- テストデータを作る道具

    private User saveUser(String loginId, Role role, boolean active) {
        User user = new User();
        user.setLoginId(loginId);
        user.setPasswordHash("dummy-hash");
        user.setName("締切 太郎");
        user.setRole(role);
        user.setActive(active);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    /** 期限が deadlineDate の 23:59:59 の期間（終了日は開始日の14日後）。 */
    private ShiftPeriod savePeriod(LocalDate startDate, LocalDate deadlineDate, PeriodStatus status) {
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(startDate);
        period.setEndDate(startDate.plusDays(14));
        period.setDeadlineAt(deadlineDate.atTime(LocalTime.of(23, 59, 59)));
        period.setStatus(status);
        entityManager.persist(period);
        return period;
    }

    /** 期限の日時を指定した受付中の期間。 */
    private ShiftPeriod savePeriodAt(LocalDate startDate, LocalDateTime deadlineAt) {
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(startDate);
        period.setEndDate(startDate.plusDays(14));
        period.setDeadlineAt(deadlineAt);
        period.setStatus(PeriodStatus.OPEN);
        entityManager.persist(period);
        return period;
    }

    private void saveSetting(User user, int... daysBefore) {
        for (int d : daysBefore) {
            entityManager.createNativeQuery("INSERT INTO notification_settings (user_id, days_before) VALUES (?, ?)")
                    .setParameter(1, user.getId()).setParameter(2, d).executeUpdate();
        }
    }

    private void saveSubmission(ShiftPeriod period, User user) {
        entityManager.flush();
        entityManager.createNativeQuery(
                "INSERT INTO request_submissions (period_id, user_id, submitted_at) VALUES (?, ?, ?)")
                .setParameter(1, period.getId()).setParameter(2, user.getId())
                .setParameter(3, TODAY.minusDays(1).atTime(12, 0)).executeUpdate();
    }

    /** 送ったことにする DEADLINE のお知らせ（created_at を指定する）。 */
    private void saveDeadlineNotice(User user, ShiftPeriod period, LocalDateTime createdAt) {
        entityManager.flush();
        entityManager.createNativeQuery("INSERT INTO notifications (user_id, type, title, body, link_url, created_at)"
                + " VALUES (?, 'DEADLINE', '前に送った', NULL, ?, ?)")
                .setParameter(1, user.getId()).setParameter(2, link(period)).setParameter(3, createdAt)
                .executeUpdate();
    }

    private long countDeadline(User user) {
        entityManager.flush();
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'DEADLINE'")
                .setParameter(1, user.getId()).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> deadlineLinks(User user) {
        return entityManager.createNativeQuery(
                "SELECT link_url FROM notifications WHERE user_id = ? AND type = 'DEADLINE' ORDER BY id")
                .setParameter(1, user.getId()).getResultList();
    }

    private static String link(ShiftPeriod period) {
        return "/staff/requests?periodId=" + period.getId();
    }

    /** 書き込みを DB へ出し、キャッシュを捨てる。 */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.entity.CalendarFeed;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * NotificationSettingService・CalendarFeedService を本物の DB で動かす統合テスト（97_テスト計画書 4.1）。
 *
 * <p>
 * 単体テスト（NotificationSettingServiceTests・CalendarFeedServiceTests）では確かめられない、
 * トランザクションの伝播・一意制約・リポジトリの絞り込み（20-IC-01 6章 No.3〜7）を確かめる。
 * テストごとにロールバックする。日付は今日（日本時間）からの相対で作る。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class NotificationCalendarServiceIntegrationTests {

    private final NotificationSettingService notificationSettingService;
    private final CalendarFeedService calendarFeedService;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Tokyo"));
    }

    // ---------------------------------------------------------------- NotificationSettingService

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createDefault_業務処理のトランザクションの外では動かない() {
        assertThatThrownBy(() -> notificationSettingService.createDefault(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void createDefault_登録直後は前日と3日前() {
        User staff = saveUser("it-ns1", true);

        notificationSettingService.createDefault(staff.getId());
        flushAndClear();

        assertThat(notificationSettingService.findDays(staff.getId())).containsExactly(1, 3);
    }

    @Test
    void save_当日と7日前に入れ替え_全部外すと0件() {
        User staff = saveUser("it-ns2", true);
        notificationSettingService.createDefault(staff.getId());
        flushAndClear();

        notificationSettingService.save(staff.getId(), List.of(7, 0, 7));
        flushAndClear();
        assertThat(notificationSettingService.findDays(staff.getId())).containsExactly(0, 7);

        notificationSettingService.save(staff.getId(), List.of());
        flushAndClear();
        assertThat(notificationSettingService.findDays(staff.getId())).isEmpty();
    }

    // ---------------------------------------------------------------- CalendarFeedService

    @Test
    void getOrCreate_43文字のトークンを作り2回目は同じ行() {
        User staff = saveUser("it-cf1", true);

        CalendarFeed first = calendarFeedService.getOrCreate(staff.getId());
        flushAndClear();
        CalendarFeed second = calendarFeedService.getOrCreate(staff.getId());

        assertThat(first.getToken()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(second.getToken()).isEqualTo(first.getToken());
        assertThat(second.getCreatedAt()).isNotNull();
    }

    @Test
    void reissue_古いトークンは404で新しいトークンは読める() {
        User staff = saveUser("it-cf2", true);
        String oldToken = calendarFeedService.getOrCreate(staff.getId()).getToken();
        flushAndClear();

        String newToken = calendarFeedService.reissue(staff.getId()).getToken();
        flushAndClear();

        assertThat(newToken).isNotEqualTo(oldToken).matches("^[A-Za-z0-9_-]{43}$");
        assertThatThrownBy(() -> calendarFeedService.buildIcs(oldToken)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(calendarFeedService.buildIcs(newToken)).startsWith("BEGIN:VCALENDAR\r\n");
    }

    @Test
    void buildIcs_存在しないトークンは404() {
        assertThatThrownBy(() -> calendarFeedService.buildIcs("z".repeat(43)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void buildIcs_無効なユーザーのトークンは404() {
        User staff = saveUser("it-cf3", false);
        String token = calendarFeedService.getOrCreate(staff.getId()).getToken();
        flushAndClear();

        assertThatThrownBy(() -> calendarFeedService.buildIcs(token)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void buildIcs_公開済み_出勤_31日前以降だけを日付順に出す() {
        User staff = saveUser("it-cf4", true);
        String token = calendarFeedService.getOrCreate(staff.getId()).getToken();
        LocalDate t = today();
        ShiftPeriod published = savePeriod(t.minusDays(40), PeriodStatus.PUBLISHED);
        ShiftPeriod closed = savePeriod(t.minusDays(39), PeriodStatus.CLOSED);
        Long d32 = saveAssignment(published, staff, t.minusDays(32), AssignmentStatus.ASSIGNED);
        Long d31 = saveAssignment(published, staff, t.minusDays(31), AssignmentStatus.ASSIGNED);
        Long today = saveAssignment(published, staff, t, AssignmentStatus.ASSIGNED);
        Long absent = saveAssignment(published, staff, t.plusDays(1), AssignmentStatus.ABSENT);
        Long unpublished = saveAssignment(closed, staff, t.plusDays(2), AssignmentStatus.ASSIGNED);
        flushAndClear();

        String ics = calendarFeedService.buildIcs(token);

        assertThat(ics).doesNotContain("UID:shift-" + d32 + "@");
        assertThat(ics).contains("UID:shift-" + d31 + "@");
        assertThat(ics).contains("UID:shift-" + today + "@");
        assertThat(ics).doesNotContain("UID:shift-" + absent + "@");
        assertThat(ics).doesNotContain("UID:shift-" + unpublished + "@");
        assertThat(ics.indexOf("UID:shift-" + d31 + "@")).isLessThan(ics.indexOf("UID:shift-" + today + "@"));
        assertThat(ics).contains("SUMMARY:日勤 8:00-17:00\r\n");
        assertThat(ics).contains("X-WR-CALNAME:シフト（結合 太郎）\r\n");
    }

    // ---------------------------------------------------------------- テストデータを作る道具

    private User saveUser(String loginId, boolean active) {
        User user = new User();
        user.setLoginId(loginId);
        user.setPasswordHash("dummy-hash");
        user.setName("結合 太郎");
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private ShiftPeriod savePeriod(LocalDate startDate, PeriodStatus status) {
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(startDate);
        period.setEndDate(startDate.plusDays(60));
        period.setDeadlineAt(LocalDateTime.of(startDate.minusDays(10), LocalTime.of(23, 59)));
        period.setStatus(status);
        entityManager.persist(period);
        return period;
    }

    private Long saveAssignment(ShiftPeriod period, User user, LocalDate workDate, AssignmentStatus status) {
        ShiftAssignment assignment = new ShiftAssignment();
        assignment.setPeriod(period);
        assignment.setUser(user);
        assignment.setWorkDate(workDate);
        assignment.setSlot(entityManager.getReference(Slot.class, SlotIds.DAY));
        assignment.setStartTime(LocalTime.of(8, 0));
        assignment.setEndTime(LocalTime.of(17, 0));
        assignment.setStatus(status);
        entityManager.persist(assignment);
        return assignment.getId();
    }

    /** 書き込みを DB へ出し、キャッシュを捨てる。 */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

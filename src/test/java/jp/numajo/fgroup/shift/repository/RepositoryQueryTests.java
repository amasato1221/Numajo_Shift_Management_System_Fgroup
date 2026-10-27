package jp.numajo.fgroup.shift.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import lombok.RequiredArgsConstructor;

/**
 * リポジトリの @Query の統合テスト（97_テスト計画書 4.1、30-03）。
 *
 * <p>
 * shift_test のDBに実際にSQLを流す。テストごとにロールバックするので、順番に関係なく動く。
 * 今回は UserRepository（searchStaff・findStaffForPeriod・findActiveById）と
 * ShiftAssignmentRepository（findNextShifts・findByUserIdAndWorkDateBetween・countAssignedFrom）を確かめる。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class RepositoryQueryTests {

    /** テストで「今日」とする日（固定Clockの 2026-10-20 に合わせる）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final UserRepository userRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------- UserRepository ----------

    @Test
    void searchStaff_有効指定_停止中と管理者と店長を含まない() {
        saveUser("t-active", "有効さん", "ゆうこう", Role.STAFF, true);
        saveUser("t-stop", "停止さん", "ていし", Role.STAFF, false);
        saveUser("t-mgr", "店長さん", "てんちょう", Role.MANAGER, true);
        flushAndClear();

        List<User> actual = userRepository.searchStaff(null, true);

        assertThat(actual).extracting(User::getLoginId).containsExactly("t-active");
    }

    @Test
    void searchStaff_状態がnull_有効と停止の両方が出る() {
        saveUser("t-active", "有効さん", "ゆうこう", Role.STAFF, true);
        saveUser("t-stop", "停止さん", "ていし", Role.STAFF, false);
        flushAndClear();

        List<User> actual = userRepository.searchStaff(null, null);

        assertThat(actual).extracting(User::getLoginId).containsExactlyInAnyOrder("t-active", "t-stop");
    }

    @Test
    void searchStaff_ふりがな順でふりがななしは最後() {
        saveUser("t-c", "三郎", "さぶろう", Role.STAFF, true);
        saveUser("t-a", "一郎", "いちろう", Role.STAFF, true);
        saveUser("t-n", "名無し", null, Role.STAFF, true);
        saveUser("t-b", "二郎", "じろう", Role.STAFF, true);
        flushAndClear();

        List<User> actual = userRepository.searchStaff(null, true);

        assertThat(actual).extracting(User::getLoginId).containsExactly("t-a", "t-c", "t-b", "t-n");
    }

    @Test
    void searchStaff_氏名とふりがなとログインIDのどれに一致しても出る() {
        saveUser("t-name", "山田太郎", "あああ", Role.STAFF, true);
        saveUser("t-kana", "佐藤", "やまだ", Role.STAFF, true);
        saveUser("t-login", "鈴木", "いいい", Role.STAFF, true);
        saveUser("zz-yama", "高橋", "ううう", Role.STAFF, true);
        flushAndClear();

        List<User> byName = userRepository.searchStaff("%山田%", true);
        List<User> byKana = userRepository.searchStaff("%やまだ%", true);
        List<User> byLoginId = userRepository.searchStaff("%zz-%", true);

        assertThat(byName).extracting(User::getLoginId).containsExactly("t-name");
        assertThat(byKana).extracting(User::getLoginId).containsExactly("t-kana");
        assertThat(byLoginId).extracting(User::getLoginId).containsExactly("zz-yama");
    }

    @Test
    void searchStaff_エスケープしたパーセントは文字として検索される() {
        saveUser("t-pct", "100%くん", "ひゃく", Role.STAFF, true);
        saveUser("t-plain", "100さん", "ひゃくさん", Role.STAFF, true);
        flushAndClear();

        List<User> actual = userRepository.searchStaff("%100!%%", true);

        assertThat(actual).extracting(User::getLoginId).containsExactly("t-pct");
    }

    @Test
    void findStaffForPeriod_停止中でもその期間に割当があれば含み_ない人は含まない() {
        User working = saveUser("t-stop-work", "停止出勤", "あ", Role.STAFF, false);
        saveUser("t-stop-none", "停止なし", "い", Role.STAFF, false);
        saveUser("t-active", "有効さん", "う", Role.STAFF, true);
        ShiftPeriod period = savePeriod(LocalDate.of(2026, 11, 1), PeriodStatus.PUBLISHED);
        saveAssignment(period, working, LocalDate.of(2026, 11, 2), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        flushAndClear();

        List<User> actual = userRepository.findStaffForPeriod(period.getId());

        assertThat(actual).extracting(User::getLoginId).containsExactly("t-stop-work", "t-active");
    }

    @Test
    void findActiveById_有効ならtrue_停止中ならfalse_いなければ空() {
        User active = saveUser("t-active", "有効さん", "あ", Role.STAFF, true);
        User stopped = saveUser("t-stop", "停止さん", "い", Role.STAFF, false);
        flushAndClear();

        assertThat(userRepository.findActiveById(active.getId())).contains(true);
        assertThat(userRepository.findActiveById(stopped.getId())).contains(false);
        assertThat(userRepository.findActiveById(-1L)).isEmpty();
    }

    // ---------- ShiftAssignmentRepository ----------

    @Test
    void findNextShifts_今日の終了時刻ちょうどは含まず_その1分前は含む() {
        User staff = saveUser("t-staff", "出勤さん", "あ", Role.STAFF, true);
        ShiftPeriod period = savePeriod(LocalDate.of(2026, 10, 1), PeriodStatus.PUBLISHED);
        saveAssignment(period, staff, TODAY, "09:00", "17:00", AssignmentStatus.ASSIGNED);
        flushAndClear();

        List<ShiftAssignment> atEnd = assignmentRepository.findNextShifts(
                staff.getId(), TODAY, LocalTime.of(17, 0), PageRequest.of(0, 1));
        List<ShiftAssignment> beforeEnd = assignmentRepository.findNextShifts(
                staff.getId(), TODAY, LocalTime.of(16, 59), PageRequest.of(0, 1));

        assertThat(atEnd).isEmpty();
        assertThat(beforeEnd).hasSize(1);
    }

    @Test
    void findNextShifts_一番近い1件だけ返し_欠勤と未公開の期間は除く() {
        User staff = saveUser("t-staff", "出勤さん", "あ", Role.STAFF, true);
        ShiftPeriod published = savePeriod(LocalDate.of(2026, 10, 1), PeriodStatus.PUBLISHED);
        ShiftPeriod closed = savePeriod(LocalDate.of(2026, 11, 1), PeriodStatus.CLOSED);
        saveAssignment(published, staff, TODAY.plusDays(3), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(published, staff, TODAY.plusDays(1), "09:00", "17:00", AssignmentStatus.ABSENT);
        saveAssignment(published, staff, TODAY.plusDays(2), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(closed, staff, TODAY.plusDays(1), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        flushAndClear();

        List<ShiftAssignment> actual = assignmentRepository.findNextShifts(
                staff.getId(), TODAY, LocalTime.of(0, 0), PageRequest.of(0, 1));

        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).getWorkDate()).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    void findByUserIdAndWorkDateBetween_両端の日付を含み_範囲外と他の人を含まない() {
        User staff = saveUser("t-staff", "出勤さん", "あ", Role.STAFF, true);
        User other = saveUser("t-other", "他の人", "い", Role.STAFF, true);
        ShiftPeriod period = savePeriod(LocalDate.of(2026, 10, 1), PeriodStatus.PUBLISHED);
        LocalDate from = LocalDate.of(2026, 10, 10);
        LocalDate to = LocalDate.of(2026, 10, 20);
        saveAssignment(period, staff, from.minusDays(1), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, from, "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, to, "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, to.plusDays(1), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, other, from, "09:00", "17:00", AssignmentStatus.ASSIGNED);
        flushAndClear();

        List<ShiftAssignment> actual = assignmentRepository.findByUserIdAndWorkDateBetween(staff.getId(), from, to);

        assertThat(actual).extracting(ShiftAssignment::getWorkDate).containsExactly(from, to);
    }

    @Test
    void countAssignedFrom_今日を含む今日以降の出勤だけ数える() {
        User staff = saveUser("t-staff", "出勤さん", "あ", Role.STAFF, true);
        ShiftPeriod period = savePeriod(LocalDate.of(2026, 10, 1), PeriodStatus.PUBLISHED);
        saveAssignment(period, staff, TODAY.minusDays(1), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, TODAY, "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, TODAY.plusDays(1), "09:00", "17:00", AssignmentStatus.ASSIGNED);
        saveAssignment(period, staff, TODAY.plusDays(2), "09:00", "17:00", AssignmentStatus.ABSENT);
        flushAndClear();

        long actual = assignmentRepository.countAssignedFrom(staff.getId(), TODAY);

        assertThat(actual).isEqualTo(2);
    }

    // ---------- テストデータを作る道具 ----------

    private User saveUser(String loginId, String name, String nameKana, Role role, boolean active) {
        User user = new User();
        user.setLoginId(loginId);
        user.setPasswordHash("dummy-hash");
        user.setName(name);
        user.setNameKana(nameKana);
        user.setRole(role);
        user.setActive(active);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private ShiftPeriod savePeriod(LocalDate startDate, PeriodStatus status) {
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(startDate);
        period.setEndDate(startDate.plusDays(13));
        period.setDeadlineAt(LocalDateTime.of(startDate.minusDays(10), LocalTime.of(23, 59)));
        period.setStatus(status);
        entityManager.persist(period);
        return period;
    }

    private void saveAssignment(ShiftPeriod period, User user, LocalDate workDate, String start, String end,
            AssignmentStatus status) {
        ShiftAssignment assignment = new ShiftAssignment();
        assignment.setPeriod(period);
        assignment.setUser(user);
        assignment.setWorkDate(workDate);
        assignment.setSlot(entityManager.getReference(Slot.class, SlotIds.DAY));
        assignment.setStartTime(LocalTime.parse(start));
        assignment.setEndTime(LocalTime.parse(end));
        assignment.setStatus(status);
        assignmentRepository.save(assignment);
    }

    /** 書き込みをDBへ出し、キャッシュを捨てて、次の検索で本当にSQLが流れるようにする。 */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

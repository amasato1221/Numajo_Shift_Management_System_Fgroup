package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * みんなのシフト（SB-04）の流れの結合テスト（20-SB-04 6章 No.1〜5、97_テスト計画書 4.2）。
 *
 * <p>
 * shift_test の DB と本物の SecurityConfig・ShiftTableService・テンプレートを使い、画面（HTML）まで描画する。
 * テストごとにロールバックする（MockMvc は同じスレッドで動くので、テストのトランザクションの中のデータが見える）。
 * ほかのテストのデータに左右されないよう、はじめに公開済みの期間をすべて CLOSED にしてから、自分の期間を1つ作る。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class AllShiftsFlowTests {

    private final WebApplicationContext context;
    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private MockMvc mvc;
    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("UPDATE shift_periods SET status = 'CLOSED' WHERE status = 'PUBLISHED'");
        jdbcTemplate.update("UPDATE store_settings SET show_others_shifts = TRUE WHERE id = 1");
        me = saveUser("faself", "全員テスト自分", "ぜんいんてすとじぶん", Role.STAFF, true);
        other = saveUser("faother", "全員テスト他人", "ぜんいんてすとたにん", Role.STAFF, true);
    }

    @Test
    void 公開済みの期間_全員の割当が出て欠勤は出ない_割当のない人の行も出る() throws Exception {
        ShiftPeriod period = savePeriod(PeriodStatus.PUBLISHED);
        LocalDate first = period.getStartDate();
        User inactive = saveUser("fagone", "全員テスト退職", "ぜんいんてすとたいしょく", Role.STAFF, false);
        User absentOnly = saveUser("faabsent", "全員テスト欠勤だけ", "ぜんいんてすとけっきん", Role.STAFF, false);
        User empty = saveUser("faempty", "全員テスト割当なし", "ぜんいんてすとわりあてなし", Role.STAFF, true);
        saveAssignment(period, me, first, SlotIds.DAY, null, null, AssignmentStatus.ASSIGNED);
        saveAssignment(period, other, first, SlotIds.EVENING, null, null, AssignmentStatus.ASSIGNED);
        saveAssignment(period, other, first.plusDays(1), SlotIds.DAY, null, null, AssignmentStatus.ABSENT);
        saveAssignment(period, inactive, first, null, LocalTime.of(13, 0), LocalTime.of(22, 0),
                AssignmentStatus.ASSIGNED);
        saveAssignment(period, absentOnly, first, SlotIds.DAY, null, null, AssignmentStatus.ABSENT);

        String html = open("/staff/all-shifts?periodId=" + period.getId());

        assertThat(html).contains("全員テスト自分", "全員テスト他人", "全員テスト退職", empty.getName());
        assertThat(html).doesNotContain("全員テスト欠勤だけ", "badge-absent");
        assertThat(html).contains("all-shifts-mine", "badge-day", "badge-evening", "badge-time");
        // スマホのカード
        assertThat(html).contains("☀日勤：全員テスト自分", "☾夕勤：全員テスト他人", "時間指定：全員テスト退職 13-22");
        assertThat(html).contains("data-auto-submit");
    }

    @Test
    void 未公開の期間を指定_404() throws Exception {
        ShiftPeriod period = savePeriod(PeriodStatus.CLOSED);

        mvc.perform(get("/staff/all-shifts").param("periodId", String.valueOf(period.getId()))
                .with(user(new LoginUser(me, false))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 店舗設定がOFF_ホームへW1401() throws Exception {
        settingOff();

        mvc.perform(get("/staff/all-shifts").with(user(new LoginUser(me, false))))
                .andExpect(redirectedUrl("/staff/home"))
                .andExpect(flash().attribute("warningMessage", "W1401"));
    }

    @Test
    void 店舗設定がOFF_メニューにみんなのシフトが出ない() throws Exception {
        String on = open("/account/password");
        settingOff();
        String off = open("/account/password");

        assertThat(on).contains("みんなのシフト");
        assertThat(off).doesNotContain("みんなのシフト");
    }

    @Test
    void 公開済みの期間がない_I1401() throws Exception {
        String html = open("/staff/all-shifts");

        assertThat(html).contains("公開済みのシフトはまだありません。");
        assertThat(html).doesNotContain("all-shifts-table");
    }

    @Test
    void 店長が開く_店長のホームへ戻す() throws Exception {
        User manager = saveUser("famgr", "全員テスト店長", null, Role.MANAGER, true);

        mvc.perform(get("/staff/all-shifts").with(user(new LoginUser(manager, false))))
                .andExpect(redirectedUrl("/manager/home"));
    }

    // ---------------------------------------------------------------- 準備

    /** 店舗設定を OFF にする。JPA が覚えている前の値を使わないよう、持っているエンティティを捨てる。 */
    private void settingOff() {
        entityManager.flush();
        jdbcTemplate.update("UPDATE store_settings SET show_others_shifts = FALSE WHERE id = 1");
        entityManager.clear();
    }

    private String open(String url) throws Exception {
        entityManager.flush();
        return mvc.perform(get(url).with(user(new LoginUser(me, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private User saveUser(String loginId, String name, String kana, Role role, boolean active) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash("x");
        u.setName(name);
        u.setNameKana(kana);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(false);
        entityManager.persist(u);
        return u;
    }

    /** 選択肢の範囲（今日の3か月前の1日以降）で、まだ使われていない開始日の期間を作る。 */
    private ShiftPeriod savePeriod(PeriodStatus status) {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Tokyo")).withDayOfMonth(1);
        while (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shift_periods WHERE start_date = ?",
                Integer.class, start) > 0) {
            start = start.getDayOfMonth() == 1 ? start.withDayOfMonth(16) : start.plusMonths(1).withDayOfMonth(1);
        }
        ShiftPeriod p = new ShiftPeriod();
        p.setStartDate(start);
        p.setEndDate(start.plusDays(2));
        p.setDeadlineAt(start.minusDays(15).atTime(23, 59));
        p.setStatus(status);
        entityManager.persist(p);
        return p;
    }

    private void saveAssignment(ShiftPeriod period, User u, LocalDate date, Byte slotId, LocalTime start,
            LocalTime end, AssignmentStatus status) {
        Slot slot = slotId == null ? null : entityManager.find(Slot.class, slotId);
        ShiftAssignment a = new ShiftAssignment();
        a.setPeriod(period);
        a.setUser(u);
        a.setWorkDate(date);
        a.setSlot(slot);
        a.setStartTime(slot == null ? start : slot.getStartTime());
        a.setEndTime(slot == null ? end : slot.getEndTime());
        a.setStatus(status);
        entityManager.persist(a);
        entityManager.flush();
    }
}

package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.constant.BadgeType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SubmissionState;
import jp.numajo.fgroup.shift.constant.WarningType;
import jp.numajo.fgroup.shift.dto.AssignmentMatrix;
import jp.numajo.fgroup.shift.dto.CountCell;
import jp.numajo.fgroup.shift.dto.DayColumn;
import jp.numajo.fgroup.shift.dto.MatrixCell;
import jp.numajo.fgroup.shift.dto.MatrixCell.AssignMark;
import jp.numajo.fgroup.shift.dto.MatrixCell.RequestMark;
import jp.numajo.fgroup.shift.dto.MatrixRow;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.PeriodPageView;
import jp.numajo.fgroup.shift.dto.PublishResult;
import jp.numajo.fgroup.shift.dto.RequestDayRow;
import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.dto.ShiftBadge;
import jp.numajo.fgroup.shift.dto.ShiftTable;
import jp.numajo.fgroup.shift.dto.ShiftTableRow;
import jp.numajo.fgroup.shift.dto.ShiftWarning;
import jp.numajo.fgroup.shift.dto.TimeAssignmentRow;
import jp.numajo.fgroup.shift.dto.TimeRequestRow;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.AssignmentService;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.RequiredStaffService;
import jp.numajo.fgroup.shift.service.ShiftRequestService;
import jp.numajo.fgroup.shift.service.ShiftTableService;
import lombok.RequiredArgsConstructor;

/**
 * ManagerPeriodController（SM-06）を、本物の Spring Security とテンプレートで確かめるテスト。
 * AssignmentService だけモックにする。権限（店長だけ）・CSRF・画面が描画できることを見る。
 *
 * <p>
 * ユーザーはテスト用の DB に作る（UserActiveCheckFilter が DB を見るため）。ログイン ID は「sm6」で始める。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ManagerPeriodControllerMvcTests {

    private static final LocalDate D1 = LocalDate.of(2099, 11, 1);

    private final WebApplicationContext context;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AssignmentService assignmentService;
    @MockitoBean
    private ShiftRequestService shiftRequestService;
    @MockitoBean
    private PeriodService periodService;

    @MockitoBean
    private ShiftTableService shiftTableService;

    @MockitoBean
    private RequiredStaffService requiredStaffService;

    private MockMvc mvc;
    private LoginUser manager;
    private LoginUser staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        manager = new LoginUser(saveUser("sm6mgr", Role.MANAGER), false);
        staff = new LoginUser(saveUser("sm6staff", Role.STAFF), false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private User saveUser(String loginId, Role role) {
        User u = new User();
        u.setLoginId(loginId);
        u.setPasswordHash(passwordEncoder.encode("Passw0rd!Passw0rd!"));
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(true);
        u.setMustChangePassword(false);
        return userRepository.save(u);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_logs "
                + "WHERE user_id IN (SELECT id FROM users WHERE login_id LIKE 'sm6%')");
        jdbcTemplate.update("DELETE FROM users WHERE login_id LIKE 'sm6%'");
    }

    private static MatrixCell cell(RequestMark request, AssignMark assign, Long assignmentId, String confirm,
            boolean disabled) {
        return new MatrixCell(request, request == RequestMark.TIME ? "13:00-22:00" : null, assign, assignmentId,
                confirm, disabled);
    }

    /** あらゆる印（希望・割当・警告・時間指定）を1画面に並べたページ。 */
    private PeriodPageView fullPage(PeriodStatus status, boolean editable, boolean publishable) {
        return page(status, editable, publishable,
                List.of(new TimeRequestRow(12L, "鈴木", D1, "11月1日(日)", LocalTime.of(13, 0), LocalTime.of(22, 0),
                        "13:00〜22:00", true)),
                List.of(new TimeAssignmentRow(102L, "鈴木", D1, "11月1日(日)", LocalTime.of(13, 0),
                        LocalTime.of(22, 0), "13:00〜22:00", AssignmentStatus.ASSIGNED)));
    }

    private PeriodPageView page(PeriodStatus status, boolean editable, boolean publishable,
            List<TimeRequestRow> timeRequests, List<TimeAssignmentRow> timeAssignments) {
        Slot day = new Slot();
        day.setId((byte) 1);
        day.setName("日勤");
        day.setCode("DAY");
        day.setStartTime(LocalTime.of(8, 0));
        day.setEndTime(LocalTime.of(17, 0));
        Slot evening = new Slot();
        evening.setId((byte) 2);
        evening.setName("夕勤");
        evening.setCode("EVENING");
        evening.setStartTime(LocalTime.of(17, 0));
        evening.setEndTime(LocalTime.of(22, 0));
        MatrixRow rowA = new MatrixRow(11L, "山田", false, true, Map.of(D1, Map.of(
                (byte) 1, cell(RequestMark.OK, AssignMark.SLOT, 101L, null, false),
                (byte) 2, cell(RequestMark.NG, AssignMark.NONE, null, "C2602", false))));
        MatrixRow rowB = new MatrixRow(12L, "鈴木", true, false, Map.of(D1, Map.of(
                (byte) 1, cell(RequestMark.TIME, AssignMark.TIME, 102L, null, true),
                (byte) 2, cell(RequestMark.NO_SKILL, AssignMark.ABSENT, 103L, null, true))));
        MatrixRow rowC = new MatrixRow(13L, "佐藤", false, true, Map.of(D1, Map.of(
                (byte) 1, cell(RequestMark.NOT_SUBMITTED, AssignMark.NONE, null, "C2602", false),
                (byte) 2, cell(RequestMark.OK, AssignMark.NONE, null, null, false))));
        AssignmentMatrix matrix = new AssignmentMatrix(List.of(new DayColumn(D1, "11/1", "1", "日", "11/1(日)")),
                List.of(day, evening),
                List.of(rowA, rowB, rowC), Map.of(D1, Map.of(
                        (byte) 1, new CountCell(2, 1, false, false),
                        (byte) 2, new CountCell(0, 1, true, true))));
        PeriodOption option = new PeriodOption(1L, D1, D1, "11/1〜11/1", LocalDateTime.of(2099, 10, 15, 23, 59, 59),
                "2099/10/15 23:59", "10/15 23:59", status, "11/1〜11/1（期限 10/15）");
        List<ShiftWarning> warnings = List.of(
                new ShiftWarning(WarningType.TRAINEE_ONLY, D1, "11月1日(日)", (byte) 2, "W2603",
                        new Object[] { "11月1日(日)", "夕勤" }),
                new ShiftWarning(WarningType.SHORTAGE, D1, "11月1日(日)", (byte) 2, "W2601",
                        new Object[] { "11月1日(日)", "夕勤", 1 }));
        return new PeriodPageView(option, null, null, List.of(option), matrix, warnings, timeRequests,
                timeAssignments, editable, publishable, publishable || !editable ? null : "W2605");
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void 店長なら画面が描画できる_作成中() throws Exception {
        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.CLOSED, true, true));
        when(assignmentService.findStaffOptions()).thenReturn(List.of());

        String html = mvc.perform(get("/manager/periods/1").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/period"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("山田").contains("鈴木").contains("日勤").contains("夕勤");
        // 日付・時刻の文字は〜Label をそのまま出す（91 5.1）
        assertThat(html).contains("提出期限 2099/10/15 23:59").contains("11/1(日)").contains("11月1日(日)")
                .contains("13:00〜22:00");
        // 送る値は日付型・時刻型をそのまま書く
        assertThat(html).contains("value=\"2099-11-01\"").contains("value=\"11:2099-11-01:1\"");
    }

    @Test
    void 店長なら画面が描画できる_時刻のプルダウンはSelectOptionで出す() throws Exception {
        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.CLOSED, true, true));
        when(assignmentService.findStaffOptions()).thenReturn(List.of());

        String html = mvc.perform(get("/manager/periods/1").with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 値は「08:00」、表示は「8:00」
        assertThat(html).contains("value=\"08:00\"").contains(">8:00</option>").contains("value=\"22:00\"");
    }

    @Test
    void 店長なら画面が描画できる_時間指定が0件ならI2611とI2612を出す() throws Exception {
        when(assignmentService.getPeriodPage(1L))
                .thenReturn(page(PeriodStatus.CLOSED, true, true, List.of(), List.of()));
        when(assignmentService.findStaffOptions()).thenReturn(List.of());

        String html = mvc.perform(get("/manager/periods/1").with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("時間指定の希望はありません。").contains("時間指定の割当はありません。");
    }

    @Test
    void 店長なら画面が描画できる_公開できない作成中と受付中と公開済み() throws Exception {
        when(assignmentService.findStaffOptions()).thenReturn(List.of());
        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.CLOSED, true, false));
        mvc.perform(get("/manager/periods/1").with(user(manager))).andExpect(status().isOk());

        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.OPEN, false, false));
        mvc.perform(get("/manager/periods/1").with(user(manager))).andExpect(status().isOk());

        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.PUBLISHED, true, false));
        mvc.perform(get("/manager/periods/1").with(user(manager))).andExpect(status().isOk());
    }

    @Test
    void バイトなら自分のホームへ戻る() throws Exception {
        mvc.perform(get("/manager/periods/1").with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post("/manager/periods/1/publish").with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));

        verify(assignmentService, never()).publish(anyLong(), anyLong());
    }

    @Test
    void ログインしていなければログイン画面へ行く() throws Exception {
        mvc.perform(get("/manager/periods/1"))
                .andExpect(status().is3xxRedirection());
    }

    // ---------------------------------------------------------------- CSRF

    @Test
    void CSRFトークンなしで公開する_自分のホームへ戻り公開されない() throws Exception {
        when(assignmentService.getPeriodPage(1L)).thenReturn(fullPage(PeriodStatus.CLOSED, true, true));
        when(assignmentService.findStaffOptions()).thenReturn(List.of());
        MockHttpSession session = new MockHttpSession();
        // セッションにトークンがないと /login?expired へ行くので、先に画面を開いてトークンを作っておく
        mvc.perform(get("/manager/periods/1").with(user(manager)).session(session)).andExpect(status().isOk());

        mvc.perform(post("/manager/periods/1/publish").with(user(manager)).session(session))
                .andExpect(redirectedUrl("/manager/home"));

        verify(assignmentService, never()).publish(anyLong(), anyLong());
    }

    @Test
    void CSRFトークンがあれば公開できる() throws Exception {
        when(assignmentService.publish(1L, manager.getUserId())).thenReturn(new PublishResult(1, false));

        mvc.perform(post("/manager/periods/1/publish").with(csrf()).with(user(manager)))
                .andExpect(redirectedUrl("/manager/periods/1"));

        verify(assignmentService).publish(1L, manager.getUserId());
    }

    @Test
    void セルの切替もCSRFトークンがあれば受け付ける() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle").with(csrf()).with(user(manager))
                .param("cell", "5:2099-11-02:1"))
                .andExpect(redirectedUrl("/manager/periods/1"));

        verify(assignmentService).toggleSlot(any(), any(), any(), any(Byte.class), any());
    }

    // ---------------------------------------------------------------- 希望の代理修正（SM-06-1）

    private RequestPageView proxyPage(SubmissionState state, String statusLabel, String submittedAtLabel) {
        PeriodOption option = new PeriodOption(1L, D1, D1.plusDays(1), "11/1〜11/2",
                LocalDateTime.of(2099, 10, 15, 23, 59, 59), "2099/10/15 23:59", "10/15 23:59", PeriodStatus.CLOSED,
                "11/1〜11/2（期限 10/15）");
        List<RequestDayRow> days = List.of(new RequestDayRow(D1, "11月1日(日)", true, true),
                new RequestDayRow(D1.plusDays(1), "11月2日(月)", true, false));
        return new RequestPageView(option, List.of(), "2099/10/15 23:59", true, null, submittedAtLabel, state,
                statusLabel, days, "山田 太郎");
    }

    private void givenProxyPage(RequestPageView page) {
        when(shiftRequestService.getProxyPage(1L, 5L)).thenReturn(page);
        when(periodService.getPeriod(1L)).thenReturn(new ShiftPeriod());
        when(shiftRequestService.toForm(any(), any())).thenReturn(new ShiftRequestForm());
    }

    @Test
    void 代理修正の画面が描画できる_未提出() throws Exception {
        givenProxyPage(proxyPage(SubmissionState.NOT_SUBMITTED, "未提出", null));

        String html = mvc.perform(get("/manager/periods/1/requests/5").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/request-proxy"))
                .andReturn().getResponse().getContentAsString();

        // 見出し・状態・案内・送り先・戻る先。日付・期間の文字は〜Label をそのまま出す（91 5.1）
        assertThat(html).contains("山田 太郎さんの希望（11/1〜11/2）").contains("未提出")
                .contains("店長が代わりに希望を直します。保存すると提出済みになります。")
                .contains("action=\"/manager/periods/1/requests/5\"").contains("name=\"periodId\"")
                .contains("11月1日(日)").contains("11月2日(月)").contains("data-fill=\"OK\"")
                .contains("data-fill=\"NG\"").contains("href=\"/manager/periods/1\"");
        // 取消ボタンはない
        assertThat(html).doesNotContain("提出を取り消す");
    }

    @Test
    void 代理修正の画面が描画できる_提出済みは最終更新も出す() throws Exception {
        givenProxyPage(proxyPage(SubmissionState.SUBMITTED, "提出済み", "2099/10/09 10:00"));

        String html = mvc.perform(get("/manager/periods/1/requests/5").with(user(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("提出済み（最終更新 2099/10/09 10:00）");
    }

    @Test
    void 代理修正の画面でバイトなら自分のホームへ戻る() throws Exception {
        mvc.perform(get("/manager/periods/1/requests/5").with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));
        mvc.perform(post("/manager/periods/1/requests/5").with(csrf()).with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));

        verify(shiftRequestService, never()).proxySubmit(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void 代理修正でCSRFトークンなしなら保存されない() throws Exception {
        givenProxyPage(proxyPage(SubmissionState.NOT_SUBMITTED, "未提出", null));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/manager/periods/1/requests/5").with(user(manager)).session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/manager/periods/1/requests/5").with(user(manager)).session(session)
                .param("periodId", "1"))
                .andExpect(redirectedUrl("/manager/home"));

        verify(shiftRequestService, never()).proxySubmit(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void 代理修正はCSRFトークンがあれば保存できる() throws Exception {
        when(shiftRequestService.proxySubmit(anyLong(), anyLong(), any(), anyLong())).thenReturn("山田 太郎");

        mvc.perform(post("/manager/periods/1/requests/5").with(csrf()).with(user(manager))
                .param("periodId", "1").param("days[0].workDate", "2099-11-01").param("days[0].mode", "SLOT"))
                .andExpect(redirectedUrl("/manager/periods/1"));

        verify(shiftRequestService).proxySubmit(anyLong(), anyLong(), any(), anyLong());
    }

    // ---------------------------------------------------------------- 印刷用シフト表（SM-08）

    private String renderPrint(PeriodStatus status) throws Exception {
        ShiftPeriod period = new ShiftPeriod();
        period.setId(1L);
        period.setStartDate(D1);
        period.setEndDate(D1.plusDays(1));
        period.setStatus(status);
        when(periodService.getPeriod(1L)).thenReturn(period);
        ShiftTableRow row = new ShiftTableRow(11L, "山田", true,
                Map.of(D1, List.of(new ShiftBadge(BadgeType.DAY, "☀", null, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                        new ShiftBadge(BadgeType.ABSENT, "☾", null, LocalTime.of(17, 0), LocalTime.of(22, 0)))),
                1);
        ShiftTable table = new ShiftTable(
                List.of(ShiftTableService.toDayColumn(D1), ShiftTableService.toDayColumn(D1.plusDays(1))),
                List.of(row));
        when(shiftTableService.build(period, true)).thenReturn(table);
        when(requiredStaffService.requiredCounts(D1, D1.plusDays(1))).thenReturn(Map.of(
                D1, Map.of((byte) 1, 2, (byte) 2, 1), D1.plusDays(1), Map.of((byte) 1, 1, (byte) 2, 1)));
        when(shiftTableService.slotCounts(table)).thenReturn(Map.of(
                D1, Map.of((byte) 1, 1, (byte) 2, 1), D1.plusDays(1), Map.of((byte) 1, 1, (byte) 2, 0)));
        return mvc.perform(get("/manager/periods/1/print").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/print"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 印刷用シフト表_公開済みは透かしがなく表と人数と不足の印が出る() throws Exception {
        String html = renderPrint(PeriodStatus.PUBLISHED);

        assertThat(html).doesNotContain("下書き（未公開）");
        assertThat(html).contains("山田").contains("研").contains("欠").contains("出勤日数");
        assertThat(html).contains("☀日勤 人数").contains("☾夕勤 人数");
        assertThat(html).contains("1/2 ▲").contains("0/1 ▲").contains("1/1");
        assertThat(html).contains("data-print").contains("data-close");
    }

    @Test
    void 印刷用シフト表_日付と曜日は文字になって出る() throws Exception {
        String html = renderPrint(PeriodStatus.PUBLISHED);

        assertThat(html).contains("2099/11/1(日)〜11/2(月)").contains("出力 ");
        assertThat(html).contains("is-sun");
    }

    @Test
    void 印刷用シフト表_作成中は下書きの透かしが出る() throws Exception {
        assertThat(renderPrint(PeriodStatus.CLOSED)).contains("下書き（未公開）");
    }

    @Test
    void 印刷用シフト表_期間がなければ404() throws Exception {
        when(periodService.getPeriod(999L)).thenThrow(new ResourceNotFoundException("period id=999"));

        mvc.perform(get("/manager/periods/999/print").with(user(manager)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 印刷用シフト表_バイトなら自分のホームへ戻る() throws Exception {
        mvc.perform(get("/manager/periods/1/print").with(user(staff)))
                .andExpect(redirectedUrl("/staff/home"));
    }
}

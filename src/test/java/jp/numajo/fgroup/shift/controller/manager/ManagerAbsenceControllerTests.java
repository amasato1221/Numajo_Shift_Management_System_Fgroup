package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.dto.AbsenceConfirmView;
import jp.numajo.fgroup.shift.dto.AbsenceDayView;
import jp.numajo.fgroup.shift.dto.AbsenceTargetRow;
import jp.numajo.fgroup.shift.dto.VacancyRecipientRow;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.AbsenceForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.AbsenceService;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * ManagerAbsenceController（SM-07）の単体テスト（20-SM-07 3章・7章、97_テスト計画書 4.2）。
 *
 * <p>
 * AbsenceService はモックにし、MockMvc（standalone）で、日付の決め方・画面の振り分け・入力チェック・
 * フラッシュ・リダイレクト先を確かめる。画面（HTML）の描画・DB への保存・通知・権限は、ManagerAbsenceFlowTests で確かめる。
 * テストの「今」は 2026/11/10、割当の日付は 11/12。
 * </p>
 *
 * <pre>
 * 組み合わせ表1：① の日付の決め方（addDayValues）  A：date がある、B：形式が正しい、C：今日以降
 * No. | A  | B  | C  | 表示する日付 | エラー | テスト
 *  1  | 偽 | －  | －  | 今日         | なし   | パラメーターなし_今日の出勤者を出す
 *  2  | 真 | 偽 | －  | 今日         | なし   | 日付の形式が違う_今日で表示しエラーにしない
 *  3  | 真 | 真 | 偽 | 今日         | E2702  | 日付の範囲（前日）
 *  4  | 真 | 真 | 真 | 今日（当日） | なし   | 日付の範囲（当日）
 *  5  | 真 | 真 | 真 | その日（翌日）| なし   | 日付の範囲（翌日）
 *  （A 偽のとき B・C は評価できない。B 偽のとき C は評価できない）
 *
 * 組み合わせ表2：登録の入力（register）  A：assignmentId に入力エラー、B：ほかの項目に入力エラー
 * No. | A  | B  | 結果                                            | テスト
 *  1  | 真 | 偽 | ① に戻る（E2701）                               | 割当IDが送られない／割当IDが数字でない
 *  2  | 偽 | 真 | ② を入れ直して再表示（register しない）          | 通知する文が空／通知する文が501文字／理由が201文字
 *  3  | 偽 | 偽 | register を呼ぶ                                  | 登録できる
 *  4  | 真 | 真 | ① に戻る（A が先）                               | 割当IDがなく通知する文も空
 *
 * 組み合わせ表3：登録後の知らせ（notified の人数）
 * No. | 人数 | 結果                          | テスト
 *  1  | 1以上 | I2703（W2701 なし）            | 登録できる
 *  2  | 0    | I2703 と W2701                 | 通知する人が0人
 *
 * 入力チェックの境界値：通知する文 0（空）・1・500・501 文字、理由 200・201 文字
 * </pre>
 */
class ManagerAbsenceControllerTests {

    private static final String FORM = "absenceForm";
    private static final String VIEW = "manager/absence";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 11, 12);
    private static final Long ASSIGNMENT_ID = 10L;
    private static final Long OPERATOR_ID = 5L;
    private static final String NOTICE = "入れる方は連絡してください。";

    private AbsenceService absenceService;
    private MockMvc mvc;
    private AbsenceConfirmView confirm;

    @BeforeEach
    void setUp() {
        absenceService = mock(AbsenceService.class);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        when(storeSettingService.get()).thenReturn(setting);
        NotificationService notificationService = mock(NotificationService.class);
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 10, 10, 0).atZone(zone).toInstant(), zone);
        mvc = MockMvcBuilders.standaloneSetup(new ManagerAbsenceController(absenceService, clock))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, notificationService))
                .build();
        User u = new User();
        u.setId(OPERATOR_ID);
        u.setLoginId("manager1");
        u.setRole(Role.MANAGER);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));

        confirm = new AbsenceConfirmView(ASSIGNMENT_ID, WORK_DATE, "11月12日(木)", "11月12日(木) ☀日勤 8:00〜17:00　山田 太郎",
                List.of(new VacancyRecipientRow(2L, "田中 花子", true)), "既定の通知文です。");
        when(absenceService.getDay(any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            return new AbsenceDayView(date, "日付ラベル" + date.getDayOfMonth(),
                    List.of(new AbsenceTargetRow(11L, "☀日勤", "田中 花子", null, null, null, null)), List.of());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 部品

    private static List<String> codes(MvcResult result, String field) {
        BindingResult binding = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + FORM);
        return binding.getFieldErrors(field).stream().map(e -> e.getCode()).toList();
    }

    private void assertDayShown(MvcResult result, LocalDate date) {
        assertThat(result.getModelAndView().getViewName()).isEqualTo(VIEW);
        assertThat(result.getModelAndView().getModel()).containsEntry("date", date)
                .containsEntry("dateLabel", "日付ラベル" + date.getDayOfMonth())
                .containsEntry("today", TODAY)
                .containsKeys("candidatesOfDay", "absentsOfDay")
                .doesNotContainKey("target");
        verify(absenceService).getDay(date);
    }

    private void assertConfirmShownAgain(MvcResult result) {
        assertThat(result.getModelAndView().getViewName()).isEqualTo(VIEW);
        assertThat(result.getModelAndView().getModel()).containsEntry("target", confirm)
                .containsEntry("recipients", confirm.recipients());
        verify(absenceService, never()).register(anyLong(), any(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- 3.1 ① の表示

    @Test
    void パラメーターなし_今日の出勤者を出す() throws Exception {
        MvcResult result = mvc.perform(get("/manager/absences"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andReturn();

        assertDayShown(result, TODAY);
        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
    }

    @ParameterizedTest(name = "date={0} → 形式違いなので今日で表示しエラーにしない")
    @CsvSource({ "abc", "2026-13-45", "20261112", "''" })
    void 日付の形式が違う_今日で表示しエラーにしない(String date) throws Exception {
        MvcResult result = mvc.perform(get("/manager/absences").param("date", date)).andReturn();

        assertDayShown(result, TODAY);
        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
    }

    @Test
    void 日付が昨日_E2702を出して今日で表示する() throws Exception {
        MvcResult result = mvc.perform(get("/manager/absences").param("date", "2026-11-09")).andReturn();

        assertDayShown(result, TODAY);
        assertThat(result.getModelAndView().getModel()).containsEntry("errorMessage", "E2702");
        verify(absenceService, never()).getDay(LocalDate.of(2026, 11, 9));
    }

    @ParameterizedTest(name = "date={0} → その日で表示する")
    @CsvSource({ "2026-11-10", "2026-11-11", "2026-12-31" })
    void 日付が今日以降_その日の出勤者を出す(String date) throws Exception {
        MvcResult result = mvc.perform(get("/manager/absences").param("date", date)).andReturn();

        assertDayShown(result, LocalDate.parse(date));
        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
    }

    // ---------------------------------------------------------------- 3.2 ② の表示

    @Test
    void 割当IDあり_確認の画面を出し日付は無視する() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenReturn(confirm);

        MvcResult result = mvc.perform(get("/manager/absences").param("assignmentId", "10")
                .param("date", "2026-11-20"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW))
                .andReturn();

        assertThat(result.getModelAndView().getModel()).containsEntry("target", confirm)
                .containsEntry("recipients", confirm.recipients());
        AbsenceForm form = (AbsenceForm) result.getModelAndView().getModel().get(FORM);
        assertThat(form.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(form.getNoticeMessage()).isEqualTo("既定の通知文です。");
        assertThat(form.getReason()).isNull();
        verify(absenceService, never()).getDay(any());
    }

    @Test
    void 割当IDあり_欠勤にできない割当_割当の日付の画面へ戻りエラーを出す() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenThrow(new BusinessException("E2706"));
        when(absenceService.findWorkDate(ASSIGNMENT_ID)).thenReturn(WORK_DATE);

        MvcResult result = mvc.perform(get("/manager/absences").param("assignmentId", "10"))
                .andExpect(redirectedUrl("/manager/absences?date=2026-11-12"))
                .andExpect(flash().attribute("errorMessage", "E2706"))
                .andReturn();

        assertThat((Object[]) result.getFlashMap().get("errorArgs")).isEmpty();
    }

    // ---------------------------------------------------------------- 3.3 登録

    @Test
    void 登録できる_欠勤を登録して期間のシフト作成へ戻りI2703を出す() throws Exception {
        when(absenceService.register(ASSIGNMENT_ID, "体調不良", NOTICE, OPERATOR_ID)).thenReturn(2);
        when(absenceService.findPeriodId(ASSIGNMENT_ID)).thenReturn(7L);

        MvcResult result = mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("reason", "体調不良").param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/periods/7"))
                .andExpect(flash().attribute("successMessage", "I2703"))
                .andReturn();

        assertThat((Object[]) result.getFlashMap().get("successArgs")).containsExactly(2);
        assertThat((Map<String, Object>) result.getFlashMap()).doesNotContainKey("warningMessage");
        verify(absenceService).register(ASSIGNMENT_ID, "体調不良", NOTICE, OPERATOR_ID);
    }

    @Test
    void 通知する人が0人_I2703と一緒にW2701を出す() throws Exception {
        when(absenceService.register(ASSIGNMENT_ID, null, NOTICE, OPERATOR_ID)).thenReturn(0);
        when(absenceService.findPeriodId(ASSIGNMENT_ID)).thenReturn(7L);

        MvcResult result = mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/periods/7"))
                .andExpect(flash().attribute("successMessage", "I2703"))
                .andExpect(flash().attribute("warningMessage", "W2701"))
                .andReturn();

        assertThat((Object[]) result.getFlashMap().get("successArgs")).containsExactly(0);
    }

    @Test
    void 理由と通知する文の前後の空白_取って登録する() throws Exception {
        when(absenceService.register(ASSIGNMENT_ID, "私用", NOTICE, OPERATOR_ID)).thenReturn(1);
        when(absenceService.findPeriodId(ASSIGNMENT_ID)).thenReturn(7L);

        mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("reason", "  私用 ").param("noticeMessage", "  " + NOTICE + " "))
                .andExpect(redirectedUrl("/manager/periods/7"));

        verify(absenceService).register(ASSIGNMENT_ID, "私用", NOTICE, OPERATOR_ID);
    }

    @ParameterizedTest(name = "通知する文が{0}文字なら登録できる")
    @CsvSource({ "1", "500" })
    void 通知する文が1文字と500文字_登録できる(int length) throws Exception {
        String message = "あ".repeat(length);
        when(absenceService.register(ASSIGNMENT_ID, null, message, OPERATOR_ID)).thenReturn(1);
        when(absenceService.findPeriodId(ASSIGNMENT_ID)).thenReturn(7L);

        mvc.perform(post("/manager/absences").param("assignmentId", "10").param("noticeMessage", message))
                .andExpect(redirectedUrl("/manager/periods/7"));
    }

    @Test
    void 理由が200文字_登録できる() throws Exception {
        String reason = "あ".repeat(200);
        when(absenceService.register(ASSIGNMENT_ID, reason, NOTICE, OPERATOR_ID)).thenReturn(1);
        when(absenceService.findPeriodId(ASSIGNMENT_ID)).thenReturn(7L);

        mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("reason", reason).param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/periods/7"));
    }

    @ParameterizedTest(name = "割当IDが「{0}」なら ① に戻って E2701")
    @CsvSource({ "''", "abc" })
    void 割当IDが送られないか数字でない_日付と人を選ぶ画面に戻りE2701を出す(String assignmentId) throws Exception {
        mvc.perform(post("/manager/absences").param("assignmentId", assignmentId).param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/absences"))
                .andExpect(flash().attribute("errorMessage", "E2701"));

        verify(absenceService, never()).register(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void 割当IDがなく通知する文も空_割当IDのエラーが先でE2701() throws Exception {
        mvc.perform(post("/manager/absences").param("noticeMessage", ""))
                .andExpect(redirectedUrl("/manager/absences"))
                .andExpect(flash().attribute("errorMessage", "E2701"));

        verify(absenceService, never()).getConfirm(any());
    }

    @ParameterizedTest(name = "通知する文「{0}」は E2704")
    @CsvSource(value = { "''", "'   '", "'　'" }, quoteCharacter = '\'')
    void 通知する文が空か空白だけ_E2704で確認の画面を再表示する(String message) throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenReturn(confirm);

        MvcResult result = mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("reason", "体調不良").param("noticeMessage", message))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(codes(result, "noticeMessage")).containsExactly("NotBlank");
        assertConfirmShownAgain(result);
        AbsenceForm form = (AbsenceForm) result.getModelAndView().getModel().get(FORM);
        assertThat(form.getReason()).isEqualTo("体調不良");
    }

    @Test
    void 通知する文が送られない_E2704で確認の画面を再表示する() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenReturn(confirm);

        MvcResult result = mvc.perform(post("/manager/absences").param("assignmentId", "10")).andReturn();

        assertThat(codes(result, "noticeMessage")).containsExactly("NotBlank");
        assertConfirmShownAgain(result);
    }

    @Test
    void 通知する文が501文字_E2705で確認の画面を再表示し送られた文のまま出す() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenReturn(confirm);
        String message = "あ".repeat(501);

        MvcResult result = mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("noticeMessage", message)).andReturn();

        assertThat(codes(result, "noticeMessage")).containsExactly("Size");
        assertConfirmShownAgain(result);
        AbsenceForm form = (AbsenceForm) result.getModelAndView().getModel().get(FORM);
        assertThat(form.getNoticeMessage()).isEqualTo(message);
    }

    @Test
    void 理由が201文字_E2703で確認の画面を再表示する() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenReturn(confirm);

        MvcResult result = mvc.perform(post("/manager/absences")
                .param("assignmentId", "10").param("reason", "あ".repeat(201)).param("noticeMessage", NOTICE))
                .andReturn();

        assertThat(codes(result, "reason")).containsExactly("Size");
        assertConfirmShownAgain(result);
    }

    @Test
    void 入力エラーの再表示で割当が欠勤にできなくなっていた_割当の日付の画面へ戻る() throws Exception {
        when(absenceService.getConfirm(ASSIGNMENT_ID)).thenThrow(new BusinessException("E2707"));
        when(absenceService.findWorkDate(ASSIGNMENT_ID)).thenReturn(WORK_DATE);

        mvc.perform(post("/manager/absences").param("assignmentId", "10").param("noticeMessage", ""))
                .andExpect(redirectedUrl("/manager/absences?date=2026-11-12"))
                .andExpect(flash().attribute("errorMessage", "E2707"));
    }

    @ParameterizedTest(name = "登録で {0} → 割当の日付の画面へ戻って赤のアラート")
    @CsvSource({ "E2706", "E2707", "E2702" })
    void 登録で業務エラー_割当の日付の画面へ戻りエラーを出す(String code) throws Exception {
        when(absenceService.register(ASSIGNMENT_ID, null, NOTICE, OPERATOR_ID)).thenThrow(new BusinessException(code));
        when(absenceService.findWorkDate(ASSIGNMENT_ID)).thenReturn(WORK_DATE);

        mvc.perform(post("/manager/absences").param("assignmentId", "10").param("noticeMessage", NOTICE))
                .andExpect(redirectedUrl("/manager/absences?date=2026-11-12"))
                .andExpect(flash().attribute("errorMessage", code));

        verify(absenceService, never()).findPeriodId(any());
    }
}

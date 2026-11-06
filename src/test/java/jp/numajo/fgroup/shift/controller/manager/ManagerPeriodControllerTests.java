package jp.numajo.fgroup.shift.controller.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.PeriodPageView;
import jp.numajo.fgroup.shift.dto.PublishResult;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.AssignmentService;
import jp.numajo.fgroup.shift.service.PeriodService;

/**
 * ManagerPeriodController（SM-06：シフト作成）のテスト（20-SM-06 8章、97_テスト計画書 4.2）。
 *
 * <p>
 * Service はモックにし、MockMvc（standalone）で、入力の読み取り・画面の振り分け・フラッシュ・リダイレクト先を確かめる。
 * 画面（HTML）は表示しない。
 * </p>
 *
 * <pre>
 * 判定                                      | テスト
 * redirectDefault  period == null           | 真：I2605 をフラッシュして店長ホーム、偽：その期間の画面
 * toggle    cell が null                    | E2601
 * toggle    cell の形が違う                 | E2601（形・枠ID 3・日付の形）
 * toggle    LocalDate.parse が失敗          | E2601（2099-13-40 のように形は合うが日付でない）
 * toggle    catch BusinessException         | 例外あり：コードと引数をフラッシュ、なし：何も出さない
 * assignTime  bindingResult.hasErrors()     | 真：最初のエラーのコードをフラッシュ、偽：サービスを呼ぶ
 * assignTime  catch BusinessException       | 例外あり：コードをフラッシュ、なし：I2606
 * deleteAssignment catch BusinessException  | 例外あり：コードをフラッシュ、なし：I2607
 * publish   catch BusinessException         | 例外あり：コードをフラッシュ、なし：I2608
 * publish   result.hasWarnings()            | 真：W2606 も出す、偽：I2608 のみ
 *
 * 組み合わせ表：時間指定の入力のエラー（値が無い／形が違う。20-00 2.2 パターン B：最初のエラーだけ出す）
 * No. | スタッフ | 日付 | 開始 | 終了 | 出すコード
 *  1  | あり     | あり | あり | あり | （エラーなし。サービスを呼ぶ）
 *  2  | なし     | あり | あり | あり | E2606
 *  3  | あり     | なし | あり | あり | E2606
 *  4  | あり     | あり | なし | あり | E2607
 *  5  | あり     | あり | あり | なし | E2607
 *  6  | なし     | なし | なし | なし | E2606（左の項目を先に出す）
 *  7  | あり     | あり | 形違い | あり | E2607（typeMismatch）
 *  8  | あり     | 形違い | あり | あり | E2606（typeMismatch）
 *  9  | 数字でない | あり | あり | あり | E2606（typeMismatch）
 * 10  | あり     | あり | あり | 形違い | E2607（typeMismatch）
 * </pre>
 */
class ManagerPeriodControllerTests {

    private static final LocalDate DATE = LocalDate.of(2099, 11, 2);

    private PeriodService periodService;
    private AssignmentService assignmentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        periodService = mock(PeriodService.class);
        assignmentService = mock(AssignmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ManagerPeriodController(periodService, assignmentService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        User u = new User();
        u.setId(9L);
        u.setLoginId("boss");
        u.setRole(Role.MANAGER);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder time() {
        return post("/manager/periods/1/assignments/time").param("userId", "5").param("workDate", "2099-11-02")
                .param("startTime", "13:00").param("endTime", "22:00");
    }

    // ---------------------------------------------------------------- 入口

    @Test
    void 期間を選ばずに開くと表示する期間へ移る() throws Exception {
        ShiftPeriod period = new ShiftPeriod();
        period.setId(7L);
        when(periodService.defaultPeriodForManager()).thenReturn(period);

        mvc.perform(get("/manager/periods"))
                .andExpect(redirectedUrl("/manager/periods/7"));
    }

    @Test
    void 期間がなければI2605を出して店長ホームへ戻る() throws Exception {
        when(periodService.defaultPeriodForManager()).thenReturn(null);

        mvc.perform(get("/manager/periods"))
                .andExpect(redirectedUrl("/manager/home"))
                .andExpect(flash().attribute("warningMessage", "I2605"));
    }

    // ---------------------------------------------------------------- 表示

    @Test
    void 画面を表示する_ページと空のフォームと時刻の選択肢とスタッフの選択肢を持つ() throws Exception {
        PeriodPageView page = new PeriodPageView(null, null, List.of(), null, List.of(), List.of(), List.of(), true,
                true, null);
        when(assignmentService.getPeriodPage(1L)).thenReturn(page);
        when(assignmentService.findStaffOptions()).thenReturn(List.of());

        mvc.perform(get("/manager/periods/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/period"))
                .andExpect(model().attribute("page", page))
                .andExpect(model().attributeExists("timeAssignmentForm", "timeOptions", "staffOptions"));
    }

    // ---------------------------------------------------------------- セルの切替

    @Test
    void セルを押すとサービスに渡して画面へ戻り成功のメッセージは出さない() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "5:2099-11-02:2"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attributeCount(0));

        verify(assignmentService).toggleSlot(1L, 5L, DATE, (byte) 2, 9L);
    }

    @Test
    void セルの指定が無ければE2601() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2601"));

        verify(assignmentService, never()).toggleSlot(anyLong(), anyLong(), any(), any(Byte.class), anyLong());
    }

    @Test
    void セルの指定の形が違えばE2601() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "abc"))
                .andExpect(flash().attribute("errorMessage", "E2601"));
        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "5:2099-11-02:3"))
                .andExpect(flash().attribute("errorMessage", "E2601"));
        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "5:2099/11/02:1"))
                .andExpect(flash().attribute("errorMessage", "E2601"));

        verify(assignmentService, never()).toggleSlot(anyLong(), anyLong(), any(), any(Byte.class), anyLong());
    }

    @Test
    void セルの日付が実在しなければE2601() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "5:2099-13-40:1"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2601"));
    }

    @Test
    void セルのユーザーIDが大きすぎて数にできなければE2601() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/toggle")
                .param("cell", "99999999999999999999:2099-11-02:1"))
                .andExpect(flash().attribute("errorMessage", "E2601"));
    }

    @Test
    void セルの切替で業務エラーならコードと引数をフラッシュする() throws Exception {
        doThrow(new BusinessException("E2605", "山田", "11月2日(月)")).when(assignmentService)
                .toggleSlot(1L, 5L, DATE, (byte) 1, 9L);

        mvc.perform(post("/manager/periods/1/assignments/toggle").param("cell", "5:2099-11-02:1"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2605"))
                .andExpect(flash().attribute("errorArgs", new Object[] { "山田", "11月2日(月)" }));
    }

    // ---------------------------------------------------------------- 時間指定

    @Test
    void 時間指定で割り当てるとI2606を出して画面へ戻る() throws Exception {
        mvc.perform(time())
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("successMessage", "I2606"));

        verify(assignmentService).assignTime(1L, 5L, DATE, LocalTime.of(13, 0), LocalTime.of(22, 0), 9L);
    }

    @Test
    void 時間指定で業務エラーならコードをフラッシュする() throws Exception {
        doThrow(new BusinessException("E2609")).when(assignmentService).assignTime(eq(1L), eq(5L), eq(DATE),
                any(), any(), eq(9L));

        mvc.perform(time())
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2609"));
    }

    @Test
    void 時間指定でスタッフが無ければE2606() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("workDate", "2099-11-02")
                .param("startTime", "13:00").param("endTime", "22:00"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2606"));

        verify(assignmentService, never()).assignTime(anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void 時間指定で日付が無ければE2606() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("startTime", "13:00").param("endTime", "22:00"))
                .andExpect(flash().attribute("errorMessage", "E2606"));
    }

    @Test
    void 時間指定で開始が無ければE2607() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("workDate", "2099-11-02").param("endTime", "22:00"))
                .andExpect(flash().attribute("errorMessage", "E2607"));
    }

    @Test
    void 時間指定で終了が無ければE2607() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("workDate", "2099-11-02").param("startTime", "13:00"))
                .andExpect(flash().attribute("errorMessage", "E2607"));
    }

    @Test
    void 時間指定が全部無ければ左の項目のE2606を先に出す() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time"))
                .andExpect(flash().attribute("errorMessage", "E2606"));
    }

    @Test
    void 時間指定で開始の形が違えばE2607() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("workDate", "2099-11-02").param("startTime", "abc").param("endTime", "22:00"))
                .andExpect(flash().attribute("errorMessage", "E2607"));
    }

    @Test
    void 時間指定で終了の形が違えばE2607() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("workDate", "2099-11-02").param("startTime", "13:00").param("endTime", "abc"))
                .andExpect(flash().attribute("errorMessage", "E2607"));
    }

    @Test
    void 時間指定で日付の形が違えばE2606() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "5")
                .param("workDate", "2099/11/02").param("startTime", "13:00").param("endTime", "22:00"))
                .andExpect(flash().attribute("errorMessage", "E2606"));
    }

    @Test
    void 時間指定でスタッフが数字でなければE2606() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/time").param("userId", "abc")
                .param("workDate", "2099-11-02").param("startTime", "13:00").param("endTime", "22:00"))
                .andExpect(flash().attribute("errorMessage", "E2606"));
    }

    // ---------------------------------------------------------------- 外す

    @Test
    void 割当を外すとI2607を出して画面へ戻る() throws Exception {
        mvc.perform(post("/manager/periods/1/assignments/101/delete"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("successMessage", "I2607"));

        verify(assignmentService).delete(1L, 101L, 9L);
    }

    @Test
    void 割当を外すとき業務エラーならコードをフラッシュする() throws Exception {
        doThrow(new BusinessException("E2603")).when(assignmentService).delete(1L, 101L, 9L);

        mvc.perform(post("/manager/periods/1/assignments/101/delete"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2603"));
    }

    // ---------------------------------------------------------------- 公開

    @Test
    void 警告がなければI2608だけ出して公開する() throws Exception {
        when(assignmentService.publish(1L, 9L)).thenReturn(new PublishResult(5, false));

        mvc.perform(post("/manager/periods/1/publish"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("successMessage", "I2608"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void 警告が残っていればI2608とW2606を出す() throws Exception {
        when(assignmentService.publish(1L, 9L)).thenReturn(new PublishResult(5, true));

        mvc.perform(post("/manager/periods/1/publish"))
                .andExpect(flash().attribute("successMessage", "I2608"))
                .andExpect(flash().attribute("warningMessage", "W2606"));
    }

    @Test
    void 公開で業務エラーならコードと引数をフラッシュする() throws Exception {
        doThrow(new BusinessException("E2611", 2L)).when(assignmentService).publish(1L, 9L);

        mvc.perform(post("/manager/periods/1/publish"))
                .andExpect(redirectedUrl("/manager/periods/1"))
                .andExpect(flash().attribute("errorMessage", "E2611"))
                .andExpect(flash().attribute("errorArgs", new Object[] { 2L }));
    }
}

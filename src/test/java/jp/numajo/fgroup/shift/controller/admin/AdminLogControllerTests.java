package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.form.LogSearchForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.OperationLogService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * AdminLogController（SA-03）の単体テスト（20-SA-03 2.1、30-06 4章）。
 *
 * <p>
 * OperationLogService はモックにし、MockMvc（standalone）で、既定値・期間のチェック・ページの直し方と model・画面名を確かめる。
 * 今日は Clock で 2026-10-22 に固定する。
 * </p>
 *
 * <pre>
 * 命令網羅：下の判定の真偽をすべて通すことで、list・applyDefaults・checkRange の全命令を通る。
 *
 * 判定条件網羅・条件網羅（applyDefaults。条件は1つずつ）：
 *  判定 1：from == null           | 真：今日−6日（2026-10-16）／偽：送った日
 *  判定 2：to == null             | 真：今日（2026-10-22）／偽：送った日
 *  判定 3：page == null || page &lt; 0（複数条件網羅）
 *    No. | page == null | page &lt; 0 | 判定 | テストの値
 *     a  | 真           | －        | 真   | page なし → 0
 *     b  | 偽           | 真        | 真   | page=-1 → 0
 *     c  | 偽           | 偽        | 偽   | page=1 → 1（page=0 も偽）
 *
 * 判定条件網羅（checkRange）：
 *  判定 4：from.isAfter(to)       | 真：E4301（from=10/23,to=10/22）／偽：次へ（from=to）
 *  判定 5：to − from &gt; 366日      | 真：E4302（367日）／偽：検索する（366日、0日）
 *
 * 判定条件網羅（list）：
 *  判定 6：errorCode != null      | 真：検索しない・0件・page=0／偽：検索する
 *  判定 7：totalPages &gt; 0 &amp;&amp; page &gt;= totalPages（複数条件網羅）
 *    No. | totalPages &gt; 0 | page &gt;= totalPages | 判定 | テストの値
 *     a  | 真             | 真                 | 真   | 51件・page=99 → 2ページ目（page=1）でもう一度検索
 *     b  | 真             | 偽                 | 偽   | 51件・page=1 → 1回だけ検索
 *     c  | 偽             | 真                 | 偽   | 0件・page=0 → 1回だけ検索（page は 0 のまま）
 *     d  | 偽             | 偽                 | －   | 起きない（page は既定値で 0 以上、totalPages=0 なら page &gt;= 0 は常に真）
 *
 * ほか：型の合わない値（from=abc、to=2026/10/01、userId=x、action=ABC、page=x）は null として既定値になる（観点 No.9）。
 * </pre>
 */
class AdminLogControllerTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final List<SelectOption> USER_OPTIONS = List.of(new SelectOption("1", "新井 正人（arai・管理者）"));

    private OperationLogService operationLogService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        operationLogService = mock(OperationLogService.class);
        when(operationLogService.userOptions()).thenReturn(USER_OPTIONS);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        setting.setShowOthersShifts(true);
        when(storeSettingService.get()).thenReturn(setting);
        // 23:59 にして、日付の境目でも今日が 10/22 のままであることも見る
        Clock clock = Clock.fixed(LocalDateTime.parse("2026-10-22T23:59:00").atZone(TOKYO).toInstant(), TOKYO);
        mvc = MockMvcBuilders.standaloneSetup(new AdminLogController(operationLogService, clock))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, mock(NotificationService.class)))
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
        u.setId(1L);
        u.setLoginId("admin");
        u.setRole(Role.ADMIN);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    /** search が呼ばれたら、渡された Pageable と total 件の空のページを返す。 */
    private void searchReturnsTotal(long total) {
        when(operationLogService.search(any(), any())).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(1);
            return new PageImpl<OperationLogRow>(List.of(), pageable, total);
        });
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name("admin/logs"))
                .andExpect(model().attribute("userOptions", USER_OPTIONS))
                .andExpect(model().attribute("actionOptions", AdminLogController.ACTION_OPTIONS))
                .andReturn();
    }

    private static LogSearchForm formOf(MvcResult result) {
        return (LogSearchForm) result.getModelAndView().getModel().get("logSearchForm");
    }

    @SuppressWarnings("unchecked")
    private static Page<OperationLogRow> logsOf(MvcResult result) {
        return (Page<OperationLogRow>) result.getModelAndView().getModel().get("logs");
    }

    /** search に渡された Pageable をすべて返す。 */
    private List<Pageable> searchedPageables(int times) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(operationLogService, times(times)).search(any(), captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- 既定値（判定 1〜3）

    @Test
    void 判定1真2真3a_条件なしなら今日の6日前から今日で0ページ目を50件_新しい順で検索する() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs"));

        LogSearchForm form = formOf(result);
        assertThat(form.getFrom()).isEqualTo(TODAY.minusDays(6));
        assertThat(form.getTo()).isEqualTo(TODAY);
        assertThat(form.getUserId()).isNull();
        assertThat(form.getAction()).isNull();
        assertThat(form.getPage()).isZero();
        Pageable pageable = searchedPageables(1).get(0);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(AdminLogController.PAGE_SIZE).isEqualTo(50);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
    }

    @Test
    void 判定1偽2偽3c_送った条件で検索する() throws Exception {
        searchReturnsTotal(51);

        MvcResult result = perform(get("/admin/logs").param("from", "2026-09-01").param("to", "2026-09-30")
                .param("userId", "3").param("action", "SHIFT_PUBLISH").param("page", "1"));

        ArgumentCaptor<LogSearchForm> captor = ArgumentCaptor.forClass(LogSearchForm.class);
        verify(operationLogService).search(captor.capture(), any());
        LogSearchForm form = captor.getValue();
        assertThat(form.getFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(form.getTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(form.getUserId()).isEqualTo(3L);
        assertThat(form.getAction()).isEqualTo(OperationAction.SHIFT_PUBLISH);
        assertThat(form.getPage()).isEqualTo(1);
        assertThat(formOf(result)).isSameAs(form);
    }

    @Test
    void 判定3b_pageが負なら0にする() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("page", "-1"));

        assertThat(formOf(result).getPage()).isZero();
        assertThat(searchedPageables(1).get(0).getPageNumber()).isZero();
    }

    @Test
    void 判定3c_page0はそのまま0() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("page", "0"));

        assertThat(formOf(result).getPage()).isZero();
    }

    @Test
    void 観点9_型の合わない値はnullとして既定値で検索する() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("from", "abc").param("to", "2026/10/01")
                .param("userId", "x").param("action", "ABC").param("page", "x"));

        LogSearchForm form = formOf(result);
        assertThat(form.getFrom()).isEqualTo(TODAY.minusDays(6));
        assertThat(form.getTo()).isEqualTo(TODAY);
        assertThat(form.getUserId()).isNull();
        assertThat(form.getAction()).isNull();
        assertThat(form.getPage()).isZero();
        verify(operationLogService).search(any(), any());
    }

    @Test
    void 空文字の条件はnullとして扱う() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("from", "").param("to", "")
                .param("userId", "").param("action", ""));

        LogSearchForm form = formOf(result);
        assertThat(form.getFrom()).isEqualTo(TODAY.minusDays(6));
        assertThat(form.getTo()).isEqualTo(TODAY);
        assertThat(form.getUserId()).isNull();
        assertThat(form.getAction()).isNull();
    }

    // ---------------------------------------------------------------- 期間のチェック（判定 4〜6）

    @Test
    void 判定4真6真_fromがtoより後ならE4301で検索せず0件() throws Exception {
        MvcResult result = perform(get("/admin/logs").param("from", "2026-10-23").param("to", "2026-10-22")
                .param("page", "3"));

        assertThat(result.getModelAndView().getModel()).containsEntry("errorMessage", "E4301");
        Page<OperationLogRow> logs = logsOf(result);
        assertThat(logs.getTotalElements()).isZero();
        assertThat(logs.getTotalPages()).isZero();
        assertThat(formOf(result).getPage()).isZero();
        verify(operationLogService, never()).search(any(), any());
    }

    @Test
    void 判定4偽5偽_fromとtoが同じ日なら検索する() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("from", "2026-10-22").param("to", "2026-10-22"));

        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
        verify(operationLogService).search(any(), any());
    }

    @Test
    void 判定5真6真_367日離れているとE4302で検索しない() throws Exception {
        MvcResult result = perform(get("/admin/logs").param("from", "2025-10-20").param("to", "2026-10-22"));

        assertThat(result.getModelAndView().getModel()).containsEntry("errorMessage", "E4302");
        assertThat(logsOf(result).getTotalElements()).isZero();
        verify(operationLogService, never()).search(any(), any());
    }

    @Test
    void 判定5偽_366日離れているところまでは検索する() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs").param("from", "2025-10-21").param("to", "2026-10-22"));

        assertThat(result.getModelAndView().getModel()).doesNotContainKey("errorMessage");
        verify(operationLogService).search(any(), any());
    }

    @Test
    void checkRange_境目の値() {
        LocalDate to = LocalDate.of(2026, 10, 22);
        assertThat(AdminLogController.checkRange(to.plusDays(1), to)).isEqualTo("E4301");
        assertThat(AdminLogController.checkRange(to, to)).isNull();
        assertThat(AdminLogController.checkRange(to.minusDays(366), to)).isNull();
        assertThat(AdminLogController.checkRange(to.minusDays(367), to)).isEqualTo("E4302");
    }

    // ---------------------------------------------------------------- 最後のページ（判定 7）

    @Test
    void 判定7a_51件でpage99なら最後のページでもう一度検索する() throws Exception {
        searchReturnsTotal(51);

        MvcResult result = perform(get("/admin/logs").param("page", "99"));

        List<Pageable> pageables = searchedPageables(2);
        assertThat(pageables.get(0).getPageNumber()).isEqualTo(99);
        assertThat(pageables.get(1).getPageNumber()).isEqualTo(1);
        assertThat(formOf(result).getPage()).isEqualTo(1);
        assertThat(logsOf(result).getNumber()).isEqualTo(1);
        assertThat(logsOf(result).getTotalPages()).isEqualTo(2);
    }

    @Test
    void 判定7b_51件でpage1なら1回だけ検索する() throws Exception {
        searchReturnsTotal(51);

        MvcResult result = perform(get("/admin/logs").param("page", "1"));

        assertThat(searchedPageables(1).get(0).getPageNumber()).isEqualTo(1);
        assertThat(formOf(result).getPage()).isEqualTo(1);
    }

    @Test
    void 判定7c_0件なら1回だけ検索してpageは0のまま() throws Exception {
        searchReturnsTotal(0);

        MvcResult result = perform(get("/admin/logs"));

        assertThat(searchedPageables(1)).hasSize(1);
        assertThat(formOf(result).getPage()).isZero();
        assertThat(logsOf(result).getTotalElements()).isZero();
    }

    // ---------------------------------------------------------------- 操作のプルダウン

    @Test
    void 操作の選択肢は列挙の全値を順に日本語名で持つ() {
        assertThat(AdminLogController.ACTION_OPTIONS).hasSize(OperationAction.values().length);
        for (int i = 0; i < OperationAction.values().length; i++) {
            OperationAction a = OperationAction.values()[i];
            assertThat(AdminLogController.ACTION_OPTIONS.get(i)).isEqualTo(new SelectOption(a.name(), a.getLabel()));
        }
    }
}

package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;

import jakarta.validation.ConstraintViolation;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.controller.common.GlobalModelAdvice;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.RequiredDefaultForm;
import jp.numajo.fgroup.shift.form.RequiredOverrideForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.RequiredStaffService;
import jp.numajo.fgroup.shift.service.StoreSettingService;

/**
 * ManagerRequiredStaffController（SM-05）の結合テスト（20-SM-05 6章 No.1・6、97_テスト計画書 4.2）。
 *
 * <p>
 * RequiredStaffService はモックにし、MockMvc（standalone）で、入力チェック・フラッシュ・リダイレクト先を確かめる。
 * 今日は 2026-11-05（日本時間）に固定する。画面（HTML）の描画・権限・CSRF は RequiredStaffFlowTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定ごとのテスト（判定条件網羅）
 * No. | メソッド・判定                            | 真                                   | 偽
 *  1  | parseYm：ym == null                       | 今月                                 | parse する
 *  2  | parseYm：DateTimeParseException           | 今月（"2026-13"・"abc"・""）          | その月
 *  3  | saveDefaults：hasErrors                   | 再表示（今月の一覧を入れ直す）       | 保存して I2501
 *  4  | saveOverride：hasErrors                   | 最初のエラーのコードでリダイレクト   | 保存へ
 *  5  | saveOverride：BusinessException           | E2504 でリダイレクト                 | I2502、日付の月へ
 *  6  | firstErrorCode：filter（エラーがある項目） | その項目のエラー                     | 次の項目を見る
 *  7  | firstErrorCode：ConstraintViolation でない | 項目で決めたコード（typeMismatch）   | 注釈のコード
 *
 * 組み合わせ表：上書きの最初のエラー（20-00 2.2 パターン B。A：日付、B：枠、C：人数 にエラーがある）
 * No. | A  | B  | C  | 出すコード
 *  1  | 真 | 真 | 真 | E2503（日付）
 *  2  | 真 | 真 | 偽 | E2503
 *  3  | 真 | 偽 | 真 | E2503
 *  4  | 真 | 偽 | 偽 | E2503
 *  5  | 偽 | 真 | 真 | E2504（枠）
 *  6  | 偽 | 真 | 偽 | E2504
 *  7  | 偽 | 偽 | 真 | E2501・E2502（人数）
 *  8  | 偽 | 偽 | 偽 | エラーなし（保存する）
 * </pre>
 */
class ManagerRequiredStaffControllerTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 11);
    private static final String DEFAULT_FORM = "requiredDefaultForm";

    private RequiredStaffService requiredStaffService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        requiredStaffService = mock(RequiredStaffService.class);
        StoreSettingService storeSettingService = mock(StoreSettingService.class);
        NotificationService notificationService = mock(NotificationService.class);
        StoreSetting setting = new StoreSetting();
        setting.setStoreName("テスト店");
        when(storeSettingService.get()).thenReturn(setting);
        RequiredDefaultForm form = new RequiredDefaultForm();
        form.setDayCounts(List.of(0, 0, 0, 0, 0, 0, 0));
        form.setEveningCounts(List.of(0, 0, 0, 0, 0, 0, 0));
        when(requiredStaffService.getDefaultForm()).thenReturn(form);
        mvc = MockMvcBuilders
                .standaloneSetup(new ManagerRequiredStaffController(requiredStaffService, CLOCK))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalModelAdvice(storeSettingService, notificationService))
                .build();
        User u = new User();
        u.setId(5L);
        u.setLoginId("manager1");
        u.setRole(Role.MANAGER);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 部品

    /** 既定値の保存。値は日勤・夕勤とも7個を「,」区切りで渡し、null の要素は送らない。 */
    private static MockHttpServletRequestBuilder defaults(String[] day, String[] evening) {
        MockHttpServletRequestBuilder request = post("/manager/required/defaults");
        for (int i = 0; i < day.length; i++) {
            if (day[i] != null) {
                request.param("dayCounts[" + i + "]", day[i]);
            }
        }
        for (int i = 0; i < evening.length; i++) {
            if (evening[i] != null) {
                request.param("eveningCounts[" + i + "]", evening[i]);
            }
        }
        return request;
    }

    private static String[] seven(String value) {
        return new String[] { value, value, value, value, value, value, value };
    }

    /** 上書きの保存。null の項目は送らない。 */
    private static MockHttpServletRequestBuilder override(String workDate, String slotId, String count) {
        MockHttpServletRequestBuilder request = post("/manager/required/overrides").param("ym", "2026-11");
        if (workDate != null) {
            request.param("workDate", workDate);
        }
        if (slotId != null) {
            request.param("slotId", slotId);
        }
        if (count != null) {
            request.param("requiredCount", count);
        }
        return request;
    }

    /** 既定値のフォームの、ある項目のエラーのメッセージコード */
    private static List<String> codes(MvcResult result, String field) {
        BindingResult binding = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + DEFAULT_FORM);
        return binding.getFieldErrors(field).stream()
                .map(e -> e.unwrap(ConstraintViolation.class).getMessageTemplate())
                .toList();
    }

    // ---------------------------------------------------------------- 初期表示（判定 No.1・2）

    @Test
    void 年月なしで表示する_今月の一覧と前後の月とフォームを入れる() throws Exception {
        mvc.perform(get("/manager/required"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/required"))
                .andExpect(model().attributeExists(DEFAULT_FORM, "requiredOverrideForm", "days"))
                .andExpect(model().attribute("ym", THIS_MONTH))
                .andExpect(model().attribute("prevYm", YearMonth.of(2026, 10)))
                .andExpect(model().attribute("nextYm", YearMonth.of(2026, 12)));

        verify(requiredStaffService).getMonth(THIS_MONTH);
    }

    @Test
    void 上書きのフォームの初期値_日付は空で枠は日勤() throws Exception {
        MvcResult result = mvc.perform(get("/manager/required")).andReturn();

        RequiredOverrideForm form = (RequiredOverrideForm) result.getModelAndView().getModel()
                .get("requiredOverrideForm");
        assertThat(form.getWorkDate()).isNull();
        assertThat(form.getSlotId()).isEqualTo(SlotIds.DAY);
        assertThat(form.getRequiredCount()).isNull();
    }

    @Test
    void 年月を指定して表示する_その月になり年をまたいで前後の月を出す() throws Exception {
        mvc.perform(get("/manager/required").param("ym", "2027-01"))
                .andExpect(model().attribute("ym", YearMonth.of(2027, 1)))
                .andExpect(model().attribute("prevYm", YearMonth.of(2026, 12)))
                .andExpect(model().attribute("nextYm", YearMonth.of(2027, 2)));
    }

    @ParameterizedTest
    @CsvSource({ "2026-13", "abc", "2026/11", "''" })
    void 年月の形が違う_今月になる(String ym) throws Exception {
        mvc.perform(get("/manager/required").param("ym", ym))
                .andExpect(model().attribute("ym", THIS_MONTH));
    }

    // ---------------------------------------------------------------- 既定値の保存（判定 No.3、6章 No.1）

    @ParameterizedTest
    @CsvSource({ "0", "20" })
    void 既定値が0か20_保存してI2501で画面へ戻る(String value) throws Exception {
        mvc.perform(defaults(seven(value), seven(value)))
                .andExpect(redirectedUrl("/manager/required"))
                .andExpect(flash().attribute("successMessage", "I2501"));

        Integer v = Integer.valueOf(value);
        verify(requiredStaffService).saveDefaults(List.of(v, v, v, v, v, v, v), List.of(v, v, v, v, v, v, v), 5L);
    }

    @ParameterizedTest
    @CsvSource({ "-1", "21" })
    void 既定値が範囲の外_E2502で再表示し保存しない(String value) throws Exception {
        String[] day = seven("1");
        day[3] = value;

        MvcResult result = mvc.perform(defaults(day, seven("1")))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/required"))
                .andReturn();

        assertThat(codes(result, "dayCounts[3]")).containsExactly("{E2502}");
        verify(requiredStaffService, never()).saveDefaults(anyList(), anyList(), anyLong());
    }

    @Test
    void 夕勤の既定値が空_E2501で再表示し今月の一覧を入れ直す() throws Exception {
        String[] evening = seven("1");
        evening[0] = "";

        MvcResult result = mvc.perform(defaults(seven("1"), evening))
                .andExpect(view().name("manager/required"))
                .andExpect(model().attribute("ym", THIS_MONTH))
                .andExpect(model().attributeExists("requiredOverrideForm", "days", "prevYm", "nextYm"))
                .andReturn();

        assertThat(codes(result, "eveningCounts[0]")).containsExactly("{E2501}");
        verify(requiredStaffService).getMonth(THIS_MONTH);
        verify(requiredStaffService, never()).saveDefaults(anyList(), anyList(), anyLong());
    }

    @Test
    void 既定値が7個そろっていない_E2501で再表示する() throws Exception {
        MvcResult result = mvc.perform(defaults(new String[] { "1", "1", "1" }, new String[0]))
                .andExpect(view().name("manager/required"))
                .andReturn();

        assertThat(codes(result, "dayCounts")).containsExactly("{E2501}");
        assertThat(codes(result, "eveningCounts")).containsExactly("{E2501}");
        verify(requiredStaffService, never()).saveDefaults(anyList(), anyList(), anyLong());
    }

    // ---------------------------------------------------------------- 上書きの保存（判定 No.4〜7、組み合わせ表）

    @Test
    void 上書きを保存する_日付の月へI2502で戻る() throws Exception {
        mvc.perform(override("2026-12-24", "2", "3"))
                .andExpect(redirectedUrl("/manager/required?ym=2026-12"))
                .andExpect(flash().attribute("successMessage", "I2502"));

        verify(requiredStaffService).saveOverride(LocalDate.of(2026, 12, 24), SlotIds.EVENING, 3, 5L);
    }

    @ParameterizedTest
    @CsvSource({ "0", "20" })
    void 上書きの人数が0か20_保存する(int count) throws Exception {
        mvc.perform(override("2026-11-03", "1", String.valueOf(count)))
                .andExpect(flash().attribute("successMessage", "I2502"));

        verify(requiredStaffService).saveOverride(LocalDate.of(2026, 11, 3), SlotIds.DAY, count, 5L);
    }

    @Test
    void 枠が3_サービスのE2504で元の月へ戻る() throws Exception {
        doThrow(new BusinessException("E2504"))
                .when(requiredStaffService).saveOverride(any(), any(), anyInt(), anyLong());

        mvc.perform(override("2026-12-24", "3", "3"))
                .andExpect(redirectedUrl("/manager/required?ym=2026-11"))
                .andExpect(flash().attribute("errorMessage", "E2504"));
    }

    @ParameterizedTest(name = "No.{0}")
    @CsvSource({
        // No., 日付, 枠, 人数, 出すコード（null は送らない）
        "1, ,           ,  , E2503",
        "2, ,           ,  5, E2503",
        "3, ,           1, , E2503",
        "4, ,           1, 5, E2503",
        "5, 2026-11-03, ,  , E2504",
        "6, 2026-11-03, ,  5, E2504",
        "7, 2026-11-03, 1, , E2501",
    })
    void 上書きの入力エラー_画面の左から最初の項目のコードを出す(int no, String workDate, String slotId,
            String count, String expected) throws Exception {
        mvc.perform(override(workDate, slotId, count))
                .andExpect(redirectedUrl("/manager/required?ym=2026-11"))
                .andExpect(flash().attribute("errorMessage", expected));

        verify(requiredStaffService, never()).saveOverride(any(), any(), anyInt(), anyLong());
    }

    @Test
    void 上書きの入力エラーが3項目_何回送っても日付のE2503になる() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(override(null, null, "21"))
                    .andExpect(flash().attribute("errorMessage", "E2503"));
        }
    }

    @ParameterizedTest
    @CsvSource({ "-1", "21" })
    void 上書きの人数が範囲の外_E2502(String count) throws Exception {
        mvc.perform(override("2026-11-03", "1", count))
                .andExpect(flash().attribute("errorMessage", "E2502"));
    }

    @ParameterizedTest
    @CsvSource({ "2026-02-30, 1, 3, E2503", "2026-11-03, abc, 3, E2504", "2026-11-03, 1, x, E2502" })
    void 値の形が違う_項目で決めたコードを出す(String workDate, String slotId, String count, String expected)
            throws Exception {
        mvc.perform(override(workDate, slotId, count))
                .andExpect(flash().attribute("errorMessage", expected));

        verify(requiredStaffService, never()).saveOverride(any(), any(), anyInt(), anyLong());
    }

    @Test
    void 上書きの入力エラーで年月の形が違う_今月へ戻る() throws Exception {
        mvc.perform(post("/manager/required/overrides").param("ym", "xx"))
                .andExpect(redirectedUrl("/manager/required?ym=2026-11"));
    }

    // ---------------------------------------------------------------- 上書きを戻す（6章 No.4）

    @Test
    void 上書きを戻す_元の月へI2503で戻る() throws Exception {
        mvc.perform(post("/manager/required/overrides/delete")
                .param("workDate", "2026-12-24").param("slotId", "1").param("ym", "2026-12"))
                .andExpect(redirectedUrl("/manager/required?ym=2026-12"))
                .andExpect(flash().attribute("successMessage", "I2503"));

        verify(requiredStaffService).deleteOverride(LocalDate.of(2026, 12, 24), SlotIds.DAY, 5L);
    }

    @Test
    void 戻すで日付がない_400でサービスを呼ばない() throws Exception {
        mvc.perform(post("/manager/required/overrides/delete").param("slotId", "1"))
                .andExpect(status().isBadRequest());

        verify(requiredStaffService, never()).deleteOverride(any(), any(), anyLong());
    }
}

package jp.numajo.fgroup.shift.controller.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.SubmissionState;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.PeriodService;
import jp.numajo.fgroup.shift.service.ShiftRequestService;

/**
 * StaffRequestController（SB-03：シフト希望提出）のテスト。
 *
 * <p>
 * 成功・業務エラー・入力エラーのときの行き先と、画面に渡す値を確かめる。画面の表示は、画面の確認（97_テスト計画書）で見る。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class StaffRequestControllerTests {

    private static final Long USER_ID = 5L;

    @Mock
    private ShiftRequestService shiftRequestService;
    @Mock
    private PeriodService periodService;

    private StaffRequestController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private ShiftRequestForm form;
    private BindingResult bindingResult;
    private LoginUser loginUser;

    @BeforeEach
    void setUp() {
        controller = new StaffRequestController(shiftRequestService, periodService);
        model = new ExtendedModelMap();
        redirectAttributes = new RedirectAttributesModelMap();
        form = new ShiftRequestForm();
        form.setPeriodId(1L);
        bindingResult = new BeanPropertyBindingResult(form, "shiftRequestForm");
        loginUser = mock(LoginUser.class);
    }

    private RequestPageView page() {
        PeriodOption option = new PeriodOption(1L, LocalDate.of(2099, 11, 1), LocalDate.of(2099, 11, 15),
                "11/1〜11/15", LocalDateTime.of(2099, 10, 15, 23, 59), "2099/10/15 23:59", "10/15 23:59",
                PeriodStatus.OPEN, "11/1〜11/15（期限 10/15）");
        return new RequestPageView(option, List.of(option), "2099/10/15 23:59", true, null, null,
                SubmissionState.NOT_SUBMITTED, "未提出",
                List.of(), "山田 太郎");
    }

    // ===== show =====

    @Test
    void show_受付中の期間がなければ空のフォームでpageはnull() {
        when(loginUser.getUserId()).thenReturn(USER_ID);
        when(shiftRequestService.getRequestPage(USER_ID, null)).thenReturn(null);

        String actual = controller.show(null, loginUser, model);

        assertThat(actual).isEqualTo("staff/requests");
        assertThat(model.getAttribute("page")).isNull();
        assertThat(model.getAttribute("shiftRequestForm")).isInstanceOf(ShiftRequestForm.class);
        verify(shiftRequestService, never()).toForm(any(), any());
    }

    @Test
    void show_期間があれば今の希望を入れたフォームと時刻の選択肢を渡す() {
        RequestPageView page = page();
        ShiftPeriod period = new ShiftPeriod();
        ShiftRequestForm current = new ShiftRequestForm();
        when(loginUser.getUserId()).thenReturn(USER_ID);
        when(shiftRequestService.getRequestPage(USER_ID, 1L)).thenReturn(page);
        when(periodService.getPeriod(1L)).thenReturn(period);
        when(shiftRequestService.toForm(USER_ID, period)).thenReturn(current);

        String actual = controller.show(1L, loginUser, model);

        assertThat(actual).isEqualTo("staff/requests");
        assertThat(model.getAttribute("page")).isSameAs(page);
        assertThat(model.getAttribute("shiftRequestForm")).isSameAs(current);
        assertThat((List<?>) model.getAttribute("timeOptions")).hasSize(29);
    }

    // ===== submit =====

    @Test
    void submit_入力エラーがあれば提出せず画面を再表示する() {
        RequestPageView page = page();
        when(loginUser.getUserId()).thenReturn(USER_ID);
        when(shiftRequestService.getRequestPage(USER_ID, 1L)).thenReturn(page);
        bindingResult.rejectValue("periodId", "E1301");

        String actual = controller.submit(form, bindingResult, loginUser, model, redirectAttributes);

        assertThat(actual).isEqualTo("staff/requests");
        assertThat(model.getAttribute("page")).isSameAs(page);
        assertThat((List<?>) model.getAttribute("timeOptions")).hasSize(29);
        assertThat(model.getAttribute("errorMessage")).isEqualTo("E1301");
        verify(shiftRequestService, never()).submit(any(), any());
    }

    @Test
    void submit_期間以外の入力エラーならE1302を画面に出す() {
        when(loginUser.getUserId()).thenReturn(USER_ID);
        when(shiftRequestService.getRequestPage(USER_ID, 1L)).thenReturn(page());
        bindingResult.rejectValue("days", "E1302");

        String actual = controller.submit(form, bindingResult, loginUser, model, redirectAttributes);

        assertThat(actual).isEqualTo("staff/requests");
        assertThat(model.getAttribute("errorMessage")).isEqualTo("E1302");
        verify(shiftRequestService, never()).submit(any(), any());
    }

    @Test
    void submit_成功したら提出画面へリダイレクトしてI1302を渡す() {
        when(loginUser.getUserId()).thenReturn(USER_ID);

        String actual = controller.submit(form, bindingResult, loginUser, model, redirectAttributes);

        assertThat(actual).isEqualTo("redirect:/staff/requests?periodId=1");
        assertThat(flashOf(redirectAttributes)).containsEntry("successMessage", "I1302");
        verify(shiftRequestService).submit(USER_ID, form);
    }

    @Test
    void submit_業務エラーならエラーのコードと引数を渡して提出画面へリダイレクトする() {
        when(loginUser.getUserId()).thenReturn(USER_ID);
        doThrow(new BusinessException("E1304", "11月1日(日)", "日勤"))
                .when(shiftRequestService).submit(USER_ID, form);

        String actual = controller.submit(form, bindingResult, loginUser, model, redirectAttributes);

        assertThat(actual).isEqualTo("redirect:/staff/requests?periodId=1");
        assertThat(flashOf(redirectAttributes)).containsEntry("errorMessage", "E1304")
                .doesNotContainKey("successMessage");
        assertThat((Object[]) redirectAttributes.getFlashAttributes().get("errorArgs"))
                .containsExactly("11月1日(日)", "日勤");
    }

    // ===== cancel =====

    @Test
    void cancel_成功したら提出画面へリダイレクトしてI1303を渡す() {
        when(loginUser.getUserId()).thenReturn(USER_ID);

        String actual = controller.cancel(1L, loginUser, redirectAttributes);

        assertThat(actual).isEqualTo("redirect:/staff/requests?periodId=1");
        assertThat(flashOf(redirectAttributes)).containsEntry("successMessage", "I1303");
        verify(shiftRequestService).cancel(USER_ID, 1L);
    }

    @Test
    void cancel_業務エラーならエラーのコードを渡して提出画面へリダイレクトする() {
        when(loginUser.getUserId()).thenReturn(USER_ID);
        doThrow(new BusinessException("E1303")).when(shiftRequestService).cancel(USER_ID, 1L);

        String actual = controller.cancel(1L, loginUser, redirectAttributes);

        assertThat(actual).isEqualTo("redirect:/staff/requests?periodId=1");
        assertThat(flashOf(redirectAttributes)).containsEntry("errorMessage", "E1303")
                .doesNotContainKey("successMessage");
    }

    /** フラッシュ属性を、AssertJ の containsEntry で比べられる Map<String, Object> にする。 */
    private static Map<String, Object> flashOf(RedirectAttributes redirectAttributes) {
        return new HashMap<String, Object>(redirectAttributes.getFlashAttributes());
    }
}

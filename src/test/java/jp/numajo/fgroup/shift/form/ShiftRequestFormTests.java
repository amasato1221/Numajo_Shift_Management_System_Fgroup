package jp.numajo.fgroup.shift.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jp.numajo.fgroup.shift.constant.RequestMode;

/**
 * ShiftRequestForm・DayRequestForm（SB-03）の入力チェックのテスト。
 *
 * <p>
 * メッセージは「{E1301}」の形のコードのまま比べる（コードから文への変換は Spring がやる）。
 * </p>
 */
class ShiftRequestFormTests {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private DayRequestForm validDay() {
        DayRequestForm day = new DayRequestForm();
        day.setWorkDate(LocalDate.of(2099, 11, 1));
        day.setMode(RequestMode.SLOT);
        return day;
    }

    private ShiftRequestForm validForm() {
        ShiftRequestForm form = new ShiftRequestForm();
        form.setPeriodId(1L);
        form.setDays(new ArrayList<>(List.of(validDay())));
        return form;
    }

    private List<String> codes(ShiftRequestForm form) {
        return validator.validate(form).stream()
                .map(v -> v.getPropertyPath() + "=" + v.getMessageTemplate())
                .sorted()
                .toList();
    }

    @Test
    void 初期値_日のリストは空でnullではない() {
        ShiftRequestForm form = new ShiftRequestForm();

        assertThat(form.getDays()).isNotNull().isEmpty();
    }

    @Test
    void 正しい値ならエラーなし() {
        assertThat(codes(validForm())).isEmpty();
    }

    @Test
    void 期間がnullならE1301() {
        ShiftRequestForm form = validForm();
        form.setPeriodId(null);

        assertThat(codes(form)).containsExactly("periodId={E1301}");
    }

    @Test
    void 日のリストが空ならE1302() {
        ShiftRequestForm form = validForm();
        form.setDays(new ArrayList<>());

        assertThat(codes(form)).containsExactly("days={E1302}");
    }

    @Test
    void 日のリストがnullならE1302() {
        ShiftRequestForm form = validForm();
        form.setDays(null);

        assertThat(codes(form)).containsExactly("days={E1302}");
    }

    @Test
    void 日付がnullならE1302() {
        ShiftRequestForm form = validForm();
        form.getDays().get(0).setWorkDate(null);

        assertThat(codes(form)).containsExactly("days[0].workDate={E1302}");
    }

    @Test
    void 入れ方がnullならE1302() {
        ShiftRequestForm form = validForm();
        form.getDays().get(0).setMode(null);

        assertThat(codes(form)).containsExactly("days[0].mode={E1302}");
    }

    @Test
    void 二日目の誤りは二日目の位置で報告する() {
        ShiftRequestForm form = validForm();
        DayRequestForm second = validDay();
        second.setMode(null);
        form.getDays().add(second);

        assertThat(codes(form)).containsExactly("days[1].mode={E1302}");
    }

    @Test
    void 日勤も夕勤も時刻もnullでよい() {
        ShiftRequestForm form = validForm();
        DayRequestForm day = form.getDays().get(0);

        assertThat(day.getDayShift()).isNull();
        assertThat(day.getEveningShift()).isNull();
        assertThat(day.getStartTime()).isNull();
        assertThat(day.getEndTime()).isNull();
        assertThat(codes(form)).isEmpty();
    }
}

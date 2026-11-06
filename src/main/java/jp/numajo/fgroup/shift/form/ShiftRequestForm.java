package jp.numajo.fgroup.shift.form;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * シフト希望の提出フォーム（SB-03・SM-06-1）。
 */
@Getter
@Setter
@NoArgsConstructor
public class ShiftRequestForm {

    /** 期間のID */
    @NotNull(message = "{E1301}")
    private Long periodId;

    /** 日ごとの希望 */
    @Valid
    @NotEmpty(message = "{E1302}")
    private List<DayRequestForm> days = new ArrayList<>();
}

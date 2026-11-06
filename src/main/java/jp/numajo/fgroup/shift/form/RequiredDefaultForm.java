package jp.numajo.fgroup.shift.form;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-05 曜日ごとの必要人数の入力（20-SM-05 1.1、30-07）。添字は 0＝日〜6＝土。
 *
 * <p>
 * 値が1つも送られないときに null にならないよう、空のリストで初期化する（@Size で E2501 にする）。
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
public class RequiredDefaultForm {

    /** 日勤の必要人数（7個） */
    @Size(min = 7, max = 7, message = "{E2501}")
    private List<@NotNull(message = "{E2501}") @Min(value = 0, message = "{E2502}")
            @Max(value = 20, message = "{E2502}") Integer> dayCounts = new ArrayList<>();

    /** 夕勤の必要人数（7個） */
    @Size(min = 7, max = 7, message = "{E2501}")
    private List<@NotNull(message = "{E2501}") @Min(value = 0, message = "{E2502}")
            @Max(value = 20, message = "{E2502}") Integer> eveningCounts = new ArrayList<>();
}

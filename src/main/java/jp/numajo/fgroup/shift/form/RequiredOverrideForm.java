package jp.numajo.fgroup.shift.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-05 日付ごとの必要人数（上書き）の入力（20-SM-05 1.2、30-07）。
 */
@Getter
@Setter
@NoArgsConstructor
public class RequiredOverrideForm {

    /** 日付 */
    @NotNull(message = "{E2503}")
    @DateTimeFormat(iso = ISO.DATE)
    private LocalDate workDate;

    /** 枠ID（1＝日勤、2＝夕勤） */
    @NotNull(message = "{E2504}")
    private Byte slotId;

    /** 必要人数 */
    @NotNull(message = "{E2501}")
    @Min(value = 0, message = "{E2502}")
    @Max(value = 20, message = "{E2502}")
    private Integer requiredCount;
}

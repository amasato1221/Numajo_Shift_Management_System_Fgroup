package jp.numajo.fgroup.shift.form;

import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-06 時間指定の割当の入力（30-07）。
 */
@Getter
@Setter
@NoArgsConstructor
public class TimeAssignmentForm {

    /** スタッフのユーザーID */
    @NotNull(message = "{E2606}")
    private Long userId;

    /** 日付 */
    @NotNull(message = "{E2606}")
    @DateTimeFormat(iso = ISO.DATE)
    private LocalDate workDate;

    /** 開始時刻 */
    @NotNull(message = "{E2607}")
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime startTime;

    /** 終了時刻 */
    @NotNull(message = "{E2607}")
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime endTime;
}

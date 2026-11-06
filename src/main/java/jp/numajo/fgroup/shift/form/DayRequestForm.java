package jp.numajo.fgroup.shift.form;

import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotNull;
import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.RequestMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * シフト希望の1日分（SB-03・SM-06-1）。
 */
@Getter
@Setter
@NoArgsConstructor
public class DayRequestForm {

    /** 日付（hidden） */
    @NotNull(message = "{E1302}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate workDate;

    /** 入れ方（SLOT：枠ごとに○×、TIME：時間指定） */
    @NotNull(message = "{E1302}")
    private RequestMode mode;

    /** 日勤：OK／NG／null（職能なし・未選択） */
    private Availability dayShift;

    /** 夕勤：OK／NG／null */
    private Availability eveningShift;

    /** 時間指定の開始 */
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime startTime;

    /** 時間指定の終了 */
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime endTime;
}

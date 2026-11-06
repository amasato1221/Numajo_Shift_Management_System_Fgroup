package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 次の出勤（SB-01、30-07）。
 *
 * @param workDate  勤務日
 * @param badge     シフトのバッジ（日勤・夕勤・時間指定）
 * @param startTime 開始時刻
 * @param endTime   終了時刻
 */
public record NextShiftView(LocalDate workDate, ShiftBadge badge, LocalTime startTime, LocalTime endTime) {
}

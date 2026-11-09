package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 次の出勤（SB-01、30-07）。
 *
 * @param workDate      勤務日
 * @param workDateLabel 勤務日の表示・月日と曜日（例：10月24日(土)）
 * @param badge         シフトのバッジ（日勤・夕勤・時間指定）
 * @param startTime     開始時刻
 * @param endTime       終了時刻
 * @param timeLabel     時間帯の表示（例：8:00〜17:00）
 */
public record NextShiftView(LocalDate workDate, String workDateLabel, ShiftBadge badge, LocalTime startTime,
        LocalTime endTime, String timeLabel) {
}

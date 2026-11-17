package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 時間指定の希望の1行（SM-06）（30-07）。
 *
 * @param userId    スタッフのユーザーID
 * @param name      氏名
 * @param workDate  日付
 * @param workDateLabel 日付の表示（月日と曜日。例：11月1日(日)）
 * @param startTime 開始時刻
 * @param endTime   終了時刻
 * @param timeLabel 時間帯の表示（例：13:00〜22:00）
 * @param assigned  同じ時間で割当済みか
 */
public record TimeRequestRow(Long userId, String name, LocalDate workDate, String workDateLabel, LocalTime startTime,
        LocalTime endTime, String timeLabel, boolean assigned) {
}

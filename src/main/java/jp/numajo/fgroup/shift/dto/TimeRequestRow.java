package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 時間指定の希望の1行（SM-06）（30-07）。
 *
 * @param userId    スタッフのユーザーID
 * @param name      氏名
 * @param workDate  日付
 * @param startTime 開始時刻
 * @param endTime   終了時刻
 * @param assigned  同じ時間で割当済みか
 */
public record TimeRequestRow(Long userId, String name, LocalDate workDate, LocalTime startTime, LocalTime endTime,
        boolean assigned) {
}

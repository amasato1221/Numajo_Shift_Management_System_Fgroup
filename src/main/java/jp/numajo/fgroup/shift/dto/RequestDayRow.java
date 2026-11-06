package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;

/**
 * シフト希望の入力表の1日分（SB-03・SM-06-1）。
 *
 * @param workDate         日付
 * @param dateLabel        日付の表示用の文字列（例：11月1日(日)）
 * @param dayQualified     日勤の職能があるか
 * @param eveningQualified 夕勤の職能があるか
 */
public record RequestDayRow(LocalDate workDate, String dateLabel, boolean dayQualified,
        boolean eveningQualified) {
}

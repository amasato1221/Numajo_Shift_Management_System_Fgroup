package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;

/**
 * 表の日付の列（SB-04・SM-06・SM-08、30-07）。セルは {@code row.cells[col.date]} で引く。
 *
 * @param date             日付
 * @param dateLabel        日付（短い）（例：11/1）
 * @param dayLabel         日だけ（例：1）
 * @param dayOfWeekLabel   曜日だけ（例：日）
 * @param dateWithDayLabel 月日と曜日（短い）（例：11/1(日)）
 */
public record DayColumn(LocalDate date, String dateLabel, String dayLabel, String dayOfWeekLabel,
        String dateWithDayLabel) {
}

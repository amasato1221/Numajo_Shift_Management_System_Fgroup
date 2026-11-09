package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;

/**
 * SM-05 日付ごとの必要人数の一覧の1行（30-07）。
 *
 * @param workDate          日付
 * @param workDateLabel     日付の表示・月日と曜日（例：11月1日(日)）
 * @param dayCount          日勤の必要人数
 * @param dayOverridden     日勤が上書きの値か
 * @param eveningCount      夕勤の必要人数
 * @param eveningOverridden 夕勤が上書きの値か
 */
public record RequiredDayView(LocalDate workDate, String workDateLabel, int dayCount, boolean dayOverridden, int eveningCount,
        boolean eveningOverridden) {
}

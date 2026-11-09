package jp.numajo.fgroup.shift.dto;

import java.time.YearMonth;
import java.util.List;

/**
 * 1か月のカレンダー（SB-01、30-07）。月曜はじまりで、週ごとに7日ずつ並べる。
 *
 * @param ym      表示する月
 * @param ymLabel 表示する月の表示・年月（例：2026年10月）
 * @param weeks  週ごとの日（1週は月曜〜日曜の7日）
 * @param prevYm 前の月（〈 のリンク）
 * @param nextYm 次の月（〉 のリンク）
 */
public record MonthCalendar(YearMonth ym, String ymLabel, List<List<CalendarDay>> weeks, YearMonth prevYm,
        YearMonth nextYm) {
}

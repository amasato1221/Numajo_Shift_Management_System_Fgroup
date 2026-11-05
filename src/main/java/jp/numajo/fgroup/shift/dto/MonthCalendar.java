package jp.numajo.fgroup.shift.dto;

import java.time.YearMonth;
import java.util.List;

/**
 * 1か月のカレンダー（SB-01、30-07）。月曜はじまりで、週ごとに7日ずつ並べる。
 *
 * @param ym     表示する月
 * @param weeks  週ごとの日（1週は月曜〜日曜の7日）
 * @param prevYm 前の月（〈 のリンク）
 * @param nextYm 次の月（〉 のリンク）
 */
public record MonthCalendar(YearMonth ym, List<List<CalendarDay>> weeks, YearMonth prevYm, YearMonth nextYm) {
}

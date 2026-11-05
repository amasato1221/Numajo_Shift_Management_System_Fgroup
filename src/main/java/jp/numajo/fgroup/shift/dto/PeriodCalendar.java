package jp.numajo.fgroup.shift.dto;

import java.util.List;

/**
 * 募集期間のカレンダー（SB-02、30-07）。月曜はじまりで、週ごとに7日ずつ並べる。
 *
 * @param period 表示する募集期間
 * @param weeks  週ごとの日（1週は月曜〜日曜の7日）。期間外の日は inMonth が false
 */
public record PeriodCalendar(PeriodOption period, List<List<CalendarDay>> weeks) {
}

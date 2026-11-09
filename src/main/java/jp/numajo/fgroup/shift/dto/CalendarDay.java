package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * カレンダーの1日のマス（20-SB-01 2.2、20-SB-02 2.1、30-07）。
 *
 * @param date     日付
 * @param dayLabel 日付の表示・日だけ（例：1、24）
 * @param inMonth  表示する範囲の日か（SB-01 はその月、SB-02 はその期間）。false の日はバッジを出さない
 * @param today    今日か
 * @param badges   その日のバッジ（開始時刻の順）
 * @param periodId その日を含む募集期間のID。なければ null
 */
public record CalendarDay(LocalDate date, String dayLabel, boolean inMonth, boolean today, List<ShiftBadge> badges,
        Long periodId) {
}

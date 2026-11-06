package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.util.DateTimeUtil;

/**
 * 割当表（SM-06）全体（30-07）。
 *
 * @param days   期間の日付（昇順）
 * @param slots  枠（ID 昇順）
 * @param rows   スタッフごとの行
 * @param counts 日付 →（枠ID → 割当数/必要数）
 */
public record AssignmentMatrix(List<LocalDate> days, List<Slot> slots, List<MatrixRow> rows,
        Map<LocalDate, Map<Byte, CountCell>> counts) {

    /**
     * 表の見出しの日付（91 の日付の表示：DateTimeUtil で作る）。
     *
     * @param day 日付
     * @return 例：11/1
     */
    public String dateLabel(LocalDate day) {
        return DateTimeUtil.shortDate(day);
    }

    /**
     * 表の見出しの曜日。
     *
     * @param day 日付
     * @return 例：日
     */
    public String dayOfWeekLabel(LocalDate day) {
        return DateTimeUtil.dayOfWeek(day);
    }
}

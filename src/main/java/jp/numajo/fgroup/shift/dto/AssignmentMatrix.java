package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jp.numajo.fgroup.shift.entity.Slot;

/**
 * 割当表（SM-06）全体（30-07）。
 *
 * @param days   期間の日付の列（昇順。見出し用の〜Label つき）
 * @param slots  枠（ID 昇順）
 * @param rows   スタッフごとの行
 * @param counts 日付 →（枠ID → 割当数/必要数）
 */
public record AssignmentMatrix(List<DayColumn> days, List<Slot> slots, List<MatrixRow> rows,
        Map<LocalDate, Map<Byte, CountCell>> counts) {
}

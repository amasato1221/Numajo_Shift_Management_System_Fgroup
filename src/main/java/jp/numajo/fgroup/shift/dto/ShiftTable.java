package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jp.numajo.fgroup.shift.constant.BadgeType;

/**
 * シフト表（SB-04・SM-08、30-07）。
 *
 * @param days 期間の日付（開始日から終了日まで）
 * @param rows スタッフごとの行（ふりがな順）
 */
public record ShiftTable(List<LocalDate> days, List<ShiftTableRow> rows) {

    /**
     * その日に、その種類のバッジがある人の名前を行の順に並べる（SB-04 のスマホのカード）。
     * 時間指定は「田中 13-22」のように時間を付ける。
     *
     * @param day  日付
     * @param type バッジの種類
     * @return 名前の一覧。いなければ空
     */
    public List<String> namesOn(LocalDate day, BadgeType type) {
        List<String> names = new ArrayList<>();
        for (ShiftTableRow row : rows) {
            for (ShiftBadge badge : row.cells().getOrDefault(day, List.of())) {
                if (badge.type() == type) {
                    names.add(badge.timeText() == null ? row.name() : row.name() + " " + badge.timeText());
                }
            }
        }
        return names;
    }
}

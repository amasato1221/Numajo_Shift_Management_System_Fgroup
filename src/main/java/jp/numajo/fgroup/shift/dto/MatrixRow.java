package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * 割当表（SM-06）の行（スタッフ1人）（30-07）。
 *
 * @param userId    スタッフのユーザーID
 * @param name      氏名
 * @param trainee   研修中か
 * @param submitted 希望を提出済みか
 * @param cells     日付 →（枠ID → セル）
 */
public record MatrixRow(Long userId, String name, boolean trainee, boolean submitted,
        Map<LocalDate, Map<Byte, MatrixCell>> cells) {
}

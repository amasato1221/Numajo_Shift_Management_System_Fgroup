package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * シフト表の1行＝スタッフ1人（SB-04・SM-08、30-07）。
 *
 * @param userId   スタッフのユーザーID
 * @param name     氏名
 * @param trainee  研修中か
 * @param cells    日付ごとのバッジ（開始時刻の順）。割当のない日は入らない
 * @param workDays 勤務日数（出勤の割当がある日の数。欠勤だけの日は数えない）
 */
public record ShiftTableRow(Long userId, String name, boolean trainee, Map<LocalDate, List<ShiftBadge>> cells,
        int workDays) {
}

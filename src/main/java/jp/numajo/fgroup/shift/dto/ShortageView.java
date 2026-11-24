package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;

/**
 * ダッシュボード（SM-01）の人数が足りない枠1件（30-07）。
 *
 * @param periodId      期間のID（「シフト作成へ」のリンク先）
 * @param workDate      日付
 * @param workDateLabel 日付の表示（月日と曜日。例：11月3日(火)）
 * @param slotName      アイコン付きの枠名（☀日勤・☾夕勤）
 * @param assigned      今の人数
 * @param required      必要人数
 */
public record ShortageView(Long periodId, LocalDate workDate, String workDateLabel, String slotName, int assigned,
        int required) {
}

package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;
import java.util.List;

import jp.numajo.fgroup.shift.constant.SubmissionState;

/**
 * シフト希望の提出画面（SB-03）の表示用。
 *
 * @param period          表示する期間
 * @param periodOptions   期間のプルダウンの選択肢
 * @param deadlineLabel   提出期限の表示用の文字列（例：2026/10/15 23:59）
 * @param editable        入力・提出できるか
 * @param submittedAt     提出した日時（未提出なら null）
 * @param submittedAtLabel 提出した日時の表示用の文字列（未提出なら null）
 * @param submissionState 提出の状態
 * @param statusLabel     提出の状態の表示名
 * @param days            入力表の日ごとの行
 * @param staffName       対象のバイトの氏名
 */
public record RequestPageView(PeriodOption period, List<PeriodOption> periodOptions, String deadlineLabel,
        boolean editable, LocalDateTime submittedAt, String submittedAtLabel, SubmissionState submissionState,
        String statusLabel, List<RequestDayRow> days, String staffName) {
}

package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * シフト作成画面（SM-06）に出す値（30-07）。
 *
 * @param period              表示中の期間
 * @param publishedAt         公開日時（公開済みでなければ null）
 * @param publishedAtLabel    公開日時の表示（例：2026/10/20 18:30。公開済みでなければ null）
 * @param periodOptions       期間のプルダウンの選択肢
 * @param matrix              割当表
 * @param warnings            警告
 * @param timeRequests        時間指定の希望
 * @param timeAssignments     時間指定の割当
 * @param editable            割当を変えられるか（作成中か公開済み）
 * @param publishable         公開できるか
 * @param notPublishableCode  公開できない理由のメッセージコード（W2604・W2605。公開できる・公開済みなら null）
 */
public record PeriodPageView(PeriodOption period, LocalDateTime publishedAt, String publishedAtLabel,
        List<PeriodOption> periodOptions,
        AssignmentMatrix matrix, List<ShiftWarning> warnings, List<TimeRequestRow> timeRequests,
        List<TimeAssignmentRow> timeAssignments, boolean editable, boolean publishable,
        String notPublishableCode) {
}

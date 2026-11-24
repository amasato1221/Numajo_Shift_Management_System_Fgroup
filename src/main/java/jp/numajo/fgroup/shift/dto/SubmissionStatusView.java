package jp.numajo.fgroup.shift.dto;

import java.util.List;

/**
 * ダッシュボード（SM-01）の希望の提出状況1期間分（30-07）。
 *
 * @param period           受付中の期間
 * @param submitted        提出した有効なバイトの人数
 * @param total            有効なバイトの人数
 * @param unsubmittedNames 未提出者の名前（ふりがな順）
 */
public record SubmissionStatusView(PeriodOption period, int submitted, int total, List<String> unsubmittedNames) {
}

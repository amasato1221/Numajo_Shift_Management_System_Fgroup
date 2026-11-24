package jp.numajo.fgroup.shift.dto;

/**
 * ダッシュボード（SM-01）の募集期間の表の1行（30-07）。
 *
 * @param period    期間
 * @param submitted 提出した有効なバイトの人数
 * @param total     有効なバイトの人数
 */
public record PeriodRowView(PeriodOption period, int submitted, int total) {
}

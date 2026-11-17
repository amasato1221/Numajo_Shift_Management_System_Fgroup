package jp.numajo.fgroup.shift.dto;

/**
 * シフトの公開（SM-06）の結果（30-07）。
 *
 * @param assignmentCount 公開した割当の数
 * @param hasWarnings     人数不足・職能不足・無効なスタッフの割当が残っているか
 */
public record PublishResult(int assignmentCount, boolean hasWarnings) {
}

package jp.numajo.fgroup.shift.dto;

/**
 * 外部コマンドの実行結果。
 *
 * @param exitCode 終了コード（起動できない・時間切れは -1）
 * @param timedOut 時間切れになったか
 * @param output   出力
 */
public record ProcessResult(int exitCode, boolean timedOut, String output) {
}

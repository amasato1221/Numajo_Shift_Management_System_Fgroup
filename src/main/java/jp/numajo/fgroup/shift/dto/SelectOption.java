package jp.numajo.fgroup.shift.dto;

/**
 * プルダウンの選択肢。値は送る形、label は画面の表示。
 *
 * @param value 送る値
 * @param label 表示
 */
public record SelectOption(String value, String label) {
}

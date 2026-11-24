package jp.numajo.fgroup.shift.dto;

/**
 * 店長アカウントの人数（SA-01 の管理メニュー）。
 *
 * @param active  有効な店長の人数
 * @param stopped 停止中の店長の人数
 */
public record ManagerCountView(long active, long stopped) {
}

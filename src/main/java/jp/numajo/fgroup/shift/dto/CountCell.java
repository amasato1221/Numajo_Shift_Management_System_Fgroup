package jp.numajo.fgroup.shift.dto;

/**
 * 割当表（SM-06）の最下行の「割当数/必要数」1つ（30-07）。
 *
 * @param assigned    割当数（出勤）
 * @param required    必要数
 * @param shortage    人数が足りないか
 * @param traineeOnly 研修中の人だけか
 */
public record CountCell(int assigned, int required, boolean shortage, boolean traineeOnly) {
}

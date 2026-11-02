package jp.numajo.fgroup.shift.dto;

/**
 * 職能の一覧の1行（SM-04）。
 *
 * @param id          職能ID
 * @param name        職能名
 * @param description 説明
 * @param holderCount 持っている人数（有効なバイトだけ）
 */
public record SkillRowView(Long id, String name, String description, long holderCount) {
}

package jp.numajo.fgroup.shift.constant;

/**
 * シフト作成の警告の種類。並び順（ordinal）は画面の並び順と同じ。
 */
public enum WarningType {
    /** 研修中の人だけ（公開できない） */
    TRAINEE_ONLY,
    /** 人数不足 */
    SHORTAGE,
    /** 職能不足 */
    SKILL,
    /** 無効なスタッフの割当（今日以降） */
    INACTIVE_STAFF
}

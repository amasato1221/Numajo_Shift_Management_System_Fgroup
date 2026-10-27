package jp.numajo.fgroup.shift.constant;

/**
 * スタッフ一覧（SM-02）の状態の絞り込み。
 */
public enum StaffStatusFilter {
    ALL, ACTIVE, INACTIVE;

    /** 文字列から変換する。null・不正な値は ACTIVE。 */
    public static StaffStatusFilter parse(String value) {
        if (value != null) {
            for (StaffStatusFilter f : values()) {
                if (f.name().equals(value)) {
                    return f;
                }
            }
        }
        return ACTIVE;
    }

    /** UserRepository#searchStaff に渡す値（ALL は null）。 */
    public Boolean toActiveParam() {
        return switch (this) {
            case ALL -> null;
            case ACTIVE -> Boolean.TRUE;
            case INACTIVE -> Boolean.FALSE;
        };
    }
}

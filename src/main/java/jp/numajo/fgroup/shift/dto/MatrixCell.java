package jp.numajo.fgroup.shift.dto;

/**
 * 割当表（SM-06）のセル1つ（30-07）。
 *
 * @param request      希望の記号
 * @param requestTitle 希望の記号に付ける説明（時間指定の「13:00-22:00」。なければ null）
 * @param assign       割当の記号
 * @param assignmentId 割当のID（割当がなければ null）
 * @param confirmCode  ボタンを押したときの確認のメッセージコード（C2602〜C2604。なければ null）
 * @param disabled     ボタンを押せないか（欠勤・時間指定の割当）
 */
public record MatrixCell(RequestMark request, String requestTitle, AssignMark assign, Long assignmentId,
        String confirmCode, boolean disabled) {

    /** 希望の記号（○、×、時、・、－）。 */
    public enum RequestMark {
        OK, NG, TIME, NOT_SUBMITTED, NO_SKILL
    }

    /** 割当の記号（空、●、◐、欠）。 */
    public enum AssignMark {
        NONE, SLOT, TIME, ABSENT
    }
}

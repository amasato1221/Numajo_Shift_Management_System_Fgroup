package jp.numajo.fgroup.shift.form;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-06 セルの割当の切替の入力（30-07）。入力チェックは Controller が正規表現で行う（E2601）。
 */
@Getter
@Setter
@NoArgsConstructor
public class AssignmentToggleForm {

    /** セル（「ユーザーID:日付:枠ID」。例：5:2026-11-01:1） */
    private String cell;
}

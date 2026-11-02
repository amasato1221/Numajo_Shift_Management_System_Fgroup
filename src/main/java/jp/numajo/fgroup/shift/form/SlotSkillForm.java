package jp.numajo.fgroup.shift.form;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 枠ごとの必要な職能のフォーム（SM-04）。
 */
@Getter
@Setter
@NoArgsConstructor
public class SlotSkillForm {

    /** 日勤に必要な職能のID */
    private List<Long> daySkillIds = new ArrayList<>();

    /** 夕勤に必要な職能のID */
    private List<Long> eveningSkillIds = new ArrayList<>();
}

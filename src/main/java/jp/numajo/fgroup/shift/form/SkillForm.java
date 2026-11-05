package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 職能の追加・変更フォーム（SM-04）。
 */
@Getter
@Setter
@NoArgsConstructor
public class SkillForm {

    /** 職能名 */
    @NotBlank(message = "{E2401}")
    @Size(max = 50, message = "{E2402}")
    private String name;

    /** 説明 */
    @Size(max = 200, message = "{E2403}")
    private String description;
}

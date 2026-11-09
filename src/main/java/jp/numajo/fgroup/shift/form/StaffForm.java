package jp.numajo.fgroup.shift.form;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スタッフの登録・編集フォーム（SM-03）。
 */
@Getter
@Setter
@NoArgsConstructor
public class StaffForm {

    /** 氏名 */
    @NotBlank(message = "{E2201}")
    @Size(max = 50, message = "{E2202}")
    private String name;

    /** ふりがな（ひらがな・長音・空白だけ） */
    @NotBlank(message = "{E2203}")
    @Size(max = 50, message = "{E2204}")
    @Pattern(regexp = "^[ぁ-ゖー 　]+$", message = "{E2205}")
    private String nameKana;

    /** ログインID（半角英数字4〜20文字） */
    @NotBlank(message = "{E2206}")
    @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "{E2207}")
    private String loginId;

    /** 職能のID */
    private List<Long> skillIds = new ArrayList<>();

    /** 研修中か */
    private boolean trainee;

    /** 有効か（登録は常に有効） */
    private boolean active = true;
}

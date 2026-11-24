package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 店長アカウントの作成フォーム（SA-02）。
 */
@Getter
@Setter
@NoArgsConstructor
public class ManagerForm {

    /** 氏名 */
    @NotBlank(message = "{E4201}")
    @Size(max = 50, message = "{E4202}")
    private String name;

    /** ふりがな（任意。ひらがな・長音・空白だけ） */
    @Size(max = 50, message = "{E4203}")
    @Pattern(regexp = "^[ぁ-ゖー 　]*$", message = "{E4204}")
    private String nameKana;

    /** ログインID（半角英数字4〜20文字） */
    @NotBlank(message = "{E4205}")
    @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "{E4206}")
    private String loginId;
}

package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SC-03 パスワード変更の入力（20-SC-03 1章、30-07）。
 */
@Getter
@Setter
@NoArgsConstructor
public class PasswordChangeForm {

    @NotBlank(message = "{E0301}")
    private String currentPassword;

    @NotBlank(message = "{E0302}")
    @Pattern(regexp = "^[\\x21-\\x7E]{8,64}$", message = "{E0303}")
    private String newPassword;

    @NotBlank(message = "{E0304}")
    private String confirmPassword;
}

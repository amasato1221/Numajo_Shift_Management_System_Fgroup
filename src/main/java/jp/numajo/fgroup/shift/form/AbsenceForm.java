package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-07 欠勤登録の入力（20-SM-07 2.2、30-07）。
 */
@Getter
@Setter
@NoArgsConstructor
public class AbsenceForm {

    /** 欠勤にする割当のID */
    @NotNull(message = "{E2701}")
    private Long assignmentId;

    /** 理由（任意） */
    @Size(max = 200, message = "{E2703}")
    private String reason;

    /** 通知する文（初期値は既定文。店長が書き換えてよい） */
    @NotBlank(message = "{E2704}")
    @Size(max = 500, message = "{E2705}")
    private String noticeMessage;
}

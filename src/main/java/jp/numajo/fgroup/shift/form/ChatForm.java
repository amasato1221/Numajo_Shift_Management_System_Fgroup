package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チャットの送信フォーム（SB-06・SM-11 で共用）。
 */
@Getter
@Setter
@NoArgsConstructor
public class ChatForm {

    /** メッセージ（500文字まで） */
    @NotBlank(message = "{E1601}")
    @Size(max = 500, message = "{E1602}")
    private String body;
}

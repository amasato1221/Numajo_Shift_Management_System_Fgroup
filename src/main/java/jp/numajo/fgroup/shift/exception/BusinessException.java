package jp.numajo.fgroup.shift.exception;

import lombok.Getter;

/**
 * 業務エラー。コントローラーで受け取り、メッセージコードを画面に出す。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 93_メッセージ一覧のコード（例：E1303） */
    private final String messageCode;
    /** メッセージの引数 */
    private final transient Object[] args;

    public BusinessException(String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
    }
}

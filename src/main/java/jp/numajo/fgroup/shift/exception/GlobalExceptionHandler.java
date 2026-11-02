package jp.numajo.fgroup.shift.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * 例外を受け取り、エラー画面（ER-01・ER-02）を出す。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** 対象が見つからない・URLの形が違う・必要なパラメーターがない・HTTPメソッドが違う → 404 */
    @ExceptionHandler({ ResourceNotFoundException.class, NoResourceFoundException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpRequestMethodNotSupportedException.class })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Exception e) {
        log.debug("404: {}", e.getMessage());
        return "error/404";
    }

    /** 予期しないエラー → 500 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception e) {
        log.error("予期しないエラー", e);
        return "error/500";
    }
}

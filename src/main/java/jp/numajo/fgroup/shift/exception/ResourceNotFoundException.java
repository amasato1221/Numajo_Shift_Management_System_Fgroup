package jp.numajo.fgroup.shift.exception;

/**
 * 対象が見つからない（または自分のものでない）。404 画面を出す。
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}

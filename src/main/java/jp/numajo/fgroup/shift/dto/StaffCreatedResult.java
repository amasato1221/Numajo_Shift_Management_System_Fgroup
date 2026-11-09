package jp.numajo.fgroup.shift.dto;

/**
 * スタッフ登録（SM-03）の結果。初期パスワードは、登録の直後に1回だけ画面に出す。
 *
 * @param userId          登録したユーザーのID
 * @param initialPassword 初期パスワード（平文）
 */
public record StaffCreatedResult(Long userId, String initialPassword) {
}

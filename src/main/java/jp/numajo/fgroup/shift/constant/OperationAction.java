package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 操作ログに記録する操作の種類（94_セキュリティ設計書 4章）。
 */
@Getter
@RequiredArgsConstructor
public enum OperationAction {
    LOGIN_SUCCESS("ログイン"),
    LOGIN_FAILURE("ログイン失敗"),
    LOGIN_LOCKED("アカウントのロック"),
    LOGOUT("ログアウト"),
    PASSWORD_CHANGE("パスワード変更"),
    STAFF_CREATE("スタッフの登録"),
    STAFF_UPDATE("スタッフの変更"),
    STAFF_PASSWORD_RESET("スタッフのパスワード初期化"),
    MANAGER_CREATE("店長アカウントの作成"),
    MANAGER_STOP("店長アカウントの停止"),
    MANAGER_RESUME("店長アカウントの再開"),
    MANAGER_PASSWORD_RESET("店長のパスワード初期化"),
    SKILL_CREATE("職能の追加"),
    SKILL_UPDATE("職能の変更"),
    SKILL_DELETE("職能の削除"),
    SLOT_SKILL_UPDATE("枠の必要職能の変更"),
    REQUIRED_UPDATE("必要人数の変更"),
    REQUEST_PROXY_UPDATE("希望の代理修正"),
    ASSIGNMENT_ADD("割当の追加"),
    ASSIGNMENT_REMOVE("割当の削除"),
    SHIFT_PUBLISH("シフトの公開"),
    ABSENCE_REGISTER("欠勤登録"),
    STORE_SETTING_UPDATE("店舗設定の変更"),
    BACKUP_CREATE("バックアップ作成");

    /** 画面に出す日本語名 */
    private final String label;
}

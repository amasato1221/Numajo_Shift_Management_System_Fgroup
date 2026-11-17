package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * チャット一覧（SM-10）の1行。1行は1人のバイト（30-07）。
 *
 * @param staffId     バイトのユーザーID
 * @param initial     丸いアイコンに出す氏名の1文字目
 * @param staffName   氏名（無効なバイトは末尾に「（無効）」）
 * @param active      有効なバイトか（無効なら氏名を灰色にする）
 * @param lastBody    最後のメッセージ（30文字を超えたら30文字で切り「…」を付ける。まだなければ null）
 * @param lastAt      最後のメッセージの日時（まだなければ null）
 * @param lastAtLabel 最後のメッセージの日時の表示（今日 H:mm／今年 M/d／それ以前 yyyy/M/d。まだなければ null）
 * @param unreadCount バイトが送った未読の数
 */
public record ChatListRow(Long staffId, String initial, String staffName, boolean active, String lastBody,
        LocalDateTime lastAt, String lastAtLabel, long unreadCount) {
}

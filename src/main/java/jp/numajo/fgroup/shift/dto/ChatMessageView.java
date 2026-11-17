package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * チャットの吹き出し1つ分（SB-06・SM-11 共用。fragments/chat-messages.html）。
 *
 * @param id             メッセージID
 * @param senderName     送信者の氏名
 * @param body           本文
 * @param createdAt      送信日時
 * @param createdAtLabel 送信日時の表示（M/d H:mm。例：10/15 23:59）
 * @param mine           見る人の側の発言か（右・緑にする。SB-06：自分が送った、SM-11：店長が送った）
 */
public record ChatMessageView(Long id, String senderName, String body, LocalDateTime createdAt,
        String createdAtLabel, boolean mine) {
}

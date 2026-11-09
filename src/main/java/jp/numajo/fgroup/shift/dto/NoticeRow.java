package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.NotificationType;

/**
 * お知らせの一覧の1行（SC-02、SB-01 の新しいお知らせ。30-07）。
 *
 * @param id        お知らせID
 * @param type      種類
 * @param typeLabel 種類の表示名（締切・公開など。type.getLabel()）
 * @param title     件名
 * @param body      本文（100文字を超えたら100文字で切り「…」を付ける）
 * @param createdAt      作った日時
 * @param createdAtLabel 作った日時の表示・日時（例：2026/10/16 09:00）
 * @param unread         未読か（read_at が null）
 */
public record NoticeRow(Long id, NotificationType type, String typeLabel, String title, String body,
        LocalDateTime createdAt, String createdAtLabel, boolean unread) {
}

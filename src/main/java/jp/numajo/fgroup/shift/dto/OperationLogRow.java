package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * 操作ログの一覧の1行（SA-03 の一覧、SA-01 の最近の操作。30-07）。
 *
 * @param id             操作ログID
 * @param createdAt      操作日時
 * @param createdAtLabel 操作日時の表示・日時（秒まで。例：2026/10/22 09:05:31）
 * @param userName       利用者の氏名（user_id が NULL なら「－」）
 * @param actionLabel    操作の日本語名（OperationAction の label）
 * @param target         対象
 * @param detail         内容
 * @param ipAddress      IPアドレス
 */
public record OperationLogRow(Long id, LocalDateTime createdAt, String createdAtLabel, String userName,
        String actionLabel, String target, String detail, String ipAddress) {
}

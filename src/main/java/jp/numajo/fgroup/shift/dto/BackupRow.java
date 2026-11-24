package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.BackupStatus;

/**
 * バックアップの履歴の1行（SA-04 の履歴、SA-01 の最新のバックアップ。30-07）。
 *
 * @param id             バックアップID
 * @param createdAt      作成日時
 * @param createdAtLabel 作成日時の表示・日時（例：2026/10/20 18:40）
 * @param fileName       ファイル名
 * @param fileSize       大きさ（バイト。失敗したときは null）
 * @param status         結果
 * @param createdByName  作った人の名前（ユーザーが消えて分からないときは「－」）
 */
public record BackupRow(Long id, LocalDateTime createdAt, String createdAtLabel, String fileName, Long fileSize,
        BackupStatus status, String createdByName) {

    /** 大きさを KB（小数1けた）で返す。失敗したときは「－」。 */
    public String sizeText() {
        return fileSize == null ? "－" : String.format("%.1f KB", fileSize / 1024.0);
    }
}

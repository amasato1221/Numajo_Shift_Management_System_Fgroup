package jp.numajo.fgroup.shift.dto;

import jp.numajo.fgroup.shift.constant.BackupStatus;

/**
 * バックアップを作った結果（SA-04。30-07）。
 *
 * @param status   結果（SUCCESS／FAILED）
 * @param fileName ファイル名（例：backup_20261020_184012.sql）
 */
public record BackupResult(BackupStatus status, String fileName) {
}

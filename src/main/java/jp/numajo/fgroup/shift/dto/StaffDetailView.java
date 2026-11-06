package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * スタッフ編集画面（SM-03）に出す状態の表示。
 *
 * @param id               ユーザーID
 * @param updatedAt        最終更新日時
 * @param updatedAtLabel   最終更新の表示（例：2026/10/12 21:03）。更新日時がなければ null
 * @param locked           ロック中か
 * @param lockedUntil      ロック解除日時
 * @param lockedUntilLabel ロック解除の時刻の表示（例：13:30）。ロック中でなければ null
 */
public record StaffDetailView(Long id, LocalDateTime updatedAt, String updatedAtLabel, boolean locked,
        LocalDateTime lockedUntil, String lockedUntilLabel) {
}

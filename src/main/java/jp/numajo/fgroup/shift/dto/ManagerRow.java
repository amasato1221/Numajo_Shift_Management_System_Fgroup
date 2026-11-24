package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * 店長アカウント管理（SA-02）の一覧の1行分。
 *
 * @param id             ユーザーID
 * @param name           氏名
 * @param loginId        ログインID
 * @param active         有効か
 * @param locked         ロック中か
 * @param createdAt      作成日時
 * @param createdAtLabel 作成日の表示（例：2026年10月21日）
 * @param lastActive     有効な店長がこの人だけか（停止の確認文を C4203 にする）
 */
public record ManagerRow(Long id, String name, String loginId, boolean active, boolean locked,
        LocalDateTime createdAt, String createdAtLabel, boolean lastActive) {
}

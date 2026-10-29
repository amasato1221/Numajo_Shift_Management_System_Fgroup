package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * お知らせの種類。
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
    DEADLINE("締切"),
    PUBLISHED("公開"),
    CHANGED("変更"),
    VACANCY("欠員"),
    CHAT("チャット");

    /** 画面に出す日本語名（10_基本設計書 5.7） */
    private final String label;
}

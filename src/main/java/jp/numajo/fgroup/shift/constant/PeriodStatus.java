package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 募集期間の状態（受付中・作成中・公開済み）。
 */
@Getter
@RequiredArgsConstructor
public enum PeriodStatus {
    OPEN("受付中"),
    CLOSED("作成中"),
    PUBLISHED("公開済み");

    /** 画面に出す日本語名（10_基本設計書 5.7） */
    private final String label;
}

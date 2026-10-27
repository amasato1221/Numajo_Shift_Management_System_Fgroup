package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望提出（SB-03）で表示する提出状況。
 */
@Getter
@RequiredArgsConstructor
public enum SubmissionState {
    /** 受付中で、まだ提出していない */
    NOT_SUBMITTED("未提出"),
    /** 受付中で、提出済み */
    SUBMITTED("提出済み"),
    /** 受付が終わった（提出したかどうかに関係なく） */
    CLOSED("受付終了");

    /** 画面に出す日本語名（10_基本設計書 5.7） */
    private final String label;
}

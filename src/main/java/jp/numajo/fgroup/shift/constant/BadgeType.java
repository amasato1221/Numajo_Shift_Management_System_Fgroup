package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフトバッジの種類と CSS クラス（20-00 4章）。
 */
@Getter
@RequiredArgsConstructor
public enum BadgeType {
    /** 公開済みの日勤 */
    DAY("badge-day"),
    /** 公開済みの夕勤 */
    EVENING("badge-evening"),
    /** 公開済みの時間指定 */
    TIME("badge-time"),
    /** 未公開の期間で、自分が○か時間指定を出している */
    PENDING("badge-pending"),
    /** 欠勤 */
    ABSENT("badge-absent");

    /** テンプレートで class に入れる値 */
    private final String cssClass;
}

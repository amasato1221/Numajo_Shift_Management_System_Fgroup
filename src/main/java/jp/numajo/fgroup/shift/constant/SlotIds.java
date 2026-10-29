package jp.numajo.fgroup.shift.constant;

/**
 * 枠のID（slots.id）。枠は固定の2つだけ。
 */
public final class SlotIds {

    /** 日勤 8:00〜17:00 */
    public static final byte DAY = 1;
    /** 夕勤 17:00〜22:00 */
    public static final byte EVENING = 2;

    private SlotIds() {
    }
}

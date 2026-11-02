package jp.numajo.fgroup.shift.util;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * シフトの時刻に関する共通処理。
 */
public final class ShiftTimeUtil {

    /** 時間指定の最小時刻 */
    public static final LocalTime MIN_TIME = LocalTime.of(8, 0);
    /** 時間指定の最大時刻 */
    public static final LocalTime MAX_TIME = LocalTime.of(22, 0);
    /** 時刻の刻み（分） */
    public static final int STEP_MINUTES = 30;

    private static final List<LocalTime> TIME_OPTIONS;

    static {
        List<LocalTime> list = new ArrayList<>();
        for (LocalTime t = MIN_TIME; !t.isAfter(MAX_TIME); t = t.plusMinutes(STEP_MINUTES)) {
            list.add(t);
        }
        TIME_OPTIONS = Collections.unmodifiableList(list);
    }

    private ShiftTimeUtil() {
    }

    /** 時刻プルダウンの選択肢（8:00〜22:00 の30分刻み、29個）。 */
    public static List<LocalTime> timeOptions() {
        return TIME_OPTIONS;
    }

    /** 8:00〜22:00 の範囲で、分が 0 か 30、秒が 0 なら true。null は false。 */
    public static boolean isOnGrid(LocalTime time) {
        return time != null
                && !time.isBefore(MIN_TIME) && !time.isAfter(MAX_TIME)
                && time.getMinute() % STEP_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    /** 2つの時間帯が重なるか（終わりと始まりが同じ時刻は重ならない）。 */
    public static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}

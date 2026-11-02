package jp.numajo.fgroup.shift.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 日付・時刻の表示形式（10_基本設計書 5.3）。
 */
public final class DateTimeUtil {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPANESE);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPANESE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d");
    private static final DateTimeFormatter SHORT_YEAR_DATE = DateTimeFormatter.ofPattern("yyyy/M/d");
    private static final DateTimeFormatter SHORT_DATE_TIME = DateTimeFormatter.ofPattern("M/d H:mm");
    private static final DateTimeFormatter DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter PRINT_START = DateTimeFormatter.ofPattern("yyyy/M/d(E)", Locale.JAPANESE);
    private static final DateTimeFormatter PRINT_END = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE);

    private DateTimeUtil() {
    }

    /** 例：10月25日(土) */
    public static String monthDay(LocalDate date) {
        return date.format(MONTH_DAY);
    }

    /** 例：2026年10月25日 */
    public static String fullDate(LocalDate date) {
        return date.format(FULL_DATE);
    }

    /** 例：2026/10/31 23:59 */
    public static String dateTime(LocalDateTime dateTime) {
        return dateTime.format(DATE_TIME);
    }

    /** 例：8:00、13:30 */
    public static String time(LocalTime time) {
        return time.format(TIME);
    }

    /** 例：11/1〜11/15 */
    public static String period(LocalDate start, LocalDate end) {
        return start.format(SHORT_DATE) + "〜" + end.format(SHORT_DATE);
    }

    /** 例：10/15 */
    public static String shortDate(LocalDate date) {
        return date.format(SHORT_DATE);
    }

    /** 例：2025/12/3 */
    public static String shortYearDate(LocalDate date) {
        return date.format(SHORT_YEAR_DATE);
    }

    /** 例：10/15 23:59 */
    public static String shortDateTime(LocalDateTime dateTime) {
        return dateTime.format(SHORT_DATE_TIME);
    }

    /** 例：2026/10/22 09:05:31 */
    public static String dateTimeSeconds(LocalDateTime dateTime) {
        return dateTime.format(DATE_TIME_SECONDS);
    }

    /** 印刷用の期間。例：2026/11/1(日)〜11/15(日) */
    public static String printPeriod(LocalDate start, LocalDate end) {
        return start.format(PRINT_START) + "〜" + end.format(PRINT_END);
    }

    /** 時間指定のバッジ。例：13-22、13:30-22、8-12:30 */
    public static String shortRange(LocalTime start, LocalTime end) {
        return shortTime(start) + "-" + shortTime(end);
    }

    private static String shortTime(LocalTime t) {
        return t.getMinute() == 0 ? String.valueOf(t.getHour()) : t.format(TIME);
    }
}

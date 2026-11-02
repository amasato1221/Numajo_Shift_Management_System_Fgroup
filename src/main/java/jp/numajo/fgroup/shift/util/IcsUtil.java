package jp.numajo.fgroup.shift.util;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * iCalendar（RFC 5545）の文字列を作る共通処理（IC-01）。
 */
public final class IcsUtil {

    /** 改行（iCalendar は CRLF） */
    public static final String CRLF = "\r\n";
    /** 1行の最大バイト数 */
    static final int MAX_LINE_BYTES = 75;

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcsUtil() {
    }

    /** 「\」「;」「,」改行をエスケープする。null は空文字。 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** 75バイトを超える行を「CRLF＋半角スペース」で折り返す。文字の途中では切らない。 */
    public static String fold(String line) {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        int limit = MAX_LINE_BYTES;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > limit) {
                sb.append(CRLF).append(' ');
                bytes = 1;
                limit = MAX_LINE_BYTES;
            }
            sb.append(ch);
            bytes += len;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 日本時間の日付と時刻を、UTC の「yyyyMMdd'T'HHmmss'Z'」にする。 */
    public static String utc(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(JST).withZoneSameInstant(ZoneOffset.UTC).format(UTC_FORMAT);
    }
}

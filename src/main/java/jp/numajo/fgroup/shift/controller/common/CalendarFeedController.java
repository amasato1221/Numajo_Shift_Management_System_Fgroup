package jp.numajo.fgroup.shift.controller.common;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import jp.numajo.fgroup.shift.service.CalendarFeedService;
import lombok.RequiredArgsConstructor;

/**
 * IC-01 カレンダー購読（.ics）。20-IC-01、30-06 1.5。
 *
 * <p>
 * Google カレンダーや Apple のカレンダーが読みに来る URL。ログインは不要で、トークンを知っている人だけが読める
 * （SecurityConfig で /ical/** を permitAll にしている）。画面はなく、iCalendar のテキストをそのまま返す。
 * </p>
 */
@Controller
@RequiredArgsConstructor
public class CalendarFeedController {

    /** 返すテキストの種類（20-IC-01 2-3） */
    private static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", StandardCharsets.UTF_8);
    /** ダウンロードしたときのファイル名（20-IC-01 2-3） */
    private static final String FILE_NAME = "shift.ics";

    private final CalendarFeedService calendarFeedService;

    /**
     * そのトークンの人の確定シフトを iCalendar 形式で返す（20-IC-01 2章）。
     * トークンの形（英数字・「_」「-」の43文字）が違う URL は、マッピングに合わないため Spring が 404 にする。
     *
     * @param token 購読用のトークン
     * @return iCalendar のテキスト（text/calendar）
     * @throws jp.numajo.fgroup.shift.exception.ResourceNotFoundException トークンがない、ユーザーがいない・無効
     */
    @GetMapping("/ical/{token:[A-Za-z0-9_-]{43}}.ics")
    public ResponseEntity<String> feed(@PathVariable String token) {
        String ics = calendarFeedService.buildIcs(token);
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(FILE_NAME).build()
                        .toString())
                .cacheControl(CacheControl.noCache())
                .body(ics);
    }
}

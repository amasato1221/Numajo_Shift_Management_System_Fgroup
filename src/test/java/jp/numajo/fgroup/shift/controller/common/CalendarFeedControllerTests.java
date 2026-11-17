package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.service.CalendarFeedService;
import lombok.RequiredArgsConstructor;

/**
 * CalendarFeedController（IC-01 カレンダー購読）のテスト（20-IC-01 6章、97_テスト計画書 4.2）。
 *
 * <p>
 * 本物の Spring Security と例外の処理（GlobalExceptionHandler）を使い、CalendarFeedService だけモックにする。
 * ログインしないで読めること、URL の形が違うと 404 になること、返すテキストの種類とヘッダーを確かめる。
 * トークンの絞り込み・割当の絞り込み・ICS の中身（6章 No.2〜10）は、CalendarFeedServiceTests と
 * NotificationCalendarServiceIntegrationTests で確かめている。
 * </p>
 *
 * <pre>
 * テストの観点（20-IC-01 6章）とこのクラスのテスト
 * No.1  形式が違うトークン（42文字・44文字・記号入り）| 404（サービスを呼ばない）
 * No.2〜4 存在しない・古い・無効なユーザーのトークン | サービスの ResourceNotFoundException が 404 になる
 * No.11 Content-Type                                  | text/calendar;charset=UTF-8
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class CalendarFeedControllerTests {

    /** 形の正しいトークン（英数字・「_」「-」の43文字） */
    private static final String TOKEN = "Ab3_-" + "x".repeat(38);
    private static final String ICS = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:シフト（山田 太郎）\r\nEND:VCALENDAR\r\n";

    private final WebApplicationContext context;

    @MockitoBean
    private CalendarFeedService calendarFeedService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 正しいトークン_ログインしなくてもiCalendarのテキストとヘッダーを返す() throws Exception {
        when(calendarFeedService.buildIcs(TOKEN)).thenReturn(ICS);

        String body = mvc.perform(get("/ical/" + TOKEN + ".ics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/calendar;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"shift.ics\""))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo(ICS);
    }

    @Test
    void トークンが42文字_404でサービスを呼ばない() throws Exception {
        mvc.perform(get("/ical/" + TOKEN.substring(1) + ".ics"))
                .andExpect(status().isNotFound());

        verify(calendarFeedService, never()).buildIcs(any());
    }

    @Test
    void トークンが44文字_404でサービスを呼ばない() throws Exception {
        mvc.perform(get("/ical/" + TOKEN + "x.ics"))
                .andExpect(status().isNotFound());

        verify(calendarFeedService, never()).buildIcs(any());
    }

    @Test
    void トークンに使えない記号が入る_404でサービスを呼ばない() throws Exception {
        mvc.perform(get("/ical/" + TOKEN.substring(1) + "~.ics"))
                .andExpect(status().isNotFound());

        verify(calendarFeedService, never()).buildIcs(any());
    }

    @Test
    void 拡張子がicsでない_404でサービスを呼ばない() throws Exception {
        mvc.perform(get("/ical/" + TOKEN + ".txt"))
                .andExpect(status().isNotFound());

        verify(calendarFeedService, never()).buildIcs(any());
    }

    @Test
    void 存在しない古い無効なユーザーのトークン_サービスの例外が404になる() throws Exception {
        when(calendarFeedService.buildIcs(TOKEN)).thenThrow(new ResourceNotFoundException("calendar feed"));

        mvc.perform(get("/ical/" + TOKEN + ".ics"))
                .andExpect(status().isNotFound());
    }
}

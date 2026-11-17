package jp.numajo.fgroup.shift.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import jp.numajo.fgroup.shift.service.NotificationService;

/**
 * DeadlineNotificationScheduler の単体テスト（95_通知・定期処理設計書 1.1・4章、30-08 4章、97_テスト計画書 4.1）。
 *
 * <p>
 * scheduler のクラスには @Profile("!test") が付き、test プロファイルでは Bean ができない（30-08 4章）。
 * そのため Spring を起動しない単体テストにし、NotificationService は @Mock、Clock は固定して
 * new DeadlineNotificationScheduler(notificationService, clock) で作る。
 * scheduler には「いつ呼ぶか」だけを書き、判定（if など）はないので、組み合わせ表は、
 * 渡す値・動かす時刻を1行ずつ並べた下の表にする。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * No. | 対象            | 確かめること                 | 期待する結果
 *  1  | sendReminders   | 渡す値                       | LocalDate.now(clock)（日本時間の今日）
 *  2  | sendReminders   | 日本時間で日付が変わる直後   | UTC では前日でも、日本時間の今日を渡す
 *  3  | 動かす時刻       | @Scheduled の cron と zone  | 毎日 9:00、Asia/Tokyo
 *  4  | 起動したとき     | @EventListener がない       | 起動時には動かさない（95 4.3）
 *  5  | テストのとき     | @Profile の値               | "!test"（test では Bean を作らない）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class DeadlineNotificationSchedulerTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Mock
    private NotificationService notificationService;

    private DeadlineNotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DeadlineNotificationScheduler(notificationService,
                clockAt(LocalDateTime.of(2026, 11, 17, 9, 0)));
    }

    private static Clock clockAt(LocalDateTime tokyoDateTime) {
        return Clock.fixed(tokyoDateTime.atZone(TOKYO).toInstant(), TOKYO);
    }

    @Test
    void 締切のお知らせを動かす_今日の日付を渡す() {
        when(notificationService.sendDeadlineReminders(LocalDate.of(2026, 11, 17))).thenReturn(3);

        scheduler.sendReminders();

        verify(notificationService).sendDeadlineReminders(LocalDate.of(2026, 11, 17));
    }

    @Test
    void 締切のお知らせを動かす_日本時間の0時台でも日本時間の今日を渡す() {
        // 日本時間 11/17 0:30 は UTC では 11/16 15:30
        DeadlineNotificationScheduler early = new DeadlineNotificationScheduler(notificationService,
                clockAt(LocalDateTime.of(2026, 11, 17, 0, 30)));

        early.sendReminders();

        verify(notificationService).sendDeadlineReminders(LocalDate.of(2026, 11, 17));
    }

    @Test
    void 動かす時刻_毎日9時で日本時間() throws NoSuchMethodException {
        Scheduled scheduled = DeadlineNotificationScheduler.class.getMethod("sendReminders")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 9 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void 起動したとき_動かさない() {
        boolean hasListener = Arrays.stream(DeadlineNotificationScheduler.class.getMethods())
                .anyMatch(m -> m.isAnnotationPresent(EventListener.class));

        assertThat(hasListener).isFalse();
    }

    @Test
    void テストのとき_Beanを作らない設定になっている() {
        Profile profile = DeadlineNotificationScheduler.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("!test");
    }
}

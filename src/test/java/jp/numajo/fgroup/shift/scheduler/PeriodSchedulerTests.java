package jp.numajo.fgroup.shift.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import jp.numajo.fgroup.shift.service.PeriodService;

/**
 * PeriodScheduler の単体テスト（95_通知・定期処理設計書 1.1、30-08 4章、97_テスト計画書 4.1）。
 *
 * <p>
 * scheduler のクラスには @Profile("!test") が付き、test プロファイルでは Bean ができない（30-08 4章）。
 * そのため Spring を起動しない単体テストにし、PeriodService は @Mock、Clock は固定して
 * new PeriodScheduler(periodService, clock) で作る。
 * scheduler には「いつ呼ぶか」だけを書き、判定（if など）はないので、組み合わせ表は、
 * 呼ぶもの・渡す値・動かす時刻を1行ずつ並べた下の表にする。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * No. | 対象            | 確かめること                                   | 期待する結果
 *  1  | onStartup       | 呼ぶ順番                                       | BT-02（締切）→ BT-01（作成）
 *  2  | onStartup       | 渡す値                                         | 今日の日付・今の日時（Clock）
 *  3  | createUpcoming  | 渡す値                                         | LocalDate.now(clock)
 *  4  | closeExpired    | 渡す値                                         | LocalDateTime.now(clock)
 *  5  | 動かす時刻       | @Scheduled の cron と zone                    | 0:05・0:00:30、Asia/Tokyo
 *  6  | 起動したとき     | @EventListener の対象                          | ApplicationReadyEvent
 *  7  | テストのとき     | @Profile の値                                  | "!test"（test では Bean を作らない）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class PeriodSchedulerTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 0, 5, 0);

    @Mock
    private PeriodService periodService;

    private PeriodScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TOKYO).toInstant(), TOKYO);
        scheduler = new PeriodScheduler(periodService, clock);
    }

    @Test
    void 起動したとき_締切のあとに作成を呼ぶ() {
        scheduler.onStartup();

        InOrder order = inOrder(periodService);
        order.verify(periodService).closeExpiredPeriods(NOW);
        order.verify(periodService).createUpcomingPeriods(NOW.toLocalDate());
    }

    @Test
    void 起動したとき_Clockの今日と今の日時を渡す() {
        scheduler.onStartup();

        verify(periodService).closeExpiredPeriods(LocalDateTime.of(2026, 10, 21, 0, 5, 0));
        verify(periodService).createUpcomingPeriods(LocalDate.of(2026, 10, 21));
    }

    @Test
    void 作成を動かす_今日の日付を渡す() {
        scheduler.createUpcoming();

        verify(periodService).createUpcomingPeriods(LocalDate.of(2026, 10, 21));
    }

    @Test
    void 締切を動かす_今の日時を渡す() {
        scheduler.closeExpired();

        verify(periodService).closeExpiredPeriods(LocalDateTime.of(2026, 10, 21, 0, 5, 0));
    }

    @Test
    void 動かす時刻_作成は0時5分で締切は0時0分30秒で日本時間() throws NoSuchMethodException {
        Scheduled create = PeriodScheduler.class.getMethod("createUpcoming").getAnnotation(Scheduled.class);
        Scheduled close = PeriodScheduler.class.getMethod("closeExpired").getAnnotation(Scheduled.class);

        assertThat(create.cron()).isEqualTo("0 5 0 * * *");
        assertThat(create.zone()).isEqualTo("Asia/Tokyo");
        assertThat(close.cron()).isEqualTo("30 0 0 * * *");
        assertThat(close.zone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void 起動したときの対象_ApplicationReadyEventだけ() throws NoSuchMethodException {
        EventListener listener = PeriodScheduler.class.getMethod("onStartup").getAnnotation(EventListener.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void テストのとき_Beanを作らない設定になっている() {
        Profile profile = PeriodScheduler.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("!test");
    }
}

package jp.numajo.fgroup.shift.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.service.PeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 募集期間の作成（BT-01）と締切（BT-02）。テストの中では動かさない。
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PeriodScheduler {

    private final PeriodService periodService;
    private final Clock clock;

    /** 起動したとき：締切 → 作成 の順に1回動かす。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        closeExpired();
        createUpcoming();
    }

    /** BT-01：毎日 0:05 */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Tokyo")
    public void createUpcoming() {
        int count = periodService.createUpcomingPeriods(LocalDate.now(clock));
        log.info("BT-01 募集期間を{}件作成しました", count);
    }

    /** BT-02：毎日 0:00:30 */
    @Scheduled(cron = "30 0 0 * * *", zone = "Asia/Tokyo")
    public void closeExpired() {
        int count = periodService.closeExpiredPeriods(LocalDateTime.now(clock));
        log.info("BT-02 募集期間を{}件締め切りました", count);
    }
}

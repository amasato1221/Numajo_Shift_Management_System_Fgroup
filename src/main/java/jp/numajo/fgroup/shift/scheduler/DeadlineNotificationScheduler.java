package jp.numajo.fgroup.shift.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 締切が近い人へのお知らせ（BT-03）。テストの中では動かさない。
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DeadlineNotificationScheduler {

    private final NotificationService notificationService;
    private final Clock clock;

    /** BT-03：毎日 9:00。起動時には動かさない（9:00 前に起動すると二重にならないよう、Service 側でも同じ日の送信済みを確かめる）。 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    public void sendReminders() {
        int count = notificationService.sendDeadlineReminders(LocalDate.now(clock));
        log.info("BT-03 締切のお知らせを{}件作成しました", count);
    }
}

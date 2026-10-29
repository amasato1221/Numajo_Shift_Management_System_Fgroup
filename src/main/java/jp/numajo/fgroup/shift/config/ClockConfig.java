package jp.numajo.fgroup.shift.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 現在日時を取るための Clock。テストでは固定した Clock に差し替える。
 */
@Configuration
public class ClockConfig {

    /** 日本時間の Clock */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }
}

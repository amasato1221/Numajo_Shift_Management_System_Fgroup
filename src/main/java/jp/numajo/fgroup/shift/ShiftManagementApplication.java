package jp.numajo.fgroup.shift;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * シフト管理システムの起動クラス。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ShiftManagementApplication {

    static {
        // JVMの既定のタイムゾーンを日本時間に固定する（時刻をDBへ入れるときに9時間ずれるのを防ぐ。テストでも効く）
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ShiftManagementApplication.class, args);
    }
}

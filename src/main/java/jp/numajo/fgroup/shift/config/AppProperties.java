package jp.numajo.fgroup.shift.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * application.yml の app.* の設定。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 購読URLの組み立てに使うURL（末尾の / なし） */
    private String baseUrl = "http://localhost:8080";

    /** バックアップの設定 */
    private Backup backup = new Backup();

    /**
     * app.backup.* の設定。
     */
    @Getter
    @Setter
    public static class Backup {
        /** mysqldump.exe の場所 */
        private String mysqldumpPath;
        /** 保存先フォルダ */
        private String dir;
        /** 待つ秒数 */
        private int timeoutSeconds = 120;
    }
}

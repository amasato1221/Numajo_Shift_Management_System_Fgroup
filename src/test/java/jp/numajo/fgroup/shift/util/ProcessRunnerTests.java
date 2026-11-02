package jp.numajo.fgroup.shift.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import jp.numajo.fgroup.shift.dto.ProcessResult;

/**
 * ProcessRunner の単体テスト（Windows の cmd・powershell で確認する）。
 */
@EnabledOnOs(OS.WINDOWS)
class ProcessRunnerTests {

    /** 5秒待つコマンド（cmd の timeout は入力待ちになるため powershell を使う） */
    private static final List<String> SLEEP_5_SECONDS =
            List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 5");

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void 成功_環境変数が渡る() {
        ProcessResult r = runner.run(List.of("cmd", "/c", "echo %MYSQL_PWD%"), Map.of("MYSQL_PWD", "secret"),
                Duration.ofSeconds(5));

        assertThat(r.exitCode()).isZero();
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output().trim()).isEqualTo("secret");
    }

    @Test
    void 失敗_終了コードとエラー出力() {
        ProcessResult r = runner.run(List.of("cmd", "/c", "echo err 1>&2 & exit /b 3"), Map.of(),
                Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.output().trim()).isEqualTo("err");
    }

    @Test
    void コマンドがない() {
        ProcessResult r = runner.run(List.of("C:\\no\\such\\command.exe"), Map.of(), Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void 時間切れ() {
        ProcessResult r = runner.run(SLEEP_5_SECONDS, Map.of(), Duration.ofMillis(300));

        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
    }

    @Test
    void 割り込み() {
        Thread.currentThread().interrupt();

        ProcessResult r = runner.run(SLEEP_5_SECONDS, Map.of(), Duration.ofSeconds(5));

        assertThat(r.output()).isEqualTo("interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }
}

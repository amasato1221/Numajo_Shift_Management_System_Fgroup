package jp.numajo.fgroup.shift.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import jp.numajo.fgroup.shift.dto.ProcessResult;

/**
 * ProcessRunner の単体テスト（Linux・Mac の sh で確認する。30-08 0章の5つの観点）。
 *
 * <p>
 * Windows では動かさない。Windows の確認は ProcessRunnerTests（97_テスト計画書 4.3）で行う。
 * クラウドの作業環境や GitHub Actions など、Windows 以外で ProcessRunner の分岐を確かめるために置く。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * - pb.start() が例外を投げるか（コマンドなし／それ以外）
 * - waitFor が時間内に終わらないか（時間切れ／成功・失敗）
 * - waitFor が割り込まれるか（割り込み／それ以外）
 * </pre>
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerShTests {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void 成功_環境変数が渡る() {
        ProcessResult r = runner.run(List.of("sh", "-c", "echo $MYSQL_PWD"), Map.of("MYSQL_PWD", "secret"),
                Duration.ofSeconds(5));

        assertThat(r.exitCode()).isZero();
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output().trim()).isEqualTo("secret");
    }

    @Test
    void 失敗_終了コードとエラー出力() {
        ProcessResult r = runner.run(List.of("sh", "-c", "echo err 1>&2; exit 3"), Map.of(), Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output().trim()).isEqualTo("err");
    }

    @Test
    void コマンドがない() {
        ProcessResult r = runner.run(List.of("/no/such/command"), Map.of(), Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output()).isNotEmpty();
    }

    @Test
    void 時間切れ() {
        ProcessResult r = runner.run(List.of("sh", "-c", "sleep 5"), Map.of(), Duration.ofMillis(300));

        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.output()).isEmpty();
    }

    @Test
    void 割り込み() {
        Thread.currentThread().interrupt();

        ProcessResult r = runner.run(List.of("sh", "-c", "sleep 5"), Map.of(), Duration.ofSeconds(5));

        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output()).isEqualTo("interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }
}

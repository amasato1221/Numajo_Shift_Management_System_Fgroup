package jp.numajo.fgroup.shift.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.dto.ProcessResult;

/**
 * 外部のコマンド（mysqldump）を実行する。BackupService のテストでモックにするため、ProcessBuilder を包む。
 */
@Component
public class ProcessRunner {

    /**
     * コマンドを実行し、終わるか時間切れになるまで待つ。
     *
     * @param command コマンドと引数（1つずつ分けたリスト。文字列をつなげない）
     * @param env     足す環境変数
     * @param timeout 待つ時間
     * @return 終了コード・時間切れか・出力（標準出力と標準エラーをまとめたもの）
     */
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // コマンドが見つからないなど
            return new ProcessResult(-1, false, e.getMessage());
        }
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, true, "");
            }
            return new ProcessResult(process.exitValue(), false, output.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, false, "interrupted");
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

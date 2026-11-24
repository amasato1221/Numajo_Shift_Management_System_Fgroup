package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.dto.BackupResult;
import jp.numajo.fgroup.shift.dto.BackupRow;
import jp.numajo.fgroup.shift.dto.ProcessResult;
import jp.numajo.fgroup.shift.entity.Backup;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.repository.BackupRepository;
import jp.numajo.fgroup.shift.util.ProcessRunner;

/**
 * BackupService の単体テスト（20-SA-04 2.1・2.2、6章 No.2〜6・8、97_テスト計画書 4.2）。
 *
 * <p>
 * ProcessRunner（ProcessBuilder を包んだクラス）をモックにし、mysqldump の代わりにテストの中でファイルを作る。
 * 保存先は JUnit の一時フォルダを使う。ログは Logback の ListAppender で受け取って確かめる
 * （コンソールの出力を見ると、Windows ではコンソールの文字コードが MS932 になり日本語が一致しないため）。本物の mysqldump での作成（No.1）と復元（No.7）は、Windows の PC で手で確かめる。
 * </p>
 *
 * <pre>
 * 判定                                     | 真                         | 偽                       | テスト
 * D1 !running.compareAndSet(false, true)   | E4402（作成中）             | 作成へ進む                | 作成中にもう一度押す_E4402
 * D2 Files.createDirectories が例外        | FAILED（コマンドは動かさない）| コマンドへ進む            | 保存先フォルダを作れない／ない（No.4）
 * D3 uri.getPort() == -1                   | 3306                       | URL のポート              | URLにポートがない_3306
 * D4 password == null                      | 空文字                     | そのまま                  | パスワードが設定されていない_空文字
 * D5 exitCode == 0 && size > 0             | SUCCESS                    | FAILED、ファイルを消す     | 下の複数条件の表
 * D6 Files.size が例外                     | 0（ファイルがない）          | ファイルの大きさ          | 終了コード0でファイルなし など
 * D7 Files.deleteIfExists が例外           | ログに出して続ける           | 消す                     | 途中のファイルを消せない
 * D8 output == null                        | 空文字                     | D9 へ                    | head_null
 * D9 output.length() <= 200                | そのまま                   | 先頭200文字               | head_200文字・201文字
 * D10 fileSize == null（create の中）        | FAILED                     | SUCCESS                  | 成功・失敗のテスト全部
 * D11 createdAt == null（toRow）            | 表示は null                | 日時の表示                | toRow_作成日時なし
 * D12 createdBy == null（toRow）            | 「－」                     | 名前                     | 履歴_作った人が消えている
 *
 * 複数条件 D5（A 終了コードが0, B 大きさ > 0） | 結果                    | テスト（No.）
 * （真, 真）                                 | SUCCESS、大きさが入る     | 正しい設定で作成_SUCCESS
 * （真, 偽）                                 | FAILED、ファイルは残らない | 終了コード0で空のファイル／ファイルなし
 * （偽, 真）                                 | FAILED、ファイルは残らない | パスワードが間違い（No.3）
 * （偽, 偽）                                 | FAILED                   | mysqldump のパスが間違い（No.2）、時間切れ（No.6）
 * A が偽のときは短絡評価で B を見ないが、組み合わせとしてテストする。
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTests {

    private static final String PASSWORD = "secret-pw-123";
    private static final String DUMP_PATH = "/opt/mysql/bin/mysqldump";
    private static final String FILE_NAME = "backup_20261020_184012.sql";

    @Mock
    private BackupRepository backupRepository;

    @Mock
    private BackupRecorder backupRecorder;

    @Mock
    private ProcessRunner processRunner;

    @TempDir
    private Path tempDir;

    private AppProperties props;
    private DataSourceProperties dataSourceProperties;
    private BackupService service;

    /** BackupService のログを受け取る */
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(BackupService.class)).addAppender(logs);
        props = new AppProperties();
        props.getBackup().setMysqldumpPath(DUMP_PATH);
        props.getBackup().setDir(tempDir.resolve("backup").toString());
        props.getBackup().setTimeoutSeconds(120);
        dataSourceProperties = new DataSourceProperties();
        dataSourceProperties.setUrl("jdbc:mysql://dbhost:3307/shift_management?connectionTimeZone=%2B09:00");
        dataSourceProperties.setUsername("shift_app");
        dataSourceProperties.setPassword(PASSWORD);
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 20, 18, 40, 12).atZone(ZoneId.of("Asia/Tokyo"))
                .toInstant(), ZoneId.of("Asia/Tokyo"));
        service = new BackupService(backupRepository, backupRecorder, processRunner, props, dataSourceProperties,
                clock);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BackupService.class)).detachAppender(logs);
    }

    // ---------------------------------------------------------------- 部品

    /** 受け取ったログ（引数を埋めたもの）を1つの文字列にする。 */
    private String logText() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private Path backupFile() {
        return tempDir.resolve("backup").resolve(FILE_NAME);
    }

    /** mysqldump の代わり：--result-file のファイルに content を書き（null なら書かない）、結果を返す。 */
    private void dumpWrites(String content, ProcessResult result) {
        when(processRunner.run(any(), any(), any())).thenAnswer(inv -> {
            if (content != null) {
                Files.writeString(backupFile(), content);
            }
            return result;
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedCommand() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(captor.capture(), any(), any());
        return captor.getValue();
    }

    private static Backup backup(long id, LocalDateTime createdAt, Long size, BackupStatus status, String userName) {
        Backup b = new Backup();
        b.setId(id);
        b.setCreatedAt(createdAt);
        b.setFileName("backup_" + id + ".sql");
        b.setFileSize(size);
        b.setStatus(status);
        if (userName != null) {
            User u = new User();
            u.setName(userName);
            b.setCreatedBy(u);
        }
        return b;
    }

    // ---------------------------------------------------------------- 2.1 履歴

    @Test
    void 履歴_新しい順の行を画面の形にする() {
        when(backupRepository.findLatest50()).thenReturn(List.of(
                backup(2, LocalDateTime.of(2026, 10, 20, 18, 40, 12), 2048L, BackupStatus.SUCCESS, "システム管理者"),
                backup(1, LocalDateTime.of(2026, 1, 5, 9, 3), null, BackupStatus.FAILED, null)));

        List<BackupRow> rows = service.findRecent(50);

        assertThat(rows).containsExactly(
                new BackupRow(2L, LocalDateTime.of(2026, 10, 20, 18, 40, 12), "2026/10/20 18:40", "backup_2.sql",
                        2048L, BackupStatus.SUCCESS, "システム管理者"),
                new BackupRow(1L, LocalDateTime.of(2026, 1, 5, 9, 3), "2026/01/05 09:03", "backup_1.sql",
                        null, BackupStatus.FAILED, "－"));
    }

    @Test
    void 履歴_limitより多いときはlimit件まで() {
        when(backupRepository.findLatest50()).thenReturn(List.of(
                backup(3, LocalDateTime.of(2026, 10, 3, 0, 0), 1L, BackupStatus.SUCCESS, "a"),
                backup(2, LocalDateTime.of(2026, 10, 2, 0, 0), 1L, BackupStatus.SUCCESS, "a"),
                backup(1, LocalDateTime.of(2026, 10, 1, 0, 0), 1L, BackupStatus.SUCCESS, "a")));

        assertThat(service.findRecent(2)).extracting(BackupRow::id).containsExactly(3L, 2L);
    }

    @Test
    void 履歴_0件なら空() {
        when(backupRepository.findLatest50()).thenReturn(List.of());

        assertThat(service.findRecent(50)).isEmpty();
    }

    @Test
    void 最新_1件あればその行() {
        when(backupRepository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.of(
                backup(7, LocalDateTime.of(2026, 10, 20, 18, 40), 1024L, BackupStatus.SUCCESS, "システム管理者")));

        assertThat(service.findLatest()).get()
                .extracting(BackupRow::id, BackupRow::createdAtLabel, BackupRow::createdByName)
                .containsExactly(7L, "2026/10/20 18:40", "システム管理者");
    }

    @Test
    void 最新_1件もなければ空() {
        when(backupRepository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());

        assertThat(service.findLatest()).isEmpty();
    }

    @Test
    void toRow_作成日時なし_表示もnull() {
        BackupRow row = BackupService.toRow(backup(1, null, 1L, BackupStatus.SUCCESS, "a"));

        assertThat(row.createdAt()).isNull();
        assertThat(row.createdAtLabel()).isNull();
    }

    // ---------------------------------------------------------------- 2.2 作成（No.1・4）

    @Test
    void 正しい設定で作成_SUCCESSで大きさを記録しコマンドは引数のリストで渡す() throws IOException {
        dumpWrites("-- MySQL dump\nCREATE TABLE x;\n", new ProcessResult(0, false, ""));

        BackupResult result = service.create(1L);

        assertThat(result).isEqualTo(new BackupResult(BackupStatus.SUCCESS, FILE_NAME));
        assertThat(backupFile()).exists();
        long size = Files.size(backupFile());
        verify(backupRecorder).record(FILE_NAME, size, BackupStatus.SUCCESS, 1L);
        assertThat(capturedCommand()).containsExactly(
                DUMP_PATH, "--host=dbhost", "--port=3307", "--user=shift_app",
                "--single-transaction", "--routines", "--no-tablespaces",
                "--default-character-set=utf8mb4",
                "--result-file=" + backupFile().toAbsolutePath(),
                "shift_management");
        verify(processRunner).run(any(), eq(Map.of("MYSQL_PWD", PASSWORD)), eq(Duration.ofSeconds(120)));
    }

    @Test
    void パスワードはコマンドの引数に入れない_No8() {
        dumpWrites("x", new ProcessResult(0, false, ""));

        service.create(1L);

        assertThat(capturedCommand()).noneMatch(arg -> arg.contains(PASSWORD));
    }

    @Test
    void 保存先フォルダがない_作られて成功_No4() {
        props.getBackup().setDir(tempDir.resolve("a").resolve("b").toString());
        when(processRunner.run(any(), any(), any())).thenAnswer(inv -> {
            Files.writeString(tempDir.resolve("a").resolve("b").resolve(FILE_NAME), "x");
            return new ProcessResult(0, false, "");
        });

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(tempDir.resolve("a").resolve("b")).isDirectory();
    }

    @Test
    void 保存先フォルダを作れない_FAILEDでコマンドは動かさない() throws IOException {
        // 保存先と同じ名前のファイルがあるとフォルダを作れない
        Path notDir = tempDir.resolve("file");
        Files.writeString(notDir, "x");
        props.getBackup().setDir(notDir.toString());

        BackupResult result = service.create(1L);

        assertThat(result).isEqualTo(new BackupResult(BackupStatus.FAILED, FILE_NAME));
        verify(processRunner, never()).run(any(), any(), any());
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
    }

    // ---------------------------------------------------------------- 2.2 作成の結果（D5 の組み合わせ）

    @Test
    void 終了コード0で空のファイル_FAILEDでファイルは残らない() {
        dumpWrites("", new ProcessResult(0, false, ""));

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        assertThat(backupFile()).doesNotExist();
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
    }

    @Test
    void 終了コード0でファイルなし_FAILED() {
        dumpWrites(null, new ProcessResult(0, false, ""));

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
    }

    @Test
    void パスワードが間違い_途中までのファイルは消してFAILED_No3() {
        dumpWrites("-- MySQL dump (途中まで)", new ProcessResult(2,
                false, "mysqldump: Got error: 1045: Access denied for user 'shift_app'"));

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        assertThat(backupFile()).doesNotExist();
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
    }

    @Test
    void mysqldumpのパスが間違い_FAILEDでファイルは残らない_No2() {
        dumpWrites(null, new ProcessResult(-1, false, "Cannot run program \"" + DUMP_PATH + "\""));

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        assertThat(backupFile()).doesNotExist();
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
    }

    @Test
    void 時間切れ_FAILEDで待つ秒数は設定どおり_No6() {
        props.getBackup().setTimeoutSeconds(1);
        dumpWrites(null, new ProcessResult(-1, true, ""));

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        verify(processRunner).run(any(), any(), eq(Duration.ofSeconds(1)));
    }

    @Test
    void 途中のファイルを消せない_ログに出してFAILEDを記録する() {
        // 結果のファイルの場所に中身のあるフォルダができていると、deleteIfExists が例外になる
        when(processRunner.run(any(), any(), any())).thenAnswer(inv -> {
            Files.createDirectories(backupFile());
            Files.writeString(backupFile().resolve("child"), "x");
            return new ProcessResult(1, false, "error");
        });

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.FAILED);
        verify(backupRecorder).record(FILE_NAME, null, BackupStatus.FAILED, 1L);
        assertThat(logText()).contains("途中までのバックアップのファイルを消せませんでした");
    }

    @Test
    void 失敗したときのログ_出力の先頭200文字だけでパスワードは出ない_No8() {
        String longOutput = "E".repeat(200) + "TAIL";
        dumpWrites(null, new ProcessResult(2, false, longOutput));

        service.create(1L);

        assertThat(logText()).contains("バックアップに失敗しました（終了コード 2、時間切れ false）：" + "E".repeat(200))
                .doesNotContain("TAIL").doesNotContain(PASSWORD);
    }

    // ---------------------------------------------------------------- 接続先の取り出し

    @Test
    void URLにポートがない_3306を使う() {
        dataSourceProperties.setUrl("jdbc:mysql://localhost/shift_test?characterEncoding=UTF-8");
        dumpWrites("x", new ProcessResult(0, false, ""));

        service.create(1L);

        assertThat(capturedCommand()).contains("--host=localhost", "--port=3306").endsWith("shift_test");
    }

    @Test
    void パスワードが設定されていない_MYSQL_PWDは空文字() {
        dataSourceProperties.setPassword(null);
        dumpWrites("x", new ProcessResult(0, false, ""));

        service.create(1L);

        verify(processRunner).run(any(), eq(Map.of("MYSQL_PWD", "")), any());
    }

    // ---------------------------------------------------------------- 同時に2つ動かさない（No.5）

    @Test
    void 作成中にもう一度押す_E4402で2回目はコマンドも記録もしない() {
        AtomicReference<Throwable> second = new AtomicReference<>();
        when(processRunner.run(any(), any(), any())).thenAnswer(inv -> {
            // 1回目の mysqldump の最中に2回目を押す
            try {
                service.create(2L);
            } catch (BusinessException e) {
                second.set(e);
            }
            Files.writeString(backupFile(), "x");
            return new ProcessResult(0, false, "");
        });

        BackupResult first = service.create(1L);

        assertThat(first.status()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(second.get()).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageCode()).isEqualTo("E4402");
        verify(backupRecorder, never()).record(anyString(), any(), any(), eq(2L));
    }

    @Test
    void 終わったら次は作れる_作成中の印はfinallyで戻す() {
        dumpWrites("x", new ProcessResult(0, false, ""));
        service.create(1L);
        try {
            Files.delete(backupFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertThat(service.create(1L).status()).isEqualTo(BackupStatus.SUCCESS);
    }

    @Test
    void 途中で例外が出ても作成中の印は戻す() {
        when(processRunner.run(any(), any(), any())).thenThrow(new IllegalStateException("想定外"));

        assertThatThrownBy(() -> service.create(1L)).isInstanceOf(IllegalStateException.class);

        // 2回目は E4402 にならず、また動く
        assertThatThrownBy(() -> service.create(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("想定外");
        verify(backupRecorder, never()).record(anyString(), any(), any(), anyLong());
    }

    // ---------------------------------------------------------------- head（出力の先頭200文字）

    @Test
    void head_null_空文字() {
        assertThat(BackupService.head(null)).isEmpty();
    }

    @ParameterizedTest(name = "{0}文字 → {1}文字")
    @CsvSource({ "0, 0", "199, 199", "200, 200", "201, 200" })
    void head_200文字までそのまま_超えたら200文字で切る(int length, int expected) {
        assertThat(BackupService.head("a".repeat(length))).hasSize(expected);
    }
}

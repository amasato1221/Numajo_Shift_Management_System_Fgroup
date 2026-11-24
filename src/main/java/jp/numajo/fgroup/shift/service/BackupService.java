package jp.numajo.fgroup.shift.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.dto.BackupResult;
import jp.numajo.fgroup.shift.dto.BackupRow;
import jp.numajo.fgroup.shift.dto.ProcessResult;
import jp.numajo.fgroup.shift.entity.Backup;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.repository.BackupRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * バックアップの作成と履歴（20-SA-04、30-05 4.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    /** ファイル名の日時の形（例：20261020_184012） */
    static final DateTimeFormatter FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** ログファイルに出す、コマンドの出力の最大文字数 */
    static final int OUTPUT_LOG_MAX = 200;

    /** URL にポートがないときの MySQL のポート */
    static final int DEFAULT_PORT = 3306;

    /** 作った人が分からないとき（ユーザーが消えたとき）の表示 */
    static final String UNKNOWN_USER = "－";

    private final BackupRepository backupRepository;
    private final BackupRecorder backupRecorder;
    private final ProcessRunner processRunner;
    private final AppProperties props;
    private final DataSourceProperties dataSourceProperties;
    private final Clock clock;

    /** 作成中か（同時に2つ動かさないため。20-SA-04 2.2-1-1） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 新しい順の履歴（20-SA-04 2.1）。
     *
     * @param limit 件数（50 だけを使う）
     * @return 履歴
     */
    @Transactional(readOnly = true)
    public List<BackupRow> findRecent(int limit) {
        return backupRepository.findLatest50().stream().limit(limit).map(BackupService::toRow).toList();
    }

    /**
     * 一番新しいバックアップ（20-SA-01）。
     *
     * @return 一番新しい1件。1件もなければ空
     */
    @Transactional(readOnly = true)
    public Optional<BackupRow> findLatest() {
        return backupRepository.findFirstByOrderByCreatedAtDescIdDesc().map(BackupService::toRow);
    }

    /**
     * mysqldump でバックアップを作り、結果を記録する（20-SA-04 2.2）。
     *
     * <p>
     * mysqldump の実行中にトランザクションを開いたままにしないため、このメソッドはトランザクションの外で動かし、
     * 記録は BackupRecorder が別のトランザクションで行う。
     * </p>
     *
     * @param adminUserId 押した管理者のユーザーID
     * @return 結果とファイル名
     * @throws BusinessException ほかのバックアップを作成中のとき（E4402）
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BackupResult create(Long adminUserId) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException("E4402");
        }
        try {
            String fileName = "backup_" + LocalDateTime.now(clock).format(FILE_NAME_TIME) + ".sql";
            Path file = Path.of(props.getBackup().getDir()).resolve(fileName);
            Long fileSize = dump(file);
            BackupStatus status = fileSize == null ? BackupStatus.FAILED : BackupStatus.SUCCESS;
            backupRecorder.record(fileName, fileSize, status, adminUserId);
            return new BackupResult(status, fileName);
        } finally {
            running.set(false);
        }
    }

    /**
     * mysqldump を実行する（20-SA-04 2.2-3〜6）。
     *
     * @param file 作るファイル
     * @return 成功したらファイルの大きさ（バイト）、失敗したら null
     */
    private Long dump(Path file) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn("バックアップの保存先フォルダを作れませんでした：{}", e.toString());
            return null;
        }
        // jdbc:mysql://{host}:{port}/{db}?... から取り出す
        URI uri = URI.create(dataSourceProperties.getUrl().substring(5));
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        String dbName = uri.getPath().substring(1);
        // 文字列をつなげて1本のコマンドにしない。パスワードは引数に入れず、環境変数 MYSQL_PWD で渡す
        List<String> command = List.of(
                props.getBackup().getMysqldumpPath(),
                "--host=" + uri.getHost(), "--port=" + port, "--user=" + dataSourceProperties.getUsername(),
                "--single-transaction", "--routines", "--no-tablespaces",
                "--default-character-set=utf8mb4",
                "--result-file=" + file.toAbsolutePath(),
                dbName);
        String password = dataSourceProperties.getPassword() == null ? "" : dataSourceProperties.getPassword();
        ProcessResult result = processRunner.run(command, Map.of("MYSQL_PWD", password),
                Duration.ofSeconds(props.getBackup().getTimeoutSeconds()));
        long size = sizeOf(file);
        if (result.exitCode() == 0 && size > 0) {
            return size;
        }
        log.warn("バックアップに失敗しました（終了コード {}、時間切れ {}）：{}", result.exitCode(), result.timedOut(),
                head(result.output()));
        deleteQuietly(file);
        return null;
    }

    /** ファイルの大きさ。ファイルがない・読めないときは 0。 */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 途中までのファイルがあれば消す。消せなくてもログに出すだけにする。 */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("途中までのバックアップのファイルを消せませんでした：{}", e.toString());
        }
    }

    /** コマンドの出力の先頭200文字（null なら空文字）。 */
    static String head(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= OUTPUT_LOG_MAX ? output : output.substring(0, OUTPUT_LOG_MAX);
    }

    /** エンティティを画面の1行にする。 */
    static BackupRow toRow(Backup backup) {
        return new BackupRow(backup.getId(), backup.getCreatedAt(),
                backup.getCreatedAt() == null ? null : DateTimeUtil.dateTime(backup.getCreatedAt()),
                backup.getFileName(), backup.getFileSize(), backup.getStatus(),
                backup.getCreatedBy() == null ? UNKNOWN_USER : backup.getCreatedBy().getName());
    }
}

package jp.numajo.fgroup.shift.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.Backup;
import jp.numajo.fgroup.shift.repository.BackupRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * バックアップの結果を backups と操作ログに記録する（20-SA-04 2.2-7、30-05 4.3）。
 *
 * <p>
 * BackupService の中のメソッドから呼ぶと {@code @Transactional} が効かないため、別のクラスにしている。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class BackupRecorder {

    private final BackupRepository backupRepository;
    private final UserRepository userRepository;
    private final OperationLogService operationLogService;

    /**
     * backups に1行入れ、操作ログ BACKUP_CREATE を残す（新しいトランザクション）。
     *
     * @param fileName    ファイル名
     * @param fileSize    大きさ（バイト。失敗なら null）
     * @param status      結果
     * @param adminUserId 作った管理者のユーザーID
     * @return 入れた行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Backup record(String fileName, Long fileSize, BackupStatus status, Long adminUserId) {
        Backup backup = new Backup();
        backup.setFileName(fileName);
        backup.setFileSize(fileSize);
        backup.setStatus(status);
        backup.setCreatedBy(userRepository.getReferenceById(adminUserId));
        Backup saved = backupRepository.save(backup);
        operationLogService.record(adminUserId, OperationAction.BACKUP_CREATE, "backup:" + saved.getId(),
                "file=" + fileName + " status=" + status.name());
        return saved;
    }
}

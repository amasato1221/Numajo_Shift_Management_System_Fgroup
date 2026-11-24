package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.Backup;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.BackupRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * BackupRecorder の単体テスト（20-SA-04 2.2-7）。
 *
 * <p>
 * 分岐はないので、成功・失敗の2通りで backups の値と操作ログの target・detail を確かめる（命令網羅）。
 * 別のトランザクション（REQUIRES_NEW）で DB に残ることは AdminBackupFlowTests で確かめる。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class BackupRecorderTests {

    @Mock
    private BackupRepository backupRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private BackupRecorder backupRecorder;

    @ParameterizedTest(name = "{1}")
    @CsvSource(nullValues = "null", value = { "2048, SUCCESS", "null, FAILED" })
    void 記録する_backupsに1行と操作ログBACKUP_CREATE(Long size, BackupStatus status) {
        User admin = new User();
        admin.setId(1L);
        when(userRepository.getReferenceById(1L)).thenReturn(admin);
        when(backupRepository.save(any(Backup.class))).thenAnswer(inv -> {
            Backup b = inv.getArgument(0);
            b.setId(10L);
            return b;
        });

        Backup saved = backupRecorder.record("backup_20261020_184012.sql", size, status, 1L);

        ArgumentCaptor<Backup> captor = ArgumentCaptor.forClass(Backup.class);
        verify(backupRepository).save(captor.capture());
        Backup b = captor.getValue();
        assertThat(saved).isSameAs(b);
        assertThat(b.getFileName()).isEqualTo("backup_20261020_184012.sql");
        assertThat(b.getFileSize()).isEqualTo(size);
        assertThat(b.getStatus()).isEqualTo(status);
        assertThat(b.getCreatedBy()).isSameAs(admin);
        verify(operationLogService).record(1L, OperationAction.BACKUP_CREATE, "backup:10",
                "file=backup_20261020_184012.sql status=" + status.name());
    }

    @Test
    void 操作ログの名前はバックアップ作成() {
        assertThat(OperationAction.BACKUP_CREATE.getLabel()).isEqualTo("バックアップ作成");
    }
}

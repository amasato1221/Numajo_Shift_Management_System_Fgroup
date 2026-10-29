package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * バックアップ履歴（backups）のエンティティ。
 */
@Entity
@Table(name = "backups")
@Getter
@Setter
@NoArgsConstructor
public class Backup {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ファイル名 */
    @Column(name = "file_name")
    private String fileName;

    /** サイズ（バイト） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 結果 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BackupStatus status;

    /** 作成した人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** 作成日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 操作ログ（operation_logs）のエンティティ。
 */
@Entity
@Table(name = "operation_logs")
@Getter
@Setter
@NoArgsConstructor
public class OperationLog {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 操作した人（不明時はnull） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 操作の種類 */
    @Column(name = "action")
    private String action;

    /** 対象 */
    @Column(name = "target")
    private String target;

    /** 詳細 */
    @Column(name = "detail")
    private String detail;

    /** IPアドレス */
    @Column(name = "ip_address")
    private String ipAddress;

    /** 操作日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

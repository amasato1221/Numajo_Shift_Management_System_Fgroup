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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 欠勤（absences）のエンティティ。
 */
@Entity
@Table(name = "absences")
@Getter
@Setter
@NoArgsConstructor
public class Absence {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 欠勤になった割当 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private ShiftAssignment assignment;

    /** 登録した店長 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by")
    private User registeredBy;

    /** 理由 */
    @Column(name = "reason")
    private String reason;

    /** 欠員通知の文面 */
    @Column(name = "notice_message")
    private String noticeMessage;

    /** 通知日時 */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

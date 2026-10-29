package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
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
 * 割当（確定シフト）（shift_assignments）のエンティティ。
 */
@Entity
@Table(name = "shift_assignments")
@Getter
@Setter
@NoArgsConstructor
public class ShiftAssignment {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 募集期間 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    private ShiftPeriod period;

    /** 割り当てた人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 日付 */
    @Column(name = "work_date")
    private LocalDate workDate;

    /** 枠（時間指定のときはnull） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private Slot slot;

    /** 開始時刻 */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻 */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 出勤・欠勤 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AssignmentStatus status;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

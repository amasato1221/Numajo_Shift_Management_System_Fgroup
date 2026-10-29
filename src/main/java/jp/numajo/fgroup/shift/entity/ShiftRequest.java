package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.Availability;
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
 * シフト希望（shift_requests）のエンティティ。
 */
@Entity
@Table(name = "shift_requests")
@Getter
@Setter
@NoArgsConstructor
public class ShiftRequest {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 募集期間 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    private ShiftPeriod period;

    /** 希望を出した人 */
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

    /** ○・×・時間指定 */
    @Enumerated(EnumType.STRING)
    @Column(name = "availability")
    private Availability availability;

    /** 開始時刻（時間指定のみ） */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻（時間指定のみ） */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

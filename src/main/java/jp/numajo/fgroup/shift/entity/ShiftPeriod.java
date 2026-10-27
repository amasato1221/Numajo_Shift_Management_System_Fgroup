package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 募集期間（shift_periods）のエンティティ。
 */
@Entity
@Table(name = "shift_periods")
@Getter
@Setter
@NoArgsConstructor
public class ShiftPeriod {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 開始日 */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 終了日 */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** 提出期限 */
    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    /** 状態 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PeriodStatus status;

    /** 公開日時 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}

package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;

/**
 * 募集期間（shift_periods）のリポジトリ。
 */
public interface ShiftPeriodRepository extends JpaRepository<ShiftPeriod, Long> {

    /** 開始日で取得する（BT-01 の重複チェック）。 */
    boolean existsByStartDate(LocalDate startDate);

    /** 状態で取得する（開始日の昇順）。 */
    List<ShiftPeriod> findByStatusOrderByStartDateAsc(PeriodStatus status);

    /** 状態で1件目（開始日の昇順）。 */
    Optional<ShiftPeriod> findFirstByStatusOrderByStartDateAsc(PeriodStatus status);

    /** 開始日の範囲（SB-02 の選択肢）。 */
    List<ShiftPeriod> findByStartDateBetweenOrderByStartDateAsc(LocalDate from, LocalDate to);

    /** 状態と開始日（SB-04 の選択肢、新しい順）。 */
    List<ShiftPeriod> findByStatusAndStartDateGreaterThanEqualOrderByStartDateDesc(PeriodStatus status,
            LocalDate from);

    /** 終了日が指定日以降（SM-01・SM-06 の選択肢）。 */
    List<ShiftPeriod> findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate from);

    /** 指定日を含む期間。 */
    @Query("SELECT p FROM ShiftPeriod p WHERE p.startDate <= :date AND p.endDate >= :date")
    Optional<ShiftPeriod> findContaining(@Param("date") LocalDate date);

    /** 指定日より後に始まる最初の期間。 */
    Optional<ShiftPeriod> findFirstByStartDateGreaterThanOrderByStartDateAsc(LocalDate date);

    /** 範囲と重なる期間（SB-01 のカレンダー）。 */
    @Query("""
            SELECT p FROM ShiftPeriod p WHERE p.startDate <= :to AND p.endDate >= :from
            ORDER BY p.startDate ASC
            """)
    List<ShiftPeriod> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 受付中・期限内で、その人が未提出の期間（SB-01）。 */
    @Query("""
            SELECT p FROM ShiftPeriod p
            WHERE p.status = jp.numajo.fgroup.shift.constant.PeriodStatus.OPEN AND p.deadlineAt >= :now
              AND NOT EXISTS (SELECT 1 FROM RequestSubmission s
                              WHERE s.id.periodId = p.id AND s.id.userId = :userId)
            ORDER BY p.deadlineAt ASC
            """)
    List<ShiftPeriod> findUnsubmitted(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 期限を過ぎた受付中の期間（BT-02）。 */
    List<ShiftPeriod> findByStatusAndDeadlineAtBefore(PeriodStatus status, LocalDateTime now);

    /** 期限の範囲（BT-03）。 */
    List<ShiftPeriod> findByStatusAndDeadlineAtBetween(PeriodStatus status, LocalDateTime from,
            LocalDateTime to);
}

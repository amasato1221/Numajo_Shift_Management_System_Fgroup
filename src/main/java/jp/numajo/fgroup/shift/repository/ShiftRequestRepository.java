package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.ShiftRequest;

/**
 * シフト希望（shift_requests）のリポジトリ。
 */
public interface ShiftRequestRepository extends JpaRepository<ShiftRequest, Long> {

    /** その期間・その人の希望（日付・枠の順）。 */
    @Query("""
            SELECT r FROM ShiftRequest r LEFT JOIN FETCH r.slot
            WHERE r.period.id = :periodId AND r.user.id = :userId
            ORDER BY r.workDate ASC, r.slot.id ASC NULLS LAST
            """)
    List<ShiftRequest> findByPeriodIdAndUserId(@Param("periodId") Long periodId, @Param("userId") Long userId);

    /** その期間・その人の希望を全部消す（提出・取り消し・代理修正）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ShiftRequest r WHERE r.period.id = :periodId AND r.user.id = :userId")
    int deleteByPeriodIdAndUserId(@Param("periodId") Long periodId, @Param("userId") Long userId);

    /** その期間の全員の希望（SM-06）。 */
    @Query("""
            SELECT r FROM ShiftRequest r JOIN FETCH r.user LEFT JOIN FETCH r.slot
            WHERE r.period.id = :periodId
            """)
    List<ShiftRequest> findByPeriodId(@Param("periodId") Long periodId);

    /** その人の日付範囲の希望（SB-01・SB-02 の「未確定」）。期間も一緒に取る。 */
    @Query("""
            SELECT r FROM ShiftRequest r JOIN FETCH r.period LEFT JOIN FETCH r.slot
            WHERE r.user.id = :userId AND r.workDate BETWEEN :from AND :to
            """)
    List<ShiftRequest> findByUserIdAndWorkDateBetween(@Param("userId") Long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}

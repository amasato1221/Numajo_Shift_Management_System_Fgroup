package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;

/**
 * 割当（shift_assignments）のリポジトリ。
 */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {

    /** 次の出勤（SB-01）。PageRequest.of(0, 1) で1件だけ取る。 */
    @Query("""
            SELECT a FROM ShiftAssignment a LEFT JOIN FETCH a.slot
            WHERE a.user.id = :userId
              AND a.status = jp.numajo.fgroup.shift.constant.AssignmentStatus.ASSIGNED
              AND a.period.status = jp.numajo.fgroup.shift.constant.PeriodStatus.PUBLISHED
              AND (a.workDate > :today OR (a.workDate = :today AND a.endTime > :nowTime))
            ORDER BY a.workDate ASC, a.startTime ASC
            """)
    List<ShiftAssignment> findNextShifts(@Param("userId") Long userId, @Param("today") LocalDate today,
            @Param("nowTime") LocalTime nowTime, Pageable pageable);

    /** その人の日付範囲の割当（SB-01・SB-02）。期間も一緒に取る。 */
    @Query("""
            SELECT a FROM ShiftAssignment a JOIN FETCH a.period LEFT JOIN FETCH a.slot
            WHERE a.user.id = :userId AND a.workDate BETWEEN :from AND :to
            ORDER BY a.workDate ASC, a.startTime ASC
            """)
    List<ShiftAssignment> findByUserIdAndWorkDateBetween(@Param("userId") Long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** その期間の全割当（ASSIGNED・ABSENT の両方。人・枠も一緒に取る）。 */
    @Query("""
            SELECT a FROM ShiftAssignment a JOIN FETCH a.user u LEFT JOIN FETCH u.skills LEFT JOIN FETCH a.slot
            WHERE a.period.id = :periodId
            ORDER BY a.workDate ASC, a.startTime ASC
            """)
    List<ShiftAssignment> findByPeriodIdWithUser(@Param("periodId") Long periodId);

    /** セルの割当（SM-06 の切替）。 */
    @Query("""
            SELECT a FROM ShiftAssignment a
            WHERE a.period.id = :periodId AND a.user.id = :userId AND a.workDate = :workDate
              AND a.slot.id = :slotId
            """)
    Optional<ShiftAssignment> findCell(@Param("periodId") Long periodId, @Param("userId") Long userId,
            @Param("workDate") LocalDate workDate, @Param("slotId") Byte slotId);

    /** その人のその日の割当（重なりのチェック）。 */
    List<ShiftAssignment> findByUserIdAndWorkDateAndStatus(Long userId, LocalDate workDate,
            AssignmentStatus status);

    /** その日の割当（SM-07 の欠員通知の相手）。 */
    List<ShiftAssignment> findByWorkDateAndStatus(LocalDate workDate, AssignmentStatus status);

    /** 公開済みの期間の、その日の割当（SM-07）。 */
    @Query("""
            SELECT a FROM ShiftAssignment a JOIN FETCH a.user u LEFT JOIN FETCH a.slot
            WHERE a.workDate = :date
              AND a.period.status = jp.numajo.fgroup.shift.constant.PeriodStatus.PUBLISHED
            ORDER BY a.startTime ASC, u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<ShiftAssignment> findPublishedByWorkDate(@Param("date") LocalDate date);

    /** カレンダー購読（IC-01）。 */
    @Query("""
            SELECT a FROM ShiftAssignment a LEFT JOIN FETCH a.slot
            WHERE a.user.id = :userId AND a.workDate >= :fromDate
              AND a.status = jp.numajo.fgroup.shift.constant.AssignmentStatus.ASSIGNED
              AND a.period.status = jp.numajo.fgroup.shift.constant.PeriodStatus.PUBLISHED
            ORDER BY a.workDate ASC, a.startTime ASC
            """)
    List<ShiftAssignment> findPublishedForFeed(@Param("userId") Long userId, @Param("fromDate") LocalDate fromDate);

    /** 期間と割当IDで取得する（SM-06 の削除）。 */
    @Query("SELECT a FROM ShiftAssignment a JOIN FETCH a.user LEFT JOIN FETCH a.slot WHERE a.id = :id AND a.period.id = :periodId")
    Optional<ShiftAssignment> findByIdAndPeriodId(@Param("id") Long id, @Param("periodId") Long periodId);

    /** 割当IDで、期間・人・枠も一緒に取得する（SM-07）。 */
    @Query("SELECT a FROM ShiftAssignment a JOIN FETCH a.period JOIN FETCH a.user LEFT JOIN FETCH a.slot WHERE a.id = :id")
    Optional<ShiftAssignment> findDetailById(@Param("id") Long id);

    /** その日の ASSIGNED の割当（人数を数える。SM-01）。 */
    @Query("""
            SELECT a FROM ShiftAssignment a
            WHERE a.workDate = :date AND a.status = jp.numajo.fgroup.shift.constant.AssignmentStatus.ASSIGNED
            """)
    List<ShiftAssignment> findAssignedByWorkDate(@Param("date") LocalDate date);

    /** 期間の状態ごとの件数（公開時の操作ログ）。 */
    long countByPeriodIdAndStatus(Long periodId, AssignmentStatus status);

    /** その人の、今日以降の ASSIGNED の割当の件数（SM-03 で無効にしたときの警告）。 */
    @Query("""
            SELECT COUNT(a) FROM ShiftAssignment a
            WHERE a.user.id = :userId AND a.workDate >= :today
              AND a.status = jp.numajo.fgroup.shift.constant.AssignmentStatus.ASSIGNED
            """)
    long countAssignedFrom(@Param("userId") Long userId, @Param("today") LocalDate today);
}

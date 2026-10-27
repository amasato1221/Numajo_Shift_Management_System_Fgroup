package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.Absence;

/**
 * 欠勤（absences）のリポジトリ。
 */
public interface AbsenceRepository extends JpaRepository<Absence, Long> {

    /** 今日以降の欠勤（SM-01）。 */
    @Query("""
            SELECT b FROM Absence b JOIN FETCH b.assignment a JOIN FETCH a.user LEFT JOIN FETCH a.slot
            WHERE a.workDate >= :today
            ORDER BY a.workDate ASC, a.startTime ASC, b.id ASC
            """)
    List<Absence> findUpcoming(@Param("today") LocalDate today);

    /** 割当IDのまとまりで取得する（SM-07）。 */
    List<Absence> findByAssignmentIdIn(Collection<Long> assignmentIds);
}

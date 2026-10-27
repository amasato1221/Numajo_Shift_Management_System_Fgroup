package jp.numajo.fgroup.shift.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.RequestSubmission;
import jp.numajo.fgroup.shift.entity.id.RequestSubmissionId;

/**
 * 希望の提出状況（request_submissions）のリポジトリ。
 */
public interface RequestSubmissionRepository extends JpaRepository<RequestSubmission, RequestSubmissionId> {

    /** その期間に提出したユーザーのID。 */
    @Query("SELECT s.id.userId FROM RequestSubmission s WHERE s.id.periodId = :periodId")
    List<Long> findUserIdsByPeriodId(@Param("periodId") Long periodId);

    /** 提出を取り消す。 */
    @Modifying
    @Query("DELETE FROM RequestSubmission s WHERE s.id.periodId = :periodId AND s.id.userId = :userId")
    int deleteByPeriodIdAndUserId(@Param("periodId") Long periodId, @Param("userId") Long userId);
}

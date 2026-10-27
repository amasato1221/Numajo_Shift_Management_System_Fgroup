package jp.numajo.fgroup.shift.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.OperationLog;

/**
 * 操作ログ（operation_logs）のリポジトリ。
 */
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    /** 新しい5件（SA-01）。 */
    @Query("SELECT l FROM OperationLog l LEFT JOIN FETCH l.user ORDER BY l.createdAt DESC, l.id DESC LIMIT 5")
    List<OperationLog> findLatest5();

    /** 検索（SA-03）。並びは Pageable の Sort で指定する。 */
    @Query(value = """
            SELECT l FROM OperationLog l LEFT JOIN FETCH l.user
            WHERE l.createdAt >= :fromAt AND l.createdAt < :toAt
              AND (:userId IS NULL OR l.user.id = :userId)
              AND (:action IS NULL OR l.action = :action)
            """,
            countQuery = """
            SELECT COUNT(l) FROM OperationLog l
            WHERE l.createdAt >= :fromAt AND l.createdAt < :toAt
              AND (:userId IS NULL OR l.user.id = :userId)
              AND (:action IS NULL OR l.action = :action)
            """)
    Page<OperationLog> search(@Param("fromAt") LocalDateTime fromAt, @Param("toAt") LocalDateTime toAt,
            @Param("userId") Long userId, @Param("action") String action, Pageable pageable);
}

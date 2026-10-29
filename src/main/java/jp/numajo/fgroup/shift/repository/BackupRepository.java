package jp.numajo.fgroup.shift.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jp.numajo.fgroup.shift.entity.Backup;

/**
 * バックアップ履歴（backups）のリポジトリ。
 */
public interface BackupRepository extends JpaRepository<Backup, Long> {

    /** 新しい50件（SA-04）。 */
    @Query("SELECT b FROM Backup b LEFT JOIN FETCH b.createdBy ORDER BY b.createdAt DESC, b.id DESC LIMIT 50")
    List<Backup> findLatest50();

    /** 一番新しい1件（SA-01）。 */
    Optional<Backup> findFirstByOrderByCreatedAtDescIdDesc();
}

package jp.numajo.fgroup.shift.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jp.numajo.fgroup.shift.entity.Slot;

/**
 * シフト枠（slots）のリポジトリ。
 */
public interface SlotRepository extends JpaRepository<Slot, Byte> {

    /** 枠を必要職能付きで id 順に取得する。 */
    @Query("SELECT DISTINCT s FROM Slot s LEFT JOIN FETCH s.requiredSkills ORDER BY s.id ASC")
    List<Slot> findAllWithRequiredSkills();
}

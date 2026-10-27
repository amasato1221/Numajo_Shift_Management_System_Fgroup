package jp.numajo.fgroup.shift.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jp.numajo.fgroup.shift.entity.Skill;

/**
 * 職能（skills）のリポジトリ。
 */
public interface SkillRepository extends JpaRepository<Skill, Long> {

    /** 名前順の全件。 */
    List<Skill> findAllByOrderByNameAscIdAsc();

    /** 同じ名前があるか（追加）。 */
    boolean existsByName(String name);

    /** 自分以外に同じ名前があるか（変更）。 */
    boolean existsByNameAndIdNot(String name, Long id);

    /** 職能ごとの、持っている有効なバイトの人数。[0]=skillId(Long), [1]=人数(Long) */
    @Query("""
            SELECT s.id, COUNT(u) FROM User u JOIN u.skills s
            WHERE u.role = jp.numajo.fgroup.shift.constant.Role.STAFF AND u.active = true
            GROUP BY s.id
            """)
    List<Object[]> countActiveStaffPerSkill();
}

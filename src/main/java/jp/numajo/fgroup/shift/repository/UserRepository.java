package jp.numajo.fgroup.shift.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;

/**
 * ユーザー（users）のリポジトリ。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** ログインIDで1件取得する。 */
    Optional<User> findByLoginId(String loginId);

    /** 有効フラグだけを取得する（UserActiveCheckFilter が毎リクエスト呼ぶ）。 */
    @Query("SELECT u.active FROM User u WHERE u.id = :id")
    Optional<Boolean> findActiveById(@Param("id") Long id);

    /** ログインIDが使われているか。 */
    boolean existsByLoginId(String loginId);

    /** 役割を指定して1件取得する（SM-03・SA-02 の対象チェック）。 */
    Optional<User> findByIdAndRole(Long id, Role role);

    /** スタッフの検索（SM-02）。pattern は LikeUtil でエスケープ済みの「%…%」。 */
    @Query("""
            SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.skills
            WHERE u.role = jp.numajo.fgroup.shift.constant.Role.STAFF
              AND (:active IS NULL OR u.active = :active)
              AND (:pattern IS NULL
                   OR u.name LIKE :pattern ESCAPE '!'
                   OR u.nameKana LIKE :pattern ESCAPE '!'
                   OR u.loginId LIKE :pattern ESCAPE '!')
            ORDER BY u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> searchStaff(@Param("pattern") String pattern, @Param("active") Boolean active);

    /** 有効なバイト（ふりがな順）。 */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = jp.numajo.fgroup.shift.constant.Role.STAFF AND u.active = true
            ORDER BY u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> findActiveStaff();

    /** 有効なバイトを職能付きで取得する（SM-07 の欠員通知の相手）。 */
    @Query("""
            SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.skills
            WHERE u.role = jp.numajo.fgroup.shift.constant.Role.STAFF AND u.active = true
            ORDER BY u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> findActiveStaffWithSkills();

    /** SM-06 の行に出すスタッフ：有効なバイト＋その期間に希望か割当がある人（職能付き）。 */
    @Query("""
            SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.skills
            WHERE u.role = jp.numajo.fgroup.shift.constant.Role.STAFF
              AND (u.active = true
                   OR EXISTS (SELECT 1 FROM ShiftRequest r WHERE r.user = u AND r.period.id = :periodId)
                   OR EXISTS (SELECT 1 FROM ShiftAssignment a WHERE a.user = u AND a.period.id = :periodId))
            ORDER BY u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> findStaffForPeriod(@Param("periodId") Long periodId);

    /** 役割と有効フラグで取得する（SM-10、通知の宛先）。 */
    @Query("""
            SELECT u FROM User u WHERE u.role = :role AND u.active = :active
            ORDER BY u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> findByRoleAndActive(@Param("role") Role role, @Param("active") boolean active);

    /** 役割で取得する（SA-02：有効→停止、ふりがな順）。 */
    @Query("""
            SELECT u FROM User u WHERE u.role = :role
            ORDER BY u.active DESC, u.nameKana ASC NULLS LAST, u.id ASC
            """)
    List<User> findByRoleForAdmin(@Param("role") Role role);

    /** 役割・有効フラグごとの人数（SA-01）。 */
    long countByRoleAndActive(Role role, boolean active);

    /** 全ユーザー（SA-03 の利用者の選択肢）。 */
    @Query("SELECT u FROM User u ORDER BY u.nameKana ASC NULLS LAST, u.id ASC")
    List<User> findAllForOptions();
}

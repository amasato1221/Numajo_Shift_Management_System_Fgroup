# 30-03 repository

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-03 |
| 版 | 第1.1版（令和8年10月22日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |
| 1.1 | 令和8年10月22日 | 設計書評価の指摘を反映：ShiftAssignmentRepository#countAssignedFrom を追加（M7）。条件4つのメソッド名クエリを例外として明記（L2） | 新井 |

このページのコードは**全文**です。そのまま写してください（30-00 1章）。

## 0. repository の決まり

| 決まり | 内容 |
| --- | --- |
| 形 | `JpaRepository<エンティティ, 主キーの型>` を継承したインターフェース |
| メソッド名から作るクエリ | 条件が2つまでで、`JOIN FETCH` がいらないものだけ（例：`existsByLoginId`）。**例外：** `NotificationRepository#existsByUserIdAndTypeAndLinkUrlAndCreatedAtGreaterThanEqual`（条件4つ）。すべて「＝」か「以上」だけで読み違えがなく、検証用プロジェクトで動作を確かめたため、このまま使う。これ以外に条件3つ以上のものを足すときは `@Query` にする |
| `@Query` | それ以外はすべて JPQL で書く。ネイティブSQL（`nativeQuery = true`）は使わない |
| 列挙型の定数 | JPQL の中では `jp.numajo.fgroup.shift.constant.PeriodStatus.PUBLISHED` のように完全な名前で書く |
| LIKE | エスケープ文字は `!`（`ESCAPE '!'`）。値は `LikeUtil.contains(keyword)` で作る（MySQL では `\` が特別な意味を持つため使わない） |
| null の並び | ふりがなが null の人は最後（`NULLS LAST`） |
| 件数の制限 | `LIMIT` を JPQL に直接書く（Hibernate 7 で使える）。ページ送りがある画面だけ `Pageable` を使う |
| 更新・削除の `@Query` | `@Modifying` を付ける。同じトランザクションでこのあと同じ行を読む・入れるものは `flushAutomatically = true, clearAutomatically = true` も付ける |

## 0.1 動作の確認

検証用のプロジェクトで次を確かめました（`RepositoryQueryTests`、8件すべて成功）。97_テスト計画書の結合テストにも同じ観点を入れています。

| 確認したこと | 結果 |
| --- | --- |
| 17個すべての `@Query` が起動時の構文チェックを通る | 通る |
| `searchStaff` で「%」を検索 | 「%」を含む人だけ（エスケープが効く） |
| `searchStaff` の並び | ふりがな順、null は最後、店長は出ない |
| `findStaffForPeriod` | 無効なバイトでも、その期間に割当があれば出る |
| `findNextShifts` | 今日の終了時刻ちょうどになったら次の日へ |
| `findUnsubmitted` | 提出済み・期限切れは出ない |
| `ChatMessageRepository` の4つ | 最後のメッセージ、バイトが送った未読の数、見る人ごとの既読 |
| `OperationLogRepository#search` | 期間・利用者・操作の絞り込み、51件で2ページ目に1件 |

## 1. リポジトリ

### `UserRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/UserRepository.java`

```java
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
```

### `SkillRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/SkillRepository.java`

```java
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
```

### `SlotRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/SlotRepository.java`

```java
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
```

### `StoreSettingRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/StoreSettingRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.StoreSetting;

/**
 * 店舗設定（store_settings）のリポジトリ。行は id=1 の1行だけ。
 */
public interface StoreSettingRepository extends JpaRepository<StoreSetting, Byte> {
}
```

### `ShiftPeriodRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/ShiftPeriodRepository.java`

```java
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
```

### `RequiredStaffDefaultRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/RequiredStaffDefaultRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.RequiredStaffDefault;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffDefaultId;

/**
 * 必要人数（曜日の既定値）のリポジトリ。findAll() で14行を取る。
 */
public interface RequiredStaffDefaultRepository extends JpaRepository<RequiredStaffDefault, RequiredStaffDefaultId> {
}
```

### `RequiredStaffOverrideRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/RequiredStaffOverrideRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.RequiredStaffOverride;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffOverrideId;

/**
 * 必要人数（日付ごとの上書き）のリポジトリ。
 */
public interface RequiredStaffOverrideRepository
        extends JpaRepository<RequiredStaffOverride, RequiredStaffOverrideId> {

    /** 日付の範囲で取得する。 */
    List<RequiredStaffOverride> findByIdWorkDateBetween(LocalDate from, LocalDate to);
}
```

### `RequestSubmissionRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/RequestSubmissionRepository.java`

```java
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
```

### `ShiftRequestRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/ShiftRequestRepository.java`

```java
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
```

### `ShiftAssignmentRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/ShiftAssignmentRepository.java`

```java
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
```

### `AbsenceRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/AbsenceRepository.java`

```java
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
```

### `NotificationRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/NotificationRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.entity.Notification;

/**
 * お知らせ（notifications）のリポジトリ。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 新しい100件（SC-02）。 */
    List<Notification> findTop100ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** 未読の新しい3件（SB-01）。 */
    List<Notification> findTop3ByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId);

    /** 自分のお知らせを1件取得する。 */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** 未読の数（ヘッダー）。 */
    long countByUserIdAndReadAtIsNull(Long userId);

    /** すべて既読にする。 */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 同じお知らせをすでに送ったか（BT-03 の二重送信防止）。 */
    boolean existsByUserIdAndTypeAndLinkUrlAndCreatedAtGreaterThanEqual(Long userId, NotificationType type,
            String linkUrl, LocalDateTime from);
}
```

### `NotificationSettingRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/NotificationSettingRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.NotificationSetting;
import jp.numajo.fgroup.shift.entity.id.NotificationSettingId;

/**
 * 締切通知の設定（notification_settings）のリポジトリ。
 */
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, NotificationSettingId> {

    /** その人の設定（何日前の昇順）。 */
    List<NotificationSetting> findByIdUserIdOrderByIdDaysBeforeAsc(Long userId);

    /** その人の設定を全部消す。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM NotificationSetting s WHERE s.id.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 指定した「何日前」を選んでいるユーザーのID（BT-03）。 */
    @Query("SELECT s.id.userId FROM NotificationSetting s WHERE s.id.daysBefore = :daysBefore")
    List<Long> findUserIdsByDaysBefore(@Param("daysBefore") Byte daysBefore);
}
```

### `CalendarFeedRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/CalendarFeedRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.CalendarFeed;

/**
 * カレンダー購読（calendar_feeds）のリポジトリ。主キーはユーザーID。
 */
public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, Long> {

    /** トークンで取得する（IC-01）。 */
    Optional<CalendarFeed> findByToken(String token);

    /** トークンが使われているか。 */
    boolean existsByToken(String token);
}
```

### `ChatMessageRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/ChatMessageRepository.java`

```java
package jp.numajo.fgroup.shift.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.ChatMessage;

/**
 * チャット（chat_messages）のリポジトリ。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 会話の新しい200件（送信者も一緒に取る）。 */
    @Query("""
            SELECT m FROM ChatMessage m JOIN FETCH m.senderUser
            WHERE m.staffUser.id = :staffUserId
            ORDER BY m.createdAt DESC, m.id DESC
            LIMIT 200
            """)
    List<ChatMessage> findLatest200(@Param("staffUserId") Long staffUserId);

    /** 相手のメッセージを既読にする。byStaff：見ている人がバイトなら true。 */
    @Modifying
    @Query("""
            UPDATE ChatMessage m SET m.readAt = :now
            WHERE m.staffUser.id = :staffId AND m.readAt IS NULL
              AND ((:byStaff = true AND m.senderUser.id <> :staffId)
                   OR (:byStaff = false AND m.senderUser.id = :staffId))
            """)
    int markRead(@Param("staffId") Long staffId, @Param("byStaff") boolean byStaff, @Param("now") LocalDateTime now);

    /** 会話ごとの最後のメッセージ（SM-10）。 */
    @Query("""
            SELECT m FROM ChatMessage m JOIN FETCH m.staffUser
            WHERE m.id IN (SELECT MAX(m2.id) FROM ChatMessage m2 GROUP BY m2.staffUser.id)
            """)
    List<ChatMessage> findLatestPerStaff();

    /** 会話ごとの、バイトが送った未読の数（SM-10）。[0]=staffUserId(Long), [1]=件数(Long) */
    @Query("""
            SELECT m.staffUser.id, COUNT(m) FROM ChatMessage m
            WHERE m.readAt IS NULL AND m.senderUser.id = m.staffUser.id
            GROUP BY m.staffUser.id
            """)
    List<Object[]> countUnreadPerStaff();
}
```

### `OperationLogRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/OperationLogRepository.java`

```java
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
```

### `BackupRepository`

ファイル：`src/main/java/jp/numajo/fgroup/shift/repository/BackupRepository.java`

```java
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
```


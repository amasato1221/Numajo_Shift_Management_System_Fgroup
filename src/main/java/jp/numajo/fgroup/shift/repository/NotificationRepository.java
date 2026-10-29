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

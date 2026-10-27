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

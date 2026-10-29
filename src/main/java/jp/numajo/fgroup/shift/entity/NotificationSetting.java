package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.NotificationSettingId;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 締切通知の設定（notification_settings）のエンティティ。
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSetting {

    /** 複合主キー（ユーザー・何日前） */
    @EmbeddedId
    private NotificationSettingId id;
}

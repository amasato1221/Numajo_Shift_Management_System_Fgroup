package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 締切通知設定の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class NotificationSettingId implements Serializable {

    /** ユーザーID */
    @Column(name = "user_id")
    private Long userId;

    /** 何日前（0・1・3・7） */
    @Column(name = "days_before")
    private Byte daysBefore;
}

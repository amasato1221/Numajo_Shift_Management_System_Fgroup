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
 * 必要人数（曜日）の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequiredStaffDefaultId implements Serializable {

    /** 曜日（0=日〜6=土） */
    @Column(name = "day_of_week")
    private Byte dayOfWeek;

    /** 枠ID */
    @Column(name = "slot_id")
    private Byte slotId;
}

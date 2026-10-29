package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（日別）の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequiredStaffOverrideId implements Serializable {

    /** 日付 */
    @Column(name = "work_date")
    private LocalDate workDate;

    /** 枠ID */
    @Column(name = "slot_id")
    private Byte slotId;
}

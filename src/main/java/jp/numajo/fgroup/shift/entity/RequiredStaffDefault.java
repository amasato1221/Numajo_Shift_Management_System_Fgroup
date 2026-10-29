package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.RequiredStaffDefaultId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（曜日の既定値）（required_staff_defaults）のエンティティ。
 */
@Entity
@Table(name = "required_staff_defaults")
@Getter
@Setter
@NoArgsConstructor
public class RequiredStaffDefault {

    /** 複合主キー（曜日・枠） */
    @EmbeddedId
    private RequiredStaffDefaultId id;

    /** 必要人数 */
    @Column(name = "required_count")
    private int requiredCount;
}

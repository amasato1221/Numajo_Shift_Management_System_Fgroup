package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.RequiredStaffOverrideId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（日付ごとの上書き）（required_staff_overrides）のエンティティ。
 */
@Entity
@Table(name = "required_staff_overrides")
@Getter
@Setter
@NoArgsConstructor
public class RequiredStaffOverride {

    /** 複合主キー（日付・枠） */
    @EmbeddedId
    private RequiredStaffOverrideId id;

    /** 必要人数 */
    @Column(name = "required_count")
    private int requiredCount;
}

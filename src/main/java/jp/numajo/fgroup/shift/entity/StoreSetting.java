package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 店舗設定（store_settings）のエンティティ。
 */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
@NoArgsConstructor
public class StoreSetting {

    /** 主キー（常に1） */
    @Id
    @Column(name = "id")
    private Byte id;

    /** 店舗名 */
    @Column(name = "store_name")
    private String storeName;

    /** ほかの人のシフトを表示するか */
    @Column(name = "show_others_shifts")
    private boolean showOthersShifts;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 職能（skills）のエンティティ。
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
@NoArgsConstructor
public class Skill {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 職能名 */
    @Column(name = "name")
    private String name;

    /** 説明 */
    @Column(name = "description")
    private String description;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

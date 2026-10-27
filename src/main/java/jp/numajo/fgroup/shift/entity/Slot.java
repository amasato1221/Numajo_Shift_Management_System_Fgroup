package jp.numajo.fgroup.shift.entity;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * シフト枠（slots）のエンティティ。
 */
@Entity
@Table(name = "slots")
@Getter
@Setter
@NoArgsConstructor
public class Slot {

    /** 主キー（1=日勤、2=夕勤） */
    @Id
    @Column(name = "id")
    private Byte id;

    /** コード（DAY／EVENING） */
    @Column(name = "code")
    private String code;

    /** 名称 */
    @Column(name = "name")
    private String name;

    /** 開始時刻 */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻 */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** この枠に必要な職能 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "slot_required_skills",
            joinColumns = @JoinColumn(name = "slot_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> requiredSkills = new HashSet<>();
}

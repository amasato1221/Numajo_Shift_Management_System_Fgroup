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
 * 提出状況の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequestSubmissionId implements Serializable {

    /** 募集期間ID */
    @Column(name = "period_id")
    private Long periodId;

    /** ユーザーID */
    @Column(name = "user_id")
    private Long userId;
}

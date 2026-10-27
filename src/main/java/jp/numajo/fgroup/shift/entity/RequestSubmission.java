package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.entity.id.RequestSubmissionId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 希望の提出状況（request_submissions）のエンティティ。
 */
@Entity
@Table(name = "request_submissions")
@Getter
@Setter
@NoArgsConstructor
public class RequestSubmission {

    /** 複合主キー（期間・ユーザー） */
    @EmbeddedId
    private RequestSubmissionId id;

    /** 提出日時 */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
}

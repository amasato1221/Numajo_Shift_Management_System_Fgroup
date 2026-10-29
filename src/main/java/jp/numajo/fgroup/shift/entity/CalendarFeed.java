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
 * カレンダー購読（calendar_feeds）のエンティティ。
 */
@Entity
@Table(name = "calendar_feeds")
@Getter
@Setter
@NoArgsConstructor
public class CalendarFeed {

    /** 主キー（ユーザーID） */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /** 購読URLのトークン（43文字） */
    @Column(name = "token")
    private String token;

    /** 発行日時 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

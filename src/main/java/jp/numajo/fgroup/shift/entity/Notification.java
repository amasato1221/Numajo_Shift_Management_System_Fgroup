package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * お知らせ（notifications）のエンティティ。
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 宛先 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 種類 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private NotificationType type;

    /** 件名 */
    @Column(name = "title")
    private String title;

    /** 本文 */
    @Column(name = "body")
    private String body;

    /** 移動先URL */
    @Column(name = "link_url")
    private String linkUrl;

    /** 既読日時 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 作成日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

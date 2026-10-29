package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * チャット（chat_messages）のエンティティ。
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 会話の相手のバイト */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_user_id")
    private User staffUser;

    /** 送信者 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id")
    private User senderUser;

    /** 本文 */
    @Column(name = "body")
    private String body;

    /** 既読日時 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 送信日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

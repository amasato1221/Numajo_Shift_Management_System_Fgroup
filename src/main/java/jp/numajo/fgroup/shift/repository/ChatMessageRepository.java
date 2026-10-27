package jp.numajo.fgroup.shift.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.numajo.fgroup.shift.entity.ChatMessage;

/**
 * チャット（chat_messages）のリポジトリ。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 会話の新しい200件（送信者も一緒に取る）。 */
    @Query("""
            SELECT m FROM ChatMessage m JOIN FETCH m.senderUser
            WHERE m.staffUser.id = :staffUserId
            ORDER BY m.createdAt DESC, m.id DESC
            LIMIT 200
            """)
    List<ChatMessage> findLatest200(@Param("staffUserId") Long staffUserId);

    /** 相手のメッセージを既読にする。byStaff：見ている人がバイトなら true。 */
    @Modifying
    @Query("""
            UPDATE ChatMessage m SET m.readAt = :now
            WHERE m.staffUser.id = :staffId AND m.readAt IS NULL
              AND ((:byStaff = true AND m.senderUser.id <> :staffId)
                   OR (:byStaff = false AND m.senderUser.id = :staffId))
            """)
    int markRead(@Param("staffId") Long staffId, @Param("byStaff") boolean byStaff, @Param("now") LocalDateTime now);

    /** 会話ごとの最後のメッセージ（SM-10）。 */
    @Query("""
            SELECT m FROM ChatMessage m JOIN FETCH m.staffUser
            WHERE m.id IN (SELECT MAX(m2.id) FROM ChatMessage m2 GROUP BY m2.staffUser.id)
            """)
    List<ChatMessage> findLatestPerStaff();

    /** 会話ごとの、バイトが送った未読の数（SM-10）。[0]=staffUserId(Long), [1]=件数(Long) */
    @Query("""
            SELECT m.staffUser.id, COUNT(m) FROM ChatMessage m
            WHERE m.readAt IS NULL AND m.senderUser.id = m.staffUser.id
            GROUP BY m.staffUser.id
            """)
    List<Object[]> countUnreadPerStaff();
}

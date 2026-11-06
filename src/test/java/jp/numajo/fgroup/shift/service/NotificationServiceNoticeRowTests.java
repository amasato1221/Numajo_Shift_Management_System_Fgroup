package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.Notification;
import jp.numajo.fgroup.shift.repository.NotificationRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * NotificationService の findUnreadTop3・toRow の単体テスト（20-SB-01 2.1-3、20-SC-02 2.1-2）。
 *
 * <pre>
 * 判定条件網羅：
 * - findUnreadTop3：取れた件数（0件／2件。2件は並びを変えずに行にする）
 * - toRow：未読か（read_at が null → unread 真／日時あり → 偽）
 *
 * 組み合わせ表：toRow の本文を切るか
 * 判定：A body != null &amp;&amp; B 長さが100文字を超える
 * No. | A | B | 結果
 *  1  | 偽| － | null のまま（短絡評価で B は評価されない）
 *  2  | 真| 偽 | そのまま（ちょうど100文字）
 *  3  | 真| 真 | 先頭100文字＋「…」（101文字）
 * </pre>
 */
class NotificationServiceNoticeRowTests {

    private static final Long USER_ID = 10L;

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository,
            mock(UserRepository.class), mock(MessageSource.class));

    private static Notification notification(long id, NotificationType type, String body, LocalDateTime readAt) {
        Notification n = new Notification();
        n.setId(id);
        n.setType(type);
        n.setTitle("件名" + id);
        n.setBody(body);
        n.setReadAt(readAt);
        n.setCreatedAt(LocalDateTime.of(2026, 10, 16, 9, (int) id));
        return n;
    }

    // ---------------------------------------------------------------- findUnreadTop3

    @Test
    void 未読がない_空のリスト() {
        when(notificationRepository.findTop3ByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(List.of());

        assertThat(service.findUnreadTop3(USER_ID)).isEmpty();
    }

    @Test
    void 未読が2件_同じ順で行にし種類の表示名を入れる() {
        when(notificationRepository.findTop3ByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(List.of(notification(2L, NotificationType.PUBLISHED, "本文2", null),
                        notification(1L, NotificationType.CHAT, "本文1", null)));

        List<NoticeRow> actual = service.findUnreadTop3(USER_ID);

        assertThat(actual).extracting(NoticeRow::id).containsExactly(2L, 1L);
        assertThat(actual).extracting(NoticeRow::typeLabel)
                .containsExactly(NotificationType.PUBLISHED.getLabel(), NotificationType.CHAT.getLabel());
        NoticeRow first = actual.get(0);
        assertThat(first.type()).isEqualTo(NotificationType.PUBLISHED);
        assertThat(first.title()).isEqualTo("件名2");
        assertThat(first.body()).isEqualTo("本文2");
        assertThat(first.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 2));
        assertThat(first.unread()).isTrue();
    }

    // ---------------------------------------------------------------- toRow

    @Test
    void toRow_既読_unreadは偽() {
        NoticeRow actual = NotificationService.toRow(
                notification(1L, NotificationType.DEADLINE, "本文", LocalDateTime.of(2026, 10, 17, 8, 0)));

        assertThat(actual.unread()).isFalse();
    }

    @Test
    void toRow_No1_本文がnull_nullのまま() {
        assertThat(NotificationService.toRow(notification(1L, NotificationType.CHAT, null, null)).body()).isNull();
    }

    @Test
    void toRow_No2_本文がちょうど100文字_そのまま() {
        String body = "あ".repeat(100);

        assertThat(NotificationService.toRow(notification(1L, NotificationType.CHAT, body, null)).body())
                .isEqualTo(body);
    }

    @Test
    void toRow_No3_本文が101文字_100文字で切って三点リーダーを付ける() {
        String body = "あ".repeat(100) + "い";

        assertThat(NotificationService.toRow(notification(1L, NotificationType.CHAT, body, null)).body())
                .isEqualTo("あ".repeat(100) + "…");
    }
}

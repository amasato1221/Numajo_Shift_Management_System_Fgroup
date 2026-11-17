package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.Notification;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.NotificationRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * NotificationService の一覧・既読まわり（findUnreadTop3・toRow・findLatest・open・readAll・safeLink）の単体テスト
 * （20-SB-01 2.1-3、20-SC-02 2章、94 3.4）。DB を使う確認は NoticeFlowTests。
 *
 * <pre>
 * 判定条件網羅：
 * - findUnreadTop3：取れた件数（0件／2件。2件は並びを変えずに行にする）
 * - findLatest：limit が 100 か（100 → 一覧／99・101 → IllegalArgumentException）、取れた件数（0件／2件）
 * - open：自分のお知らせがあるか（ない → ResourceNotFoundException）、read_at が null か（null → 今の日時／あり → 変えない）
 * - toRow：未読か（read_at が null → unread 真／日時あり → 偽）
 * - toRow：作った日時があるか（created_at あり → createdAtLabel に日時／null → null）
 *
 * 組み合わせ表：toRow の本文を切るか
 * 判定：A body != null &amp;&amp; B 長さが100文字を超える
 * No. | A | B | 結果
 *  1  | 偽| － | null のまま（短絡評価で B は評価されない）
 *  2  | 真| 偽 | そのまま（ちょうど100文字）
 *  3  | 真| 真 | 先頭100文字＋「…」（101文字）
 *
 * 組み合わせ表：safeLink（移動先を /notices にするか。20-SC-02 2.2-1-3）
 * 判定：A linkUrl == null || B / で始まらない || C // で始まる
 * No. | A | B | C | 結果
 *  1  | 真| － | － | /notices（短絡評価で B・C は評価されない）
 *  2  | 偽| 真| － | /notices（https://evil.example、空文字、staff/home）
 *  3  | 偽| 偽| 真 | /notices（//evil.example）
 *  4  | 偽| 偽| 偽 | そのまま（/staff/home、/staff/shifts?periodId=7、/manager/chats/3）
 * （OR の式なので、どれか1つが真なら残りは評価されない。A 偽・B 偽・C 偽 だけがそのまま）
 * </pre>
 */
class NotificationServiceNoticeRowTests {

    private static final Long USER_ID = 10L;

    /** 今の日時（2026/10/16 09:30 日本時間） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 9, 30);
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository,
            mock(UserRepository.class), mock(MessageSource.class),
            Clock.fixed(NOW.atZone(TOKYO).toInstant(), TOKYO));

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
        assertThat(first.createdAtLabel()).isEqualTo("2026/10/16 09:02");
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
    void toRow_作った日時がnull_表示もnull() {
        Notification n = notification(1L, NotificationType.CHAT, "本文", null);
        n.setCreatedAt(null);

        assertThat(NotificationService.toRow(n).createdAtLabel()).isNull();
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

    // ---------------------------------------------------------------- findLatest

    @Test
    void findLatest_0件_空のリスト() {
        when(notificationRepository.findTop100ByUserIdOrderByCreatedAtDescIdDesc(USER_ID)).thenReturn(List.of());

        assertThat(service.findLatest(USER_ID, 100)).isEmpty();
    }

    @Test
    void findLatest_2件_同じ順で行にする_既読と未読が混ざる() {
        when(notificationRepository.findTop100ByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(List.of(notification(2L, NotificationType.VACANCY, "本文2", null),
                        notification(1L, NotificationType.DEADLINE, "本文1", NOW)));

        List<NoticeRow> actual = service.findLatest(USER_ID, 100);

        assertThat(actual).extracting(NoticeRow::id).containsExactly(2L, 1L);
        assertThat(actual).extracting(NoticeRow::unread).containsExactly(true, false);
        assertThat(actual).extracting(NoticeRow::typeLabel)
                .containsExactly(NotificationType.VACANCY.getLabel(), NotificationType.DEADLINE.getLabel());
    }

    @ParameterizedTest
    @ValueSource(ints = { 99, 101, 0 })
    void findLatest_limitが100でない_IllegalArgumentException(int limit) {
        assertThatThrownBy(() -> service.findLatest(USER_ID, limit))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationRepository, never()).findTop100ByUserIdOrderByCreatedAtDescIdDesc(USER_ID);
    }

    // ---------------------------------------------------------------- open

    @Test
    void open_自分のお知らせにない_ResourceNotFoundException() {
        when(notificationRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(USER_ID, 5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void open_未読_今の日時を入れて移動先を返す() {
        Notification n = notification(5L, NotificationType.PUBLISHED, "本文", null);
        n.setLinkUrl("/staff/shifts?periodId=7");
        when(notificationRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(n));

        String actual = service.open(USER_ID, 5L);

        assertThat(actual).isEqualTo("/staff/shifts?periodId=7");
        assertThat(n.getReadAt()).isEqualTo(NOW);
    }

    @Test
    void open_既読_read_atは変えない() {
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 15, 8, 0);
        Notification n = notification(5L, NotificationType.CHAT, "本文", readAt);
        n.setLinkUrl("/staff/chat");
        when(notificationRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(n));

        String actual = service.open(USER_ID, 5L);

        assertThat(actual).isEqualTo("/staff/chat");
        assertThat(n.getReadAt()).isEqualTo(readAt);
    }

    @Test
    void open_移動先が外のサイト_お知らせ一覧を返す() {
        Notification n = notification(5L, NotificationType.CHAT, "本文", null);
        n.setLinkUrl("https://evil.example/");
        when(notificationRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(n));

        assertThat(service.open(USER_ID, 5L)).isEqualTo("/notices");
        assertThat(n.getReadAt()).isEqualTo(NOW);
    }

    // ---------------------------------------------------------------- readAll

    @Test
    void readAll_自分の未読を今の日時で既読にする() {
        service.readAll(USER_ID);

        verify(notificationRepository).markAllRead(USER_ID, NOW);
    }

    // ---------------------------------------------------------------- safeLink

    @ParameterizedTest
    @NullSource
    void safeLink_No1_null_お知らせ一覧(String linkUrl) {
        assertThat(NotificationService.safeLink(linkUrl)).isEqualTo("/notices");
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://evil.example/", "", "staff/home" })
    void safeLink_No2_スラッシュで始まらない_お知らせ一覧(String linkUrl) {
        assertThat(NotificationService.safeLink(linkUrl)).isEqualTo("/notices");
    }

    @Test
    void safeLink_No3_スラッシュ2つで始まる_お知らせ一覧() {
        assertThat(NotificationService.safeLink("//evil.example")).isEqualTo("/notices");
    }

    @ParameterizedTest
    @CsvSource({ "/staff/home", "/staff/shifts?periodId=7", "/manager/chats/3", "/" })
    void safeLink_No4_サイトの中_そのまま(String linkUrl) {
        assertThat(NotificationService.safeLink(linkUrl)).isEqualTo(linkUrl);
    }
}

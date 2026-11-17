package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatListRow;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.ChatMessage;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ChatMessageRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * ChatService の単体テスト（20-SM-10 6章、20-SM-11 6章、20-SB-06 6章）。
 *
 * <p>
 * リポジトリはモック。今の日時は Clock で 2026年11月17日 10:00 に固定する。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * getChatList     未読が1件以上か｜メッセージがあるか｜無効なバイトか（無効でメッセージあり→出す）
 * 並べ替え         日時が同じか（同じ→メッセージIDの大きい順）
 * cutBody         30文字を超えるか（30文字／31文字）
 * lastAtLabel     今日か｜今年か（今日 H:mm／今年 M/d／前の年 yyyy/M/d）
 * getConversation 見る人がバイトか｜送ったのがバイトか（mine は2つが同じとき）
 * send            相手がいるか｜送る人がいるか｜相手が有効か
 *
 * 組み合わせ表：getConversation の mine
 * 判定：A 見る人がバイト｜B 送ったのがバイト
 * No. | A | B | mine
 *  1  | 真| 真| 真（バイトが自分の発言を見る）
 *  2  | 真| 偽| 偽（バイトが店長の発言を見る）
 *  3  | 偽| 真| 偽（店長がバイトの発言を見る）
 *  4  | 偽| 偽| 真（店長が店長の発言を見る。自分以外の店長も右）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-17T10:00:00");

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TOKYO).toInstant(), TOKYO);
        service = new ChatService(chatMessageRepository, userRepository, notificationService, clock);
    }

    // ---------------------------------------------------------------- 準備

    private static User user(long id, String name, Role role, boolean active) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setRole(role);
        u.setActive(active);
        return u;
    }

    private static ChatMessage message(long id, User staff, User sender, String body, String at) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setStaffUser(staff);
        m.setSenderUser(sender);
        m.setBody(body);
        m.setCreatedAt(LocalDateTime.parse(at));
        return m;
    }

    private static Object[] unread(long staffId, long count) {
        return new Object[] { staffId, count };
    }

    // ---------------------------------------------------------------- 一覧

    @Test
    void getChatList_バイトもメッセージもない_空() {
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of());
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of());
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        assertThat(service.getChatList()).isEmpty();
    }

    @Test
    void getChatList_未読あり_未読なしでメッセージあり_メッセージなしの順で_それぞれ新しい順とふりがな順() {
        User a = user(1L, "青木", Role.STAFF, true);
        User b = user(2L, "伊藤", Role.STAFF, true);
        User c = user(3L, "上田", Role.STAFF, true);
        User d = user(4L, "遠藤", Role.STAFF, true);
        User e = user(5L, "加藤", Role.STAFF, true);
        User f = user(6L, "木村", Role.STAFF, true);
        User g = user(7L, "工藤", Role.STAFF, false);
        // 未読あり：a（古い）と b（新しい）、未読なし：c（新しい）と d（古い）と g（無効で中間）
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of(a, b, c, d, e, f));
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of(
                message(10L, a, a, "a", "2026-11-10T09:00:00"),
                message(11L, b, b, "b", "2026-11-16T09:00:00"),
                message(12L, c, c, "c", "2026-11-17T08:00:00"),
                message(13L, d, a, "d", "2026-11-01T08:00:00"),
                message(14L, g, g, "g", "2026-11-05T08:00:00")));
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of(unread(1L, 1L), unread(2L, 3L)));

        List<ChatListRow> rows = service.getChatList();

        assertThat(rows).extracting(ChatListRow::staffId).containsExactly(2L, 1L, 3L, 7L, 4L, 5L, 6L);
        assertThat(rows).extracting(ChatListRow::unreadCount).containsExactly(3L, 1L, 0L, 0L, 0L, 0L, 0L);
        assertThat(rows).extracting(ChatListRow::active).containsExactly(true, true, true, false, true, true, true);
        assertThat(rows.get(0).lastAt()).isEqualTo(LocalDateTime.parse("2026-11-16T09:00:00"));
        assertThat(rows.get(5).lastAt()).isNull();
    }

    @Test
    void getChatList_最後のメッセージの日時が同じ_メッセージIDの大きい方が上() {
        User a = user(1L, "青木", Role.STAFF, true);
        User b = user(2L, "伊藤", Role.STAFF, true);
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of(a, b));
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of(
                message(10L, a, a, "a", "2026-11-16T09:00:00"),
                message(11L, b, b, "b", "2026-11-16T09:00:00")));
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        assertThat(service.getChatList()).extracting(ChatListRow::staffId).containsExactly(2L, 1L);
    }

    @Test
    void getChatList_無効なバイトでメッセージあり_氏名に無効を付けて出す() {
        User g = user(7L, "工藤", Role.STAFF, false);
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of());
        when(chatMessageRepository.findLatestPerStaff())
                .thenReturn(List.of(message(14L, g, g, "お世話になりました", "2026-11-05T08:00:00")));
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        List<ChatListRow> rows = service.getChatList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).staffName()).isEqualTo("工藤（無効）");
        assertThat(rows.get(0).active()).isFalse();
        assertThat(rows.get(0).lastAt()).isEqualTo(LocalDateTime.parse("2026-11-05T08:00:00"));
        assertThat(rows.get(0).initial()).isEqualTo("工");
    }

    @Test
    void getChatList_メッセージがないバイト_本文と日時がnullで氏名はそのまま() {
        User e = user(5L, "加藤", Role.STAFF, true);
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of(e));
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of());
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        ChatListRow row = service.getChatList().get(0);

        assertThat(row).isEqualTo(new ChatListRow(5L, "加", "加藤", true, null, null, null, 0L));
    }

    @Test
    void getChatList_最後のメッセージが30文字と31文字_30文字はそのまま_31文字は切って省略の印() {
        User a = user(1L, "青木", Role.STAFF, true);
        User b = user(2L, "伊藤", Role.STAFF, true);
        String thirty = "あ".repeat(30);
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of(a, b));
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of(
                message(10L, a, a, thirty, "2026-11-17T09:00:00"),
                message(11L, b, b, thirty + "い", "2026-11-17T08:00:00")));
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        List<ChatListRow> rows = service.getChatList();

        assertThat(rows.get(0).lastBody()).isEqualTo(thirty);
        assertThat(rows.get(1).lastBody()).isEqualTo(thirty + "…");
    }

    @Test
    void getChatList_最後の日時が今日_今年_前の年_それぞれ時刻_月日_年月日() {
        User a = user(1L, "青木", Role.STAFF, true);
        User b = user(2L, "伊藤", Role.STAFF, true);
        User c = user(3L, "上田", Role.STAFF, true);
        when(userRepository.findByRoleAndActive(Role.STAFF, true)).thenReturn(List.of(a, b, c));
        when(chatMessageRepository.findLatestPerStaff()).thenReturn(List.of(
                message(10L, a, a, "x", "2026-11-17T09:05:00"),
                message(11L, b, b, "x", "2026-03-04T23:59:00"),
                message(12L, c, c, "x", "2025-12-31T23:59:00")));
        when(chatMessageRepository.countUnreadPerStaff()).thenReturn(List.of());

        List<ChatListRow> rows = service.getChatList();

        assertThat(rows).extracting(ChatListRow::lastAtLabel).containsExactly("9:05", "3/4", "2025/12/31");
    }

    @Test
    void lastAtLabel_今年の1月1日と前の年の12月31日_境目で形が変わる() {
        LocalDate today = LocalDate.parse("2026-11-17");

        assertThat(ChatService.lastAtLabel(LocalDateTime.parse("2026-01-01T00:00:00"), today)).isEqualTo("1/1");
        assertThat(ChatService.lastAtLabel(LocalDateTime.parse("2025-12-31T23:59:59"), today))
                .isEqualTo("2025/12/31");
    }

    // ---------------------------------------------------------------- 相手の取得

    @Test
    void getStaffForChat_バイトがいる_返す() {
        User staff = user(3L, "山田", Role.STAFF, true);
        when(userRepository.findByIdAndRole(3L, Role.STAFF)).thenReturn(Optional.of(staff));

        assertThat(service.getStaffForChat(3L)).isSameAs(staff);
    }

    @Test
    void getStaffForChat_いない_または店長や管理者のID_404() {
        when(userRepository.findByIdAndRole(9L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStaffForChat(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- 会話

    @Test
    void getConversation_バイトが見る_古い順で自分の発言が右_既読はバイト向け() {
        User staff = user(3L, "山田", Role.STAFF, true);
        User manager = user(1L, "店長A", Role.MANAGER, true);
        // リポジトリは新しい順（200件）で返す
        when(userRepository.findById(3L)).thenReturn(Optional.of(staff));
        when(chatMessageRepository.findLatest200(3L)).thenReturn(List.of(
                message(22L, staff, manager, "新しい", "2026-11-16T10:05:00"),
                message(21L, staff, staff, "古い", "2026-11-16T10:00:00")));

        List<ChatMessageView> views = service.getConversation(3L, 3L);

        assertThat(views).extracting(ChatMessageView::id).containsExactly(21L, 22L);
        assertThat(views).extracting(ChatMessageView::mine).containsExactly(true, false);
        assertThat(views).extracting(ChatMessageView::senderName).containsExactly("山田", "店長A");
        assertThat(views).extracting(ChatMessageView::createdAtLabel).containsExactly("11/16 10:00", "11/16 10:05");
        verify(chatMessageRepository).markRead(3L, true, NOW);
    }

    @Test
    void getConversation_店長が見る_店長の発言は右_バイトの発言は左_既読は店長向け() {
        User staff = user(3L, "山田", Role.STAFF, true);
        User a = user(1L, "店長A", Role.MANAGER, true);
        User b = user(2L, "店長B", Role.MANAGER, true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(b));
        when(chatMessageRepository.findLatest200(3L)).thenReturn(List.of(
                message(32L, staff, a, "Aから", "2026-11-16T10:05:00"),
                message(31L, staff, staff, "バイトから", "2026-11-16T10:00:00")));

        List<ChatMessageView> views = service.getConversation(3L, 2L);

        assertThat(views).extracting(ChatMessageView::mine).containsExactly(false, true);
        assertThat(views.get(1).senderName()).isEqualTo("店長A");
        verify(chatMessageRepository).markRead(3L, false, NOW);
    }

    @Test
    void getConversation_メッセージがない_空で既読の更新は呼ぶ() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "店長B", Role.MANAGER, true)));
        when(chatMessageRepository.findLatest200(3L)).thenReturn(new ArrayList<>());

        assertThat(service.getConversation(3L, 2L)).isEmpty();
        verify(chatMessageRepository).markRead(3L, false, NOW);
    }

    @Test
    void getConversation_見る人がいない_404で既読にしない() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversation(3L, 99L)).isInstanceOf(ResourceNotFoundException.class);
        verify(chatMessageRepository, never()).markRead(any(), anyBoolean(), any());
    }

    // ---------------------------------------------------------------- 送信

    @Test
    void send_有効なバイトへ店長が送る_1行保存してお知らせを作る() {
        User staff = user(3L, "山田", Role.STAFF, true);
        User manager = user(1L, "店長A", Role.MANAGER, true);
        when(userRepository.findByIdAndRole(3L, Role.STAFF)).thenReturn(Optional.of(staff));
        when(userRepository.findById(1L)).thenReturn(Optional.of(manager));

        service.send(3L, 1L, "こんにちは");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getStaffUser()).isSameAs(staff);
        assertThat(captor.getValue().getSenderUser()).isSameAs(manager);
        assertThat(captor.getValue().getBody()).isEqualTo("こんにちは");
        verify(notificationService).notifyChat(3L, manager, "こんにちは");
    }

    @Test
    void send_相手がいない_404で保存もお知らせもしない() {
        when(userRepository.findByIdAndRole(9L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(9L, 1L, "x")).isInstanceOf(ResourceNotFoundException.class);
        verify(chatMessageRepository, never()).save(any());
        verify(notificationService, never()).notifyChat(any(), any(), any());
    }

    @Test
    void send_送る人がいない_404で保存もお知らせもしない() {
        when(userRepository.findByIdAndRole(3L, Role.STAFF))
                .thenReturn(Optional.of(user(3L, "山田", Role.STAFF, true)));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(3L, 99L, "x")).isInstanceOf(ResourceNotFoundException.class);
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void send_相手のバイトが無効_E3101で保存もお知らせもしない() {
        when(userRepository.findByIdAndRole(3L, Role.STAFF))
                .thenReturn(Optional.of(user(3L, "山田", Role.STAFF, false)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "店長A", Role.MANAGER, true)));

        assertThatThrownBy(() -> service.send(3L, 1L, "x"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("E3101"));
        verify(chatMessageRepository, never()).save(any());
        verify(notificationService, never()).notifyChat(any(), any(), any());
    }
}

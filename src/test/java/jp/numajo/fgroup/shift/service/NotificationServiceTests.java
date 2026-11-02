package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.Notification;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.NotificationRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * NotificationService の単体テスト（95_通知・定期処理設計書 1.2・5章）。
 *
 * <p>
 * 件名・本文は本物の messages.properties から作り、文面まで確かめる。
 * Propagation.MANDATORY（業務処理のトランザクションの外では動かない）は LayoutTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定条件網羅：
 * - notifyVacancy：割当に枠があるか（枠名／N1006「時間指定 H:mm〜H:mm」）
 * - notifyChat：送った人がバイトか（有効な店長全員へ／そのバイトへ）、バイトがいるか（いない→404）
 * - notifyPublished・notifyVacancy：宛先の繰り返し（0人／2人）
 *
 * 組み合わせ表：cut（件名100文字・本文500文字・チャット50文字で切る）
 * 判定：A text == null || B 長さが max 以下
 * No. | A | B | 結果
 *  1  | 真| － | null（短絡評価で B は評価されない）
 *  2  | 偽| 真| そのまま（ちょうど max 文字を含む）
 *  3  | 偽| 偽| 先頭の max 文字
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTests {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        service = new NotificationService(notificationRepository, userRepository, messageSource);
    }

    private static User user(long id, String name, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setRole(role);
        return u;
    }

    private static ShiftPeriod period() {
        ShiftPeriod p = new ShiftPeriod();
        p.setId(7L);
        p.setStartDate(LocalDate.of(2026, 11, 1));
        p.setEndDate(LocalDate.of(2026, 11, 15));
        return p;
    }

    private List<Notification> saved(int count) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void countUnread_未読の数を返す() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(3L)).thenReturn(5L);

        long actual = service.countUnread(3L);

        assertThat(actual).isEqualTo(5L);
    }

    @Test
    void notifyPublished_有効なバイト全員に作る() {
        User a = user(1L, "山田", Role.STAFF);
        User b = user(2L, "佐藤", Role.STAFF);
        when(userRepository.findActiveStaff()).thenReturn(List.of(a, b));

        service.notifyPublished(period());

        List<Notification> list = saved(2);
        assertThat(list).extracting(Notification::getUser).containsExactly(a, b);
        Notification n = list.get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.PUBLISHED);
        assertThat(n.getTitle()).isEqualTo("シフトが公開されました");
        assertThat(n.getBody()).isEqualTo("11/1〜11/15 のシフトが公開されました。");
        assertThat(n.getLinkUrl()).isEqualTo("/staff/shifts?periodId=7");
        assertThat(n.getReadAt()).isNull();
    }

    @Test
    void notifyPublished_有効なバイトがいなければ作らない() {
        when(userRepository.findActiveStaff()).thenReturn(List.of());

        service.notifyPublished(period());

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyChanged_本人に1件作る() {
        User staff = user(1L, "山田", Role.STAFF);

        service.notifyChanged(staff, period(), "11月3日(火) 日勤 が追加されました。");

        Notification n = saved(1).get(0);
        assertThat(n.getUser()).isSameAs(staff);
        assertThat(n.getType()).isEqualTo(NotificationType.CHANGED);
        assertThat(n.getTitle()).isEqualTo("シフトが変更されました");
        assertThat(n.getBody()).isEqualTo("11月3日(火) 日勤 が追加されました。");
        assertThat(n.getLinkUrl()).isEqualTo("/staff/shifts?periodId=7");
    }

    @Test
    void notifyVacancy_枠の割当は件名に日付と枠名() {
        Slot slot = new Slot();
        slot.setName("日勤");
        ShiftAssignment a = new ShiftAssignment();
        a.setWorkDate(LocalDate.of(2026, 10, 27));
        a.setSlot(slot);
        User x = user(1L, "山田", Role.STAFF);
        User y = user(2L, "佐藤", Role.STAFF);

        service.notifyVacancy(List.of(x, y), a, "入れる人はいませんか");

        List<Notification> list = saved(2);
        assertThat(list).extracting(Notification::getUser).containsExactly(x, y);
        Notification n = list.get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.VACANCY);
        assertThat(n.getTitle()).isEqualTo("欠員が出ました（10月27日(火) 日勤）");
        assertThat(n.getBody()).isEqualTo("入れる人はいませんか");
        assertThat(n.getLinkUrl()).isEqualTo("/staff/home");
    }

    @Test
    void notifyVacancy_時間指定は件名の枠名が時間指定の時刻() {
        ShiftAssignment a = new ShiftAssignment();
        a.setWorkDate(LocalDate.of(2026, 10, 27));
        a.setStartTime(LocalTime.of(13, 0));
        a.setEndTime(LocalTime.of(22, 0));

        service.notifyVacancy(List.of(user(1L, "山田", Role.STAFF)), a, "本文");

        assertThat(saved(1).get(0).getTitle()).isEqualTo("欠員が出ました（10月27日(火) 時間指定 13:00〜22:00）");
    }

    @Test
    void notifyVacancy_相手がいなければ作らない() {
        ShiftAssignment a = new ShiftAssignment();
        a.setWorkDate(LocalDate.of(2026, 10, 27));
        a.setSlot(new Slot());

        service.notifyVacancy(List.of(), a, "本文");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyChat_バイトが送ったら有効な店長全員へ() {
        User sender = user(5L, "山田", Role.STAFF);
        User m1 = user(8L, "店長A", Role.MANAGER);
        User m2 = user(9L, "店長B", Role.MANAGER);
        when(userRepository.findByRoleAndActive(Role.MANAGER, true)).thenReturn(List.of(m1, m2));

        service.notifyChat(5L, sender, "明日の夕勤を代わってほしいです");

        List<Notification> list = saved(2);
        assertThat(list).extracting(Notification::getUser).containsExactly(m1, m2);
        Notification n = list.get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.CHAT);
        assertThat(n.getTitle()).isEqualTo("山田さんから新しいメッセージ");
        assertThat(n.getBody()).isEqualTo("明日の夕勤を代わってほしいです");
        assertThat(n.getLinkUrl()).isEqualTo("/manager/chats/5");
    }

    @Test
    void notifyChat_店長が送ったらそのバイトへ_本文は先頭50文字() {
        User sender = user(8L, "店長A", Role.MANAGER);
        User staff = user(5L, "山田", Role.STAFF);
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));
        String body = "あ".repeat(51);

        service.notifyChat(5L, sender, body);

        Notification n = saved(1).get(0);
        assertThat(n.getUser()).isSameAs(staff);
        assertThat(n.getTitle()).isEqualTo("店長Aさんから新しいメッセージ");
        assertThat(n.getBody()).isEqualTo("あ".repeat(50));
        assertThat(n.getLinkUrl()).isEqualTo("/staff/chat");
        verify(userRepository, never()).findByRoleAndActive(any(), any(Boolean.class));
    }

    @Test
    void notifyChat_相手のバイトがいなければ404() {
        User sender = user(8L, "店長A", Role.MANAGER);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.notifyChat(99L, sender, "本文"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_件名は100文字_本文は500文字で切る() {
        User staff = user(1L, "山田", Role.STAFF);

        service.notifyChanged(staff, period(), "い".repeat(501));

        assertThat(saved(1).get(0).getBody()).hasSize(500);
    }

    @Test
    void cut_No1_nullはnull() {
        assertThat(NotificationService.cut(null, 3)).isNull();
    }

    @Test
    void cut_No2_max文字以下はそのまま() {
        assertThat(NotificationService.cut("abc", 3)).isEqualTo("abc");
        assertThat(NotificationService.cut("ab", 3)).isEqualTo("ab");
    }

    @Test
    void cut_No3_max文字を超えたら先頭のmax文字() {
        assertThat(NotificationService.cut("abcd", 3)).isEqualTo("abc");
    }

    @Test
    void create_件名が100文字を超えたら切る() {
        User sender = user(8L, "長".repeat(100), Role.MANAGER);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, "山田", Role.STAFF)));

        service.notifyChat(5L, sender, "本文");

        assertThat(saved(1).get(0).getTitle()).isEqualTo("長".repeat(100));
    }
}

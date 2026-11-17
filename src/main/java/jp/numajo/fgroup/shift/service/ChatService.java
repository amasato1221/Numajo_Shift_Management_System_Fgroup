package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ChatListRow;
import jp.numajo.fgroup.shift.dto.ChatMessageView;
import jp.numajo.fgroup.shift.entity.ChatMessage;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ChatMessageRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * チャット（SB-06・SM-10・SM-11）。20-SM-10、20-SM-11、20-SB-06。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    /** 一覧に出す最後のメッセージの最大文字数（20-SM-10 1章） */
    static final int LAST_BODY_LENGTH = 30;
    /** 切ったときに付ける印 */
    static final String ELLIPSIS = "…";
    /** 無効なバイトの氏名に付ける印 */
    static final String INACTIVE_MARK = "（無効）";

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /**
     * チャット一覧の行を作る（20-SM-10 2.1）。
     * 並びは、未読ありの会話（新しい順）、未読なしでメッセージありの会話（新しい順）、メッセージなしの有効なバイト（ふりがな順）。
     * 無効なバイトは、メッセージがあるときだけ出す。
     *
     * @return 一覧の行（バイトがいなければ空）
     */
    public List<ChatListRow> getChatList() {
        Map<Long, ChatMessage> latest = new HashMap<>();
        for (ChatMessage m : chatMessageRepository.findLatestPerStaff()) {
            latest.put(m.getStaffUser().getId(), m);
        }
        Map<Long, Long> unread = new HashMap<>();
        for (Object[] r : chatMessageRepository.countUnreadPerStaff()) {
            unread.put((Long) r[0], (Long) r[1]);
        }

        List<User> staffList = new ArrayList<>(userRepository.findByRoleAndActive(Role.STAFF, true));
        List<User> inactive = new ArrayList<>();
        for (ChatMessage m : latest.values()) {
            if (!m.getStaffUser().isActive()) {
                inactive.add(m.getStaffUser());
            }
        }
        staffList.addAll(inactive);

        List<User> withUnread = new ArrayList<>();
        List<User> withMessage = new ArrayList<>();
        List<User> noMessage = new ArrayList<>();
        for (User u : staffList) {
            if (unread.getOrDefault(u.getId(), 0L) > 0) {
                withUnread.add(u);
            } else if (latest.containsKey(u.getId())) {
                withMessage.add(u);
            } else {
                noMessage.add(u);
            }
        }
        Comparator<User> newestFirst = Comparator
                .comparing((User u) -> latest.get(u.getId()).getCreatedAt()).reversed()
                .thenComparing(Comparator.comparing((User u) -> latest.get(u.getId()).getId()).reversed());
        withUnread.sort(newestFirst);
        withMessage.sort(newestFirst);

        LocalDate today = LocalDate.now(clock);
        List<ChatListRow> rows = new ArrayList<>();
        for (List<User> group : List.of(withUnread, withMessage, noMessage)) {
            for (User u : group) {
                rows.add(toRow(u, latest.get(u.getId()), unread.getOrDefault(u.getId(), 0L), today));
            }
        }
        return rows;
    }

    private static ChatListRow toRow(User staff, ChatMessage last, long unreadCount, LocalDate today) {
        String name = staff.isActive() ? staff.getName() : staff.getName() + INACTIVE_MARK;
        String initial = staff.getName().substring(0, 1);
        if (last == null) {
            return new ChatListRow(staff.getId(), initial, name, null, null, unreadCount);
        }
        return new ChatListRow(staff.getId(), initial, name, cutBody(last.getBody()),
                lastAtLabel(last.getCreatedAt(), today), unreadCount);
    }

    /** 30文字を超えたら30文字で切って「…」を付ける。 */
    static String cutBody(String body) {
        if (body.length() > LAST_BODY_LENGTH) {
            return body.substring(0, LAST_BODY_LENGTH) + ELLIPSIS;
        }
        return body;
    }

    /** 今日なら H:mm、今年なら M/d、それより前なら yyyy/M/d。 */
    static String lastAtLabel(LocalDateTime at, LocalDate today) {
        LocalDate date = at.toLocalDate();
        if (date.equals(today)) {
            return DateTimeUtil.time(at.toLocalTime());
        }
        if (date.getYear() == today.getYear()) {
            return DateTimeUtil.shortDate(date);
        }
        return DateTimeUtil.shortYearDate(date);
    }

    /**
     * チャットの相手のバイトを取る（20-SM-11 2.1-1）。
     *
     * @param staffId バイトのユーザーID
     * @return バイト（無効なバイトも含む）
     * @throws ResourceNotFoundException ユーザーがない、または role が STAFF でない
     */
    public User getStaffForChat(Long staffId) {
        return userRepository.findByIdAndRole(staffId, Role.STAFF)
                .orElseThrow(() -> new ResourceNotFoundException("staff " + staffId));
    }

    /**
     * 会話を古い順に取り、相手のメッセージを既読にする（20-SB-06 2.1、20-SM-11 2.1-2）。
     * 新しい200件だけを出す。見る人がバイトなら店長が送ったものを、店長ならバイトが送ったものを既読にする。
     *
     * @param staffUserId  会話の相手のバイトのユーザーID
     * @param viewerUserId 見ている人のユーザーID
     * @return 吹き出しの並び（古い順）
     */
    @Transactional
    public List<ChatMessageView> getConversation(Long staffUserId, Long viewerUserId) {
        User viewer = userRepository.findById(viewerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user " + viewerUserId));
        boolean byStaff = viewer.getRole() == Role.STAFF;

        List<ChatMessage> messages = new ArrayList<>(chatMessageRepository.findLatest200(staffUserId));
        Collections.reverse(messages);
        chatMessageRepository.markRead(staffUserId, byStaff, LocalDateTime.now(clock));

        List<ChatMessageView> views = new ArrayList<>();
        for (ChatMessage m : messages) {
            boolean sentByStaff = m.getSenderUser().getId().equals(staffUserId);
            boolean mine = byStaff == sentByStaff;
            views.add(new ChatMessageView(m.getId(), m.getSenderUser().getName(), m.getBody(), m.getCreatedAt(),
                    DateTimeUtil.shortDateTime(m.getCreatedAt()), mine));
        }
        return views;
    }

    /**
     * メッセージを送り、相手にお知らせを作る（20-SB-06 2.2、20-SM-11 2.2）。
     *
     * @param staffUserId  会話の相手のバイトのユーザーID
     * @param senderUserId 送る人のユーザーID
     * @param body         本文
     * @throws ResourceNotFoundException 相手がいない・role が STAFF でない・送る人がいない
     * @throws BusinessException         相手のバイトが無効（E3101）
     */
    @Transactional
    public void send(Long staffUserId, Long senderUserId, String body) {
        User staff = getStaffForChat(staffUserId);
        User sender = userRepository.findById(senderUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user " + senderUserId));
        if (!staff.isActive()) {
            throw new BusinessException("E3101");
        }
        ChatMessage m = new ChatMessage();
        m.setStaffUser(staff);
        m.setSenderUser(sender);
        m.setBody(body);
        chatMessageRepository.save(m);
        notificationService.notifyChat(staffUserId, sender, body);
    }
}

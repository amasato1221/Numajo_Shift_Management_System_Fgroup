package jp.numajo.fgroup.shift.service;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.Notification;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.NotificationRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * アプリ内のお知らせ（95_通知・定期処理設計書 1.2・5章）。お知らせを作るのはこのクラスだけ。
 *
 * <p>
 * ④b ではヘッダーの未読数と、ほかの Service が呼ぶ notify で始まるメソッドだけを作る。
 * 一覧・既読・締切のお知らせ（SC-02・SB-01・BT-03）は、その画面と一緒に足す。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    /** 件名の最大文字数（notifications.title の長さ） */
    static final int TITLE_MAX = 100;
    /** 本文の最大文字数（notifications.body の長さ） */
    static final int BODY_MAX = 500;
    /** チャットのお知らせの本文に入れる文字数 */
    static final int CHAT_BODY_LENGTH = 50;
    /** 一覧に出す本文の最大文字数（20-SC-02 2.1） */
    static final int ROW_BODY_MAX = 100;
    /** 一覧の本文を切ったときに付ける印（20-SC-02 1章 No.2-4） */
    static final String ELLIPSIS = "…";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;

    /** 自分の未読のお知らせ数（ヘッダー）。 */
    public long countUnread(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * 未読のお知らせを新しい順に3件（20-SB-01 2.1-3）。
     *
     * @param userId ユーザーID
     * @return 一覧の行（0〜3件）
     */
    public List<NoticeRow> findUnreadTop3(Long userId) {
        return notificationRepository.findTop3ByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId).stream()
                .map(NotificationService::toRow)
                .toList();
    }

    /** お知らせを一覧の1行にする（20-SC-02 2.1-2）。本文は100文字を超えたら切って「…」を付ける。 */
    static NoticeRow toRow(Notification n) {
        String body = n.getBody();
        if (body != null && body.length() > ROW_BODY_MAX) {
            body = body.substring(0, ROW_BODY_MAX) + ELLIPSIS;
        }
        return new NoticeRow(n.getId(), n.getType(), n.getType().getLabel(), n.getTitle(), body,
                n.getCreatedAt(), n.getReadAt() == null);
    }

    /** シフトの公開を、有効なバイト全員に知らせる（PUBLISHED。20-SM-06 3.6）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyPublished(ShiftPeriod period) {
        String title = message("N0002");
        String body = message("N1002", DateTimeUtil.period(period.getStartDate(), period.getEndDate()));
        String linkUrl = shiftsUrl(period);
        for (User staff : userRepository.findActiveStaff()) {
            create(staff, NotificationType.PUBLISHED, title, body, linkUrl);
        }
    }

    /**
     * 公開後の割当の変更を本人に知らせる（CHANGED。20-SM-06 3.3〜3.5、20-SM-07 3.3）。
     *
     * @param staff  変わったバイト
     * @param period 割当の期間
     * @param body   本文（呼び出し元が N1003〜N1005 で作る）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyChanged(User staff, ShiftPeriod period, String body) {
        create(staff, NotificationType.CHANGED, message("N0003"), body, shiftsUrl(period));
    }

    /**
     * 欠員を知らせる（VACANCY。20-SM-07 3.3）。件名は「欠員が出ました（日付 枠名）」。
     *
     * @param recipients 欠員通知の相手（20-SM-07 3.4）
     * @param assignment 欠勤になった割当
     * @param body       本文（店長が入力した文）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyVacancy(List<User> recipients, ShiftAssignment assignment, String body) {
        String title = message("N0004", DateTimeUtil.monthDay(assignment.getWorkDate()), slotName(assignment));
        for (User to : recipients) {
            create(to, NotificationType.VACANCY, title, body, "/staff/home");
        }
    }

    /**
     * チャットの新しいメッセージを知らせる（CHAT。20-SB-06 2.2、20-SM-11 2.2）。
     * 送ったのがバイトなら有効な店長全員へ、店長ならそのバイトへ。
     *
     * @param staffUserId やり取りの相手のバイトのユーザーID
     * @param sender      送った人
     * @param body        メッセージ（先頭50文字を本文にする）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyChat(Long staffUserId, User sender, String body) {
        String title = message("N0005", sender.getName());
        String shortBody = cut(body, CHAT_BODY_LENGTH);
        if (sender.getRole() == Role.STAFF) {
            for (User manager : userRepository.findByRoleAndActive(Role.MANAGER, true)) {
                create(manager, NotificationType.CHAT, title, shortBody, "/manager/chats/" + staffUserId);
            }
            return;
        }
        User staff = userRepository.findById(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user " + staffUserId));
        create(staff, NotificationType.CHAT, title, shortBody, "/staff/chat");
    }

    /** お知らせを1件作る。件名は100文字、本文は500文字で切る（95 5章）。 */
    private void create(User to, NotificationType type, String title, String body, String linkUrl) {
        Notification n = new Notification();
        n.setUser(to);
        n.setType(type);
        n.setTitle(cut(title, TITLE_MAX));
        n.setBody(cut(body, BODY_MAX));
        n.setLinkUrl(linkUrl);
        notificationRepository.save(n);
    }

    /** 枠名。時間指定（枠なし）は「時間指定 13:00〜22:00」（N1006）。 */
    private String slotName(ShiftAssignment assignment) {
        if (assignment.getSlot() == null) {
            return message("N1006", DateTimeUtil.time(assignment.getStartTime()),
                    DateTimeUtil.time(assignment.getEndTime()));
        }
        return assignment.getSlot().getName();
    }

    private String message(String code, Object... args) {
        return messageSource.getMessage(code, args, Locale.JAPAN);
    }

    private static String shiftsUrl(ShiftPeriod period) {
        return "/staff/shifts?periodId=" + period.getId();
    }

    /** max 文字を超えたら切る。null は null。 */
    static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}

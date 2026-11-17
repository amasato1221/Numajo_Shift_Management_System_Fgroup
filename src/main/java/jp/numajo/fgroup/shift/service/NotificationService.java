package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.NoticeRow;
import jp.numajo.fgroup.shift.entity.Notification;
import jp.numajo.fgroup.shift.entity.ShiftAssignment;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.NotificationRepository;
import jp.numajo.fgroup.shift.repository.NotificationSettingRepository;
import jp.numajo.fgroup.shift.repository.RequestSubmissionRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * アプリ内のお知らせ（95_通知・定期処理設計書 1.2・5章）。お知らせを作るのはこのクラスだけ。
 *
 * <p>
 * ヘッダーの未読数、SB-01・SC-02 の一覧と既読、ほかの Service が呼ぶ notify で始まるメソッド、
 * 締切のお知らせ（BT-03。sendDeadlineReminders）を持つ。
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
    /** 一覧に出す最大件数（20-SC-02 2.1。findTop100… と同じ数） */
    static final int LIST_LIMIT = 100;
    /** 移動先が使えないときに戻る先（20-SC-02 2.2-1-3、94 3.4） */
    static final String NOTICES_URL = "/notices";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    private final Clock clock;
    private final ShiftPeriodRepository shiftPeriodRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final RequestSubmissionRepository requestSubmissionRepository;

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

    /**
     * 自分のお知らせを新しい順に100件（20-SC-02 2.1）。
     *
     * @param userId ユーザーID
     * @param limit  件数（100 だけを使う。30-05 1.2）
     * @return 一覧の行（0〜100件）
     * @throws IllegalArgumentException limit が 100 でないとき
     */
    public List<NoticeRow> findLatest(Long userId, int limit) {
        if (limit != LIST_LIMIT) {
            throw new IllegalArgumentException("limit は " + LIST_LIMIT + " だけを使う: " + limit);
        }
        return notificationRepository.findTop100ByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(NotificationService::toRow)
                .toList();
    }

    /**
     * お知らせを開く（20-SC-02 2.2）。未読なら既読にし、移動先を返す。
     *
     * @param userId         ユーザーID
     * @param notificationId お知らせID
     * @return リダイレクト先（link_url。使えないときは /notices）
     * @throws ResourceNotFoundException 自分のお知らせにないとき（404）
     */
    @Transactional
    public String open(Long userId, Long notificationId) {
        Notification n = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("notification " + notificationId));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(clock));
        }
        return safeLink(n.getLinkUrl());
    }

    /**
     * 自分の未読をすべて既読にする（20-SC-02 2.3）。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void readAll(Long userId) {
        notificationRepository.markAllRead(userId, LocalDateTime.now(clock));
    }

    /**
     * 移動先が同じサイトの中か確かめる（20-SC-02 2.2-1-3、94 3.4）。
     * null・/ で始まらない・// で始まるときは /notices にする（ほかのサイトへ飛ばさないため）。
     */
    static String safeLink(String linkUrl) {
        if (linkUrl == null || !linkUrl.startsWith("/") || linkUrl.startsWith("//")) {
            return NOTICES_URL;
        }
        return linkUrl;
    }

    /** お知らせを一覧の1行にする（20-SC-02 2.1-2）。本文は100文字を超えたら切って「…」を付ける。 */
    static NoticeRow toRow(Notification n) {
        String body = n.getBody();
        if (body != null && body.length() > ROW_BODY_MAX) {
            body = body.substring(0, ROW_BODY_MAX) + ELLIPSIS;
        }
        return new NoticeRow(n.getId(), n.getType(), n.getType().getLabel(), n.getTitle(), body,
                n.getCreatedAt(), n.getCreatedAt() == null ? null : DateTimeUtil.dateTime(n.getCreatedAt()),
                n.getReadAt() == null);
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

    /**
     * 締切が近い人へ DEADLINE のお知らせを作る（BT-03。95_通知・定期処理設計書 4章）。
     *
     * <p>
     * 期限まで 0・1・3・7 日のそれぞれについて、その日が期限の受付中の期間を探し、
     * その日数を選んでいる有効なバイトのうち、未提出で、今日まだ同じお知らせを受け取っていない人に1件ずつ作る。
     * </p>
     *
     * @param today 今日（scheduler が LocalDate.now(clock) を渡す）
     * @return 作ったお知らせの件数
     */
    @Transactional
    public int sendDeadlineReminders(LocalDate today) {
        int count = 0;
        String title = message("N0001");
        for (Integer d : NotificationSettingService.DAYS_OPTIONS) {
            LocalDate target = today.plusDays(d);
            List<ShiftPeriod> periods = shiftPeriodRepository.findByStatusAndDeadlineAtBetween(PeriodStatus.OPEN,
                    target.atStartOfDay(), target.atTime(23, 59, 59));
            if (periods.isEmpty()) {
                continue;
            }
            List<Long> userIds = notificationSettingRepository.findUserIdsByDaysBefore(d.byteValue());
            if (userIds.isEmpty()) {
                continue;
            }
            List<User> staffs = userRepository.findAllById(userIds).stream()
                    .filter(u -> u.getRole() == Role.STAFF && u.isActive())
                    .sorted(Comparator.comparing(User::getId))
                    .toList();
            for (ShiftPeriod period : periods) {
                Set<Long> submitted = Set.copyOf(requestSubmissionRepository.findUserIdsByPeriodId(period.getId()));
                String linkUrl = requestsUrl(period);
                String body = message("N1001", DateTimeUtil.period(period.getStartDate(), period.getEndDate()),
                        DateTimeUtil.dateTime(period.getDeadlineAt()));
                for (User staff : staffs) {
                    if (submitted.contains(staff.getId())) {
                        continue;
                    }
                    if (notificationRepository.existsByUserIdAndTypeAndLinkUrlAndCreatedAtGreaterThanEqual(
                            staff.getId(), NotificationType.DEADLINE, linkUrl, today.atStartOfDay())) {
                        continue;
                    }
                    create(staff, NotificationType.DEADLINE, title, body, linkUrl);
                    count++;
                }
            }
        }
        return count;
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

    /** 希望提出の画面へのリンク（DEADLINE。95 1.2）。 */
    private static String requestsUrl(ShiftPeriod period) {
        return "/staff/requests?periodId=" + period.getId();
    }

    /** max 文字を超えたら切る。null は null。 */
    static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}

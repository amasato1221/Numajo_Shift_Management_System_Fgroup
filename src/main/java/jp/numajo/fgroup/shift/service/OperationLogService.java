package jp.numajo.fgroup.shift.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.entity.OperationLog;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.form.LogSearchForm;
import jp.numajo.fgroup.shift.repository.OperationLogRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 操作ログの記録と検索。
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    /** detail の最大文字数 */
    static final int DETAIL_MAX = 500;

    /** 利用者がわからないとき（user_id が NULL）の表示 */
    static final String UNKNOWN_USER = "－";

    private final OperationLogRepository operationLogRepository;
    private final UserRepository userRepository;

    /** 業務処理と同じトランザクションで記録する（業務処理が失敗したらログも残らない）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long userId, OperationAction action, String target, String detail) {
        save(userId, action, target, detail);
    }

    /** 新しいトランザクションで記録する（ログイン・ログアウトなど、業務処理の外から呼ぶとき）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(Long userId, OperationAction action, String target, String detail) {
        save(userId, action, target, detail);
    }

    private void save(Long userId, OperationAction action, String target, String detail) {
        OperationLog log = new OperationLog();
        log.setUser(userId == null ? null : userRepository.getReferenceById(userId));
        log.setAction(action.name());
        log.setTarget(target);
        log.setDetail(truncate(detail));
        log.setIpAddress(currentIpAddress());
        operationLogRepository.save(log);
    }

    /** 500文字を超えたら 497文字＋「...」にする。 */
    static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX - 3) + "...";
    }

    /** リクエスト中なら接続元のIPアドレス、定期処理などリクエストがないときは null。 */
    private String currentIpAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }

    /**
     * SA-03 操作ログを検索する（20-SA-03 2.1-5）。
     *
     * <p>
     * from・to は呼び出し元（AdminLogController）で既定値を入れ、from &lt;= to を確かめてから渡す。
     * 期間は from の 0:00:00 以上、to の翌日 0:00:00 未満。action は列が VARCHAR のため名前の文字列で比べる。
     * </p>
     *
     * @param form     検索条件（from・to は null でないこと）
     * @param pageable ページと並び（新しい順）
     * @return 1ページ分の行
     */
    @Transactional(readOnly = true)
    public Page<OperationLogRow> search(LogSearchForm form, Pageable pageable) {
        String action = form.getAction() == null ? null : form.getAction().name();
        return operationLogRepository.search(form.getFrom().atStartOfDay(), form.getTo().plusDays(1).atStartOfDay(),
                form.getUserId(), action, pageable).map(OperationLogService::toRow);
    }

    /**
     * SA-01 最近の操作（新しい順。20-SA-01 2.1-2）。
     *
     * @param limit 件数（5 だけを使う。findLatest5 の5件より多くは返らない）
     * @return 新しい順の行
     */
    @Transactional(readOnly = true)
    public List<OperationLogRow> findRecent(int limit) {
        return operationLogRepository.findLatest5().stream()
                .limit(limit)
                .map(OperationLogService::toRow)
                .toList();
    }

    /**
     * SA-03 利用者のプルダウン（全ユーザー、無効も含む。ふりがな順。20-SA-03 1.1 No.3）。
     *
     * @return 値＝ユーザーID、表示＝「氏名（ログインID・役割）」
     */
    @Transactional(readOnly = true)
    public List<SelectOption> userOptions() {
        return userRepository.findAllForOptions().stream()
                .map(u -> new SelectOption(String.valueOf(u.getId()),
                        u.getName() + "（" + u.getLoginId() + "・" + roleLabel(u.getRole()) + "）"))
                .toList();
    }

    /** エンティティを一覧の1行にする。 */
    static OperationLogRow toRow(OperationLog log) {
        User user = log.getUser();
        return new OperationLogRow(log.getId(), log.getCreatedAt(), DateTimeUtil.dateTimeSeconds(log.getCreatedAt()),
                user == null ? UNKNOWN_USER : user.getName(), actionLabel(log.getAction()), log.getTarget(),
                log.getDetail(), log.getIpAddress());
    }

    /** 操作の日本語名。列挙にない値（古いデータなど）はそのまま出す。 */
    static String actionLabel(String action) {
        for (OperationAction a : OperationAction.values()) {
            if (a.name().equals(action)) {
                return a.getLabel();
            }
        }
        return action;
    }

    /** 役割の表示名（10_基本設計書 1章・メニューの役割ラベルと同じ）。 */
    static String roleLabel(Role role) {
        return switch (role) {
            case ADMIN -> "管理者";
            case MANAGER -> "店長";
            case STAFF -> "バイト";
        };
    }
}

package jp.numajo.fgroup.shift.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.OperationLog;
import jp.numajo.fgroup.shift.repository.OperationLogRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * 操作ログの記録と検索。
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    /** detail の最大文字数 */
    static final int DETAIL_MAX = 500;

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
}

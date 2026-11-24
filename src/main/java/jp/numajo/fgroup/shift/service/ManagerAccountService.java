package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ManagerCountView;
import jp.numajo.fgroup.shift.dto.ManagerRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.ManagerForm;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.PasswordGenerator;
import lombok.RequiredArgsConstructor;

/**
 * 店長アカウントの管理（SA-01・SA-02）の業務処理。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerAccountService {

    private static final String TARGET_PREFIX = "user:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordGenerator passwordGenerator;
    private final OperationLogService operationLogService;
    private final Clock clock;

    /**
     * 店長の一覧を取る（20-SA-02 2.1）。並びは有効→停止、その中でふりがな順（null は最後）→ id。
     *
     * @return 一覧の行
     */
    public List<ManagerRow> findAll() {
        List<User> managers = userRepository.findByRoleForAdmin(Role.MANAGER);
        long activeCount = managers.stream().filter(User::isActive).count();
        LocalDateTime now = LocalDateTime.now(clock);

        return managers.stream()
                .map(user -> toRow(user, now, activeCount))
                .toList();
    }

    /**
     * 店長の有効・停止の人数を数える（20-SA-01 2.1-1）。
     *
     * @return 人数
     */
    public ManagerCountView count() {
        long active = userRepository.countByRoleAndActive(Role.MANAGER, true);
        long stopped = userRepository.countByRoleAndActive(Role.MANAGER, false);
        return new ManagerCountView(active, stopped);
    }

    /**
     * 店長の氏名を取る（停止の完了メッセージ I4204・初期パスワードの表示 I4203 の引数）。
     *
     * @param id ユーザーID
     * @return 氏名
     * @throws ResourceNotFoundException いない、または店長ではない
     */
    public String getName(Long id) {
        return findManager(id).getName();
    }

    /**
     * 店長アカウントを作る（20-SA-02 2.2）。
     *
     * @param form       入力
     * @param operatorId 操作した管理者のID
     * @return 初期パスワード（平文）
     * @throws BusinessException ログインIDが使われている（E4207）
     */
    @Transactional
    public String create(ManagerForm form, Long operatorId) {
        if (userRepository.existsByLoginId(form.getLoginId())) {
            throw new BusinessException("E4207");
        }

        String initialPassword = passwordGenerator.generate();
        User user = new User();
        user.setLoginId(form.getLoginId());
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setName(form.getName());
        user.setNameKana(blankToNull(form.getNameKana()));
        user.setRole(Role.MANAGER);
        user.setTrainee(false);
        user.setActive(true);
        user.setMustChangePassword(true);
        user.setFailedLoginCount(0);
        User saved = userRepository.save(user);

        operationLogService.record(operatorId, OperationAction.MANAGER_CREATE, TARGET_PREFIX + saved.getId(),
                "loginId=" + saved.getLoginId() + ", name=" + saved.getName());
        return initialPassword;
    }

    /**
     * 店長アカウントを停止する（20-SA-02 2.3）。ログイン中の人は UserActiveCheckFilter が次のリクエストでログアウトさせる。
     *
     * @param id         ユーザーID
     * @param operatorId 操作した管理者のID
     * @throws ResourceNotFoundException いない、または店長ではない
     * @throws BusinessException         すでに停止している（E4208）
     */
    @Transactional
    public void stop(Long id, Long operatorId) {
        User user = findManager(id);
        if (!user.isActive()) {
            throw new BusinessException("E4208");
        }
        user.setActive(false);
        operationLogService.record(operatorId, OperationAction.MANAGER_STOP, TARGET_PREFIX + id, null);
    }

    /**
     * 店長アカウントを再開する（20-SA-02 2.4）。
     *
     * @param id         ユーザーID
     * @param operatorId 操作した管理者のID
     * @throws ResourceNotFoundException いない、または店長ではない
     * @throws BusinessException         すでに有効（E4209）
     */
    @Transactional
    public void resume(Long id, Long operatorId) {
        User user = findManager(id);
        if (user.isActive()) {
            throw new BusinessException("E4209");
        }
        user.setActive(true);
        operationLogService.record(operatorId, OperationAction.MANAGER_RESUME, TARGET_PREFIX + id, null);
    }

    /**
     * パスワードを初期化する（20-SA-02 2.5）。ロック中の解除も兼ねる。
     *
     * @param id         ユーザーID
     * @param operatorId 操作した管理者のID
     * @return 新しい初期パスワード（平文）
     * @throws ResourceNotFoundException いない、または店長ではない
     */
    @Transactional
    public String resetPassword(Long id, Long operatorId) {
        User user = findManager(id);
        String newPassword = passwordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        operationLogService.record(operatorId, OperationAction.MANAGER_PASSWORD_RESET, TARGET_PREFIX + id, null);
        return newPassword;
    }

    /** 店長を取る。いない、または role が MANAGER でなければ 404。 */
    private User findManager(Long id) {
        return userRepository.findByIdAndRole(id, Role.MANAGER)
                .orElseThrow(() -> new ResourceNotFoundException("manager id=" + id));
    }

    /** ユーザーを一覧の1行に変換する。ロック中は「ロック解除日時が今より後」。 */
    private static ManagerRow toRow(User user, LocalDateTime now, long activeCount) {
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        LocalDateTime createdAt = user.getCreatedAt();
        String createdAtLabel = createdAt == null ? null : DateTimeUtil.fullDate(createdAt.toLocalDate());
        boolean lastActive = user.isActive() && activeCount == 1;

        return new ManagerRow(user.getId(), user.getName(), user.getLoginId(), user.isActive(), locked,
                createdAt, createdAtLabel, lastActive);
    }

    /** null・空文字・空白だけなら null、それ以外はそのまま返す（ふりがなは任意のため）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

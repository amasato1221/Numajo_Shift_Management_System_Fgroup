package jp.numajo.fgroup.shift.security;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * ログインの成功・失敗を記録し、5回続けて失敗したら15分ロックする。
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** ロックするまでの連続失敗回数 */
    public static final int MAX_FAILED_COUNT = 5;
    /** ロックする時間（分） */
    public static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;

    /** ログイン成功時に失敗回数とロックを解除する。 */
    @Transactional
    public void loginSucceeded(String loginId) {
        userRepository.findByLoginId(loginId).ifPresent(user -> {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        });
    }

    /**
     * パスワード誤りのときに失敗回数を1増やす。5回目でロックし、回数を0に戻す。
     *
     * @return ロックした場合は true
     */
    @Transactional
    public boolean loginFailed(String loginId, LocalDateTime now) {
        return userRepository.findByLoginId(loginId).map(user -> {
            int count = user.getFailedLoginCount() + 1;
            if (count >= MAX_FAILED_COUNT) {
                user.setFailedLoginCount(0);
                user.setLockedUntil(now.plusMinutes(LOCK_MINUTES));
                return true;
            }
            user.setFailedLoginCount(count);
            return false;
        }).orElse(false);
    }
}

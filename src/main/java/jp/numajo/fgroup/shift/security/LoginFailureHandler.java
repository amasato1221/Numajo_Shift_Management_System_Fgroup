package jp.numajo.fgroup.shift.security;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.service.OperationLogService;
import lombok.RequiredArgsConstructor;

/**
 * ログイン失敗時の処理。ロック中は /login?locked、それ以外は /login?error へ戻す。
 * 操作ログの LOGIN_LOCKED は、今回の失敗でロックしたときだけ残す（ロック中の試行は LOGIN_FAILURE）。
 */
@Component
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;
    private final OperationLogService operationLogService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String loginId = request.getParameter("loginId");
        boolean alreadyLocked = exception instanceof LockedException;
        boolean lockedNow = false;
        if (exception instanceof BadCredentialsException && loginId != null && !loginId.isEmpty()) {
            lockedNow = loginAttemptService.loginFailed(loginId, LocalDateTime.now(clock));
        }
        Long userId = loginId == null ? null : userRepository.findByLoginId(loginId).map(User::getId).orElse(null);
        operationLogService.recordInNewTransaction(userId,
                lockedNow ? OperationAction.LOGIN_LOCKED : OperationAction.LOGIN_FAILURE,
                "loginId:" + abbreviate(loginId), null);
        boolean locked = lockedNow || alreadyLocked;
        response.sendRedirect(request.getContextPath() + (locked ? "/login?locked" : "/login?error"));
    }

    /** 入力されたログインIDは長さが決まっていないため、50文字までにする。 */
    private static String abbreviate(String loginId) {
        if (loginId == null) {
            return "";
        }
        return loginId.length() <= 50 ? loginId : loginId.substring(0, 50);
    }
}

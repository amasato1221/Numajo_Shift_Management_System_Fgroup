package jp.numajo.fgroup.shift.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.service.OperationLogService;
import lombok.RequiredArgsConstructor;

/**
 * ログイン成功時の処理。初回はパスワード変更画面へ、それ以外は利用者の種類ごとのホームへ移動する。
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final LoginAttemptService loginAttemptService;
    private final OperationLogService operationLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        LoginUser user = (LoginUser) authentication.getPrincipal();
        loginAttemptService.loginSucceeded(user.getLoginId());
        operationLogService.recordInNewTransaction(user.getUserId(), OperationAction.LOGIN_SUCCESS, null, null);
        String path = user.isMustChangePassword() ? "/account/password" : HomeUrl.of(user.getRole());
        response.sendRedirect(request.getContextPath() + path);
    }
}

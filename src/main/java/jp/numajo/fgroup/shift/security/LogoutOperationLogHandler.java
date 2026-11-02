package jp.numajo.fgroup.shift.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.service.OperationLogService;
import lombok.RequiredArgsConstructor;

/**
 * ログアウトしたことを操作ログに記録する。
 */
@Component
@RequiredArgsConstructor
public class LogoutOperationLogHandler implements LogoutHandler {

    private final OperationLogService operationLogService;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            operationLogService.recordInNewTransaction(user.getUserId(), OperationAction.LOGOUT, null, null);
        }
    }
}

package jp.numajo.fgroup.shift.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 権限のない画面を開いたときに、自分のホーム画面へ戻す。
 */
public class HomeRedirectAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = (auth != null && auth.getPrincipal() instanceof LoginUser user)
                ? HomeUrl.of(user.getRole()) : "/login";
        response.sendRedirect(request.getContextPath() + path);
    }
}

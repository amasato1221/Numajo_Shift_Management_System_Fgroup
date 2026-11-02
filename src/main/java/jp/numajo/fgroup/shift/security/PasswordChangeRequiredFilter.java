package jp.numajo.fgroup.shift.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * パスワード変更が必要な利用者は、パスワード変更画面・ログアウト・静的ファイル以外を開けないようにする。
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (auth != null && auth.getPrincipal() instanceof LoginUser user && user.isMustChangePassword()
                && !isAllowed(path)) {
            response.sendRedirect(request.getContextPath() + "/account/password");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(String path) {
        return path.startsWith("/account/password") || path.equals("/logout")
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.startsWith("/webjars/") || path.equals("/error");
    }
}

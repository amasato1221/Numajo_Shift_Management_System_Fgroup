package jp.numajo.fgroup.shift.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * ログイン中に無効（停止）にされた利用者を、次のリクエストでログアウトさせる。
 * Bean にすると通常のフィルターとしても登録されて2回動くため、SecurityConfig で new して使う。
 */
public class UserActiveCheckFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public UserActiveCheckFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user
                && !userRepository.findActiveById(user.getUserId()).orElse(false)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendRedirect(request.getContextPath() + "/login?disabled");
            return;
        }
        chain.doFilter(request, response);
    }
}

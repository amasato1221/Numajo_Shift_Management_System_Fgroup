package jp.numajo.fgroup.shift.security;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * ログインIDから利用者を読み込む。
 */
@Service
@RequiredArgsConstructor
public class LoginUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        LocalDateTime now = LocalDateTime.now(clock);
        return userRepository.findByLoginId(loginId)
                .map(user -> new LoginUser(user, user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)))
                .orElseThrow(() -> new UsernameNotFoundException(loginId));
    }
}

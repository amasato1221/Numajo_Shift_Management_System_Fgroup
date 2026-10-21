# 30-04 security（ログイン・権限）

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-04 |
| 版 | 第1.0版（令和8年10月21日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |

このページのコードは**全文**です。そのまま写してください（30-00 1章）。動きの説明は 20-SC-01 と 94_セキュリティ設計書にあります。

## 0. 動作の確認

検証用のプロジェクトで次のテストがすべて通り、security パッケージの**命令網羅・分岐網羅がともに100%**であることを JaCoCo で確かめました（`LoginFlowTests` 9件、`SecurityUnitTests` 20件）。

| 確認したこと |
| --- |
| 初回ログインはパスワード変更画面へ。変更前はほかの画面を開けない |
| 権限のない画面は自分のホームへ。未ログインはログイン画面へ |
| 5回失敗でロック、ロック中は正しいパスワードでも入れない、ちょうど解除時刻になったら入れる |
| 存在しないID・無効なアカウントは「IDまたはパスワードが違います」 |
| ログイン成功・失敗・ロック・ログアウトが操作ログに残る |
| ログイン中に無効にされたら、次の操作で `/login?disabled` へ |
| パスワード変更が必要なときに開けるのは、SC-03・ログアウト・静的ファイル・エラー画面だけ |

## 0.1 フィルターの順番

```
… → UsernamePasswordAuthenticationFilter（ログイン） → … →
UserActiveCheckFilter（無効ならログアウト） → PasswordChangeRequiredFilter（初回の変更を強制） →
AuthorizationFilter（URLの権限） → コントローラー
```

`UserActiveCheckFilter` と `PasswordChangeRequiredFilter` は `@Component` にしてはいけません（Spring Boot が通常のフィルターとしても登録し、2回動くため）。`SecurityConfig` の中で `new` します。

## 1. 設定

### `SecurityConfig`

ファイル：`src/main/java/jp/numajo/fgroup/shift/config/SecurityConfig.java`

```java
package jp.numajo.fgroup.shift.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.HomeRedirectAccessDeniedHandler;
import jp.numajo.fgroup.shift.security.LoginFailureHandler;
import jp.numajo.fgroup.shift.security.LoginSuccessHandler;
import jp.numajo.fgroup.shift.security.LogoutOperationLogHandler;
import jp.numajo.fgroup.shift.security.PasswordChangeRequiredFilter;
import jp.numajo.fgroup.shift.security.UserActiveCheckFilter;

/**
 * Spring Security の設定（94_セキュリティ設計書）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginSuccessHandler successHandler,
            LoginFailureHandler failureHandler, LogoutOperationLogHandler logoutHandler,
            UserRepository userRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/webjars/**", "/error",
                        "/ical/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/manager/**").hasRole("MANAGER")
                .requestMatchers("/staff/**").hasRole("STAFF")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("loginId")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .addLogoutHandler(logoutHandler)
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.invalidSessionUrl("/login?expired"))
            .exceptionHandling(ex -> ex.accessDeniedHandler(new HomeRedirectAccessDeniedHandler()))
            .addFilterBefore(new UserActiveCheckFilter(userRepository), AuthorizationFilter.class)
            .addFilterAfter(new PasswordChangeRequiredFilter(), UserActiveCheckFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

## 2. ログイン中の利用者

### `LoginUser`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LoginUser.java`

```java
package jp.numajo.fgroup.shift.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import lombok.Getter;

/**
 * ログイン中の利用者の情報。セッションに保存されるため、エンティティそのものは持たない。
 */
@Getter
public class LoginUser implements UserDetails {

    private final Long userId;
    private final String loginId;
    private final String passwordHash;
    private final String name;
    private final Role role;
    private final boolean active;
    /** 読み込んだ時点でロック中だったか（LoginUserDetailsService が Clock で判定する） */
    private final boolean locked;
    private boolean mustChangePassword;

    public LoginUser(User user, boolean locked) {
        this.userId = user.getId();
        this.loginId = user.getLoginId();
        this.passwordHash = user.getPasswordHash();
        this.name = user.getName();
        this.role = user.getRole();
        this.active = user.isActive();
        this.locked = locked;
        this.mustChangePassword = user.isMustChangePassword();
    }

    /** パスワード変更後に、変更が必要な状態を解除する。 */
    public void passwordChanged() {
        this.mustChangePassword = false;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
```

### `LoginUserDetailsService`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LoginUserDetailsService.java`

```java
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
```

### `LoginAttemptService`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LoginAttemptService.java`

```java
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
```

## 3. ハンドラー

### `LoginSuccessHandler`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LoginSuccessHandler.java`

```java
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
```

### `LoginFailureHandler`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LoginFailureHandler.java`

```java
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
        boolean locked = exception instanceof LockedException;
        if (exception instanceof BadCredentialsException && loginId != null && !loginId.isEmpty()) {
            locked = loginAttemptService.loginFailed(loginId, LocalDateTime.now(clock));
        }
        Long userId = loginId == null ? null : userRepository.findByLoginId(loginId).map(User::getId).orElse(null);
        operationLogService.recordInNewTransaction(userId,
                locked ? OperationAction.LOGIN_LOCKED : OperationAction.LOGIN_FAILURE,
                "loginId:" + abbreviate(loginId), null);
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
```

### `LogoutOperationLogHandler`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/LogoutOperationLogHandler.java`

```java
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
```

### `HomeRedirectAccessDeniedHandler`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/HomeRedirectAccessDeniedHandler.java`

```java
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
```

### `HomeUrl`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/HomeUrl.java`

```java
package jp.numajo.fgroup.shift.security;

import jp.numajo.fgroup.shift.constant.Role;

/**
 * 利用者の種類ごとのホーム画面のURL。
 */
public final class HomeUrl {

    private HomeUrl() {
    }

    public static String of(Role role) {
        return switch (role) {
            case ADMIN -> "/admin/home";
            case MANAGER -> "/manager/home";
            case STAFF -> "/staff/home";
        };
    }
}
```

## 4. フィルター

### `PasswordChangeRequiredFilter`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/PasswordChangeRequiredFilter.java`

```java
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
```

### `UserActiveCheckFilter`

ファイル：`src/main/java/jp/numajo/fgroup/shift/security/UserActiveCheckFilter.java`

```java
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
```

## 5. 操作ログ（security から使う Service）

`OperationLogService` は service パッケージのクラスですが、ログインの処理から使うためここにも全文を載せます。検索のメソッド（`search`・`findRecent`）は 30-05 のとおり、このクラスに足します。

### `OperationLogService`

ファイル：`src/main/java/jp/numajo/fgroup/shift/service/OperationLogService.java`

```java
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
```


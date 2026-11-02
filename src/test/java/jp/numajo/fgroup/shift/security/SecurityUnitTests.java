package jp.numajo.fgroup.shift.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import jakarta.servlet.FilterChain;
import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.service.OperationLogService;

/**
 * security パッケージの単体テスト（97_テスト計画書 4.1、94_セキュリティ設計書 6章 No.5 など）。
 *
 * <p>
 * DB は使わず、Repository と OperationLogService は Mockito のモックにする。
 * 時刻は固定の Clock（2026-10-20 09:00 日本時間）を使う。
 * </p>
 *
 * <pre>
 * 組み合わせ表 1：ロック中かどうか（LoginUserDetailsService）
 * 判定：lockedUntil != null &amp;&amp; lockedUntil.isAfter(now)（A：null でない、B：now より後）
 * No. | A  | B  | 判定 | テストの値
 *  1  | 偽 | －  | 偽   | lockedUntil = null
 *  2  | 真 | 真  | 真   | lockedUntil = now + 1秒
 *  3  | 真 | 偽  | 偽   | lockedUntil = now ちょうど（解除時刻ちょうどは入れる）
 *  4  | 真 | 偽  | 偽   | lockedUntil = now - 1秒
 *
 * 組み合わせ表 2：ログイン失敗の記録（LoginFailureHandler）
 * 判定：exception instanceof BadCredentialsException &amp;&amp; loginId != null &amp;&amp; !loginId.isEmpty()
 *      （A：パスワード誤り、B：ID が null でない、C：ID が空でない）
 * No. | A  | B  | C  | 判定 | テストの値
 *  1  | 真 | 真 | 真 | 真   | BadCredentialsException、"alice"
 *  2  | 偽 | 真 | 真 | 偽   | LockedException、"alice"
 *  3  | 真 | 偽 | －  | 偽   | BadCredentialsException、ID なし
 *  4  | 真 | 真 | 偽 | 偽   | BadCredentialsException、""
 *
 * 組み合わせ表 3：初回パスワード変更の強制（PasswordChangeRequiredFilter）
 * 判定：auth != null &amp;&amp; principal instanceof LoginUser &amp;&amp; mustChangePassword &amp;&amp; !isAllowed(path)
 *      （A：ログイン情報あり、B：LoginUser、C：変更が必要、D：許可されていない画面）
 * No. | A  | B  | C  | D  | 判定 | テストの値
 *  1  | 真 | 真 | 真 | 真 | 真   | 変更が必要な人が /staff/home → /account/password へ
 *  2  | 偽 | －  | －  | －  | 偽   | ログイン情報なし
 *  3  | 真 | 偽 | －  | －  | 偽   | principal が文字列
 *  4  | 真 | 真 | 偽 | －  | 偽   | 変更済みの人
 *  5  | 真 | 真 | 真 | 偽 | 偽   | 許可された画面 8種類（パラメーター化）
 *
 * 組み合わせ表 4：無効にされた人（UserActiveCheckFilter。97 3.2 の例2）
 * 判定：auth != null &amp;&amp; principal instanceof LoginUser &amp;&amp; !active
 *      （A：ログイン情報あり、B：LoginUser、C：無効。C は findActiveById の結果が false または空）
 * No. | A  | B  | C  | 判定 | 結果
 *  1  | 真 | 真 | 真 | 真   | /login?disabled（セッション破棄）
 *  2  | 偽 | －  | －  | 偽   | 次へ進む
 *  3  | 真 | 偽 | －  | 偽   | 次へ進む
 *  4  | 真 | 真 | 偽 | 偽   | 次へ進む（有効）
 * </pre>
 */
class SecurityUnitTests {

    /** 固定する「今」：2026-10-20 09:00（日本時間） */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0, 0);

    private UserRepository userRepository;
    private OperationLogService operationLogService;
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        operationLogService = mock(OperationLogService.class);
        loginAttemptService = mock(LoginAttemptService.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 部品

    private static User user(long id, String loginId, Role role, boolean active, boolean mustChange) {
        User u = new User();
        u.setId(id);
        u.setLoginId(loginId);
        u.setPasswordHash("hash-" + loginId);
        u.setName("テスト" + loginId);
        u.setRole(role);
        u.setActive(active);
        u.setMustChangePassword(mustChange);
        return u;
    }

    private static LoginUser loginUser(Role role, boolean mustChange) {
        return new LoginUser(user(10L, "alice", role, true, mustChange), false);
    }

    private static void setAuthentication(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static Authentication token(LoginUser loginUser) {
        return new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
    }

    // ---------------------------------------------------------------- HomeUrl

    @ParameterizedTest(name = "{0} のホーム")
    @CsvSource({ "ADMIN,/admin/home", "MANAGER,/manager/home", "STAFF,/staff/home" })
    void homeUrl_利用者の種類ごとのホームを返す(Role role, String expected) {
        assertThat(HomeUrl.of(role)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------- LoginUser

    @ParameterizedTest(name = "{0} の権限名")
    @EnumSource(Role.class)
    void loginUser_権限名はROLE_と種類名(Role role) {
        LoginUser loginUser = loginUser(role, false);

        assertThat(loginUser.getAuthorities()).extracting("authority").containsExactly("ROLE_" + role.name());
    }

    @Test
    void loginUser_ユーザーの値を写す() {
        LoginUser loginUser = new LoginUser(user(7L, "bob", Role.STAFF, true, true), false);

        assertThat(loginUser.getUserId()).isEqualTo(7L);
        assertThat(loginUser.getUsername()).isEqualTo("bob");
        assertThat(loginUser.getLoginId()).isEqualTo("bob");
        assertThat(loginUser.getPassword()).isEqualTo("hash-bob");
        assertThat(loginUser.getName()).isEqualTo("テストbob");
        assertThat(loginUser.getRole()).isEqualTo(Role.STAFF);
        assertThat(loginUser.isMustChangePassword()).isTrue();
    }

    @Test
    void loginUser_ロック中ならisAccountNonLockedが偽() {
        assertThat(new LoginUser(user(1L, "a", Role.STAFF, true, false), true).isAccountNonLocked()).isFalse();
        assertThat(new LoginUser(user(1L, "a", Role.STAFF, true, false), false).isAccountNonLocked()).isTrue();
    }

    @Test
    void loginUser_無効ならisEnabledが偽() {
        assertThat(new LoginUser(user(1L, "a", Role.STAFF, false, false), false).isEnabled()).isFalse();
        assertThat(new LoginUser(user(1L, "a", Role.STAFF, true, false), false).isEnabled()).isTrue();
    }

    @Test
    void loginUser_passwordChangedで変更が必要な状態を解除する() {
        LoginUser loginUser = loginUser(Role.STAFF, true);

        loginUser.passwordChanged();

        assertThat(loginUser.isMustChangePassword()).isFalse();
    }

    // ---------------------------------------------------------------- LoginUserDetailsService

    private LoginUserDetailsService detailsService() {
        return new LoginUserDetailsService(userRepository, CLOCK);
    }

    private LoginUser load(LocalDateTime lockedUntil) {
        User u = user(1L, "alice", Role.STAFF, true, false);
        u.setLockedUntil(lockedUntil);
        when(userRepository.findByLoginId("alice")).thenReturn(Optional.of(u));
        return (LoginUser) detailsService().loadUserByUsername("alice");
    }

    @Test
    void 読み込み_存在しないIDはUsernameNotFoundException() {
        assertThatThrownBy(() -> detailsService().loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("nobody");
    }

    @Test
    void 読み込み_No1_ロック時刻がnullなら入れる() {
        assertThat(load(null).isAccountNonLocked()).isTrue();
    }

    @Test
    void 読み込み_No2_ロック解除がこれからならロック中() {
        assertThat(load(NOW.plusSeconds(1)).isAccountNonLocked()).isFalse();
    }

    @Test
    void 読み込み_No3_解除時刻ちょうどは入れる() {
        assertThat(load(NOW).isAccountNonLocked()).isTrue();
    }

    @Test
    void 読み込み_No4_解除時刻を過ぎたら入れる() {
        assertThat(load(NOW.minusSeconds(1)).isAccountNonLocked()).isTrue();
    }

    // ---------------------------------------------------------------- LoginAttemptService

    private LoginAttemptService attemptService() {
        return new LoginAttemptService(userRepository);
    }

    @Test
    void ログイン成功_失敗回数とロックを解除する() {
        User u = user(1L, "alice", Role.STAFF, true, false);
        u.setFailedLoginCount(3);
        u.setLockedUntil(NOW.plusMinutes(5));
        when(userRepository.findByLoginId("alice")).thenReturn(Optional.of(u));

        attemptService().loginSucceeded("alice");

        assertThat(u.getFailedLoginCount()).isZero();
        assertThat(u.getLockedUntil()).isNull();
    }

    @Test
    void ログイン成功_存在しないIDでも例外にならない() {
        attemptService().loginSucceeded("nobody");

        verify(userRepository).findByLoginId("nobody");
    }

    @ParameterizedTest(name = "失敗{0}回 → {1}回、ロックしない")
    @CsvSource({ "0,1", "3,4" })
    void ログイン失敗_5回未満は回数を増やすだけ(int before, int after) {
        User u = user(1L, "alice", Role.STAFF, true, false);
        u.setFailedLoginCount(before);
        when(userRepository.findByLoginId("alice")).thenReturn(Optional.of(u));

        boolean locked = attemptService().loginFailed("alice", NOW);

        assertThat(locked).isFalse();
        assertThat(u.getFailedLoginCount()).isEqualTo(after);
        assertThat(u.getLockedUntil()).isNull();
    }

    @Test
    void ログイン失敗_5回目で15分ロックして回数を0に戻す() {
        User u = user(1L, "alice", Role.STAFF, true, false);
        u.setFailedLoginCount(4);
        when(userRepository.findByLoginId("alice")).thenReturn(Optional.of(u));

        boolean locked = attemptService().loginFailed("alice", NOW);

        assertThat(locked).isTrue();
        assertThat(u.getFailedLoginCount()).isZero();
        assertThat(u.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void ログイン失敗_存在しないIDはfalse() {
        assertThat(attemptService().loginFailed("nobody", NOW)).isFalse();
    }

    // ---------------------------------------------------------------- LoginSuccessHandler

    private LoginSuccessHandler successHandler() {
        return new LoginSuccessHandler(loginAttemptService, operationLogService);
    }

    @Test
    void 成功ハンドラー_初回ログインはパスワード変更画面へ() throws Exception {
        LoginUser loginUser = loginUser(Role.STAFF, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler().onAuthenticationSuccess(new MockHttpServletRequest(), response, token(loginUser));

        assertThat(response.getRedirectedUrl()).isEqualTo("/account/password");
    }

    @ParameterizedTest(name = "{0} は {1} へ")
    @CsvSource({ "ADMIN,/admin/home", "MANAGER,/manager/home", "STAFF,/staff/home" })
    void 成功ハンドラー_通常は種類ごとのホームへ(Role role, String home) throws Exception {
        LoginUser loginUser = loginUser(role, false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler().onAuthenticationSuccess(new MockHttpServletRequest(), response, token(loginUser));

        assertThat(response.getRedirectedUrl()).isEqualTo(home);
    }

    @Test
    void 成功ハンドラー_失敗回数を戻し操作ログを残しコンテキストパスを付ける() throws Exception {
        LoginUser loginUser = loginUser(Role.MANAGER, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler().onAuthenticationSuccess(request, response, token(loginUser));

        verify(loginAttemptService).loginSucceeded("alice");
        verify(operationLogService).recordInNewTransaction(10L, OperationAction.LOGIN_SUCCESS, null, null);
        assertThat(response.getRedirectedUrl()).isEqualTo("/app/manager/home");
    }

    // ---------------------------------------------------------------- LoginFailureHandler

    private LoginFailureHandler failureHandler() {
        return new LoginFailureHandler(loginAttemptService, operationLogService, userRepository, CLOCK);
    }

    private static MockHttpServletRequest loginRequest(String loginId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        if (loginId != null) {
            request.setParameter("loginId", loginId);
        }
        return request;
    }

    @Test
    void 失敗ハンドラー_No1_パスワード誤りは回数を数えてerrorへ() throws Exception {
        when(userRepository.findByLoginId("alice"))
                .thenReturn(Optional.of(user(10L, "alice", Role.STAFF, true, false)));
        when(loginAttemptService.loginFailed("alice", NOW)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest("alice"), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(operationLogService).recordInNewTransaction(10L, OperationAction.LOGIN_FAILURE, "loginId:alice", null);
    }

    @Test
    void 失敗ハンドラー_No1_今回の失敗でロックしたらlockedへ_LOGIN_LOCKEDを残す() throws Exception {
        when(userRepository.findByLoginId("alice"))
                .thenReturn(Optional.of(user(10L, "alice", Role.STAFF, true, false)));
        when(loginAttemptService.loginFailed("alice", NOW)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest("alice"), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?locked");
        verify(operationLogService).recordInNewTransaction(10L, OperationAction.LOGIN_LOCKED, "loginId:alice", null);
    }

    @Test
    void 失敗ハンドラー_No2_ロック中の試行は回数を数えずLOGIN_FAILUREで残す() throws Exception {
        when(userRepository.findByLoginId("alice"))
                .thenReturn(Optional.of(user(10L, "alice", Role.STAFF, true, false)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest("alice"), response, new LockedException("locked"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?locked");
        verify(loginAttemptService, never()).loginFailed(anyString(), any());
        verify(operationLogService).recordInNewTransaction(10L, OperationAction.LOGIN_FAILURE, "loginId:alice", null);
    }

    @Test
    void 失敗ハンドラー_No3_IDなしは回数を数えずユーザー検索もしない() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest(null), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(loginAttemptService, never()).loginFailed(anyString(), any());
        verifyNoInteractions(userRepository);
        verify(operationLogService).recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, "loginId:", null);
    }

    @Test
    void 失敗ハンドラー_No4_IDが空文字は回数を数えない() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest(""), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(loginAttemptService, never()).loginFailed(anyString(), any());
        verify(operationLogService).recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, "loginId:", null);
    }

    @Test
    void 失敗ハンドラー_無効なアカウントはerrorへ回数は数えない() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest("alice"), response, new DisabledException("off"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(loginAttemptService, never()).loginFailed(anyString(), any());
    }

    @Test
    void 失敗ハンドラー_存在しないIDは利用者IDがnullで残る() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(loginRequest("nobody"), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verify(operationLogService).recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, "loginId:nobody", null);
    }

    @Test
    void 失敗ハンドラー_IDは50文字までに切る() throws Exception {
        String id50 = "a".repeat(50);
        String id51 = "b".repeat(51);

        failureHandler().onAuthenticationFailure(loginRequest(id50), new MockHttpServletResponse(),
                new BadCredentialsException("x"));
        failureHandler().onAuthenticationFailure(loginRequest(id51), new MockHttpServletResponse(),
                new BadCredentialsException("x"));

        verify(operationLogService).recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, "loginId:" + id50,
                null);
        verify(operationLogService).recordInNewTransaction(null, OperationAction.LOGIN_FAILURE,
                "loginId:" + "b".repeat(50), null);
    }

    @Test
    void 失敗ハンドラー_コンテキストパスを付ける() throws Exception {
        MockHttpServletRequest request = loginRequest("alice");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler().onAuthenticationFailure(request, response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/app/login?error");
    }

    // ---------------------------------------------------------------- LogoutOperationLogHandler

    @Test
    void ログアウト_LoginUserなら操作ログを残す() {
        new LogoutOperationLogHandler(operationLogService).logout(new MockHttpServletRequest(),
                new MockHttpServletResponse(), token(loginUser(Role.STAFF, false)));

        verify(operationLogService).recordInNewTransaction(10L, OperationAction.LOGOUT, null, null);
    }

    @Test
    void ログアウト_ログイン情報がなければ何もしない() {
        new LogoutOperationLogHandler(operationLogService).logout(new MockHttpServletRequest(),
                new MockHttpServletResponse(), null);

        verifyNoInteractions(operationLogService);
    }

    @Test
    void ログアウト_LoginUser以外なら何もしない() {
        Authentication other = new UsernamePasswordAuthenticationToken("someone", null, List.of());

        new LogoutOperationLogHandler(operationLogService).logout(new MockHttpServletRequest(),
                new MockHttpServletResponse(), other);

        verifyNoInteractions(operationLogService);
    }

    // ---------------------------------------------------------------- HomeRedirectAccessDeniedHandler

    @ParameterizedTest(name = "{0} は {1} へ戻す")
    @CsvSource({ "ADMIN,/admin/home", "MANAGER,/manager/home", "STAFF,/staff/home" })
    void 権限なし_自分のホームへ戻す(Role role, String home) throws Exception {
        setAuthentication(loginUser(role, false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HomeRedirectAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo(home);
    }

    @Test
    void 権限なし_ログイン情報がなければログイン画面へ() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HomeRedirectAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void 権限なし_LoginUser以外ならログイン画面へ() throws Exception {
        setAuthentication("someone");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HomeRedirectAccessDeniedHandler().handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/app/login");
    }

    // ---------------------------------------------------------------- PasswordChangeRequiredFilter

    private MockHttpServletResponse runPasswordFilter(String path, String contextPath, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + path);
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PasswordChangeRequiredFilter().doFilter(request, response, chain);
        return response;
    }

    @Test
    void 変更強制_No1_変更が必要な人は他の画面からパスワード変更へ() throws Exception {
        setAuthentication(loginUser(Role.STAFF, true));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter("/staff/home", "", chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/account/password");
        verifyNoInteractions(chain);
    }

    @Test
    void 変更強制_No1_コンテキストパスを外して判定し付けて戻す() throws Exception {
        setAuthentication(loginUser(Role.STAFF, true));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter("/staff/home", "/app", chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/app/account/password");
        verifyNoInteractions(chain);
    }

    @Test
    void 変更強制_No2_ログイン情報がなければ通す() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter("/staff/home", "", chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void 変更強制_No3_LoginUser以外なら通す() throws Exception {
        setAuthentication("someone");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter("/staff/home", "", chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void 変更強制_No4_変更済みの人は通す() throws Exception {
        setAuthentication(loginUser(Role.STAFF, false));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter("/staff/home", "", chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest(name = "No.5 {0} は変更が必要でも開ける")
    @CsvSource({ "/account/password", "/logout", "/css/app.css", "/js/app.js", "/images/logo.png",
            "/webjars/bootstrap/5/css/bootstrap.min.css", "/error", "/account/password/" })
    void 変更強制_No5_許可された画面は通す(String path) throws Exception {
        setAuthentication(loginUser(Role.STAFF, true));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runPasswordFilter(path, "", chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
    }

    // ---------------------------------------------------------------- UserActiveCheckFilter

    private MockHttpServletResponse runActiveFilter(MockHttpServletRequest request, FilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new UserActiveCheckFilter(userRepository).doFilter(request, response, chain);
        return response;
    }

    @Test
    void 無効チェック_No1_無効にされたらセッションを破棄してdisabledへ() throws Exception {
        setAuthentication(loginUser(Role.STAFF, false));
        when(userRepository.findActiveById(10L)).thenReturn(Optional.of(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/staff/home");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runActiveFilter(request, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?disabled");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void 無効チェック_No1_利用者が見つからない場合も無効として扱う() throws Exception {
        setAuthentication(loginUser(Role.STAFF, false));
        when(userRepository.findActiveById(10L)).thenReturn(Optional.empty());
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runActiveFilter(new MockHttpServletRequest("GET", "/staff/home"), chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?disabled");
        verifyNoInteractions(chain);
    }

    @Test
    void 無効チェック_No1_セッションがなくても例外にならずコンテキストパスを付ける() throws Exception {
        setAuthentication(loginUser(Role.STAFF, false));
        when(userRepository.findActiveById(10L)).thenReturn(Optional.of(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/staff/home");
        request.setContextPath("/app");

        MockHttpServletResponse response = runActiveFilter(request, mock(FilterChain.class));

        assertThat(response.getRedirectedUrl()).isEqualTo("/app/login?disabled");
    }

    @Test
    void 無効チェック_No2_ログイン情報がなければ通す() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runActiveFilter(new MockHttpServletRequest("GET", "/login"), chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void 無効チェック_No3_LoginUser以外なら通す() throws Exception {
        setAuthentication("someone");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runActiveFilter(new MockHttpServletRequest("GET", "/staff/home"), chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void 無効チェック_No4_有効なら通す() throws Exception {
        setAuthentication(loginUser(Role.STAFF, false));
        when(userRepository.findActiveById(10L)).thenReturn(Optional.of(true));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = runActiveFilter(new MockHttpServletRequest("GET", "/staff/home"), chain);

        assertThat(response.getRedirectedUrl()).isNull();
        verify(chain).doFilter(any(), any());
    }
}

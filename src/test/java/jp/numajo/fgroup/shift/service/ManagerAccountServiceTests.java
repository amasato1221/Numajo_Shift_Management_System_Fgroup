package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ManagerCountView;
import jp.numajo.fgroup.shift.dto.ManagerRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.ManagerForm;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.PasswordGenerator;

/**
 * ManagerAccountService（SA-01・SA-02：店長アカウント管理）のテスト。
 *
 * <table>
 * <caption>ロック中の判定（lockedUntil と今の比べ方）</caption>
 * <tr><th>lockedUntil</th><th>ロック中</th></tr>
 * <tr><td>null</td><td>いいえ</td></tr>
 * <tr><td>今より前</td><td>いいえ</td></tr>
 * <tr><td>今と同じ</td><td>いいえ</td></tr>
 * <tr><td>今より後</td><td>はい</td></tr>
 * </table>
 *
 * <table>
 * <caption>最後の有効な店長か（lastActive ＝ 有効 かつ 有効な人数が1）の複数条件</caption>
 * <tr><th>この人が有効</th><th>有効な人数が1</th><th>lastActive</th></tr>
 * <tr><td>はい</td><td>はい</td><td>true</td></tr>
 * <tr><td>はい</td><td>いいえ（2人）</td><td>false</td></tr>
 * <tr><td>いいえ</td><td>はい</td><td>false</td></tr>
 * <tr><td>いいえ</td><td>いいえ（0人）</td><td>false</td></tr>
 * </table>
 *
 * <table>
 * <caption>ふりがなの保存（null または空白だけなら null）の条件</caption>
 * <tr><th>入力</th><th>null か</th><th>空白だけか</th><th>保存する値</th></tr>
 * <tr><td>null</td><td>はい</td><td>－</td><td>null</td></tr>
 * <tr><td>空文字</td><td>いいえ</td><td>はい</td><td>null</td></tr>
 * <tr><td>全角・半角の空白</td><td>いいえ</td><td>はい</td><td>null</td></tr>
 * <tr><td>やまだ</td><td>いいえ</td><td>いいえ</td><td>やまだ</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class ManagerAccountServiceTests {

    /** テストの「今」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final Long ADMIN_ID = 1L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PasswordGenerator passwordGenerator;
    @Mock
    private OperationLogService operationLogService;

    private ManagerAccountService service;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new ManagerAccountService(userRepository, passwordEncoder, passwordGenerator, operationLogService,
                clock);
    }

    private static User manager(Long id, boolean active, LocalDateTime lockedUntil) {
        User user = new User();
        user.setId(id);
        user.setName("店長" + id);
        user.setNameKana("てんちょう" + id);
        user.setLoginId("tencho" + id);
        user.setRole(Role.MANAGER);
        user.setActive(active);
        user.setLockedUntil(lockedUntil);
        user.setCreatedAt(LocalDateTime.of(2026, 10, 21, 9, 5));
        return user;
    }

    private static ManagerForm form(String nameKana) {
        ManagerForm form = new ManagerForm();
        form.setName("山田 店長");
        form.setNameKana(nameKana);
        form.setLoginId("Yamada");
        return form;
    }

    // ---------------------------------------------------------------- findAll

    @Test
    void findAll_0人なら空の一覧() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER)).thenReturn(List.of());

        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void findAll_Repositoryの並びのまま各項目を行に入れる() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER))
                .thenReturn(List.of(manager(3L, true, null), manager(2L, false, null)));

        List<ManagerRow> rows = service.findAll();

        assertThat(rows).extracting(ManagerRow::id).containsExactly(3L, 2L);
        ManagerRow first = rows.get(0);
        assertThat(first.name()).isEqualTo("店長3");
        assertThat(first.loginId()).isEqualTo("tencho3");
        assertThat(first.active()).isTrue();
        assertThat(first.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 5));
        assertThat(first.createdAtLabel()).isEqualTo("2026年10月21日");
        assertThat(rows.get(1).active()).isFalse();
    }

    @Test
    void findAll_作成日時がnullなら表示もnull() {
        User user = manager(1L, true, null);
        user.setCreatedAt(null);
        when(userRepository.findByRoleForAdmin(Role.MANAGER)).thenReturn(List.of(user));

        assertThat(service.findAll().get(0).createdAtLabel()).isNull();
    }

    @Test
    void findAll_ロック中の判定はロック解除日時が今より後のときだけ() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER)).thenReturn(List.of(
                manager(1L, true, null),
                manager(2L, true, NOW.minusMinutes(1)),
                manager(3L, true, NOW),
                manager(4L, true, NOW.plusMinutes(1))));

        assertThat(service.findAll()).extracting(ManagerRow::locked).containsExactly(false, false, false, true);
    }

    @Test
    void findAll_有効が1人だけならその人がlastActive_停止の人はfalse() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER))
                .thenReturn(List.of(manager(1L, true, null), manager(2L, false, null)));

        assertThat(service.findAll()).extracting(ManagerRow::lastActive).containsExactly(true, false);
    }

    @Test
    void findAll_有効が2人ならどちらもlastActiveではない() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER))
                .thenReturn(List.of(manager(1L, true, null), manager(2L, true, null)));

        assertThat(service.findAll()).extracting(ManagerRow::lastActive).containsExactly(false, false);
    }

    @Test
    void findAll_有効が0人なら停止の人はlastActiveではない() {
        when(userRepository.findByRoleForAdmin(Role.MANAGER)).thenReturn(List.of(manager(1L, false, null)));

        assertThat(service.findAll()).extracting(ManagerRow::lastActive).containsExactly(false);
    }

    // ---------------------------------------------------------------- count

    @Test
    void count_有効と停止の人数を返す() {
        when(userRepository.countByRoleAndActive(Role.MANAGER, true)).thenReturn(2L);
        when(userRepository.countByRoleAndActive(Role.MANAGER, false)).thenReturn(1L);

        assertThat(service.count()).isEqualTo(new ManagerCountView(2L, 1L));
    }

    // ---------------------------------------------------------------- getName

    @Test
    void getName_店長なら氏名を返す() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(manager(5L, true, null)));

        assertThat(service.getName(5L)).isEqualTo("店長5");
    }

    @Test
    void getName_店長でなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getName(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_ログインIDが使われていればE4207で何も保存しない() {
        when(userRepository.existsByLoginId("Yamada")).thenReturn(true);

        assertThatThrownBy(() -> service.create(form("やまだ"), ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("E4207"));
        verify(userRepository, never()).save(any());
        verify(operationLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void create_店長として保存し初期パスワードを返し操作ログを残す() {
        when(userRepository.existsByLoginId("Yamada")).thenReturn(false);
        when(passwordGenerator.generate()).thenReturn("Abcd234567");
        when(passwordEncoder.encode("Abcd234567")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });

        String password = service.create(form("やまだ てんちょう"), ADMIN_ID);

        assertThat(password).isEqualTo("Abcd234567");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(Role.MANAGER);
        assertThat(saved.getLoginId()).isEqualTo("Yamada");
        assertThat(saved.getName()).isEqualTo("山田 店長");
        assertThat(saved.getNameKana()).isEqualTo("やまだ てんちょう");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isTrainee()).isFalse();
        assertThat(saved.getFailedLoginCount()).isZero();
        verify(operationLogService).record(ADMIN_ID, OperationAction.MANAGER_CREATE, "user:10",
                "loginId=Yamada, name=山田 店長");
    }

    @Test
    void create_ふりがながnullならnullで保存() {
        assertThat(savedNameKana(null)).isNull();
    }

    @Test
    void create_ふりがなが空文字ならnullで保存() {
        assertThat(savedNameKana("")).isNull();
    }

    @Test
    void create_ふりがなが空白だけならnullで保存() {
        assertThat(savedNameKana(" 　")).isNull();
    }

    /** ふりがなを指定して作成し、保存した値を返す。 */
    private String savedNameKana(String nameKana) {
        when(passwordGenerator.generate()).thenReturn("Abcd234567");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(form(nameKana), ADMIN_ID);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue().getNameKana();
    }

    // ---------------------------------------------------------------- stop

    @Test
    void stop_有効な店長を停止し操作ログを残す() {
        User user = manager(5L, true, null);
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(user));

        service.stop(5L, ADMIN_ID);

        assertThat(user.isActive()).isFalse();
        verify(operationLogService).record(ADMIN_ID, OperationAction.MANAGER_STOP, "user:5", null);
    }

    @Test
    void stop_すでに停止中ならE4208で操作ログを残さない() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(manager(5L, false, null)));

        assertThatThrownBy(() -> service.stop(5L, ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("E4208"));
        verify(operationLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void stop_店長でなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stop(5L, ADMIN_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- resume

    @Test
    void resume_停止中の店長を再開し操作ログを残す() {
        User user = manager(5L, false, null);
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(user));

        service.resume(5L, ADMIN_ID);

        assertThat(user.isActive()).isTrue();
        verify(operationLogService).record(ADMIN_ID, OperationAction.MANAGER_RESUME, "user:5", null);
    }

    @Test
    void resume_すでに有効ならE4209で操作ログを残さない() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(manager(5L, true, null)));

        assertThatThrownBy(() -> service.resume(5L, ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("E4209"));
        verify(operationLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void resume_店長でなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume(5L, ADMIN_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- resetPassword

    @Test
    void resetPassword_新しいパスワードにしてロックを解除し操作ログを残す() {
        User user = manager(5L, true, NOW.plusMinutes(10));
        user.setFailedLoginCount(5);
        user.setMustChangePassword(false);
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.of(user));
        when(passwordGenerator.generate()).thenReturn("Newp234567");
        when(passwordEncoder.encode("Newp234567")).thenReturn("new-hash");

        String password = service.resetPassword(5L, ADMIN_ID);

        assertThat(password).isEqualTo("Newp234567");
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(operationLogService).record(eq(ADMIN_ID), eq(OperationAction.MANAGER_PASSWORD_RESET), eq("user:5"),
                isNull());
    }

    @Test
    void resetPassword_店長でなければ404でパスワードを作らない() {
        when(userRepository.findByIdAndRole(5L, Role.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(5L, ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(passwordEncoder, never()).encode(anyString());
    }
}

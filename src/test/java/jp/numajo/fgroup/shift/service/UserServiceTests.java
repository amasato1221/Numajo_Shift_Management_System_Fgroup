package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * UserService#changePassword の単体テスト（20-SC-03 2.2・6章 No.4〜7、97_テスト計画書 4.1）。
 *
 * <p>
 * 判定はすべて条件が1つなので、組み合わせ表は要らない（条件網羅＝判定条件網羅）。
 * パスワードの照合には、本物の BCryptPasswordEncoder を使う。
 * </p>
 *
 * <pre>
 * 手順（20-SC-03 2.2）の判定        | 真のとき          | テスト
 * 1. new != confirm                 | E0305             | 確認が違う
 * 2. ユーザーがいない                | ResourceNotFound  | 見つからない
 * 3. current が合わない              | E0306             | 今のパスワードが違う
 * 4. new が今と同じ                  | E0307             | 今と同じ
 * 5. すべて偽                       | 変更・ログ         | 正しく変更
 * </pre>
 */
class UserServiceTests {

    private static final Long USER_ID = 7L;
    private static final String CURRENT = "CurrentPass1!";
    private static final String NEW = "NewPassw0rd!";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private UserRepository userRepository;
    private OperationLogService operationLogService;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        operationLogService = mock(OperationLogService.class);
        service = new UserService(userRepository, encoder, operationLogService);
        user = new User();
        user.setId(USER_ID);
        user.setLoginId("alice");
        user.setRole(Role.STAFF);
        user.setPasswordHash(encoder.encode(CURRENT));
        user.setMustChangePassword(true);
    }

    @Test
    void 確認用が一致しない_E0305になりユーザーを検索しない() {
        assertThatThrownBy(() -> service.changePassword(USER_ID, CURRENT, NEW, NEW + "x"))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E0305");

        verifyNoInteractions(userRepository, operationLogService);
    }

    @Test
    void ユーザーが存在しない_ResourceNotFoundExceptionになる() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(USER_ID, CURRENT, NEW, NEW))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(operationLogService);
    }

    @Test
    void 今のパスワードが違う_E0306になりDBは変わらない() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        String before = user.getPasswordHash();

        assertThatThrownBy(() -> service.changePassword(USER_ID, "wrong-password", NEW, NEW))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E0306");

        assertThat(user.getPasswordHash()).isEqualTo(before);
        assertThat(user.isMustChangePassword()).isTrue();
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 新しいパスワードが今と同じ_E0307になりDBは変わらない() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        String before = user.getPasswordHash();

        assertThatThrownBy(() -> service.changePassword(USER_ID, CURRENT, CURRENT, CURRENT))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E0307");

        assertThat(user.getPasswordHash()).isEqualTo(before);
        verify(operationLogService, never()).record(USER_ID, OperationAction.PASSWORD_CHANGE, null, null);
    }

    @Test
    void 正しい入力_ハッシュが変わり変更必須が外れて操作ログが残る() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.changePassword(USER_ID, CURRENT, NEW, NEW);

        assertThat(encoder.matches(NEW, user.getPasswordHash())).isTrue();
        assertThat(encoder.matches(CURRENT, user.getPasswordHash())).isFalse();
        assertThat(user.getPasswordHash()).isNotEqualTo(NEW);
        assertThat(user.isMustChangePassword()).isFalse();
        verify(operationLogService).record(USER_ID, OperationAction.PASSWORD_CHANGE, null, null);
    }
}

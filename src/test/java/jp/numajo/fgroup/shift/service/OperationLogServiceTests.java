package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.OperationLog;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.OperationLogRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * OperationLogService の単体テスト（97_テスト計画書 2.3、30-04）。
 *
 * <pre>
 * 判定 1：detail == null || detail.length() &lt;= 500（A：null、B：500文字以下）
 * No. | A  | B  | 判定 | テストの値
 *  1  | 真 | －  | 真   | null → そのまま
 *  2  | 偽 | 真  | 真   | 500文字 → そのまま
 *  3  | 偽 | 偽  | 偽   | 501文字 → 497文字＋「...」
 *
 * 判定 2：userId == null（真：user を null、偽：getReferenceById で取る）
 * 判定 3：リクエスト中か（真：IPアドレスを入れる、偽：null）
 * </pre>
 */
class OperationLogServiceTests {

    private OperationLogRepository operationLogRepository;
    private UserRepository userRepository;
    private OperationLogService service;

    @BeforeEach
    void setUp() {
        operationLogRepository = mock(OperationLogRepository.class);
        userRepository = mock(UserRepository.class);
        service = new OperationLogService(operationLogRepository, userRepository);
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private OperationLog savedLog() {
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- truncate

    @Test
    void 切り詰め_No1_nullはそのまま() {
        assertThat(OperationLogService.truncate(null)).isNull();
    }

    @Test
    void 切り詰め_No2_500文字はそのまま() {
        String detail = "a".repeat(500);

        assertThat(OperationLogService.truncate(detail)).isEqualTo(detail);
    }

    @Test
    void 切り詰め_No3_501文字は497文字と省略記号にする() {
        String result = OperationLogService.truncate("a".repeat(501));

        assertThat(result).hasSize(500);
        assertThat(result).isEqualTo("a".repeat(497) + "...");
    }

    // ---------------------------------------------------------------- record

    @Test
    void 記録_利用者IDありなら参照を取って保存する() {
        User ref = new User();
        ref.setId(5L);
        when(userRepository.getReferenceById(5L)).thenReturn(ref);

        service.record(5L, OperationAction.PASSWORD_CHANGE, "対象", "詳細");

        OperationLog log = savedLog();
        assertThat(log.getUser()).isSameAs(ref);
        assertThat(log.getAction()).isEqualTo("PASSWORD_CHANGE");
        assertThat(log.getTarget()).isEqualTo("対象");
        assertThat(log.getDetail()).isEqualTo("詳細");
    }

    @Test
    void 記録_利用者IDなしならuserはnullで参照を取らない() {
        service.recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, "loginId:x", null);

        OperationLog log = savedLog();
        assertThat(log.getUser()).isNull();
        assertThat(log.getAction()).isEqualTo("LOGIN_FAILURE");
        assertThat(log.getDetail()).isNull();
        verify(userRepository, never()).getReferenceById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 記録_detailが長ければ切り詰めて保存する() {
        service.recordInNewTransaction(null, OperationAction.LOGIN_FAILURE, null, "x".repeat(600));

        assertThat(savedLog().getDetail()).hasSize(500).endsWith("...");
    }

    @Test
    void 記録_リクエスト中なら接続元のIPアドレスを入れる() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.recordInNewTransaction(null, OperationAction.LOGOUT, null, null);

        assertThat(savedLog().getIpAddress()).isEqualTo("192.0.2.10");
    }

    @Test
    void 記録_リクエストがなければIPアドレスはnull() {
        service.recordInNewTransaction(null, OperationAction.LOGOUT, null, null);

        assertThat(savedLog().getIpAddress()).isNull();
    }
}

package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.entity.OperationLog;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.form.LogSearchForm;
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
 *
 * 以下は SA-03 の検索・SA-01 の最近の操作（20-SA-03 2.1、30-05 4.2）。いずれも条件が1つの判定なので、
 * 判定条件網羅＝条件網羅＝複数条件網羅になる。
 * 判定 4：form.getAction() == null（真：null を渡す、偽：name() の文字列を渡す）
 * 判定 5：log.getUser() == null（真：「－」、偽：氏名）
 * 判定 6：actionLabel のループ a.name().equals(action)（真：日本語名を返す、最後まで偽：そのままの文字列を返す）
 * 判定 7：roleLabel の switch（ADMIN：管理者、MANAGER：店長、STAFF：バイト）
 * 判定 8：findRecent の limit（limit ≥ 件数：全件、limit &lt; 件数：limit 件まで）
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

    // ---------------------------------------------------------------- search（SA-03）

    private static User user(Long id, String name, String loginId, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setLoginId(loginId);
        u.setRole(role);
        return u;
    }

    private static OperationLog log(Long id, User user, String action, LocalDateTime createdAt) {
        OperationLog log = new OperationLog();
        log.setId(id);
        log.setUser(user);
        log.setAction(action);
        log.setTarget("users:" + id);
        log.setDetail("内容" + id);
        log.setIpAddress("192.0.2." + id);
        log.setCreatedAt(createdAt);
        return log;
    }

    private static LogSearchForm form(LocalDate from, LocalDate to, Long userId, OperationAction action) {
        LogSearchForm form = new LogSearchForm();
        form.setFrom(from);
        form.setTo(to);
        form.setUserId(userId);
        form.setAction(action);
        return form;
    }

    @Test
    void 検索_判定4真_actionなしならnullで_期間はfromの0時からtoの翌日0時未満で検索する() {
        Pageable pageable = PageRequest.of(1, 50);
        OperationLog log = log(9L, user(3L, "新井 正人", "arai", Role.ADMIN), "SHIFT_PUBLISH",
                LocalDateTime.of(2026, 10, 22, 9, 5, 31));
        when(operationLogRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(log), pageable, 51));

        Page<OperationLogRow> page = service.search(
                form(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 22), null, null), pageable);

        verify(operationLogRepository).search(eq(LocalDateTime.of(2026, 10, 16, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 23, 0, 0)), isNull(), isNull(), eq(pageable));
        assertThat(page.getTotalElements()).isEqualTo(51);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getContent()).containsExactly(new OperationLogRow(9L,
                LocalDateTime.of(2026, 10, 22, 9, 5, 31), "2026/10/22 09:05:31", "新井 正人", "シフトの公開",
                "users:9", "内容9", "192.0.2.9"));
    }

    @Test
    void 検索_判定4偽_actionありなら名前の文字列と利用者IDで検索する() {
        Pageable pageable = PageRequest.of(0, 50);
        when(operationLogRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<OperationLogRow> page = service.search(
                form(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22), 4L, OperationAction.LOGIN_FAILURE),
                pageable);

        verify(operationLogRepository).search(eq(LocalDateTime.of(2026, 10, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 23, 0, 0)), eq(4L), eq("LOGIN_FAILURE"), eq(pageable));
        assertThat(page.getContent()).isEmpty();
    }

    // ---------------------------------------------------------------- toRow・actionLabel・roleLabel

    @Test
    void 行_判定5真_利用者がnullなら氏名はハイフン() {
        OperationLogRow row = OperationLogService.toRow(
                log(1L, null, "LOGIN_FAILURE", LocalDateTime.of(2026, 10, 22, 23, 59, 59)));

        assertThat(row.userName()).isEqualTo("－");
        assertThat(row.createdAtLabel()).isEqualTo("2026/10/22 23:59:59");
        assertThat(row.actionLabel()).isEqualTo("ログイン失敗");
    }

    @Test
    void 行_判定5偽_利用者がいれば氏名() {
        OperationLogRow row = OperationLogService.toRow(
                log(2L, user(5L, "山田 太郎", "yamada", Role.STAFF), "LOGOUT", LocalDateTime.of(2026, 1, 2, 3, 4, 5)));

        assertThat(row.userName()).isEqualTo("山田 太郎");
        assertThat(row.createdAtLabel()).isEqualTo("2026/01/02 03:04:05");
    }

    @Test
    void 操作名_判定6真_列挙の全値を日本語名にする() {
        for (OperationAction a : OperationAction.values()) {
            assertThat(OperationLogService.actionLabel(a.name())).isEqualTo(a.getLabel());
        }
    }

    @Test
    void 操作名_判定6偽_列挙にない値とnullはそのまま() {
        assertThat(OperationLogService.actionLabel("OLD_ACTION")).isEqualTo("OLD_ACTION");
        assertThat(OperationLogService.actionLabel(null)).isNull();
    }

    @Test
    void 役割名_判定7_3つの役割() {
        assertThat(OperationLogService.roleLabel(Role.ADMIN)).isEqualTo("管理者");
        assertThat(OperationLogService.roleLabel(Role.MANAGER)).isEqualTo("店長");
        assertThat(OperationLogService.roleLabel(Role.STAFF)).isEqualTo("バイト");
    }

    // ---------------------------------------------------------------- userOptions（SA-03）

    @Test
    void 利用者の選択肢_リポジトリの順に氏名とログインIDと役割で作る() {
        when(userRepository.findAllForOptions()).thenReturn(List.of(
                user(1L, "新井 正人", "arai", Role.ADMIN),
                user(2L, "店長 一郎", "tencho01", Role.MANAGER),
                user(3L, "山田 太郎", "yamada", Role.STAFF)));

        assertThat(service.userOptions()).containsExactly(
                new SelectOption("1", "新井 正人（arai・管理者）"),
                new SelectOption("2", "店長 一郎（tencho01・店長）"),
                new SelectOption("3", "山田 太郎（yamada・バイト）"));
    }

    @Test
    void 利用者の選択肢_ユーザーがいなければ空() {
        when(userRepository.findAllForOptions()).thenReturn(List.of());

        assertThat(service.userOptions()).isEmpty();
    }

    // ---------------------------------------------------------------- findRecent（SA-01）

    private List<OperationLog> fiveLogs() {
        return List.of(
                log(5L, null, "LOGIN_FAILURE", LocalDateTime.of(2026, 10, 22, 9, 0, 5)),
                log(4L, user(1L, "新井 正人", "arai", Role.ADMIN), "BACKUP_CREATE",
                        LocalDateTime.of(2026, 10, 22, 9, 0, 4)),
                log(3L, null, "LOGIN_FAILURE", LocalDateTime.of(2026, 10, 22, 9, 0, 3)),
                log(2L, null, "LOGIN_FAILURE", LocalDateTime.of(2026, 10, 22, 9, 0, 2)),
                log(1L, null, "LOGIN_FAILURE", LocalDateTime.of(2026, 10, 22, 9, 0, 1)));
    }

    @Test
    void 最近の操作_判定8_limitが件数以上なら新しい順に全件を行にする() {
        when(operationLogRepository.findLatest5()).thenReturn(fiveLogs());

        List<OperationLogRow> rows = service.findRecent(5);

        assertThat(rows).extracting(OperationLogRow::id).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(rows.get(1).userName()).isEqualTo("新井 正人");
        assertThat(rows.get(1).actionLabel()).isEqualTo("バックアップ作成");
        assertThat(rows.get(1).createdAtLabel()).isEqualTo("2026/10/22 09:00:04");
    }

    @Test
    void 最近の操作_判定8_limitが件数より少なければlimit件まで() {
        when(operationLogRepository.findLatest5()).thenReturn(fiveLogs());

        assertThat(service.findRecent(3)).extracting(OperationLogRow::id).containsExactly(5L, 4L, 3L);
    }

    @Test
    void 最近の操作_ログがなければ空() {
        when(operationLogRepository.findLatest5()).thenReturn(List.of());

        assertThat(service.findRecent(5)).isEmpty();
    }
}

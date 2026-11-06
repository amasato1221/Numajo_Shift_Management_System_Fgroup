package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.StaffCreatedResult;
import jp.numajo.fgroup.shift.dto.StaffDetailView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.StaffForm;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SkillRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.PasswordGenerator;

/**
 * StaffService（SM-03：スタッフ登録・編集・パスワード初期化）のテスト。
 *
 * <table>
 * <caption>無効にしたときの割当の件数（update の戻り値）</caption>
 * <tr><th>今の状態</th><th>保存する状態</th><th>今日以降の割当</th><th>戻り値</th></tr>
 * <tr><td>有効</td><td>無効</td><td>2件</td><td>2</td></tr>
 * <tr><td>有効</td><td>無効</td><td>0件（昨日だけなど）</td><td>0</td></tr>
 * <tr><td>無効</td><td>無効</td><td>（数えない）</td><td>0</td></tr>
 * <tr><td>無効</td><td>有効</td><td>（数えない）</td><td>0</td></tr>
 * <tr><td>有効</td><td>有効</td><td>（数えない）</td><td>0</td></tr>
 * </table>
 *
 * <table>
 * <caption>ログインIDの重複チェック（update）</caption>
 * <tr><th>変更</th><th>ほかの人が使用中</th><th>結果</th></tr>
 * <tr><td>変えない</td><td>－</td><td>調べない（OK）</td></tr>
 * <tr><td>大文字・小文字だけ変える</td><td>－</td><td>調べない（OK）</td></tr>
 * <tr><td>別のIDに変える</td><td>なし</td><td>OK</td></tr>
 * <tr><td>別のIDに変える</td><td>あり</td><td>E2208</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class StaffServiceEditTests {

    /** テストの「今」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final Long OPERATOR_ID = 99L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private ShiftAssignmentRepository assignmentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PasswordGenerator passwordGenerator;
    @Mock
    private NotificationSettingService notificationSettingService;
    @Mock
    private CalendarFeedService calendarFeedService;
    @Mock
    private OperationLogService operationLogService;

    private StaffService service;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new StaffService(userRepository, skillRepository, assignmentRepository, passwordEncoder,
                passwordGenerator, notificationSettingService, calendarFeedService, operationLogService, clock);
    }

    private static Skill skill(Long id, String name) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName(name);
        return skill;
    }

    /** 保存済みのスタッフ（有効・職能なし）。 */
    private static User staff(Long id, String loginId) {
        User user = new User();
        user.setId(id);
        user.setLoginId(loginId);
        user.setName("山田 太郎");
        user.setNameKana("やまだ たろう");
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setSkills(new HashSet<>());
        return user;
    }

    private static StaffForm form(String loginId, Long... skillIds) {
        StaffForm form = new StaffForm();
        form.setName("山田 太郎");
        form.setNameKana("やまだ たろう");
        form.setLoginId(loginId);
        form.setSkillIds(List.of(skillIds));
        return form;
    }

    private void givenStaff(User user) {
        when(userRepository.findByIdAndRole(user.getId(), Role.STAFF)).thenReturn(Optional.of(user));
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_登録して初期パスワードを返す() {
        when(userRepository.existsByLoginId("yamada")).thenReturn(false);
        when(skillRepository.findAllById(any())).thenReturn(List.of(skill(10L, "レジ"), skill(20L, "キッチン")));
        when(passwordGenerator.generate()).thenReturn("Abcdef2345");
        when(passwordEncoder.encode("Abcdef2345")).thenReturn("HASH");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        StaffForm form = form("yamada", 10L, 20L);
        form.setTrainee(true);

        StaffCreatedResult actual = service.create(form, OPERATOR_ID);

        assertThat(actual.userId()).isEqualTo(7L);
        assertThat(actual.initialPassword()).isEqualTo("Abcdef2345");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getLoginId()).isEqualTo("yamada");
        assertThat(saved.getPasswordHash()).isEqualTo("HASH");
        assertThat(saved.getName()).isEqualTo("山田 太郎");
        assertThat(saved.getNameKana()).isEqualTo("やまだ たろう");
        assertThat(saved.getRole()).isEqualTo(Role.STAFF);
        assertThat(saved.isTrainee()).isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.getFailedLoginCount()).isZero();
        assertThat(saved.getSkills()).extracting(Skill::getId).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void create_通知の設定と購読トークンを作り操作ログを残す() {
        when(userRepository.existsByLoginId("yamada")).thenReturn(false);
        when(passwordGenerator.generate()).thenReturn("Abcdef2345");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        service.create(form("yamada"), OPERATOR_ID);

        verify(notificationSettingService).createDefault(7L);
        verify(calendarFeedService).getOrCreate(7L);
        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_CREATE, "user:7",
                "loginId=yamada, name=山田 太郎");
    }

    @Test
    void create_職能が選ばれていなければ職能を調べない() {
        when(userRepository.existsByLoginId("yamada")).thenReturn(false);
        when(passwordGenerator.generate()).thenReturn("Abcdef2345");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        service.create(form("yamada"), OPERATOR_ID);

        verify(skillRepository, never()).findAllById(any());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getSkills()).isEmpty();
    }

    @Test
    void create_ログインIDが使われていればE2208で保存しない() {
        when(userRepository.existsByLoginId("yamada")).thenReturn(true);

        assertThatThrownBy(() -> service.create(form("yamada"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2208");

        verify(userRepository, never()).save(any());
    }

    @Test
    void create_存在しない職能があればE2209で保存しない() {
        when(userRepository.existsByLoginId("yamada")).thenReturn(false);
        when(skillRepository.findAllById(any())).thenReturn(List.of(skill(10L, "レジ")));

        assertThatThrownBy(() -> service.create(form("yamada", 10L, 20L), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2209");

        verify(userRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- getForEdit・getDetail

    @Test
    void getForEdit_今の値をフォームに入れる() {
        User user = staff(1L, "yamada");
        user.setTrainee(true);
        user.setActive(false);
        user.setSkills(new HashSet<>(Set.of(skill(20L, "キッチン"), skill(10L, "レジ"))));
        givenStaff(user);

        StaffForm actual = service.getForEdit(1L);

        assertThat(actual.getName()).isEqualTo("山田 太郎");
        assertThat(actual.getNameKana()).isEqualTo("やまだ たろう");
        assertThat(actual.getLoginId()).isEqualTo("yamada");
        assertThat(actual.getSkillIds()).containsExactly(10L, 20L);
        assertThat(actual.isTrainee()).isTrue();
        assertThat(actual.isActive()).isFalse();
    }

    @Test
    void getForEdit_いないかスタッフでなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForEdit(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDetail_ロック中なら解除の時刻を出す() {
        User user = staff(1L, "yamada");
        user.setUpdatedAt(LocalDateTime.of(2098, 12, 31, 21, 3));
        user.setLockedUntil(LocalDateTime.of(2099, 1, 1, 13, 30));
        givenStaff(user);

        StaffDetailView actual = service.getDetail(1L);

        assertThat(actual.updatedAtLabel()).isEqualTo("2098/12/31 21:03");
        assertThat(actual.locked()).isTrue();
        assertThat(actual.lockedUntilLabel()).isEqualTo("13:30");
    }

    @Test
    void getDetail_ロック解除の時刻が今と同じならロック中ではない() {
        User user = staff(1L, "yamada");
        user.setLockedUntil(NOW);
        givenStaff(user);

        StaffDetailView actual = service.getDetail(1L);

        assertThat(actual.locked()).isFalse();
        assertThat(actual.lockedUntilLabel()).isNull();
    }

    @Test
    void getDetail_ロックされていなければ解除の時刻はなし() {
        User user = staff(1L, "yamada");
        givenStaff(user);

        StaffDetailView actual = service.getDetail(1L);

        assertThat(actual.locked()).isFalse();
        assertThat(actual.lockedUntilLabel()).isNull();
        assertThat(actual.updatedAtLabel()).isNull();
    }

    @Test
    void getDetail_いなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- update

    @Test
    void update_変えた項目だけを決めた順で操作ログに残す() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(skillRepository.findAllById(any())).thenReturn(List.of(skill(10L, "レジ")));
        StaffForm form = form("yamada", 10L);
        form.setName("山田 次郎");

        int actual = service.update(1L, form, OPERATOR_ID);

        assertThat(actual).isZero();
        assertThat(user.getName()).isEqualTo("山田 次郎");
        assertThat(user.getSkills()).extracting(Skill::getId).containsExactly(10L);
        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_UPDATE, "user:1", "name,skills");
    }

    @Test
    void update_全部の項目を変えたらname_nameKana_loginId_trainee_skills_activeの順() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(userRepository.existsByLoginId("tanaka")).thenReturn(false);
        when(skillRepository.findAllById(any())).thenReturn(List.of(skill(10L, "レジ")));
        StaffForm form = form("tanaka", 10L);
        form.setName("田中 花子");
        form.setNameKana("たなか はなこ");
        form.setTrainee(true);
        form.setActive(false);

        service.update(1L, form, OPERATOR_ID);

        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_UPDATE, "user:1",
                "name,nameKana,loginId,trainee,skills,active");
    }

    @Test
    void update_何も変えなければ操作ログを残さない() {
        User user = staff(1L, "yamada");
        givenStaff(user);

        int actual = service.update(1L, form("yamada"), OPERATOR_ID);

        assertThat(actual).isZero();
        verify(operationLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void update_職能を全部外すと職能が0件になる() {
        User user = staff(1L, "yamada");
        user.setSkills(new HashSet<>(Set.of(skill(10L, "レジ"))));
        givenStaff(user);

        service.update(1L, form("yamada"), OPERATOR_ID);

        assertThat(user.getSkills()).isEmpty();
        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_UPDATE, "user:1", "skills");
    }

    @Test
    void update_ログインIDを変えないなら重複を調べない() {
        User user = staff(1L, "yamada");
        givenStaff(user);

        service.update(1L, form("yamada"), OPERATOR_ID);

        verify(userRepository, never()).existsByLoginId(any());
    }

    @Test
    void update_大文字小文字だけの変更は重複を調べない() {
        User user = staff(1L, "yamada");
        givenStaff(user);

        service.update(1L, form("Yamada"), OPERATOR_ID);

        verify(userRepository, never()).existsByLoginId(any());
        assertThat(user.getLoginId()).isEqualTo("Yamada");
        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_UPDATE, "user:1", "loginId");
    }

    @Test
    void update_別のIDに変えてほかの人が使っていればE2208() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(userRepository.existsByLoginId("tanaka")).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, form("tanaka"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2208");

        assertThat(user.getLoginId()).isEqualTo("yamada");
        verify(operationLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void update_存在しない職能があればE2209() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(skillRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(1L, form("yamada", 10L), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2209");
    }

    @Test
    void update_いなければ404() {
        when(userRepository.findByIdAndRole(5L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5L, form("yamada"), OPERATOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_有効から無効にすると今日以降の割当の件数を返す() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(assignmentRepository.countAssignedFrom(1L, LocalDate.of(2099, 1, 1))).thenReturn(2L);
        StaffForm form = form("yamada");
        form.setActive(false);

        int actual = service.update(1L, form, OPERATOR_ID);

        assertThat(actual).isEqualTo(2);
        assertThat(user.isActive()).isFalse();
        verify(operationLogService).record(OPERATOR_ID, OperationAction.STAFF_UPDATE, "user:1", "active");
    }

    @Test
    void update_有効から無効にしても今日以降の割当がなければ0() {
        User user = staff(1L, "yamada");
        givenStaff(user);
        when(assignmentRepository.countAssignedFrom(1L, LocalDate.of(2099, 1, 1))).thenReturn(0L);
        StaffForm form = form("yamada");
        form.setActive(false);

        int actual = service.update(1L, form, OPERATOR_ID);

        assertThat(actual).isZero();
    }

    @Test
    void update_すでに無効の人を保存し直しても割当を数えず0() {
        User user = staff(1L, "yamada");
        user.setActive(false);
        givenStaff(user);
        StaffForm form = form("yamada");
        form.setActive(false);

        int actual = service.update(1L, form, OPERATOR_ID);

        assertThat(actual).isZero();
        verify(assignmentRepository, never()).countAssignedFrom(any(), any());
    }

    @Test
    void update_無効から有効にしても割当を数えず0() {
        User user = staff(1L, "yamada");
        user.setActive(false);
        givenStaff(user);

        int actual = service.update(1L, form("yamada"), OPERATOR_ID);

        assertThat(actual).isZero();
        assertThat(user.isActive()).isTrue();
        verify(assignmentRepository, never()).countAssignedFrom(any(), any());
    }

    // ---------------------------------------------------------------- resetPassword

    @Test
    void resetPassword_新しいパスワードにしてロックを解除する() {
        User user = staff(1L, "yamada");
        user.setPasswordHash("OLD");
        user.setMustChangePassword(false);
        user.setFailedLoginCount(5);
        user.setLockedUntil(LocalDateTime.of(2099, 1, 1, 13, 30));
        givenStaff(user);
        when(passwordGenerator.generate()).thenReturn("Newpass234");
        when(passwordEncoder.encode("Newpass234")).thenReturn("NEWHASH");

        String actual = service.resetPassword(1L, OPERATOR_ID);

        assertThat(actual).isEqualTo("Newpass234");
        assertThat(user.getPasswordHash()).isEqualTo("NEWHASH");
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(operationLogService).record(eq(OPERATOR_ID), eq(OperationAction.STAFF_PASSWORD_RESET),
                eq("user:1"), isNull());
    }

    @Test
    void resetPassword_いなければ404でログを残さない() {
        when(userRepository.findByIdAndRole(5L, Role.STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(5L, OPERATOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(operationLogService, never()).record(any(), any(), any(), any());
    }
}

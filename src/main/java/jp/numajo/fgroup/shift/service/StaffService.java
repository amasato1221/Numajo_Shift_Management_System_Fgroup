package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.dto.StaffCreatedResult;
import jp.numajo.fgroup.shift.dto.StaffDetailView;
import jp.numajo.fgroup.shift.dto.StaffRowView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.StaffForm;
import jp.numajo.fgroup.shift.repository.ShiftAssignmentRepository;
import jp.numajo.fgroup.shift.repository.SkillRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.LikeUtil;
import jp.numajo.fgroup.shift.util.PasswordGenerator;
import lombok.RequiredArgsConstructor;

/**
 * スタッフの管理（SM-02・SM-03）の業務処理。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffService {

    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordGenerator passwordGenerator;
    private final NotificationSettingService notificationSettingService;
    private final CalendarFeedService calendarFeedService;
    private final OperationLogService operationLogService;
    private final Clock clock;

    /**
     * スタッフを検索する（20-SM-02 2.1）。
     *
     * @param keyword 氏名・ふりがな・ログインIDの部分一致。null・空なら絞り込まない
     * @param status  状態の絞り込み
     * @return ふりがな順の一覧
     */
    public List<StaffRowView> search(String keyword, StaffStatusFilter status) {
        String pattern = LikeUtil.contains(keyword);
        LocalDateTime now = LocalDateTime.now(clock);

        return userRepository.searchStaff(pattern, status.toActiveParam()).stream()
                .map(user -> toRow(user, now))
                .toList();
    }

    /**
     * スタッフを登録する（20-SM-03 2.2）。初期パスワードを作り、通知の設定と購読トークンも作る。
     *
     * @param form       入力
     * @param operatorId 操作した店長のID
     * @return 登録したユーザーのIDと初期パスワード
     * @throws BusinessException ログインIDが使われている（E2208）、存在しない職能がある（E2209）
     */
    @Transactional
    public StaffCreatedResult create(StaffForm form, Long operatorId) {
        if (userRepository.existsByLoginId(form.getLoginId())) {
            throw new BusinessException("E2208");
        }
        Set<Skill> skills = findSkills(form.getSkillIds());

        String initialPassword = passwordGenerator.generate();
        User user = new User();
        user.setLoginId(form.getLoginId());
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setName(form.getName());
        user.setNameKana(form.getNameKana());
        user.setRole(Role.STAFF);
        user.setTrainee(form.isTrainee());
        user.setActive(true);
        user.setMustChangePassword(true);
        user.setFailedLoginCount(0);
        user.setSkills(skills);
        User saved = userRepository.save(user);

        notificationSettingService.createDefault(saved.getId());
        calendarFeedService.getOrCreate(saved.getId());
        operationLogService.record(operatorId, OperationAction.STAFF_CREATE, "user:" + saved.getId(),
                "loginId=" + saved.getLoginId() + ", name=" + saved.getName());
        return new StaffCreatedResult(saved.getId(), initialPassword);
    }

    /**
     * 編集画面のフォームに入れる今の値を取る（20-SM-03 2.3）。
     *
     * @param id ユーザーID
     * @return 今の値を入れたフォーム
     * @throws ResourceNotFoundException いない、またはスタッフではない
     */
    public StaffForm getForEdit(Long id) {
        User user = findStaff(id);
        StaffForm form = new StaffForm();
        form.setName(user.getName());
        form.setNameKana(user.getNameKana());
        form.setLoginId(user.getLoginId());
        form.setSkillIds(user.getSkills().stream().map(Skill::getId).sorted().collect(Collectors.toList()));
        form.setTrainee(user.isTrainee());
        form.setActive(user.isActive());
        return form;
    }

    /**
     * 編集画面の状態の表示（最終更新・ロック中）を作る（20-SM-03 2.3）。
     *
     * @param id ユーザーID
     * @return 状態の表示
     * @throws ResourceNotFoundException いない、またはスタッフではない
     */
    public StaffDetailView getDetail(Long id) {
        User user = findStaff(id);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime updatedAt = user.getUpdatedAt();
        LocalDateTime lockedUntil = user.getLockedUntil();
        boolean locked = lockedUntil != null && lockedUntil.isAfter(now);

        String updatedAtLabel = updatedAt == null ? null : DateTimeUtil.dateTime(updatedAt);
        String lockedUntilLabel = locked ? DateTimeUtil.time(lockedUntil.toLocalTime()) : null;
        return new StaffDetailView(user.getId(), updatedAt, updatedAtLabel, locked, lockedUntil, lockedUntilLabel);
    }

    /**
     * スタッフの情報を更新する（20-SM-03 2.4）。割当は消さない。
     *
     * @param id         ユーザーID
     * @param form       入力
     * @param operatorId 操作した店長のID
     * @return 有効から無効に変えたときの、今日以降の出勤（ASSIGNED）の割当の件数。それ以外は 0
     * @throws BusinessException ログインIDが使われている（E2208）、存在しない職能がある（E2209）
     */
    @Transactional
    public int update(Long id, StaffForm form, Long operatorId) {
        User user = findStaff(id);

        // 大文字・小文字だけの変更は、同じ人のIDなので重複を調べない
        if (!user.getLoginId().equalsIgnoreCase(form.getLoginId())
                && userRepository.existsByLoginId(form.getLoginId())) {
            throw new BusinessException("E2208");
        }
        Set<Skill> skills = findSkills(form.getSkillIds());

        boolean deactivated = user.isActive() && !form.isActive();
        List<String> changed = changedItems(user, form, skills);

        user.setName(form.getName());
        user.setNameKana(form.getNameKana());
        user.setLoginId(form.getLoginId());
        user.setTrainee(form.isTrainee());
        user.setActive(form.isActive());
        if (changed.contains("skills")) {
            user.getSkills().clear();
            user.getSkills().addAll(skills);
        }

        if (!changed.isEmpty()) {
            operationLogService.record(operatorId, OperationAction.STAFF_UPDATE, "user:" + id,
                    String.join(",", changed));
        }
        if (deactivated) {
            return (int) assignmentRepository.countAssignedFrom(id, LocalDate.now(clock));
        }
        return 0;
    }

    /**
     * パスワードを初期化する（20-SM-03 2.5）。ロック中の解除も兼ねる。
     *
     * @param id         ユーザーID
     * @param operatorId 操作した店長のID
     * @return 新しい初期パスワード（平文）
     * @throws ResourceNotFoundException いない、またはスタッフではない
     */
    @Transactional
    public String resetPassword(Long id, Long operatorId) {
        User user = findStaff(id);
        String newPassword = passwordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        operationLogService.record(operatorId, OperationAction.STAFF_PASSWORD_RESET, "user:" + id, null);
        return newPassword;
    }

    /** 変わった項目の名前を、name,nameKana,loginId,trainee,skills,active の順に並べる。 */
    private static List<String> changedItems(User user, StaffForm form, Set<Skill> newSkills) {
        Set<Long> oldSkillIds = user.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        Set<Long> newSkillIds = newSkills.stream().map(Skill::getId).collect(Collectors.toSet());

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(user.getName(), form.getName())) {
            changed.add("name");
        }
        if (!Objects.equals(user.getNameKana(), form.getNameKana())) {
            changed.add("nameKana");
        }
        if (!Objects.equals(user.getLoginId(), form.getLoginId())) {
            changed.add("loginId");
        }
        if (user.isTrainee() != form.isTrainee()) {
            changed.add("trainee");
        }
        if (!oldSkillIds.equals(newSkillIds)) {
            changed.add("skills");
        }
        if (user.isActive() != form.isActive()) {
            changed.add("active");
        }
        return changed;
    }

    /** IDの職能を取る。存在しないIDがあれば E2209。null と重複は除く。 */
    private Set<Skill> findSkills(List<Long> skillIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (skillIds != null) {
            for (Long skillId : skillIds) {
                if (skillId != null) {
                    ids.add(skillId);
                }
            }
        }
        if (ids.isEmpty()) {
            return new HashSet<>();
        }
        List<Skill> found = skillRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new BusinessException("E2209");
        }
        return new HashSet<>(found);
    }

    /** スタッフを取る。いない、または role が STAFF でなければ 404。 */
    private User findStaff(Long id) {
        return userRepository.findByIdAndRole(id, Role.STAFF)
                .orElseThrow(() -> new ResourceNotFoundException("staff id=" + id));
    }

    /** ユーザーを一覧の1行に変換する。ロック中は「ロック解除日時が今より後」。 */
    private StaffRowView toRow(User user, LocalDateTime now) {
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        List<String> skillNames = user.getSkills().stream()
                .map(Skill::getName)
                .sorted()
                .toList();

        return new StaffRowView(user.getId(), user.getName(), user.getNameKana(), user.getLoginId(),
                skillNames, user.isTrainee(), user.isActive(), locked);
    }
}

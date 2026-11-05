package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.dto.StaffRowView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.LikeUtil;
import lombok.RequiredArgsConstructor;

/**
 * スタッフの管理（SM-02・SM-03）の業務処理。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffService {

    private final UserRepository userRepository;
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

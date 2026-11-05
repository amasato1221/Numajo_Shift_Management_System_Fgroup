package jp.numajo.fgroup.shift.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.entity.NotificationSetting;
import jp.numajo.fgroup.shift.entity.id.NotificationSettingId;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;

/**
 * 締切通知の設定（SB-05、SM-03）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSettingService {

    /** 締切通知の選択肢（当日・前日・3日前・7日前） */
    public static final List<Integer> DAYS_OPTIONS = List.of(0, 1, 3, 7);

    /** 締切通知の既定値（前日・3日前） */
    public static final List<Integer> DEFAULT_DAYS = List.of(1, 3);

    private final NotificationSettingRepository notificationSettingRepository;

    /**
     * その人が選んでいる「何日前」を昇順で返す（20-SB-05 2.1）。
     *
     * @param userId ユーザーID
     * @return 何日前のリスト（選んでいなければ空）
     */
    public List<Integer> findDays(Long userId) {
        return notificationSettingRepository.findByIdUserIdOrderByIdDaysBeforeAsc(userId).stream()
                .map(s -> s.getId().getDaysBefore().intValue())
                .toList();
    }

    /**
     * 締切通知の設定を保存する（20-SB-05 2.2）。今の設定を全部消してから入れ直す。
     *
     * @param userId     ユーザーID
     * @param daysBefore 何日前のリスト（空なら0件にする）
     * @throws BusinessException 0・1・3・7 以外の値か null がある（E1501）
     */
    @Transactional
    public void save(Long userId, List<Integer> daysBefore) {
        for (Integer d : daysBefore) {
            // List.of の contains(null) は NullPointerException になるため、先に null を調べる
            if (d == null || !DAYS_OPTIONS.contains(d)) {
                throw new BusinessException("E1501");
            }
        }
        Set<Integer> unique = new LinkedHashSet<>(daysBefore);
        notificationSettingRepository.deleteByUserId(userId);
        insert(userId, unique);
    }

    /**
     * 新しく登録したバイトに既定値（前日・3日前）の2行を入れる（20-SM-03 2.2-6）。
     * StaffService#create と同じトランザクションの中でだけ動く。
     *
     * @param userId ユーザーID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefault(Long userId) {
        insert(userId, DEFAULT_DAYS);
    }

    /** 値ごとに1行入れる。 */
    private void insert(Long userId, Iterable<Integer> days) {
        for (Integer d : days) {
            NotificationSetting setting = new NotificationSetting();
            setting.setId(new NotificationSettingId(userId, d.byteValue()));
            notificationSettingRepository.save(setting);
        }
    }
}

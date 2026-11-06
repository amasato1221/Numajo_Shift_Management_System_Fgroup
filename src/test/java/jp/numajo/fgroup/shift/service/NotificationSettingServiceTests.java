package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.entity.NotificationSetting;
import jp.numajo.fgroup.shift.entity.id.NotificationSettingId;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.repository.NotificationSettingRepository;

/**
 * NotificationSettingService の単体テスト（30-05 1.3、20-SB-05 2.1・2.2、20-SM-03 2.2-6）。
 *
 * <p>
 * Propagation.MANDATORY（createDefault は業務処理のトランザクションの外では動かない）と、
 * 本物の DB への保存は NotificationCalendarServiceIntegrationTests で確かめる。
 * </p>
 *
 * <pre>
 * 命令網羅：findDays・save・createDefault・insert のすべての文を通る（下の各テスト）
 *
 * 判定条件網羅：
 * - save の for：0回（空リスト）／1回以上
 * - save の if：真（2・-1・8・null）／偽（0・1・3・7）
 * - insert の for：0回（空リスト）／1回以上
 *
 * 組み合わせ表（条件網羅・複数条件網羅）：save の値の判定
 * 判定：A d == null || B !DAYS_OPTIONS.contains(d)
 * No. | A | B | 結果
 *  1  | 真| － | E1501（短絡評価で B は評価されない。List.of の contains(null) の例外を防ぐ）
 *  2  | 偽| 真| E1501（2・-1・8）
 *  3  | 偽| 偽| 保存する（0・1・3・7）
 * 境界値：選択肢の前後（-1・0・1・2・3・7・8）
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTests {

    @Mock
    private NotificationSettingRepository notificationSettingRepository;

    @InjectMocks
    private NotificationSettingService service;

    private static NotificationSetting setting(long userId, int days) {
        NotificationSetting s = new NotificationSetting();
        s.setId(new NotificationSettingId(userId, (byte) days));
        return s;
    }

    /** save された「何日前」を、入れた順に返す。 */
    private List<Integer> savedDays(int count) {
        ArgumentCaptor<NotificationSetting> captor = ArgumentCaptor.forClass(NotificationSetting.class);
        verify(notificationSettingRepository, times(count)).save(captor.capture());
        return captor.getAllValues().stream().map(s -> s.getId().getDaysBefore().intValue()).toList();
    }

    // ---------------------------------------------------------------- 定数

    @Test
    void 定数_選択肢と既定値() {
        assertThat(NotificationSettingService.DAYS_OPTIONS).containsExactly(0, 1, 3, 7);
        assertThat(NotificationSettingService.DEFAULT_DAYS).containsExactly(1, 3);
    }

    // ---------------------------------------------------------------- findDays

    @Test
    void findDays_昇順の値をIntegerで返す() {
        when(notificationSettingRepository.findByIdUserIdOrderByIdDaysBeforeAsc(5L))
                .thenReturn(List.of(setting(5L, 0), setting(5L, 3), setting(5L, 7)));

        assertThat(service.findDays(5L)).containsExactly(0, 3, 7);
    }

    @Test
    void findDays_行がなければ空() {
        when(notificationSettingRepository.findByIdUserIdOrderByIdDaysBeforeAsc(5L)).thenReturn(List.of());

        assertThat(service.findDays(5L)).isEmpty();
    }

    // ---------------------------------------------------------------- save

    @Test
    void save_組み合わせ3_当日と7日前を保存すると消してから2行入れる() {
        service.save(5L, List.of(0, 7));

        InOrder order = inOrder(notificationSettingRepository);
        order.verify(notificationSettingRepository).deleteByUserId(5L);
        order.verify(notificationSettingRepository, times(2)).save(any());
        assertThat(savedDays(2)).containsExactly(0, 7);
    }

    @Test
    void save_選択肢4つすべてを保存できる() {
        service.save(5L, List.of(0, 1, 3, 7));

        assertThat(savedDays(4)).containsExactly(0, 1, 3, 7);
    }

    @Test
    void save_入れる行のユーザーIDが正しい() {
        service.save(9L, List.of(1));

        ArgumentCaptor<NotificationSetting> captor = ArgumentCaptor.forClass(NotificationSetting.class);
        verify(notificationSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getId().getUserId()).isEqualTo(9L);
    }

    @Test
    void save_空のリストは消すだけで0件にする() {
        service.save(5L, List.of());

        verify(notificationSettingRepository).deleteByUserId(5L);
        verify(notificationSettingRepository, never()).save(any());
    }

    @Test
    void save_重複は取り除き最初に出た順で入れる() {
        service.save(5L, List.of(3, 1, 3, 1));

        assertThat(savedDays(2)).containsExactly(3, 1);
    }

    @Test
    void save_組み合わせ2_選択肢にない値はE1501で何も変えない() {
        assertE1501(List.of(1, 2));
    }

    @Test
    void save_組み合わせ2_選択肢にない値はE1501_境界の前後() {
        assertE1501(List.of(-1));
        assertE1501(List.of(8));
    }

    @Test
    void save_組み合わせ1_nullの値はE1501() {
        List<Integer> list = new ArrayList<>(Arrays.asList(1, null));
        assertE1501(list);
    }

    private void assertE1501(List<Integer> days) {
        assertThatThrownBy(() -> service.save(5L, days))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageCode())
                .isEqualTo("E1501");
        verify(notificationSettingRepository, never()).deleteByUserId(anyLong());
        verify(notificationSettingRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- createDefault

    @Test
    void createDefault_前日と3日前の2行を入れる() {
        service.createDefault(7L);

        assertThat(savedDays(2)).containsExactly(1, 3);
        verify(notificationSettingRepository, never()).deleteByUserId(anyLong());
    }
}

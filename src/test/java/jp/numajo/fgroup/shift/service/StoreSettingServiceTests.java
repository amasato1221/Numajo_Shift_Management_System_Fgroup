package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.repository.StoreSettingRepository;

/**
 * StoreSettingService の単体テスト（20-SM-09 2.1・2.2）。
 *
 * <pre>
 * 組み合わせ表：update の「値が今と同じか」
 * 判定：A 店舗名が同じ && B ほかの人のシフト表示が同じ
 * No. | A | B | 結果
 *  1  | 真| 真| 何もしない（操作ログも残さない）
 *  2  | 真| 偽| 保存して操作ログ
 *  3  | 偽| 真| 保存して操作ログ
 *  4  | 偽| 偽| 保存して操作ログ
 * No.3・4 は短絡評価で B を見ないが、組み合わせとしてテストする。
 *
 * 判定条件網羅：get の「id=1 の行があるか」（ある／ない）。
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class StoreSettingServiceTests {

    private static final String NAME = "セブンイレブン 三島駅西通り店";

    @Mock
    private StoreSettingRepository storeSettingRepository;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private StoreSettingService storeSettingService;

    private StoreSetting setting(String name, boolean show) {
        StoreSetting s = new StoreSetting();
        s.setId((byte) 1);
        s.setStoreName(name);
        s.setShowOthersShifts(show);
        when(storeSettingRepository.findById((byte) 1)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void get_id1の行を返す() {
        StoreSetting s = setting(NAME, true);

        StoreSetting actual = storeSettingService.get();

        assertThat(actual).isSameAs(s);
    }

    @Test
    void get_行がなければIllegalStateException() {
        when(storeSettingRepository.findById((byte) 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeSettingService.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void update_No1_両方同じなら何もしない() {
        StoreSetting s = setting(NAME, true);

        storeSettingService.update(NAME, true, 10L);

        assertThat(s.getStoreName()).isEqualTo(NAME);
        assertThat(s.isShowOthersShifts()).isTrue();
        verify(operationLogService, never()).record(any(), any(), anyString(), anyString());
    }

    @Test
    void update_No2_表示だけ変えると保存して操作ログ() {
        StoreSetting s = setting(NAME, true);

        storeSettingService.update(NAME, false, 10L);

        assertThat(s.isShowOthersShifts()).isFalse();
        verify(operationLogService).record(10L, OperationAction.STORE_SETTING_UPDATE, "store_settings:1",
                "storeName=" + NAME + ", showOthersShifts=false");
    }

    @Test
    void update_No3_店舗名だけ変えると保存して操作ログ() {
        StoreSetting s = setting(NAME, true);

        storeSettingService.update("新しい店", true, 10L);

        assertThat(s.getStoreName()).isEqualTo("新しい店");
        assertThat(s.isShowOthersShifts()).isTrue();
        verify(operationLogService).record(10L, OperationAction.STORE_SETTING_UPDATE, "store_settings:1",
                "storeName=新しい店, showOthersShifts=true");
    }

    @Test
    void update_No4_両方変えると保存して操作ログ() {
        StoreSetting s = setting(NAME, false);

        storeSettingService.update("新しい店", true, 10L);

        assertThat(s.getStoreName()).isEqualTo("新しい店");
        assertThat(s.isShowOthersShifts()).isTrue();
        verify(operationLogService).record(10L, OperationAction.STORE_SETTING_UPDATE, "store_settings:1",
                "storeName=新しい店, showOthersShifts=true");
    }
}

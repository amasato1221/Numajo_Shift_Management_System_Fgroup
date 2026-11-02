package jp.numajo.fgroup.shift.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.repository.StoreSettingRepository;
import lombok.RequiredArgsConstructor;

/**
 * 店舗設定（SM-09、ヘッダーの店舗名）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreSettingService {

    /** 店舗設定の行のID（1行だけ） */
    static final byte SETTING_ID = 1;

    private final StoreSettingRepository storeSettingRepository;
    private final OperationLogService operationLogService;

    /**
     * 店舗設定を取得する（20-SM-09 2.1）。
     *
     * @return id＝1 の店舗設定
     * @throws IllegalStateException 行がない（初期データで必ず入るため、500 エラーでよい）
     */
    public StoreSetting get() {
        return storeSettingRepository.findById(SETTING_ID)
                .orElseThrow(() -> new IllegalStateException("store_settings の id=1 がありません"));
    }

    /**
     * 店舗設定を保存する（20-SM-09 2.2）。値が今と同じなら何もしない（操作ログも残さない）。
     *
     * @param storeName        店舗名
     * @param showOthersShifts ほかの人のシフトを表示するか
     * @param operatorId       操作した店長のユーザーID
     */
    @Transactional
    public void update(String storeName, boolean showOthersShifts, Long operatorId) {
        StoreSetting setting = get();
        if (Objects.equals(setting.getStoreName(), storeName) && setting.isShowOthersShifts() == showOthersShifts) {
            return;
        }
        setting.setStoreName(storeName);
        setting.setShowOthersShifts(showOthersShifts);
        operationLogService.record(operatorId, OperationAction.STORE_SETTING_UPDATE, "store_settings:" + SETTING_ID,
                "storeName=" + storeName + ", showOthersShifts=" + showOthersShifts);
    }
}

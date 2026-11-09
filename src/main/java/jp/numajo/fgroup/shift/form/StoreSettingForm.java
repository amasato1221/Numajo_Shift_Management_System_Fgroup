package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SM-09 店舗設定の入力（20-SM-09 1章、30-07）。
 */
@Getter
@Setter
@NoArgsConstructor
public class StoreSettingForm {

    /** 店舗名 */
    @NotBlank(message = "{E2901}")
    @Size(max = 50, message = "{E2902}")
    private String storeName;

    /** ほかの人のシフトを表示するか（チェックを外すと hidden の _showOthersShifts により false になる） */
    private boolean showOthersShifts;
}

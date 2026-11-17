package jp.numajo.fgroup.shift.form;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SB-05 締切通知の設定の入力（20-SB-05 1.1、30-07）。
 *
 * <p>
 * 入力チェックは付けない。0・1・3・7 以外の値は NotificationSettingService#save で E1501 にする。
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
public class NotificationSettingForm {

    /** 何日前に知らせるか（0＝当日、1＝前日、3＝3日前、7＝7日前）。全部外すと null で届く */
    private List<Integer> daysBefore;
}

package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ダッシュボード（SM-01）の欠員1件（30-07）。
 *
 * @param workDate        日付
 * @param workDateLabel   日付の表示（月日と曜日。例：10月24日(土)）
 * @param slotLabel       枠（☀日勤・☾夕勤。時間指定は「時間指定 13:00〜22:00」）
 * @param staffName       欠勤した人の名前
 * @param notifiedAt      欠員を通知した日時。通知していなければ null
 * @param notifiedAtLabel 通知日時の表示（短い。例：10/23 09:12）。通知していなければ null
 * @param hasCount        人数を出すか（時間指定でどの枠とも重ならなければ false）
 * @param assigned        その枠の今の人数（hasCount が false なら 0）
 * @param required        その枠の必要人数（hasCount が false なら 0）
 */
public record VacancyView(LocalDate workDate, String workDateLabel, String slotLabel, String staffName,
        LocalDateTime notifiedAt, String notifiedAtLabel, boolean hasCount, int assigned, int required) {
}

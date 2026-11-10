package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 欠勤登録（SM-07）の「① 日付と人を選ぶ」の1画面分。
 *
 * @param date            日付
 * @param dateLabel       日付の表示（月日と曜日）
 * @param candidatesOfDay その日の出勤者（公開済みの期間の ASSIGNED）
 * @param absentsOfDay    すでに欠勤の人（ABSENT）
 */
public record AbsenceDayView(LocalDate date, String dateLabel, List<AbsenceTargetRow> candidatesOfDay,
        List<AbsenceTargetRow> absentsOfDay) {
}

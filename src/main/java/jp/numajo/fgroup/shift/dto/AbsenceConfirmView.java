package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 欠勤登録（SM-07）の「② 内容を確かめて登録する」の1画面分。
 *
 * @param assignmentId   割当ID
 * @param workDate       割当の日付
 * @param workDateLabel  日付の表示（月日と曜日）
 * @param targetLabel    欠勤する人の表示（例：10月24日(土) ☀日勤 8:00〜17:00　山田 太郎）
 * @param recipients     通知する相手
 * @param defaultMessage 通知する文の既定文
 */
public record AbsenceConfirmView(Long assignmentId, LocalDate workDate, String workDateLabel, String targetLabel,
        List<VacancyRecipientRow> recipients, String defaultMessage) {
}

package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;

/**
 * 時間指定の割当の1行（SM-06）（30-07）。
 *
 * @param assignmentId 割当のID
 * @param name         氏名
 * @param workDate     日付
 * @param workDateLabel 日付の表示（月日と曜日。例：11月1日(日)）
 * @param startTime    開始時刻
 * @param endTime      終了時刻
 * @param timeLabel    時間帯の表示（例：13:00〜22:00）
 * @param status       出勤・欠勤
 */
public record TimeAssignmentRow(Long assignmentId, String name, LocalDate workDate, String workDateLabel,
        LocalTime startTime, LocalTime endTime, String timeLabel, AssignmentStatus status) {
}

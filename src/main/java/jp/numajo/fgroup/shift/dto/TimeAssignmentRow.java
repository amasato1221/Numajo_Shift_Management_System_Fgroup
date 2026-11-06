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
 * @param startTime    開始時刻
 * @param endTime      終了時刻
 * @param status       出勤・欠勤
 */
public record TimeAssignmentRow(Long assignmentId, String name, LocalDate workDate, LocalTime startTime,
        LocalTime endTime, AssignmentStatus status) {
}

package jp.numajo.fgroup.shift.dto;

import java.time.LocalDateTime;

/**
 * 欠勤登録（SM-07）の「その日の出勤者」「すでに欠勤の人」の1行分。
 *
 * @param assignmentId       割当ID
 * @param slotLabel          枠（☀日勤・☾夕勤。時間指定は「時間指定 13:00〜22:00」）
 * @param staffName          氏名
 * @param registeredAt       欠勤を登録した日時（出勤者の行は null）
 * @param registeredAtLabel  登録日時の表示（日時。出勤者の行は null）
 * @param notifiedAt         通知した日時（出勤者の行は null）
 * @param notifiedAtLabel    通知日時の表示（日時。出勤者の行は null）
 */
public record AbsenceTargetRow(Long assignmentId, String slotLabel, String staffName,
        LocalDateTime registeredAt, String registeredAtLabel, LocalDateTime notifiedAt, String notifiedAtLabel) {
}

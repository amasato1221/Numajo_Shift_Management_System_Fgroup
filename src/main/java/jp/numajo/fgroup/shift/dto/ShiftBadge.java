package jp.numajo.fgroup.shift.dto;

import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.BadgeType;

/**
 * シフトのバッジ1つ（20-00 4章、30-07）。表示は fragments/shift-badge.html。
 *
 * @param type      バッジの種類（日勤・夕勤・時間指定・未確定・欠勤）
 * @param icons     アイコン（☀、☾、時間指定は重なる枠の分だけ並べる）
 * @param timeText  時間の表示（例：13-22）。枠のシフトは null
 * @param startTime 開始時刻（1日に複数あるときの並べ替えに使う）
 * @param endTime   終了時刻
 */
public record ShiftBadge(BadgeType type, String icons, String timeText, LocalTime startTime, LocalTime endTime) {
}

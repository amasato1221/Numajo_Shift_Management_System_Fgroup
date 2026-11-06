package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;

import jp.numajo.fgroup.shift.constant.WarningType;

/**
 * シフト作成（SM-06）の警告1件（30-07）。画面では messageCode と args からメッセージを作る。
 *
 * @param type        警告の種類
 * @param workDate    日付
 * @param slotId      枠のID
 * @param messageCode メッセージコード（W2601〜W2603、W2607）
 * @param args        メッセージの引数
 */
public record ShiftWarning(WarningType type, LocalDate workDate, Byte slotId, String messageCode, Object[] args) {
}

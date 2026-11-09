package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.PeriodStatus;

/**
 * 募集期間のプルダウンの選択肢（30-07）。
 *
 * @param id         募集期間のID
 * @param startDate          開始日
 * @param endDate            終了日
 * @param periodLabel        募集期間の表示（例：11/1〜11/15）
 * @param deadlineAt         提出期限
 * @param deadlineLabel      提出期限の表示・日時（例：2026/10/15 23:59）
 * @param deadlineShortLabel 提出期限の表示・日時（短い）（例：10/15 23:59）
 * @param status             状態
 * @param label              プルダウンの表示名（例：11/1〜11/15（期限 10/15））
 */
public record PeriodOption(Long id, LocalDate startDate, LocalDate endDate, String periodLabel,
        LocalDateTime deadlineAt, String deadlineLabel, String deadlineShortLabel, PeriodStatus status,
        String label) {
}

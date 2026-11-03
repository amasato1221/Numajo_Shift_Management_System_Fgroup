package jp.numajo.fgroup.shift.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.PeriodStatus;

/**
 * 募集期間のプルダウンの選択肢（30-07）。
 *
 * @param id         募集期間のID
 * @param startDate  開始日
 * @param endDate    終了日
 * @param deadlineAt 提出期限
 * @param status     状態
 * @param label      表示名（例：11/1〜11/15（期限 10/15））
 */
public record PeriodOption(Long id, LocalDate startDate, LocalDate endDate, LocalDateTime deadlineAt,
        PeriodStatus status, String label) {
}

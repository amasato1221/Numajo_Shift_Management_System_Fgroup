package jp.numajo.fgroup.shift.dto;

import java.util.List;

/**
 * 店長のダッシュボード1画面分（SM-01、30-07）。
 *
 * @param submissionStatuses 受付中の期間ごとの希望の提出状況（開始日の順）。なければ空（I2101）
 * @param shortages          作成中の期間の人数が足りない枠（日付・枠の順に最大10件）。なければ空（I2102）
 * @param moreShortages      10件を超えた残りの件数（「ほか○件」）
 * @param vacancies          今日以降の欠勤（日付・開始時刻の順）。なければ空（I2103）
 * @param periods            募集期間の表（終わってから31日以内の期間、開始日の順）
 */
public record DashboardView(List<SubmissionStatusView> submissionStatuses, List<ShortageView> shortages,
        int moreShortages, List<VacancyView> vacancies, List<PeriodRowView> periods) {
}

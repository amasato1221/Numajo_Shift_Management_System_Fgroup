package jp.numajo.fgroup.shift.dto;

import java.util.List;

/**
 * バイトのホーム1画面分（SB-01、30-07）。
 *
 * @param nextShift          次の出勤。なければ null（I1101 を出す）
 * @param unsubmittedPeriods 希望が未提出の期間（期限の早い順）
 * @param latestNotices      未読のお知らせ（新しい順に最大3件）
 * @param calendar           今月のシフト
 */
public record StaffHomeView(NextShiftView nextShift, List<PeriodOption> unsubmittedPeriods,
        List<NoticeRow> latestNotices, MonthCalendar calendar) {
}

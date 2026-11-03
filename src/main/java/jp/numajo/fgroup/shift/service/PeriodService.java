package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * 募集期間に関する業務（30-05 3.5、95_通知・定期処理設計書 2・3章）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeriodService {

    /** 店長の期間の選択肢に残す日数（終了日が今日のこの日数前以降。20-SM-06 2 の No.1） */
    private static final int MANAGER_OPTION_DAYS = 62;

    private final ShiftPeriodRepository shiftPeriodRepository;
    private final Clock clock;

    /**
     * バイトの期間の選択肢（20-SB-02 2.1-1）。
     * 開始日が「今日の3か月前の1日」から「今日の2か月後の末日」まで、開始日の昇順。
     * 表示名は「10/16〜10/31」。
     */
    public List<PeriodOption> findOptionsForStaff() {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusMonths(3).withDayOfMonth(1);
        LocalDate to = today.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());
        return shiftPeriodRepository.findByStartDateBetweenOrderByStartDateAsc(from, to).stream()
                .map(period -> toOption(period, DateTimeUtil.period(period.getStartDate(), period.getEndDate())))
                .toList();
    }

    /**
     * 表示する期間を決める（20-SB-02 2.1-2）。
     *
     * @param options  {@link #findOptionsForStaff()} の結果
     * @param periodId 指定された期間のID（なければ null）
     * @return 表示する期間。選択肢が0件で periodId もないときは null
     * @throws ResourceNotFoundException periodId が選択肢にないとき（選択肢が0件でも）
     */
    public ShiftPeriod resolveDefault(List<PeriodOption> options, Long periodId) {
        if (periodId != null) {
            boolean listed = options.stream().anyMatch(option -> option.id().equals(periodId));
            if (!listed) {
                throw new ResourceNotFoundException("period id=" + periodId);
            }
            return getPeriod(periodId);
        }
        if (options.isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now(clock);
        for (PeriodOption option : options) {
            if (!option.startDate().isAfter(today) && !option.endDate().isBefore(today)) {
                return getPeriod(option.id());
            }
        }
        for (PeriodOption option : options) {
            if (option.startDate().isAfter(today)) {
                return getPeriod(option.id());
            }
        }
        return getPeriod(options.get(options.size() - 1).id());
    }

    /**
     * 希望を受け付けているか（10_基本設計書 6.1：状態だけでなく日時でも確認する）。
     */
    public boolean isAcceptingRequests(ShiftPeriod period) {
        LocalDateTime now = LocalDateTime.now(clock);
        return period.getStatus() == PeriodStatus.OPEN && !now.isAfter(period.getDeadlineAt());
    }

    /**
     * 期間を選ばずに SM-06 を開いたときの期間（20-SM-06 3.1）。
     * CLOSED の一番早い期間 → OPEN の一番早い期間 → 今日を含む期間、の順に探す。
     *
     * @return 期間。どれもなければ null
     */
    public ShiftPeriod defaultPeriodForManager() {
        return shiftPeriodRepository.findFirstByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)
                .or(() -> shiftPeriodRepository.findFirstByStatusOrderByStartDateAsc(PeriodStatus.OPEN))
                .or(() -> shiftPeriodRepository.findContaining(LocalDate.now(clock)))
                .orElse(null);
    }

    /**
     * 店長の期間の選択肢（20-SM-06 2 の No.1）。
     * 終了日が今日の62日前以降の期間を、開始日の昇順で返す。表示名は「11/1〜11/15（作成中）」。
     */
    public List<PeriodOption> findOptionsForManager() {
        LocalDate from = LocalDate.now(clock).minusDays(MANAGER_OPTION_DAYS);
        return shiftPeriodRepository.findByEndDateGreaterThanEqualOrderByStartDateAsc(from).stream()
                .map(period -> toOption(period, DateTimeUtil.period(period.getStartDate(), period.getEndDate())
                        + "（" + period.getStatus().getLabel() + "）"))
                .toList();
    }

    /**
     * 期間を1件取る。
     *
     * @throws ResourceNotFoundException 期間がないとき
     */
    public ShiftPeriod getPeriod(Long id) {
        return shiftPeriodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("period id=" + id));
    }

    /**
     * BT-01：今月と来月の前半・後半の4期間のうち、ないものを作る（95 2章）。
     *
     * @param today 今日
     * @return 作った数
     */
    @Transactional
    public int createUpcomingPeriods(LocalDate today) {
        LocalDate first = today.withDayOfMonth(1);
        List<LocalDate> starts = List.of(
                first,
                first.withDayOfMonth(16),
                first.plusMonths(1),
                first.plusMonths(1).withDayOfMonth(16));

        int count = 0;
        for (LocalDate start : starts) {
            if (shiftPeriodRepository.existsByStartDate(start)) {
                continue;
            }
            LocalDateTime deadline = deadlineOf(start);

            ShiftPeriod period = new ShiftPeriod();
            period.setStartDate(start);
            period.setEndDate(endDateOf(start));
            period.setDeadlineAt(deadline);
            // すでに期限を過ぎた期間を OPEN で作らない（95 2.2-3-4）
            period.setStatus(deadline.toLocalDate().isBefore(today) ? PeriodStatus.CLOSED : PeriodStatus.OPEN);
            shiftPeriodRepository.save(period);
            count++;
        }
        return count;
    }

    /**
     * BT-02：期限を過ぎた受付中の期間を CLOSED にする（95 3章）。
     * 期限ちょうどのときは締めない（{@code Before} は「より前」）。
     *
     * @param now 今の日時
     * @return 締めた数
     */
    @Transactional
    public int closeExpiredPeriods(LocalDateTime now) {
        List<ShiftPeriod> expired = shiftPeriodRepository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, now);
        for (ShiftPeriod period : expired) {
            period.setStatus(PeriodStatus.CLOSED);
        }
        return expired.size();
    }

    /**
     * 提出期限の決め方（10_基本設計書 6.1）。
     * 開始日が1日 → 前の月の15日 23:59:59。16日 → 前の月の末日 23:59:59。
     */
    public static LocalDateTime deadlineOf(LocalDate startDate) {
        LocalDate previousMonth = startDate.minusMonths(1);
        if (startDate.getDayOfMonth() == 1) {
            return previousMonth.withDayOfMonth(15).atTime(23, 59, 59);
        }
        return previousMonth.with(TemporalAdjusters.lastDayOfMonth()).atTime(23, 59, 59);
    }

    /**
     * 選択肢にする。表示名は「11/1〜11/15（期限 10/15）」。
     * バイト・店長の選択肢（findOptionsForStaff・findOptionsForManager）以外の画面で使う形。
     */
    public static PeriodOption toOption(ShiftPeriod period) {
        String label = DateTimeUtil.period(period.getStartDate(), period.getEndDate())
                + "（期限 " + DateTimeUtil.shortDate(period.getDeadlineAt().toLocalDate()) + "）";
        return toOption(period, label);
    }

    private static PeriodOption toOption(ShiftPeriod period, String label) {
        return new PeriodOption(period.getId(), period.getStartDate(), period.getEndDate(),
                period.getDeadlineAt(), period.getStatus(), label);
    }

    /** 終了日。開始日が1日なら15日、16日なら月末。 */
    private static LocalDate endDateOf(LocalDate startDate) {
        if (startDate.getDayOfMonth() == 1) {
            return startDate.withDayOfMonth(15);
        }
        return startDate.with(TemporalAdjusters.lastDayOfMonth());
    }
}

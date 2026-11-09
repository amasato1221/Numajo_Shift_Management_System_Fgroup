package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;

/**
 * PeriodService の単体テスト（95_通知・定期処理設計書 6.1・6.2、20-SB-02 2.1、20-SM-06 3.1）。
 *
 * <p>
 * Repository はモックにする。日時は Clock を固定して渡す（97_テスト計画書 3.1）。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   isAcceptingRequests：A＝状態が OPEN、B＝期限を過ぎていない
 *   resolveDefault     ：P＝期間の指定がある、L＝指定が選択肢にある、
 *                        S＝開始日が今日以前、E＝終了日が今日以後、N＝開始日が今日より後
 *   createUpcomingPeriods：X＝同じ開始日の期間がある、D＝開始日が1日、C＝期限の日が今日より前
 *
 * No. | メソッド                | 判定の値                     | 結果
 *  1  | isAcceptingRequests    | A真 B真（期限ちょうど）       | 真
 *  2  | isAcceptingRequests    | A真 B偽（1秒過ぎ）            | 偽
 *  3  | isAcceptingRequests    | A偽 B真                      | 偽
 *  4  | isAcceptingRequests    | A偽 B偽                      | 偽
 *  5  | findOptionsForStaff    | 今日 10/21                    | 7/1〜12/31 で探す、表示名「10/16〜10/31」
 *  6  | findOptionsForStaff    | 今日 12/31（年またぎ・2月末）  | 9/1〜翌2/28 で探す
 *  7  | findOptionsForStaff    | 今日 11/30（月末が31日）       | 8/1〜翌1/31 で探す
 *  8  | toOption               | －                           | 「11/1〜11/15（期限 10/15）」
 *  9  | resolveDefault         | P真 L真                      | 指定の期間
 * 10  | resolveDefault         | P真 L偽                      | ResourceNotFound
 * 11  | resolveDefault         | P真 L偽（選択肢が空）           | ResourceNotFound
 * 12  | resolveDefault         | P偽、選択肢が空               | null
 * 13  | resolveDefault         | P偽、S真E偽→S真E真            | 今日を含む期間
 * 14  | resolveDefault         | P偽、S真E偽→S偽（N真）         | 今日より後で一番近い期間
 * 15  | resolveDefault         | P偽、N偽（後の期間なし）        | 選択肢の最後
 * 16  | getPeriod              | ある                         | 返す
 * 17  | getPeriod              | ない                         | ResourceNotFound
 * 18  | defaultPeriodForManager| 作成中あり                    | 作成中の期間
 * 19  | defaultPeriodForManager| 作成中なし 受付中あり          | 受付中の期間
 * 20  | defaultPeriodForManager| 作成中なし 受付中なし 今日を含む | 今日を含む期間
 * 21  | defaultPeriodForManager| すべてなし                    | null
 * 22  | findOptionsForManager  | 今日 10/21                    | 終了日が 8/20 以降で探す、表示名「8/16〜8/31（公開済み）」
 * 23  | deadlineOf             | D真                          | 前の月の15日 23:59:59
 * 24  | deadlineOf             | D偽                          | 前の月の末日 23:59:59
 * 25  | deadlineOf             | D真（1月、年またぎ）           | 前の年の12/15
 * 26  | deadlineOf             | D偽（うるう年の2月）           | 2/29
 * 27  | createUpcomingPeriods  | X偽×4、今日 10/21             | 4件（95 2.3 の表）、戻り値4
 * 28  | createUpcomingPeriods  | X真×4                        | 作らない、戻り値0
 * 29  | createUpcomingPeriods  | X真×2 X偽×2、今日 11/1        | 12/1・12/16 の2件が OPEN
 * 30  | createUpcomingPeriods  | 年またぎ、今日 12/15           | 1/1（OPEN）・1/16
 * 31  | createUpcomingPeriods  | 今日 1/31                     | 2/1 は CLOSED、2/16 は OPEN で終了日 2/28
 * 32  | createUpcomingPeriods  | うるう年の2月                  | 2/16 の終了日が 2/29
 * 33  | createUpcomingPeriods  | C偽（期限の日＝今日）           | OPEN
 * 34  | createUpcomingPeriods  | C真（期限の日＝今日の前日）      | CLOSED
 * 35  | closeExpiredPeriods    | 期限切れの受付中が1つ           | CLOSED、戻り値1
 * 36  | closeExpiredPeriods    | 期限切れの受付中が2つ           | 両方 CLOSED、戻り値2
 * 37  | closeExpiredPeriods    | 期限切れなし                   | 何も変えない、戻り値0
 *
 * 除外：resolveDefault で S偽かつ E偽（開始日が今日より後で、終了日が今日より前）は、
 * 開始日 ≦ 終了日 なのでありえない。
 * 期限ちょうどで締めない（95 3.2）のは Repository の「Before」で決まる。ここではモックなので、
 * 渡す値の確認（No.34）と、ShiftPeriodRepositoryTests の実DBのテストで確かめる。
 * </pre>
 */
class PeriodServiceTests {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final ShiftPeriodRepository repository = mock(ShiftPeriodRepository.class);

    // ---------- isAcceptingRequests（例1：97_テスト計画書 3.2） ----------

    @Test
    void 受付中で期限ちょうど_受け付ける() {
        PeriodService service = serviceAt("2026-10-31T23:59:59");
        ShiftPeriod period = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-31T23:59:59");

        boolean actual = service.isAcceptingRequests(period);

        assertThat(actual).isTrue();
    }

    @Test
    void 受付中で期限を1秒過ぎた_受け付けない() {
        PeriodService service = serviceAt("2026-11-01T00:00:00");
        ShiftPeriod period = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-31T23:59:59");

        boolean actual = service.isAcceptingRequests(period);

        assertThat(actual).isFalse();
    }

    @Test
    void 作成中で期限前_受け付けない() {
        PeriodService service = serviceAt("2026-10-31T12:00:00");
        ShiftPeriod period = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.CLOSED, "2026-10-31T23:59:59");

        boolean actual = service.isAcceptingRequests(period);

        assertThat(actual).isFalse();
    }

    @Test
    void 公開済みで期限後_受け付けない() {
        PeriodService service = serviceAt("2026-11-01T00:00:00");
        ShiftPeriod period = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.PUBLISHED, "2026-10-31T23:59:59");

        boolean actual = service.isAcceptingRequests(period);

        assertThat(actual).isFalse();
    }

    // ---------- findOptionsForStaff・toOption ----------

    @Test
    void バイトの選択肢で今日が10月21日_7月1日から12月31日の開始日で探す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod period = period(2L, "2026-10-16", "2026-10-31", PeriodStatus.OPEN, "2026-09-30T23:59:59");
        when(repository.findByStartDateBetweenOrderByStartDateAsc(date("2026-07-01"), date("2026-12-31")))
                .thenReturn(List.of(period));

        List<PeriodOption> actual = service.findOptionsForStaff();

        assertThat(actual).extracting(PeriodOption::id).containsExactly(2L);
        assertThat(actual.get(0).label()).isEqualTo("10/16〜10/31");
        assertThat(actual.get(0).status()).isEqualTo(PeriodStatus.OPEN);
        verify(repository).findByStartDateBetweenOrderByStartDateAsc(date("2026-07-01"), date("2026-12-31"));
    }

    @Test
    void バイトの選択肢で今日が12月31日_9月1日から翌年2月28日の開始日で探す() {
        PeriodService service = serviceAt("2026-12-31T10:00:00");

        List<PeriodOption> actual = service.findOptionsForStaff();

        assertThat(actual).isEmpty();
        verify(repository).findByStartDateBetweenOrderByStartDateAsc(date("2026-09-01"), date("2027-02-28"));
    }

    @Test
    void バイトの選択肢で今日が11月30日_8月1日から翌年1月31日の開始日で探す() {
        PeriodService service = serviceAt("2026-11-30T10:00:00");

        service.findOptionsForStaff();

        verify(repository).findByStartDateBetweenOrderByStartDateAsc(date("2026-08-01"), date("2027-01-31"));
    }

    @Test
    void 選択肢にする_表示名は期間と期限になる() {
        ShiftPeriod period = period(5L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");

        PeriodOption actual = PeriodService.toOption(period);

        assertThat(actual.label()).isEqualTo("11/1〜11/15（期限 10/15）");
        assertThat(actual.id()).isEqualTo(5L);
        assertThat(actual.startDate()).isEqualTo(date("2026-11-01"));
        assertThat(actual.endDate()).isEqualTo(date("2026-11-15"));
        assertThat(actual.deadlineAt()).isEqualTo(LocalDateTime.parse("2026-10-15T23:59:59"));
        assertThat(actual.status()).isEqualTo(PeriodStatus.OPEN);
        // 〜Label（30-07 3章）：募集期間・日時・日時（短い）
        assertThat(actual.periodLabel()).isEqualTo("11/1〜11/15");
        assertThat(actual.deadlineLabel()).isEqualTo("2026/10/15 23:59");
        assertThat(actual.deadlineShortLabel()).isEqualTo("10/15 23:59");
    }

    // ---------- resolveDefault ----------

    @Test
    void 期間を指定して選択肢にある_その期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod target = period(3L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");
        when(repository.findById(3L)).thenReturn(Optional.of(target));

        ShiftPeriod actual = service.resolveDefault(List.of(PeriodService.toOption(target)), 3L);

        assertThat(actual).isSameAs(target);
    }

    @Test
    void 期間を指定したが選択肢にない_ResourceNotFoundExceptionになる() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod other = period(3L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");

        assertThatThrownBy(() -> service.resolveDefault(List.of(PeriodService.toOption(other)), 99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void 期間を指定したが選択肢が空_ResourceNotFoundExceptionになる() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");

        assertThatThrownBy(() -> service.resolveDefault(List.of(), 3L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void 指定がなく選択肢も空_nullを返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");

        ShiftPeriod actual = service.resolveDefault(List.of(), null);

        assertThat(actual).isNull();
    }

    @Test
    void 指定がなく今日を含む期間がある_その期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        List<PeriodOption> options = threeOptions();
        ShiftPeriod containing = period(2L, "2026-10-16", "2026-10-31", PeriodStatus.OPEN, "2026-09-30T23:59:59");
        when(repository.findById(2L)).thenReturn(Optional.of(containing));

        ShiftPeriod actual = service.resolveDefault(options, null);

        assertThat(actual).isSameAs(containing);
    }

    @Test
    void 指定がなく今日を含む期間がなく後の期間がある_一番近い後の期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        List<PeriodOption> options = List.of(threeOptions().get(0), threeOptions().get(2));
        ShiftPeriod next = period(3L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");
        when(repository.findById(3L)).thenReturn(Optional.of(next));

        ShiftPeriod actual = service.resolveDefault(options, null);

        assertThat(actual).isSameAs(next);
    }

    @Test
    void 指定がなく今日を含む期間も後の期間もない_選択肢の最後を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        List<PeriodOption> options = List.of(threeOptions().get(0));
        ShiftPeriod last = period(1L, "2026-09-16", "2026-09-30", PeriodStatus.PUBLISHED, "2026-08-31T23:59:59");
        when(repository.findById(1L)).thenReturn(Optional.of(last));

        ShiftPeriod actual = service.resolveDefault(options, null);

        assertThat(actual).isSameAs(last);
    }

    // ---------- getPeriod ----------

    @Test
    void 期間がある_その期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod period = period(7L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");
        when(repository.findById(7L)).thenReturn(Optional.of(period));

        ShiftPeriod actual = service.getPeriod(7L);

        assertThat(actual).isSameAs(period);
    }

    @Test
    void 期間がない_ResourceNotFoundExceptionになる() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPeriod(7L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- defaultPeriodForManager・findOptionsForManager ----------

    @Test
    void 店長の初期の期間で作成中がある_作成中の期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod closed = period(4L, "2026-10-16", "2026-10-31", PeriodStatus.CLOSED, "2026-09-30T23:59:59");
        when(repository.findFirstByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)).thenReturn(Optional.of(closed));

        ShiftPeriod actual = service.defaultPeriodForManager();

        assertThat(actual).isSameAs(closed);
        verify(repository, never()).findFirstByStatusOrderByStartDateAsc(PeriodStatus.OPEN);
        verify(repository, never()).findContaining(any());
    }

    @Test
    void 店長の初期の期間で作成中がなく受付中がある_受付中の期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod open = period(5L, "2026-11-16", "2026-11-30", PeriodStatus.OPEN, "2026-10-31T23:59:59");
        when(repository.findFirstByStatusOrderByStartDateAsc(PeriodStatus.CLOSED)).thenReturn(Optional.empty());
        when(repository.findFirstByStatusOrderByStartDateAsc(PeriodStatus.OPEN)).thenReturn(Optional.of(open));

        ShiftPeriod actual = service.defaultPeriodForManager();

        assertThat(actual).isSameAs(open);
        verify(repository, never()).findContaining(any());
    }

    @Test
    void 店長の初期の期間で作成中も受付中もなく今日を含む期間がある_その期間を返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod published = period(6L, "2026-10-16", "2026-10-31", PeriodStatus.PUBLISHED,
                "2026-09-30T23:59:59");
        when(repository.findContaining(date("2026-10-21"))).thenReturn(Optional.of(published));

        ShiftPeriod actual = service.defaultPeriodForManager();

        assertThat(actual).isSameAs(published);
    }

    @Test
    void 店長の初期の期間でどれもない_nullを返す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");

        ShiftPeriod actual = service.defaultPeriodForManager();

        assertThat(actual).isNull();
    }

    @Test
    void 店長の選択肢で今日が10月21日_終了日が8月20日以降で探す() {
        PeriodService service = serviceAt("2026-10-21T10:00:00");
        ShiftPeriod period = period(8L, "2026-08-16", "2026-08-31", PeriodStatus.PUBLISHED, "2026-07-31T23:59:59");
        when(repository.findByEndDateGreaterThanEqualOrderByStartDateAsc(date("2026-08-20")))
                .thenReturn(List.of(period));

        List<PeriodOption> actual = service.findOptionsForManager();

        assertThat(actual).extracting(PeriodOption::id).containsExactly(8L);
        assertThat(actual.get(0).label()).isEqualTo("8/16〜8/31（公開済み）");
        verify(repository).findByEndDateGreaterThanEqualOrderByStartDateAsc(date("2026-08-20"));
    }

    // ---------- deadlineOf ----------

    @Test
    void 開始日が1日_前の月の15日の23時59分59秒() {
        LocalDateTime actual = PeriodService.deadlineOf(date("2026-11-01"));

        assertThat(actual).isEqualTo(LocalDateTime.parse("2026-10-15T23:59:59"));
    }

    @Test
    void 開始日が16日_前の月の末日の23時59分59秒() {
        LocalDateTime actual = PeriodService.deadlineOf(date("2026-11-16"));

        assertThat(actual).isEqualTo(LocalDateTime.parse("2026-10-31T23:59:59"));
    }

    @Test
    void 開始日が1月1日_前の年の12月15日の23時59分59秒() {
        LocalDateTime actual = PeriodService.deadlineOf(date("2027-01-01"));

        assertThat(actual).isEqualTo(LocalDateTime.parse("2026-12-15T23:59:59"));
    }

    @Test
    void 開始日がうるう年の3月16日_2月29日の23時59分59秒() {
        LocalDateTime actual = PeriodService.deadlineOf(date("2028-03-16"));

        assertThat(actual).isEqualTo(LocalDateTime.parse("2028-02-29T23:59:59"));
    }

    // ---------- createUpcomingPeriods（BT-01。95 6.1） ----------

    @Test
    void 期間が1つもなく今日が10月21日_4件を作り最初の3件は締切済み() {
        PeriodService service = serviceAt("2026-10-21T00:05:00");

        int actual = service.createUpcomingPeriods(date("2026-10-21"));

        assertThat(actual).isEqualTo(4);
        List<ShiftPeriod> saved = savedPeriods(4);
        assertPeriod(saved.get(0), "2026-10-01", "2026-10-15", "2026-09-15T23:59:59", PeriodStatus.CLOSED);
        assertPeriod(saved.get(1), "2026-10-16", "2026-10-31", "2026-09-30T23:59:59", PeriodStatus.CLOSED);
        assertPeriod(saved.get(2), "2026-11-01", "2026-11-15", "2026-10-15T23:59:59", PeriodStatus.CLOSED);
        assertPeriod(saved.get(3), "2026-11-16", "2026-11-30", "2026-10-31T23:59:59", PeriodStatus.OPEN);
        assertThat(saved).allSatisfy(p -> assertThat(p.getPublishedAt()).isNull());
    }

    @Test
    void すべての期間が作成済み_何も作らず0を返す() {
        PeriodService service = serviceAt("2026-10-21T00:05:00");
        when(repository.existsByStartDate(any())).thenReturn(true);

        int actual = service.createUpcomingPeriods(date("2026-10-21"));

        assertThat(actual).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void 今日が11月1日で11月の分はある_12月の2期間が受付中で増える() {
        PeriodService service = serviceAt("2026-11-01T00:05:00");
        when(repository.existsByStartDate(date("2026-11-01"))).thenReturn(true);
        when(repository.existsByStartDate(date("2026-11-16"))).thenReturn(true);

        int actual = service.createUpcomingPeriods(date("2026-11-01"));

        assertThat(actual).isEqualTo(2);
        List<ShiftPeriod> saved = savedPeriods(2);
        assertPeriod(saved.get(0), "2026-12-01", "2026-12-15", "2026-11-15T23:59:59", PeriodStatus.OPEN);
        assertPeriod(saved.get(1), "2026-12-16", "2026-12-31", "2026-11-30T23:59:59", PeriodStatus.OPEN);
    }

    @Test
    void 今日が12月15日で年をまたぐ_翌年1月の2期間を作る() {
        PeriodService service = serviceAt("2026-12-15T00:05:00");
        when(repository.existsByStartDate(date("2026-12-01"))).thenReturn(true);
        when(repository.existsByStartDate(date("2026-12-16"))).thenReturn(true);

        int actual = service.createUpcomingPeriods(date("2026-12-15"));

        assertThat(actual).isEqualTo(2);
        List<ShiftPeriod> saved = savedPeriods(2);
        assertPeriod(saved.get(0), "2027-01-01", "2027-01-15", "2026-12-15T23:59:59", PeriodStatus.OPEN);
        assertPeriod(saved.get(1), "2027-01-16", "2027-01-31", "2026-12-31T23:59:59", PeriodStatus.OPEN);
    }

    @Test
    void 今日が1月31日で1月の分はある_2月1日は締切済みで2月16日の終了日は28日() {
        PeriodService service = serviceAt("2027-01-31T00:05:00");
        when(repository.existsByStartDate(date("2027-01-01"))).thenReturn(true);
        when(repository.existsByStartDate(date("2027-01-16"))).thenReturn(true);

        int actual = service.createUpcomingPeriods(date("2027-01-31"));

        assertThat(actual).isEqualTo(2);
        List<ShiftPeriod> saved = savedPeriods(2);
        assertPeriod(saved.get(0), "2027-02-01", "2027-02-15", "2027-01-15T23:59:59", PeriodStatus.CLOSED);
        assertPeriod(saved.get(1), "2027-02-16", "2027-02-28", "2027-01-31T23:59:59", PeriodStatus.OPEN);
    }

    @Test
    void うるう年の2月_2月16日の終了日は29日() {
        PeriodService service = serviceAt("2028-02-10T00:05:00");
        when(repository.existsByStartDate(date("2028-02-01"))).thenReturn(true);

        int actual = service.createUpcomingPeriods(date("2028-02-10"));

        assertThat(actual).isEqualTo(3);
        List<ShiftPeriod> saved = savedPeriods(3);
        assertPeriod(saved.get(0), "2028-02-16", "2028-02-29", "2028-01-31T23:59:59", PeriodStatus.CLOSED);
    }

    @Test
    void 期限の日が今日と同じ_受付中で作る() {
        PeriodService service = serviceAt("2026-10-15T00:05:00");

        service.createUpcomingPeriods(date("2026-10-15"));

        ShiftPeriod nov1 = savedPeriods(4).get(2);
        assertPeriod(nov1, "2026-11-01", "2026-11-15", "2026-10-15T23:59:59", PeriodStatus.OPEN);
    }

    @Test
    void 期限の日が今日の前日_締切済みで作る() {
        PeriodService service = serviceAt("2026-10-16T00:05:00");

        service.createUpcomingPeriods(date("2026-10-16"));

        ShiftPeriod nov1 = savedPeriods(4).get(2);
        assertPeriod(nov1, "2026-11-01", "2026-11-15", "2026-10-15T23:59:59", PeriodStatus.CLOSED);
    }

    // ---------- closeExpiredPeriods（BT-02。95 6.2） ----------

    @Test
    void 期限切れの受付中が1つ_締切済みにして1を返す() {
        PeriodService service = serviceAt("2026-11-01T00:00:30");
        ShiftPeriod expired = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-31T23:59:59");
        LocalDateTime now = LocalDateTime.parse("2026-11-01T00:00:30");
        when(repository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, now)).thenReturn(List.of(expired));

        int actual = service.closeExpiredPeriods(now);

        assertThat(actual).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(PeriodStatus.CLOSED);
        verify(repository).findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, now);
    }

    @Test
    void 期限切れの受付中が2つ_両方を締切済みにして2を返す() {
        PeriodService service = serviceAt("2026-11-17T00:00:30");
        ShiftPeriod first = period(1L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN, "2026-10-15T23:59:59");
        ShiftPeriod second = period(2L, "2026-11-16", "2026-11-30", PeriodStatus.OPEN, "2026-10-31T23:59:59");
        LocalDateTime now = LocalDateTime.parse("2026-11-17T00:00:30");
        when(repository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, now)).thenReturn(List.of(first, second));

        int actual = service.closeExpiredPeriods(now);

        assertThat(actual).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(second.getStatus()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    void 期限切れの受付中がない_何も変えず0を返す() {
        PeriodService service = serviceAt("2026-10-31T23:59:59");
        LocalDateTime now = LocalDateTime.parse("2026-10-31T23:59:59");

        int actual = service.closeExpiredPeriods(now);

        assertThat(actual).isZero();
        verify(repository).findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, now);
    }

    // ---------- 補助 ----------

    /** 指定の日時（日本時間）で固定した Clock を持つ PeriodService を作る。 */
    private PeriodService serviceAt(String dateTime) {
        Clock clock = Clock.fixed(LocalDateTime.parse(dateTime).atZone(TOKYO).toInstant(), TOKYO);
        return new PeriodService(repository, clock);
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static ShiftPeriod period(Long id, String start, String end, PeriodStatus status, String deadline) {
        ShiftPeriod period = new ShiftPeriod();
        period.setId(id);
        period.setStartDate(date(start));
        period.setEndDate(date(end));
        period.setStatus(status);
        period.setDeadlineAt(LocalDateTime.parse(deadline));
        return period;
    }

    /** 終わった期間・今日を含む期間・これから始まる期間の3つ（今日は 2026-10-21）。 */
    private static List<PeriodOption> threeOptions() {
        return List.of(
                PeriodService.toOption(period(1L, "2026-09-16", "2026-09-30", PeriodStatus.PUBLISHED,
                        "2026-08-31T23:59:59")),
                PeriodService.toOption(period(2L, "2026-10-16", "2026-10-31", PeriodStatus.OPEN,
                        "2026-09-30T23:59:59")),
                PeriodService.toOption(period(3L, "2026-11-01", "2026-11-15", PeriodStatus.OPEN,
                        "2026-10-15T23:59:59")));
    }

    /** save に渡された期間を、渡された順に取り出す。 */
    private List<ShiftPeriod> savedPeriods(int times) {
        ArgumentCaptor<ShiftPeriod> captor = ArgumentCaptor.forClass(ShiftPeriod.class);
        verify(repository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    private static void assertPeriod(ShiftPeriod actual, String start, String end, String deadline,
            PeriodStatus status) {
        assertThat(actual.getStartDate()).isEqualTo(date(start));
        assertThat(actual.getEndDate()).isEqualTo(date(end));
        assertThat(actual.getDeadlineAt()).isEqualTo(LocalDateTime.parse(deadline));
        assertThat(actual.getStatus()).isEqualTo(status);
    }
}

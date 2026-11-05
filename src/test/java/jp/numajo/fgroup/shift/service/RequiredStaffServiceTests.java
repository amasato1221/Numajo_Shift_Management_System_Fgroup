package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.RequiredDayView;
import jp.numajo.fgroup.shift.entity.RequiredStaffDefault;
import jp.numajo.fgroup.shift.entity.RequiredStaffOverride;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffDefaultId;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffOverrideId;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.RequiredDefaultForm;
import jp.numajo.fgroup.shift.repository.RequiredStaffDefaultRepository;
import jp.numajo.fgroup.shift.repository.RequiredStaffOverrideRepository;

/**
 * RequiredStaffService の単体テスト（20-SM-05 6章 No.2〜6、97_テスト計画書 4.1）。
 *
 * <p>
 * 2026年11月は 11/1 が日曜日、11/7 が土曜日。
 * </p>
 *
 * <pre>
 * 判定ごとのテスト（判定条件網羅）
 * No. | メソッド・判定                                    | 真                         | 偽
 *  1  | getDefaultForm：slotId == DAY                     | 日勤の列に入る             | 夕勤の列に入る
 *  2  | getMonth：for（月末を過ぎていない）               | 30日ぶん作る               | ループを抜ける
 *  3  | saveDefaults：for（i < 7）                         | 2行ずつ作る                | 14行で抜ける
 *  4  | saveOverride：slotId が 1・2 以外（下の表）        | E2504                      | 保存する
 *  5  | deleteOverride：row.isEmpty()                     | 何もしない（ログなし）     | 消してログを残す
 *  6  | requiredCount：上書きがある（Optional）            | 上書きの値                 | 既定値を見る
 *  7  | requiredCount：既定値がある（Optional）            | 既定値                     | 0
 *  8  | requiredCounts：for（to を過ぎていない）           | 日ごとに作る               | 抜ける（from＞to は空）
 *  9  | slotCode：slotId == DAY                           | DAY                        | EVENING
 * 10  | resolve：override != null                         | 上書きの値                 | 既定値（なければ 0）
 *
 * 組み合わせ表：saveOverride の枠のチェック（条件網羅・複数条件網羅）
 * 判定：slotId == null || (slotId != DAY &amp;&amp; slotId != EVENING)（A：null、B：1でない、C：2でない）
 * No. | A  | B  | C  | 判定 | テストの値
 *  1  | 真 | － | － | 真   | null → E2504
 *  2  | 偽 | 真 | 真 | 真   | 3 → E2504（20-SM-05 6章 No.6）、0 → E2504
 *  3  | 偽 | 真 | 偽 | 偽   | 2 → 保存
 *  4  | 偽 | 偽 | 真 | 偽   | 1 → 保存
 * 除外：A が真のとき B・C は評価されない（短絡評価。null は 1 とも 2 とも比べられない）。
 *       B・C がともに偽（1 でも 2 でもある）はありえない。
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class RequiredStaffServiceTests {

    private static final Long OPERATOR = 9L;
    /** 2026-11-03 は火曜日（曜日＝2） */
    private static final LocalDate TUE = LocalDate.of(2026, 11, 3);
    /** 2026-11-01 は日曜日（曜日＝0） */
    private static final LocalDate SUN = LocalDate.of(2026, 11, 1);

    @Mock
    private RequiredStaffDefaultRepository requiredStaffDefaultRepository;

    @Mock
    private RequiredStaffOverrideRepository requiredStaffOverrideRepository;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private RequiredStaffService requiredStaffService;

    private static RequiredStaffDefault def(int dayOfWeek, byte slotId, int count) {
        RequiredStaffDefault d = new RequiredStaffDefault();
        d.setId(new RequiredStaffDefaultId((byte) dayOfWeek, slotId));
        d.setRequiredCount(count);
        return d;
    }

    private static RequiredStaffOverride ovr(LocalDate date, byte slotId, int count) {
        RequiredStaffOverride o = new RequiredStaffOverride();
        o.setId(new RequiredStaffOverrideId(date, slotId));
        o.setRequiredCount(count);
        return o;
    }

    /** 14行すべての既定値（日勤＝曜日＋1、夕勤＝曜日＋10） */
    private static List<RequiredStaffDefault> allDefaults() {
        List<RequiredStaffDefault> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(def(i, SlotIds.DAY, i + 1));
            rows.add(def(i, SlotIds.EVENING, i + 10));
        }
        return rows;
    }

    // ---------------------------------------------------------------- 曜日の計算

    @ParameterizedTest
    @CsvSource({ "2026-11-01, 0", "2026-11-02, 1", "2026-11-06, 5", "2026-11-07, 6" })
    void 曜日を数える_日曜は0で土曜は6(LocalDate date, int expected) {
        assertThat(RequiredStaffService.dayOfWeek(date)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------- getDefaultForm（判定 No.1）

    @Test
    void 既定値が14行ある_日勤と夕勤の列に曜日の順で入る() {
        when(requiredStaffDefaultRepository.findAll()).thenReturn(allDefaults());

        RequiredDefaultForm form = requiredStaffService.getDefaultForm();

        assertThat(form.getDayCounts()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(form.getEveningCounts()).containsExactly(10, 11, 12, 13, 14, 15, 16);
    }

    @Test
    void 既定値の行がない_すべて0になる() {
        when(requiredStaffDefaultRepository.findAll()).thenReturn(List.of());

        RequiredDefaultForm form = requiredStaffService.getDefaultForm();

        assertThat(form.getDayCounts()).containsExactly(0, 0, 0, 0, 0, 0, 0);
        assertThat(form.getEveningCounts()).containsExactly(0, 0, 0, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- getMonth（判定 No.2・10）

    @Test
    void 月の一覧を作る_上書きのある値だけ上書きの印が付き残りは曜日の既定値() {
        YearMonth ym = YearMonth.of(2026, 11);
        when(requiredStaffDefaultRepository.findAll()).thenReturn(allDefaults());
        when(requiredStaffOverrideRepository.findByIdWorkDateBetween(SUN, LocalDate.of(2026, 11, 30)))
                .thenReturn(List.of(ovr(TUE, SlotIds.DAY, 0)));

        List<RequiredDayView> days = requiredStaffService.getMonth(ym);

        assertThat(days).hasSize(30);
        assertThat(days.get(0)).isEqualTo(new RequiredDayView(SUN, 1, false, 10, false));
        // 6章 No.3：上書きを0人にすると 0（既定値の 3 は使わない）
        assertThat(days.get(2)).isEqualTo(new RequiredDayView(TUE, 0, true, 12, false));
        assertThat(days.get(29).workDate()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void 月の一覧で夕勤だけ上書きがある_夕勤だけに印が付く() {
        when(requiredStaffDefaultRepository.findAll()).thenReturn(List.of());
        when(requiredStaffOverrideRepository.findByIdWorkDateBetween(any(), any()))
                .thenReturn(List.of(ovr(TUE, SlotIds.EVENING, 5)));

        List<RequiredDayView> days = requiredStaffService.getMonth(YearMonth.of(2026, 11));

        assertThat(days.get(2)).isEqualTo(new RequiredDayView(TUE, 0, false, 5, true));
        assertThat(days.get(3)).isEqualTo(new RequiredDayView(TUE.plusDays(1), 0, false, 0, false));
    }

    @Test
    void うるう年の2月_29日ぶん作る() {
        when(requiredStaffDefaultRepository.findAll()).thenReturn(List.of());
        when(requiredStaffOverrideRepository.findByIdWorkDateBetween(any(), any())).thenReturn(List.of());

        assertThat(requiredStaffService.getMonth(YearMonth.of(2028, 2))).hasSize(29);
    }

    // ---------------------------------------------------------------- saveDefaults（判定 No.3）

    @Test
    @SuppressWarnings("unchecked")
    void 既定値を保存する_14行を保存し操作ログを1件残す() {
        List<Integer> day = List.of(0, 1, 2, 3, 4, 5, 20);
        List<Integer> evening = List.of(20, 19, 18, 17, 16, 15, 0);

        requiredStaffService.saveDefaults(day, evening, OPERATOR);

        ArgumentCaptor<List<RequiredStaffDefault>> captor = ArgumentCaptor.forClass(List.class);
        verify(requiredStaffDefaultRepository).saveAll(captor.capture());
        List<RequiredStaffDefault> rows = captor.getValue();
        assertThat(rows).hasSize(14);
        assertThat(rows.get(0).getId()).isEqualTo(new RequiredStaffDefaultId((byte) 0, SlotIds.DAY));
        assertThat(rows.get(0).getRequiredCount()).isZero();
        assertThat(rows.get(1).getId()).isEqualTo(new RequiredStaffDefaultId((byte) 0, SlotIds.EVENING));
        assertThat(rows.get(1).getRequiredCount()).isEqualTo(20);
        assertThat(rows.get(12).getId()).isEqualTo(new RequiredStaffDefaultId((byte) 6, SlotIds.DAY));
        assertThat(rows.get(12).getRequiredCount()).isEqualTo(20);
        assertThat(rows.get(13).getRequiredCount()).isZero();
        verify(operationLogService).record(OPERATOR, OperationAction.REQUIRED_UPDATE, null, "defaults");
    }

    // ---------------------------------------------------------------- saveOverride（判定 No.4・9、組み合わせ表）

    @Test
    void 枠がnull_E2504で保存しない() {
        assertThatThrownBy(() -> requiredStaffService.saveOverride(TUE, null, 3, OPERATOR))
                .isInstanceOf(BusinessException.class).extracting("messageCode").isEqualTo("E2504");

        verify(requiredStaffOverrideRepository, never()).save(any());
        verify(operationLogService, never()).record(any(), any(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({ "3", "0" })
    void 枠が1と2以外_E2504で保存しない(byte slotId) {
        assertThatThrownBy(() -> requiredStaffService.saveOverride(TUE, slotId, 3, OPERATOR))
                .isInstanceOf(BusinessException.class).extracting("messageCode").isEqualTo("E2504");

        verify(requiredStaffOverrideRepository, never()).save(any());
        verify(operationLogService, never()).record(any(), any(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({ "1, DAY, 3", "2, EVENING, 0" })
    void 枠が日勤か夕勤_上書きを保存し操作ログを残す(byte slotId, String code, int count) {
        requiredStaffService.saveOverride(TUE, slotId, count, OPERATOR);

        ArgumentCaptor<RequiredStaffOverride> captor = ArgumentCaptor.forClass(RequiredStaffOverride.class);
        verify(requiredStaffOverrideRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(new RequiredStaffOverrideId(TUE, slotId));
        assertThat(captor.getValue().getRequiredCount()).isEqualTo(count);
        verify(operationLogService).record(OPERATOR, OperationAction.REQUIRED_UPDATE, null,
                "override 2026-11-03 " + code + "=" + count);
    }

    // ---------------------------------------------------------------- deleteOverride（判定 No.5）

    @Test
    void 上書きがある_消して操作ログを残す() {
        RequiredStaffOverride row = ovr(TUE, SlotIds.DAY, 3);
        when(requiredStaffOverrideRepository.findById(new RequiredStaffOverrideId(TUE, SlotIds.DAY)))
                .thenReturn(Optional.of(row));

        requiredStaffService.deleteOverride(TUE, SlotIds.DAY, OPERATOR);

        verify(requiredStaffOverrideRepository).delete(row);
        verify(operationLogService).record(OPERATOR, OperationAction.REQUIRED_UPDATE, null,
                "override delete 2026-11-03 DAY");
    }

    @Test
    void 上書きがない_何も消さず操作ログも残さない() {
        when(requiredStaffOverrideRepository.findById(any())).thenReturn(Optional.empty());

        requiredStaffService.deleteOverride(TUE, SlotIds.EVENING, OPERATOR);

        verify(requiredStaffOverrideRepository, never()).delete(any());
        verify(operationLogService, never()).record(any(), any(), isNull(), anyString());
    }

    // ---------------------------------------------------------------- requiredCount（判定 No.6・7）

    @Test
    void 上書きがある_上書きの値で曜日の既定値は見ない() {
        when(requiredStaffOverrideRepository.findById(new RequiredStaffOverrideId(TUE, SlotIds.DAY)))
                .thenReturn(Optional.of(ovr(TUE, SlotIds.DAY, 7)));

        assertThat(requiredStaffService.requiredCount(TUE, SlotIds.DAY)).isEqualTo(7);
        verify(requiredStaffDefaultRepository, never()).findById(any());
    }

    @Test
    void 上書きがなく既定値がある_曜日の既定値() {
        when(requiredStaffOverrideRepository.findById(any())).thenReturn(Optional.empty());
        when(requiredStaffDefaultRepository.findById(new RequiredStaffDefaultId((byte) 2, SlotIds.EVENING)))
                .thenReturn(Optional.of(def(2, SlotIds.EVENING, 4)));

        assertThat(requiredStaffService.requiredCount(TUE, SlotIds.EVENING)).isEqualTo(4);
    }

    @Test
    void 日曜日で上書きがない_曜日0の既定値を使う() {
        when(requiredStaffOverrideRepository.findById(any())).thenReturn(Optional.empty());
        when(requiredStaffDefaultRepository.findById(new RequiredStaffDefaultId((byte) 0, SlotIds.DAY)))
                .thenReturn(Optional.of(def(0, SlotIds.DAY, 6)));

        assertThat(requiredStaffService.requiredCount(SUN, SlotIds.DAY)).isEqualTo(6);
    }

    @Test
    void 上書きも既定値もない_0() {
        when(requiredStaffOverrideRepository.findById(any())).thenReturn(Optional.empty());
        when(requiredStaffDefaultRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(requiredStaffService.requiredCount(TUE, SlotIds.DAY)).isZero();
    }

    // ---------------------------------------------------------------- requiredCounts（判定 No.8・10）

    @Test
    void 期間の必要人数_上書き既定値0の順で決まり日付の順に並ぶ() {
        LocalDate from = LocalDate.of(2026, 10, 31);
        LocalDate to = LocalDate.of(2026, 11, 3);
        // 既定値は土（6）の日勤と日（0）の夕勤だけ
        when(requiredStaffDefaultRepository.findAll())
                .thenReturn(List.of(def(6, SlotIds.DAY, 2), def(0, SlotIds.EVENING, 3)));
        when(requiredStaffOverrideRepository.findByIdWorkDateBetween(from, to))
                .thenReturn(List.of(ovr(TUE, SlotIds.DAY, 5), ovr(SUN, SlotIds.EVENING, 0)));

        Map<LocalDate, Map<Byte, Integer>> counts = requiredStaffService.requiredCounts(from, to);

        assertThat(counts).containsOnlyKeys(from, SUN, SUN.plusDays(1), TUE);
        assertThat(counts.keySet()).containsExactly(from, SUN, SUN.plusDays(1), TUE);
        assertThat(counts.get(from)).containsEntry(SlotIds.DAY, 2).containsEntry(SlotIds.EVENING, 0).hasSize(2);
        // 日曜の夕勤：既定値 3 より上書きの 0 を使う
        assertThat(counts.get(SUN)).containsEntry(SlotIds.DAY, 0).containsEntry(SlotIds.EVENING, 0);
        assertThat(counts.get(SUN.plusDays(1))).containsEntry(SlotIds.DAY, 0).containsEntry(SlotIds.EVENING, 0);
        assertThat(counts.get(TUE)).containsEntry(SlotIds.DAY, 5).containsEntry(SlotIds.EVENING, 0);
    }

    @Test
    void 開始日が終了日より後_空() {
        when(requiredStaffDefaultRepository.findAll()).thenReturn(List.of());
        when(requiredStaffOverrideRepository.findByIdWorkDateBetween(eq(TUE), eq(SUN))).thenReturn(List.of());

        assertThat(requiredStaffService.requiredCounts(TUE, SUN)).isEmpty();
    }
}

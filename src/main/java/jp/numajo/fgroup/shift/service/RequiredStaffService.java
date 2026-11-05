package jp.numajo.fgroup.shift.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import lombok.RequiredArgsConstructor;

/**
 * 必要人数（SM-05。SM-06・SM-01・SM-08 からも使う）。20-SM-05 2章、30-05 3.4。
 *
 * <p>
 * 必要人数は「日付ごとの上書きがあればその値、なければ曜日の既定値、それもなければ 0」で決める（20-SM-05 2.5）。
 * 曜日は 0＝日〜6＝土（{@code DayOfWeek#getValue() % 7}）。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequiredStaffService {

    /** 1週間の日数（既定値の行の数は 7曜日×2枠＝14行） */
    static final int DAYS_IN_WEEK = 7;

    private final RequiredStaffDefaultRepository requiredStaffDefaultRepository;
    private final RequiredStaffOverrideRepository requiredStaffOverrideRepository;
    private final OperationLogService operationLogService;

    /**
     * 曜日ごとの既定値をフォームにする（20-SM-05 2.1-2）。行がない曜日・枠は 0。
     *
     * @return 日勤・夕勤それぞれ7個（0＝日〜6＝土）の値を入れたフォーム
     */
    public RequiredDefaultForm getDefaultForm() {
        List<Integer> dayCounts = new ArrayList<>(Collections.nCopies(DAYS_IN_WEEK, 0));
        List<Integer> eveningCounts = new ArrayList<>(Collections.nCopies(DAYS_IN_WEEK, 0));
        for (RequiredStaffDefault d : requiredStaffDefaultRepository.findAll()) {
            List<Integer> target = d.getId().getSlotId() == SlotIds.DAY ? dayCounts : eveningCounts;
            target.set(d.getId().getDayOfWeek(), d.getRequiredCount());
        }
        RequiredDefaultForm form = new RequiredDefaultForm();
        form.setDayCounts(dayCounts);
        form.setEveningCounts(eveningCounts);
        return form;
    }

    /**
     * その月の日ごとの必要人数と、上書きかどうか（20-SM-05 2.1-3）。SQLは2回。
     *
     * @param ym 年月
     * @return 1日から月末までの行（日付の順）
     */
    public List<RequiredDayView> getMonth(YearMonth ym) {
        Map<Integer, Map<Byte, Integer>> defaults = loadDefaults();
        Map<LocalDate, Map<Byte, Integer>> overrides = loadOverrides(ym.atDay(1), ym.atEndOfMonth());
        List<RequiredDayView> days = new ArrayList<>();
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            Map<Byte, Integer> dayOverrides = overrides.getOrDefault(d, Map.of());
            days.add(new RequiredDayView(d,
                    resolve(d, SlotIds.DAY, defaults, overrides), dayOverrides.containsKey(SlotIds.DAY),
                    resolve(d, SlotIds.EVENING, defaults, overrides), dayOverrides.containsKey(SlotIds.EVENING)));
        }
        return days;
    }

    /**
     * 曜日ごとの既定値を保存する（20-SM-05 2.2）。14行を UPSERT する。
     *
     * @param dayCounts     日勤の必要人数（7個、0＝日〜6＝土）
     * @param eveningCounts 夕勤の必要人数（7個、0＝日〜6＝土）
     * @param operatorId    操作した店長のユーザーID
     */
    @Transactional
    public void saveDefaults(List<Integer> dayCounts, List<Integer> eveningCounts, Long operatorId) {
        List<RequiredStaffDefault> rows = new ArrayList<>();
        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            rows.add(newDefault(i, SlotIds.DAY, dayCounts.get(i)));
            rows.add(newDefault(i, SlotIds.EVENING, eveningCounts.get(i)));
        }
        requiredStaffDefaultRepository.saveAll(rows);
        operationLogService.record(operatorId, OperationAction.REQUIRED_UPDATE, null, "defaults");
    }

    /**
     * 日付ごとの上書きを保存する（20-SM-05 2.3-2）。(work_date, slot_id) で UPSERT する。
     *
     * @param workDate   日付
     * @param slotId     枠ID（1・2）
     * @param count      必要人数
     * @param operatorId 操作した店長のユーザーID
     * @throws BusinessException E2504（枠が日勤・夕勤以外）
     */
    @Transactional
    public void saveOverride(LocalDate workDate, Byte slotId, int count, Long operatorId) {
        if (slotId == null || (slotId != SlotIds.DAY && slotId != SlotIds.EVENING)) {
            throw new BusinessException("E2504");
        }
        RequiredStaffOverride row = new RequiredStaffOverride();
        row.setId(new RequiredStaffOverrideId(workDate, slotId));
        row.setRequiredCount(count);
        requiredStaffOverrideRepository.save(row);
        operationLogService.record(operatorId, OperationAction.REQUIRED_UPDATE, null,
                "override " + workDate + " " + slotCode(slotId) + "=" + count);
    }

    /**
     * 日付ごとの上書きを消して、曜日の既定値に戻す（20-SM-05 2.4）。行がなければ何もしない（操作ログも残さない）。
     *
     * @param workDate   日付
     * @param slotId     枠ID
     * @param operatorId 操作した店長のユーザーID
     */
    @Transactional
    public void deleteOverride(LocalDate workDate, Byte slotId, Long operatorId) {
        Optional<RequiredStaffOverride> row =
                requiredStaffOverrideRepository.findById(new RequiredStaffOverrideId(workDate, slotId));
        if (row.isEmpty()) {
            return;
        }
        requiredStaffOverrideRepository.delete(row.get());
        operationLogService.record(operatorId, OperationAction.REQUIRED_UPDATE, null,
                "override delete " + workDate + " " + slotCode(slotId));
    }

    /**
     * 1つの日付・枠の必要人数（20-SM-05 2.5）。
     *
     * @param workDate 日付
     * @param slotId   枠ID
     * @return 上書き → 曜日の既定値 → 0 の順で見つかった値
     */
    public int requiredCount(LocalDate workDate, Byte slotId) {
        return requiredStaffOverrideRepository.findById(new RequiredStaffOverrideId(workDate, slotId))
                .map(RequiredStaffOverride::getRequiredCount)
                .orElseGet(() -> requiredStaffDefaultRepository
                        .findById(new RequiredStaffDefaultId((byte) dayOfWeek(workDate), slotId))
                        .map(RequiredStaffDefault::getRequiredCount)
                        .orElse(0));
    }

    /**
     * 期間の日ごと・枠ごとの必要人数（20-SM-05 2.5。SM-06 などで使う）。SQLは2回。
     *
     * @param from 開始日
     * @param to   終了日（この日を含む）
     * @return 日付 →（枠ID → 必要人数）。日付の順。from が to より後なら空
     */
    public Map<LocalDate, Map<Byte, Integer>> requiredCounts(LocalDate from, LocalDate to) {
        Map<Integer, Map<Byte, Integer>> defaults = loadDefaults();
        Map<LocalDate, Map<Byte, Integer>> overrides = loadOverrides(from, to);
        Map<LocalDate, Map<Byte, Integer>> result = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<Byte, Integer> counts = new LinkedHashMap<>();
            counts.put(SlotIds.DAY, resolve(d, SlotIds.DAY, defaults, overrides));
            counts.put(SlotIds.EVENING, resolve(d, SlotIds.EVENING, defaults, overrides));
            result.put(d, counts);
        }
        return result;
    }

    /** 曜日（0＝日〜6＝土）。 */
    static int dayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % DAYS_IN_WEEK;
    }

    /** 操作ログの detail に書く枠の名前（1 → DAY、それ以外 → EVENING）。 */
    private static String slotCode(Byte slotId) {
        return slotId == SlotIds.DAY ? "DAY" : "EVENING";
    }

    /** 曜日の既定値をすべて読む：曜日 →（枠ID → 人数）。 */
    private Map<Integer, Map<Byte, Integer>> loadDefaults() {
        Map<Integer, Map<Byte, Integer>> defaults = new HashMap<>();
        for (RequiredStaffDefault d : requiredStaffDefaultRepository.findAll()) {
            defaults.computeIfAbsent((int) d.getId().getDayOfWeek(), k -> new HashMap<>())
                    .put(d.getId().getSlotId(), d.getRequiredCount());
        }
        return defaults;
    }

    /** 期間の上書きを読む：日付 →（枠ID → 人数）。 */
    private Map<LocalDate, Map<Byte, Integer>> loadOverrides(LocalDate from, LocalDate to) {
        Map<LocalDate, Map<Byte, Integer>> overrides = new HashMap<>();
        for (RequiredStaffOverride o : requiredStaffOverrideRepository.findByIdWorkDateBetween(from, to)) {
            overrides.computeIfAbsent(o.getId().getWorkDate(), k -> new HashMap<>())
                    .put(o.getId().getSlotId(), o.getRequiredCount());
        }
        return overrides;
    }

    /** 読み込んだ値から、上書き → 曜日の既定値 → 0 の順で決める。 */
    private static int resolve(LocalDate date, Byte slotId, Map<Integer, Map<Byte, Integer>> defaults,
            Map<LocalDate, Map<Byte, Integer>> overrides) {
        Integer override = overrides.getOrDefault(date, Map.of()).get(slotId);
        if (override != null) {
            return override;
        }
        return defaults.getOrDefault(dayOfWeek(date), Map.of()).getOrDefault(slotId, 0);
    }

    /** 既定値の行を作る。 */
    private static RequiredStaffDefault newDefault(int dayOfWeek, byte slotId, int count) {
        RequiredStaffDefault row = new RequiredStaffDefault();
        row.setId(new RequiredStaffDefaultId((byte) dayOfWeek, slotId));
        row.setRequiredCount(count);
        return row;
    }
}

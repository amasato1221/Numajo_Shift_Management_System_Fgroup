package jp.numajo.fgroup.shift.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.Availability;
import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.constant.RequestMode;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.constant.SubmissionState;
import jp.numajo.fgroup.shift.dto.PeriodOption;
import jp.numajo.fgroup.shift.dto.RequestDayRow;
import jp.numajo.fgroup.shift.dto.RequestPageView;
import jp.numajo.fgroup.shift.entity.RequestSubmission;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import jp.numajo.fgroup.shift.entity.ShiftRequest;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.entity.id.RequestSubmissionId;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.DayRequestForm;
import jp.numajo.fgroup.shift.form.ShiftRequestForm;
import jp.numajo.fgroup.shift.repository.RequestSubmissionRepository;
import jp.numajo.fgroup.shift.repository.ShiftPeriodRepository;
import jp.numajo.fgroup.shift.repository.ShiftRequestRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;
import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.util.DateTimeUtil;
import jp.numajo.fgroup.shift.util.ShiftTimeUtil;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望の提出（SB-03）の業務処理。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftRequestService {

    private final ShiftPeriodRepository shiftPeriodRepository;
    private final ShiftRequestRepository shiftRequestRepository;
    private final RequestSubmissionRepository requestSubmissionRepository;
    private final UserRepository userRepository;
    private final SlotRepository slotRepository;
    private final PeriodService periodService;
    private final Clock clock;

    /**
     * 提出画面の表示用の値を作る（20-SB-03 3.1）。
     *
     * @param userId   ログイン中のバイトのユーザーID
     * @param periodId 期間のID。null なら、受付中で期限が一番早い期間
     * @return 表示用の値。表示する期間がなければ null
     * @throws ResourceNotFoundException 指定した期間がないとき
     */
    public RequestPageView getRequestPage(Long userId, Long periodId) {
        ShiftPeriod period = periodId != null ? periodService.getPeriod(periodId) : findDefaultPeriod();
        if (period == null) {
            return null;
        }
        User user = findUser(userId);
        boolean editable = periodService.isAcceptingRequests(period);
        LocalDateTime submittedAt = requestSubmissionRepository
                .findById(new RequestSubmissionId(period.getId(), userId))
                .map(RequestSubmission::getSubmittedAt)
                .orElse(null);
        SubmissionState state = toState(editable, submittedAt);
        Set<Byte> qualified = qualifiedSlotIds(userId);
        List<RequestDayRow> days = datesOf(period).stream()
                .map(date -> new RequestDayRow(date, DateTimeUtil.monthDay(date), qualified.contains(SlotIds.DAY),
                        qualified.contains(SlotIds.EVENING)))
                .toList();

        return new RequestPageView(PeriodService.toOption(period), findOptions(period),
                DateTimeUtil.dateTime(period.getDeadlineAt()), editable, submittedAt, labelOf(submittedAt), state,
                state.getLabel(), days, user.getName());
    }

    /**
     * 今の希望から、入力表の初期値のフォームを作る（20-SB-03 3.1-5）。
     *
     * @param userId ユーザーID
     * @param period 期間
     * @return 期間の全日付を並べたフォーム
     */
    public ShiftRequestForm toForm(Long userId, ShiftPeriod period) {
        Map<LocalDate, List<ShiftRequest>> byDate = shiftRequestRepository
                .findByPeriodIdAndUserId(period.getId(), userId).stream()
                .collect(Collectors.groupingBy(ShiftRequest::getWorkDate));

        ShiftRequestForm form = new ShiftRequestForm();
        form.setPeriodId(period.getId());
        for (LocalDate date : datesOf(period)) {
            form.getDays().add(toDayForm(date, byDate.getOrDefault(date, List.of())));
        }
        return form;
    }

    /**
     * 希望を提出する（20-SB-03 3.2）。提出し直すと、前の希望は全部消えて入れ替わる。
     *
     * @param userId ユーザーID
     * @param form   入力した値
     * @throws ResourceNotFoundException 期間がないとき
     * @throws BusinessException         受付終了（E1303）、入力の誤り（E1302、E1304〜E1308）
     */
    @Transactional
    public void submit(Long userId, ShiftRequestForm form) {
        ShiftPeriod period = periodService.getPeriod(form.getPeriodId());
        if (!periodService.isAcceptingRequests(period)) {
            throw new BusinessException("E1303");
        }
        validateDays(period, form.getDays(), qualifiedSlotIds(userId));

        replaceRequests(period, userId, form.getDays());

        RequestSubmissionId id = new RequestSubmissionId(period.getId(), userId);
        RequestSubmission submission = requestSubmissionRepository.findById(id).orElseGet(() -> {
            RequestSubmission created = new RequestSubmission();
            created.setId(id);
            return created;
        });
        submission.setSubmittedAt(LocalDateTime.now(clock));
        requestSubmissionRepository.save(submission);
    }

    /**
     * 提出を取り消す（20-SB-03 3.3）。希望と提出状況を消す。
     *
     * @param userId   ユーザーID
     * @param periodId 期間のID
     * @throws ResourceNotFoundException 期間がないとき
     * @throws BusinessException         受付終了（E1303）
     */
    @Transactional
    public void cancel(Long userId, Long periodId) {
        ShiftPeriod period = periodService.getPeriod(periodId);
        if (!periodService.isAcceptingRequests(period)) {
            throw new BusinessException("E1303");
        }
        shiftRequestRepository.deleteByPeriodIdAndUserId(period.getId(), userId);
        requestSubmissionRepository.deleteByPeriodIdAndUserId(period.getId(), userId);
    }

    /**
     * ユーザーが入れる枠のIDを返す。ユーザーの職能が、枠の必要職能をすべて含む枠（必要職能がない枠は誰でも可）。
     *
     * @param userId ユーザーID
     * @return 入れる枠のID
     */
    public Set<Byte> qualifiedSlotIds(Long userId) {
        Set<Long> ownSkillIds = findUser(userId).getSkills().stream()
                .map(Skill::getId)
                .collect(Collectors.toSet());

        Set<Byte> qualified = new HashSet<>();
        for (Slot slot : slotRepository.findAllWithRequiredSkills()) {
            Set<Long> requiredIds = slot.getRequiredSkills().stream()
                    .map(Skill::getId)
                    .collect(Collectors.toSet());
            if (ownSkillIds.containsAll(requiredIds)) {
                qualified.add(slot.getId());
            }
        }
        return qualified;
    }

    /**
     * 日ごとの入力を確かめる（20-SB-03 3.2-2-3〜2-4）。最初に見つかった誤りで止める。
     *
     * @param period    期間
     * @param days      日ごとの入力
     * @param qualified 入れる枠のID
     * @throws BusinessException E1302、E1304〜E1308
     */
    public void validateDays(ShiftPeriod period, List<DayRequestForm> days, Set<Byte> qualified) {
        checkDates(period, days);
        List<Slot> slots = slotRepository.findAllWithRequiredSkills().stream()
                .sorted(Comparator.comparing(Slot::getId))
                .toList();

        List<DayRequestForm> sorted = days.stream()
                .sorted(Comparator.comparing(DayRequestForm::getWorkDate))
                .toList();
        for (DayRequestForm day : sorted) {
            String dateLabel = DateTimeUtil.monthDay(day.getWorkDate());
            if (day.getMode() == RequestMode.TIME) {
                validateTime(day, dateLabel, slots, qualified);
            } else {
                validateSlots(day, dateLabel, slots, qualified);
            }
        }
    }

    /**
     * 期間・ユーザーの希望を全部消して、入力から作り直す（20-SB-03 3.2-3）。
     *
     * @param period 期間
     * @param userId ユーザーID
     * @param days   日ごとの入力（確かめ済みのもの）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void replaceRequests(ShiftPeriod period, Long userId, List<DayRequestForm> days) {
        shiftRequestRepository.deleteByPeriodIdAndUserId(period.getId(), userId);

        ShiftPeriod periodRef = shiftPeriodRepository.getReferenceById(period.getId());
        User userRef = userRepository.getReferenceById(userId);
        List<ShiftRequest> rows = new ArrayList<>();
        for (DayRequestForm day : days) {
            if (day.getMode() == RequestMode.TIME) {
                rows.add(newRow(periodRef, userRef, day.getWorkDate(), null, Availability.TIME, day.getStartTime(),
                        day.getEndTime()));
                continue;
            }
            // 職能のない枠は値が null なので、行を作らない
            if (day.getDayShift() != null) {
                rows.add(newRow(periodRef, userRef, day.getWorkDate(), SlotIds.DAY, day.getDayShift(), null, null));
            }
            if (day.getEveningShift() != null) {
                rows.add(newRow(periodRef, userRef, day.getWorkDate(), SlotIds.EVENING, day.getEveningShift(), null,
                        null));
            }
        }
        shiftRequestRepository.saveAll(rows);
    }

    private ShiftRequest newRow(ShiftPeriod period, User user, LocalDate workDate, Byte slotId,
            Availability availability, LocalTime startTime, LocalTime endTime) {
        ShiftRequest row = new ShiftRequest();
        row.setPeriod(period);
        row.setUser(user);
        row.setWorkDate(workDate);
        row.setSlot(slotId == null ? null : slotRepository.getReferenceById(slotId));
        row.setAvailability(availability);
        row.setStartTime(startTime);
        row.setEndTime(endTime);
        return row;
    }

    /** 日付が、期間の全日付とちょうど一致するか（足りない・多い・重複・空がないか）を確かめる。 */
    private void checkDates(ShiftPeriod period, List<DayRequestForm> days) {
        boolean broken = days == null
                || days.stream().anyMatch(d -> d == null || d.getWorkDate() == null || d.getMode() == null);
        if (broken) {
            throw new BusinessException("E1302");
        }
        List<LocalDate> expected = datesOf(period);
        Set<LocalDate> actual = days.stream().map(DayRequestForm::getWorkDate).collect(Collectors.toSet());
        if (days.size() != expected.size() || !actual.equals(new HashSet<>(expected))) {
            throw new BusinessException("E1302");
        }
    }

    /** 「枠ごと」の日の確かめ。先に E1304 を日勤→夕勤の順にすべての枠で調べ、そのあと E1302 を調べる。 */
    private void validateSlots(DayRequestForm day, String dateLabel, List<Slot> slots, Set<Byte> qualified) {
        for (Slot slot : slots) {
            if (qualified.contains(slot.getId()) && valueOf(day, slot.getId()) == null) {
                throw new BusinessException("E1304", dateLabel, slot.getName());
            }
        }
        for (Slot slot : slots) {
            Availability value = valueOf(day, slot.getId());
            boolean unqualifiedWithValue = !qualified.contains(slot.getId()) && value != null;
            if (value == Availability.TIME || unqualifiedWithValue) {
                throw new BusinessException("E1302");
            }
        }
    }

    /** 「時間指定」の日の確かめ。 */
    private void validateTime(DayRequestForm day, String dateLabel, List<Slot> slots, Set<Byte> qualified) {
        if (day.getDayShift() == Availability.TIME || day.getEveningShift() == Availability.TIME) {
            throw new BusinessException("E1302");
        }
        LocalTime start = day.getStartTime();
        LocalTime end = day.getEndTime();
        if (start == null || end == null) {
            throw new BusinessException("E1305", dateLabel);
        }
        if (!ShiftTimeUtil.isOnGrid(start) || !ShiftTimeUtil.isOnGrid(end)) {
            throw new BusinessException("E1306", dateLabel);
        }
        if (!start.isBefore(end)) {
            throw new BusinessException("E1307", dateLabel);
        }
        for (Slot slot : slots) {
            if (!qualified.contains(slot.getId())
                    && ShiftTimeUtil.overlaps(start, end, slot.getStartTime(), slot.getEndTime())) {
                throw new BusinessException("E1308", dateLabel, slot.getName());
            }
        }
    }

    /** 枠ごとの値（日勤・夕勤以外の枠は null）。 */
    private Availability valueOf(DayRequestForm day, Byte slotId) {
        if (slotId == SlotIds.DAY) {
            return day.getDayShift();
        }
        if (slotId == SlotIds.EVENING) {
            return day.getEveningShift();
        }
        return null;
    }

    /** 1日分の既存の希望から、フォームの1日分を作る。時間指定の行があれば TIME、なければ SLOT。 */
    private DayRequestForm toDayForm(LocalDate date, List<ShiftRequest> rows) {
        DayRequestForm day = new DayRequestForm();
        day.setWorkDate(date);
        day.setMode(RequestMode.SLOT);
        for (ShiftRequest row : rows) {
            if (row.getAvailability() == Availability.TIME) {
                day.setMode(RequestMode.TIME);
                day.setStartTime(row.getStartTime());
                day.setEndTime(row.getEndTime());
            } else if (row.getSlot() != null && row.getSlot().getId() == SlotIds.DAY) {
                day.setDayShift(row.getAvailability());
            } else if (row.getSlot() != null && row.getSlot().getId() == SlotIds.EVENING) {
                day.setEveningShift(row.getAvailability());
            }
        }
        return day;
    }

    /** 受付中で期限が過ぎていない期間のうち、期限が一番早いもの。なければ null。 */
    private ShiftPeriod findDefaultPeriod() {
        LocalDateTime now = LocalDateTime.now(clock);
        return shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN).stream()
                .filter(p -> !p.getDeadlineAt().isBefore(now))
                .min(Comparator.comparing(ShiftPeriod::getDeadlineAt))
                .orElse(null);
    }

    /** 期間のプルダウンの選択肢。受付中の期間を期限の早い順に並べ、表示中の期間が受付中でなければ先頭に入れる。 */
    private List<PeriodOption> findOptions(ShiftPeriod current) {
        List<PeriodOption> options = new ArrayList<>();
        if (current.getStatus() != PeriodStatus.OPEN) {
            options.add(PeriodService.toOption(current));
        }
        shiftPeriodRepository.findByStatusOrderByStartDateAsc(PeriodStatus.OPEN).stream()
                .sorted(Comparator.comparing(ShiftPeriod::getDeadlineAt))
                .map(PeriodService::toOption)
                .forEach(options::add);
        return options;
    }

    /** 提出日時の表示用の文字列。未提出（null）なら null。 */
    private String labelOf(LocalDateTime submittedAt) {
        return submittedAt == null ? null : DateTimeUtil.dateTime(submittedAt);
    }

    private SubmissionState toState(boolean editable, LocalDateTime submittedAt) {
        if (!editable) {
            return SubmissionState.CLOSED;
        }
        return submittedAt != null ? SubmissionState.SUBMITTED : SubmissionState.NOT_SUBMITTED;
    }

    private List<LocalDate> datesOf(ShiftPeriod period) {
        return period.getStartDate().datesUntil(period.getEndDate().plusDays(1)).toList();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found. id=" + userId));
    }
}

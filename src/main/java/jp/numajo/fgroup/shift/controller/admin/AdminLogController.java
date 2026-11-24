package jp.numajo.fgroup.shift.controller.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.dto.OperationLogRow;
import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.form.LogSearchForm;
import jp.numajo.fgroup.shift.service.OperationLogService;
import lombok.RequiredArgsConstructor;

/**
 * SA-03 操作ログ。20-SA-03 2.1。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/logs")
public class AdminLogController {

    /** 1ページの件数（20-00 の定数の表） */
    public static final int PAGE_SIZE = 50;

    /** 既定の期間：今日を含む7日分（今日の6日前から） */
    static final int DEFAULT_DAYS_BEFORE = 6;

    /** 期間（to − from）の上限の日数。これより長いと E4302 */
    static final long MAX_RANGE_DAYS = 366;

    /** 操作のプルダウン（OperationAction の全値、表示は日本語名） */
    static final List<SelectOption> ACTION_OPTIONS = Arrays.stream(OperationAction.values())
            .map(a -> new SelectOption(a.name(), a.getLabel()))
            .toList();

    /** 並び：新しい順（created_at 降順→id 降順） */
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final OperationLogService operationLogService;
    private final Clock clock;

    /**
     * 初期表示・検索（20-SA-03 2.1）。
     *
     * <p>
     * {@code @Validated} は付けない。型の合わない値で出た BindingResult のエラーは無視し、その項目は null として扱う。
     * </p>
     */
    @GetMapping
    public String list(@ModelAttribute("logSearchForm") LogSearchForm form, BindingResult bindingResult,
                       Model model) {
        applyDefaults(form);
        model.addAttribute("userOptions", operationLogService.userOptions());
        model.addAttribute("actionOptions", ACTION_OPTIONS);

        String errorCode = checkRange(form.getFrom(), form.getTo());
        if (errorCode != null) {
            // 検索せず、結果は0件で表示する
            model.addAttribute("errorMessage", errorCode);
            form.setPage(0);
            model.addAttribute("logs", Page.<OperationLogRow>empty(PageRequest.of(0, PAGE_SIZE, SORT)));
            return "admin/logs";
        }

        Page<OperationLogRow> logs = operationLogService.search(form, PageRequest.of(form.getPage(), PAGE_SIZE, SORT));
        if (logs.getTotalPages() > 0 && form.getPage() >= logs.getTotalPages()) {
            // 最後のページより後ろ → 最後のページでもう一度検索する
            form.setPage(logs.getTotalPages() - 1);
            logs = operationLogService.search(form, PageRequest.of(form.getPage(), PAGE_SIZE, SORT));
        }
        model.addAttribute("logs", logs);
        return "admin/logs";
    }

    /** 既定値を入れる（20-SA-03 2.1-2）。from：今日−6日、to：今日、page：0（null・負のとき）。 */
    private void applyDefaults(LogSearchForm form) {
        LocalDate today = LocalDate.now(clock);
        if (form.getFrom() == null) {
            form.setFrom(today.minusDays(DEFAULT_DAYS_BEFORE));
        }
        if (form.getTo() == null) {
            form.setTo(today);
        }
        if (form.getPage() == null || form.getPage() < 0) {
            form.setPage(0);
        }
    }

    /**
     * 期間を確かめる（20-SA-03 2.1-3・4）。
     *
     * @return from &gt; to なら E4301、to − from が366日より長いなら E4302、よければ null
     */
    static String checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return "E4301";
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            return "E4302";
        }
        return null;
    }
}

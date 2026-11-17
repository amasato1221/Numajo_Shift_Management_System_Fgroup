package jp.numajo.fgroup.shift.controller.staff;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.dto.SelectOption;
import jp.numajo.fgroup.shift.entity.CalendarFeed;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.NotificationSettingForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.CalendarFeedService;
import jp.numajo.fgroup.shift.service.NotificationSettingService;
import lombok.RequiredArgsConstructor;

/**
 * SB-05 設定（通知・カレンダー連携）。20-SB-05 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/settings")
public class StaffSettingController {

    private static final String VIEW = "staff/settings";
    private static final String REDIRECT = "redirect:/staff/settings";

    /** 締切通知のチェックボックスの選択肢（20-SB-05 1.1 No.2。値は NotificationSettingService.DAYS_OPTIONS と同じ） */
    static final List<SelectOption> DAY_OPTIONS = List.of(
            new SelectOption("0", "当日"),
            new SelectOption("1", "前日"),
            new SelectOption("3", "3日前"),
            new SelectOption("7", "7日前"));

    private final NotificationSettingService notificationSettingService;
    private final CalendarFeedService calendarFeedService;

    /**
     * 初期表示（20-SB-05 2.1）。保存済みの締切通知と、購読用URL（なければ作る）を表示する。
     */
    @GetMapping
    public String show(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        NotificationSettingForm form = new NotificationSettingForm();
        form.setDaysBefore(notificationSettingService.findDays(loginUser.getUserId()));
        CalendarFeed feed = calendarFeedService.getOrCreate(loginUser.getUserId());

        model.addAttribute("notificationSettingForm", form);
        model.addAttribute("dayOptions", DAY_OPTIONS);
        model.addAttribute("feedUrl", calendarFeedService.feedUrl(feed.getToken()));
        model.addAttribute("webcalUrl", calendarFeedService.webcalUrl(feed.getToken()));
        return VIEW;
    }

    /**
     * 締切通知の保存（20-SB-05 2.2）。全部外したとき（null）は空のリストにして0件にし、W1501 も出す。
     * 0・1・3・7 以外の値は E1501 の赤のアラートで戻す（DB は変えない）。
     */
    @PostMapping("/notifications")
    public String saveNotifications(@ModelAttribute NotificationSettingForm form,
                                    @AuthenticationPrincipal LoginUser loginUser,
                                    RedirectAttributes redirectAttributes) {
        List<Integer> days = form.getDaysBefore() == null ? List.of() : form.getDaysBefore();
        try {
            notificationSettingService.save(loginUser.getUserId(), days);
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            redirectAttributes.addFlashAttribute("errorArgs", e.getArgs());
            return REDIRECT;
        }
        redirectAttributes.addFlashAttribute("successMessage", "I1501");
        if (days.isEmpty()) {
            redirectAttributes.addFlashAttribute("warningMessage", "W1501");
        }
        return REDIRECT;
    }

    /**
     * 購読URLの再発行（20-SB-05 2.3）。古いURLは使えなくなる。
     */
    @PostMapping("/calendar/reissue")
    public String reissue(@AuthenticationPrincipal LoginUser loginUser, RedirectAttributes redirectAttributes) {
        calendarFeedService.reissue(loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I1502");
        return REDIRECT;
    }
}

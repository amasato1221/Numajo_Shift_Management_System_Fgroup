package jp.numajo.fgroup.shift.controller.common;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.StoreSettingService;
import lombok.RequiredArgsConstructor;

/**
 * ヘッダーとメニューに必要な値をすべての画面に入れる（20-00 3.3）。入力の前後の空白も取る（20-00 5章）。
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final StoreSettingService storeSettingService;
    private final NotificationService notificationService;

    /** 前後の空白を取り、空文字を null にする（20-00 5章）。 */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ModelAttribute("storeName")
    public String storeName() {
        return storeSettingService.get().getStoreName();
    }

    @ModelAttribute("showOthersShifts")
    public boolean showOthersShifts() {
        return storeSettingService.get().isShowOthersShifts();
    }

    @ModelAttribute("unreadCount")
    public long unreadCount(@AuthenticationPrincipal LoginUser loginUser) {
        return loginUser == null ? 0 : notificationService.countUnread(loginUser.getUserId());
    }

    @ModelAttribute("loginUser")
    public LoginUser loginUser(@AuthenticationPrincipal LoginUser loginUser) {
        return loginUser;
    }
}

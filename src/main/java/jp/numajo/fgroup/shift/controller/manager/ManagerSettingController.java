package jp.numajo.fgroup.shift.controller.manager;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.form.StoreSettingForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.StoreSettingService;
import lombok.RequiredArgsConstructor;

/**
 * SM-09 店舗設定。20-SM-09 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/settings")
public class ManagerSettingController {

    private final StoreSettingService storeSettingService;

    /** 初期表示（20-SM-09 2.1）。今の店舗設定をフォームに入れて表示する。 */
    @GetMapping
    public String show(Model model) {
        StoreSetting setting = storeSettingService.get();
        StoreSettingForm form = new StoreSettingForm();
        form.setStoreName(setting.getStoreName());
        form.setShowOthersShifts(setting.isShowOthersShifts());
        model.addAttribute("storeSettingForm", form);
        model.addAttribute("setting", setting);
        return "manager/settings";
    }

    /** 保存（20-SM-09 2.2）。入力エラーなら最終更新の表示用に setting を入れ直して再表示する。 */
    @PostMapping
    public String save(@Validated @ModelAttribute("storeSettingForm") StoreSettingForm form,
                       BindingResult bindingResult,
                       @AuthenticationPrincipal LoginUser loginUser,
                       Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("setting", storeSettingService.get());
            return "manager/settings";
        }
        storeSettingService.update(form.getStoreName(), form.isShowOthersShifts(), loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I2901");
        return "redirect:/manager/settings";
    }
}

package jp.numajo.fgroup.shift.controller.manager;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.dto.StaffCreatedResult;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.StaffForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.SkillService;
import jp.numajo.fgroup.shift.service.StaffService;
import lombok.RequiredArgsConstructor;

/**
 * スタッフ一覧・登録・編集（SM-02・SM-03）。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/staff")
public class ManagerStaffController {

    /** 検索文字の最大長 */
    static final int KEYWORD_MAX_LENGTH = 50;

    private static final String FORM_VIEW = "manager/staff-form";
    private static final String REDIRECT_PREFIX = "redirect:/manager/staff/";

    private final StaffService staffService;
    private final SkillService skillService;

    /**
     * スタッフ一覧を表示する（20-SM-02 2.1）。
     *
     * @param keyword 検索文字（前後の空白を取り、空なら null。50文字を超えた分は切る）
     * @param status  状態（ALL／ACTIVE／INACTIVE。null・不正は ACTIVE）
     * @param model   画面に渡す値
     * @return テンプレート名
     */
    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, Model model) {
        StaffStatusFilter filter = StaffStatusFilter.parse(status);
        String word = normalizeKeyword(keyword);

        model.addAttribute("staffList", staffService.search(word, filter));
        model.addAttribute("keyword", word);
        model.addAttribute("status", filter.name());
        return "manager/staff-list";
    }

    /**
     * 登録画面を表示する（20-SM-03 2.1）。
     *
     * @param model 画面に渡す値
     * @return テンプレート名
     */
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("staffForm", new StaffForm());
        model.addAttribute("skills", skillService.findAll());
        model.addAttribute("isNew", true);
        return FORM_VIEW;
    }

    /**
     * 登録する（20-SM-03 2.2）。入力チェックや業務のエラーは、入力を残したまま再表示する。
     *
     * @param form               入力
     * @param bindingResult      入力チェックの結果
     * @param loginUser          操作した店長
     * @param model              画面に渡す値
     * @param redirectAttributes リダイレクト先に渡す値
     * @return テンプレート名またはリダイレクト先
     */
    @PostMapping
    public String create(@Validated @ModelAttribute("staffForm") StaffForm form, BindingResult bindingResult,
            @AuthenticationPrincipal LoginUser loginUser, Model model, RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            try {
                StaffCreatedResult result = staffService.create(form, loginUser.getUserId());
                redirectAttributes.addFlashAttribute("initialPassword", result.initialPassword());
                redirectAttributes.addFlashAttribute("successMessage", "I2202");
                return REDIRECT_PREFIX + result.userId();
            } catch (BusinessException e) {
                rejectBusinessError(bindingResult, e);
            }
        }
        model.addAttribute("skills", skillService.findAll());
        model.addAttribute("isNew", true);
        return FORM_VIEW;
    }

    /**
     * 編集画面を表示する（20-SM-03 2.3）。
     *
     * @param id    ユーザーID
     * @param model 画面に渡す値
     * @return テンプレート名
     */
    @GetMapping("/{id}")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("staffForm", staffService.getForEdit(id));
        addEditValues(model, id);
        return FORM_VIEW;
    }

    /**
     * 更新する（20-SM-03 2.4）。
     *
     * @param id                 ユーザーID
     * @param form               入力
     * @param bindingResult      入力チェックの結果
     * @param loginUser          操作した店長
     * @param model              画面に渡す値
     * @param redirectAttributes リダイレクト先に渡す値
     * @return テンプレート名またはリダイレクト先
     */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Validated @ModelAttribute("staffForm") StaffForm form,
            BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser, Model model,
            RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            try {
                int remaining = staffService.update(id, form, loginUser.getUserId());
                redirectAttributes.addFlashAttribute("successMessage", "I2204");
                if (remaining >= 1) {
                    redirectAttributes.addFlashAttribute("warningMessage", "W2201");
                    redirectAttributes.addFlashAttribute("warningArgs",
                            new Object[] { form.getName(), remaining });
                }
                return REDIRECT_PREFIX + id;
            } catch (BusinessException e) {
                rejectBusinessError(bindingResult, e);
            }
        }
        addEditValues(model, id);
        return FORM_VIEW;
    }

    /**
     * パスワードを初期化する（20-SM-03 2.5）。
     *
     * @param id                 ユーザーID
     * @param loginUser          操作した店長
     * @param redirectAttributes リダイレクト先に渡す値
     * @return リダイレクト先
     */
    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
            RedirectAttributes redirectAttributes) {
        String password = staffService.resetPassword(id, loginUser.getUserId());
        redirectAttributes.addFlashAttribute("initialPassword", password);
        redirectAttributes.addFlashAttribute("successMessage", "I2205");
        return REDIRECT_PREFIX + id;
    }

    /** 編集画面に必要な値（職能の選択肢・状態の表示）を model に入れる。 */
    private void addEditValues(Model model, Long id) {
        model.addAttribute("skills", skillService.findAll());
        model.addAttribute("staff", staffService.getDetail(id));
        model.addAttribute("isNew", false);
    }

    /** 業務エラーを、該当する項目のエラーにする（E2209 は職能、それ以外はログインID）。 */
    private static void rejectBusinessError(BindingResult bindingResult, BusinessException e) {
        String field = "E2209".equals(e.getMessageCode()) ? "skillIds" : "loginId";
        bindingResult.rejectValue(field, e.getMessageCode());
    }

    /** 前後の空白を取り、空なら null、長ければ50文字で切る。 */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > KEYWORD_MAX_LENGTH ? trimmed.substring(0, KEYWORD_MAX_LENGTH) : trimmed;
    }
}

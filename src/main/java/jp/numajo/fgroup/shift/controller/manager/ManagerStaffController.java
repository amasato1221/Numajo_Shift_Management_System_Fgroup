package jp.numajo.fgroup.shift.controller.manager;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
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

    private final StaffService staffService;

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

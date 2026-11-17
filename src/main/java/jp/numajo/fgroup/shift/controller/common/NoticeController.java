package jp.numajo.fgroup.shift.controller.common;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import lombok.RequiredArgsConstructor;

/**
 * SC-02 お知らせ一覧。20-SC-02 2章、30-06 1.3。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/notices")
public class NoticeController {

    /** 一覧に出す件数（20-SC-02 2.1） */
    private static final int LIST_LIMIT = 100;

    private final NotificationService notificationService;

    /**
     * 初期表示（20-SC-02 2.1）。自分のお知らせを新しい順に100件出す。
     *
     * @param loginUser ログイン中の人
     * @param model     画面に渡す値（notices）
     * @return common/notices
     */
    @GetMapping
    public String list(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        model.addAttribute("notices", notificationService.findLatest(loginUser.getUserId(), LIST_LIMIT));
        return "common/notices";
    }

    /**
     * お知らせを開く（20-SC-02 2.2）。既読にして、お知らせの移動先へ飛ぶ。
     *
     * @param id        お知らせID
     * @param loginUser ログイン中の人
     * @return redirect: ＋ 移動先（使えないときは /notices）
     */
    @PostMapping("/{id}/open")
    public String open(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        return "redirect:" + notificationService.open(loginUser.getUserId(), id);
    }

    /**
     * すべて既読にする（20-SC-02 2.3）。
     *
     * @param loginUser          ログイン中の人
     * @param redirectAttributes フラッシュ（I0202）
     * @return redirect:/notices
     */
    @PostMapping("/read-all")
    public String readAll(@AuthenticationPrincipal LoginUser loginUser, RedirectAttributes redirectAttributes) {
        notificationService.readAll(loginUser.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "I0202");
        return "redirect:/notices";
    }
}

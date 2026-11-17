package jp.numajo.fgroup.shift.controller.staff;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jp.numajo.fgroup.shift.form.ChatForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ChatService;
import lombok.RequiredArgsConstructor;

/**
 * SB-06 チャット（店長と）。20-SB-06 2章、30-06 2章。
 *
 * <p>
 * バイトの会話は、そのバイト自身のユーザーIDで1つにまとまる（chat_messages.staff_user_id）。
 * そのため、会話の相手（staffUserId）も送る人（senderUserId）も、ログイン中のバイト自身になる。
 * </p>
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/chat")
public class StaffChatController {

    private static final String VIEW = "staff/chat";
    /** 送ったあとは一番下（最新）を見せる（20-SB-06 1章） */
    private static final String REDIRECT_LATEST = "redirect:/staff/chat#latest";

    private final ChatService chatService;

    /**
     * 店長とのやり取りを表示する（20-SB-06 2.1）。開くと、店長からの未読が既読になる。
     *
     * @param loginUser ログイン中のバイト
     * @param model     画面に渡す値（messages、chatForm）
     * @return テンプレート名
     */
    @GetMapping
    public String show(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        model.addAttribute("chatForm", new ChatForm());
        addMessages(model, loginUser);
        return VIEW;
    }

    /**
     * 店長へメッセージを送る（20-SB-06 2.2）。入力チェックのエラーは入力を残して再表示する。
     *
     * @param form          入力
     * @param bindingResult 入力チェックの結果
     * @param loginUser     ログイン中のバイト
     * @param model         画面に渡す値
     * @return 入力エラーならテンプレート名、送れたら一番下へのリダイレクト先
     */
    @PostMapping
    public String send(@Validated @ModelAttribute("chatForm") ChatForm form, BindingResult bindingResult,
            @AuthenticationPrincipal LoginUser loginUser, Model model) {
        if (bindingResult.hasErrors()) {
            addMessages(model, loginUser);
            return VIEW;
        }
        chatService.send(loginUser.getUserId(), loginUser.getUserId(), form.getBody());
        return REDIRECT_LATEST;
    }

    private void addMessages(Model model, LoginUser loginUser) {
        model.addAttribute("messages", chatService.getConversation(loginUser.getUserId(), loginUser.getUserId()));
    }
}

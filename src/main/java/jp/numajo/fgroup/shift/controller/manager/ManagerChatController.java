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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ChatForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ChatService;
import lombok.RequiredArgsConstructor;

/**
 * チャット一覧・チャット（バイトごと）（SM-10・SM-11）。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/chats")
public class ManagerChatController {

    private static final String LIST_VIEW = "manager/chat-list";
    private static final String CHAT_VIEW = "manager/chat";
    private static final String REDIRECT_PREFIX = "redirect:/manager/chats/";

    private final ChatService chatService;

    /**
     * チャット一覧を表示する（20-SM-10 2.1）。
     *
     * @param model 画面に渡す値
     * @return テンプレート名
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("rows", chatService.getChatList());
        return LIST_VIEW;
    }

    /**
     * バイトとのチャットを表示する（20-SM-11 2.1）。
     *
     * @param staffId   バイトのユーザーID
     * @param loginUser 見ている店長
     * @param model     画面に渡す値
     * @return テンプレート名
     */
    @GetMapping("/{staffId}")
    public String show(@PathVariable Long staffId, @AuthenticationPrincipal LoginUser loginUser, Model model) {
        model.addAttribute("chatForm", new ChatForm());
        addChatValues(model, staffId, loginUser);
        return CHAT_VIEW;
    }

    /**
     * メッセージを送る（20-SM-11 2.2）。入力チェックのエラーは入力を残して再表示し、業務エラーはリダイレクトで知らせる。
     *
     * @param staffId            バイトのユーザーID
     * @param form               入力
     * @param bindingResult      入力チェックの結果
     * @param loginUser          送る店長
     * @param model              画面に渡す値
     * @param redirectAttributes リダイレクト先に渡す値
     * @return テンプレート名またはリダイレクト先
     */
    @PostMapping("/{staffId}")
    public String send(@PathVariable Long staffId, @Validated @ModelAttribute("chatForm") ChatForm form,
            BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser, Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addChatValues(model, staffId, loginUser);
            return CHAT_VIEW;
        }
        try {
            chatService.send(staffId, loginUser.getUserId(), form.getBody());
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessageCode());
            return REDIRECT_PREFIX + staffId;
        }
        return REDIRECT_PREFIX + staffId + "#latest";
    }

    private void addChatValues(Model model, Long staffId, LoginUser loginUser) {
        model.addAttribute("staff", chatService.getStaffForChat(staffId));
        model.addAttribute("messages", chatService.getConversation(staffId, loginUser.getUserId()));
    }
}

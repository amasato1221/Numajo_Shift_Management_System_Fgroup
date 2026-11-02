package jp.numajo.fgroup.shift.controller.common;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jp.numajo.fgroup.shift.security.HomeUrl;
import jp.numajo.fgroup.shift.security.LoginUser;

/**
 * ルート（/）。ログイン状態に応じて、ログイン画面かホームへ移す（30-06 1.1）。
 */
@Controller
public class RootController {

    @GetMapping("/")
    public String root(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            return "redirect:/login";
        }
        return "redirect:" + HomeUrl.of(loginUser.getRole());
    }
}

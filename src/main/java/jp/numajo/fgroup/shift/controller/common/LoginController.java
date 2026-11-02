package jp.numajo.fgroup.shift.controller.common;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jp.numajo.fgroup.shift.security.HomeUrl;
import jp.numajo.fgroup.shift.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * SC-01 ログイン画面の表示（20-SC-01 2.1）。ログインの処理そのものは Spring Security が行う。
 */
@Controller
@RequiredArgsConstructor
public class LoginController {

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return "redirect:" + HomeUrl.of(loginUser.getRole());
        }
        return "common/login";
    }
}

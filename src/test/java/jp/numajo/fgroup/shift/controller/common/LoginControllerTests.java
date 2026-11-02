package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;

/**
 * LoginController（/login の表示）の単体テスト（97_テスト計画書 2.2・3.2、20-SC-01 6章 No.9）。
 *
 * <pre>
 * 判定：authentication != null
 *       &amp;&amp; !(authentication instanceof AnonymousAuthenticationToken)
 *       &amp;&amp; authentication.getPrincipal() instanceof LoginUser
 *      （A：ログイン情報がある、B：匿名ではない、C：principal が LoginUser）
 *
 * No. | A  | B  | C  | 判定 | 結果               | テストの値
 *  1  | 偽 | －  | －  | 偽   | common/login       | authentication = null
 *  2  | 真 | 偽 | －  | 偽   | common/login       | 匿名（AnonymousAuthenticationToken）
 *  3  | 真 | 真 | 偽 | 偽   | common/login       | principal が文字列
 *  4  | 真 | 真 | 真 | 真   | redirect:各ホーム  | LoginUser（ADMIN・MANAGER・STAFF の3通り）
 *
 * 組み合わせ表に書かない組み合わせ（2³ ＝ 8通りのうち、No.1〜4 以外の4通り）：
 *  ・A が偽のとき、B・C は評価されない（&amp;&amp; の短絡評価）。A 偽・B 偽／真・C 偽／真の4通りは、No.1 と同じ結果になる。
 *  ・A 真・B 偽・C 真：匿名のときの principal は文字列「anonymousUser」で、LoginUser にならない。
 *    また B が偽なら C は評価されないので、結果は No.2 と同じ。
 * </pre>
 */
class LoginControllerTests {

    private final LoginController controller = new LoginController();

    private static LoginUser loginUser(Role role) {
        User u = new User();
        u.setId(1L);
        u.setLoginId("alice");
        u.setRole(role);
        u.setActive(true);
        return new LoginUser(u, false);
    }

    @Test
    void No1_ログイン情報がなければログイン画面を表示() {
        assertThat(controller.login(null)).isEqualTo("common/login");
    }

    @Test
    void No2_匿名ならログイン画面を表示() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(controller.login(anonymous)).isEqualTo("common/login");
    }

    @Test
    void No3_principalがLoginUserでなければログイン画面を表示() {
        UsernamePasswordAuthenticationToken other = new UsernamePasswordAuthenticationToken("someone", null,
                List.of());

        assertThat(controller.login(other)).isEqualTo("common/login");
    }

    @ParameterizedTest(name = "No.4 {0} は {1} へ")
    @CsvSource({ "ADMIN,redirect:/admin/home", "MANAGER,redirect:/manager/home", "STAFF,redirect:/staff/home" })
    void No4_ログイン済みなら種類ごとのホームへ(Role role, String expected) {
        LoginUser loginUser = loginUser(role);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(loginUser, null,
                loginUser.getAuthorities());

        assertThat(controller.login(token)).isEqualTo(expected);
    }
}

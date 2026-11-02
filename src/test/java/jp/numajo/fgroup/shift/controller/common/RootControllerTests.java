package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;

/**
 * RootController（/）の単体テスト（97_テスト計画書 2.2、30-06 1.1）。
 *
 * <p>
 * 判定は「ログイン情報があるか」の1つだけなので、組み合わせ表は要らない（条件網羅＝判定条件網羅）。
 * </p>
 *
 * <pre>
 * No. | loginUser == null | 結果
 *  1  | 真               | redirect:/login
 *  2  | 偽               | redirect:各ホーム（ADMIN・MANAGER・STAFF の3通り）
 * </pre>
 *
 * <p>
 * 実際のアプリでは、SecurityConfig が「/ はログイン必須」にしているため、未ログインの / は
 * このクラスに届く前に /login へ移される（LoginFlowTests で確かめる）。No.1 はメソッド単体で確かめる。
 * </p>
 */
class RootControllerTests {

    private final RootController controller = new RootController();

    private static LoginUser loginUser(Role role) {
        User u = new User();
        u.setId(1L);
        u.setLoginId("alice");
        u.setRole(role);
        u.setActive(true);
        return new LoginUser(u, false);
    }

    @Test
    void No1_ログイン情報がなければログイン画面へ() {
        assertThat(controller.root(null)).isEqualTo("redirect:/login");
    }

    @ParameterizedTest(name = "No.2 {0} は {1} へ")
    @CsvSource({ "ADMIN,redirect:/admin/home", "MANAGER,redirect:/manager/home", "STAFF,redirect:/staff/home" })
    void No2_ログイン済みなら種類ごとのホームへ(Role role, String expected) {
        assertThat(controller.root(loginUser(role))).isEqualTo(expected);
    }
}

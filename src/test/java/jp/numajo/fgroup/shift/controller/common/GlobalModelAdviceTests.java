package jp.numajo.fgroup.shift.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.web.bind.WebDataBinder;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.StoreSetting;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.NotificationService;
import jp.numajo.fgroup.shift.service.StoreSettingService;
import lombok.Getter;
import lombok.Setter;

/**
 * GlobalModelAdvice の単体テスト（20-00 3.3・5章、30-06 1.6）。
 *
 * <p>
 * 画面に入ったときの見え方（ヘッダー・メニュー）は LayoutTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定条件網羅：unreadCount の「loginUser == null」（真＝0、偽＝未読数）。条件は1つだけ。
 * StringTrimmerEditor(true)：前後に空白あり→取る、空白だけ→null、空文字→null、空白なし→そのまま。
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class GlobalModelAdviceTests {

    @Mock
    private StoreSettingService storeSettingService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private GlobalModelAdvice advice;

    private static LoginUser loginUser() {
        User u = new User();
        u.setId(3L);
        u.setLoginId("yamada");
        u.setName("山田");
        u.setRole(Role.STAFF);
        u.setActive(true);
        return new LoginUser(u, false);
    }

    private void setting(String name, boolean show) {
        StoreSetting s = new StoreSetting();
        s.setStoreName(name);
        s.setShowOthersShifts(show);
        when(storeSettingService.get()).thenReturn(s);
    }

    @Test
    void storeName_店舗設定の店舗名() {
        setting("三島駅西通り店", true);

        assertThat(advice.storeName()).isEqualTo("三島駅西通り店");
    }

    @Test
    void showOthersShifts_店舗設定の値() {
        setting("店", true);
        assertThat(advice.showOthersShifts()).isTrue();
    }

    @Test
    void showOthersShifts_falseならfalse() {
        setting("店", false);
        assertThat(advice.showOthersShifts()).isFalse();
    }

    @Test
    void unreadCount_未ログインなら0で数えない() {
        assertThat(advice.unreadCount(null)).isZero();
        verify(notificationService, never()).countUnread(3L);
    }

    @Test
    void unreadCount_ログイン中なら自分の未読数() {
        when(notificationService.countUnread(3L)).thenReturn(4L);

        assertThat(advice.unreadCount(loginUser())).isEqualTo(4L);
    }

    @Test
    void loginUser_そのまま返す() {
        LoginUser u = loginUser();

        assertThat(advice.loginUser(u)).isSameAs(u);
        assertThat(advice.loginUser(null)).isNull();
    }

    @Test
    void initBinder_前後の空白を取り_空白だけと空文字はnull() {
        TrimForm form = new TrimForm();
        WebDataBinder binder = new WebDataBinder(form);
        advice.initBinder(binder);
        MutablePropertyValues values = new MutablePropertyValues();
        values.add("a", "  山田  ");
        values.add("b", "   ");
        values.add("c", "");
        values.add("d", "山田");

        binder.bind(values);

        assertThat(form.getA()).isEqualTo("山田");
        assertThat(form.getB()).isNull();
        assertThat(form.getC()).isNull();
        assertThat(form.getD()).isEqualTo("山田");
    }

    /** 空白の取り方を確かめるためのフォーム（テスト用）。 */
    @Getter
    @Setter
    public static class TrimForm {
        private String a;
        private String b;
        private String c;
        private String d;
    }
}

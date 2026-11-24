package jp.numajo.fgroup.shift.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.ManagerRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.ManagerForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.ManagerAccountService;

/**
 * AdminManagerController（SA-02：店長アカウント管理）のテスト。
 *
 * <p>
 * 分岐（入力チェックのエラー・業務エラー・成功）ごとに、返すビューとフラッシュの値を確かめる。
 * 画面の描画・権限・CSRF は AdminManagerFlowTests で確かめる。
 * </p>
 *
 * <table>
 * <caption>create の分岐</caption>
 * <tr><th>入力チェックのエラー</th><th>E4207</th><th>結果</th></tr>
 * <tr><td>あり</td><td>－（Service を呼ばない）</td><td>再表示</td></tr>
 * <tr><td>なし</td><td>あり</td><td>loginId のエラーにして再表示</td></tr>
 * <tr><td>なし</td><td>なし</td><td>リダイレクト（初期パスワード・氏名・I4202）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class AdminManagerControllerTests {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private ManagerAccountService managerAccountService;

    private AdminManagerController controller;
    private Model model;
    private RedirectAttributesModelMap redirect;
    private LoginUser admin;

    @BeforeEach
    void setUp() {
        controller = new AdminManagerController(managerAccountService);
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        User user = new User();
        user.setId(ADMIN_ID);
        user.setLoginId("admin");
        user.setPasswordHash("x");
        user.setName("管理者");
        user.setRole(Role.ADMIN);
        user.setActive(true);
        admin = new LoginUser(user, false);
    }

    /** リダイレクト先に渡す値（型を Object にそろえる）。 */
    private Map<String, Object> flash() {
        return new HashMap<>(redirect.getFlashAttributes());
    }

    private static ManagerForm form() {
        ManagerForm form = new ManagerForm();
        form.setName("山田 店長");
        form.setLoginId("yamada");
        return form;
    }

    // ---------------------------------------------------------------- list

    @Test
    void list_一覧と空のフォームを入れてテンプレート名を返す() {
        List<ManagerRow> rows = List.of(new ManagerRow(2L, "山田", "yamada", true, false, null, null, true));
        when(managerAccountService.findAll()).thenReturn(rows);

        String view = controller.list(model);

        assertThat(view).isEqualTo("admin/managers");
        assertThat(model.getAttribute("managers")).isSameAs(rows);
        assertThat(model.getAttribute("managerForm")).isInstanceOf(ManagerForm.class);
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_入力チェックのエラーならServiceを呼ばず一覧を入れ直して再表示() {
        ManagerForm form = form();
        BindingResult result = new BeanPropertyBindingResult(form, "managerForm");
        result.rejectValue("name", "E4201");
        when(managerAccountService.findAll()).thenReturn(List.of());

        String view = controller.create(form, result, admin, model, redirect);

        assertThat(view).isEqualTo("admin/managers");
        assertThat(model.getAttribute("managers")).isEqualTo(List.of());
        verify(managerAccountService, never()).create(any(), any());
    }

    @Test
    void create_ログインIDの重複ならloginIdのエラーにして再表示() {
        ManagerForm form = form();
        BindingResult result = new BeanPropertyBindingResult(form, "managerForm");
        when(managerAccountService.create(form, ADMIN_ID)).thenThrow(new BusinessException("E4207"));
        when(managerAccountService.findAll()).thenReturn(List.of());

        String view = controller.create(form, result, admin, model, redirect);

        assertThat(view).isEqualTo("admin/managers");
        assertThat(result.getFieldError("loginId").getCode()).isEqualTo("E4207");
        assertThat(model.getAttribute("managers")).isEqualTo(List.of());
        assertThat(flash()).isEmpty();
    }

    @Test
    void create_成功なら初期パスワードと氏名とI4202を渡してリダイレクト() {
        ManagerForm form = form();
        BindingResult result = new BeanPropertyBindingResult(form, "managerForm");
        when(managerAccountService.create(form, ADMIN_ID)).thenReturn("Abcd234567");

        String view = controller.create(form, result, admin, model, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        assertThat(flash())
                .containsEntry("initialPassword", "Abcd234567")
                .containsEntry("initialPasswordName", "山田 店長")
                .containsEntry("successMessage", "I4202");
    }

    // ---------------------------------------------------------------- stop

    @Test
    void stop_成功ならI4204と氏名を渡す() {
        when(managerAccountService.getName(5L)).thenReturn("山田");

        String view = controller.stop(5L, admin, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        verify(managerAccountService).stop(5L, ADMIN_ID);
        assertThat(flash()).containsEntry("successMessage", "I4204");
        assertThat((Object[]) flash().get("successArgs")).containsExactly("山田");
    }

    @Test
    void stop_すでに停止中ならE4208を赤のアラートで出す() {
        when(managerAccountService.getName(5L)).thenReturn("山田");
        doThrow(new BusinessException("E4208")).when(managerAccountService).stop(5L, ADMIN_ID);

        String view = controller.stop(5L, admin, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        assertThat(flash()).containsEntry("errorMessage", "E4208")
                .doesNotContainKey("successMessage");
    }

    // ---------------------------------------------------------------- resume

    @Test
    void resume_成功ならI4205() {
        String view = controller.resume(5L, admin, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        verify(managerAccountService).resume(5L, ADMIN_ID);
        assertThat(flash()).containsEntry("successMessage", "I4205");
    }

    @Test
    void resume_すでに有効ならE4209を赤のアラートで出す() {
        doThrow(new BusinessException("E4209")).when(managerAccountService).resume(5L, ADMIN_ID);

        String view = controller.resume(5L, admin, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        assertThat(flash()).containsEntry("errorMessage", "E4209")
                .doesNotContainKey("successMessage");
    }

    // ---------------------------------------------------------------- resetPassword

    @Test
    void resetPassword_新しいパスワードと氏名とI4206を渡してリダイレクト() {
        when(managerAccountService.getName(5L)).thenReturn("山田");
        when(managerAccountService.resetPassword(5L, ADMIN_ID)).thenReturn("Newp234567");

        String view = controller.resetPassword(5L, admin, redirect);

        assertThat(view).isEqualTo("redirect:/admin/managers");
        assertThat(flash())
                .containsEntry("initialPassword", "Newp234567")
                .containsEntry("initialPasswordName", "山田")
                .containsEntry("successMessage", "I4206");
    }
}

package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import jp.numajo.fgroup.shift.dto.StaffCreatedResult;
import jp.numajo.fgroup.shift.dto.StaffDetailView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.form.StaffForm;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.SkillService;
import jp.numajo.fgroup.shift.service.StaffService;

/**
 * ManagerStaffController（SM-03：スタッフ登録・編集）のテスト。
 *
 * <p>
 * 行き先とフラッシュの値、エラーの項目を確かめる。URL は ManagerStaffControllerMvcTests、画面の描画・権限・CSRF は ManagerStaffFlowTests で見る。
 * </p>
 *
 * <table>
 * <caption>登録・更新の結果と行き先</caption>
 * <tr><th>操作</th><th>入力チェック</th><th>Service の結果</th><th>行き先</th></tr>
 * <tr><td>登録</td><td>エラー</td><td>（呼ばない）</td><td>フォームを再表示</td></tr>
 * <tr><td>登録</td><td>OK</td><td>登録できた</td><td>編集画面へ（初期パスワード・I2202）</td></tr>
 * <tr><td>登録</td><td>OK</td><td>E2208</td><td>フォームを再表示（loginId のエラー）</td></tr>
 * <tr><td>登録</td><td>OK</td><td>E2209</td><td>フォームを再表示（skillIds のエラー）</td></tr>
 * <tr><td>更新</td><td>エラー</td><td>（呼ばない）</td><td>フォームを再表示</td></tr>
 * <tr><td>更新</td><td>OK</td><td>残りの割当 0</td><td>編集画面へ（I2204）</td></tr>
 * <tr><td>更新</td><td>OK</td><td>残りの割当 2</td><td>編集画面へ（I2204・W2201）</td></tr>
 * <tr><td>更新</td><td>OK</td><td>E2208</td><td>フォームを再表示（loginId のエラー）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class ManagerStaffControllerEditTests {

    private static final String FORM_VIEW = "manager/staff-form";

    @Mock
    private StaffService staffService;
    @Mock
    private SkillService skillService;

    private ManagerStaffController controller;
    private Model model;
    private RedirectAttributesModelMap redirect;
    private LoginUser manager;

    @BeforeEach
    void setUp() {
        controller = new ManagerStaffController(staffService, skillService);
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        User user = new User();
        user.setId(99L);
        user.setLoginId("manager1");
        user.setRole(Role.MANAGER);
        user.setActive(true);
        manager = new LoginUser(user, false);
    }

    private static StaffForm form() {
        StaffForm form = new StaffForm();
        form.setName("山田 太郎");
        form.setNameKana("やまだ たろう");
        form.setLoginId("yamada");
        return form;
    }

    private static BindingResult bindingResult(StaffForm form) {
        return new BeanPropertyBindingResult(form, "staffForm");
    }

    private static Skill skill(Long id, String name) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName(name);
        return skill;
    }

    private static StaffDetailView detail() {
        return new StaffDetailView(1L, LocalDateTime.of(2099, 1, 1, 9, 0), "2099/01/01 09:00", false, null, null);
    }

    private Map<String, ?> flash() {
        return redirect.getFlashAttributes();
    }

    // ---------------------------------------------------------------- 登録画面・登録

    @Test
    void newForm_登録画面の値を入れる() {
        List<Skill> skills = List.of(skill(10L, "レジ"));
        when(skillService.findAll()).thenReturn(skills);

        String actual = controller.newForm(model);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(model.getAttribute("staffForm")).isInstanceOf(StaffForm.class);
        assertThat(((StaffForm) model.getAttribute("staffForm")).isActive()).isTrue();
        assertThat(model.getAttribute("skills")).isEqualTo(skills);
        assertThat(model.getAttribute("isNew")).isEqualTo(true);
    }

    @Test
    void create_入力エラーならフォームを再表示してServiceを呼ばない() {
        StaffForm form = form();
        BindingResult result = bindingResult(form);
        result.rejectValue("name", "E2201");

        String actual = controller.create(form, result, manager, model, redirect);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(model.getAttribute("isNew")).isEqualTo(true);
        verify(staffService, never()).create(any(), any());
    }

    @Test
    void create_登録できたら編集画面へ移り初期パスワードとI2202を渡す() {
        StaffForm form = form();
        when(staffService.create(form, 99L)).thenReturn(new StaffCreatedResult(7L, "Abcdef2345"));

        String actual = controller.create(form, bindingResult(form), manager, model, redirect);

        assertThat(actual).isEqualTo("redirect:/manager/staff/7");
        assertThat(flash().get("initialPassword")).isEqualTo("Abcdef2345");
        assertThat(flash().get("successMessage")).isEqualTo("I2202");
    }

    @Test
    void create_ログインIDが使われていればloginIdのエラーで再表示する() {
        StaffForm form = form();
        BindingResult result = bindingResult(form);
        when(staffService.create(form, 99L)).thenThrow(new BusinessException("E2208"));

        String actual = controller.create(form, result, manager, model, redirect);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(result.getFieldError("loginId").getCode()).isEqualTo("E2208");
        assertThat(flash()).isEmpty();
    }

    @Test
    void create_存在しない職能ならskillIdsのエラーで再表示する() {
        StaffForm form = form();
        BindingResult result = bindingResult(form);
        when(staffService.create(form, 99L)).thenThrow(new BusinessException("E2209"));

        String actual = controller.create(form, result, manager, model, redirect);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(result.getFieldError("skillIds").getCode()).isEqualTo("E2209");
    }

    // ---------------------------------------------------------------- 編集画面・更新

    @Test
    void edit_編集画面の値を入れる() {
        StaffForm form = form();
        StaffDetailView detail = detail();
        List<Skill> skills = List.of(skill(10L, "レジ"));
        when(staffService.getForEdit(1L)).thenReturn(form);
        when(staffService.getDetail(1L)).thenReturn(detail);
        when(skillService.findAll()).thenReturn(skills);

        String actual = controller.edit(1L, model);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(model.getAttribute("staffForm")).isSameAs(form);
        assertThat(model.getAttribute("staff")).isSameAs(detail);
        assertThat(model.getAttribute("skills")).isEqualTo(skills);
        assertThat(model.getAttribute("isNew")).isEqualTo(false);
    }

    @Test
    void update_入力エラーならフォームを再表示してServiceを呼ばない() {
        StaffForm form = form();
        BindingResult result = bindingResult(form);
        result.rejectValue("loginId", "E2207");
        when(staffService.getDetail(1L)).thenReturn(detail());

        String actual = controller.update(1L, form, result, manager, model, redirect);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(model.getAttribute("isNew")).isEqualTo(false);
        verify(staffService, never()).update(any(), any(), any());
    }

    @Test
    void update_割当が残っていなければI2204だけ渡す() {
        StaffForm form = form();
        when(staffService.update(1L, form, 99L)).thenReturn(0);

        String actual = controller.update(1L, form, bindingResult(form), manager, model, redirect);

        assertThat(actual).isEqualTo("redirect:/manager/staff/1");
        assertThat(flash().get("successMessage")).isEqualTo("I2204");
        assertThat(flash()).doesNotContainKey("warningMessage");
    }

    @Test
    void update_割当が残っていればW2201も渡す() {
        StaffForm form = form();
        when(staffService.update(1L, form, 99L)).thenReturn(2);

        String actual = controller.update(1L, form, bindingResult(form), manager, model, redirect);

        assertThat(actual).isEqualTo("redirect:/manager/staff/1");
        assertThat(flash().get("successMessage")).isEqualTo("I2204");
        assertThat(flash().get("warningMessage")).isEqualTo("W2201");
        assertThat((Object[]) flash().get("warningArgs")).containsExactly("山田 太郎", 2);
    }

    @Test
    void update_ログインIDが使われていればloginIdのエラーで再表示する() {
        StaffForm form = form();
        BindingResult result = bindingResult(form);
        when(staffService.update(eq(1L), eq(form), eq(99L))).thenThrow(new BusinessException("E2208"));
        when(staffService.getDetail(1L)).thenReturn(detail());

        String actual = controller.update(1L, form, result, manager, model, redirect);

        assertThat(actual).isEqualTo(FORM_VIEW);
        assertThat(result.getFieldError("loginId").getCode()).isEqualTo("E2208");
        assertThat(model.getAttribute("isNew")).isEqualTo(false);
    }

    // ---------------------------------------------------------------- パスワード初期化

    @Test
    void resetPassword_編集画面へ移り新しいパスワードとI2205を渡す() {
        when(staffService.resetPassword(1L, 99L)).thenReturn("Newpass234");

        String actual = controller.resetPassword(1L, manager, redirect);

        assertThat(actual).isEqualTo("redirect:/manager/staff/1");
        assertThat(flash().get("initialPassword")).isEqualTo("Newpass234");
        assertThat(flash().get("successMessage")).isEqualTo("I2205");
    }
}

package jp.numajo.fgroup.shift.controller.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.dto.StaffRowView;
import jp.numajo.fgroup.shift.service.SkillService;
import jp.numajo.fgroup.shift.service.StaffService;

/**
 * ManagerStaffController（SM-02：スタッフ一覧）のテスト。
 *
 * <p>
 * 検索文字と状態の整え方を確かめる。画面の表示は、画面の確認（97_テスト計画書）で見る。
 * </p>
 *
 * <table>
 * <caption>検索文字の整え方（ManagerStaffController#list）</caption>
 * <tr><th>渡された検索文字</th><th>Service に渡す値</th></tr>
 * <tr><td>null</td><td>null</td></tr>
 * <tr><td>空文字</td><td>null</td></tr>
 * <tr><td>空白だけ</td><td>null</td></tr>
 * <tr><td>前後に空白あり</td><td>空白を取った文字</td></tr>
 * <tr><td>50文字</td><td>そのまま</td></tr>
 * <tr><td>51文字</td><td>50文字で切る</td></tr>
 * </table>
 *
 * <table>
 * <caption>状態の受け取り方（ManagerStaffController#list）</caption>
 * <tr><th>status</th><th>Service に渡す値</th></tr>
 * <tr><td>null</td><td>ACTIVE</td></tr>
 * <tr><td>不正な文字</td><td>ACTIVE</td></tr>
 * <tr><td>ALL</td><td>ALL</td></tr>
 * <tr><td>ACTIVE</td><td>ACTIVE</td></tr>
 * <tr><td>INACTIVE</td><td>INACTIVE</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class ManagerStaffControllerTests {

    @Mock
    private StaffService staffService;
    @Mock
    private SkillService skillService;

    private ManagerStaffController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = new ManagerStaffController(staffService, skillService);
        model = new ExtendedModelMap();
    }

    @Test
    void list_一覧のテンプレート名を返す() {
        String actual = controller.list(null, null, model);

        assertThat(actual).isEqualTo("manager/staff-list");
    }

    @Test
    void list_Serviceの結果をstaffListに入れる() {
        List<StaffRowView> rows = List.of(new StaffRowView(1L, "山田", "やまだ", "yamada", List.of(), false, true, false));
        when(staffService.search(any(), any())).thenReturn(rows);

        controller.list(null, null, model);

        assertThat(model.getAttribute("staffList")).isEqualTo(rows);
    }

    @Test
    void list_状態がnullならACTIVE() {
        controller.list(null, null, model);

        verify(staffService).search(isNull(), eq(StaffStatusFilter.ACTIVE));
        assertThat(model.getAttribute("status")).isEqualTo("ACTIVE");
    }

    @Test
    void list_状態が不正ならACTIVE() {
        controller.list(null, "XXX", model);

        verify(staffService).search(isNull(), eq(StaffStatusFilter.ACTIVE));
        assertThat(model.getAttribute("status")).isEqualTo("ACTIVE");
    }

    @Test
    void list_状態にINACTIVEを渡すとINACTIVE() {
        controller.list(null, "INACTIVE", model);

        verify(staffService).search(isNull(), eq(StaffStatusFilter.INACTIVE));
        assertThat(model.getAttribute("status")).isEqualTo("INACTIVE");
    }

    @Test
    void list_状態にALLを渡すとALL() {
        controller.list(null, "ALL", model);

        verify(staffService).search(isNull(), eq(StaffStatusFilter.ALL));
        assertThat(model.getAttribute("status")).isEqualTo("ALL");
    }

    @Test
    void list_検索文字がnullならnullのまま() {
        controller.list(null, null, model);

        verify(staffService).search(isNull(), any());
        assertThat(model.getAttribute("keyword")).isNull();
    }

    @Test
    void list_検索文字が空ならnullにする() {
        controller.list("", null, model);

        verify(staffService).search(isNull(), any());
        assertThat(model.getAttribute("keyword")).isNull();
    }

    @Test
    void list_検索文字が空白だけならnullにする() {
        controller.list("   ", null, model);

        verify(staffService).search(isNull(), any());
        assertThat(model.getAttribute("keyword")).isNull();
    }

    @Test
    void list_検索文字の前後の空白を取る() {
        controller.list("  やま  ", null, model);

        verify(staffService).search(eq("やま"), any());
        assertThat(model.getAttribute("keyword")).isEqualTo("やま");
    }

    @Test
    void list_検索文字が50文字ならそのまま() {
        String keyword = "あ".repeat(50);

        controller.list(keyword, null, model);

        verify(staffService).search(eq(keyword), any());
    }

    @Test
    void list_検索文字が51文字なら50文字で切る() {
        String keyword = "あ".repeat(51);

        controller.list(keyword, null, model);

        verify(staffService).search(eq("あ".repeat(50)), any());
        assertThat(model.getAttribute("keyword")).isEqualTo("あ".repeat(50));
    }
}

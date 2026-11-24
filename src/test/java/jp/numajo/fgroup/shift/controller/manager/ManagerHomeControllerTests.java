package jp.numajo.fgroup.shift.controller.manager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import jp.numajo.fgroup.shift.dto.DashboardView;
import jp.numajo.fgroup.shift.service.DashboardService;

/**
 * ManagerHomeController（SM-01）の単体テスト（20-SM-01 2.1）。
 *
 * <p>
 * DashboardService はモックにし、MockMvc（standalone）で model と画面名を確かめる。分岐はない（命令網羅のみ）。
 * 画面（HTML）の描画・権限は ManagerHomeFlowTests で確かめる。
 * </p>
 */
class ManagerHomeControllerTests {

    @Test
    void 初期表示_DashboardViewをdashboardに入れてmanager_homeを返す() throws Exception {
        DashboardService dashboardService = mock(DashboardService.class);
        DashboardView view = new DashboardView(List.of(), List.of(), 0, List.of(), List.of());
        when(dashboardService.getDashboard()).thenReturn(view);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ManagerHomeController(dashboardService))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();

        mvc.perform(get("/manager/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/home"))
                .andExpect(model().attribute("dashboard", view));
        verify(dashboardService).getDashboard();
    }
}

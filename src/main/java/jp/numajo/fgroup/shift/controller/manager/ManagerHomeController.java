package jp.numajo.fgroup.shift.controller.manager;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jp.numajo.fgroup.shift.service.DashboardService;
import lombok.RequiredArgsConstructor;

/**
 * SM-01 ダッシュボード。20-SM-01 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/manager/home")
public class ManagerHomeController {

    private final DashboardService dashboardService;

    /** 初期表示（20-SM-01 2.1）。 */
    @GetMapping
    public String home(Model model) {
        model.addAttribute("dashboard", dashboardService.getDashboard());
        return "manager/home";
    }
}

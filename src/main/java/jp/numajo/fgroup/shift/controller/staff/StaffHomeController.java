package jp.numajo.fgroup.shift.controller.staff;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.StaffHomeService;
import lombok.RequiredArgsConstructor;

/**
 * SB-01 ホーム（次の出勤）。20-SB-01 2章。
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/home")
public class StaffHomeController {

    private final StaffHomeService staffHomeService;
    private final Clock clock;

    /**
     * 初期表示（20-SB-01 2.1）。
     *
     * @param ym カレンダーの月（yyyy-MM）。ない・形が違うときは今月
     */
    @GetMapping
    public String home(@RequestParam(required = false) String ym,
                       @AuthenticationPrincipal LoginUser loginUser,
                       Model model) {
        model.addAttribute("home", staffHomeService.getHome(loginUser.getUserId(), toYearMonth(ym)));
        return "staff/home";
    }

    /** ym を YearMonth にする。null・形が違う（2026-13、abc など）ときは今月（30-06 2章）。 */
    private YearMonth toYearMonth(String ym) {
        if (ym == null) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(ym);
        } catch (DateTimeParseException e) {
            return YearMonth.now(clock);
        }
    }
}

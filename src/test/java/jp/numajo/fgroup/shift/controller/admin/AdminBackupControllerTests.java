package jp.numajo.fgroup.shift.controller.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jp.numajo.fgroup.shift.config.AppProperties;
import jp.numajo.fgroup.shift.constant.BackupStatus;
import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.dto.BackupResult;
import jp.numajo.fgroup.shift.dto.BackupRow;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.security.LoginUser;
import jp.numajo.fgroup.shift.service.BackupService;

/**
 * AdminBackupController（SA-04）の単体テスト（20-SA-04 2章、97_テスト計画書 4.2）。
 *
 * <p>
 * BackupService はモックにし、MockMvc（standalone）で model・フラッシュ・リダイレクト先を確かめる。
 * 画面（HTML）の描画・権限・DB への記録は AdminBackupFlowTests で確かめる。
 * </p>
 *
 * <pre>
 * 判定                                 | 真                         | 偽                | テスト
 * create が BusinessException を投げる | errorMessage＝E4402         | 結果を見る         | 作成中_E4402
 * result.status() == SUCCESS           | successMessage＝I4402（ファイル名） | errorMessage＝E4401 | 成功_I4402・失敗_E4401
 * </pre>
 */
class AdminBackupControllerTests {

    private BackupService backupService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        backupService = mock(BackupService.class);
        AppProperties props = new AppProperties();
        props.getBackup().setDir("C:/shift-management/backup");
        mvc = MockMvcBuilders.standaloneSetup(new AdminBackupController(backupService, props))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        User u = new User();
        u.setId(1L);
        u.setLoginId("admin");
        u.setRole(Role.ADMIN);
        u.setActive(true);
        LoginUser loginUser = new LoginUser(u, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 画面を表示する_新しい50件と保存先をmodelに入れる() throws Exception {
        List<BackupRow> rows = List.of(new BackupRow(1L, null, "2026/10/20 18:40", "a.sql", 1L,
                BackupStatus.SUCCESS, "システム管理者"));
        when(backupService.findRecent(50)).thenReturn(rows);

        mvc.perform(get("/admin/backups"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backups"))
                .andExpect(model().attribute("backups", rows))
                .andExpect(model().attribute("backupDir", "C:/shift-management/backup"));
        verify(backupService).findRecent(50);
    }

    @Test
    void 作成_成功_I4402とファイル名() throws Exception {
        when(backupService.create(1L)).thenReturn(new BackupResult(BackupStatus.SUCCESS, "backup_1.sql"));

        mvc.perform(post("/admin/backups"))
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attribute("successMessage", "I4402"))
                .andExpect(flash().attribute("successArgs", new Object[] { "backup_1.sql" }))
                .andExpect(flash().attributeCount(2));
    }

    @Test
    void 作成_失敗_E4401() throws Exception {
        when(backupService.create(1L)).thenReturn(new BackupResult(BackupStatus.FAILED, "backup_1.sql"));

        mvc.perform(post("/admin/backups"))
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attribute("errorMessage", "E4401"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void 作成_ほかのバックアップを作成中_E4402() throws Exception {
        when(backupService.create(1L)).thenThrow(new BusinessException("E4402"));

        mvc.perform(post("/admin/backups"))
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attribute("errorMessage", "E4402"))
                .andExpect(flash().attributeExists("errorArgs"));
    }
}

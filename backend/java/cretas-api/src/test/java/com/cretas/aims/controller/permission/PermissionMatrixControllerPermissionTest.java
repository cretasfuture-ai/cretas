package com.cretas.aims.controller.permission;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.permission.PermissionMatrixViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限矩阵只读端点的门禁契约 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
 *
 * <p>这张表把<b>所有</b>角色对<b>所有</b>模块的权限一次性摊开, 是侦察用的高价值目标 ——
 * 门禁一旦漏, 任何登录账号都能拿到完整的权限地图。所以阴性对照挑的是两个
 * 「侧边栏很宽、但按定义不该碰权限管理」的角色 (生产经理 / 销售主管)。
 *
 * <p>阴性对照的写法沿用 #628 的形状: 断言的不只是 403, 还有
 * {@code meta.requiredPermissions} —— 措辞/状态码要能证明拦住它的是<b>那道闸</b>,
 * 而不是别的什么东西碰巧也返回了 403 (measurement-and-wiring 形态 B: 外层闸赢,
 * 内层闸变成到不了的代码)。
 */
@ExtendWith(MockitoExtension.class)
class PermissionMatrixControllerPermissionTest {

    private static final String URL = "/api/mobile/F006/permissions/matrix";

    @Mock private PermissionMatrixViewService matrixViewService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private PlatformRolePermissionRepository platformRolePermissionRepository;
    @Mock private FactoryModuleConfigRepository factoryModuleConfigRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PermissionMatrixController controller = new PermissionMatrixController(matrixViewService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(
                        permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    @Test
    void productionManagerIsRejectedBySystemGateBeforeTheMatrixIsBuilt() throws Exception {
        User user = user(2001L, "production_manager");
        when(userRepository.findById(2001L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read", "system:read_write"))
                .thenReturn(false);

        mockMvc.perform(get(URL).requestAttr("userId", 2001L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.meta.role").value("production_manager"))
                .andExpect(jsonPath("$.meta.module").value("system"))
                .andExpect(jsonPath("$.meta.requireAll").value(false))
                .andExpect(jsonPath("$.meta.requiredPermissions.length()").value(2))
                .andExpect(jsonPath("$.meta.requiredPermissions[0].module").value("system"))
                .andExpect(jsonPath("$.meta.requiredPermissions[0].action").value("read"))
                .andExpect(jsonPath("$.meta.requiredPermissions[1].module").value("system"))
                .andExpect(jsonPath("$.meta.requiredPermissions[1].action").value("read_write"));

        verify(matrixViewService, never()).build(any());
    }

    @Test
    void salesManagerIsRejectedBySystemGateBeforeTheMatrixIsBuilt() throws Exception {
        User user = user(2002L, "sales_manager");
        when(userRepository.findById(2002L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read", "system:read_write"))
                .thenReturn(false);

        mockMvc.perform(get(URL).requestAttr("userId", 2002L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.role").value("sales_manager"))
                .andExpect(jsonPath("$.meta.module").value("system"))
                .andExpect(jsonPath("$.meta.requiredPermissions.length()").value(2));

        verify(matrixViewService, never()).build(any());
    }

    @Test
    void anonymousRequestWithoutUserIdIs401() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(matrixViewService, never()).build(any());
    }

    @Test
    void factorySuperAdminReachesTheServiceThroughTheSameInterceptor() throws Exception {
        User user = user(2003L, "factory_super_admin");
        when(userRepository.findById(2003L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read", "system:read_write"))
                .thenReturn(true);
        when(matrixViewService.build("F006")).thenReturn(new PermissionMatrixView(
                "F006",
                List.of("system"),
                List.of(new PermissionMatrixView.RoleRef("viewer", "查看者", "factory", 50)),
                java.util.Map.of("viewer", java.util.Map.of("system", "-")),
                java.util.Map.of("viewer", java.util.Map.of("system", "r")),
                List.of(new PermissionMatrixView.Diff("viewer", "system", "-", "r", "override")),
                List.of(),
                List.of(),
                List.of()));

        mockMvc.perform(get(URL).requestAttr("userId", 2003L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.factoryId").value("F006"))
                .andExpect(jsonPath("$.data.diffs.length()").value(1))
                .andExpect(jsonPath("$.data.diffs[0].kind").value("override"));

        verify(matrixViewService).build("F006");
    }

    /**
     * 🔴 用<b>真的</b> {@link PermissionServiceImpl} + prod L1 里那几行 {@code system} 复现:
     * 这个页面今天<b>把「权限管理员」自己关在门外</b>。
     *
     * <p>prod {@code platform_role_permissions} 里 {@code permission_admin × system = '-'},
     * 而硬编码兜底把 permission_admin 当成超管 (全模块 rw)。L1 有行 ⇒ L1 赢 ⇒ 兜底那格
     * 永远到不了 ⇒ {@code system:read} 为 false。
     *
     * <p>本轮 owner 裁定只读, 所以<b>不改 L1</b>; 这条断言把现状钉住, 让 step 2 改 L1 时
     * 必须显式地把它改红再改绿, ⛔ 不允许悄悄地变。
     */
    @Test
    void prodL1ShapeShowsPermissionAdminIsLockedOutWhileSuperAdminsAreNot() {
        PermissionServiceImpl real = new PermissionServiceImpl();
        ReflectionTestUtils.setField(real, "platformRepo", platformRolePermissionRepository);
        ReflectionTestUtils.setField(real, "factoryConfigRepo", factoryModuleConfigRepository);

        when(factoryModuleConfigRepository.findByFactoryIdAndConfigVersion(eq("F006"), eq(1)))
                .thenReturn(List.of());
        stubL1("permission_admin", "system", "-");
        stubL1("production_manager", "system", "-");
        stubL1("sales_manager", "system", "-");
        stubL1("department_admin", "system", "r");

        assertThat(real.hasPermission(user(2004L, "permission_admin"), "system:read"))
                .as("prod L1 的 permission_admin×system='-' 让权限管理员进不了权限矩阵页")
                .isFalse();
        assertThat(real.hasPermission(user(2001L, "production_manager"), "system:read")).isFalse();
        assertThat(real.hasPermission(user(2002L, "sales_manager"), "system:read")).isFalse();
        // 阳性对照: 同一条解析链上必须有人是 true, 否则「全 false」也可能是仪器坏了
        assertThat(real.hasPermission(user(2005L, "department_admin"), "system:read")).isTrue();
        assertThat(real.hasPermission(user(2003L, "factory_super_admin"), "system:read")).isTrue();
    }

    private void stubL1(String role, String module, String level) {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode(role);
        row.setModuleCode(module);
        row.setPermissionLevel(level);
        when(platformRolePermissionRepository
                .findByRoleCodeAndModuleCodeAndDeletedAtIsNull(role, module))
                .thenReturn(Optional.of(row));
    }

    private static User user(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("F006");
        user.setRoleCode(roleCode);
        return user;
    }
}

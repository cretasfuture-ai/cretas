package com.cretas.aims.controller.permission;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.permission.PermissionMatrixViewService;
import com.cretas.aims.service.permission.PlatformRolePermissionWriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @Mock private PlatformRolePermissionWriteService writeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PermissionMatrixController controller =
                new PermissionMatrixController(matrixViewService, writeService);
        // 与 WebMvcConfig 同序: PermissionInterceptor(order 3) → RequireRoleInterceptor(order 4)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(
                        permissionService, userRepository, new ObjectMapper()),
                        new RequireRoleInterceptor(new ObjectMapper()))
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

    /**
     * 🔴 2026-11-18 (PROTO-DEMO-PERM): 权限矩阵是全平台的表, 工厂总监看都不该看到。
     * 他过得了 system:read (超管短路) —— 拦住他的必须是 @RequireRole 那道闸, 措辞要能证明是它。
     */
    @Test
    void factorySuperAdminCannotReadThePlatformMatrix() throws Exception {
        User user = user(2003L, "factory_super_admin");
        when(userRepository.findById(2003L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read", "system:read_write"))
                .thenReturn(true);

        mockMvc.perform(factoryActor(get(URL), 2003L, "factory_super_admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("当前角色无权访问此接口，需要: platform_admin"));

        verify(matrixViewService, never()).build(any());
    }

    @Test
    void platformAdminReadsTheMatrix() throws Exception {
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

        mockMvc.perform(platformActor(get(URL), 9001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.factoryId").value("F006"))
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

    // ==================== step-2: PUT /matrix/{role}/{module} ====================

    private static final String WRITE_URL = "/api/mobile/F006/permissions/matrix/sales_manager/warehouse";

    @Test
    void salesManagerCannotWriteAndTheRejectionNamesTheSystemReadWriteGate() throws Exception {
        User user = user(2002L, "sales_manager");
        when(userRepository.findById(2002L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read_write")).thenReturn(false);

        mockMvc.perform(put(WRITE_URL).requestAttr("userId", 2002L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"rw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.meta.role").value("sales_manager"))
                .andExpect(jsonPath("$.meta.requiredPermissions.length()").value(1))
                .andExpect(jsonPath("$.meta.requiredPermissions[0].module").value("system"))
                .andExpect(jsonPath("$.meta.requiredPermissions[0].action").value("read_write"));

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void productionManagerCannotWriteEither() throws Exception {
        User user = user(2001L, "production_manager");
        when(userRepository.findById(2001L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read_write")).thenReturn(false);

        mockMvc.perform(put(WRITE_URL).requestAttr("userId", 2001L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"-\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.module").value("system"));

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anonymousWriteIs401() throws Exception {
        mockMvc.perform(put(WRITE_URL).contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"rw\"}"))
                .andExpect(status().isUnauthorized());
        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    /**
     * 🔴 越权阴性对照 (PROTO-DEMO-PERM): 工厂超管 / 权限管理员都过得了 system:read_write
     * (超管短路 / V20261101_37 的 rw), 修复前能改全平台 L1 —— 一个工厂改了所有工厂。
     * 现在必须 403, 且写服务一次都没被调用。
     */
    @Test
    void factorySuperAdminCannotWriteThePlatformTable() throws Exception {
        User user = user(2003L, "factory_super_admin");
        when(userRepository.findById(2003L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read_write")).thenReturn(true);

        mockMvc.perform(factoryActor(put(WRITE_URL), 2003L, "factory_super_admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"rw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前角色无权访问此接口，需要: platform_admin"));

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void permissionAdminWithSystemReadWriteCannotWriteEither() throws Exception {
        User admin = user(2004L, "permission_admin");
        when(userRepository.findById(2004L)).thenReturn(Optional.of(admin));
        when(permissionService.hasAnyPermission(admin, "system:read_write")).thenReturn(true);

        mockMvc.perform(factoryActor(put(WRITE_URL), 2004L, "permission_admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"r\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前角色无权访问此接口，需要: platform_admin"));

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    /** 工厂令牌里把角色写成 platform_admin 也不算 (RequireRoleInterceptor 只信平台令牌)。 */
    @Test
    void factoryTokenClaimingPlatformAdminRoleIsStillRejected() throws Exception {
        User user = user(2006L, "platform_admin");
        when(userRepository.findById(2006L)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "system:read_write")).thenReturn(true);

        mockMvc.perform(factoryActor(put(WRITE_URL), 2006L, "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"rw\"}"))
                .andExpect(status().isForbidden());

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    /**
     * 第二道: 方法体内自己再判一次平台身份 —— 拦截器漏挂 / 注解被删时仍然拒绝。
     * 直接调方法 (绕过全部拦截器) 模拟「闸没接上」。
     */
    @Test
    void writeCellRejectsFactoryActorEvenWithoutInterceptors() {
        PermissionMatrixController controller = new PermissionMatrixController(matrixViewService, writeService);
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 2003L, "F006", "factory_super_admin"));

        assertThatThrownBy(() -> controller.writeCell("F006", "sales_manager", "warehouse",
                new PermissionMatrixController.WriteCellRequest("rw", null), req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));

        verify(writeService, never()).write(any(), any(), any(), any(), any(), any());
    }

    /** 阳性对照: 平台管理员写得进去, 审计操作人取自平台身份 (id + 角色), 不去 users 表按 id 撞人。 */
    @Test
    void platformAdminReachesTheWriterAsThePlatformActor() throws Exception {
        PlatformRolePermission reread = new PlatformRolePermission();
        reread.setRoleCode("sales_manager");
        reread.setModuleCode("warehouse");
        reread.setPermissionLevel("r");
        org.mockito.ArgumentCaptor<User> actor = org.mockito.ArgumentCaptor.forClass(User.class);
        when(writeService.write(actor.capture(), eq("F006"), eq("sales_manager"), eq("warehouse"), eq("r"), eq("仓管协同")))
                .thenReturn(new PlatformRolePermissionWriteService.WriteResult(reread, 501L, "-"));

        mockMvc.perform(platformActor(put(WRITE_URL), 9001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"r\",\"reason\":\"仓管协同\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("sales_manager"))
                .andExpect(jsonPath("$.data.oldLevel").value("-"))
                .andExpect(jsonPath("$.data.level").value("r"))
                .andExpect(jsonPath("$.data.auditId").value(501));

        assertThat(actor.getValue().getId()).isEqualTo(9001L);
        assertThat(actor.getValue().getRoleCode()).isEqualTo("platform_admin");
        verify(userRepository, never()).findById(9001L);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder factoryActor(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Long id, String role) {
        return b.requestAttr("userId", id).requestAttr("role", role)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, "F006", role));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder platformActor(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Long id) {
        return b.requestAttr("userId", id).requestAttr("role", "platform_admin")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, id, "PLATFORM", "platform_admin"));
    }

    /**
     * V20261101_37 之后的 prod 形状: permission_admin × system = 'rw'。用真的 PermissionServiceImpl
     * 证明 permission_admin 现在两道门 (只读 system:read / 写 system:read_write) 都过得去,
     * 而上面那条 '-' 的测试仍然钉着「种子不改就进不来」—— 两条一起读: 变的是数据, 不是判定链。
     */
    @Test
    void afterFlyway37PermissionAdminPassesBothGates() {
        PermissionServiceImpl real = new PermissionServiceImpl();
        ReflectionTestUtils.setField(real, "platformRepo", platformRolePermissionRepository);
        ReflectionTestUtils.setField(real, "factoryConfigRepo", factoryModuleConfigRepository);
        when(factoryModuleConfigRepository.findByFactoryIdAndConfigVersion(eq("F006"), eq(1)))
                .thenReturn(List.of());
        stubL1("permission_admin", "system", "rw");
        stubL1("sales_manager", "system", "-");

        User admin = user(2004L, "permission_admin");
        assertThat(real.hasPermission(admin, "system:read")).isTrue();
        assertThat(real.hasPermission(admin, "system:read_write")).isTrue();
        assertThat(real.hasAnyPermission(admin, "system:read_write")).isTrue();
        // 阴性对照: 同一条链上销售主管仍然两道都不过
        assertThat(real.hasPermission(user(2002L, "sales_manager"), "system:read_write")).isFalse();
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

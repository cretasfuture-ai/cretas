package com.cretas.aims.security;

import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RBAC unit tests for {@link AttachmentPermissionResolver} — Sprint1-Fix-K2.
 *
 * <p>Covers the four high-risk cases PR_AUDIT flagged on #658:
 * <ol>
 *   <li>warehouse_manager attempting PAYMENT_VOUCHER read → 403 (no finance perm)</li>
 *   <li>finance_manager attempting PAYMENT_VOUCHER read → allowed</li>
 *   <li>warehouse_manager attempting QUALITY_CHECK read → 403 (no quality perm)</li>
 *   <li>factory_super_admin → always bypass</li>
 * </ol>
 * Plus upload guardrails (content-type whitelist + 10MB size cap).
 */
@DisplayName("AttachmentPermissionResolver RBAC 单测")
@ExtendWith(MockitoExtension.class)
class AttachmentPermissionResolverTest {

    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private AttachmentPermissionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AttachmentPermissionResolver(permissionService, userRepository);
    }

    private User userWithRole(Long id, FactoryUserRole role) {
        User u = new User();
        u.setId(id);
        u.setRoleCode(role.name());
        return u;
    }

    // ==================== Entity-type RBAC ====================

    @Test
    @DisplayName("❌ warehouse_manager 调 PAYMENT_VOUCHER read → 403 (无 finance:read 权限)")
    void warehouseManager_paymentVoucher_read_forbidden() {
        User warehouseMgr = userWithRole(101L, FactoryUserRole.warehouse_manager);
        when(permissionService.hasAnyPermission(eq(warehouseMgr), any(String[].class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(warehouseMgr, EntityType.PAYMENT_VOUCHER));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("PAYMENT_VOUCHER"), "错误信息应包含 entityType");
    }

    @Test
    @DisplayName("✅ finance_manager 调 PAYMENT_VOUCHER read → 通过 (有 finance:read 权限)")
    void financeManager_paymentVoucher_read_allowed() {
        User financeMgr = userWithRole(102L, FactoryUserRole.finance_manager);
        when(permissionService.hasAnyPermission(eq(financeMgr), any(String[].class))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireRead(financeMgr, EntityType.PAYMENT_VOUCHER));
    }

    @Test
    @DisplayName("❌ warehouse_manager 调 QUALITY_CHECK read → 403 (无 quality:read 权限)")
    void warehouseManager_qualityCheck_read_forbidden() {
        User warehouseMgr = userWithRole(101L, FactoryUserRole.warehouse_manager);
        when(permissionService.hasAnyPermission(eq(warehouseMgr), any(String[].class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(warehouseMgr, EntityType.QUALITY_CHECK));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("✅ factory_super_admin 总是 bypass — PAYMENT_VOUCHER / QUALITY_CHECK / 所有 entityType")
    void factorySuperAdmin_bypasses_allChecks() {
        User admin = userWithRole(1L, FactoryUserRole.factory_super_admin);
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.PAYMENT_VOUCHER));
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.QUALITY_CHECK));
        assertDoesNotThrow(() -> resolver.requireWrite(admin, EntityType.PAYMENT_VOUCHER));
        // 不应该 call hasAnyPermission — admin 直接 bypass
        verify(permissionService, never()).hasAnyPermission(any(), any(String[].class));
    }

    @Test
    @DisplayName("✅ platform_admin 总是 bypass")
    void platformAdmin_bypasses_allChecks() {
        User admin = userWithRole(0L, FactoryUserRole.platform_admin);
        assertDoesNotThrow(() -> resolver.requireRead(admin, EntityType.PAYMENT_VOUCHER));
        verify(permissionService, never()).hasAnyPermission(any(), any(String[].class));
    }

    @Test
    @DisplayName("❌ 未登录 (user=null) → 401")
    void nullUser_throws401() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead((User) null, EntityType.PURCHASE_ORDER));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("❌ entityType=null → 400")
    void nullEntityType_throws400() {
        User user = userWithRole(101L, FactoryUserRole.warehouse_manager);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(user, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("✅ requireWrite 仅 read_write 权限通过 (read 单独不够)")
    void requireWrite_demandsReadWrite() {
        User salesMgr = userWithRole(103L, FactoryUserRole.sales_manager);
        // Mock: 单独 sales:read 不够 — resolver 应只问 sales:read_write
        when(permissionService.hasAnyPermission(eq(salesMgr), eq(new String[]{"sales:read_write"}))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireWrite(salesMgr, EntityType.CUSTOMER));
        verify(permissionService).hasAnyPermission(eq(salesMgr), eq(new String[]{"sales:read_write"}));
    }

    @Test
    @DisplayName("✅ 采购收货附件写入按仓储职责授权")
    void purchaseReceipt_write_usesWarehousePermissions() {
        User warehouseWorker = userWithRole(104L, FactoryUserRole.warehouse_worker);
        String[] expected = {"warehouse:read_write", "inventory:write"};
        when(permissionService.hasAnyPermission(eq(warehouseWorker), eq(expected))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireWrite(warehouseWorker, EntityType.PURCHASE_RECEIPT));
        verify(permissionService).hasAnyPermission(eq(warehouseWorker), eq(expected));
    }

    @Test
    @DisplayName("✅ 采购人员只能只读查看采购收货凭证")
    void purchaseReceipt_read_preservesProcurementTraceability() {
        User procurementManager = userWithRole(105L, FactoryUserRole.procurement_manager);
        String[] expected = {"warehouse:read", "warehouse:read_write", "procurement:read", "procurement:read_write"};
        when(permissionService.hasAnyPermission(eq(procurementManager), eq(expected))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireRead(procurementManager, EntityType.PURCHASE_RECEIPT));
        verify(permissionService).hasAnyPermission(eq(procurementManager), eq(expected));
    }

    // ==================== Upload guardrails ====================

    @Test
    @DisplayName("❌ 上传 .exe (application/octet-stream) → 400 not in whitelist")
    void uploadGuardrail_rejectsExecutable() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("application/octet-stream", 1024L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
    }

    @Test
    @DisplayName("❌ 上传 20MB 文件 → 400 exceeds 10MB cap")
    void uploadGuardrail_rejectsOversize() {
        long twentyMb = 20L * 1024L * 1024L;
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("application/pdf", twentyMb));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("10MB"));
    }

    @Test
    @DisplayName("✅ 上传 pdf / jpeg / png / xlsx / docx / mp4 在 whitelist 内")
    void uploadGuardrail_acceptsWhitelisted() {
        assertDoesNotThrow(() -> resolver.validateUploadRequest("application/pdf", 1024L));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("image/jpeg", 1024L));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("image/png", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null));
        assertDoesNotThrow(() -> resolver.validateUploadRequest("video/mp4", 5L * 1024L * 1024L));
    }

    @Test
    @DisplayName("❌ Content-Type 为空 → 400")
    void uploadGuardrail_rejectsBlankContentType() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.validateUploadRequest("", null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("✅ Content-Type 大写 → 正常 (whitelist 比对小写)")
    void uploadGuardrail_caseInsensitive() {
        assertDoesNotThrow(() -> resolver.validateUploadRequest("Application/PDF", null));
    }

    // ==================== Request-based: actor type decides before any users lookup ====================

    /** Shared id: platform_admins.id and users.id are independent sequences, so they collide. */
    private static final long SHARED_ID = 7L;

    private static HttpServletRequest requestAs(AuthenticatedActorType type, String factoryId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", SHARED_ID);
        req.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, SHARED_ID, factoryId, role));
        return req;
    }

    private static HttpServletRequest platformAdmin(String role) {
        return requestAs(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
    }

    private static HttpServletRequest factoryUser(String role) {
        return requestAs(AuthenticatedActorType.FACTORY_USER, "F999", role);
    }

    @Test
    @DisplayName("✅ factory user: requireRead(request) 按 users 行 + 权限判定")
    void factoryUser_request_resolvesUsersRow() {
        User financeMgr = userWithRole(SHARED_ID, FactoryUserRole.finance_manager);
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(financeMgr));
        when(permissionService.hasAnyPermission(eq(financeMgr), any(String[].class))).thenReturn(true);

        assertDoesNotThrow(() -> resolver.requireRead(factoryUser("finance_manager"), EntityType.PAYMENT_VOUCHER));
        assertEquals(SHARED_ID, resolver.requireWrite(factoryUser("finance_manager"), EntityType.PAYMENT_VOUCHER));
    }

    @Test
    @DisplayName("❌ 没有 actor (只有裸 userId) → 401, 不拿 userId 去查 users")
    void noActor_unauthorized_evenWithRawUserId() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", SHARED_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(req, EntityType.PAYMENT_VOUCHER));
        assertEquals(401, ex.getCode());
        BusinessException nullReq = assertThrows(BusinessException.class,
                () -> resolver.requireRead((HttpServletRequest) null, EntityType.PAYMENT_VOUCHER));
        assertEquals(401, nullReq.getCode());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("✅ 平台 super_admin, users 里同 id 是无财务权限的仓管 → 仍放行, 不查 users")
    void platformBypassRole_notJudgedAsCollidingFactoryUser() {
        // The colliding factory account would be refused PAYMENT_VOUCHER.
        User collidingWarehouseMgr = userWithRole(SHARED_ID, FactoryUserRole.warehouse_manager);
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(collidingWarehouseMgr));
        lenient().when(permissionService.hasAnyPermission(eq(collidingWarehouseMgr), any(String[].class)))
                .thenReturn(false);

        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            assertDoesNotThrow(() -> resolver.requireRead(platformAdmin(role), EntityType.PAYMENT_VOUCHER), role);
            assertEquals(SHARED_ID, resolver.requireWrite(platformAdmin(role), EntityType.PAYMENT_VOUCHER), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("❌ 平台 auditor, users 里同 id 是工厂超管 → 仍 403 (不借用工厂超管的 bypass)")
    void platformNonBypassRole_refused_evenIfCollidingFactoryUserIsAdmin() {
        // The colliding factory account would bypass every check.
        User collidingFactoryAdmin = userWithRole(SHARED_ID, FactoryUserRole.factory_super_admin);
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(collidingFactoryAdmin));

        for (String role : new String[]{"auditor", "operation_admin", "system_admin"}) {
            BusinessException read = assertThrows(BusinessException.class,
                    () -> resolver.requireRead(platformAdmin(role), EntityType.PAYMENT_VOUCHER), role);
            assertEquals(403, read.getCode(), role);
            assertTrue(read.getMessage().contains("平台角色 [" + role + "]"), read.getMessage());
            BusinessException write = assertThrows(BusinessException.class,
                    () -> resolver.requireWrite(platformAdmin(role), EntityType.PAYMENT_VOUCHER), role);
            assertEquals(403, write.getCode(), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("阴性对照: 同 id 的工厂用户保持自己的结果 (仓管 403 / 工厂超管放行)")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        User warehouseMgr = userWithRole(SHARED_ID, FactoryUserRole.warehouse_manager);
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(warehouseMgr));
        when(permissionService.hasAnyPermission(eq(warehouseMgr), any(String[].class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireRead(factoryUser("warehouse_manager"), EntityType.PAYMENT_VOUCHER));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("仓储主管"), "工厂用户的拒绝措辞来自他自己的角色: " + ex.getMessage());

        User factoryAdmin = userWithRole(SHARED_ID, FactoryUserRole.factory_super_admin);
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(factoryAdmin));
        assertDoesNotThrow(() -> resolver.requireRead(factoryUser("factory_super_admin"), EntityType.PAYMENT_VOUCHER));
    }
}

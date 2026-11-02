package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.alerts.AlertRuleCreateTool;
import com.cretas.aims.ai.tool.impl.crm.OrderTodayTool;
import com.cretas.aims.ai.tool.impl.foodsafety.SsopExecutionTodayTool;
import com.cretas.aims.ai.tool.impl.material.MaterialRejectionReasonTool;
import com.cretas.aims.ai.tool.impl.workdesk.PriceHistoryQueryTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.execution.TrustedExecutionContext;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S6 follow-up (organizer re-review of #1316, B2/B3/WRITE): the strict sweep's examples, checked on the AI path
 * itself — the real {@link ToolPermissionChain}, {@link ToolRbacEnforcer}, {@link ToolRbacGuard} and the real
 * default {@link PermissionServiceImpl} matrix, with the real tool classes.
 *
 * <ul>
 *   <li>{@code order_today} returned full sales orders to everyone while {@code order_list} was gated: now the sales
 *       order gate ({@code SalesController#listOrders}).</li>
 *   <li>{@code price_history_query} returned purchase-price aggregates to operators / QC: now the #1305 purchase-price
 *       rule ({@code procurement:read}).</li>
 *   <li>{@code material_rejection_reason} (a WRITE-mode tool, skipped before) read inventory data ungated.</li>
 *   <li>{@code ssop_execution_today}: SSOP records are only on the admin food-safety page.</li>
 *   <li>{@code alert_rule_create} keeps its {@code system:read_write} code and now also requires the admin roles
 *       ({@link ToolExecutor#getRequiredRoles}): {@code restaurant_owner} holds the code but not the alert pages.</li>
 * </ul>
 */
@DisplayName("严格扫描的例子: 真实权限链 + 真实默认矩阵下, AI 路径与直接接口同口径")
class GatedDataStrictSweepPermissionTest {

    private static final String FACTORY = "F006";

    private final AtomicLong ids = new AtomicLong(97_000L);
    private final Map<String, Long> userIds = new HashMap<>();
    private UserRepository users;
    private ToolPermissionChain chain;

    @BeforeEach
    void setUp() {
        PermissionServiceImpl matrix = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
        users = mock(UserRepository.class);
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", matrix);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        chain = new ToolPermissionChain(enforcer, new FrontlineAiWriteGrantService(
                mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class),
                new WriteGuardService(),
                mock(com.cretas.aims.service.intent.IntentConfigManagementService.class),
                mock(ToolRegistry.class)));
    }

    @Test
    void orderTodayFollowsTheSalesOrderGate() {
        ToolExecutor tool = GatedDataScan.instance(OrderTodayTool.class);
        assertThat(denied(tool, "operator")).isTrue();
        assertThat(denied(tool, "quality_inspector")).isTrue();
        assertThat(denied(tool, "hr_admin")).isTrue();
        assertThat(denied(tool, "sales_manager")).isFalse();
    }

    @Test
    void priceHistoryFollowsThePurchasePriceRule() {
        ToolExecutor tool = GatedDataScan.instance(PriceHistoryQueryTool.class);
        assertThat(tool.getRequiredPermissions()).containsExactly("procurement:read");
        assertThat(denied(tool, "operator")).isTrue();
        assertThat(denied(tool, "quality_inspector")).isTrue();
        assertThat(denied(tool, "procurement_manager")).isFalse();
    }

    @Test
    void aWriteModeToolThatReadsInventoryIsGatedLikeTheInventoryPages() {
        ToolExecutor tool = GatedDataScan.instance(MaterialRejectionReasonTool.class);
        assertThat(ToolAccessModes.isWrite(tool)).isTrue();
        assertThat(denied(tool, "hr_admin")).isTrue();
        assertThat(denied(tool, "warehouse_manager")).isFalse();
    }

    @Test
    void ssopRecordsAreAdminOnlyLikeTheFoodSafetyPage() {
        ToolExecutor tool = GatedDataScan.instance(SsopExecutionTodayTool.class);
        assertThat(denied(tool, "quality_manager")).isTrue();
        assertThat(denied(tool, "permission_admin")).isFalse();
    }

    @Test
    void requiredRolesAreEnforcedOnTopOfTheCodes() {
        ToolExecutor tool = GatedDataScan.instance(AlertRuleCreateTool.class);
        assertThat(tool.getRequiredRoles()).containsExactlyInAnyOrder("factory_super_admin", "permission_admin");
        // restaurant_owner holds system:read_write (the code alone would admit them) but not the alert pages.
        assertThat(denied(tool, "restaurant_owner")).isTrue();
        assertThat(denied(tool, "factory_super_admin")).isFalse();
        // Platform admins pass role requirements, as RequireRoleInterceptor lets them through @RequireRole.
        assertThat(denied(tool, "platform_admin")).isFalse();
    }

    @Test
    void requiredRolesFailClosedWhenTheCallerCannotBeResolved() {
        ToolExecutor tool = GatedDataScan.instance(AlertRuleCreateTool.class);
        Map<String, Object> context = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(context, FACTORY, 424242L, "factory_super_admin");
        when(users.findById(424242L)).thenReturn(Optional.empty());
        assertThat(chain.denyReason(tool, FACTORY, "factory_super_admin", null, context)).isPresent();
    }

    private boolean denied(ToolExecutor tool, String role) {
        long id = userIds.computeIfAbsent(role, r -> {
            long next = ids.incrementAndGet();
            User u = new User();
            u.setId(next);
            u.setFactoryId(FACTORY);
            u.setRoleCode(r);
            u.setIsActive(true);
            when(users.findById(next)).thenReturn(Optional.of(u));
            return next;
        });
        Map<String, Object> context = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(context, FACTORY, id, role);
        // WRITE-mode tools: confirmation is not the layer under test.
        context = new WriteGuardService().withServerConfirmation(context);
        return chain.denyReason(tool, FACTORY, role, null, context).isPresent();
    }
}

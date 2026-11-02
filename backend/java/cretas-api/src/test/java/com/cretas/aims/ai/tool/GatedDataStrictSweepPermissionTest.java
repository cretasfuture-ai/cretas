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
 *   <li>{@code material_rejection_reason} (declared WRITE, so skipped before, though it only reads) read inventory data
 *       ungated: now READ and gated like the inventory pages.</li>
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
    private ToolRbacGuard guard;

    @BeforeEach
    void setUp() {
        PermissionServiceImpl matrix = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
        users = mock(UserRepository.class);
        guard = new ToolRbacGuard();
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
    void priceHistoryRefusesAWarehouseManagerWithNoPriceConfigAndNeverReadsPurchases() throws Exception {
        // #1305: with no L1/L2 rows only procurement_manager sees purchase prices, although the fallback matrix
        // gives warehouse_manager procurement:read. The whole result is purchase prices, so the tool itself refuses.
        PriceHistoryQueryTool tool = new PriceHistoryQueryTool();
        com.cretas.aims.repository.inventory.PurchaseOrderItemRepository items =
                mock(com.cretas.aims.repository.inventory.PurchaseOrderItemRepository.class);
        ReflectionTestUtils.setField(tool, "purchaseOrderItemRepository", items);
        ReflectionTestUtils.setField(tool, "purchaseOrderRepository",
                mock(com.cretas.aims.repository.inventory.PurchaseOrderRepository.class));
        ReflectionTestUtils.setField(tool, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        PermissionServiceImpl matrix = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
        ReflectionTestUtils.setField(tool, "priceVisibility", new com.cretas.aims.security.PriceVisibility(matrix));
        ReflectionTestUtils.setField(tool, "userRepository", users);

        String refused = run(tool, "warehouse_manager");
        assertThat(refused).contains("\"success\":false").contains("采购价");
        org.mockito.Mockito.verify(items, org.mockito.Mockito.never()).findByMaterialTypeId(org.mockito.ArgumentMatchers.anyString());

        org.mockito.Mockito.when(items.findByMaterialTypeId("M1")).thenReturn(java.util.List.of());
        assertThat(run(tool, "procurement_manager")).contains("\"success\":true");
    }

    @Test
    void attendanceToolsBindTheSubjectToTheCallerUnlessTheyReadHr() throws Exception {
        // V20261103_140 opens ATTENDANCE_TODAY / HISTORY / STATUS to every role, like /time-clock/today etc.;
        // those endpoints bind the subject to the JWT user, so the tools must not take any userId from the model.
        com.cretas.aims.service.TimeClockService clock = mock(com.cretas.aims.service.TimeClockService.class);
        for (ToolExecutor tool : java.util.List.<ToolExecutor>of(
                new com.cretas.aims.ai.tool.impl.hr.AttendanceTodayTool(),
                new com.cretas.aims.ai.tool.impl.hr.AttendanceHistoryTool(),
                new com.cretas.aims.ai.tool.impl.hr.AttendanceStatusTool())) {
            ReflectionTestUtils.setField(tool, "timeClockService", clock);
            ReflectionTestUtils.setField(tool, "rbacGuard", guard);
            ReflectionTestUtils.setField(tool, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

            String other = runWith(tool, "sales_manager", "{\"userId\":\"22\"}");
            assertThat(other).as(tool.getToolName()).contains("\"success\":false").contains("本人");
            org.mockito.Mockito.verifyNoInteractions(clock);

            denied(tool, "sales_manager");
            long own = userIds.get("sales_manager");
            assertThat(runWith(tool, "sales_manager", "{\"userId\":\"" + own + "\"}")).as(tool.getToolName())
                    .doesNotContain("本人的考勤");
            assertThat(runWith(tool, "hr_admin", "{\"userId\":\"22\"}")).as(tool.getToolName())
                    .doesNotContain("本人的考勤");
            org.mockito.Mockito.clearInvocations(clock);
        }
    }

    private String runWith(ToolExecutor tool, String role, String args) throws Exception {
        denied(tool, role);
        Map<String, Object> context = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(context, FACTORY, userIds.get(role), role);
        return tool.execute(com.cretas.aims.ai.dto.ToolCall.of("t1", tool.getToolName(), args), context);
    }

    private String run(ToolExecutor tool, String role) throws Exception {
        denied(tool, role); // registers the user for this role
        Map<String, Object> context = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(context, FACTORY, userIds.get(role), role);
        return tool.execute(com.cretas.aims.ai.dto.ToolCall.of("t1", tool.getToolName(), "{\"materialTypeId\":\"M1\"}"), context);
    }

    @Test
    void materialRejectionReasonIsAReadToolGatedLikeTheInventoryPages() throws Exception {
        // It was declared WRITE (and so skipped by the read sweep) although it only reads expired batches.
        ToolExecutor tool = GatedDataScan.instance(MaterialRejectionReasonTool.class);
        assertThat(ToolAccessModes.isWrite(tool)).isFalse();
        // Evidence for the WriteGuardService suffix exception (MATERIAL_REJECTION_REASON): it only reads.
        GatedDataScan scan = GatedDataScan.run();
        assertThat(scan.toolCalls.get("material_rejection_reason"))
                .extracting(GatedDataScan.DataMethod::name)
                .allMatch(name -> name.startsWith("get") || name.startsWith("find") || name.startsWith("count"));
        assertThat(new WriteGuardService().hasWriteSuffix("material_rejection_reason")).isFalse();
        assertThat(denied(tool, "hr_admin")).isTrue();
        assertThat(denied(tool, "warehouse_manager")).isFalse();
    }

    @Test
    void foodSafetyFollowsTheQualityRuling() {
        // Organizer ruling 2026-11-03: SSOP / food samples / nutrition / additives have no App read page; quality owns them.
        ToolExecutor ssop = GatedDataScan.instance(SsopExecutionTodayTool.class);
        assertThat(denied(ssop, "quality_manager")).isFalse();
        assertThat(denied(ssop, "quality_inspector")).isFalse();
        assertThat(denied(ssop, "sales_manager")).isTrue();
        // The pre-production check also admits production read: workshop supervisors and team leaders keep it.
        ToolExecutor gate = GatedDataScan.instance(com.cretas.aims.ai.tool.impl.foodsafety.SsopProductionGateCheckTool.class);
        assertThat(denied(gate, "workshop_supervisor")).isFalse();
        assertThat(denied(gate, "team_leader")).isFalse();
        assertThat(denied(gate, "sales_manager")).isTrue();
    }

    @Test
    void coldChainReadsAreOpenToWarehouseQualityAndEquipmentButWritesNeedTheWrite() {
        ToolExecutor temps = GatedDataScan.instance(com.cretas.aims.ai.tool.impl.foodsafety.ColdChainTempQueryTool.class);
        assertThat(denied(temps, "warehouse_manager")).isFalse();
        assertThat(denied(temps, "quality_manager")).isFalse();
        assertThat(denied(temps, "sales_manager")).isTrue();
        ToolExecutor register = GatedDataScan.instance(com.cretas.aims.ai.tool.impl.foodsafety.ColdChainEquipmentRegisterTool.class);
        assertThat(register.getRequiredPermissions()).containsExactly("equipment:read_write");
        assertThat(denied(register, "warehouse_manager")).isTrue();
    }

    @Test
    void headcountIsProductionOrDashboardReadNotHr() {
        ToolExecutor tool = GatedDataScan.instance(com.cretas.aims.ai.tool.impl.dataop.OnlineStaffCountTool.class);
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrder("production:read", "dashboard:read");
        assertThat(denied(tool, "production_manager")).isFalse();
        assertThat(denied(tool, "workshop_supervisor")).isFalse();
    }

    @Test
    void platformAdminsPassLegacyRoleGatesLikeRequireRole() {
        ToolExecutor tool = GatedDataScan.instance(com.cretas.aims.ai.tool.impl.system.OperationLogQueryTool.class);
        assertThat(tool.hasPermission("platform_admin")).isFalse();
        assertThat(denied(tool, "platform_admin")).isFalse();
        assertThat(denied(tool, "production_manager")).isTrue();
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

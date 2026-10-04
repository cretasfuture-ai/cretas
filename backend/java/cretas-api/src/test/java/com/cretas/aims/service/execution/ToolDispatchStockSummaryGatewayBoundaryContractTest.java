package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ToolGatewayDataScopeBoundaryContractTest;
import com.cretas.aims.ai.tool.gateway.ToolGatewayDataScopeBoundaryContractTest.StockFixture;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolDispatchStockSummaryGatewayBoundaryContractTest {
    @Test
    void defaultOffDispatchReturnsScopedAndPriceProtectedConcreteStockOnReusedWorker() throws Exception {
        Fixture f = new Fixture();
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (long actor : new long[] {101L, 102L, 150L, 101L}) {
                worker.submit(() -> {
                    ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
                    IntentExecuteResponse response = f.execute(actor, false);
                    assertThat(response.getStatus()).isEqualTo("SUCCESS");
                    var batches = f.stock.mapper.valueToTree(response.getResultData()).path("batches");
                    java.util.ArrayList<String> actualIds = new java.util.ArrayList<>();
                    batches.forEach(batch -> actualIds.add(batch.path("id").asText()));
                    assertThat(actualIds).containsExactlyElementsOf(actor == 150L ? List.of("A", "B")
                            : actor == 101L ? List.of("A") : List.of("B"));
                    ToolGatewayDataScopeBoundaryContractTest.assertStock(f.stock.mapper.valueToTree(
                            Map.of("data", response.getResultData())), actor == 150L ? List.of("A", "B")
                            : actor == 101L ? List.of("A") : List.of("B"), actor == 150L);
                    assertThat(response.getMetadata()).containsEntry("executionBoundary", "TOOL_EXECUTION_GATEWAY")
                            .containsEntry("gatewayStatus", "SUCCEEDED");
                    ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
                }).get();
            }
            verifyNoInteractions(f.redundancy, f.correction, f.correctionAgent, f.verifier, f.validator, f.learning);
            assertThat(f.stock.queries).containsExactly("F006:[101]", "F006:[102]", "F006:ALL", "F006:[101]");
        } finally { worker.shutdownNow(); }
    }

    @Test
    void unknownInactiveForeignAndCanonicalDenialCannotExecuteOrReadCache() {
        Fixture f = new Fixture();
        for (long actor : new long[] {999L, 104L, 103L}) {
            assertThat(f.execute(actor, false).getStatus()).isIn("DENIED", "PERMISSION_DENIED");
            ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
        }
        assertThat(f.stock.queries).isEmpty();
        verifyNoInteractions(f.redundancy);
        Fixture denied = new Fixture();
        denied.stock.platformRows.add(PlatformRolePermission.builder().roleCode("warehouse_worker")
                .moduleCode("inventory").permissionLevel("-").build());
        denied.stock.platformRows.add(PlatformRolePermission.builder().roleCode("warehouse_worker")
                .moduleCode("warehouse").permissionLevel("-").build());
        assertThat(denied.execute(101L, false).getStatus()).isEqualTo("PERMISSION_DENIED");
        assertThat(denied.stock.queries).isEmpty();
        verifyNoInteractions(denied.redundancy);
    }

    @Test
    void concreteFailureDoesNotRetryOrCacheAndNextWorkerCallHasFreshScope() throws Exception {
        Fixture f = new Fixture();
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                f.stock.failQueries = true;
                assertThat(f.execute(101L, false).getStatus()).isEqualTo("FAILED");
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
                f.stock.failQueries = false;
                var response = f.execute(102L, false);
                assertThat(response.getStatus()).isEqualTo("SUCCESS");
                ToolGatewayDataScopeBoundaryContractTest.assertStock(f.stock.mapper.valueToTree(
                        Map.of("data", response.getResultData())), List.of("B"), false);
                ToolGatewayDataScopeBoundaryContractTest.assertCleanWorker();
            }).get();
            verifyNoInteractions(f.redundancy, f.correction, f.correctionAgent, f.verifier, f.validator);
            assertThat(f.stock.queries).containsExactly("F006:[102]");
        } finally { worker.shutdownNow(); }
    }

    @Test
    void stockPreviewMissingRegistrationAndFactoryPolicyDisabledFailClosed() {
        Fixture f = new Fixture();
        assertThat(f.execute(101L, true).getStatus()).isEqualTo("PREVIEW_UNSUPPORTED");
        assertThat(f.stock.queries).isEmpty();
        when(f.stock.tools.getExecutor("material_stock_summary")).thenReturn(Optional.empty());
        assertThat(f.execute(101L, false).getStatus()).isEqualTo("DENIED");
        assertThat(f.stock.queries).isEmpty();
        verifyNoInteractions(f.redundancy);
        Fixture disabled = new Fixture();
        FactoryToolConfig config = new FactoryToolConfig(); config.setEnabled(false);
        when(disabled.stock.configs.findByFactoryIdAndToolName("F006", "material_stock_summary"))
                .thenReturn(Optional.of(config));
        assertThat(disabled.execute(101L, false).getStatus()).isEqualTo("DENIED");
        assertThat(disabled.stock.queries).isEmpty();
        verifyNoInteractions(disabled.redundancy);
    }

    private static final class Fixture {
        final StockFixture stock = new StockFixture();
        final ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        final SelfCorrectionService correction = mock(SelfCorrectionService.class);
        final CorrectionAgentService correctionAgent = mock(CorrectionAgentService.class);
        final ExternalVerifierService verifier = mock(ExternalVerifierService.class);
        final ToolResultValidatorService validator = mock(ToolResultValidatorService.class);
        final ParameterExtractionLearningService learning = mock(ParameterExtractionLearningService.class);
        final ToolDispatchService dispatch;
        Fixture() {
            when(stock.tools.isToolEnabledForFactory("F006", "material_stock_summary")).thenReturn(true);
            ToolRbacGuard guard = new ToolRbacGuard();
            ReflectionTestUtils.setField(guard, "userRepository", stock.users);
            ReflectionTestUtils.setField(guard, "permissionService", stock.canonical);
            ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
            ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
            dispatch = new ToolDispatchService(stock.tools, stock.mapper, null, redundancy,
                    correction, correctionAgent, verifier, validator, learning);
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
            ReflectionTestUtils.setField(dispatch, "toolExecutionGateway", stock.gateway);
            ReflectionTestUtils.setField(dispatch, "legacyToolMigrationRegistry", stock.legacy);
            ReflectionTestUtils.setField(dispatch, "authenticatedToolPrincipalFactory",
                    new AuthenticatedToolPrincipalFactory(stock.factories));
            assertThat(ReflectionTestUtils.getField(dispatch, "intentDispatchGatewayMigrationEnabled")).isEqualTo(false);
        }
        IntentExecuteResponse execute(long actor, boolean preview) {
            return dispatch.executeWithTool(stock.stock, "F006", IntentExecuteRequest.builder()
                    .userInput("查看库存").mode("READ").previewOnly(preview)
                    .context(Map.of("factoryId", "F007", "userId", 150L, "userRole", "factory_super_admin",
                            "canViewPrices", true, "permissions", List.of("*:read_write"))).build(),
                    AIIntentConfig.builder().intentCode("MATERIAL_STOCK_SUMMARY").intentName("库存汇总")
                            .intentCategory("MATERIAL").toolName("material_stock_summary").build(),
                    actor, "warehouse_worker", null);
        }
    }
}

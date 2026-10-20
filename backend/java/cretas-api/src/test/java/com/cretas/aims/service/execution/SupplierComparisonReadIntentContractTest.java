package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SupplierComparisonReadIntentContractTest {
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final AIIntentConfigRepository configs = mock(AIIntentConfigRepository.class);
    private final SupplierPriceComparisonTool tool = new SupplierPriceComparisonTool();

    @BeforeEach
    void registerActualReadTool() {
        when(tools.getExecutor(SupplierComparisonReadIntent.TOOL)).thenReturn(Optional.of(tool));
    }

    @Test
    void absentConfigHasReadToolBindingAndExistingPermissionMatrixInsteadOfRankingRoles() {
        AIIntentConfig result = SupplierComparisonReadIntent.resolve("F006", tools, configs).orElseThrow();
        assertThat(result.getIntentCode()).isEqualTo(SupplierComparisonReadIntent.CODE);
        assertThat(result.getToolName()).isEqualTo(tool.getToolName());
        assertThat(result.getRequiredPermission()).isEqualTo("procurement:read_write,finance:read_write");
        assertThat(result.getFactoryId()).isEqualTo("F006");
        assertThat(result.getRequiredRoles()).isNull();
        verify(configs, never()).save(any());
    }

    @Test
    void explicitInactiveConfigCannotBecomeAbsentRowDefault() {
        when(configs.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE))
                .thenReturn(Optional.of(config("F006", false)));
        assertThat(SupplierComparisonReadIntent.resolve("F006", tools, configs)).isEmpty();
    }

    @Test
    void explicitOtherFactoryConfigCannotBecomePlatformDefault() {
        when(configs.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE))
                .thenReturn(Optional.of(config("F-OTHER", true)));
        assertThat(SupplierComparisonReadIntent.resolve("F006", tools, configs)).isEmpty();
    }

    @Test
    void configuredRestrictionsArePreservedRatherThanReplacedByCodeDefaults() {
        AIIntentConfig row = config("F006", true);
        row.setRequiredRoles("[\"factory_super_admin\"]");
        row.setRequiredPermission("restricted:read");
        row.setRequiresApproval(true);
        when(configs.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE))
                .thenReturn(Optional.of(row));
        assertThat(SupplierComparisonReadIntent.resolve("F006", tools, configs)).containsSame(row);
        assertThat(row.getRequiredPermission()).isEqualTo("restricted:read");
        assertThat(row.getRequiredRoles()).isEqualTo("[\"factory_super_admin\"]");
        assertThat(row.getRequiresApproval()).isTrue();
    }

    @Test
    void repositoryFailureIsNotTreatedAsMissingConfiguration() {
        when(configs.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThat(SupplierComparisonReadIntent.resolve("F006", tools, configs)).isEmpty();
    }

    @Test
    void descriptorMustRemainEnabledReadWithDeclaredPermissions() {
        ToolExecutor changed = mock(ToolExecutor.class);
        when(changed.isEnabled()).thenReturn(true);
        when(changed.getToolName()).thenReturn(SupplierComparisonReadIntent.TOOL);
        when(changed.getVersion()).thenReturn(SupplierComparisonReadIntent.VERSION);
        when(changed.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(tools.getExecutor(SupplierComparisonReadIntent.TOOL)).thenReturn(Optional.of(changed));
        assertThat(SupplierComparisonReadIntent.resolve("F006", tools, configs)).isEmpty();
        verifyNoInteractions(configs);
    }

    @Test
    void missingFactoryNeverResolvesAnUnscopedIntent() {
        assertThat(SupplierComparisonReadIntent.resolve("", tools, configs)).isEmpty();
        verifyNoInteractions(configs);
    }

    @Test
    void actualOrchestratorPhraseShortcutUsesTheRegisteredReadMetadata() {
        IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(orchestrator, "knowledgeBase", knowledge);
        ReflectionTestUtils.setField(orchestrator, "toolRegistry", tools);
        ReflectionTestUtils.setField(orchestrator, "intentConfigRepository", configs);
        IntentMatchResult result = ReflectionTestUtils.invokeMethod(orchestrator,
                "tryOrchestratorPhraseShortcut", "供应商价格比较：没有真实报价请明确不可比价", "F006", "FACTORY");
        assertThat(result).isNotNull();
        assertThat(result.getBestMatch().getIntentCode()).isEqualTo(SupplierComparisonReadIntent.CODE);
        assertThat(result.getBestMatch().getToolName()).isEqualTo(SupplierComparisonReadIntent.TOOL);
    }

    @Test
    void existingIntentAndCentralToolGatesStillRejectAnUnprivilegedActor() {
        ToolRbacGuard guard = mock(ToolRbacGuard.class);
        IntentPermissionGate intentGate = new IntentPermissionGate();
        ReflectionTestUtils.setField(intentGate, "rbacGuard", guard);
        ToolRbacEnforcer toolGate = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(toolGate, "rbacGuard", guard);
        AIIntentConfig intent = SupplierComparisonReadIntent.resolve("F006", tools, configs).orElseThrow();
        assertThat(intentGate.check(intent, 99L, "operator").isDenied()).isTrue();
        assertThat(toolGate.check(tool, Map.of("factoryId", "F006", "userId", 99L)).isAllowed()).isFalse();
    }

    private static AIIntentConfig config(String factory, boolean active) {
        return AIIntentConfig.builder().intentCode(SupplierComparisonReadIntent.CODE)
                .intentName("comparison").toolName(SupplierComparisonReadIntent.TOOL)
                .factoryId(factory).isActive(active).build();
    }
}

package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FactoryIntentCatalogContractTest {
    @Test
    void configWritesCannotResurrectRetiredCapabilitiesOrAliasRows() {
        var repository = mock(AIIntentConfigRepository.class);
        var service = new IntentConfigManagementServiceImpl(repository);
        for (String code : List.of("RESTAURANT_OLD", "EFFICIENCY_COMPARE_ALIAS")) {
            var row = AIIntentConfig.builder().intentCode(code).build();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createIntent(row))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateIntent(row))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        }
        verifyNoInteractions(repository);
    }
    @Test
    void canonicalCatalogRetiresDedicatedCodesWithoutDeletingSharedFactoryCapabilities() {
        assertThat(FactoryIntentCatalog.canonicalCodes()).hasSize(265);
        assertThat(FactoryIntentCatalog.retiredCodes()).hasSize(81);
        FactoryIntentCatalog.retiredCodes().forEach(code -> {
            assertThat(FactoryIntentCatalog.isRetiredIntent(code)).isTrue();
            assertThat(FactoryIntentCatalog.project(AIIntentConfig.builder().intentCode(code).build())).isNull();
        });
        assertThat(FactoryIntentCatalog.isRetiredIntent("RESTAURANT_OPS_DAYPART_PERFORMANCE")).isTrue();
        assertThat(FactoryIntentCatalog.canonicalCodes()).contains("FOOD_SAMPLE_LIFECYCLE", "HACCP_CHECKPOINT",
                "INVENTORY_GAIN_INBOUND", "SUPPLIER_PRICE_ALERT", "CAMERA_STREAMS", "SYSTEM_PROFILE_EDIT");
        assertThat(FactoryIntentCatalog.canonicalCode("EFFICIENCY_COMPARE_ALIAS")).isEqualTo("EFFICIENCY_COMPARISON");
    }

    @Test
    void projectionKeepsTenantAndPermissionFieldsAndDoesNotMutateJpaEntity() {
        AIIntentConfig source = AIIntentConfig.builder().id("tenant-row").factoryId("FACTORY-A")
                .intentCode("CAMERA_STREAMS").businessType("RESTAURANT").isActive(false)
                .requiredRoles("[\"camera_admin\"]").requiresApproval(true).build();
        AIIntentConfig projected = FactoryIntentCatalog.project(source);
        assertThat(projected).isNotSameAs(source);
        assertThat(projected.getBusinessType()).isEqualTo("FACTORY");
        assertThat(projected.getFactoryId()).isEqualTo("FACTORY-A");
        assertThat(projected.getRequiredRoles()).isEqualTo(source.getRequiredRoles());
        assertThat(projected.getRequiresApproval()).isTrue();
        assertThat(projected.getIsActive()).isFalse();
        assertThat(source.getBusinessType()).isEqualTo("RESTAURANT");
        assertThat(FactoryIntentCatalog.project(AIIntentConfig.builder().intentCode("TENANT_CUSTOM_FACTORY_QUERY").build())).isNotNull();
    }

    @Test
    void finalCachedResultCannotExposeRetiredCandidateOrGuessRunnerUp() {
        AIIntentConfig retired = AIIntentConfig.builder().intentCode("RESTAURANT_DAILY_REVENUE").build();
        var candidate = IntentMatchResult.CandidateIntent.builder().intentCode("REPORT_INVENTORY").confidence(.1).build();
        IntentMatchResult cached = IntentMatchResult.builder().bestMatch(retired).confidence(.99)
                .userInput("查营业额").topCandidates(List.of(candidate)).build();
        IntentMatchResult filtered = FactoryIntentCatalog.filterResult(cached);
        assertThat(filtered.hasMatch()).isFalse();
        assertThat(filtered.getTopCandidates()).isEmpty();
        assertThat(cached.getBestMatch()).isSameAs(retired);
        assertThat(cached.getTopCandidates()).containsExactly(candidate);
    }

    @Test
    void aliasLookupUsesCanonicalPermissionsAndRetiredLookupNeverHitsDatabase() {
        AIIntentConfigRepository repository = mock(AIIntentConfigRepository.class);
        var service = new IntentConfigManagementServiceImpl(repository);
        AIIntentConfig target = AIIntentConfig.builder().intentCode("EFFICIENCY_COMPARISON")
                .requiredRoles("[\"factory_super_admin\"]").build();
        when(repository.findByIntentCodeAndFactoryIdOrPlatform("EFFICIENCY_COMPARISON", "A"))
                .thenReturn(List.of(target));
        assertThat(service.getIntentByCode("A", "EFFICIENCY_COMPARE_ALIAS")).containsSame(target);
        assertThat(service.getIntentByCode("A", "RESTAURANT_DAILY_REVENUE")).isEmpty();
        verify(repository, never()).findByIntentCodeAndFactoryIdOrPlatform("RESTAURANT_DAILY_REVENUE", "A");
    }

    @Test
    void retiredToolCannotRegisterEvenWhenItsBeanIsPresentAndEnabled() {
        ToolExecutor retired = mock(ToolExecutor.class);
        when(retired.getToolName()).thenReturn("restaurant_unlisted_legacy_tool");
        ToolExecutor retained = mock(ToolExecutor.class);
        when(retained.getToolName()).thenReturn("material_batch_query");
        when(retained.isEnabled()).thenReturn(true);
        when(retained.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(retained.getRiskLevel()).thenReturn(ToolExecutor.RiskLevel.LOW);
        when(retained.getDomainTags()).thenReturn(Set.of("material"));
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "toolExecutors", List.of(retired, retained));
        registry.init();
        assertThat(registry.getAllToolNames()).containsExactly("material_batch_query");
        assertThat(registry.getExecutor("restaurant_unlisted_legacy_tool")).isEmpty();
    }

    @Test
    void globalSkillWritesCannotBeRoutedForFactoryPrincipalsIncludingFactoryAdmins() {
        var intent = AIIntentConfig.builder().intentCode("GOVERNANCE_SKILL_COMPOSE").build();
        var match = IntentMatchResult.builder().bestMatch(intent).confidence(.99).topCandidates(List.of()).build();
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 1L, "A", "factory_super_admin"))) {
            assertThat(FactoryIntentCatalog.filterResult(match).hasMatch()).isFalse();
        }
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "platform_admin"))) {
            assertThat(FactoryIntentCatalog.filterResult(match).hasMatch()).isTrue();
        }
    }
}

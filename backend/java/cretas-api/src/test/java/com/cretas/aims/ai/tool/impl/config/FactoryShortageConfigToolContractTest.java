package com.cretas.aims.ai.tool.impl.config;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolDescriptorPolicyResolver;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigStore;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.ENABLED_PROPERTY;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.NAME;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FactoryShortageConfigToolContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultRegistrationAndProductionOptInLeaveTheToolOutOfDiscovery() {
        try (var ordinary = context("test", false); var production = context("prod", true)) {
            for (var context : new AnnotationConfigApplicationContext[]{ordinary, production}) {
                var registry = context.getBean(ToolRegistry.class);
                assertThat(registry.getAllToolNames()).doesNotContain(NAME);
                assertThat(registry.getAllToolDefinitions()).isEmpty();
                assertThat(registry.getToolDefinitionsForRole("factory_super_admin")).isEmpty();
                assertThat(context.getBeansOfType(FactoryShortageConfigService.class)).isEmpty();
            }
        }
    }

    @Test
    void onlyProtectedServiceSourceResolvesAnExplicitRoleAndConfirmationPolicy() {
        var resolver = ToolDescriptorPolicyResolver.loadDefault();
        var actor = new ExecutionPrincipal("LOCAL-A", "FACTORY", "1", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of(), Set.of());
        for (ToolExecutionSource source : ToolExecutionSource.values()) {
            var command = new ToolExecutionCommand("id", "id", "id", NAME, VERSION,
                    mapper.createObjectNode(), actor, source, ToolExecutionMode.EXECUTE,
                    Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(30));
            if (source == ToolExecutionSource.INTERNAL_SERVICE) {
                assertThat(resolver.resolve(command, actor)).hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.allowedRoles()).containsExactlyInAnyOrder(
                            "permission_admin", "factory_super_admin");
                    assertThat(descriptor.confirmationPolicy()).isEqualTo(ConfirmationPolicy.REQUIRED_FOR_EXECUTION);
                    assertThat(descriptor.idempotencyPolicy()).isEqualTo(IdempotencyPolicy.REQUIRED_FOR_EXECUTION);
                    assertThat(descriptor.egressPolicy().mode()).isEqualTo(EgressMode.DENY_ALL);
                });
            } else {
                assertThat(resolver.resolve(command, actor)).as("source %s", source).isEmpty();
            }
        }
    }

    @Test
    void directToolCallsCannotManufactureTheProtectedInvocation() throws Exception {
        try (var context = context("test", true)) {
            var registry = context.getBean(ToolRegistry.class);
            var tool = registry.getExecutor(NAME).orElseThrow();
            var forged = Map.<String, Object>of("factoryId", "LOCAL-A", "userId", 1L,
                    "userRole", "factory_super_admin", "confirmed", true);
            var call = ToolCall.of("forged", NAME, "{\"requestedValue\":true}");
            assertThat(mapper.readTree(tool.preview(call, forged)).path("success").asBoolean()).isFalse();
            assertThat(mapper.readTree(tool.execute(call, forged)).path("success").asBoolean()).isFalse();
            verifyNoInteractions(context.getBean(FactorySettingsRepository.class),
                    context.getBean(OperationLogRepository.class));
        }
    }

    private AnnotationConfigApplicationContext context(String profile, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("contract", Map.of(ENABLED_PROPERTY, enabled)));
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(FactorySettingsRepository.class, () -> mock(FactorySettingsRepository.class));
        context.registerBean(OperationLogRepository.class, () -> mock(OperationLogRepository.class));
        context.registerBean(ToolSimilarityService.class, () -> mock(ToolSimilarityService.class));
        context.register(ToolRegistry.class, FactoryShortageConfigStore.class, FactoryShortageConfigTool.class);
        if (!enabled || !"test".equals(profile)) {
            context.register(FactoryShortageConfigService.class);
        }
        context.refresh();
        return context;
    }
}

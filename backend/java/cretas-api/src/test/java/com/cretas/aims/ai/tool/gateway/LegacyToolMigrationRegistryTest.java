package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationManifestLoader;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorCatalog;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LegacyToolMigrationRegistryTest {
    @Test
    void retiredLaneCannotResolveEvenIfOldBeanIsInjected() {
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.getExecutor("restaurant_dish_list")).thenReturn(Optional.of(mock(ToolExecutor.class)));
        var manifest = new LegacyToolMigrationManifestLoader().loadDefault();
        assertThat(manifest.tools()).isEmpty();
        var registry = new LegacyToolMigrationRegistry(tools, mock(FactoryToolConfigRepository.class),
                ToolDescriptorCatalog.loadDefault(), manifest, RuntimeToolDescriptorRegistry.loadDefault(), true);
        for (String business : Set.of("FACTORY", "RESTAURANT")) {
            var principal = new ExecutionPrincipal("A", business, "42", PrincipalType.USER, Set.of("operator"), Set.of(), Set.of());
            for (var mode : ToolExecutionMode.values()) {
                var command = new ToolExecutionCommand("r", "c", "t", "restaurant_dish_list", "1.0.0",
                        JsonNodeFactory.instance.objectNode(), principal, ToolExecutionSource.AI_INTENT_DISPATCH,
                        mode, Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(30));
                assertThat(registry.resolve(command, principal)).isEmpty();
            }
        }
    }

    @Test
    void retiringRestaurantLanePreservesApprovedFactoryPolicies() {
        var approved = RuntimeToolDescriptorRegistry.loadDefault();
        assertThat(approved.approvedToolNames()).contains("material_stock_summary", "material_batch_query",
                "material_expired_query", "smartbi_factory_analysis");
        assertThat(approved.approvedToolNames()).noneMatch(name -> name.startsWith("restaurant_"));
    }
}

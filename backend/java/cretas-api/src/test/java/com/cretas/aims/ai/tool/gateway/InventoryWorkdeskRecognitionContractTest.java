package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryWorkdeskRecognitionContractTest {
    @Test
    void shortageQuestionDoesNotBecomeABatchListBecauseItContainsAnAmbiguousPhrase() {
        IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        assertThat(knowledge.matchPhrase("我想了解有哪些原料快不够用了，先只查询", "FACTORY"))
                .contains("MATERIAL_LOW_STOCK_ALERT");
        assertThat(knowledge.matchPhrase("有哪些原料", "FACTORY"))
                .contains("MATERIAL_BATCH_QUERY");
    }

    @Test
    void batchIntentDispatchRequiresCurrentWarehousePermissionAndMatchingTenant() {
        ToolDescriptorPolicyResolver resolver = ToolDescriptorPolicyResolver.loadDefault();
        ExecutionPrincipal reader = principal("WHOLESALE_TEST", "FACTORY", Set.of("warehouse:read"));
        ToolExecutionCommand command = command(reader, ToolExecutionSource.AI_INTENT_DISPATCH);
        assertThat(resolver.resolve(command, reader)).isPresent();
        assertThat(resolver.resolve(command, principal("WHOLESALE_TEST", "FACTORY", Set.of()))).isEmpty();
        assertThat(resolver.resolve(command, principal("OTHER", "FACTORY", Set.of("warehouse:read")))).isEmpty();
        ExecutionPrincipal restaurant = principal("WHOLESALE_TEST", "RESTAURANT", Set.of("warehouse:read"));
        assertThat(resolver.resolve(command(restaurant, ToolExecutionSource.AI_INTENT_DISPATCH), restaurant)).isEmpty();
        assertThat(resolver.resolve(command(reader, ToolExecutionSource.HTTP_CONTROLLER), reader)).isEmpty();
    }

    private ExecutionPrincipal principal(String tenant, String businessType, Set<String> permissions) {
        return new ExecutionPrincipal(tenant, businessType, "1709", PrincipalType.USER,
                Set.of("warehouse_keeper"), permissions, Set.of());
    }

    private ToolExecutionCommand command(ExecutionPrincipal principal, ToolExecutionSource source) {
        return new ToolExecutionCommand("inventory-request", "inventory-correlation", "inventory-trace",
                "material_batch_query", "1.0.0", new ObjectMapper().createObjectNode(), principal,
                source, ToolExecutionMode.EXECUTE, Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(60));
    }
}

package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import com.cretas.aims.service.governance.ToolSimilarityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Disabled manual invoice tools must be absent from the executable registry. */
class InvoiceRequestRegistryReachabilityTest {

    @Test
    void disabledManualInvoiceToolsAreNotReachableFromToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "toolExecutors", List.of(
                new InvoiceRequestTool(), new InvoiceRequestFromOrderTool()));
        ReflectionTestUtils.setField(registry, "toolSimilarityService", mock(ToolSimilarityService.class));

        registry.init();

        assertThat(registry.hasExecutor("finance_invoice_request")).isFalse();
        assertThat(registry.hasExecutor("finance_invoice_request_from_order")).isFalse();
        assertThat(registry.getAllToolNames())
                .doesNotContain("finance_invoice_request", "finance_invoice_request_from_order");
    }

    @Test
    void descriptorsRemainTruthfulForCompatibilityTombstones() {
        var descriptors = new ToolDescriptorInventoryLoader().loadDefault().descriptors();

        for (String toolName : List.of("finance_invoice_request", "finance_invoice_request_from_order")) {
            var descriptor = descriptors.stream()
                    .filter(entry -> entry.toolName().equals(toolName))
                    .findFirst();
            assertThat(descriptor).as(toolName).isPresent();
            assertThat(descriptor.orElseThrow().actionType()).isEqualTo(ToolExecutor.ActionType.WRITE);
            assertThat(descriptor.orElseThrow().riskLevel()).isEqualTo(ToolExecutor.RiskLevel.HIGH);
            assertThat(descriptor.orElseThrow().requiredPermissions()).containsExactly("finance:read_write");
        }
    }
}

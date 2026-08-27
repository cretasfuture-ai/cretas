package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import com.cretas.aims.ai.tool.impl.sales.SalesCreateInvoiceTool;
import com.cretas.aims.service.governance.ToolSimilarityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Disabled manual invoice tools must be absent from the executable registry. */
class InvoiceRequestRegistryReachabilityTest {

    @Test
    void disabledManualInvoiceToolsAreNotReachableFromToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "toolExecutors", List.of(
                new InvoiceRequestTool(), new InvoiceRequestFromOrderTool(), new SalesCreateInvoiceTool()));
        ReflectionTestUtils.setField(registry, "toolSimilarityService", mock(ToolSimilarityService.class));

        registry.init();

        assertThat(registry.hasExecutor("finance_invoice_request")).isFalse();
        assertThat(registry.hasExecutor("finance_invoice_request_from_order")).isFalse();
        assertThat(registry.hasExecutor("sales_create_invoice")).isFalse();
        assertThat(registry.getAllToolNames())
                .doesNotContain("finance_invoice_request", "finance_invoice_request_from_order", "sales_create_invoice");
    }

    /**
     * ⚠️ {@code domainTags} 逐个工具写死，⛔ 不是三个都断言 {@code "finance"}。
     *
     * <p>原来的写法把三个墓碑一起断言成 {@code ["finance"]}，而 {@code sales_create_invoice}
     * 是 {@code impl.sales} 包里的销售工具，清册里也记着 {@code ["sales"]}。#185 回源码逐条核对
     * 后把这一条从 {@code [finance]} 订正成 {@code [sales]}（同一批订正还把三个工具的
     * {@code hasPermission} 由 true 改成 false）——
     * 于是这条断言守的是<b>历史</b>，不是需求：它在钉一份已经被证明写错了的清册。</p>
     *
     * <p>⛔ 订正的是「域标签是哪个」，不是把这条断言删掉：
     * WRITE / HIGH / {@code finance:read_write} 三项一个字没放松 —— 它们是这条断言真正在守的
     * 东西（开票是高风险写操作，即使工具本身已经下线，清册也不许把它记成低风险只读）。</p>
     */
    @Test
    void descriptorsRemainTruthfulForCompatibilityTombstones() {
        var descriptors = new ToolDescriptorInventoryLoader().loadDefault().descriptors();

        var expectedDomainByTool = List.of(
                Map.entry("finance_invoice_request", "finance"),
                Map.entry("finance_invoice_request_from_order", "finance"),
                Map.entry("sales_create_invoice", "sales"));

        for (var expected : expectedDomainByTool) {
            String toolName = expected.getKey();
            var descriptor = descriptors.stream()
                    .filter(entry -> entry.toolName().equals(toolName))
                    .findFirst();
            assertThat(descriptor).as(toolName).isPresent();
            assertThat(descriptor.orElseThrow().actionType()).as(toolName).isEqualTo(ToolExecutor.ActionType.WRITE);
            assertThat(descriptor.orElseThrow().riskLevel()).as(toolName).isEqualTo(ToolExecutor.RiskLevel.HIGH);
            assertThat(descriptor.orElseThrow().requiredPermissions()).as(toolName)
                    .containsExactly("finance:read_write");
            assertThat(descriptor.orElseThrow().domainTags()).as(toolName)
                    .containsExactly(expected.getValue());
        }
    }
}

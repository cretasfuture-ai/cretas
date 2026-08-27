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
     * 这三个工具虽然都开发票、都要 {@code finance:read_write}，但<b>域标签跟着实现类走</b>，
     * 不跟着权限走：{@code sales_create_invoice} 的实现类是
     * {@code com.cretas.aims.ai.tool.impl.sales.SalesCreateInvoiceTool} ⇒ {@code sales}。
     *
     * <p>⚠️ 本断言原先是一句 {@code containsExactly("finance")} 把三条一锅端，
     * 2026-08-27 #185 把清册里那一格从 {@code [finance]} 订正成 {@code [sales]}
     * （让清册与源码一致）之后它就红了 —— 断言没坏，是它守的口径被判定为错的。
     * ⇒ 逐工具写明期望，而不是删掉这条断言。</p>
     */
    private static final Map<String, String> EXPECTED_DOMAIN_TAG = Map.of(
            "finance_invoice_request", "finance",
            "finance_invoice_request_from_order", "finance",
            "sales_create_invoice", "sales");

    @Test
    void descriptorsRemainTruthfulForCompatibilityTombstones() {
        var descriptors = new ToolDescriptorInventoryLoader().loadDefault().descriptors();

        for (String toolName : List.of(
                "finance_invoice_request", "finance_invoice_request_from_order", "sales_create_invoice")) {
            var descriptor = descriptors.stream()
                    .filter(entry -> entry.toolName().equals(toolName))
                    .findFirst();
            assertThat(descriptor).as(toolName).isPresent();
            // ⚠️ 每条都带 .as(toolName): 原先只有 isPresent 带了标签, 于是这一族红出来时
            //    只说「期望 [finance] 实际 [sales]」而不说是哪个工具。
            assertThat(descriptor.orElseThrow().actionType()).as(toolName)
                    .isEqualTo(ToolExecutor.ActionType.WRITE);
            assertThat(descriptor.orElseThrow().riskLevel()).as(toolName)
                    .isEqualTo(ToolExecutor.RiskLevel.HIGH);
            assertThat(descriptor.orElseThrow().requiredPermissions()).as(toolName)
                    .containsExactly("finance:read_write");
            assertThat(descriptor.orElseThrow().domainTags()).as(toolName)
                    .containsExactly(EXPECTED_DOMAIN_TAG.get(toolName));
        }
    }
}

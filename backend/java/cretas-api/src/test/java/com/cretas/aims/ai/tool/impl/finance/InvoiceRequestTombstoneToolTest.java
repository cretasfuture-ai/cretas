package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.ai.tool.impl.sales.SalesCreateInvoiceTool;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceRequestTombstoneToolTest {

    @Test
    void amountBasedTool_isDisabledHighRiskWriteAndFailsClosed() {
        InvoiceRequestTool tool = new InvoiceRequestTool();

        assertTombstoneMetadata(tool);
        assertThatThrownBy(() -> tool.doExecute("F006", Map.of(
                "salesOrderId", "SO-001", "amount", 100), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手工开票申请已停用");
    }

    @Test
    void orderBasedTool_isDisabledHighRiskWriteAndFailsClosed() {
        InvoiceRequestFromOrderTool tool = new InvoiceRequestFromOrderTool();

        assertTombstoneMetadata(tool);
        assertThatThrownBy(() -> tool.doExecute("F006", Map.of(
                "salesOrderId", "SO-001"), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手工按订单申请开票已停用");
    }

    @Test
    void salesCreateInvoice_isDisabledAndCannotCreateManualReceivable() {
        TestSalesCreateInvoiceTool tool = new TestSalesCreateInvoiceTool();

        assertTombstoneMetadata(tool);
        assertThatThrownBy(() -> tool.invoke("F006", Map.of(
                "counterpartyId", "C-001", "amount", 100), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sales_create_invoice 已停用")
                .hasMessageContaining("禁止");
    }

    private static final class TestSalesCreateInvoiceTool extends SalesCreateInvoiceTool {
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) throws Exception {
            return super.doExecute(factoryId, params, context);
        }
    }

    private void assertTombstoneMetadata(AbstractTool tool) {
        assertThat(tool.isEnabled()).isFalse();
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.getRequiredPermissions()).containsExactly("finance:read_write");
        assertThat(tool.getActionType()).isEqualTo(AbstractTool.ActionType.WRITE);
        assertThat(tool.getRiskLevel()).isEqualTo(AbstractTool.RiskLevel.HIGH);
        assertThat(tool.getAccessMode()).isEqualTo(AbstractTool.AccessMode.WRITE);
    }
}

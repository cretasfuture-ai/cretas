package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderQueryToolTest {

    @Test
    void queriesCanonicalSalesOrderById() {
        SalesService salesService = mock(SalesService.class);
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        order.setOrderNumber("SO-001");
        when(salesService.getSalesOrderById("F001", "SO-ID-1")).thenReturn(order);

        Map<String, Object> result = new OrderQueryTool(salesService, repository)
                .doExecute("F001", Map.of("orderId", "SO-ID-1"), Map.of());

        assertThat(result.get("source")).isEqualTo("sales_orders");
        assertThat(result.get("order")).isEqualTo(OrderListTool.toSummary(order));
        verify(salesService).getSalesOrderById("F001", "SO-ID-1");
    }

    @Test
    void spokenOrderNumberUsesScopedOrderAndReportsProgress() {
        SalesService salesService = mock(SalesService.class);
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        SalesOrder order = new SalesOrder();
        order.setId("sales-id");
        order.setOrderNumber("DEMO-20261105-SO-001");
        order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
        order.setTotalAmount(new BigDecimal("100"));
        order.setPaidAmount(new BigDecimal("30"));
        when(salesService.getSalesOrderById("F006", order.getOrderNumber()))
                .thenThrow(new ResourceNotFoundException("销售订单不存在"));
        when(repository.findByFactoryIdAndOrderNumber("F006", order.getOrderNumber())).thenReturn(Optional.of(order));
        when(salesService.getSalesOrderById("F006", order.getId())).thenReturn(order);

        Map<String, Object> result = new OrderQueryTool(salesService, repository)
                .doExecute("F006", Map.of("originalUserInput", "DEMO-20261105-SO-001 这单到哪了？",
                        "orderId", "model-selected-order"), Map.of());

        assertThat(result.get("message").toString())
                .contains(order.getOrderNumber(), "当前节点：部分发货", "发货：已部分发货", "收款：已部分收款");
        verify(salesService).getSalesOrderById("F006", order.getId());
    }
}

package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.TaskBadgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务角标计数（服务端）")
class TaskBadgeControllerTest {

    @Mock private SalesOrderRepository salesOrders;
    @Mock private CustomerMaterialArrivalNoticeRepository notices;

    @Test
    @DisplayName("超过 500 条也给出真实总数（COUNT），不是第一页的条数，也不隐藏")
    void countAbove500IsExact() {
        when(salesOrders.countActionable(any(), any())).thenReturn(1234L);
        TaskBadgeController controller = new TaskBadgeController(new TaskBadgeService(salesOrders, notices));

        ApiResponse<TaskBadgeController.BadgeCount> r = controller.salesOrders("F001");

        assertEquals("KNOWN", r.getData().state());
        assertEquals(1234L, r.getData().count());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SalesOrderStatus>> terminal = ArgumentCaptor.forClass(Collection.class);
        verify(salesOrders).countActionable(org.mockito.ArgumentMatchers.eq("F001"), terminal.capture());
        assertEquals(Set.of(SalesOrderStatus.COMPLETED, SalesOrderStatus.CANCELLED, SalesOrderStatus.SHORT_CLOSED),
                Set.copyOf(terminal.getValue()));
    }

    @Test
    @DisplayName("计数算不出来：状态 UNKNOWN、没有数字，不是 0")
    void failureIsUnknownNotZero() {
        when(salesOrders.countActionable(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(notices.countByFactoryIdAndStatus("F001", CustomerMaterialArrivalStatus.PENDING_APPROVAL))
                .thenThrow(new IllegalStateException("db down"));
        TaskBadgeController controller = new TaskBadgeController(new TaskBadgeService(salesOrders, notices));

        for (var r : new TaskBadgeController.BadgeCount[] {
                controller.salesOrders("F001").getData(), controller.unorderedInbound("F001").getData()}) {
            assertEquals("UNKNOWN", r.state());
            assertNull(r.count());
        }
    }

    @Test
    @DisplayName("无订单入库按工厂统计待审批数")
    void unorderedInboundIsFactoryScoped() {
        when(notices.countByFactoryIdAndStatus("F001", CustomerMaterialArrivalStatus.PENDING_APPROVAL)).thenReturn(7L);
        TaskBadgeController controller = new TaskBadgeController(new TaskBadgeService(salesOrders, notices));
        assertEquals(7L, controller.unorderedInbound("F001").getData().count());
        verify(notices).countByFactoryIdAndStatus("F001", CustomerMaterialArrivalStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("接口权限与对应的列表接口一致（没权限看列表就读不了计数）")
    void permissionsMirrorTheListEndpoints() throws Exception {
        var sales = TaskBadgeController.class.getMethod("salesOrders", String.class).getAnnotation(RequirePermission.class);
        var inbound = TaskBadgeController.class.getMethod("unorderedInbound", String.class).getAnnotation(RequirePermission.class);
        assertNotNull(sales);
        assertNotNull(inbound);
        assertEquals(Set.of("sales:read_write", "sales:read", "finance:read", "procurement:read"), Set.of(sales.value()));
        assertEquals(Set.of("operations:read", "operations:read_write", "warehouse:read", "warehouse:read_write"),
                Set.of(inbound.value()));
    }
}

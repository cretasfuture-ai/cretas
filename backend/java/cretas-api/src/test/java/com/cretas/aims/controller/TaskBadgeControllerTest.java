package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.TaskBadgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务角标计数（服务端）")
class TaskBadgeControllerTest {

    @Mock private SalesOrderRepository salesOrders;
    @Mock private CustomerMaterialArrivalNoticeRepository notices;
    @Mock private DataScopeResolver resolver;

    private boolean pushed;

    @AfterEach
    void popScope() {
        if (pushed) DataScopeContext.pop();
    }

    private void scope(DataScope scope, Long userId) {
        DataScopeContext.push(DataScopeContext.builder().userId(userId).factoryId("F001").roleCode("sales")
                .scope(scope).column("created_by").build());
        pushed = true;
    }

    private TaskBadgeController controller() {
        TaskBadgeService service = new TaskBadgeService(salesOrders, notices);
        ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);
        return new TaskBadgeController(service);
    }

    @Test
    @DisplayName("ALL 范围：超过 500 条也给出真实总数（COUNT），不是第一页的条数，也不隐藏")
    void countAbove500IsExact() {
        scope(DataScope.ALL, 9L);
        when(salesOrders.countActionable(any(), any())).thenReturn(1234L);

        ApiResponse<TaskBadgeController.BadgeCount> r = controller().salesOrders("F001");

        assertEquals("KNOWN", r.getData().state());
        assertEquals(1234L, r.getData().count());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SalesOrderStatus>> terminal = ArgumentCaptor.forClass(Collection.class);
        verify(salesOrders).countActionable(eq("F001"), terminal.capture());
        assertEquals(Set.of(SalesOrderStatus.COMPLETED, SalesOrderStatus.CANCELLED, SalesOrderStatus.SHORT_CLOSED),
                Set.copyOf(terminal.getValue()));
    }

    @Test
    @DisplayName("SELF 范围：只数自己创建的，不碰全厂计数")
    void selfScopeCountsOnlyOwnOrders() {
        scope(DataScope.SELF, 9L);
        when(salesOrders.countActionableCreatedBy(eq("F001"), eq(9L), any())).thenReturn(4L);

        assertEquals(4L, controller().salesOrders("F001").getData().count());
        verify(salesOrders, never()).countActionable(any(), any());
    }

    @Test
    @DisplayName("SELF_AND_BELOW 范围：按下属链计数（与销售订单列表同一条链）")
    void chainScopeCountsTheCreatorChain() {
        scope(DataScope.SELF_AND_BELOW, 9L);
        when(resolver.resolveCreatedByChain(any())).thenReturn(List.of(9L, 10L, 11L));
        when(salesOrders.countActionableCreatedByIn(eq("F001"), eq(List.of(9L, 10L, 11L)), any())).thenReturn(6L);

        assertEquals(6L, controller().salesOrders("F001").getData().count());
        verify(salesOrders, never()).countActionable(any(), any());
    }

    @Test
    @DisplayName("没有数据范围上下文：失败关闭为 UNKNOWN，绝不退回全厂计数")
    void missingScopeContextFailsClosed() {
        TaskBadgeController.BadgeCount r = controller().salesOrders("F001").getData();

        assertEquals("UNKNOWN", r.state());
        assertNull(r.count());
        verifyNoInteractions(salesOrders);
    }

    @Test
    @DisplayName("没有用户 id、或本计数不会收窄的范围（CUSTOM）：UNKNOWN，不是全部")
    void scopeWithoutUserOrUnsupportedScopeIsUnknown() {
        scope(DataScope.SELF, null);
        assertEquals("UNKNOWN", controller().salesOrders("F001").getData().state());
        DataScopeContext.pop();
        pushed = false;

        scope(DataScope.CUSTOM, 9L);
        assertEquals("UNKNOWN", controller().salesOrders("F001").getData().state());
        verifyNoInteractions(salesOrders);
    }

    @Test
    @DisplayName("计数算不出来：状态 UNKNOWN、没有数字，不是 0")
    void failureIsUnknownNotZero() {
        scope(DataScope.ALL, 9L);
        when(salesOrders.countActionable(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(notices.countByFactoryIdAndStatus("F001", CustomerMaterialArrivalStatus.PENDING_APPROVAL))
                .thenThrow(new IllegalStateException("db down"));
        TaskBadgeController controller = controller();

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
        assertEquals(7L, controller().unorderedInbound("F001").getData().count());
        verify(notices).countByFactoryIdAndStatus("F001", CustomerMaterialArrivalStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("销售订单计数方法带 @DataScope（由切面建立范围）；接口权限与列表接口一致")
    void permissionsAndScopeAnnotationMirrorTheListEndpoints() throws Exception {
        var sales = TaskBadgeController.class.getMethod("salesOrders", String.class).getAnnotation(RequirePermission.class);
        var inbound = TaskBadgeController.class.getMethod("unorderedInbound", String.class).getAnnotation(RequirePermission.class);
        assertNotNull(sales);
        assertNotNull(inbound);
        assertEquals(Set.of("sales:read_write", "sales:read", "finance:read", "procurement:read"), Set.of(sales.value()));
        assertEquals(Set.of("operations:read", "operations:read_write", "warehouse:read", "warehouse:read_write"),
                Set.of(inbound.value()));
        assertNotNull(TaskBadgeService.class.getMethod("countActionableSalesOrders", String.class)
                .getAnnotation(com.cretas.aims.annotation.DataScope.class));
    }
}

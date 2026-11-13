package com.cretas.aims.security;

import com.cretas.aims.controller.inventory.PurchaseController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-13 F006 真机: 仓库工人(数据范围非「全工厂」)打开「收货」, 4 条 purchase/orders/by-status 都被路由闸以
 * DATA_SCOPE_UNSUPPORTED 拦下, 整页报错。修复后: 只有声明了行级范围的 PurchaseController#listOrdersByStatus
 * 放行, 并在控制器里按采购单创建人过滤 + 附说明; 全工厂范围仍走原服务调用, 不带说明。
 */
class PurchaseReceivingByStatusScopeContractTest {
    private static final String FACTORY = "F1";
    private final DataScopeResolver resolver = mock(DataScopeResolver.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void scopedWorkerReachesByStatusOnlyThroughTheRowScopedHandler() throws Exception {
        scope(DataScope.SELF, List.of(1555L));
        var interceptor = new DataScopeRouteInterceptor(resolver, jdbc);
        HandlerMethod byStatus = new HandlerMethod(controller(mock(PurchaseService.class)), byStatusMethod());
        assertThat(interceptor.preHandle(get("purchase/orders/by-status"), new MockHttpServletResponse(), byStatus)).isTrue();

        // Negative control: the same path served by a handler without row-scope support stays refused.
        HandlerMethod unscoped = new HandlerMethod(new UnscopedProbe(), UnscopedProbe.class.getMethod("run"));
        assertThatThrownBy(() -> interceptor.preHandle(get("purchase/orders/by-status"), new MockHttpServletResponse(), unscoped))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getErrorCode()).isEqualTo(DataScopeRouteInterceptor.DATA_SCOPE_UNSUPPORTED);
                });
        // Negative control: another purchase-order shortcut, even on the row-scoped handler, is still refused.
        assertThatThrownBy(() -> interceptor.preHandle(get("purchase/orders/export"), new MockHttpServletResponse(), byStatus))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void scopedCallerGetsOnlyOwnedOrdersAndTheHint() throws Exception {
        PurchaseService service = mock(PurchaseService.class);
        PurchaseOrder mine = new PurchaseOrder();
        mine.setId("PO-MINE");
        when(service.getPurchaseOrdersByStatusOwnedBy(eq(FACTORY), eq(PurchaseOrderStatus.FINANCE_APPROVED),
                eq(List.of(1555L, 1560L)), eq(1), eq(20))).thenReturn(PageResponse.of(List.of(mine), 1, 20, 1L));
        PurchaseController controller = controller(service);
        scope(DataScope.SELF_AND_BELOW, List.of(1555L, 1560L));
        MockHttpServletRequest request = get("purchase/orders/by-status");
        bind(request);

        ApiResponse<PageResponse<PurchaseOrder>> response = controller.listOrdersByStatus(
                FACTORY, PurchaseOrderStatus.FINANCE_APPROVED, 1, 20, "Bearer t", request);

        assertThat(response.getData().getContent()).extracting(PurchaseOrder::getId).containsExactly("PO-MINE");
        assertThat(response.getActionHint()).isEqualTo(PurchaseController.SCOPED_RECEIVING_HINT);
        verify(service).getPurchaseOrdersByStatusOwnedBy(eq(FACTORY), eq(PurchaseOrderStatus.FINANCE_APPROVED),
                eq(List.of(1555L, 1560L)), eq(1), eq(20));
        verify(service, never()).getPurchaseOrdersByStatus(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void scopedCallerWithNoVisibleOwnersStillGoesThroughTheOwnerQuery() throws Exception {
        PurchaseService service = mock(PurchaseService.class);
        when(service.getPurchaseOrdersByStatusOwnedBy(eq(FACTORY), any(), eq(List.of()), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        PurchaseController controller = controller(service);
        scope(DataScope.SELF, List.of());
        MockHttpServletRequest request = get("purchase/orders/by-status");
        bind(request);

        ApiResponse<PageResponse<PurchaseOrder>> response = controller.listOrdersByStatus(
                FACTORY, PurchaseOrderStatus.CLOSED, 1, 20, "Bearer t", request);

        assertThat(response.getData().getContent()).isEmpty();
        verify(service, never()).getPurchaseOrdersByStatus(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void wholeFactoryCallerKeepsTheOriginalQueryWithoutHint() throws Exception {
        PurchaseService service = mock(PurchaseService.class);
        when(service.getPurchaseOrdersByStatus(FACTORY, PurchaseOrderStatus.COMPLETED, 1, 20))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        PurchaseController controller = controller(service);
        scope(DataScope.ALL, List.of(1555L));
        MockHttpServletRequest request = get("purchase/orders/by-status");
        bind(request);

        ApiResponse<PageResponse<PurchaseOrder>> response = controller.listOrdersByStatus(
                FACTORY, PurchaseOrderStatus.COMPLETED, 1, 20, "Bearer t", request);

        assertThat(response.getActionHint()).isNull();
        verify(service).getPurchaseOrdersByStatus(FACTORY, PurchaseOrderStatus.COMPLETED, 1, 20);
        verify(service, never()).getPurchaseOrdersByStatusOwnedBy(anyString(), any(), anyList(), anyInt(), anyInt());
    }

    static class UnscopedProbe { public void run() {} }

    private PurchaseController controller(PurchaseService service) {
        PurchaseController controller = new PurchaseController(service, null, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "businessRowScope", new BusinessRowScope(resolver, jdbc));
        return controller;
    }

    private static java.lang.reflect.Method byStatusMethod() throws NoSuchMethodException {
        return PurchaseController.class.getMethod("listOrdersByStatus", String.class, PurchaseOrderStatus.class,
                int.class, int.class, String.class, jakarta.servlet.http.HttpServletRequest.class);
    }

    private void scope(DataScope kind, List<Long> chain) {
        DataScopeContext context = DataScopeContext.builder().userId(1555L).factoryId(FACTORY)
                .roleCode("warehouse_worker").scope(kind).build();
        when(resolver.resolve(1555L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(chain);
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/" + FACTORY + "/" + path);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1555L, FACTORY, "warehouse_worker"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", FACTORY));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/mobile/{factoryId}/" + path);
        return request;
    }
}

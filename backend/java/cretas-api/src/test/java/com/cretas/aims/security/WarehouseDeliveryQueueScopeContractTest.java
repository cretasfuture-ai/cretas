package com.cretas.aims.security;

import com.cretas.aims.controller.warehouse.WarehouseDeliveryController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.SalesService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-13 F006 真机: 仓库工人(默认数据范围「仅本人」)打开「发货」, 待确认/历史两条都被路由闸以
 * 「当前数据范围不支持此快捷查询」拦下。修复后: 这两条只读队列按来源单据归属在查询里过滤后放行,
 * 并附说明; 其余没有接入行级范围的快捷查询仍然拦, 且带 errorCode + 去哪里调整的提示。
 */
class WarehouseDeliveryQueueScopeContractTest {
    private final DataScopeResolver resolver = mock(DataScopeResolver.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void selfScopedWarehouseWorkerReachesTheQueueReadsButNotOtherShortcuts() throws Exception {
        scope(DataScope.SELF);
        var controller = new WarehouseDeliveryController(mock(SalesService.class), null);
        var interceptor = new DataScopeRouteInterceptor(resolver, jdbc);
        for (String route : List.of("pending", "history")) {
            String method = route.equals("pending") ? "listPending" : "listHistory";
            HandlerMethod handler = new HandlerMethod(controller,
                    WarehouseDeliveryController.class.getMethod(method, String.class, int.class, int.class));
            assertThat(interceptor.preHandle(get("warehouse/deliveries/" + route), new MockHttpServletResponse(), handler))
                    .as(route).isTrue();
        }

        // Negative control: another unscoped shortcut on the same resource stays refused, with guidance.
        HandlerMethod probe = new HandlerMethod(new UnscopedProbe(), UnscopedProbe.class.getMethod("run"));
        assertThatThrownBy(() -> interceptor.preHandle(get("warehouse/deliveries/export"), new MockHttpServletResponse(), probe))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getErrorCode()).isEqualTo(DataScopeRouteInterceptor.DATA_SCOPE_UNSUPPORTED);
                    assertThat(e.getActionHint()).contains("角色权限 → 数据范围");
                });

        // Same path served by a handler that does not declare row-scope support is still refused.
        HandlerMethod unscopedPending = new HandlerMethod(new UnscopedProbe(), UnscopedProbe.class.getMethod("run"));
        assertThatThrownBy(() -> interceptor.preHandle(get("warehouse/deliveries/pending"), new MockHttpServletResponse(), unscopedPending))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void scopedQueueResponseCarriesTheConfigurationHintAndWholeFactoryDoesNot() {
        SalesService sales = mock(SalesService.class);
        when(sales.getPendingWarehouseDeliveries(eq("F1"), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.<SalesDeliveryRecord>of(), 1, 20, 0L));
        var controller = new WarehouseDeliveryController(sales, null);
        ReflectionTestUtils.setField(controller, "businessRowScope", new BusinessRowScope(resolver, jdbc));

        scope(DataScope.SELF);
        bind(get("warehouse/deliveries/pending"));
        ApiResponse<?> scoped = controller.listPending("F1", 1, 20);
        assertThat(scoped.getActionHint()).isEqualTo(WarehouseDeliveryController.SCOPED_QUEUE_HINT);

        RequestContextHolder.resetRequestAttributes();
        scope(DataScope.ALL);
        bind(get("warehouse/deliveries/pending"));
        assertThat(controller.listPending("F1", 1, 20).getActionHint()).isNull();
    }

    static class UnscopedProbe { public void run() {} }

    private void scope(DataScope kind) {
        DataScopeContext context = DataScopeContext.builder().userId(1555L).factoryId("F1")
                .roleCode("warehouse_worker").scope(kind).build();
        when(resolver.resolve(1555L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(1555L));
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/F1/" + path);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1555L, "F1", "warehouse_worker"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", "F1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/mobile/{factoryId}/" + path);
        return request;
    }
}

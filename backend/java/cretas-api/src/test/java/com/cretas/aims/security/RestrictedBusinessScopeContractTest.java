package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RestrictedBusinessScopeContractTest {
    private static final String FACTORY = "F1";

    @ParameterizedTest
    @EnumSource(value = DataScope.class, names = {"SELF", "SELF_AND_BELOW", "DEPT_AND_BELOW"})
    void unsupportedProductionAndFinanceRoutesFailClosedForEveryNonAllScope(DataScope scope) throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(scope, "production_manager"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(FACTORY), eq("production_manager"))).thenReturn(1L);
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertDenied(interceptor, "GET", "/production-plans", "生产计划", "productionList", Map.of());
        assertDenied(interceptor, "GET", "/production-plans/PLAN-1", "生产计划", "productionDetail", Map.of("planId", "PLAN-1"));
        assertDenied(interceptor, "POST", "/production-plans/batch", "生产计划", "productionBatch", Map.of());
        assertDenied(interceptor, "GET", "/finance/overview", "财务", "financeOverview", Map.of());
        assertDenied(interceptor, "GET", "/finance/transactions", "财务", "financeTransactions", Map.of());
        assertDenied(interceptor, "POST", "/finance/receivable/payment", "财务", "financePayment", Map.of());
        assertDenied(interceptor, "POST", "/finance/adjustments/batch-approve", "财务", "financeBatch", Map.of());
    }

    @Test
    void unconfiguredFactoryRoleKeepsLegacyResourceNullCompatibility() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF, "production_manager"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(FACTORY), eq("production_manager"))).thenReturn(0L);
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertThat(interceptor.preHandle(request("GET", "/finance/overview", "financeOverview", Map.of()),
                new MockHttpServletResponse(), handler("financeOverview"))).isTrue();
        verify(jdbc).queryForObject(anyString(), eq(Long.class), eq(FACTORY), eq("production_manager"));
    }

    @Test
    void missingPersistentRoleFailsClosedBeforeTreatingRouteAsUnconfigured() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/finance/overview", "financeOverview", Map.of()),
                new MockHttpServletResponse(), handler("financeOverview")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        verifyNoInteractions(jdbc);
    }

    @Test
    void persistentScopeLookupFailureFailsClosedWithServiceUnavailable() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF, "finance_manager"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(FACTORY), eq("finance_manager")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/finance/transactions", "financeTransactions", Map.of()),
                new MockHttpServletResponse(), handler("financeTransactions")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(503));
    }

    @Test
    void allScopePreservesUnsupportedRoutesAndMappedPaymentWriteCompatibility() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.ALL));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertThatCode(() -> {
            interceptor.preHandle(request("GET", "/finance/overview", "financeOverview", Map.of()),
                    new MockHttpServletResponse(), handler("financeOverview"));
            interceptor.preHandle(request("POST", "/production-plans/batch", "productionBatch", Map.of()),
                    new MockHttpServletResponse(), handler("productionBatch"));
            interceptor.preHandle(request("POST", "/finance/payments/record", "paymentRecord", Map.of()),
                    new MockHttpServletResponse(), handler("paymentRecord"));
        }).doesNotThrowAnyException();
        verify(resolver, times(3)).resolve(56L);
        verify(resolver, never()).assertCanAccess(anyString(), anyLong());
        verifyNoMoreInteractions(resolver);
        verifyNoInteractions(jdbc);
    }

    @Test
    void mappedRowScopedRoutesStillUseExistingResolverControlFlow() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), eq(FACTORY), eq("PAY-1"))).thenReturn(java.util.List.of(56L));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), eq(FACTORY), eq("SO-1"))).thenReturn(java.util.List.of(56L));
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, jdbc);

        assertThatCode(() -> interceptor.preHandle(
                request("GET", "/finance/payments/PAY-1", "paymentDetail", Map.of("paymentId", "PAY-1")),
                new MockHttpServletResponse(), handler("paymentDetail"))).doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preHandle(
                request("GET", "/production-plans/by-sales-order/SO-1", "productionByOrder", Map.of("salesOrderId", "SO-1")),
                new MockHttpServletResponse(), handler("productionByOrder"))).doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preHandle(
                request("POST", "/finance/payments/record", "paymentRecord", Map.of()),
                new MockHttpServletResponse(), handler("paymentRecord"))).doesNotThrowAnyException();
        verify(resolver, times(2)).assertCanAccess(FACTORY, 56L);
    }

    @Test
    void typedFactoryAndPlatformIdentityChecksRemainBeforeScopeCapabilityCheck() throws Exception {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF));
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(resolver, mock(JdbcTemplate.class));

        assertThatThrownBy(() -> interceptor.preHandle(
                requestWithoutActor("GET", "/finance/overview", "financeOverview", Map.of()),
                new MockHttpServletResponse(), handler("financeOverview")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(401));
        assertThatThrownBy(() -> interceptor.preHandle(
                request("GET", "/finance/overview", "financeOverview", Map.of(),
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 56L, "F2", "finance_manager")),
                new MockHttpServletResponse(), handler("financeOverview")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
        assertThatCode(() -> interceptor.preHandle(
                request("GET", "/finance/overview", "financeOverview", Map.of(),
                        new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 56L, null, "super_admin")),
                new MockHttpServletResponse(), handler("financeOverview"))).doesNotThrowAnyException();
        verifyNoInteractions(resolver);
    }

    @Test
    void unrelatedResourceNullRouteKeepsLegacyNoActorCompatibility() throws Exception {
        DataScopeRouteInterceptor interceptor = new DataScopeRouteInterceptor(mock(DataScopeResolver.class), mock(JdbcTemplate.class));
        assertThatCode(() -> interceptor.preHandle(
                requestWithoutActor("GET", "/health", "unrelated", Map.of()),
                new MockHttpServletResponse(), handler("unrelated"))).doesNotThrowAnyException();
    }

    private static void assertDenied(DataScopeRouteInterceptor interceptor, String method, String path,
                                     String messagePart, String handlerName, Map<String, String> extraVariables)
            throws Exception {
        assertThatThrownBy(() -> interceptor.preHandle(
                request(method, path, handlerName, extraVariables), new MockHttpServletResponse(), handler(handlerName)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(403);
                    assertThat(error).hasMessageContaining(messagePart);
                });
    }

    private static DataScopeContext context(DataScope scope) {
        return context(scope, null);
    }

    private static DataScopeContext context(DataScope scope, String roleCode) {
        return DataScopeContext.builder().userId(56L).factoryId(FACTORY).roleCode(roleCode).scope(scope).build();
    }

    private static MockHttpServletRequest request(String method, String path, String handlerName,
                                                  Map<String, String> extraVariables) throws Exception {
        return request(method, path, handlerName, extraVariables,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 56L, FACTORY, "factory_user"));
    }

    private static MockHttpServletRequest request(String method, String path, String handlerName,
                                                  Map<String, String> extraVariables, AuthenticatedActor actor)
            throws Exception {
        MockHttpServletRequest request = requestWithoutActor(method, path, handlerName, extraVariables);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        return request;
    }

    private static MockHttpServletRequest requestWithoutActor(String method, String path, String handlerName,
                                                               Map<String, String> extraVariables) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/mobile/" + FACTORY + path);
        Map<String, String> variables = new HashMap<>();
        variables.put("factoryId", FACTORY);
        variables.putAll(extraVariables);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/mobile/{factoryId}" + path.replaceAll("/(PLAN-1|PAY-1|SO-1)$", "/{$1}"));
        return request;
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method method = Probe.class.getDeclaredMethod(name);
        return new HandlerMethod(new Probe(), method);
    }

    @RestController
    static class Probe {
        public void productionList() {}
        public void productionDetail() {}
        public void productionBatch() {}
        public void financeOverview() {}
        public void financeTransactions() {}
        public void financePayment() {}
        public void financeBatch() {}
        public void paymentRecord() {}
        public void paymentDetail() {}
        public void productionByOrder() {}
        public void unrelated() {}
    }
}

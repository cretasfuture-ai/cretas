package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessRowScopeContractTest {
    private final DataScopeResolver resolver=mock(DataScopeResolver.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BusinessRowScope scope=new BusinessRowScope(resolver,jdbc);
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest @EnumSource(value=DataScope.class,names={"SELF","SELF_AND_BELOW","DEPT_AND_BELOW"})
    void explicitPoliciesUsePersistedRoleAndResolvedCreatorChain(DataScope kind) {
        MockHttpServletRequest request=request();
        DataScopeContext context=context(kind);
        when(resolver.resolve(56L)).thenReturn(context);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(1L);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(56L,57L));
        bind(request);
        assertThat(scope.creatorIds("F1")).containsExactly(56L,57L);
        BusinessRowScope.requireOwner(scope,"F1",57L);
        assertThatThrownBy(()->BusinessRowScope.requireOwner(scope,"F1",58L))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(()->BusinessRowScope.requireOwner(scope,"F1",null)).isInstanceOf(BusinessException.class);
        verify(jdbc,times(1)).queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"));
        verify(resolver,times(1)).resolve(56L);
    }

    @Test void unconfiguredInheritedScopeKeepsExistingFrontlineContract() {
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(0L);
        bind(request());
        assertThat(scope.creatorIds("F1")).isNull();
        verify(resolver,never()).resolveCreatedByChain(any());
    }

    @Test void mappedRecordsRetainInheritedScopeEvenAfterExplicitPolicyCacheReturnsUnrestricted() {
        DataScopeContext context=context(DataScope.SELF);
        when(resolver.resolve(56L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(56L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(0L);
        bind(request());
        assertThat(scope.creatorIds("F1")).isNull();
        assertThat(BusinessRowScope.recordCreators(scope,"F1")).containsExactly(56L);
        assertThat(BusinessRowScope.recordCreators(scope,"F1")).containsExactly(56L);
        verify(resolver,times(1)).resolveCreatedByChain(context);
        assertThatThrownBy(()->BusinessRowScope.recordCreators(scope,"F2"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
    }

    @Test void allDoesNotQueryFactoryPolicyAndInternalJobsRemainUnrestricted() {
        assertThat(BusinessRowScope.creators(null,"F1")).isNull();
        when(resolver.resolve(56L)).thenReturn(context(DataScope.ALL));
        bind(request());
        assertThat(scope.creatorIds("F1")).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test void emptyResolvedOwnersDoesNotTurnIntoAnUnrestrictedQuery() {
        DataScopeContext context=context(DataScope.CUSTOM);
        when(resolver.resolve(56L)).thenReturn(context);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(1L);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of());
        bind(request());
        assertThat(scope.creatorIds("F1")).isEmpty();
        assertThatThrownBy(()->BusinessRowScope.requireOwner(scope,"F1",56L)).isInstanceOf(BusinessException.class);
    }

    @Test void missingComponentAndLookupFailureFailClosedForHttp() {
        bind(request());
        assertThatThrownBy(()->BusinessRowScope.creators(null,"F1"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
        when(resolver.resolve(56L)).thenReturn(context(DataScope.SELF));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->scope.creatorIds("F1"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
    }

    @Test void cachedPolicyCannotBeReusedForAnotherFactory() {
        when(resolver.resolve(56L)).thenReturn(context(DataScope.ALL));
        bind(request());
        assertThat(scope.creatorIds("F1")).isNull();
        assertThatThrownBy(()->scope.creatorIds("F2"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
    }

    @Test void typedActorIsRequiredAndFactoryMismatchNeverResolvesCollidingIds() {
        MockHttpServletRequest request=request();
        request.removeAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE);bind(request);
        assertThatThrownBy(()->scope.creatorIds("F1")).isInstanceOf(BusinessException.class);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F2","finance_manager"));
        assertThatThrownBy(()->scope.creatorIds("F1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(resolver,jdbc);
    }

    @Test void supportedProductionRecordStillChecksOwnerBeforeControllerExecution() throws Exception {
        DataScopeContext context=context(DataScope.SELF);
        when(resolver.resolve(56L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(56L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(1L);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),eq("F1"),eq("P1")))
                .thenReturn(List.of(99L));
        MockHttpServletRequest request=request();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("factoryId","F1","planId","P1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/mobile/{factoryId}/production-plans/{planId}/start");
        HandlerMethod handler=new HandlerMethod(new ScopedProbe(),ScopedProbe.class.getMethod("run"));
        assertThatThrownBy(()->new DataScopeRouteInterceptor(resolver,jdbc).preHandle(request,new MockHttpServletResponse(),handler))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
    }

    @RowScopeSupported static class ScopedProbe { public void run() {} }
    @Test void onlyTheConnectedProductionSummaryIsOpenedForExplicitScopes() throws Exception {
        DataScopeContext context=context(DataScope.SELF);
        when(resolver.resolve(56L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(56L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("F1"),eq("finance_manager"))).thenReturn(1L);
        var controller=new com.cretas.aims.controller.ListSummaryController(null,null,null);
        HandlerMethod handler=new HandlerMethod(controller,controller.getClass().getMethod("getSummary",
                String.class,String.class,com.cretas.aims.dto.listsummary.ListSummaryRequest.class,
                jakarta.servlet.http.HttpServletRequest.class,jakarta.servlet.http.HttpServletResponse.class));
        MockHttpServletRequest request=request();
        request.setMethod("POST");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/mobile/{factoryId}/list-summary/{entityType}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("factoryId","F1","entityType","productionPlan"));
        assertThat(new DataScopeRouteInterceptor(resolver,jdbc).preHandle(request,new MockHttpServletResponse(),handler)).isTrue();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("factoryId","F1","entityType","attendance"));
        assertThatThrownBy(()->new DataScopeRouteInterceptor(resolver,jdbc).preHandle(request,new MockHttpServletResponse(),handler))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
    }
    private static DataScopeContext context(DataScope kind) { return DataScopeContext.builder().userId(56L).factoryId("F1").roleCode("finance_manager").scope(kind).build(); }
    private static void bind(MockHttpServletRequest request) { RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); }
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request=new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","untrusted_jwt_role"));
        return request;
    }
}

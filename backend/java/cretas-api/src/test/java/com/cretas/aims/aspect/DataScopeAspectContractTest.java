package com.cretas.aims.aspect;

import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataScopeAspectContractTest {

    private final DataScopeResolver resolver = mock(DataScopeResolver.class);
    private final DataScopeAspect aspect = new DataScopeAspect(resolver);

    @AfterEach
    void cleanThreadLocals() {
        RequestContextHolder.resetRequestAttributes();
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    @Test
    void resolvesUserIdFromJwtRequestAttributeAndPushesScopeForTheServiceCall() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "1552");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        DataScopeContext resolved = DataScopeContext.builder()
                .userId(1552L)
                .factoryId("F006")
                .roleCode("sales_manager")
                .scope(com.cretas.aims.entity.enums.DataScope.SELF)
                .department("销售部")
                .column("created_by")
                .build();
        when(resolver.resolve(1552L)).thenReturn(resolved);

        DataScope annotation = annotation("owner_id", "created_by");
        ProceedingJoinPoint joinPoint = joinPoint();
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            DataScopeContext active = DataScopeContext.current();
            assertThat(active).isNotNull();
            assertThat(active.getUserId()).isEqualTo(1552L);
            assertThat(active.getFactoryId()).isEqualTo("F006");
            assertThat(active.getScope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            assertThat(active.getColumn()).isEqualTo("owner_id");
            return "scoped-result";
        });

        assertThat(aspect.aroundDataScope(joinPoint, annotation)).isEqualTo("scoped-result");
        verify(resolver).resolve(1552L);
        assertThat(DataScopeContext.current()).isNull();
    }

    @Test
    void anonymousRequestStillProceedsWithoutInventingADataScope() throws Throwable {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(resolver.resolve(null)).thenReturn(null);

        ProceedingJoinPoint joinPoint = joinPoint();
        when(joinPoint.proceed()).thenReturn("anonymous-result");

        assertThat(aspect.aroundDataScope(joinPoint, annotation("", "")))
                .isEqualTo("anonymous-result");
        verify(resolver).resolve(null);
        assertThat(DataScopeContext.current()).isNull();
    }

    @Test
    void springProxyBindsTheMethodAnnotationBeforeReadingItsColumn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "1552");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        DataScopeContext resolved = DataScopeContext.builder()
                .userId(1552L)
                .factoryId("F006")
                .roleCode("sales_manager")
                .scope(com.cretas.aims.entity.enums.DataScope.SELF)
                .department("销售部")
                .column("created_by")
                .build();
        when(resolver.resolve(1552L)).thenReturn(resolved);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new AnnotatedService());
        proxyFactory.addAspect(aspect);
        AnnotatedService proxy = proxyFactory.getProxy();

        assertThat(proxy.list()).isEqualTo("owner_id");
        verify(resolver).resolve(1552L);
        assertThat(DataScopeContext.current()).isNull();
    }

    // ── platform_admins.id and users.id are independent sequences: id 1552 below is both a
    //    platform admin and an unrelated factory sales_manager whose scope is SELF.

    private static void as(AuthenticatedActorType type, String factoryId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "1552");
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, 1552L, factoryId, role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void collidingSalesManagerScope() {
        lenient().when(resolver.resolve(1552L)).thenReturn(DataScopeContext.builder()
                .userId(1552L).factoryId("F999").roleCode("sales_manager")
                .scope(com.cretas.aims.entity.enums.DataScope.SELF).department("销售部")
                .column("created_by").build());
    }

    @Test
    void bypassPlatformRoleGetsNoRowScopeAndIsNeverResolvedThroughUsers() throws Throwable {
        collidingSalesManagerScope();
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            ProceedingJoinPoint joinPoint = joinPoint();
            when(joinPoint.proceed()).thenAnswer(invocation -> {
                assertThat(DataScopeContext.current()).as(role).isNull();
                return "unscoped";
            });
            assertThat(aspect.aroundDataScope(joinPoint, annotation("", ""))).isEqualTo("unscoped");
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void nonBypassPlatformRoleIsRefusedBeforeAnyScopeOrServiceCall() throws Throwable {
        collidingSalesManagerScope();
        for (String role : java.util.List.of("auditor", "operation_admin", "system_admin")) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            ProceedingJoinPoint joinPoint = joinPoint();
            assertThatThrownBy(() -> aspect.aroundDataScope(joinPoint, annotation("", "")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(role);
            verify(joinPoint, never()).proceed();
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void negativeControlFactoryUserWithSameIdKeepsTheirOwnScope() throws Throwable {
        collidingSalesManagerScope();
        as(AuthenticatedActorType.FACTORY_USER, "F999", "sales_manager");
        ProceedingJoinPoint joinPoint = joinPoint();
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertThat(DataScopeContext.current().getScope())
                    .isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            return "scoped";
        });
        assertThat(aspect.aroundDataScope(joinPoint, annotation("", ""))).isEqualTo("scoped");
        verify(resolver).resolve(1552L);
    }

    static class AnnotatedService {
        @DataScope(column = "owner_id")
        public String list() {
            return DataScopeContext.current().getColumn();
        }
    }

    private static DataScope annotation(String column, String value) {
        DataScope annotation = mock(DataScope.class);
        when(annotation.column()).thenReturn(column);
        when(annotation.value()).thenReturn(value);
        return annotation;
    }

    private static ProceedingJoinPoint joinPoint() {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringTypeName()).thenReturn("ExampleService");
        when(signature.getName()).thenReturn("list");
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }
}

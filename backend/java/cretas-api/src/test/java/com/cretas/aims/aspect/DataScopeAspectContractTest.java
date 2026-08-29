package com.cretas.aims.aspect;

import com.cretas.aims.annotation.DataScope;
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

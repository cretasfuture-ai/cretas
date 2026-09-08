package com.cretas.aims.aspect;

import com.cretas.aims.annotation.Loggable;
import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.service.datacenter.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LoggableAspectContractTest {

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void falseApiResponseAndExceptionSecretsAreAuditedCorrectly() throws Throwable {
        OperationLogService service = mock(OperationLogService.class);
        LoggableAspect aspect = new LoggableAspect(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/materials/42");
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));

        ProceedingJoinPoint point = point("save", ApiResponse.error("password=hunter2"));
        aspect.around(point, annotation("save"));
        OperationLog first = capture(service);
        assertThat(first.getSuccess()).isFalse();
        assertThat(first.getErrorMessage()).doesNotContain("hunter2");
        assertThat(request.getAttribute(RequestAuditSupport.EXPLICIT_AUDIT_ATTRIBUTE)).isEqualTo(Boolean.TRUE);

        reset(service);
        ProceedingJoinPoint throwing = point("save", new IllegalStateException("token=abc123"));
        when(throwing.proceed()).thenThrow(new IllegalStateException("token=abc123"));
        try { aspect.around(throwing, annotation("save")); } catch (IllegalStateException ignored) { }
        OperationLog second = capture(service);
        assertThat(second.getSuccess()).isFalse();
        assertThat(second.getErrorMessage()).doesNotContain("abc123");
    }

    private static Loggable annotation(String methodName) throws Exception {
        return Sample.class.getDeclaredMethod(methodName, String.class).getAnnotation(Loggable.class);
    }

    private static ProceedingJoinPoint point(String methodName, Object result) throws Exception {
        Method method = Sample.class.getDeclaredMethod(methodName, String.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"id"});
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(new Object[]{"42"});
        doReturn(result).when(point).proceed();
        return point;
    }

    private static OperationLog capture(OperationLogService service) {
        var captor = org.mockito.ArgumentCaptor.forClass(OperationLog.class);
        verify(service).record(captor.capture());
        return captor.getValue();
    }

    static class Sample {
        @Loggable(module = "MATERIAL", action = "UPDATE", entityType = "Material", entityIdParam = "id")
        public Object save(String id) { return null; }
    }
}

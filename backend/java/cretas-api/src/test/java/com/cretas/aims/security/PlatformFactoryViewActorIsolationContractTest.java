package com.cretas.aims.security;

import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlatformFactoryViewActorIsolationContractTest {
    @AfterEach
    void clean() {
        RequestContextHolder.resetRequestAttributes();
        PriceSensitiveContext.clear();
    }

    private MockHttpServletRequest viewRequest() {
        return viewRequest("F001");
    }

    private MockHttpServletRequest viewRequest(String factory) {
        var request = new MockHttpServletRequest("GET", "/api/mobile/" + factory + "/reports/dashboard/overview");
        request.setAttribute("userId", 7L);
        request.setAttribute("username", "platform-admin");
        request.setAttribute("role", "platform_admin");
        request.setAttribute("factoryId", factory);
        request.setAttribute(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, true);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 7L, "PLATFORM", "platform_admin"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", factory));
        return request;
    }

    @Test
    void collidingFactoryUserIsNeverResolvedForViewResponseOrOperationalCost() {
        var users = mock(UserRepository.class);
        var permissions = mock(PermissionService.class);
        var mobile = mock(MobileService.class);
        User collidingFinanceUser = new User();
        collidingFinanceUser.setId(7L);
        collidingFinanceUser.setFactoryId("OTHER_FACTORY");
        when(users.findById(7L)).thenReturn(Optional.of(collidingFinanceUser));

        var request = viewRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        var item = new PurchaseOrderItem();
        item.setUnitPrice(new BigDecimal("800.00"));
        advice.beforeBodyWrite(item, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(request), null);
        assertThat(item.getUnitPrice()).isNull();
        var resolver = new PriceMaskResolver(mobile, users, permissions);
        assertThat(resolver.shouldMaskOperationalCost("Bearer signed-view-token")).isTrue();
        assertThat(resolver.shouldMaskPrice("Bearer signed-view-token")).isTrue();
        assertThat(resolver.shouldMaskPrice("Bearer signed-view-token", PriceSensitive.PURCHASE)).isTrue();
        verifyNoInteractions(users, permissions, mobile);
    }

    @Test
    void actualFactoryUserStillUsesTheirOwnPricePermission() {
        var users = mock(UserRepository.class);
        var permissions = mock(PermissionService.class);
        User factoryUser = new User();
        factoryUser.setId(7L);
        factoryUser.setFactoryId("F001");
        when(users.findById(7L)).thenReturn(Optional.of(factoryUser));
        when(permissions.hasPermission(eq(factoryUser), anyString())).thenReturn(true);
        var request = viewRequest();
        request.removeAttribute(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F001", "finance_manager"));
        var advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        var item = new PurchaseOrderItem();
        item.setUnitPrice(new BigDecimal("800.00"));
        advice.beforeBodyWrite(item, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(request), null);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("800.00");
        verify(users).findById(7L);
    }

    @Test
    void viewTenantAttributeDoesNotSuppressRealPlatformAccessAudit() throws Exception {
        var writer = mock(PlatformDataAccessLogWriter.class);
        var interceptor = new PlatformDataAccessLogInterceptor(writer);
        var request = viewRequest();
        var response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, null);
        interceptor.afterCompletion(request, response, null, null);
        var row = ArgumentCaptor.forClass(PlatformDataAccessLog.class);
        verify(writer).submit(row.capture());
        assertThat(row.getValue().getActorFactoryId()).isEqualTo("PLATFORM");
        assertThat(row.getValue().getTargetFactoryId()).isEqualTo("F001");
        assertThat(row.getValue().getActorUserId()).isEqualTo(7L);
    }

    @Test
    void validExtendedFactoryIdentifiersStillProduceAnAuditRecord() throws Exception {
        String factory = "A.B:" + "x".repeat(60);
        var writer = mock(PlatformDataAccessLogWriter.class);
        var interceptor = new PlatformDataAccessLogInterceptor(writer);
        var request = viewRequest(factory);
        var response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, null);
        interceptor.afterCompletion(request, response, null, null);
        var row = ArgumentCaptor.forClass(PlatformDataAccessLog.class);
        verify(writer).submit(row.capture());
        assertThat(row.getValue().getTargetFactoryId()).isEqualTo(factory);
    }
}

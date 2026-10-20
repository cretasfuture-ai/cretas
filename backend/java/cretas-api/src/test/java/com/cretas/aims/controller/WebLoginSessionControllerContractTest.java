package com.cretas.aims.controller;

import com.cretas.aims.config.WebLoginSessionFilter;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.utils.CookieAuthHelper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebLoginSessionControllerContractTest {
    final MobileService mobile = mock(MobileService.class);
    final WebLoginSessionService sessions = mock(WebLoginSessionService.class);
    final CookieAuthHelper cookies = new CookieAuthHelper();
    final MobileController controller = new MobileController(mobile, mock(EquipmentAlertRepository.class), cookies, sessions);
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void normalWebLoginIssuesPersistentSessionAndRotatesPreviousBrowserOnly() {
        var request = new MockHttpServletRequest(); request.addHeader("X-Client-Type", "web");
        request.setCookies(new Cookie(CookieAuthHelper.WEB_SESSION_COOKIE, "previous"));
        var response = new MockHttpServletResponse();
        var input = new MobileDTO.LoginRequest();
        when(mobile.unifiedLogin(input)).thenReturn(MobileDTO.LoginResponse.builder().token("access").refreshToken("refresh").build());
        when(sessions.issue("access")).thenReturn("new-session");
        controller.unifiedLogin(input, request, response);
        var order = inOrder(sessions); order.verify(sessions).issue("access"); order.verify(sessions).revoke("previous");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("cretas_web_session=new-session", "HttpOnly", "; Secure"));
    }

    @Test void mobileLoginDoesNotGainPersistentSession() {
        var input = new MobileDTO.LoginRequest();
        when(mobile.unifiedLogin(input)).thenReturn(MobileDTO.LoginResponse.builder().token("mobile-access").build());
        var response = new MockHttpServletResponse();
        controller.unifiedLogin(input, new MockHttpServletRequest(), response);
        verifyNoInteractions(sessions); assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void demoLoginDoesNotGainPersistentSession() {
        when(mobile.demoLogin("factory")).thenReturn(MobileDTO.LoginResponse.builder().token("demo-access").refreshToken("demo-refresh").build());
        var request = new MockHttpServletRequest(); request.addHeader("X-Client-Type", "web");
        var response = new MockHttpServletResponse(); controller.demoLogin("factory", request, response);
        verifyNoInteractions(sessions);
        assertThat(response.getHeaders("Set-Cookie")).noneSatisfy(cookie -> assertThat(cookie).contains("cretas_web_session="));
    }

    @Test void logoutRevokesPersistentCredentialAndOriginalLegacySession() {
        var request = new MockHttpServletRequest(); request.setAttribute("userId", 7L);
        request.addHeader("Authorization", "Bearer transient-access");
        request.setAttribute(WebLoginSessionFilter.ORIGINAL_ACCESS_TOKEN, "original-access");
        request.setCookies(new Cookie(CookieAuthHelper.WEB_SESSION_COOKIE, "owned-session"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var response = new MockHttpServletResponse(); controller.logout(null, request, response);
        verify(mobile).logout(7L, null, "original-access"); verify(sessions).revoke("owned-session");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("cretas_web_session=", "Max-Age=0"));
    }
}

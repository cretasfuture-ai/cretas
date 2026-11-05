package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.impl.canvas.SplitOrderTool;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasAndSplitRetiredRestaurantRoleContractTest {

    @ParameterizedTest
    @EnumSource(value = FactoryUserRole.class, names = "restaurant_.*", mode = EnumSource.Mode.MATCH_ALL)
    void realRequireRoleInterceptorRejectsRetiredRestaurantRoles(FactoryUserRole role) throws Exception {
        Method list = CanvasThresholdsController.class.getDeclaredMethod("list", String.class, String.class);
        HandlerMethod handler = new HandlerMethod(new CanvasThresholdsController(null, null), list);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/F006/canvas-thresholds");
        request.setAttribute("role", role.name());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = new RequireRoleInterceptor(new ObjectMapper()).preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @EnumSource(value = FactoryUserRole.class, names = "restaurant_.*", mode = EnumSource.Mode.MATCH_ALL)
    void splitOrderToolRejectsRetiredRestaurantRoles(FactoryUserRole role) {
        assertThat(new SplitOrderTool().hasPermission(role.name())).isFalse();
    }
}

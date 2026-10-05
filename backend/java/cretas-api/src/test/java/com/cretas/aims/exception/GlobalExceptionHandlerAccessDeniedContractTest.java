package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerAccessDeniedContractTest {
    private final MockMvc mvc = standaloneSetup(new ProtectedController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void deniedServiceReturnsForbiddenAndHidesInternalPermissionDetails() throws Exception {
        String body = mvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("您没有执行此操作的权限"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("F007", "PRIVATE_POLICY", "internal", "AccessDeniedException");
    }

    @Test
    void allowedControllerKeepsSuccessfulResponse() throws Exception {
        mvc.perform(get("/test/access-allowed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("ALLOWED"));
    }

    @Test
    void unavailablePermissionConfigurationKeeps503() throws Exception {
        mvc.perform(get("/test/permission-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("权限配置暂时无法读取，请稍后重试"));
    }

    @Test
    void unrelatedRuntimeFailureKeepsSanitized500() throws Exception {
        String body = mvc.perform(get("/test/ordinary-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(500))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("PRIVATE_RUNTIME_DETAIL");
    }

    @RestController
    private static class ProtectedController {
        @GetMapping("/test/access-denied")
        ApiResponse<String> denied() {
            throw new AccessDeniedException("F007 PRIVATE_POLICY /internal/permission");
        }

        @GetMapping("/test/access-allowed")
        ApiResponse<String> allowed() {
            return ApiResponse.success("ALLOWED");
        }

        @GetMapping("/test/permission-unavailable")
        ApiResponse<String> unavailable() {
            throw new BusinessException(503, "权限配置暂时无法读取，请稍后重试");
        }

        @GetMapping("/test/ordinary-failure")
        ApiResponse<String> failure() {
            throw new RuntimeException("PRIVATE_RUNTIME_DETAIL");
        }
    }
}

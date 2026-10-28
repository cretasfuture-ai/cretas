package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.service.config.SchedulerRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Run results carry what a scheduled tool returned under its run-as user, so every read and the ruling
 * go through the real @RequireRole interceptor: an ordinary role is refused before the service is touched.
 */
@DisplayName("定时运行记录接口门禁（真实映射 + RequireRoleInterceptor）")
class SchedulerRunControllerAccessTest {

    private final SchedulerRunService service = mock(SchedulerRunService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchedulerRunController(service))
            .addInterceptors(new RequireRoleInterceptor(new ObjectMapper()))
            .build();

    private static final String BASE = "/api/mobile/F006/config/v2/scheduler";

    @Test
    @DisplayName("操作员读历史 / 最近一次 / 详情、裁定：全部 403，且没有碰 service")
    void ordinaryRoleIsRefusedOnEveryEndpoint() throws Exception {
        for (String url : new String[] {BASE + "/daily/runs", BASE + "/daily/runs/latest", BASE + "/runs/r-1"}) {
            mvc.perform(get(url).requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                    .requestAttr("role", "operator")).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/runs/r-1/resolve").requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                .requestAttr("role", "operator").contentType("application/json")
                .content("{\"resolution\":\"SUCCESS\",\"note\":\"n\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阳性对照：厂级超管 / 权限管理员可读")
    void adminRolesCanRead() throws Exception {
        when(service.latest("F006", "daily")).thenReturn(Optional.empty());
        for (String role : new String[] {"factory_super_admin", "permission_admin"}) {
            mvc.perform(get(BASE + "/daily/runs/latest").requestAttr("factoryId", "F006")
                    .requestAttr("userId", 42L).requestAttr("role", role)).andExpect(status().isOk());
        }
        verify(service, times(2)).latest("F006", "daily");
        verify(service, never()).history(any(), any(), anyInt(), anyInt());
    }
}

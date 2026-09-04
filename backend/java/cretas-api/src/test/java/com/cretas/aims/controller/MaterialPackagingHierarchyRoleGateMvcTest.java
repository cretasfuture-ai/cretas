package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.service.MaterialPackagingHierarchyService;
import com.cretas.aims.service.MobileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷 46 follow-up (2026-09-04) —— {@link MaterialPackagingHierarchyController} 那两个
 * 写端点跑真实请求穿过真实 {@link RequireRoleInterceptor}。见
 * {@link RawMaterialTypeRoleGateMvcTest} 的完整背景，本文件是同一形态在
 * material-packaging 这条平行路径上的钉子 —— 漏了这里会让「物料主记录保存成功、包装
 * 规格却 403」这种更隐蔽的形式复发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialPackagingHierarchyController upsert/delete — 真实 @RequireRole 拦截器放行/拒绝")
class MaterialPackagingHierarchyRoleGateMvcTest {

    @Mock private MaterialPackagingHierarchyService service;
    @Mock private MobileService mobileService;

    private MockMvc mockMvc;

    private static final String URL = "/api/mobile/F006/material-packaging/by-material/RMT-F006-001";
    private static final String VALID_BODY = "{\"level1Unit\":\"kg\"}";

    @BeforeEach
    void setUp() {
        MaterialPackagingHierarchyController controller =
                new MaterialPackagingHierarchyController(service, mobileService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RequireRoleInterceptor(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("🔴 warehouse_worker PUT upsert → 403")
    void warehouseWorker_upsert_forbidden() throws Exception {
        mockMvc.perform(put(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "warehouse_worker")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verify(service, never()).upsert(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("warehouse_manager PUT upsert → 通过角色闸, 到达 service")
    void warehouseManager_upsert_passes() throws Exception {
        when(service.upsert(anyString(), anyString(), any(), any())).thenReturn(
                MaterialPackagingHierarchyDTO.builder().materialTypeId("RMT-F006-001").level1Unit("kg").build());

        mockMvc.perform(put(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "warehouse_manager")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).upsert(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("🔴 warehouse_worker DELETE → 403")
    void warehouseWorker_delete_forbidden() throws Exception {
        mockMvc.perform(delete(URL).requestAttr("role", "warehouse_worker"))
                .andExpect(status().isForbidden());

        verify(service, never()).deleteByMaterialTypeId(anyString(), anyString());
    }

    @Test
    @DisplayName("factory_super_admin DELETE → 通过角色闸")
    void factorySuperAdmin_delete_passes() throws Exception {
        mockMvc.perform(delete(URL).requestAttr("role", "factory_super_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).deleteByMaterialTypeId("F006", "RMT-F006-001");
    }
}

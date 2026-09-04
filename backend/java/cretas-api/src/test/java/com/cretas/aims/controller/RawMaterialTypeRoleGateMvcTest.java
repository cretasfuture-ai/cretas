package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷 46 follow-up (2026-09-04) —— 跑真实请求穿过真实 {@link RequireRoleInterceptor}，
 * 证明「拦截器真的按注解放行/拒绝」（{@link RawMaterialTypeManagerOnlyRoleGateTest} 只
 * 证明「注解写对了」，两者不是同一件事，见 {@link CanvasThresholdsControllerPermissionTest}
 * 头部注释那条纪律）。
 *
 * <p>只挂 {@code RequireRoleInterceptor}，不挂 {@code PermissionInterceptor} —— 模块闸
 * 已由 {@link RawMaterialTypeWarehouseWriteGateTest} 独立钉住，这里只隔离测角色闸这一层。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RawMaterialTypeController create/update/delete — 真实 @RequireRole 拦截器放行/拒绝")
class RawMaterialTypeRoleGateMvcTest {

    @Mock private RawMaterialTypeService materialTypeService;
    @Mock private MobileService mobileService;
    @Mock private SupplierService supplierService;

    private MockMvc mockMvc;

    private static final String CREATE_URL = "/api/mobile/F006/raw-material-types";
    private static final String ITEM_URL = "/api/mobile/F006/raw-material-types/RMT-F006-001";
    private static final String VALID_BODY =
            "{\"name\":\"猪舌\",\"unit\":\"kg\",\"category\":\"肉类\",\"storageType\":\"frozen\"}";

    @BeforeEach
    void setUp() {
        RawMaterialTypeController controller =
                new RawMaterialTypeController(materialTypeService, mobileService, supplierService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RequireRoleInterceptor(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("🔴 warehouse_worker(仓管) POST create → 403 (缺陷本体: 通过模块闸但不该通过角色闸)")
    void warehouseWorker_create_forbidden() throws Exception {
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "warehouse_worker")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }

    @Test
    @DisplayName("warehouse_manager(仓储主管) POST create → 通过角色闸, 到达 service")
    void warehouseManager_create_passes() throws Exception {
        when(materialTypeService.createMaterialType(anyString(), any())).thenReturn(
                RawMaterialTypeDTO.builder().id("RMT-F006-001").code("YL001").name("猪舌").unit("kg").build());

        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "warehouse_manager")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(materialTypeService).createMaterialType(anyString(), any());
    }

    @Test
    @DisplayName("🔴 production_manager POST create → 403 (owner: 只让仓储主管改, 生产侧不再例外)")
    void productionManager_create_forbidden() throws Exception {
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "production_manager")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }

    @Test
    @DisplayName("factory_super_admin PUT update → 通过角色闸")
    void factorySuperAdmin_update_passes() throws Exception {
        when(materialTypeService.updateMaterialType(anyString(), anyString(), any())).thenReturn(
                RawMaterialTypeDTO.builder().id("RMT-F006-001").name("猪舌").unit("kg").build());

        mockMvc.perform(put(ITEM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("role", "factory_super_admin")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("平台管理员 (super_admin) 无条件放行 DELETE — PLATFORM_ADMIN_ROLES 不受角色白名单约束")
    void platformSuperAdminBypass_delete_passes() throws Exception {
        mockMvc.perform(delete(ITEM_URL).requestAttr("role", "super_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(materialTypeService).deleteMaterialType("F006", "RMT-F006-001");
    }

    @Test
    @DisplayName("🔴 warehouse_worker DELETE → 403")
    void warehouseWorker_delete_forbidden() throws Exception {
        mockMvc.perform(delete(ITEM_URL).requestAttr("role", "warehouse_worker"))
                .andExpect(status().isForbidden());

        verify(materialTypeService, never()).deleteMaterialType(anyString(), anyString());
    }

    @Test
    @DisplayName("阳性对照: 未登录 (无 role 属性) → 403 未登录, 不是静默放行")
    void missingRole_isForbiddenNotSilentlyAllowed() throws Exception {
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }
}

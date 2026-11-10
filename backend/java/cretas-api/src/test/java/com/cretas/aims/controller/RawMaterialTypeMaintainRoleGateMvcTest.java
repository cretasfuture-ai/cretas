package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工厂纪律 B7: 跑真实请求穿过真实的 {@link PermissionInterceptor} + 真实的 {@link PermissionServiceImpl},
 * 证明「原料类型维护」权限真的按岗位放行 / 拒绝 (注解写对了 ≠ 拦截器真的按它拦, 两件事)。
 * 只有阈值中心读取器是桩, 用来模拟「工厂改了岗位名单」。
 */
@DisplayName("RawMaterialTypeController 维护端点 — 真实 PermissionInterceptor 放行/拒绝")
class RawMaterialTypeMaintainRoleGateMvcTest {

    private static final String CREATE_URL = "/api/mobile/F006/raw-material-types";
    private static final String ITEM_URL = "/api/mobile/F006/raw-material-types/RMT-F006-001";
    private static final String VALID_BODY =
            "{\"name\":\"猪舌\",\"unit\":\"kg\",\"category\":\"肉类\",\"storageType\":\"frozen\"}";

    private RawMaterialTypeService materialTypeService;
    private ThresholdResolverService thresholds;
    private UserRepository users;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        materialTypeService = mock(RawMaterialTypeService.class);
        thresholds = mock(ThresholdResolverService.class);
        users = mock(UserRepository.class);
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "thresholdResolver", thresholds);
        RawMaterialTypeController controller = new RawMaterialTypeController(
                materialTypeService, mock(MobileService.class), mock(SupplierService.class), null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .build();
        when(materialTypeService.createMaterialType(anyString(), any())).thenReturn(
                RawMaterialTypeDTO.builder().id("RMT-F006-001").code("YL001").name("猪舌").unit("kg").build());
    }

    private void loginAs(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId("F006");
        u.setRoleCode(role);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("🔴 warehouse_worker(仓管) POST create → 403, 且没到 service")
    void warehouseWorker_forbidden() throws Exception {
        loginAs(1, "warehouse_worker");
        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", 1L).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }

    @Test
    @DisplayName("warehouse_manager(仓储主管) POST create → 通过, 到达 service (阳性对照)")
    void warehouseManager_passes() throws Exception {
        loginAs(2, "warehouse_manager");
        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", 2L).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(materialTypeService).createMaterialType(eq("F006"), any());
    }

    @Test
    @DisplayName("factory_super_admin(厂长) PUT / DELETE 都通过")
    void factoryHead_updateAndDelete_pass() throws Exception {
        loginAs(3, "factory_super_admin");
        when(materialTypeService.updateMaterialType(anyString(), anyString(), any())).thenReturn(
                RawMaterialTypeDTO.builder().id("RMT-F006-001").name("猪舌").unit("kg").build());
        mockMvc.perform(put(ITEM_URL).contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", 3L).content(VALID_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(delete(ITEM_URL).requestAttr("userId", 3L)).andExpect(status().isOk());
        verify(materialTypeService).deleteMaterialType("F006", "RMT-F006-001");
    }

    @Test
    @DisplayName("工厂把名单改成 production_manager ⇒ 他能了, 仓储主管反而不行 (权限真的是可配置的)")
    void factoryConfiguration_flipsTheOutcome() throws Exception {
        when(thresholds.getString("F006", ThresholdKeys.INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN, null))
                .thenReturn("production_manager");
        loginAs(4, "production_manager");
        loginAs(2, "warehouse_manager");

        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", 4L).content(VALID_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", 2L).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未登录 (无 userId) → 401/403, 不是静默放行")
    void notLoggedIn_isRejected() throws Exception {
        mockMvc.perform(post(CREATE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().is4xxClientError());
        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }
}

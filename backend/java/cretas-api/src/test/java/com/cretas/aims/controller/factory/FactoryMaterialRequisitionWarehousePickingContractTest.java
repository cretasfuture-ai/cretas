package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-S315-03: 仓管做领料单的备料 / 确认领料 / 调拨三个动作, 不再被 production_plan 模块挡成 403。
 *
 * <p>真实入口: 真的 {@link ModuleEnabledInterceptor} + 真的 controller 走 MockMvc。
 * 放开的只有这三个动作, 其它接口对仓管仍是 403, 工厂开关仍只看 production_plan。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactoryMaterialRequisitionWarehousePickingContractTest {

    private static final String BASE = "/api/mobile/F006/material-requisitions";
    private static final long WAREHOUSE_USER = 1554L;

    @Mock private FactoryConfigService factoryConfigService;
    @Mock private UserModuleAccessService moduleAccess;
    @Mock private UserRepository userRepository;
    @Mock private FactoryMaterialRequisitionService service;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        ModuleEnabledInterceptor interceptor = new ModuleEnabledInterceptor(
                factoryConfigService, moduleAccess, userRepository, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new FactoryMaterialRequisitionController(service))
                .addInterceptors(interceptor)
                .build();

        user = new User();
        user.setId(WAREHOUSE_USER);
        user.setFactoryId("F006");
        user.setUsername("f006_warehouse_mgr");
        user.setRoleCode("warehouse_manager");
        when(userRepository.findById(WAREHOUSE_USER)).thenReturn(Optional.of(user));
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled("F006", "warehouse")).thenReturn(true);
        when(moduleAccess.canAccessModule(user, "production_plan")).thenReturn(false);
        when(moduleAccess.canAccessModule(user, "warehouse")).thenReturn(true);

        FactoryMaterialRequisition requisition = new FactoryMaterialRequisition();
        when(service.startPicking(any(), any(), any())).thenReturn(requisition);
        when(service.confirmPicking(any(), any(), any(), anyList())).thenReturn(requisition);
        when(service.transferToFactory(any(), any(), any())).thenReturn(requisition);
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr("userId", WAREHOUSE_USER);
    }

    private MockHttpServletRequestBuilder confirmPicking() {
        return asUser(put(BASE + "/MR-1/confirm-picking")
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"));
    }

    @Test
    @DisplayName("阳性: 没有 production_plan 的仓管能备料 / 确认领料 / 调拨, 请求真的到了服务层")
    void warehouseUserReachesTheThreePickingActions() throws Exception {
        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isOk());
        mockMvc.perform(confirmPicking()).andExpect(status().isOk());
        mockMvc.perform(asUser(put(BASE + "/MR-1/transfer"))).andExpect(status().isOk());

        verify(service).startPicking(eq("F006"), eq("MR-1"), eq(WAREHOUSE_USER));
        verify(service).confirmPicking(eq("F006"), eq("MR-1"), eq(WAREHOUSE_USER), anyList());
        verify(service).transferToFactory(eq("F006"), eq("MR-1"), eq(WAREHOUSE_USER));
    }

    @Test
    @DisplayName("阴性: 其余接口不放 —— 仓管生成 / 签收 / 关单 / 取消 / 按计划查仍被 production_plan 挡成 403")
    void warehouseUserStaysBlockedEverywhereElse() throws Exception {
        List<MockHttpServletRequestBuilder> others = List.of(
                post(BASE + "/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productionPlanId\":\"P-1\"}"),
                put(BASE + "/MR-1/receive"),
                put(BASE + "/MR-1/close").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put(BASE + "/MR-1/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                get(BASE + "/by-plan/P-1"));
        for (MockHttpServletRequestBuilder other : others) {
            mockMvc.perform(asUser(other)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阳性: 没有 production_plan 的仓管能看领料单列表和详情")
    void warehouseUserCanReadListAndDetail() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
        // 详情接口改走 getDetailForPicking (顺带给辅料配比, 2026-11-10); 配比本身见 FactoryMaterialRequisitionPickingDosageContractTest
        when(service.getDetailForPicking(eq("F006"), eq("MR-1"), any())).thenReturn(new FactoryMaterialRequisition());

        mockMvc.perform(asUser(get(BASE))).andExpect(status().isOk());
        mockMvc.perform(asUser(get(BASE + "/MR-1"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("阴性: 显式 DENY production_plan 的账号, 列表和详情也看不了")
    void explicitDenyBlocksReadsToo() throws Exception {
        when(moduleAccess.isExplicitlyDenied(user, "production_plan")).thenReturn(true);

        mockMvc.perform(asUser(get(BASE))).andExpect(status().isForbidden());
        mockMvc.perform(asUser(get(BASE + "/MR-1"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阴性: 既没有 production_plan 也没有 warehouse 的账号, 三个动作仍是 403")
    void userWithNeitherModuleStaysBlocked() throws Exception {
        when(moduleAccess.canAccessModule(user, "warehouse")).thenReturn(false);

        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isForbidden());
        mockMvc.perform(confirmPicking()).andExpect(status().isForbidden());
        mockMvc.perform(asUser(put(BASE + "/MR-1/transfer"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阴性: 账号被显式 DENY production_plan 时, warehouse 权限不能绕过去")
    void explicitDenyOnPrimaryModuleWins() throws Exception {
        when(moduleAccess.isExplicitlyDenied(user, "production_plan")).thenReturn(true);

        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isForbidden());
        mockMvc.perform(confirmPicking()).andExpect(status().isForbidden());
        mockMvc.perform(asUser(put(BASE + "/MR-1/transfer"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阴性: 本厂仓储模块没启用时, warehouse 权限不算数")
    void disabledWarehouseModuleDoesNotCount() throws Exception {
        when(factoryConfigService.isModuleEnabled("F006", "warehouse")).thenReturn(false);

        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("阴性: 工厂开关仍只看 production_plan —— 关了它, 仓管也不通")
    void factorySwitchStillFollowsProductionPlan() throws Exception {
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(false);

        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("其他角色范围不变: 有 production_plan 的账号照常通过, 不依赖 warehouse")
    void productionUserKeepsAccess() throws Exception {
        when(moduleAccess.canAccessModule(user, "production_plan")).thenReturn(true);
        when(moduleAccess.canAccessModule(user, "warehouse")).thenReturn(false);

        mockMvc.perform(asUser(put(BASE + "/MR-1/start-picking"))).andExpect(status().isOk());
        verify(service).startPicking(eq("F006"), eq("MR-1"), eq(WAREHOUSE_USER));
    }

    @Test
    @DisplayName("权限注解没被动: 三个动作仍要 warehouse:read_write 或 production:read_write, 其它动作不认 warehouse 模块")
    void permissionAnnotationsAreUnchanged() {
        for (String name : List.of("startPicking", "confirmPicking", "transfer")) {
            assertThat(permissions(name)).containsExactlyInAnyOrder("warehouse:read_write", "production:read_write");
        }
        for (String name : List.of("generate", "receive", "close", "cancel")) {
            assertThat(method(name).getAnnotation(com.cretas.aims.annotation.RequireModule.class))
                    .as("%s 不该有方法级模块放宽", name).isNull();
        }
    }

    private static List<String> permissions(String name) {
        return Arrays.asList(method(name).getAnnotation(RequirePermission.class).value());
    }

    private static Method method(String name) {
        return Arrays.stream(FactoryMaterialRequisitionController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

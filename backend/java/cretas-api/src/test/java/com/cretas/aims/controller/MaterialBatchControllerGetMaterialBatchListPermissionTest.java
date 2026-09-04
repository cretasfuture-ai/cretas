package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.cretas.aims.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷 (2026-09-04, LIUSHANMEN organizer handoff) —— {@code MaterialBatchController
 * #getMaterialBatchList} 是 {@code @DataScope("created_by")} 覆盖的 6 处服务方法里，
 * 控制器侧唯二两个此前<b>完全没有 {@code @RequirePermission}</b> 的读端点之一 (另一个是
 * {@code CustomerController#getCustomerList})。{@code @DataScope} 只对 SELF-scope 一线角色
 * (operator/warehouse_worker/quality_inspector/yield_operator) 收窄行，ALL-scope 角色
 * (含 L1 明确未授予 warehouse/inventory 模块的 hr_admin/procurement_manager/finance_manager 等)
 * 之前读到的是不受任何模块权限校验的全工厂批次列表。
 *
 * <p>本测试穿过真实 {@link PermissionInterceptor}，证明拦截器真的按新加的
 * {@code @RequirePermission({"warehouse:read_write","warehouse:read","inventory:read_write",
 * "inventory:read"})} 放行/拒绝 —— 与本控制器已有的同域端点 {@code #getStockSummary}
 * 用完全一致的权限集 (见其 javadoc)。
 *
 * @see com.cretas.aims.controller.inventory.SalesControllerDeliveryDetailPermissionTest reference MockMvc+PermissionInterceptor pattern
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialBatchController#getMaterialBatchList — warehouse/inventory:read(_write) 权限门 (缺陷: 此前无权限校验)")
class MaterialBatchControllerGetMaterialBatchListPermissionTest {

    @Mock private MaterialBatchService materialBatchService;
    @Mock private MobileService mobileService;
    @Mock private PriceMaskResolver priceMaskResolver;
    @Mock private OpeningInventoryService openingInventoryService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    private static final String URL = "/api/mobile/F006/material-batches";

    private MockMvc buildMockMvc() {
        MaterialBatchController controller = new MaterialBatchController(
                materialBatchService, mobileService, priceMaskResolver, openingInventoryService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /**
     * 正向: 持有 inventory:read (只读, 非 warehouse:*) 的角色能读到列表 —— 证明四选一的
     * OR 语义生效, 不要求 warehouse 模块权限。
     */
    @Test
    @DisplayName("持有 inventory:read 的角色 → 200, 到达 service")
    void roleWithInventoryRead_reachesService() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        User viewer = new User();
        viewer.setId(80L);
        when(userRepository.findById(80L)).thenReturn(Optional.of(viewer));
        // NOTE (Mockito varargs pitfall, per 既有先例): getArguments() expands varargs
        // positionally — index 0 = user, index 1.. = each permission code.
        when(permissionService.hasAnyPermission(eq(viewer), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        if ("inventory:read".equals(args[i])) {
                            return true;
                        }
                    }
                    return false;
                });

        PageResponse<MaterialBatchDTO> page = PageResponse.of(List.of(), 1, 20, 0L);
        when(materialBatchService.getMaterialBatchList(eq("F006"), any())).thenReturn(page);

        mockMvc.perform(get(URL).requestAttr("userId", 80L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(materialBatchService).getMaterialBatchList(eq("F006"), any());
    }

    /**
     * 阳性对照 (per rule: 「不再 403」的断言只有在同时证明权限门仍然拦得住无权限角色时
     * 才有意义): 既无 warehouse:* 也无 inventory:* 权限的角色仍然 403，且 service 一次
     * 都没被调用。
     */
    @Test
    @DisplayName("🔴 缺陷本体的阴性对照: 无 warehouse:*/inventory:* 权限的角色 → 403, service 未被调用")
    void roleWithNoWarehouseOrInventoryPermission_isForbiddenAndServiceNeverCalled() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        User outsider = new User();
        outsider.setId(81L);
        when(userRepository.findById(81L)).thenReturn(Optional.of(outsider));
        when(permissionService.hasAnyPermission(eq(outsider), any(String[].class)))
                .thenReturn(false);

        mockMvc.perform(get(URL).requestAttr("userId", 81L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verify(materialBatchService, never()).getMaterialBatchList(anyString(), any());
    }
}

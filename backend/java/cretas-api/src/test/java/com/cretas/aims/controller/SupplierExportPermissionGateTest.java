package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.supplier.SupplierImportService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.security.PriceMaskResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S2-09 (acc-s2 prod 实测 2026-11-03, QATEST_1021_001): 没有采购模块的 sales_manager / workshop_supervisor
 * 对 {@code GET /suppliers/export} 拿到 200 + 整表数据。organizer 裁定: 列表 / 详情读保持开放 (#318,
 * 别的流程的供应商选择器依赖它), 整表导出走与写操作同一套模块闸的「读」级。
 *
 * <p>走真实链路: MockMvc → 真 {@link PermissionInterceptor} (@RequirePermission) → 真 {@link SupplierController},
 * 权限判定用真 {@link PermissionServiceImpl}, 角色 × 模块级别取自工厂配置 (与 F006 一致: 销售经理 / 车间主任
 * 采购「-」, 采购经理采购 rw)。
 */
@DisplayName("供应商整表导出: 采购 / 财务模块读级才能导出; 列表读仍开放")
class SupplierExportPermissionGateTest {

    private static final String F = "S209-F";
    private static final String EXPORT = "/api/mobile/" + F + "/suppliers/export";
    private static final String LIST = "/api/mobile/" + F + "/suppliers";

    private final UserRepository users = mock(UserRepository.class);
    private final SupplierService suppliers = mock(SupplierService.class);
    private final Map<String, Map<String, String>> override = new HashMap<>();
    private MockMvc mvc;
    private long nextId = 1;
    private final Map<String, Long> idOf = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        override.put("procurement_manager", new HashMap<>(Map.of("procurement", "rw")));
        override.put("finance_manager", new HashMap<>(Map.of("procurement", "-", "finance", "rw")));
        override.put("sales_manager", new HashMap<>(Map.of("procurement", "-", "sales", "rw", "finance", "-")));
        override.put("workshop_supervisor", new HashMap<>(Map.of("procurement", "-", "production", "rw", "finance", "-")));
        when(l2.findByFactoryIdAndConfigVersion(F, 1)).thenAnswer(inv -> {
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(F);
            Map<String, Map<String, String>> copy = new HashMap<>();
            override.forEach((role, levels) -> copy.put(role, Map.copyOf(levels)));
            cfg.setRoleModuleOverride(copy);
            return List.of(cfg);
        });
        for (String role : List.of("procurement_manager", "finance_manager", "sales_manager", "workshop_supervisor")) {
            user(role);
        }

        when(suppliers.exportSupplierList(eq(F), anyBoolean())).thenReturn(new byte[] {1, 2, 3});
        when(suppliers.getSupplierList(eq(F), any(PageRequest.class)))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        PriceMaskResolver priceMask = mock(PriceMaskResolver.class);
        SupplierController controller = new SupplierController(suppliers, mock(MobileService.class), priceMask,
                mock(SupplierImportService.class), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .build();
    }

    @ParameterizedTest(name = "{0} 导出 → 403")
    @ValueSource(strings = {"sales_manager", "workshop_supervisor"})
    void rolesWithoutProcurementOrFinanceCannotExport(String role) throws Exception {
        mvc.perform(get(EXPORT).header("Authorization", "Bearer t").requestAttr("userId", idOf.get(role)))
                .andExpect(status().isForbidden())
                // 模块闸自己的措辞 (不是别处的拦截): 「您的角色 [X] … procurement …」
                .andExpect(content().string(org.hamcrest.Matchers.containsString("您的角色")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("procurement")));
        verify(suppliers, never()).exportSupplierList(anyString(), anyBoolean());
    }

    @ParameterizedTest(name = "{0} 导出 → 200")
    @ValueSource(strings = {"procurement_manager", "finance_manager"})
    void procurementOrFinanceReadersCanExport(String role) throws Exception {
        mvc.perform(get(EXPORT).header("Authorization", "Bearer t").requestAttr("userId", idOf.get(role)))
                .andExpect(status().isOk());
        verify(suppliers).exportSupplierList(eq(F), anyBoolean());
    }

    /** 对照 (#318): 列表读仍对没有采购模块的角色开放 —— 别的流程里的供应商选择器依赖它。 */
    @Test
    void theSupplierListStaysOpenForSalesManager() throws Exception {
        mvc.perform(get(LIST).requestAttr("userId", idOf.get("sales_manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void user(String role) {
        long id = nextId++;
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("s209-" + role);
        u.setRoleCode(role);
        u.setIsActive(true);
        when(users.findById(id)).thenReturn(Optional.of(u));
        idOf.put(role, id);
    }
}

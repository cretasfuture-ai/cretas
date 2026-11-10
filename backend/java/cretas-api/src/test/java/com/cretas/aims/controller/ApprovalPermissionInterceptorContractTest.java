package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.reversal.ReportReversalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批权限码走<b>真实的</b> PermissionInterceptor (#1580 权限独立审查 3: 只反射查注解照不出没接上)。
 *
 * <p>报工撤回审批接口: MockMvc + 真 PermissionInterceptor + 真 PermissionServiceImpl (假 L1 / L2 表)。
 * 默认授权 = 原名单且要求 production 有效读写 (审查 2); 显式配置审批码按显式配置。
 */
class ApprovalPermissionInterceptorContractTest {

    private static final String F = "F006";

    private final Map<String, Map<String, String>> fakeL1 = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> fakeL2 = new HashMap<>();
    private final Map<Long, User> users = new HashMap<>();
    private ReportReversalService reversalService;
    private MockMvc mvc;
    private long nextId = 9000;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = fakeL1.getOrDefault(inv.<String>getArgument(0), Collections.emptyMap()).get(inv.<String>getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(new ArrayList<>());
        FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
        when(factoryRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(inv -> {
            Map<String, Map<String, String>> override = fakeL2.get(inv.<String>getArgument(0));
            if (override == null) return Collections.emptyList();
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(inv.getArgument(0));
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", factoryRepo);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.<Long>getArgument(0))));

        reversalService = mock(ReportReversalService.class);
        ReportReversalController controller = new ReportReversalController();
        ReflectionTestUtils.setField(controller, "reversalService", reversalService);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, userRepository, new ObjectMapper()))
                .build();
    }

    private long login(FactoryUserRole role) {
        User u = new User();
        u.setId(nextId++);
        u.setRoleCode(role.name());
        u.setFactoryId(F);
        u.setIsActive(true);
        users.put(u.getId(), u);
        return u.getId();
    }

    private org.springframework.test.web.servlet.ResultActions approve(long userId) throws Exception {
        return mvc.perform(put("/api/mobile/" + F + "/reversals/42/approve").requestAttr("userId", userId));
    }

    @Test
    @DisplayName("默认: 生产经理过拦截器批准; 操作员 / 质检员被拦截器 403, 服务一次都没调")
    void defaultGrantThroughTheRealInterceptor() throws Exception {
        long manager = login(FactoryUserRole.production_manager);
        approve(manager).andExpect(status().isOk());
        verify(reversalService).approveReversal(eq(F), eq(42L), eq(manager), any());

        for (FactoryUserRole frontline : List.of(FactoryUserRole.operator, FactoryUserRole.quality_inspector)) {
            long id = login(frontline);
            approve(id).andExpect(status().isForbidden());
            verify(reversalService, never()).approveReversal(eq(F), eq(42L), eq(id), any());
        }
    }

    @Test
    @DisplayName("审查 2: 本厂把生产经理的 production 降成只读 → 默认不能批 (以前批不了, 现在也不能)")
    void moduleDowngradeRemovesTheDefaultGrant() throws Exception {
        fakeL2.put(F, Map.of("production_manager", Map.of("production", "r")));
        long manager = login(FactoryUserRole.production_manager);
        approve(manager).andExpect(status().isForbidden());
        verify(reversalService, never()).approveReversal(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("显式配置审批码按显式配置: 授给操作员 → 能批; 生产经理改成不授给 → 不能批")
    void explicitApprovalConfigWins() throws Exception {
        fakeL2.put(F, Map.of(
                "operator", Map.of("approval_report_reversal", "rw"),
                "production_manager", Map.of("approval_report_reversal", "-")));
        long operator = login(FactoryUserRole.operator);
        approve(operator).andExpect(status().isOk());
        approve(login(FactoryUserRole.production_manager)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("平台默认 (L1) 降级同样生效: L1 production_manager.production = r → 默认不能批")
    void platformDowngradeRemovesTheDefaultGrant() throws Exception {
        fakeL1.put("production_manager", Map.of("production", "r"));
        approve(login(FactoryUserRole.production_manager)).andExpect(status().isForbidden());
    }
}

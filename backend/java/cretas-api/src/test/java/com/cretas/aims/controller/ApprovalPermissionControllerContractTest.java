package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本厂「谁能审批」一览 (纪律排查 B1–B3/B11/B15): 读的是与 hasPermission 同一条解析链, 并标明来源。
 * 用真的 PermissionServiceImpl + 假 L1 / L2 表。
 */
class ApprovalPermissionControllerContractTest {

    private static final String F = "F006";

    private final Map<String, Map<String, Map<String, String>>> fakeL2 = new HashMap<>();
    private ApprovalPermissionController controller;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(List.of());
        FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
        when(factoryRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(inv -> {
            Map<String, Map<String, String>> override = fakeL2.get(inv.<String>getArgument(0));
            if (override == null) return Collections.emptyList();
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(inv.getArgument(0));
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
        PermissionServiceImpl service = new PermissionServiceImpl();
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryRepo);
        controller = new ApprovalPermissionController(service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cell(String approval, String role) {
        Map<String, Object> body = controller.list(F).getData();
        Map<String, Map<String, Map<String, Object>>> grants = (Map<String, Map<String, Map<String, Object>>>) body.get("grants");
        return grants.get(approval).get(role);
    }

    @Test
    @DisplayName("默认: 生产经理可审撤回 (系统默认), 操作员不可; 超管恒有; 平台管理员不在本厂岗位列表里")
    void defaultsAndSources() {
        assertThat(cell(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "production_manager"))
                .containsEntry("granted", true).containsEntry("source", "DEFAULT");
        assertThat(cell(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "operator"))
                .containsEntry("granted", false).containsEntry("source", "DEFAULT");
        assertThat(cell(PermissionServiceImpl.APPROVAL_REFUND, "factory_super_admin"))
                .containsEntry("granted", true).containsEntry("source", "ALWAYS");
        assertThat(cell(PermissionServiceImpl.APPROVAL_REFUND, "finance_manager")).containsEntry("granted", true);
        assertThat(cell(PermissionServiceImpl.APPROVAL_REFUND, "platform_admin")).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> approvals = (List<Map<String, String>>) controller.list(F).getData().get("approvals");
        assertThat(approvals).extracting(a -> a.get("label"))
                .containsExactly("报工撤回审批", "盘点差异审批", "质检处置审批", "改价审批", "退款审批");
    }

    @Test
    @DisplayName("本厂改过: 授给操作员 → 可审批 (本厂设置); 生产经理改成不授给 → 不可")
    void factoryOverrideIsShownWithItsSource() {
        fakeL2.put(F, Map.of(
                "operator", Map.of(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "rw"),
                "production_manager", Map.of(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "-")));
        assertThat(cell(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "operator"))
                .containsEntry("granted", true).containsEntry("source", "FACTORY");
        assertThat(cell(PermissionServiceImpl.APPROVAL_REPORT_REVERSAL, "production_manager"))
                .containsEntry("granted", false).containsEntry("source", "FACTORY");
    }

    @Test
    @DisplayName("只有能改权限的人能看 (与改授权的接口同一个权限)")
    void sameGateAsTheWriteEndpoint() throws Exception {
        assertThat(ApprovalPermissionController.class.getMethod("list", String.class)
                .getAnnotation(RequirePermission.class).value()).containsExactly("system:read_write");
    }
}

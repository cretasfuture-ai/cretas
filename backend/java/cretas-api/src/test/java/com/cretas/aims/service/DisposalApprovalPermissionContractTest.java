package com.cretas.aims.service;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 B8: 报废审批按「报废审批」权限判断, 不再写死岗位名; App 与后端读同一份判断 (canApprove)。
 * 默认 = 改动前的名单 (财务经理 + 厂长); 工厂可在阈值里改。
 */
@DisplayName("报废审批权限 (disposal:approve)")
class DisposalApprovalPermissionContractTest {

    private static final String FACTORY = "F-DISPOSAL";

    private DisposalRecordService service;
    private ThresholdResolverService thresholds;

    @BeforeEach
    void setUp() {
        service = new DisposalRecordService(mock(DisposalRecordRepository.class), mock(MaterialBatchRepository.class),
                mock(MaterialBatchAdjustmentRepository.class), mock(ProductionBatchRepository.class),
                mock(FinishedGoodsBatchRepository.class), mock(FinishedGoodsAdjustmentLogRepository.class));
        thresholds = mock(ThresholdResolverService.class);
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "thresholdResolver", thresholds);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    private void configure(String roles) {
        when(thresholds.getString(FACTORY, ThresholdKeys.INVENTORY_PERMISSION_DISPOSAL_APPROVE, null)).thenReturn(roles);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("默认: 财务经理和厂长能审批, 仓储主管 / 生产经理 / 质量经理不能 (= 改动前的名单)")
    void defaultsMatchTheOldHardCodedList() {
        assertThat(service.canApprove(FACTORY, "finance_manager")).isTrue();
        assertThat(service.canApprove(FACTORY, "factory_super_admin")).isTrue();
        assertThat(service.canApprove(FACTORY, "warehouse_manager")).isFalse();
        assertThat(service.canApprove(FACTORY, "production_manager")).isFalse();
        assertThat(service.canApprove(FACTORY, "quality_manager")).isFalse();
        // 改动前名单里的平台账号仍然能审批
        assertThat(service.canApprove(FACTORY, "platform_admin")).isTrue();
        assertThat(service.canApprove(FACTORY, "platform_super_admin")).isTrue();
        // 阴性对照: 平台层的其它账号 (开发 / 审计 / 运维) 不因本次改动获得报废审批
        for (String other : new String[]{"developer", "auditor", "system_admin", "operation_admin", "super_admin"}) {
            assertThat(service.canApprove(FACTORY, other)).as(other).isFalse();
        }
    }

    @Test
    @DisplayName("approve / reject 的后端守卫与 canApprove 一致: 没权限 → 403 (在碰任何数据之前)")
    void backendGuardsFollowTheSameAnswer() {
        assertForbidden(() -> service.approveDisposal(FACTORY, 1L, 1, "仓管", "warehouse_manager"));
        assertForbidden(() -> service.rejectDisposal(FACTORY, 1L, 1, "仓管", "不合适", "warehouse_manager"));
    }

    @Test
    @DisplayName("工厂把名单配成 production_manager ⇒ 他能审批了, 财务经理反而不能; 厂长不受影响")
    void factoryConfigurationFlipsTheOutcome() {
        configure("production_manager");
        assertThat(service.canApprove(FACTORY, "production_manager")).isTrue();
        assertThat(service.canApprove(FACTORY, "finance_manager")).isFalse();
        assertThat(service.canApprove(FACTORY, "factory_super_admin")).isTrue();
        assertForbidden(() -> service.approveDisposal(FACTORY, 1L, 1, "财务", "finance_manager"));
    }

    @Test
    @DisplayName("配成空串 = 只有厂长; 权限服务缺失 = 一律拒绝 (不放行)")
    void emptyMeansOnlyTheFactoryHeadAndMissingServiceDenies() {
        configure("");
        assertThat(service.canApprove(FACTORY, "finance_manager")).isFalse();
        assertThat(service.canApprove(FACTORY, "factory_super_admin")).isTrue();

        ReflectionTestUtils.setField(service, "permissionService", null);
        assertThat(service.canApprove(FACTORY, "factory_super_admin")).isFalse();
        assertForbidden(() -> service.approveDisposal(FACTORY, 1L, 1, "厂长", "factory_super_admin"));
    }
}

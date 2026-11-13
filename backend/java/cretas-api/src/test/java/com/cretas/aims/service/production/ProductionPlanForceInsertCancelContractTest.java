package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消一张还在待审批的强制插单: 它在审批画布里的实例必须一起结束 (1551-11 遗留项 2)。
 *
 * <p>以前只把计划置 CANCELLED, 实例留在 RUNNING ⇒ 审批人待办里一直挂着一条点了只会 409 的悬空审批。
 */
class ProductionPlanForceInsertCancelContractTest {

    private static final String F = "F006";
    private static final long OPERATOR = 42L;

    private ProductionPlanRepository plans;
    private ProductionBatchRepository batches;
    private WorkflowEngineService engine;
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        plans = mock(ProductionPlanRepository.class);
        batches = mock(ProductionBatchRepository.class);
        engine = mock(WorkflowEngineService.class);
        service = new ProductionPlanServiceImpl(
                plans, batches, mock(MaterialBatchRepository.class), mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class), mock(ProductTypeRepository.class),
                mock(ProductionPlanMapper.class), mock(ConversionRepository.class), mock(SchedulingService.class),
                mock(com.cretas.aims.repository.ProductionLineRepository.class), mock(UserRepository.class),
                mock(ExcelUtil.class), mock(SalesOrderRepository.class), mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "workflowEngine", engine);
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(batches.findByFactoryIdAndProductionPlanId(eq(F), anyString())).thenReturn(List.of());
    }

    private ProductionPlan plan(String id, boolean forceInserted) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber(id);
        p.setPlannedQuantity(new BigDecimal("50"));
        p.setStatus(ProductionPlanStatus.PENDING);
        if (forceInserted) {
            p.markAsForceInsert(1L, "客户急单", true);
        }
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));
        when(plans.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private static ApprovalWorkflowInstance running(String id, String planId) {
        return ApprovalWorkflowInstance.builder().id(id).factoryId(F).workflowId("WF").moduleCode("FORCE_INSERT")
                .businessEntityId(planId).status(ApprovalWorkflowInstance.InstanceStatus.RUNNING).build();
    }

    @Test
    void cancellingAPendingForceInsertAlsoCancelsItsRunningApprovalInstance() {
        ProductionPlan p = plan("P-1", true);
        when(engine.getCurrentInstance(F, "FORCE_INSERT", "P-1")).thenReturn(Optional.of(running("INST-1", "P-1")));

        service.cancelProductionPlan(F, "P-1", "客户撤单", OPERATOR);

        assertThat(p.getStatus()).as("一定会发生: 计划确实被取消").isEqualTo(ProductionPlanStatus.CANCELLED);
        verify(engine).cancel(eq("INST-1"), eq(OPERATOR), contains("客户撤单"));
    }

    @Test
    void cancellingAPlanWithNoRunningInstanceDoesNotTouchTheWorkflowEngine() {
        ProductionPlan p = plan("P-2", true);
        when(engine.getCurrentInstance(F, "FORCE_INSERT", "P-2")).thenReturn(Optional.empty());

        service.cancelProductionPlan(F, "P-2", "客户撤单", OPERATOR);

        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
        verify(engine, never()).cancel(any(), any(), any());
    }

    @Test
    void anOrdinaryPlanIsNeverLookedUpInTheForceInsertWorkflow() {
        ProductionPlan p = plan("P-3", false);

        service.cancelProductionPlan(F, "P-3", "排错了", OPERATOR);

        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
        verify(engine, never()).getCurrentInstance(any(), any(), any());
        verify(engine, never()).cancel(any(), any(), any());
    }

    @Test
    void anAlreadyCancelledPlanIsIdempotentAndDoesNotCancelTheInstanceAgain() {
        ProductionPlan p = plan("P-4", true);
        p.setStatus(ProductionPlanStatus.CANCELLED);

        service.cancelProductionPlan(F, "P-4", "再点一次", OPERATOR);

        verify(engine, never()).cancel(any(), any(), any());
    }
}

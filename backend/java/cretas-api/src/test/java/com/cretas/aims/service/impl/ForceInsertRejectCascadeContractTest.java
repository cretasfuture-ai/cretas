package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 1551-11 红线 1 独立审查 B1 (a): 驳回强制插单 = 作废计划, 挂在上面还没开工的手工 / AI 批次随之作废 (与取消计划
 * 同一个级联 {@code PlanBatchCascade}), 不再留在 PLANNED 等着被开工。已开工的存量批次不在这里动 (要走报工撤回)。
 * 入口: 真实的直接审批入口 {@code approveForceInsert(.., approved=false, ..)}。
 */
class ForceInsertRejectCascadeContractTest {

    private static final String F = "F006";

    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InsertSlotRepository slots = mock(InsertSlotRepository.class);
    private final ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
    private final WorkProcessTaskRepository tasks = mock(WorkProcessTaskRepository.class);

    private UrgentInsertServiceImpl service;
    private ProductionPlan plan;
    private ProductionBatch planned;
    private ProductionBatch running;
    private WorkProcessTask openTask;

    @BeforeEach
    void setUp() {
        // 本人审批判定: 审批人不是提交人 => 非本人 (gate 为 null 时 service fail-closed, 为 mock 不打桩返回 null)
        com.cretas.aims.service.workflow.FactorySelfApprovalGate selfGate = mock(com.cretas.aims.service.workflow.FactorySelfApprovalGate.class);
        when(selfGate.decide(anyString(), anyBoolean(), any()))
                .thenReturn(com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision.NOT_SELF);
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                ProductionPlanRepository.class, plans, UserRepository.class, users,
                InsertSlotRepository.class, slots,
                SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class),
                com.cretas.aims.service.workflow.FactorySelfApprovalGate.class, selfGate));
        ReflectionTestUtils.setField(service, "productionBatchRepository", batches);
        ReflectionTestUtils.setField(service, "workProcessTaskRepository", tasks);

        plan = new ProductionPlan();
        plan.setId("PP-QA-URG-9");
        plan.setFactoryId(F);
        plan.setPlanNumber("QA-URG-0009");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.markAsForceInsert(1L, "客户急单", true);
        when(plans.findById("PP-QA-URG-9")).thenReturn(Optional.of(plan));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));

        User approver = new User();
        approver.setId(9L);
        approver.setFullName("QA生产经理");
        when(users.findById(9L)).thenReturn(Optional.of(approver));
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of());

        planned = batch(5501L, "QA-PB-5501", ProductionBatchStatus.PLANNED);
        running = batch(5502L, "QA-PB-5502", ProductionBatchStatus.IN_PROGRESS);
        when(batches.findByFactoryIdAndProductionPlanId(F, "PP-QA-URG-9")).thenReturn(List.of(planned, running));

        openTask = new WorkProcessTask();
        openTask.setStatus(WorkProcessTask.Status.PENDING);
        when(tasks.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 5501L)).thenReturn(List.of(openTask));
    }

    private static ProductionBatch batch(Long id, String no, ProductionBatchStatus status) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber(no);
        b.setProductionPlanId("PP-QA-URG-9");
        b.setStatus(status);
        return b;
    }

    @Test
    void rejectCancelsUnstartedBatchesAndTheirTasksButLeavesStartedOnes() {
        service.approveForceInsert(F, "PP-QA-URG-9", 9L, false, "产能已满");

        assertThat(plan.isRejected()).isTrue();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
        assertThat(planned.getStatus()).as("未开工批次随驳回作废").isEqualTo(ProductionBatchStatus.CANCELLED);
        verify(batches).save(planned);
        assertThat(openTask.getStatus()).isEqualTo(WorkProcessTask.Status.CANCELLED);
        assertThat(running.getStatus()).as("已开工存量不在这里动 (要走报工撤回)").isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(batches, never()).save(running);
    }

    @Test
    void negativeControlApproveDoesNotTouchBatches() {
        // 批准走排程 (这里 scheduler 是放行桩), 不该碰批次
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of(slotFor(plan)));
        service.approveForceInsert(F, "PP-QA-URG-9", 9L, true, "同意");

        assertThat(plan.isApproved()).isTrue();
        assertThat(planned.getStatus()).isEqualTo(ProductionBatchStatus.PLANNED);
        verify(batches, never()).save(any());
    }

    private static com.cretas.aims.entity.InsertSlot slotFor(ProductionPlan p) {
        com.cretas.aims.entity.InsertSlot s = new com.cretas.aims.entity.InsertSlot();
        s.setStartTime(p.getStartTime());
        s.setProductionLineId(p.getSuggestedProductionLineId());
        s.setStatus("selected");
        return s;
    }
}

package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.InsertSlotPreview;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1551-11: 强制插单接进审批画布运行时。
 *
 * <ul>
 *   <li>有启用画布 ⇒ 提交时建画布实例, 计划待审批, 不排入;</li>
 *   <li>OA 批准走完画布 ⇒ 才插入并顺延排程; 中间节点只推进; 驳回 ⇒ 作废计划、释放时段;</li>
 *   <li>提交人本人审批遵守工厂「允许本人审批」开关 (#1580), 开着时原因留痕;</li>
 *   <li>走画布的计划, 直接审批入口拒绝 (否则绕过画布配的审批人);</li>
 *   <li>没有画布也没有旧配置 ⇒ 维持原状 (不建实例, 直接排入)。</li>
 * </ul>
 *
 * <p>画布引擎是桩: 引擎本身的节点推进有自己的测试; 这里守的是插单服务对实例状态的投影。
 */
class UrgentInsertForceInsertCanvasContractTest {

    private static final String F = "F-FI-CANVAS";
    private static final String PT = "PT-1";
    private static final long SUBMITTER = 1L;
    private static final long APPROVER = 9L;
    private final LocalDateTime start = LocalDateTime.of(2027, 1, 5, 9, 0);

    private InsertSlotRepository slots;
    private ProductionPlanRepository plans;
    private InsertSlotPreviewRepository previews;
    private UrgentInsertScheduleService scheduler;
    private ApprovalChainService approvalChain;
    private WorkflowEngineService engine;
    private FactorySettingsRepository settings;
    private com.cretas.aims.service.discipline.FactoryDisciplinePolicy discipline;
    private com.cretas.aims.service.ApprovalWorkflowService workflows;
    private com.cretas.aims.service.DecisionAuditService audit;
    private UrgentInsertServiceImpl service;
    private InsertSlot slot;

    @BeforeEach
    void setUp() {
        slots = mock(InsertSlotRepository.class);
        plans = mock(ProductionPlanRepository.class);
        previews = mock(InsertSlotPreviewRepository.class);
        ImpactAnalysisService impact = mock(ImpactAnalysisService.class);
        slot = InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(start).endTime(start.plusHours(2)).requiredWorkers(2).availableWorkers(0)
                .availableCapacity(BigDecimal.TEN).impactLevel("none").status("available").build();
        slot.setUpdatedAt(start.minusMinutes(5));
        when(slots.findByIdAndFactoryId("S1", F)).thenReturn(Optional.of(slot));
        when(slots.claimAvailableSlot(eq("S1"), eq(F), any())).thenReturn(1);
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        ChainImpactResult chain = ChainImpactResult.builder().impactLevel("none").requiresApproval(false)
                .affectedPlans(List.of()).totalAffectedPlans(0).directConflicts(0).cascadeDelays(0)
                .maxDelayMinutes(0).totalDelayMinutes(0).affectedVipCustomers(0)
                .criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build();
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any())).thenReturn(chain);
        when(impact.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        when(previews.findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(F), any())).thenReturn(List.of());
        when(previews.countByFactoryIdAndSlotId(F, "S1")).thenReturn(0L);
        when(previews.save(any())).thenAnswer(i -> i.getArgument(0));
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductType product = new ProductType();
        product.setId(PT);
        product.setFactoryId(F);
        product.setUnit("kg");
        when(products.findByIdAndFactoryId(PT, F)).thenReturn(Optional.of(product));
        when(products.findByIdAndFactoryIdForUpdate(PT, F)).thenReturn(Optional.of(product));
        ResourceCheckResult ready = ResourceCheckResult.available("TEST", BigDecimal.TEN, BigDecimal.ONE);
        LineCapacityEquipmentCheck lines = mock(LineCapacityEquipmentCheck.class);
        when(lines.checkEquipment(anyString(), anyString())).thenReturn(ready);
        when(lines.checkCapacity(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(ready);
        WorkerShiftAvailabilityCheck workers = mock(WorkerShiftAvailabilityCheck.class);
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, 0, 0, Set.of(), "ok"));
        scheduler = UrgentInsertTestSupport.okScheduler();
        UserRepository users = mock(UserRepository.class);
        when(users.findById(SUBMITTER)).thenReturn(Optional.of(user(SUBMITTER, "提交人")));
        when(users.findById(APPROVER)).thenReturn(Optional.of(user(APPROVER, "生产经理")));
        approvalChain = mock(ApprovalChainService.class);
        engine = mock(WorkflowEngineService.class);
        settings = mock(FactorySettingsRepository.class);
        discipline = mock(com.cretas.aims.service.discipline.FactoryDisciplinePolicy.class);
        workflows = mock(com.cretas.aims.service.ApprovalWorkflowService.class);
        audit = mock(com.cretas.aims.service.DecisionAuditService.class);
        when(engine.canTransition(any(), any())).thenReturn(true);
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(UrgentInsertScheduleService.class, scheduler),
                Map.entry(UserRepository.class, users),
                Map.entry(InsertSlotRepository.class, slots),
                Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(InsertSlotPreviewRepository.class, previews),
                Map.entry(ProductTypeRepository.class, products),
                Map.entry(ImpactAnalysisService.class, impact),
                Map.entry(LineCapacityEquipmentCheck.class, lines),
                Map.entry(WorkerShiftAvailabilityCheck.class, workers),
                Map.entry(SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class)),
                Map.entry(ObjectMapper.class, new ObjectMapper()),
                Map.entry(ApprovalChainService.class, approvalChain),
                Map.entry(WorkflowEngineService.class, engine),
                Map.entry(FactorySelfApprovalGate.class, new FactorySelfApprovalGate(settings)),
                Map.entry(FactorySettingsRepository.class, settings),
                Map.entry(com.cretas.aims.service.discipline.FactoryDisciplinePolicy.class, discipline),
                Map.entry(com.cretas.aims.service.ApprovalWorkflowService.class, workflows),
                Map.entry(com.cretas.aims.service.DecisionAuditService.class, audit)));
    }

    // ------------------------------------------------------------------ fixtures

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        u.setRoleCode("production_manager");
        return u;
    }

    private static ApprovalWorkflowInstance instance(String id, String planId, InstanceStatus status) {
        ApprovalWorkflowInstance i = ApprovalWorkflowInstance.builder()
                .id(id).factoryId(F).workflowId("WF-FI").moduleCode("FORCE_INSERT")
                .businessEntityId(planId).status(status).build();
        return i;
    }

    private GetInsertSlotsRequest analyzeRequest() {
        GetInsertSlotsRequest r = new GetInsertSlotsRequest();
        r.setProductTypeId(PT);
        r.setRequiredQuantity(new BigDecimal("50"));
        r.setQuantityUnit("KG");
        return r;
    }

    private ConfirmUrgentInsertRequest forceRequest(String previewId) {
        return ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId(PT).previewId(previewId)
                .quantityUnit("KG").plannedQuantity(new BigDecimal("50")).forceInsert(true)
                .notes("客户急单").build();
    }

    private InsertSlotPreview issuePreview() {
        Map<String, Object> out = service.createInsertPreview(F, SUBMITTER, "S1", analyzeRequest());
        assertThat(out.get("previewId")).as("预览应已签发").isNotNull();
        ArgumentCaptor<InsertSlotPreview> saved = ArgumentCaptor.forClass(InsertSlotPreview.class);
        verify(previews).save(saved.capture());
        InsertSlotPreview preview = saved.getValue();
        preview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId(preview.getId(), F)).thenReturn(Optional.of(preview));
        return preview;
    }

    private void canvasEnabled() {
        when(approvalChain.requiresApproval(eq(F), eq(DecisionType.FORCE_INSERT), anyMap())).thenReturn(true);
    }

    /** 一张走画布、待审批的强制插单 (提交人 SUBMITTER), 时段 S1 已被它占着。 */
    private ProductionPlan pendingCanvasPlan(String planId, String instanceId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(planId);
        p.setFactoryId(F);
        p.setPlanNumber("URGENT-" + planId);
        p.setStartTime(start);
        p.setSuggestedProductionLineId("L1");
        p.setCreatedBy(SUBMITTER);
        p.markAsForceInsert(SUBMITTER, "客户急单", true);
        p.setStatus(ProductionPlanStatus.PENDING);
        when(plans.findById(planId)).thenReturn(Optional.of(p));
        slot.setStatus("selected");
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of(slot));
        when(engine.getInstance(F, instanceId))
                .thenReturn(Optional.of(instance(instanceId, planId, InstanceStatus.RUNNING)));
        when(engine.getLatestInstance(F, "FORCE_INSERT", planId))
                .thenReturn(Optional.of(instance(instanceId, planId, InstanceStatus.RUNNING)));
        return p;
    }

    private void transitionEndsIn(String instanceId, String planId, InstanceStatus status) {
        when(engine.transitionNode(eq(instanceId), any(), any(), any(), any(), any()))
                .thenReturn(instance(instanceId, planId, status));
    }

    private static String code(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ------------------------------------------------------------------ 提交

    @Test
    void withAnEnabledCanvasTheForceInsertStartsAnInstanceAndWaitsWithoutScheduling() {
        canvasEnabled();
        when(engine.startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), anyString(), anyMap(), eq(SUBMITTER)))
                .thenAnswer(i -> Optional.of(instance("INST-1", i.getArgument(2), InstanceStatus.RUNNING)));
        InsertSlotPreview preview = issuePreview();

        service.forceInsert(F, SUBMITTER, forceRequest(preview.getId()));

        ArgumentCaptor<ProductionPlan> saved = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(plans, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ProductionPlan plan = saved.getValue();
        assertThat(plan.isPendingApproval()).as("计划待审批").isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(engine).startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), eq(plan.getId()), ctx.capture(),
                eq(SUBMITTER));
        assertThat(ctx.getValue()).containsEntry("planId", plan.getId())
                .containsEntry("planNumber", plan.getPlanNumber())
                .containsEntry("forceInsertReason", "客户急单")
                .containsKey("impactLevel");
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void aCanvasThatApprovesOnSubmitSchedulesImmediatelyAndRecordsTheAutoApproval() {
        canvasEnabled();
        when(engine.startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), anyString(), anyMap(), eq(SUBMITTER)))
                .thenAnswer(i -> Optional.of(instance("INST-A", i.getArgument(2), InstanceStatus.APPROVED)));
        InsertSlotPreview preview = issuePreview();

        service.forceInsert(F, SUBMITTER, forceRequest(preview.getId()));

        ArgumentCaptor<ProductionPlan> applied = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(scheduler).apply(eq(F), any(), applied.capture(), eq(UrgentInsertTestSupport.SCHEDULE_FP), eq(SUBMITTER));
        assertThat(applied.getValue().getApprovalStatus()).isEqualTo(ProductionPlan.ApprovalStatus.APPROVED);
        assertThat(applied.getValue().getApproverId()).as("没有人审, 不能记成提交人批的").isNull();
    }

    @Test
    void aCanvasThatEndsInRejectOnSubmitFailsTheWholeSubmission() {
        canvasEnabled();
        when(engine.startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), anyString(), anyMap(), eq(SUBMITTER)))
                .thenAnswer(i -> Optional.of(instance("INST-R", i.getArgument(2), InstanceStatus.REJECTED)));
        InsertSlotPreview preview = issuePreview();

        assertThatThrownBy(() -> service.forceInsert(F, SUBMITTER, forceRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("FORCE_INSERT_WORKFLOW_TERMINAL_ON_START"));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void canvasDisabledBetweenTheCheckAndTheStartIsAConflictNotASilentInsert() {
        canvasEnabled();
        when(engine.startWorkflowIfConfigured(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        InsertSlotPreview preview = issuePreview();

        assertThatThrownBy(() -> service.forceInsert(F, SUBMITTER, forceRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("FORCE_INSERT_WORKFLOW_NOT_CONFIGURED"));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void withoutCanvasOrLegacyConfigNothingChangesNoInstanceAndImmediateSchedule() {
        when(approvalChain.requiresApproval(eq(F), eq(DecisionType.FORCE_INSERT), anyMap())).thenReturn(false);
        InsertSlotPreview preview = issuePreview();

        service.forceInsert(F, SUBMITTER, forceRequest(preview.getId()));

        verify(engine, never()).startWorkflowIfConfigured(any(), any(), any(), any(), any());
        verify(scheduler).apply(eq(F), any(), any(ProductionPlan.class), eq(UrgentInsertTestSupport.SCHEDULE_FP),
                eq(SUBMITTER));
    }

    @Test
    void anUnmigratedLegacyChainStillRefusesBeforeAnythingIsWritten() {
        when(approvalChain.requiresApproval(eq(F), eq(DecisionType.FORCE_INSERT), anyMap()))
                .thenThrow(new BusinessException(409, "旧版审批配置尚未迁移到审批画布")
                        .withCode("OA_LEGACY_CONFIG_MIGRATION_REQUIRED"));
        InsertSlotPreview preview = issuePreview();

        assertThatThrownBy(() -> service.forceInsert(F, SUBMITTER, forceRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("OA_LEGACY_CONFIG_MIGRATION_REQUIRED"));
        verify(slots, never()).claimAvailableSlot(any(), any(), any());
        verify(plans, never()).save(any(ProductionPlan.class));
    }

    // ------------------------------------------------------------------ 工厂开关「插单一律走审批」(Steve 2026-11-11)

    private ConfirmUrgentInsertRequest normalRequest(String previewId) {
        return ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId(PT).previewId(previewId)
                .quantityUnit("KG").plannedQuantity(new BigDecimal("50")).notes("普通插单").build();
    }

    private void activeCanvas(boolean present) {
        when(workflows.getActiveByDecisionType(F, DecisionType.FORCE_INSERT)).thenReturn(present
                ? Optional.of(com.cretas.aims.entity.config.ApprovalWorkflow.builder().id("WF-FI").build())
                : Optional.empty());
    }

    @Test
    void switchOnWithACanvasSendsEvenALowImpactInsertIntoApproval() {
        when(discipline.urgentInsertAlwaysApproval(F)).thenReturn(true);
        activeCanvas(true);
        canvasEnabled();
        when(engine.startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), anyString(), anyMap(), eq(SUBMITTER)))
                .thenAnswer(i -> Optional.of(instance("INST-ALL", i.getArgument(2), InstanceStatus.RUNNING)));
        InsertSlotPreview preview = issuePreview();
        assertThat(preview.getRequiresApproval()).as("一定会发生: 这张预览本身不要求审批 (低影响)").isFalse();

        service.confirmInsert(F, SUBMITTER, normalRequest(preview.getId()));

        verify(engine).startWorkflowIfConfigured(eq(F), eq("FORCE_INSERT"), anyString(), anyMap(), eq(SUBMITTER));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        ArgumentCaptor<ProductionPlan> saved = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(plans, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().isPendingApproval()).isTrue();
        // 审计标明「普通插单因开关转入」, 不与真正的强制插单混算
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(audit).logForceInsert(eq(F), eq("ProductionPlan"), anyString(), ctx.capture(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), eq(SUBMITTER), any(), any());
        assertThat(ctx.getValue()).containsEntry("routedBy", UrgentInsertServiceImpl.ROUTED_BY_ALWAYS_APPROVAL);
    }

    @Test
    void switchOffKeepsTheOldBehaviourALowImpactInsertIsScheduledDirectly() {
        when(discipline.urgentInsertAlwaysApproval(F)).thenReturn(false);
        activeCanvas(true);
        canvasEnabled();
        InsertSlotPreview preview = issuePreview();

        service.confirmInsert(F, SUBMITTER, normalRequest(preview.getId()));

        verify(engine, never()).startWorkflowIfConfigured(any(), any(), any(), any(), any());
        verify(scheduler).apply(eq(F), any(), any(ProductionPlan.class), eq(UrgentInsertTestSupport.SCHEDULE_FP),
                eq(SUBMITTER));
    }

    @Test
    void switchOnWithoutAnActiveCanvasHasNobodyToApproveSoTheInsertIsScheduledDirectly() {
        when(discipline.urgentInsertAlwaysApproval(F)).thenReturn(true);
        activeCanvas(false);
        // 本厂还挂着没迁移的旧版审批链: 强制插单路会 409 LEGACY; 普通插单不应被它挡住 (开关只认画布)
        when(approvalChain.requiresApproval(eq(F), eq(DecisionType.FORCE_INSERT), anyMap()))
                .thenThrow(new BusinessException(409, "旧版审批配置尚未迁移到审批画布")
                        .withCode("OA_LEGACY_CONFIG_MIGRATION_REQUIRED"));
        InsertSlotPreview preview = issuePreview();

        service.confirmInsert(F, SUBMITTER, normalRequest(preview.getId()));

        verify(engine, never()).startWorkflowIfConfigured(any(), any(), any(), any(), any());
        ArgumentCaptor<ProductionPlan> saved = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(plans, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getIsForceInserted()).as("没转入强制插单路").isNotEqualTo(Boolean.TRUE);
        verify(scheduler).apply(eq(F), any(), any(ProductionPlan.class), eq(UrgentInsertTestSupport.SCHEDULE_FP),
                eq(SUBMITTER));
    }

    @Test
    void anUnreadableSwitchCountsAsOff() {
        when(discipline.urgentInsertAlwaysApproval(F)).thenThrow(new IllegalStateException("db down"));
        activeCanvas(true);
        InsertSlotPreview preview = issuePreview();

        service.confirmInsert(F, SUBMITTER, normalRequest(preview.getId()));

        verify(engine, never()).startWorkflowIfConfigured(any(), any(), any(), any(), any());
        verify(scheduler).apply(any(), any(), any(), any(), eq(SUBMITTER));
    }

    // ------------------------------------------------------------------ OA 批准 / 驳回

    @Test
    void finalApprovalInsertsAndShiftsTheSchedule() {
        ProductionPlan plan = pendingCanvasPlan("P-1", "INST-1");
        transitionEndsIn("INST-1", "P-1", InstanceStatus.APPROVED);

        service.applyWorkflowAction(F, "P-1", "INST-1", APPROVER, "production_manager", null,
                HistoryAction.APPROVE, "同意");

        verify(engine).transitionNode("INST-1", APPROVER, "production_manager", HistoryAction.APPROVE, "同意", null);
        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(APPROVER));
        assertThat(plan.getApprovalStatus()).isEqualTo(ProductionPlan.ApprovalStatus.APPROVED);
        assertThat(plan.getApproverId()).isEqualTo(APPROVER);
    }

    @Test
    void anIntermediateNodeOnlyAdvancesTheCanvasAndTheInsertKeepsWaiting() {
        ProductionPlan plan = pendingCanvasPlan("P-2", "INST-2");
        transitionEndsIn("INST-2", "P-2", InstanceStatus.RUNNING);

        service.applyWorkflowAction(F, "P-2", "INST-2", APPROVER, "production_manager", null,
                HistoryAction.APPROVE, "一级同意");

        verify(engine).transitionNode(eq("INST-2"), any(), any(), eq(HistoryAction.APPROVE), any(), any());
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        assertThat(plan.isPendingApproval()).isTrue();
    }

    @Test
    void rejectionCancelsThePlanAndReleasesTheSlotWithoutScheduling() {
        ProductionPlan plan = pendingCanvasPlan("P-3", "INST-3");
        transitionEndsIn("INST-3", "P-3", InstanceStatus.REJECTED);

        service.applyWorkflowAction(F, "P-3", "INST-3", APPROVER, "production_manager", null,
                HistoryAction.REJECT, "产能不够");

        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
        assertThat(plan.getApprovalStatus()).isEqualTo(ProductionPlan.ApprovalStatus.REJECTED);
        assertThat(slot.getStatus()).as("驳回释放占用的时段").isEqualTo("available");
        verify(slots).save(slot);
    }

    @Test
    void rejectionWithoutAReasonIsRefusedBeforeTheCanvasMoves() {
        pendingCanvasPlan("P-4", "INST-4");

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-4", "INST-4", APPROVER,
                "production_manager", null, HistoryAction.REJECT, "  "))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_REJECT_REASON_REQUIRED"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
        assertThat(slot.getStatus()).isEqualTo("selected");
    }

    @Test
    void anAlreadyFinishedInstanceIsAConflictWithoutASecondInsert() {
        pendingCanvasPlan("P-5", "INST-5");
        when(engine.getInstance(F, "INST-5"))
                .thenReturn(Optional.of(instance("INST-5", "P-5", InstanceStatus.APPROVED)));

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-5", "INST-5", APPROVER, "production_manager", null,
                HistoryAction.REJECT, "不同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_ALREADY_DECIDED"));

        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void anInstanceOfAnotherPlanIsAMismatch() {
        pendingCanvasPlan("P-6", "INST-6");
        when(engine.getInstance(F, "INST-6"))
                .thenReturn(Optional.of(instance("INST-6", "OTHER-PLAN", InstanceStatus.RUNNING)));

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-6", "INST-6", APPROVER,
                "production_manager", null, HistoryAction.APPROVE, "同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_WORKFLOW_MISMATCH"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPlanCancelledWhileWaitingCannotBeApprovedAndRejectingItLeavesTheSlotAlone() {
        ProductionPlan plan = pendingCanvasPlan("P-7", "INST-7");
        plan.setStatus(ProductionPlanStatus.CANCELLED);

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-7", "INST-7", APPROVER,
                "production_manager", null, HistoryAction.APPROVE, "同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_NOT_PENDING"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());

        // 驳回只结束审批: 时段早已随取消释放, 现在 selected 的可能是别的计划占着
        transitionEndsIn("INST-7", "P-7", InstanceStatus.REJECTED);
        service.applyWorkflowAction(F, "P-7", "INST-7", APPROVER, "production_manager", null,
                HistoryAction.REJECT, "计划已取消");
        verify(engine).transitionNode(eq("INST-7"), any(), any(), eq(HistoryAction.REJECT), any(), any());
        assertThat(slot.getStatus()).isEqualTo("selected");
        verify(slots, never()).save(any());
    }

    @Test
    void aNodeThatMovedOnUnderTheLockIsAConflictNotAnApprovalOfTheNextNode() {
        pendingCanvasPlan("P-14", "INST-14");
        ApprovalWorkflowInstance moved = instance("INST-14", "P-14", InstanceStatus.RUNNING);
        moved.setCurrentNodeIds(new java.util.ArrayList<>(List.of("level_2")));
        when(engine.getInstance(F, "INST-14")).thenReturn(Optional.of(moved));

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-14", "INST-14", APPROVER,
                "production_manager", "level_1", HistoryAction.APPROVE, "同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OA_TASK_NODE_CHANGED"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void anActorNotOnTheCurrentNodeIsRefusedUnderTheLock() {
        pendingCanvasPlan("P-15", "INST-15");
        when(engine.canTransition(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-15", "INST-15", APPROVER,
                "production_manager", null, HistoryAction.APPROVE, "同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OA_TASK_FORBIDDEN"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 按当前审批流重新发起

    @Test
    void restartThatApprovesOnCreationSchedulesThePlan() {
        ProductionPlan plan = pendingCanvasPlan("P-16", "INST-16");
        when(engine.restartWithCurrentDefinition(F, "INST-16", 1309L, "画布改过"))
                .thenReturn(instance("INST-16B", "P-16", InstanceStatus.APPROVED));

        service.restartWorkflowBinding(F, "INST-16", 1309L, "画布改过");

        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(1309L));
        assertThat(plan.getApprovalStatus()).isEqualTo(ProductionPlan.ApprovalStatus.APPROVED);
    }

    @Test
    void restartThatRejectsOnCreationRollsBackInsteadOfStrandingThePlan() {
        pendingCanvasPlan("P-17", "INST-17");
        when(engine.restartWithCurrentDefinition(F, "INST-17", 1309L, "画布改过"))
                .thenReturn(instance("INST-17B", "P-17", InstanceStatus.REJECTED));

        assertThatThrownBy(() -> service.restartWorkflowBinding(F, "INST-17", 1309L, "画布改过"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_WORKFLOW_TERMINAL_ON_START"));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void restartThatStillWaitsChangesNothing() {
        ProductionPlan plan = pendingCanvasPlan("P-18", "INST-18");
        when(engine.restartWithCurrentDefinition(F, "INST-18", 1309L, "画布改过"))
                .thenReturn(instance("INST-18B", "P-18", InstanceStatus.RUNNING));

        service.restartWorkflowBinding(F, "INST-18", 1309L, "画布改过");

        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        assertThat(plan.isPendingApproval()).isTrue();
    }

    // ------------------------------------------------------------------ 本人审批 (#1580)

    @Test
    void theSubmitterCannotApproveTheirOwnInsertWhileTheSwitchIsOff() {
        pendingCanvasPlan("P-8", "INST-8");
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-8", "INST-8", SUBMITTER,
                "factory_super_admin", null, HistoryAction.APPROVE, "我自己批"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_SELF_APPROVAL_FORBIDDEN"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void withTheSwitchOnTheSubmitterMayApproveButMustGiveAReasonThatIsRecorded() {
        ProductionPlan plan = pendingCanvasPlan("P-9", "INST-9");
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(true);

        assertThatThrownBy(() -> service.applyWorkflowAction(F, "P-9", "INST-9", SUBMITTER,
                "factory_super_admin", null, HistoryAction.APPROVE, ""))
                .satisfies(e -> assertThat(code(e)).isEqualTo(FactorySelfApprovalGate.REASON_REQUIRED));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any(), any());

        transitionEndsIn("INST-9", "P-9", InstanceStatus.APPROVED);
        service.applyWorkflowAction(F, "P-9", "INST-9", SUBMITTER, "factory_super_admin", null,
                HistoryAction.APPROVE, "本厂只有我一个人能审批");

        verify(engine).transitionNode("INST-9", SUBMITTER, "factory_super_admin", HistoryAction.APPROVE,
                UrgentInsertServiceImpl.SELF_APPROVAL_MARK + "本厂只有我一个人能审批", null);
        assertThat(plan.getApprovalComment()).isEqualTo(UrgentInsertServiceImpl.SELF_APPROVAL_MARK + "本厂只有我一个人能审批");
        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(SUBMITTER));
    }

    @Test
    void anotherApproverIsNotSelfApprovalEvenWithTheSwitchOff() {
        pendingCanvasPlan("P-10", "INST-10");
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);
        transitionEndsIn("INST-10", "P-10", InstanceStatus.APPROVED);

        service.applyWorkflowAction(F, "P-10", "INST-10", APPROVER, "production_manager", null,
                HistoryAction.APPROVE, "同意");

        verify(scheduler).apply(any(), any(), any(), any(), eq(APPROVER));
    }

    // ------------------------------------------------------------------ 直接审批入口

    @Test
    void theDirectApprovalEndpointRefusesACanvasPlanSoItCannotBypassTheCanvas() {
        pendingCanvasPlan("P-11", "INST-11");

        assertThatThrownBy(() -> service.approveForceInsert(F, "P-11", APPROVER, true, "同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_APPROVAL_VIA_OA"));
        assertThatThrownBy(() -> service.approveForceInsert(F, "P-11", APPROVER, false, "不同意"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_APPROVAL_VIA_OA"));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        assertThat(slot.getStatus()).isEqualTo("selected");
    }

    @Test
    void theDirectApprovalEndpointStillWorksForAPlanWithoutACanvas() {
        ProductionPlan plan = pendingCanvasPlan("P-12", "INST-12");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-12")).thenReturn(Optional.empty());

        ProductionPlanDTO dto = service.approveForceInsert(F, "P-12", APPROVER, true, "同意");

        assertThat(dto).isNotNull();
        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(APPROVER));
    }

    @Test
    void directRejectionReleasesOnlyTheSlotOnThePlansOwnLine() {
        ProductionPlan plan = pendingCanvasPlan("P-13", "INST-13");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-13")).thenReturn(Optional.empty());
        InsertSlot otherLine = InsertSlot.builder().id("S2").factoryId(F).productionLineId("L2")
                .startTime(start).endTime(start.plusHours(2)).status("selected").build();
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of(otherLine, slot));

        service.approveForceInsert(F, "P-13", APPROVER, false, "不同意");

        assertThat(otherLine.getStatus()).as("别的产线同一时刻的时段不动").isEqualTo("selected");
        assertThat(slot.getStatus()).isEqualTo("available");
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
    }

    // ------------------------------------------------------------------ 收尾 (1551-11 遗留): 直接入口本人审批 / 并行节点 / 待审批列表

    @Test
    void directApprovalByTheSubmitterIsForbiddenWhileTheSwitchIsOff() {
        pendingCanvasPlan("P-20", "INST-20");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-20")).thenReturn(Optional.empty());
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);

        assertThatThrownBy(() -> service.approveForceInsert(F, "P-20", SUBMITTER, true, "我自己批"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORCE_INSERT_SELF_APPROVAL_FORBIDDEN"));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
        assertThat(slot.getStatus()).as("没批成, 时段仍被占着").isEqualTo("selected");
    }

    @Test
    void directApprovalBySubmitterWithTheSwitchOnNeedsAReasonAndIsMarkedInTheComment() {
        ProductionPlan plan = pendingCanvasPlan("P-21", "INST-21");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-21")).thenReturn(Optional.empty());
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(true);

        assertThatThrownBy(() -> service.approveForceInsert(F, "P-21", SUBMITTER, true, " "))
                .satisfies(e -> assertThat(code(e)).isEqualTo(FactorySelfApprovalGate.REASON_REQUIRED));
        verify(scheduler, never()).apply(any(), any(), any(), any(), any());

        service.approveForceInsert(F, "P-21", SUBMITTER, true, "本厂只有我一个人");

        assertThat(plan.getApprovalComment()).isEqualTo(UrgentInsertServiceImpl.SELF_APPROVAL_MARK + "本厂只有我一个人");
        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(SUBMITTER));
    }

    @Test
    void directApprovalByAnotherPersonIsNotSelfApprovalEvenWithTheSwitchOff() {
        ProductionPlan plan = pendingCanvasPlan("P-22", "INST-22");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-22")).thenReturn(Optional.empty());
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);

        service.approveForceInsert(F, "P-22", APPROVER, true, "同意");

        assertThat(plan.getApprovalComment()).isEqualTo("同意");
        verify(scheduler).apply(eq(F), eq(slot), eq(plan), isNull(), eq(APPROVER));
    }

    @Test
    void directRejectionByTheSubmitterIsAWithdrawalAndIsNotBlocked() {
        ProductionPlan plan = pendingCanvasPlan("P-23", "INST-23");
        when(engine.getLatestInstance(F, "FORCE_INSERT", "P-23")).thenReturn(Optional.empty());
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);

        service.approveForceInsert(F, "P-23", SUBMITTER, false, "不要了");

        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo("available");
    }

    @Test
    void theOaActionAdvancesTheNodeTheApproverWasShownNotTheFirstActiveNode() {
        pendingCanvasPlan("P-24", "INST-24");
        ApprovalWorkflowInstance parallel = instance("INST-24", "P-24", InstanceStatus.RUNNING);
        parallel.setCurrentNodeIds(new java.util.ArrayList<>(List.of("branch_a", "branch_b")));
        when(engine.getInstance(F, "INST-24")).thenReturn(Optional.of(parallel));
        transitionEndsIn("INST-24", "P-24", InstanceStatus.RUNNING);

        service.applyWorkflowAction(F, "P-24", "INST-24", APPROVER, "production_manager", "branch_b",
                HistoryAction.APPROVE, "二号分支同意");

        verify(engine).transitionNode("INST-24", APPROVER, "production_manager", HistoryAction.APPROVE,
                "二号分支同意", "branch_b");
    }

    @Test
    void pendingListMarksCanvasPlansWithTheirInstanceAndHidesCancelledOnes() {
        ProductionPlan live = pendingCanvasPlan("P-25", "INST-25");
        ProductionPlan cancelled = pendingCanvasPlan("P-26", "INST-26");
        cancelled.setStatus(ProductionPlanStatus.CANCELLED);
        ProductionPlan direct = pendingCanvasPlan("P-27", "INST-27");
        when(plans.findByFactoryIdAndIsForceInsertedAndRequiresApprovalAndApprovalStatus(
                F, true, true, ProductionPlan.ApprovalStatus.PENDING)).thenReturn(List.of(live, cancelled, direct));
        when(engine.getCurrentInstance(F, "FORCE_INSERT", "P-25"))
                .thenReturn(Optional.of(instance("INST-25", "P-25", InstanceStatus.RUNNING)));
        when(engine.getCurrentInstance(F, "FORCE_INSERT", "P-27")).thenReturn(Optional.empty());

        List<ProductionPlanDTO> pending = service.getPendingForceInsertApprovals(F);

        assertThat(pending).as("已取消的不在待审批里 (只剩 2 张)").hasSize(2);
        assertThat(pending.get(0).getApprovalWorkflowInstanceId()).isEqualTo("INST-25");
        assertThat(pending.get(1).getApprovalWorkflowInstanceId()).as("没有画布的计划不带实例").isNull();
    }
}

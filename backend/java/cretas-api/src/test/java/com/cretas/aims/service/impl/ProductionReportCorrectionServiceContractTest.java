package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionReportCorrection;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportCorrectionRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionReportCorrectionServiceContractTest {
    @Mock ProductionReportCorrectionRepository correctionRepository;
    @Mock ProductionReportRepository reportRepository;
    @Mock ProductionBatchRepository batchRepository;
    @Mock ProductionPlanRepository planRepository;
    @Mock WorkProcessTaskRepository taskRepository;
    @Mock UserRepository userRepository;
    @Mock WorkflowEngineService workflowEngine;
    @Mock WipInventoryService wipInventoryService;

    ProductionReportCorrectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionReportCorrectionServiceImpl(correctionRepository, reportRepository,
                batchRepository, planRepository, taskRepository, userRepository, workflowEngine,
                wipInventoryService);
        lenient().when(planRepository.findByIdForUpdate(any(String.class))).thenReturn(Optional.of(plan()));
    }

    @Test
    void pendingDraftKeepsOriginalReportAndStartsConfiguredWorkflow() {
        ProductionReport report = report();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan()));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L);
        task.setFactoryId("F006");
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        ApprovalWorkflowInstance instance = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn("WF-1");
        when(instance.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        when(workflowEngine.startWorkflowIfConfigured(eq("F006"), eq("PRODUCTION_REPORT_CORRECTION"),
                any(), anyMap(), eq(7L))).thenReturn(Optional.of(instance));

        var result = service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L);

        assertThat(result.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(result.getWorkflowInstanceId()).isEqualTo("WF-1");
        assertThat(java.util.UUID.fromString(result.getId()).toString()).isEqualTo(result.getId());
        var order = org.mockito.Mockito.inOrder(wipInventoryService, correctionRepository, workflowEngine);
        order.verify(wipInventoryService).validateApprovedOutputQuantityCorrection(
                eq("F006"), eq(report), eq(task), eq(new BigDecimal("10")),
                eq(new BigDecimal("9")), eq(result.getId()));
        order.verify(correctionRepository).save(any());
        order.verify(workflowEngine).startWorkflowIfConfigured(
                eq("F006"), eq("PRODUCTION_REPORT_CORRECTION"), eq(result.getId()), anyMap(), eq(7L));
        assertThat(report.getOutputQuantity()).isEqualByComparingTo("10");
        assertThat(report.getNotes()).isEqualTo("原备注");
        verify(reportRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void consumedOutputIsRejectedBeforePendingCorrectionOrWorkflowWrite() {
        ProductionReport report = report();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006"))
                .thenReturn(Optional.of(report));
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L);
        task.setFactoryId("F006");
        // The old submit never reaches these dependencies; explicit verification below protects wiring.
        lenient().when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        lenient().doThrow(new BusinessException(409, "该 WIP 已被下游消费，数量更正会破坏库存账")
                        .withCode("WIP_CORRECTION_DOWNSTREAM_CONSUMED"))
                .when(wipInventoryService).validateApprovedOutputQuantityCorrection(
                        eq("F006"), eq(report), eq(task), eq(new BigDecimal("10")),
                        eq(new BigDecimal("9")), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 2, 60, "原备注", "核对数量"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下游消费");

        verify(wipInventoryService).validateApprovedOutputQuantityCorrection(
                eq("F006"), eq(report), eq(task), eq(new BigDecimal("10")),
                eq(new BigDecimal("9")), org.mockito.ArgumentMatchers.argThat(id -> {
                    if (id == null) return false;
                    try { return java.util.UUID.fromString(id).toString().equals(id); }
                    catch (IllegalArgumentException invalid) { return false; }
                }));
        verify(correctionRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflowIfConfigured(any(), any(), any(), anyMap(), any());
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(wipInventoryService, never()).correctApprovedOutputQuantity(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unsupportedReportPrecisionIsRejectedBeforeCorrectionOrWorkflowWrite() {
        ProductionReport report = report();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan()));

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9.001"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("两位小数");
        verify(correctionRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflowIfConfigured(any(), any(), any(), anyMap(), any());
    }

    @Test
    void inputReportCannotBeSubmittedForOutputCorrection() {
        ProductionReport report = report();
        report.setReportKind("INPUT");
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("投入或过程报工不能作为产出修正");
        verify(correctionRepository, never()).save(any());
    }

    @Test
    void segmentAndUnknownReportsFailBeforeSavingOrStartingWorkflow() {
        for (String kind : List.of("SEGMENT", "UNRECOGNIZED")) {
            ProductionReport report = report();
            report.setReportKind(kind);
            when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));

            assertThatThrownBy(() -> service.submit("F006", 10L,
                    new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                            BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                    .isInstanceOf(BusinessException.class);
            verify(correctionRepository, never()).save(any());
            verify(workflowEngine, never()).startWorkflowIfConfigured(any(), any(), any(), anyMap(), any());
            org.mockito.Mockito.clearInvocations(reportRepository, correctionRepository, workflowEngine);
        }
    }

    @Test
    void staleBeforePlanLockRejectsCompletedPlanWithoutCreatingCorrection() {
        ProductionReport report = report();
        ProductionPlan completed = plan();
        completed.setStatus(ProductionPlanStatus.COMPLETED);
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("生产计划已进入终态");
        verify(correctionRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflowIfConfigured(any(), any(), any(), anyMap(), any());
    }

    @Test
    void lockedPlanRejectsSubmitBeforeCorrectionOrWorkflowWriteEvenWithNullStatus() {
        ProductionReport report = report();
        ProductionPlan locked = plan();
        locked.setStatus(null);
        locked.setIsLocked(true);
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_REPORT_CORRECTION_PLAN_LOCKED");
                })
                .hasMessageContaining("生产计划已锁定");
        verify(wipInventoryService, never()).validateApprovedOutputQuantityCorrection(
                any(), any(), any(), any(), any(), any());
        verify(correctionRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflowIfConfigured(any(), any(), any(), anyMap(), any());
    }

    @Test
    void approvalRejectsPlanLockedAfterSubmitBeforeApplyingCorrection() {
        ProductionReport report = report();
        ProductionPlan plan = plan();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance instance = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn("WF-1");
        when(instance.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        when(workflowEngine.startWorkflowIfConfigured(eq("F006"), eq("PRODUCTION_REPORT_CORRECTION"),
                any(), anyMap(), eq(7L))).thenReturn(Optional.of(instance));

        var submitted = service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L);
        ProductionReportCorrection pending = correction();
        pending.setId(submitted.getId());
        pending.setWorkflowInstanceId(submitted.getWorkflowInstanceId());
        plan.setIsLocked(true);
        when(correctionRepository.findByIdAndFactoryId(submitted.getId(), "F006"))
                .thenReturn(Optional.of(pending));
        org.mockito.Mockito.clearInvocations(correctionRepository, reportRepository, batchRepository,
                planRepository, taskRepository, workflowEngine, wipInventoryService);

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", submitted.getId(), "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_REPORT_CORRECTION_PLAN_LOCKED");
                })
                .hasMessageContaining("生产计划已锁定");
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(wipInventoryService, never()).correctApprovedOutputQuantity(
                any(), any(), any(), any(), any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(correctionRepository, never()).save(any());
    }

    @Test
    void approvalRejectsReportSettledAfterSubmitBeforeWorkflowTransition() {
        ProductionReport report = report();
        ProductionPlan plan = plan();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F006", 10L, ProductionReportCorrection.Status.PENDING_APPROVAL)).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance instance = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn("WF-1");
        when(instance.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        when(workflowEngine.startWorkflowIfConfigured(eq("F006"), eq("PRODUCTION_REPORT_CORRECTION"),
                any(), anyMap(), eq(7L))).thenReturn(Optional.of(instance));

        var submitted = service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L);
        ProductionReportCorrection pending = correction();
        pending.setId(submitted.getId());
        pending.setWorkflowInstanceId(submitted.getWorkflowInstanceId());
        report.setSettled(true);
        when(correctionRepository.findByIdAndFactoryId(submitted.getId(), "F006"))
                .thenReturn(Optional.of(pending));
        org.mockito.Mockito.clearInvocations(correctionRepository, reportRepository, batchRepository,
                planRepository, taskRepository, workflowEngine, wipInventoryService);

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", submitted.getId(), "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_REPORT_CORRECTION_SETTLEMENT_RACE");
                })
                .hasMessageContaining("已结单");
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(wipInventoryService, never()).validateApprovedOutputQuantityCorrection(
                any(), any(), any(), any(), any(), any());
        verify(wipInventoryService, never()).correctApprovedOutputQuantity(
                any(), any(), any(), any(), any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(correctionRepository, never()).save(any());
    }

    @Test
    void correctableListingKeepsPageRowsAndMarksInputWithoutHidingLaterOutput() {
        ProductionReport input = report();
        input.setReportKind("INPUT");
        ProductionReport output = report();
        output.setId(11L);
        output.setReportKind("OUTPUT");
        when(reportRepository.findByFactoryIdAndSettledFalseAndStatusInAndDeletedAtIsNull(
                eq("F006"), any(), any())).thenReturn(new PageImpl<>(List.of(input, output),
                PageRequest.of(0, 2), 2));
        when(correctionRepository.findByFactoryIdAndProductionReportIdInOrderBySubmittedAtDesc(
                eq("F006"), any())).thenReturn(List.of());

        var result = service.listCorrectable("F006", PageRequest.of(0, 2));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0)).containsEntry("correctionEligible", false)
                .containsEntry("reportKind", "INPUT");
        assertThat(result.getContent().get(1)).containsEntry("correctionEligible", true)
                .containsEntry("reportKind", "OUTPUT");
    }

    @Test
    void approvalSummaryReadsMetadataWithoutRowLockAndKeepsFactoryBoundary() {
        ProductionReportCorrection correction = correction();
        ProductionReport sameFactory = report();
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findById(10L)).thenReturn(Optional.of(sameFactory));

        var summary = service.approvalSummary("F006", "COR-1");

        assertThat(summary).containsEntry("reportKind", null).containsEntry("outputUnit", null);
        verify(reportRepository).findById(10L);
        verify(reportRepository, never()).findByIdAndFactoryIdForUpdate(10L, "F006");

        ProductionReport foreign = report();
        foreign.setFactoryId("OTHER");
        when(reportRepository.findById(10L)).thenReturn(Optional.of(foreign));
        assertThat(service.approvalSummary("F006", "COR-1")).doesNotContainKeys("reportKind", "outputUnit");
    }

    @Test
    void missingWorkflowFailsClosedWithoutChangingEffectiveReport() {
        ProductionReport report = report();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan()));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowEngine.startWorkflowIfConfigured(any(), any(), any(), anyMap(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先配置报工修正审批流程");
        assertThat(report.getOutputQuantity()).isEqualByComparingTo("10");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void workflowWithoutHumanApprovalNodeFailsClosed() {
        ProductionReport report = report();
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(batchRepository.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch()));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan()));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance autoApproved = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(autoApproved.getId()).thenReturn("WF-AUTO");
        when(autoApproved.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.startWorkflowIfConfigured(any(), any(), any(), anyMap(), any()))
                .thenReturn(Optional.of(autoApproved));

        assertThatThrownBy(() -> service.submit("F006", 10L,
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置可审批节点");
        assertThat(report.getOutputQuantity()).isEqualByComparingTo("10");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void terminalApprovalAtomicallyAppliesAllowedFieldsWithoutTaskRollback() {
        ProductionReport report = report();
        ProductionReportCorrection correction = correction();
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L);
        task.setFactoryId("F006");
        task.setStatus(WorkProcessTask.Status.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        ProductionReport earlierPartial = report();
        earlierPartial.setId(9L);
        earlierPartial.setOutputQuantity(new BigDecimal("4"));
        earlierPartial.setTotalWorkMinutes(10);
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 30L))
                .thenReturn(List.of(earlierPartial, report));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.APPROVE, "同意"))
                .thenReturn(updated);

        var result = service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意");

        assertThat(result.getStatus()).isEqualTo("APPROVED_APPLIED");
        assertThat(report.getOutputQuantity()).isEqualByComparingTo("9");
        assertThat(report.getDataCorrected()).isTrue();
        assertThat(report.getLastCorrectionId()).isEqualTo("COR-1");
        assertThat(task.getActualQuantity()).isEqualByComparingTo("13");
        assertThat(task.getActualMinutes()).isEqualTo(100);
        assertThat(task.getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
    }

    @Test
    void quantityOnlyCorrectionPreservesUnknownTaskMinutes() {
        ProductionReport report = report();
        report.setTotalWorkMinutes(null);
        ProductionReportCorrection correction = correction();
        correction.setOriginalTotalWorkMinutes(null);
        correction.setProposedTotalWorkMinutes(null);
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L);
        task.setFactoryId("F006");
        task.setStatus(WorkProcessTask.Status.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 30L))
                .thenReturn(List.of(report));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.APPROVE, "同意"))
                .thenReturn(updated);

        service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意");

        assertThat(task.getActualMinutes()).as("unknown report minutes remain unknown").isNull();
        verify(taskRepository).save(task);
    }

    @Test
    void pendingApprovalCannotApplyAfterPlanBecomesCompleted() {
        ProductionReport report = report();
        ProductionReportCorrection correction = correction();
        ProductionPlan completed = plan();
        completed.setStatus(ProductionPlanStatus.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(planRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("生产计划已进入终态");
        assertThat(report.getOutputQuantity()).isEqualByComparingTo("10");
        assertThat(report.getDataCorrected()).isFalse();
        assertThat(correction.getStatus()).isEqualTo(ProductionReportCorrection.Status.PENDING_APPROVAL);
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(correctionRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void approvalRejectsInputOrUnknownReportBeforeTransition() {
        for (String kind : List.of("INPUT", "UNRECOGNIZED")) {
            ProductionReport report = report();
            report.setReportKind(kind);
            ProductionReportCorrection correction = correction();
            when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
            when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));

            assertThatThrownBy(() -> service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                    "configured_role", HistoryAction.APPROVE, "同意"))
                    .isInstanceOf(BusinessException.class);
            verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
            org.mockito.Mockito.clearInvocations(reportRepository, correctionRepository, workflowEngine);
        }
    }

    @Test
    void taskAggregationIgnoresInputReports() {
        ProductionReport report = report();
        ProductionReportCorrection correction = correction();
        ProductionReport input = report();
        input.setId(11L);
        input.setReportKind("INPUT");
        input.setOutputQuantity(new BigDecimal("100"));
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L); task.setFactoryId("F006"); task.setStatus(WorkProcessTask.Status.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 30L))
                .thenReturn(List.of(input, report));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.APPROVE, "同意"))
                .thenReturn(updated);

        service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意");

        assertThat(task.getActualQuantity()).isEqualByComparingTo("9");
    }

    @Test
    void taskAggregationRejectsMixedTrustedOutputUnits() {
        ProductionReport report = report();
        report.setOutputUnit("kg");
        ProductionReportCorrection correction = correction();
        ProductionReport earlier = report();
        earlier.setId(9L); earlier.setOutputQuantity(new BigDecimal("4")); earlier.setOutputUnit("box");
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L); task.setFactoryId("F006"); task.setStatus(WorkProcessTask.Status.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 30L))
                .thenReturn(List.of(earlier, report));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.APPROVE, "同意"))
                .thenReturn(updated);

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("产出单位不一致");
        assertThat(task.getActualQuantity()).isNull();
    }

    @Test
    void taskAggregationRejectsUnknownNonzeroOutputBeforeTaskSave() {
        ProductionReport report = report();
        ProductionReportCorrection correction = correction();
        ProductionReport unknown = report();
        unknown.setId(9L);
        unknown.setReportKind("UNRECOGNIZED");
        unknown.setOutputQuantity(new BigDecimal("4"));
        WorkProcessTask task = new WorkProcessTask();
        task.setId(30L); task.setFactoryId("F006"); task.setStatus(WorkProcessTask.Status.COMPLETED);
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(reportRepository.findByIdAndFactoryIdForUpdate(10L, "F006")).thenReturn(Optional.of(report));
        when(taskRepository.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task));
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 30L))
                .thenReturn(List.of(unknown, report));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.APPROVE, "同意"))
                .thenReturn(updated);

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报工阶段无法确认");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void rejectionKeepsOriginalValues() {
        ProductionReportCorrection correction = correction();
        when(correctionRepository.findByIdAndFactoryId("COR-1", "F006")).thenReturn(Optional.of(correction));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ApprovalWorkflowInstance updated = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(updated.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.REJECTED);
        when(workflowEngine.transitionNode("WF-1", 99L, "configured_role", HistoryAction.REJECT, "证据不足"))
                .thenReturn(updated);

        var result = service.applyWorkflowAction("F006", "COR-1", "WF-1", 99L,
                "configured_role", HistoryAction.REJECT, "证据不足");

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(reportRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void pendingCorrectionBlocksSettlementWithProcessName() {
        ProductionReportCorrection pending = correction();
        pending.setProcessCategory("包装");
        when(correctionRepository.findByFactoryIdAndProductionPlanIdAndStatus(
                "F006", "PLAN-1", ProductionReportCorrection.Status.PENDING_APPROVAL))
                .thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.assertNoPendingForPlan("F006", "PLAN-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("包装");
    }

    private ProductionReport report() {
        return ProductionReport.builder().id(10L).factoryId("F006").batchId(20L).workProcessTaskId(30L)
                .workerId(1L).reportType("PROGRESS").reportDate(java.time.LocalDate.now())
                .status(ProductionReport.Status.SUBMITTED).settled(false).processCategory("包装")
                .productTypeId("SKU-1").outputQuantity(new BigDecimal("10"))
                .goodQuantity(new BigDecimal("9")).defectQuantity(BigDecimal.ONE)
                .totalWorkers(2).totalWorkMinutes(60).notes("原备注").build();
    }

    private ProductionBatch batch() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(20L); batch.setFactoryId("F006"); batch.setProductionPlanId("PLAN-1");
        return batch;
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1"); plan.setFactoryId("F006");
        return plan;
    }

    private ProductionReportCorrection correction() {
        return ProductionReportCorrection.builder().id("COR-1").factoryId("F006")
                .productionReportId(10L).workProcessTaskId(30L).productionBatchId(20L)
                .productionPlanId("PLAN-1").productTypeId("SKU-1").processCategory("包装")
                .status(ProductionReportCorrection.Status.PENDING_APPROVAL)
                .originalOutputQuantity(new BigDecimal("10")).proposedOutputQuantity(new BigDecimal("9"))
                .originalGoodQuantity(new BigDecimal("9")).proposedGoodQuantity(new BigDecimal("8"))
                .originalDefectQuantity(BigDecimal.ONE).proposedDefectQuantity(BigDecimal.ONE)
                .originalTotalWorkers(2).proposedTotalWorkers(3)
                .originalTotalWorkMinutes(60).proposedTotalWorkMinutes(90)
                .originalNotes("原备注").proposedNotes("修正备注")
                .correctionReason("现场复核").submittedBy(7L).workflowInstanceId("WF-1").build();
    }
}

package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.quality.DispositionApproveRequest;
import com.cretas.aims.dto.quality.DispositionApplyRequest;
import com.cretas.aims.dto.quality.ExecuteDispositionRequest;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.StateMachineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityDispositionControllerSecurityContractTest {

    @InjectMocks
    private QualityDispositionController controller;
    @Mock
    private com.cretas.aims.service.quality.QualityDispositionDecisionGuard decisionGuard;

    @org.junit.jupiter.api.BeforeEach
    void lockedInspectionIsRefreshedByGuard() {
        lenient().when(decisionGuard.lockInspection(anyString(), any())).thenAnswer(call -> call.getArgument(1));
    }

    @Mock
    private QualityDispositionRuleService qualityDispositionRuleService;
    @Mock
    private QualityInspectionRepository qualityInspectionRepository;
    @Mock
    private DecisionAuditLogRepository decisionAuditLogRepository;
    @Mock
    private StateMachineService stateMachineService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PushNotificationService pushNotificationService;

    @Test
    void executeRejectsClientSuppliedExecutorThatDiffersFromJwtUser() {
        ExecuteDispositionRequest request = ExecuteDispositionRequest.builder()
                .batchId(100L)
                .inspectionId("qi-1")
                .actionCode("REWORK")
                .executorId(1559L)
                .build();

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.executeDisposition("F006", request, 1558L));

        assertEquals(400, error.getCode());
        assertEquals("处置执行人必须与当前登录用户一致", error.getMessage());
        verifyNoInteractions(qualityInspectionRepository, qualityDispositionRuleService);
    }

    @Test
    void approveEndpointRequiresManagerRole() throws Exception {
        Method method = QualityDispositionController.class.getMethod(
                "approveDisposition", String.class, String.class,
                DispositionApproveRequest.class, Long.class);

        RequireRole roleGate = method.getAnnotation(RequireRole.class);
        assertNotNull(roleGate);
        assertTrue(Arrays.asList(roleGate.value()).contains("quality_manager"));
        assertFalse(Arrays.asList(roleGate.value()).contains("quality_inspector"));
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void approvalUsesTrustedApproverAndExecutesApprovedPathExactlyOnce() {
        DecisionAuditLog pending = DecisionAuditLog.builder()
                .id("approval-1")
                .factoryId("F006")
                .entityType("QualityInspection")
                .entityId("qi-1")
                .decisionMade("REWORK")
                .executorId(1558L)
                .requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING)
                .build();
        QualityInspection inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId("F006");
        inspection.setProductionBatchId(100L);
        DispositionApproveRequest request = new DispositionApproveRequest();
        request.setApproved(true);
        request.setApproverId(1559L);
        request.setApproverName("质量经理");
        request.setComment("同意返工");

        when(decisionAuditLogRepository.findById("approval-1")).thenReturn(Optional.of(pending));
        when(decisionAuditLogRepository.save(any(DecisionAuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(qualityInspectionRepository.findById("qi-1")).thenReturn(Optional.of(inspection));
        when(qualityDispositionRuleService.executeApprovedDisposition(
                eq("F006"), same(inspection), eq(QualityDispositionRuleService.DispositionAction.REWORK),
                eq(1559L), anyString()))
                .thenReturn(QualityDispositionRuleService.DispositionExecutionResult.builder()
                        .success(true)
                        .executedAction(QualityDispositionRuleService.DispositionAction.REWORK)
                        .newBatchStatus("REWORK_REQUIRED")
                        .auditLogId("execution-1")
                        .build());

        var response = controller.approveDisposition("F006", "approval-1", request, 1559L);

        assertTrue(response.getData().getApproved());
        assertEquals(1559L, response.getData().getApproverId());
        assertEquals("EXECUTED", response.getData().getExecutionStatus());
        assertEquals("REWORK_REQUIRED", response.getData().getNewBatchStatus());
        verify(qualityDispositionRuleService, times(1)).executeApprovedDisposition(
                eq("F006"), same(inspection), eq(QualityDispositionRuleService.DispositionAction.REWORK),
                eq(1559L), anyString());
        verify(qualityDispositionRuleService, never()).executeDisposition(anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void approvalRejectsAlreadyProcessedRequestBeforeExecution() {
        DecisionAuditLog processed = DecisionAuditLog.builder()
                .id("approval-1")
                .factoryId("F006")
                .requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.APPROVED)
                .build();
        DispositionApproveRequest request = new DispositionApproveRequest();
        request.setApproved(true);
        request.setApproverId(1559L);
        when(decisionAuditLogRepository.findById("approval-1")).thenReturn(Optional.of(processed));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.approveDisposition("F006", "approval-1", request, 1559L));

        assertEquals(409, error.getCode());
        verify(qualityDispositionRuleService, never())
                .executeApprovedDisposition(anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void approvedRequestFailsLoudlyWhenInspectionNoLongerExists() {
        DecisionAuditLog pending = DecisionAuditLog.builder()
                .id("approval-1")
                .factoryId("F006")
                .entityType("QualityInspection")
                .entityId("missing-inspection")
                .decisionMade("REWORK")
                .executorId(1558L)
                .requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING)
                .build();
        DispositionApproveRequest request = new DispositionApproveRequest();
        request.setApproved(true);
        request.setApproverId(1559L);

        when(decisionAuditLogRepository.findById("approval-1")).thenReturn(Optional.of(pending));
        when(qualityInspectionRepository.findById("missing-inspection")).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.approveDisposition("F006", "approval-1", request, 1559L));
        assertEquals(500, error.getCode());
        assertEquals("审批执行失败，处置申请未生效", error.getMessage());
        verify(decisionAuditLogRepository, never()).save(any());
        verify(qualityDispositionRuleService, never())
                .executeApprovedDisposition(anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void applyWritesPendingRecordIntoQualityInspectionHistoryUsingTrustedApplicant() {
        QualityInspection inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId("F006");
        inspection.setProductionBatchId(100L);
        DispositionApplyRequest request = new DispositionApplyRequest();
        request.setInspectionId("qi-1");
        request.setBatchId(100L);
        request.setActionCode("REWORK");
        request.setReason("返工确认");
        request.setApplicantId(1558L);
        request.setApplicantName("质检员");

        when(qualityInspectionRepository.findById("qi-1")).thenReturn(Optional.of(inspection));
        when(decisionAuditLogRepository.save(any(DecisionAuditLog.class)))
                .thenAnswer(invocation -> {
                    DecisionAuditLog saved = invocation.getArgument(0);
                    saved.setId("approval-1");
                    return saved;
                });

        var response = controller.applyDisposition("F006", request, 1558L);

        ArgumentCaptor<DecisionAuditLog> logCaptor = ArgumentCaptor.forClass(DecisionAuditLog.class);
        verify(decisionAuditLogRepository).save(logCaptor.capture());
        assertEquals("QualityInspection", logCaptor.getValue().getEntityType());
        assertEquals(1558L, logCaptor.getValue().getExecutorId());
        assertEquals("approval-1", response.getData().getApplicationId());
        assertEquals("PENDING_APPROVAL", response.getData().getStatus());
    }

    @Test
    void applyRejectsUnknownActionBeforeCreatingApprovalRecord() {
        QualityInspection inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId("F006");
        inspection.setProductionBatchId(100L);
        DispositionApplyRequest request = new DispositionApplyRequest();
        request.setInspectionId("qi-1");
        request.setBatchId(100L);
        request.setActionCode("UNSUPPORTED_ACTION");
        request.setReason("非法动作");
        request.setApplicantId(1558L);
        when(qualityInspectionRepository.findById("qi-1")).thenReturn(Optional.of(inspection));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.applyDisposition("F006", request, 1558L));

        assertEquals(400, error.getCode());
        assertEquals("无效的处置动作: UNSUPPORTED_ACTION", error.getMessage());
        verify(decisionAuditLogRepository, never()).save(any());
    }

    @Test
    void approvalRejectsSelfApprovalBeforeChangingPendingRecord() {
        DecisionAuditLog pending = DecisionAuditLog.builder()
                .id("approval-1")
                .factoryId("F006")
                .entityType("QualityInspection")
                .entityId("qi-1")
                .decisionMade("REWORK")
                .executorId(1558L)
                .requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING)
                .build();
        DispositionApproveRequest request = new DispositionApproveRequest();
        request.setApproved(true);
        request.setApproverId(1558L);
        when(decisionAuditLogRepository.findById("approval-1")).thenReturn(Optional.of(pending));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.approveDisposition("F006", "approval-1", request, 1558L));

        assertEquals(403, error.getCode());
        assertEquals("申请人与审批人不能为同一用户", error.getMessage());
        verify(decisionAuditLogRepository, never()).save(any());
    }

    @Test
    void applyRejectsReleaseBecauseItDoesNotRequireApproval() {
        QualityInspection inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId("F006");
        inspection.setProductionBatchId(100L);
        DispositionApplyRequest request = new DispositionApplyRequest();
        request.setInspectionId("qi-1");
        request.setBatchId(100L);
        request.setActionCode("RELEASE");
        request.setReason("直接放行");
        request.setApplicantId(1558L);
        when(qualityInspectionRepository.findById("qi-1")).thenReturn(Optional.of(inspection));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.applyDisposition("F006", request, 1558L));

        assertEquals(400, error.getCode());
        assertEquals("该处置动作无需审批，请直接执行", error.getMessage());
        verify(decisionAuditLogRepository, never()).save(any());
    }
}

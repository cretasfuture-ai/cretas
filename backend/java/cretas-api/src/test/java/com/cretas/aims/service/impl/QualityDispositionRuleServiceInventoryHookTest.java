package com.cretas.aims.service.impl;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the 质检处置→库存 bridge is invoked at the correct point inside
 * {@link QualityDispositionRuleServiceImpl#executeDisposition}:
 * <ul>
 *   <li>Direct execution (no special approval) → {@code applyDisposition} IS called (flip happens).</li>
 *   <li>Approval-initiated branch (needs special approval) → {@code applyDisposition} is NOT called
 *       (an un-approved RELEASE must not un-quarantine).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QualityDispositionRuleServiceInventoryHookTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private QualityDispositionRuleServiceImpl service;

    @Mock
    private ApprovalChainService approvalChainService;
    @Mock
    private DecisionAuditService decisionAuditService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QualityDispositionInventoryService dispositionInventoryService;
    @Mock
    private com.cretas.aims.service.quality.QualityDispositionDecisionGuard decisionGuard;

    private QualityInspection inspection;

    @BeforeEach
    void setUp() {
        // dispositionInventoryService is an @Autowired(required=false) field; @InjectMocks uses the
        // @RequiredArgsConstructor (final fields only) and does NOT field-inject it → wire manually.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "dispositionInventoryService", dispositionInventoryService);

        inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId(FACTORY_ID);
        inspection.setProductionBatchId(100L);
        inspection.setResult("PASS");
        inspection.setPassRate(new java.math.BigDecimal("100"));
        lenient().when(decisionGuard.lockInspection(anyString(), any())).thenAnswer(call -> call.getArgument(1));

        User executor = mock(User.class);
        lenient().when(executor.getFullName()).thenReturn("张质检");
        lenient().when(executor.getRole()).thenReturn("quality_manager");
        lenient().when(userRepository.findById(9L)).thenReturn(Optional.of(executor));
    }

    @Test
    @DisplayName("HOOK-01: 直接执行放行 (无特批) → 桥接 applyDisposition 被调用")
    void directReleaseInvokesBridge() {
        // requiresSpecialApproval → false (走直接执行分支)
        when(approvalChainService.requiresApproval(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(false);
        when(decisionAuditService.logRuleExecution(anyString(), anyString(), anyString(), anyMap(), anyMap(),
                anyList(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(DecisionAuditLog.builder().id("al-1").build());

        service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "质检合格放行");

        verify(dispositionInventoryService, times(1)).applyDisposition(inspection, DispositionAction.RELEASE);
    }

    @Test
    @DisplayName("HOOK-02: 需特批 (approval-initiated) → 桥接不被调用 (未批不释放隔离)")
    void approvalInitiatedDoesNotInvokeBridge() {
        // requiresSpecialApproval → true (走 approval-initiated 分支, 只记审计, 不执行库存翻转)
        when(approvalChainService.requiresApproval(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(true);
        when(approvalChainService.findMatchingConfig(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(Optional.empty());
        when(decisionAuditService.logPendingApproval(anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(DecisionAuditLog.builder().id("al-2").build());

        service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "低合格率放行申请");

        verify(dispositionInventoryService, never()).applyDisposition(any(), any());
    }

    @Test
    @DisplayName("HOOK-03: 返工静态要求审批，即使工厂未配置异常审批也不能直接执行")
    void reworkAlwaysInitiatesApproval() {
        when(approvalChainService.findMatchingConfig(eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap()))
                .thenReturn(Optional.empty());
        when(decisionAuditService.logPendingApproval(anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(DecisionAuditLog.builder().id("al-rework-pending").build());

        var result = service.executeDisposition(
                FACTORY_ID, inspection, DispositionAction.REWORK, 9L, "申请返工");

        assertTrue(result.isApprovalInitiated());
        assertEquals("al-rework-pending", result.getApprovalRequestId());
        assertEquals("PENDING_APPROVAL", result.getNewBatchStatus());
        verify(decisionAuditService).logPendingApproval(
                eq(FACTORY_ID), eq("QualityInspection"), eq(inspection.getId()), anyMap(),
                eq("REWORK"), eq("申请返工"), eq(9L), anyString(), anyString(), any(), any(), any());
        verify(dispositionInventoryService, never()).applyDisposition(any(), any());
        verify(approvalChainService, never()).requiresApproval(
                eq(FACTORY_ID), eq(DecisionType.QUALITY_EXCEPTION), anyMap());
    }

    @Test
    @DisplayName("HOOK-04: 审批通过后的返工只执行一次库存桥接，不再次进入审批")
    void approvedReworkExecutesBridgeOnce() {
        when(decisionAuditService.logRuleExecution(anyString(), anyString(), anyString(), anyMap(), anyMap(),
                anyList(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(DecisionAuditLog.builder().id("al-rework-approved").build());

        var result = service.executeApprovedDisposition(
                FACTORY_ID, inspection, DispositionAction.REWORK, 9L, "返工审批通过");

        assertFalse(result.isApprovalInitiated());
        assertEquals("REWORK_REQUIRED", result.getNewBatchStatus());
        verify(dispositionInventoryService, times(1)).applyDisposition(inspection, DispositionAction.REWORK);
        verifyNoInteractions(approvalChainService);
    }

    @Test
    @DisplayName("HOOK-05: 动作审批真值由枚举单一来源提供")
    void actionApprovalPolicyIsSingleSource() {
        assertFalse(DispositionAction.RELEASE.requiresApproval());
        assertNull(DispositionAction.RELEASE.getApprovalLevel());
        assertTrue(DispositionAction.REWORK.requiresApproval());
        assertEquals("SUPERVISOR", DispositionAction.REWORK.getApprovalLevel());
        assertTrue(DispositionAction.SCRAP.requiresApproval());
        assertEquals("QUALITY_HEAD", DispositionAction.SCRAP.getApprovalLevel());
    }

    @Test
    void failedInspectionCannotUseDirectReleaseWithoutFactoryApprovalConfiguration() {
        inspection.setResult("FAIL");
        inspection.setPassRate(new java.math.BigDecimal("40"));
        var error = assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "不能绕过复核"));
        assertEquals(409, error.getCode());
        verifyNoInteractions(approvalChainService, decisionAuditService, dispositionInventoryService);
    }

    @Test
    void passLabelWithoutQualifyingSampleRateCannotDirectRelease() {
        inspection.setPassRate(new java.math.BigDecimal("40"));
        assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.executeDisposition(FACTORY_ID, inspection, DispositionAction.RELEASE, 9L, "不接受矛盾的标签"));
        verifyNoInteractions(approvalChainService, decisionAuditService, dispositionInventoryService);
    }
}

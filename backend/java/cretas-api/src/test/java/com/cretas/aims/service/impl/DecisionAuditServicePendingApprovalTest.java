package com.cretas.aims.service.impl;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionAuditServicePendingApprovalTest {

    @Mock
    private DecisionAuditLogRepository auditLogRepository;

    @Test
    void pendingApprovalPersistsExecutableActionInsteadOfGenericForceInsertLabel() {
        DecisionAuditServiceImpl service = new DecisionAuditServiceImpl(
                auditLogRepository, new ObjectMapper());
        when(auditLogRepository.save(any(DecisionAuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.logPendingApproval(
                "F006", "QualityInspection", "qi-1", Map.of("requestedAction", "REWORK"),
                "REWORK", "申请返工", 1558L, "质检员", "quality_inspector",
                null, null, null);

        ArgumentCaptor<DecisionAuditLog> captor = ArgumentCaptor.forClass(DecisionAuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        DecisionAuditLog saved = captor.getValue();
        assertEquals(DecisionAuditLog.DecisionType.APPROVAL, saved.getDecisionType());
        assertEquals("REWORK", saved.getDecisionMade());
        assertEquals(DecisionAuditLog.ApprovalStatus.PENDING, saved.getApprovalStatus());
        assertEquals("PENDING_APPROVAL", saved.getNewState());
        assertTrue(saved.getInputContext().contains("REWORK"));
    }
}

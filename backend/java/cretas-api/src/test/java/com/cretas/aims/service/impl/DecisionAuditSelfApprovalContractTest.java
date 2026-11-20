package com.cretas.aims.service.impl;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 红线 4: 本人审批的决策日志不能没有原因 —— 接线错了就报错, 不写一条无原因的本人审批。 */
class DecisionAuditSelfApprovalContractTest {

    @Test
    void selfApprovalWithoutReasonIsRefusedAndNothingIsWritten() {
        DecisionAuditLogRepository repo = mock(DecisionAuditLogRepository.class);
        DecisionAuditServiceImpl service = new DecisionAuditServiceImpl(repo, new ObjectMapper());

        for (String blank : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> service.logApproval("F006", "ProductionPlan", "QA-P",
                    DecisionAuditLog.ApprovalStatus.APPROVED, "x", 1L, "QA", true, blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(repo, never()).save(any());
    }
}

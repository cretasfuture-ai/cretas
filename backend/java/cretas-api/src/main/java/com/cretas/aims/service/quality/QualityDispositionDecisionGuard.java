package com.cretas.aims.service.quality;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One decision mutex per production batch; joins the caller's audit/inventory transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class QualityDispositionDecisionGuard {
    private final ProductionBatchRepository batches;
    private final QualityInspectionRepository inspections;
    private final DecisionAuditLogRepository audits;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private static final Set<String> TERMINAL = Set.of("RELEASE", "CONDITIONAL_RELEASE", "REWORK", "SCRAP");

    public QualityInspection lockInspection(String factoryId, QualityInspection candidate) {
        if (!Objects.equals(factoryId, candidate.getFactoryId())) {
            throw new BusinessException(403, "质检记录不属于当前工厂");
        }
        // Reuse the existing tenant-scoped production row lock. Different inspections or
        // applications for the same batch must share this mutex, not only their audit row.
        Long batchId = candidate.getProductionBatchId();
        if (batchId != null) {
            batches.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> new EntityNotFoundException("ProductionBatch", batchId.toString()));
        }
        QualityInspection current = entityManager.find(QualityInspection.class, candidate.getId());
        if (current == null) throw new EntityNotFoundException("QualityInspection", candidate.getId());
        // Controllers may have read this entity before waiting for the batch mutex.
        // Refresh is essential: a lock query alone can return a stale first-level entity.
        if (current.getProductionBatchId() == null) entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        else entityManager.refresh(current);
        if (!Objects.equals(factoryId, current.getFactoryId())
                || !Objects.equals(batchId, current.getProductionBatchId())) {
            throw new BusinessException(409, "质检记录已变更，请重新查看后操作");
        }
        return current;
    }

    public void refreshApproval(DecisionAuditLog application) {
        entityManager.refresh(application, LockModeType.PESSIMISTIC_WRITE);
    }

    private List<QualityInspection> batchInspections(QualityInspection current) {
        return current.getProductionBatchId() == null ? List.of(current)
                : inspections.findByFactoryIdAndProductionBatchId(current.getFactoryId(),
                        current.getProductionBatchId(), Pageable.unpaged()).getContent();
    }

    public void assertCanStart(QualityInspection current) {
        assertLatestInspection(current);
        for (QualityInspection inspection : batchInspections(current)) {
            for (DecisionAuditLog audit : audits.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("QualityInspection", inspection.getId())) {
                if (!Objects.equals(current.getFactoryId(), audit.getFactoryId())) continue;
                if (Boolean.TRUE.equals(audit.getRequiresApproval())
                        && audit.getApprovalStatus() == DecisionAuditLog.ApprovalStatus.PENDING) {
                    throw new BusinessException(409, "本批次已有待审批处置，请先处理原申请");
                }
                if (inspection.getId().equals(current.getId()) && completedTerminal(audit)) {
                    throw new BusinessException(409, "本次质检已有最终处置；如需重新处理，请先登记新的复检记录");
                }
            }
        }
    }

    public void assertApprovalCurrent(QualityInspection current) {
        assertLatestInspection(current);
        // Also protect duplicate applications that already existed before this guard.
        // The caller has not approved its own row yet, so only an earlier winner is terminal.
        for (DecisionAuditLog audit : audits.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("QualityInspection", current.getId())) {
            if (Objects.equals(current.getFactoryId(), audit.getFactoryId()) && completedTerminal(audit)) {
                throw new BusinessException(409, "本次质检已有最终处置，不能再批准冲突申请");
            }
        }
    }

    private void assertLatestInspection(QualityInspection current) {
        for (QualityInspection other : batchInspections(current)) {
            if (other.getCreatedAt() != null && current.getCreatedAt() != null
                    && other.getCreatedAt().isAfter(current.getCreatedAt())) {
                throw new BusinessException(409, "本批次已有更新的质检记录，请重新复核后申请处置");
            }
        }
    }

    private boolean completedTerminal(DecisionAuditLog audit) {
        if (audit.getApprovalStatus() == DecisionAuditLog.ApprovalStatus.APPROVED
                && audit.getDecisionMade() != null && TERMINAL.contains(audit.getDecisionMade())) return true;
        if (audit.getDecisionType() != DecisionAuditLog.DecisionType.RULE_EXECUTION || audit.getOutputResult() == null) return false;
        try {
            return TERMINAL.contains(objectMapper.readTree(audit.getOutputResult()).path("action").asText());
        } catch (Exception invalidAudit) {
            throw new BusinessException(409, "原处置记录无法核对，请先检查处置历史");
        }
    }
}

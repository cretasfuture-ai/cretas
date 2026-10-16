package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Removes only material-consumption rows whose producing report lineage was
 * reversed.  The row quantity and any posted marker are deliberately left
 * untouched; soft deletion only removes the stale unposted input from a later
 * interim settlement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReversalConsumptionCleanupService {

    private final MaterialConsumptionRepository consumptionRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository planRepository;
    private final ReportReversalLogRepository reversalLogRepository;

    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    /** Locks the parent plan then batch before executeReversal enumerates reports. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockReversalScope(String factoryId, Long batchId, String planId) {
        if (factoryId == null || batchId == null) {
            throw new IllegalArgumentException("reversal scope is incomplete");
        }
        if (planId != null) {
            lockPlanScope(factoryId, planId);
        }
        ProductionBatch batch = batchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                .orElseThrow(() -> new IllegalStateException("reversal batch disappeared: " + batchId));
        if (!Objects.equals(factoryId, batch.getFactoryId()) || !Objects.equals(batchId, batch.getId())
                || !Objects.equals(planId, batch.getProductionPlanId())) {
            throw new IllegalStateException("reversal batch lineage mismatch: " + batchId);
        }
    }

    private void lockPlanScope(String factoryId, String planId) {
        com.cretas.aims.entity.ProductionPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new IllegalStateException("reversal plan disappeared: " + planId));
        if (!Objects.equals(factoryId, plan.getFactoryId()) || !Objects.equals(planId, plan.getId())) {
            throw new IllegalStateException("reversal plan lineage mismatch: " + planId);
        }
    }

    /** Called inside executeReversal's transaction for the exact reversed batch. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int softDeleteUnpostedForReversedBatch(
            String factoryId, Long batchId, String expectedPlanId, LocalDateTime deletedAt) {
        if (factoryId == null || batchId == null || deletedAt == null) {
            throw new IllegalArgumentException("reversal consumption cleanup scope is incomplete");
        }
        ProductionBatch batch = batchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                .orElseThrow(() -> new IllegalStateException("reversal batch disappeared: " + batchId));
        if (!Objects.equals(batchId, batch.getId()) || !Objects.equals(factoryId, batch.getFactoryId())
                || !Objects.equals(expectedPlanId, batch.getProductionPlanId())) {
            throw new IllegalStateException("reversal batch lineage mismatch: " + batchId);
        }
        List<MaterialConsumption> rows = consumptionRepository
                .findByProductionBatchIdAndFactoryId(batchId, factoryId);
        return softDeleteUnposted(factoryId, batchId, expectedPlanId, rows, deletedAt, deletedAt);
    }

    /**
     * Repairs legacy DONE reversals before a plan is settled again.  The batch
     * query is authoritative for the plan, so rows from old materialized steps
     * remain visible even when their process-sheet rows were already deleted.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int cleanupHistoricalDoneReversals(String factoryId, String planId, LocalDateTime deletedAt) {
        if (factoryId == null || planId == null || planId.isBlank() || deletedAt == null) {
            throw new IllegalArgumentException("historical reversal cleanup scope is incomplete");
        }
        lockPlanScope(factoryId, planId);
        int deleted = 0;
        List<ProductionBatch> batches = batchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId);
        if (batches == null) {
            return 0;
        }
        batches = batches.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(ProductionBatch::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (ProductionBatch batch : batches) {
            if (batch == null || batch.getId() == null
                    || !Objects.equals(factoryId, batch.getFactoryId())
                    || !Objects.equals(planId, batch.getProductionPlanId())) {
                continue;
            }
            ProductionBatch lockedBatch = batchRepository.findByIdAndFactoryIdForUpdate(batch.getId(), factoryId)
                    .orElseThrow(() -> new IllegalStateException("historical reversal batch disappeared: " + batch.getId()));
            if (!Objects.equals(batch.getId(), lockedBatch.getId())
                    || !Objects.equals(factoryId, lockedBatch.getFactoryId())
                    || !Objects.equals(planId, lockedBatch.getProductionPlanId())) {
                continue;
            }
            ReportReversalLog reversal = reversalLogRepository
                    .findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                            batch.getId(), ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .orElse(null);
            // Missing, ambiguous, non-DONE, mismatched-plan, or timestamp-less
            // lineage is intentionally a no-op: broad deletion is unsafe.
            if (reversal == null
                    || reversal.getStatus() != ReportReversalLog.ReversalStatus.DONE
                    || reversal.getReversalScope() != ReportReversalLog.ReversalScope.WHOLE_ORDER
                    || !Objects.equals(factoryId, reversal.getFactoryId())
                    || !Objects.equals(planId, reversal.getPlanId())
                    || !Objects.equals(batch.getId(), reversal.getBatchId())
                    || reversal.getDeletedAt() != null
                    || reversal.getCreatedAt() == null) {
                continue;
            }
            // updatedAt is mutable: WIP-history repair saves reverted_txn_ids
            // after DONE and advances it. Use the immutable approval anchor
            // when present, otherwise the legacy/no-approval creation time.
            LocalDateTime cutoff = reversal.getApprovedAt() != null
                    ? reversal.getApprovedAt() : reversal.getCreatedAt();
            if (cutoff.isAfter(deletedAt)) {
                continue;
            }
            List<MaterialConsumption> rows = consumptionRepository
                    .findByProductionBatchIdAndFactoryId(batch.getId(), factoryId);
            deleted += softDeleteUnposted(factoryId, batch.getId(), planId, rows, deletedAt, cutoff);
        }
        return deleted;
    }

    private int softDeleteUnposted(String factoryId, Long batchId,
                                   String expectedPlanId, List<MaterialConsumption> rows, LocalDateTime deletedAt,
                                   LocalDateTime cutoff) {
        int deleted = 0;
        if (rows == null) {
            return 0;
        }
        rows = rows.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(MaterialConsumption::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (MaterialConsumption row : rows) {
            if (entityManager == null) {
                throw new IllegalStateException("reversal consumption cleanup requires EntityManager");
            }
            entityManager.lock(row, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(factoryId, row.getFactoryId())
                    || !Objects.equals(batchId, row.getProductionBatchId())
                    || row.getDeletedAt() != null
                    || row.getInterimSettledAt() != null
                    || !("RAW_MATERIAL".equals(row.getSourceType())
                        || "SEMI_FINISHED".equals(row.getSourceType())
                        || "PACKAGING".equals(row.getSourceType())
                        || "SEASONING".equals(row.getSourceType()))
                    || (row.getProductionPlanId() != null
                        && !Objects.equals(expectedPlanId, row.getProductionPlanId()))
                    || (cutoff != null && (row.getCreatedAt() == null || row.getCreatedAt().isAfter(cutoff)))) {
                continue;
            }
            row.setDeletedAt(deletedAt);
            consumptionRepository.save(row);
            deleted++;
        }
        if (deleted > 0) {
            log.info("[REVERSAL-CONSUMPTION] soft-deleted {} unposted rows factory={} batch={} cutoff={}",
                    deleted, factoryId, batchId, cutoff);
        }
        return deleted;
    }
}

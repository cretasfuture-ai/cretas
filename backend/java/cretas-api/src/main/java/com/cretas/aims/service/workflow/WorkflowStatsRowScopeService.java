package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowNodeDTO;
import com.cretas.aims.dto.workflow.WorkflowStatsDTO;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Row-scoped workflow counters. This service deliberately has no cache: creator IDs
 * are request-specific and must never share the factory-only workflowStats cache.
 */
@Service
public class WorkflowStatsRowScopeService {

    @PersistenceContext
    private EntityManager entityManager;

    public WorkflowStatsDTO getProductionStats(String factoryId, Collection<Long> creatorIds) {
        if (creatorIds == null) {
            throw new IllegalArgumentException("creatorIds is required for a row-scoped query");
        }
        if (creatorIds.isEmpty()) {
            return assemble("production", 0, 0, 0, "待生产", "进行中", "已完成");
        }
        long pending = countProduction(factoryId, creatorIds, List.of(
                ProductionPlanStatus.PLANNED, ProductionPlanStatus.PENDING));
        long inProgress = countProduction(factoryId, creatorIds, List.of(
                ProductionPlanStatus.IN_PROGRESS, ProductionPlanStatus.PAUSED));
        long done = countProduction(factoryId, creatorIds, List.of(ProductionPlanStatus.COMPLETED));
        return assemble("production", pending, inProgress, done, "待生产", "进行中", "已完成");
    }

    public WorkflowStatsDTO getFinanceStats(String factoryId, Collection<Long> creatorIds) {
        if (creatorIds == null) {
            throw new IllegalArgumentException("creatorIds is required for a row-scoped query");
        }
        if (creatorIds.isEmpty()) {
            return assemble("finance", 0, 0, 0, "待开票", "待回款", "已收款");
        }
        long pending = countInvoice(factoryId, creatorIds, List.of(InvoiceStatus.REQUESTED));
        long inProgress = countInvoice(factoryId, creatorIds,
                List.of(InvoiceStatus.APPROVED, InvoiceStatus.ISSUED))
                + countPayment(factoryId, creatorIds, List.of(PaymentRecordStatus.PENDING));
        long done = countPayment(factoryId, creatorIds, List.of(PaymentRecordStatus.VERIFIED));
        return assemble("finance", pending, inProgress, done, "待开票", "待回款", "已收款");
    }

    private long countProduction(String factoryId, Collection<Long> creatorIds,
                                 Collection<ProductionPlanStatus> statuses) {
        return entityManager.createQuery(
                        "SELECT COUNT(p) FROM ProductionPlan p "
                                + "WHERE p.factoryId = :factoryId "
                                + "AND p.createdBy IN :creatorIds "
                                + "AND p.status IN :statuses", Long.class)
                .setParameter("factoryId", factoryId)
                .setParameter("creatorIds", creatorIds)
                .setParameter("statuses", statuses)
                .getSingleResult();
    }

    private long countInvoice(String factoryId, Collection<Long> creatorIds,
                              Collection<InvoiceStatus> statuses) {
        return entityManager.createQuery(
                        "SELECT COUNT(i) FROM InvoiceRecord i "
                                + "JOIN SalesOrder o ON o.id = i.salesOrderId AND o.factoryId = i.factoryId "
                                + "WHERE i.factoryId = :factoryId "
                                + "AND o.createdBy IN :creatorIds "
                                + "AND i.status IN :statuses "
                                + "AND i.deletedAt IS NULL", Long.class)
                .setParameter("factoryId", factoryId)
                .setParameter("creatorIds", creatorIds)
                .setParameter("statuses", statuses)
                .getSingleResult();
    }

    private long countPayment(String factoryId, Collection<Long> creatorIds,
                              Collection<PaymentRecordStatus> statuses) {
        return entityManager.createQuery(
                        "SELECT COUNT(p) FROM PaymentRecord p "
                                + "JOIN SalesOrder o ON o.id = p.salesOrderId AND o.factoryId = p.factoryId "
                                + "WHERE p.factoryId = :factoryId "
                                + "AND o.createdBy IN :creatorIds "
                                + "AND p.status IN :statuses "
                                + "AND p.deletedAt IS NULL", Long.class)
                .setParameter("factoryId", factoryId)
                .setParameter("creatorIds", creatorIds)
                .setParameter("statuses", statuses)
                .getSingleResult();
    }

    private WorkflowStatsDTO assemble(String module, long pending, long inProgress, long done,
                                      String pendingLabel, String inProgressLabel, String doneLabel) {
        return WorkflowStatsDTO.builder()
                .module(module)
                .nodes(List.of(
                        WorkflowNodeDTO.builder().id("pending").label(pendingLabel)
                                .status("PENDING").count(pending).build(),
                        WorkflowNodeDTO.builder().id("in_progress").label(inProgressLabel)
                                .status("IN_PROGRESS").count(inProgress).build(),
                        WorkflowNodeDTO.builder().id("done").label(doneLabel)
                                .status("DONE").count(done).build()))
                .lastRefreshedAt(Instant.now())
                .build();
    }
}

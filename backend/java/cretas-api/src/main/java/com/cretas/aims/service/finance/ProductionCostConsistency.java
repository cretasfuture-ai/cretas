package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

/** Serializes cost changes with period closing; historical GL must be reversed through the existing reopen flow. */
@Service
public class ProductionCostConsistency {
    private final EntityManager entityManager;
    private final AccountingPeriodService periods;

    public ProductionCostConsistency(EntityManager entityManager,
            @org.springframework.context.annotation.Lazy AccountingPeriodService periods) {
        this.entityManager = entityManager;
        this.periods = periods;
    }

    public static void lockFactory(EntityManager em, String factoryId) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "production-cost-finance:" + factoryId).getSingleResult();
    }

    public void assertChangeAllowed(String factoryId, String planId) {
        lockFactory(entityManager, factoryId);
        // Include both the ledger's delivery date and the immutable shipment event. Allocation rows
        // also count: changing one shared batch must not rewrite another closed month's cost basis.
        List<?> dates = entityManager.createNativeQuery("""
            SELECT DISTINCT d FROM (
              SELECT CAST(end_time AS date) d FROM production_batches
              WHERE factory_id=:factory AND production_plan_id=:plan AND deleted_at IS NULL
              UNION SELECT CAST(settled_at AS date) FROM production_settlements
              WHERE factory_id=:factory AND production_plan_id=:plan AND deleted_at IS NULL
              UNION SELECT CAST(warehouse_received_at AS date) FROM production_settlements
              WHERE factory_id=:factory AND production_plan_id=:plan AND deleted_at IS NULL
              UNION SELECT CAST(b.production_date AS date) FROM finished_goods_batches b
              WHERE b.factory_id=:factory AND b.production_plan_id=:plan AND b.deleted_at IS NULL
              UNION SELECT r.delivery_date FROM sales_delivery_records r
              JOIN sales_delivery_items i ON i.delivery_record_id=r.id AND i.deleted_at IS NULL
              JOIN finished_goods_batches b ON b.factory_id=r.factory_id AND b.deleted_at IS NULL
                AND (i.finished_goods_batch_id=b.id OR EXISTS (
                  SELECT 1 FROM sales_delivery_item_batch_allocations a WHERE a.factory_id=r.factory_id
                    AND a.delivery_item_id=CAST(i.id AS varchar) AND a.finished_goods_batch_id=b.id AND a.deleted_at IS NULL))
              WHERE r.factory_id=:factory AND b.production_plan_id=:plan AND r.deleted_at IS NULL
                AND COALESCE(r.record_role,'LEGACY')<>'MASTER'
                AND r.status IN ('SHIPPED','DELIVERED')
              UNION SELECT r.delivery_date FROM sales_delivery_records r
              JOIN batch_lineage_edges e ON e.factory_id=r.factory_id AND e.target_id=r.id
              JOIN finished_goods_batches b ON b.id=e.source_id AND b.factory_id=e.factory_id AND b.deleted_at IS NULL
              WHERE r.factory_id=:factory AND b.production_plan_id=:plan AND r.deleted_at IS NULL AND e.deleted_at IS NULL
                AND COALESCE(r.record_role,'LEGACY')<>'MASTER'
                AND r.status IN ('SHIPPED','DELIVERED') AND e.source_type='FINISHED_BATCH'
                AND e.target_type='SHIPMENT_RECORD' AND e.edge_type='FINISHED_TO_SHIPMENT'
                AND e.meta->>'shipmentCarrier'='SALES_DELIVERY_RECORD'
                AND e.meta->>'origin'='SALES_DELIVERY_SHIPMENT'
              UNION SELECT CAST(e.event_time AS date) FROM batch_lineage_edges e
              JOIN finished_goods_batches b ON b.id=e.source_id AND b.factory_id=e.factory_id AND b.deleted_at IS NULL
              WHERE e.factory_id=:factory AND b.production_plan_id=:plan AND e.deleted_at IS NULL
                AND e.source_type='FINISHED_BATCH' AND e.target_type='SHIPMENT_RECORD'
                AND e.edge_type='FINISHED_TO_SHIPMENT' AND e.meta->>'shipmentCarrier'='SALES_DELIVERY_RECORD'
                AND e.meta->>'origin'='SALES_DELIVERY_SHIPMENT'
            ) affected WHERE d IS NOT NULL ORDER BY d
            """).setParameter("factory", factoryId).setParameter("plan", planId).getResultList();
        for (Object value : dates) {
            LocalDate date = value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
            if (periods.getStatus(factoryId, date.getYear(), date.getMonthValue()) != AccountingPeriod.Status.OPEN) {
                throw new BusinessException(409, String.format("%d-%02d 已进入结账流程，请先由财务反结账，再更正成本并重新结转", date.getYear(), date.getMonthValue()))
                        .withCode("COST_PERIOD_NOT_OPEN");
            }
        }
    }

    public void assertShipmentPeriodOpen(String factoryId, LocalDate date) {
        if (date == null) throw new BusinessException(400,"确认发货前必须填写实际发运日期").withCode("SHIPMENT_DATE_REQUIRED");
        if (periods.getStatus(factoryId,date.getYear(),date.getMonthValue()) != AccountingPeriod.Status.OPEN) {
            throw new BusinessException(409,"出货日期所属期间未开放，请先由财务核对或反结账，再确认出货")
                    .withCode("SHIPMENT_PERIOD_NOT_OPEN");
        }
    }

    public void invalidateCostIndicators(String factoryId) {
        // Never delete or rewrite indicator_versions: the next normal read appends fresh evidence.
        entityManager.createNativeQuery("""
            UPDATE indicators SET last_computed_at=NULL, last_value=NULL
            WHERE factory_id=:factory AND deleted_at IS NULL
              AND code IN ('FACTORY_ACTUAL_TOTAL_COST','FACTORY_LU_UNIT_COST')
            """).setParameter("factory", factoryId).executeUpdate();
    }
}

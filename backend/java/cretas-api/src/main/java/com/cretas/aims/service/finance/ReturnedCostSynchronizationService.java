package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Revalues only proven physical return descendants inside the original cost correction transaction. */
@Service
@RequiredArgsConstructor
public class ReturnedCostSynchronizationService {
    private final ReturnOrderItemRepository items;
    private final ReturnOrderRepository orders;
    private final FinishedGoodsBatchRepository batches;
    private final FinishedGoodsAdjustmentLogRepository logs;
    private final AccountingPeriodService periods;
    private final DisposalCostAccountingService disposals;
    @org.springframework.beans.factory.annotation.Autowired
    private UnitContractService unitContractService;
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public void synchronize(String factory, FinishedGoodsBatch source, BigDecimal previousCost, Long actor) {
        ProductionCostConsistency.lockFactory(entityManager, factory);
        if (source==null || !Objects.equals(factory,source.getFactoryId()) || source.getProductTypeId()==null || source.getUnit()==null
                || (source.getOwnership()!=null && source.getOwnership()!=InventoryOwnership.COMPANY_OWNED) || source.getUnitCost()==null
                || source.getUnitCost().signum()<0) throw conflict("成本更正的源批次无效");
        walk(factory,source,previousCost,actor,new HashSet<>());
    }

    private void walk(String factory, FinishedGoodsBatch source, BigDecimal previousCost, Long actor, Set<String> seen) {
        if (!seen.add(source.getId())) throw conflict("退货成本来源存在循环或重复，请先核对追溯记录");
        var lines=items.findCompletedPhysicalReturnsForSource(factory,source.getId(),source.getBatchNumber());
        for (var line:lines) {
            var order=orders.findById(line.getReturnOrderId()).filter(o->factory.equals(o.getFactoryId()))
                    .orElseThrow(()->conflict("退货来源工厂不匹配"));
            assertOpen(factory,order.getReturnDate());
            if (actor==null || actor<=0) throw new BusinessException(401,"同步退货成本需要已认证操作者");
            var candidates=batches.findByFactoryIdAndReturnOrderItemId(factory,line.getId());
            FinishedGoodsBatch candidate;
            if(candidates.size()==1) candidate=candidates.getFirst();
            else if(candidates.isEmpty() && order.getItems().size()==1) candidate=batches
                    .findByFactoryIdAndBatchNumber(factory,"RTN-"+order.getReturnNumber()+"-1")
                    .filter(b->b.getReturnOrderItemId()==null).orElseThrow(()->conflict("历史退货缺少唯一回库证据"));
            else throw conflict("退货行回库批次缺失或重复");
            var child=batches.findByIdAndFactoryIdForUpdate(candidate.getId(),factory)
                    .orElseThrow(()->conflict("退回成品批次不存在"));
            if (!Objects.equals(factory,child.getFactoryId()) || child.getBatchNumber()==null
                    || !child.getBatchNumber().startsWith("RTN-"+order.getReturnNumber()+"-")
                    || !Objects.equals(source.getProductTypeId(),line.getProductTypeId())
                    || !Objects.equals(source.getBatchNumber(),line.getBatchNumber())
                    || !Objects.equals(child.getProductTypeId(),source.getProductTypeId())
                    || !sameUnit(factory, child.getUnit(), source.getUnit())
                    || (child.getOwnership()!=null && child.getOwnership()!=InventoryOwnership.COMPANY_OWNED)
                    || line.getQuantity()==null || line.getQuantity().signum()<=0
                    || child.getProducedQuantity()==null || child.getProducedQuantity().signum()<0)
                throw conflict("退回批次的SKU、原生单位、归属或数量证据不匹配");
            BigDecimal original=child.getProducedQuantity();
            for(var log:logs.findByBatchIdOrderByCreatedAtDesc(child.getId())) {
                if(!factory.equals(log.getFactoryId()) || log.getAdjustmentQuantity()==null) throw conflict("退回库存调整证据不完整");
                original=original.subtract(log.getAdjustmentQuantity());
            }
            if(original.compareTo(line.getQuantity())!=0) throw conflict("退回数量与实物入库证据不符");
            BigDecimal old=child.getUnitCost();
            if(old!=null && (previousCost==null || old.compareTo(previousCost)!=0))
                throw conflict("退回成本快照与更正前源成本不符，请人工核对后重试");
            assertShipmentDatesOpen(factory,child);
            if(old==null || old.compareTo(source.getUnitCost())!=0) {
                child.setUnitCost(source.getUnitCost());batches.save(child);
                logs.save(FinishedGoodsAdjustmentLog.builder().factoryId(factory).batchId(child.getId())
                        .adjustmentQuantity(BigDecimal.ZERO).beforeProduced(child.getProducedQuantity())
                        .afterProduced(child.getProducedQuantity()).operatorId(actor).referenceType("COST_CORRECTION")
                        .reason("源批次="+source.getId()+"; unitCostBefore="+old+"; unitCostAfter="+source.getUnitCost()).build());
            }
            walk(factory,child,old,actor,seen);
        }
        disposals.revalueForBatch(factory,source.getId(),actor);
    }

    private void assertShipmentDatesOpen(String factory,FinishedGoodsBatch batch) {
        List<?> rows=entityManager.createNativeQuery("""
            SELECT r.delivery_date,sum(e.quantity_used),
              sum(CASE WHEN e.unit=:nativeUnit AND e.quantity_used>0 THEN 0 ELSE 1 END)
            FROM sales_delivery_records r
            JOIN batch_lineage_edges e ON e.factory_id=r.factory_id AND e.target_id=r.id
            WHERE r.factory_id=:factory AND e.source_id=:batch AND e.deleted_at IS NULL AND r.deleted_at IS NULL
              AND e.source_type='FINISHED_BATCH' AND e.target_type='SHIPMENT_RECORD'
              AND e.edge_type='FINISHED_TO_SHIPMENT' AND r.status IN ('SHIPPED','DELIVERED')
              AND e.meta->>'shipmentCarrier'='SALES_DELIVERY_RECORD' AND e.meta->>'origin'='SALES_DELIVERY_SHIPMENT'
            GROUP BY r.id,r.delivery_date
            """).setParameter("factory",factory).setParameter("batch",batch.getId())
                .setParameter("nativeUnit",batch.getUnit()).getResultList();
        BigDecimal proven=BigDecimal.ZERO;
        for(Object value:rows) {
            Object[] row=(Object[])value;Object date=row[0];
            if(row.length!=3 || row[2]==null || ((Number)row[2]).longValue()!=0)
                throw conflict("退回批次再次出货的原生单位或正数量证据不完整");
            assertOpen(factory,date instanceof java.sql.Date d?d.toLocalDate():(LocalDate)date);
            if(row[1]==null) throw conflict("退回批次再次出货的数量证据缺失");
            proven=proven.add(new BigDecimal(row[1].toString()));
        }
        if(batch.getShippedQuantity()==null || batch.getShippedQuantity().signum()<0
                || batch.getShippedQuantity().compareTo(proven)!=0)
            throw conflict("退回批次再次出货的扣库证据缺失或数量不完整，不能更正历史成本");
    }
    private void assertOpen(String factory,LocalDate date) {
        if(date==null || periods.getStatus(factory,date.getYear(),date.getMonthValue())!=AccountingPeriod.Status.OPEN)
            throw conflict("退货或再次出货期间已进入结账，请先反结账再更正来源成本");
    }
    private BusinessException conflict(String message) {return new BusinessException(409,message)
            .withCode("RETURN_COST_SYNC_BLOCKED")
            .withHint("请核对退货单、回库批次、原生单位和成本快照后重试");}

    private boolean sameUnit(String factory, String left, String right) {
        if (left == null || right == null) return false;
        if (unitContractService == null) {
            throw new IllegalStateException("UnitContractService is required for unit identity checks");
        }
        String canonicalLeft = unitContractService.storageUnit(factory, left);
        String canonicalRight = unitContractService.storageUnit(factory, right);
        return canonicalLeft != null && !canonicalLeft.isBlank()
                && Objects.equals(canonicalLeft, canonicalRight);
    }
}

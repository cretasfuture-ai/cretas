package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.WarehouseTransferMovement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 缺口 15「温度关联批次」: 某库在某时段里可能在库的批次, 以及它们最后一次出库流水的时刻。
 *
 * <p>候选条件: 登记时刻不晚于超温结束, 且「还有实物」或「最后修改不早于超温开始」——
 * 一个在超温开始前就出完了的批次, 出完那次修改必然早于超温开始, 不会被捞进来;
 * 被捞进来的再由服务层按流水逐个判。
 *
 * <p>⛔ 不写成 {@code Repository<MaterialBatch, String>} 接口: Spring Data 会把它登记成 MaterialBatch 的仓储,
 * {@code Repositories.getRepositoryFor(MaterialBatch.class)} 就可能拿到这个不带 CRUD 的接口
 * (fast-contracts 里 SoftDeleteBatch3aContractTest 实测 ClassCastException)。
 */
@Repository
public class ColdChainBatchStayQueries {

    @PersistenceContext
    private EntityManager em;

    public List<MaterialBatch> findMaterialBatchCandidates(String factoryId, Collection<String> warehouseIds,
                                                           LocalDateTime from, LocalDateTime to) {
        return em.createQuery("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId "
                        + "AND m.warehouseId IN :warehouseIds AND m.createdAt <= :to "
                        + "AND (m.updatedAt >= :from OR (m.receiptQuantity - m.usedQuantity) > 0)", MaterialBatch.class)
                .setParameter("factoryId", factoryId)
                .setParameter("warehouseIds", warehouseIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    public List<FinishedGoodsBatch> findFinishedGoodsCandidates(String factoryId, Collection<String> warehouseIds,
                                                                LocalDateTime from, LocalDateTime to) {
        return em.createQuery("SELECT f FROM FinishedGoodsBatch f WHERE f.factoryId = :factoryId "
                        + "AND f.warehouseId IN :warehouseIds AND f.createdAt <= :to "
                        + "AND (f.updatedAt >= :from OR (f.producedQuantity - f.shippedQuantity) > 0)", FinishedGoodsBatch.class)
                .setParameter("factoryId", factoryId)
                .setParameter("warehouseIds", warehouseIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** 原料 / 在制批次: 领用流水 (投产消耗) 的最后时刻。返回 [batchId, max(consumptionTime)]。 */
    public List<Object[]> lastConsumptionAt(String factoryId, Collection<String> batchIds) {
        return em.createQuery("SELECT c.batchId, MAX(c.consumptionTime) FROM MaterialConsumption c "
                        + "WHERE c.factoryId = :factoryId AND c.batchId IN :batchIds GROUP BY c.batchId", Object[].class)
                .setParameter("factoryId", factoryId)
                .setParameter("batchIds", batchIds)
                .getResultList();
    }

    /**
     * 原料 / 在制批次: 调拨发出流水的最后时刻。返回 [sourceBatchId, max(createdAt)]。
     * 枚举走绑定参数 (嵌套枚举的全限定名写进 HQL 在 Hibernate 6 上解析不了, 见 FactoryWarehouse)。
     */
    public List<Object[]> lastTransferDispatchAt(String factoryId, Collection<String> batchIds) {
        return em.createQuery("SELECT w.sourceBatchId, MAX(w.createdAt) FROM WarehouseTransferMovement w "
                        + "WHERE w.factoryId = :factoryId AND w.kind = :kind AND w.sourceBatchId IN :batchIds "
                        + "GROUP BY w.sourceBatchId", Object[].class)
                .setParameter("factoryId", factoryId)
                .setParameter("kind", WarehouseTransferMovement.Kind.DISPATCH)
                .setParameter("batchIds", batchIds)
                .getResultList();
    }

    /** 成品批次: 发货明细的最后时刻。返回 [finishedGoodsBatchId, max(createdAt)]。 */
    public List<Object[]> lastDeliveryAt(Collection<String> batchIds) {
        return em.createQuery("SELECT i.finishedGoodsBatchId, MAX(i.createdAt) FROM SalesDeliveryItem i "
                        + "WHERE i.finishedGoodsBatchId IN :batchIds GROUP BY i.finishedGoodsBatchId", Object[].class)
                .setParameter("batchIds", batchIds)
                .getResultList();
    }

    /** 成品批次: 数量调整流水的最后时刻。返回 [batchId, max(createdAt)]。 */
    public List<Object[]> lastFinishedGoodsAdjustmentAt(String factoryId, Collection<String> batchIds) {
        return em.createQuery("SELECT l.batchId, MAX(l.createdAt) FROM FinishedGoodsAdjustmentLog l "
                        + "WHERE l.factoryId = :factoryId AND l.batchId IN :batchIds GROUP BY l.batchId", Object[].class)
                .setParameter("factoryId", factoryId)
                .setParameter("batchIds", batchIds)
                .getResultList();
    }
}

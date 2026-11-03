package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 缺口 15「温度关联批次」: 某库在某时段里可能在库的批次, 以及它们最后一次出库流水的时刻。
 *
 * <p>候选条件: 登记时刻不晚于超温结束, 且「还有实物」或「最后修改不早于超温开始」——
 * 一个在超温开始前就出完了的批次, 出完那次修改必然早于超温开始, 不会被捞进来;
 * 被捞进来的再由服务层按流水逐个判。
 */
@org.springframework.stereotype.Repository
public interface ColdChainBatchStayRepository extends Repository<MaterialBatch, String> {

    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId AND m.warehouseId IN :warehouseIds "
            + "AND m.createdAt <= :to "
            + "AND (m.updatedAt >= :from OR (m.receiptQuantity - m.usedQuantity) > 0)")
    List<MaterialBatch> findMaterialBatchCandidates(@Param("factoryId") String factoryId,
                                                    @Param("warehouseIds") Collection<String> warehouseIds,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    @Query("SELECT f FROM FinishedGoodsBatch f WHERE f.factoryId = :factoryId AND f.warehouseId IN :warehouseIds "
            + "AND f.createdAt <= :to "
            + "AND (f.updatedAt >= :from OR (f.producedQuantity - f.shippedQuantity) > 0)")
    List<FinishedGoodsBatch> findFinishedGoodsCandidates(@Param("factoryId") String factoryId,
                                                         @Param("warehouseIds") Collection<String> warehouseIds,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);

    /** 原料 / 在制批次: 领用流水 (投产消耗) 的最后时刻。返回 [batchId, max(consumptionTime)]。 */
    @Query("SELECT c.batchId, MAX(c.consumptionTime) FROM MaterialConsumption c "
            + "WHERE c.factoryId = :factoryId AND c.batchId IN :batchIds GROUP BY c.batchId")
    List<Object[]> lastConsumptionAt(@Param("factoryId") String factoryId,
                                     @Param("batchIds") Collection<String> batchIds);

    /**
     * 原料 / 在制批次: 调拨发出流水的最后时刻。返回 [sourceBatchId, max(createdAt)]。
     * {@code kind} 传 {@code Kind.DISPATCH} (嵌套枚举的全限定名写进 HQL 在 Hibernate 6 上解析不了, 见 FactoryWarehouse)。
     */
    @Query("SELECT w.sourceBatchId, MAX(w.createdAt) FROM WarehouseTransferMovement w "
            + "WHERE w.factoryId = :factoryId AND w.kind = :kind "
            + "AND w.sourceBatchId IN :batchIds GROUP BY w.sourceBatchId")
    List<Object[]> lastTransferDispatchAt(@Param("factoryId") String factoryId,
                                          @Param("kind") com.cretas.aims.entity.inventory.WarehouseTransferMovement.Kind kind,
                                          @Param("batchIds") Collection<String> batchIds);

    /** 成品批次: 发货明细的最后时刻。返回 [finishedGoodsBatchId, max(createdAt)]。 */
    @Query("SELECT i.finishedGoodsBatchId, MAX(i.createdAt) FROM SalesDeliveryItem i "
            + "WHERE i.finishedGoodsBatchId IN :batchIds GROUP BY i.finishedGoodsBatchId")
    List<Object[]> lastDeliveryAt(@Param("batchIds") Collection<String> batchIds);

    /** 成品批次: 数量调整流水的最后时刻。返回 [batchId, max(createdAt)]。 */
    @Query("SELECT l.batchId, MAX(l.createdAt) FROM FinishedGoodsAdjustmentLog l "
            + "WHERE l.factoryId = :factoryId AND l.batchId IN :batchIds GROUP BY l.batchId")
    List<Object[]> lastFinishedGoodsAdjustmentAt(@Param("factoryId") String factoryId,
                                                 @Param("batchIds") Collection<String> batchIds);
}

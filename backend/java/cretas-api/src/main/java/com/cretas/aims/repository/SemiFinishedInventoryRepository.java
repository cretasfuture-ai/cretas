package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 半成品库存 (WIP) Repository — G6 地基。
 *
 * <p>支撑 Wave 2/3: 部分领用扣减 ({@code findByIntermediateBatchNo} 拿源 WIP 行)、
 * 整批出成率 WIP 在制量汇总 ({@code findByFactoryIdAndBatchId})、防呆 :max。
 *
 * <p>软删除约定与 production_reports 一致 (deleted_at IS NULL 过滤); 查询方法显式带
 * {@code DeletedAtIsNull} 后缀 (实体未继承 BaseEntity 的 @Where 自动过滤)。
 */
@Repository
public interface SemiFinishedInventoryRepository extends JpaRepository<SemiFinishedInventory, Long> {

    /**
     * 按工序批次号查 WIP 行 (幂等 upsert 锚点)。
     * intermediate_batch_no 工厂内唯一 (partial unique uq_sfi_intermediate_batch_no), 故返 Optional。
     */
    Optional<SemiFinishedInventory> findByIntermediateBatchNoAndDeletedAtIsNull(String intermediateBatchNo);

    Optional<SemiFinishedInventory> findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
            String factoryId, String intermediateBatchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.intermediateBatchNo = :intermediateBatchNo AND w.deletedAt IS NULL")
    Optional<SemiFinishedInventory> findForUpdateByIntermediateBatchNoAndDeletedAtIsNull(
            @Param("intermediateBatchNo") String intermediateBatchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId "
            + "AND w.intermediateBatchNo = :intermediateBatchNo AND w.deletedAt IS NULL")
    Optional<SemiFinishedInventory> findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
            @Param("factoryId") String factoryId,
            @Param("intermediateBatchNo") String intermediateBatchNo);

    /** 某生产批次的全部 WIP 行 (整批出成率在制量汇总用)。 */
    List<SemiFinishedInventory> findByFactoryIdAndBatchIdAndDeletedAtIsNull(String factoryId, Long batchId);

    /** 某生产批次某状态的 WIP 行 (如 AVAILABLE 可领用 / DEPLETED 已清零)。 */
    List<SemiFinishedInventory> findByFactoryIdAndBatchIdAndStatusAndDeletedAtIsNull(
            String factoryId, Long batchId, String status);

    /**
     * 工厂级某状态的全部 WIP 行 (半成品盘点发起时快照 AVAILABLE 行)。
     * 按工序序、创建时间正序排列, 便于盘点录入界面稳定展示。
     */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId "
            + "AND w.status = :status AND w.deletedAt IS NULL "
            + "ORDER BY w.processOrder ASC, w.createdAt ASC")
    List<SemiFinishedInventory> findByFactoryIdAndStatusForStocktake(
            @Param("factoryId") String factoryId,
            @Param("status") String status);

    /** 某工序任务产出的 WIP 行 (一道任务一笔 WIP 行)。 */
    List<SemiFinishedInventory> findByFactoryIdAndSourceWorkProcessTaskIdAndDeletedAtIsNull(
            String factoryId, Long sourceWorkProcessTaskId);

    /**
     * 某生产批次有结余 (available_quantity > 0) 且未软删的 AVAILABLE WIP 行。
     * D3 余料退回 (Wave 4): 完工时若有结余 → 提示退回总仓。
     */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId "
            + "AND w.batchId = :batchId AND w.status = 'AVAILABLE' "
            + "AND w.availableQuantity > 0 AND w.deletedAt IS NULL "
            + "ORDER BY w.processOrder ASC")
    List<SemiFinishedInventory> findRemainingWip(@Param("factoryId") String factoryId,
                                                 @Param("batchId") Long batchId);

    /**
     * 备货看板 WIP 可用量聚合 — 按产品类型汇总可用半成品库存。
     *
     * <p>COALESCE(..., 0) 保证空集返回 0 而非 null，Service 层无需 null 检查。
     * 仅计入 {@code availableQuantity > 0} 的行（已耗尽/返库行不参与累加）。
     *
     * <p>注意：{@link SemiFinishedInventory} 未继承 BaseEntity，无 {@code @Where} 自动过滤，
     * 软删除过滤须在 JPQL 中显式加 {@code AND s.deletedAt IS NULL}（与同类方法保持一致）。
     *
     * @param factoryId     工厂 ID
     * @param productTypeId 产品类型 ID
     * @return 可用 WIP 总量 (≥0)
     */
    @Query("SELECT COALESCE(SUM(s.availableQuantity), 0) FROM SemiFinishedInventory s " +
           "WHERE s.factoryId = :factoryId AND s.productTypeId = :productTypeId " +
           "AND s.availableQuantity > 0 AND s.deletedAt IS NULL")
    BigDecimal sumAvailableByProduct(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    /**
     * SP2 二次加工: 列出工厂所有 AVAILABLE 且余量 > 0 的 WIP 行.
     * 用于 RN/web-admin 的"选择源 WIP"下拉列表。
     * 按工序序正序展示，同工序按批次创建时间排序。
     */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId " +
           "AND w.status = 'AVAILABLE' AND w.availableQuantity > 0 " +
           "AND w.deletedAt IS NULL " +
           "ORDER BY w.processOrder ASC, w.createdAt ASC")
    List<SemiFinishedInventory> findAvailableByFactory(@Param("factoryId") String factoryId);

    /** 按 ID 加悲观写锁 (SP2 二次加工计划创建时扣减 WIP 用)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.id = :id AND w.deletedAt IS NULL")
    Optional<SemiFinishedInventory> findByIdForUpdate(@Param("id") Long id);

    /**
     * C3: 工厂级半成品重量库存视图 — 所有未软删 WIP 行，含全状态 (AVAILABLE/DEPLETED/RETURNED)。
     * 按工序序升序、创建时间降序排列。不过滤 status/availableQuantity，展示完整库存快照。
     */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId " +
           "AND w.deletedAt IS NULL " +
           "ORDER BY w.processOrder ASC, w.createdAt DESC")
    List<SemiFinishedInventory> findByFactoryIdForWeightView(@Param("factoryId") String factoryId);

    /** Quantity-amount ledger scope: WIP ownership follows its source production batch. */
    @Query("SELECT w FROM SemiFinishedInventory w JOIN ProductionBatch b ON w.batchId = b.id " +
           "WHERE w.factoryId = :factoryId AND b.factoryId = :factoryId " +
           "AND b.createdBy IN :owners AND w.deletedAt IS NULL " +
           "ORDER BY w.processOrder ASC, w.createdAt DESC")
    List<SemiFinishedInventory> findByFactoryIdForWeightViewAndSourceOwnerIn(
            @Param("factoryId") String factoryId,
            @Param("owners") java.util.Collection<Long> owners);

    // ==================== 质检放行 (缺口 11/13) ====================

    /** 质检放行待办: 本厂处于某一质检状态 (通常 PENDING) 的未软删半成品行。 */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId " +
           "AND w.inspectionStatus = :inspectionStatus AND w.deletedAt IS NULL " +
           "ORDER BY w.updatedAt DESC")
    List<SemiFinishedInventory> findByFactoryIdAndInspectionStatusForRelease(
            @Param("factoryId") String factoryId,
            @Param("inspectionStatus") String inspectionStatus);

    /** 放行决定时按 id + 工厂加写锁 (防两个质检员同时放行/判不合格同一行)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.id = :id AND w.factoryId = :factoryId " +
           "AND w.deletedAt IS NULL")
    Optional<SemiFinishedInventory> findByIdAndFactoryIdForUpdate(
            @Param("id") Long id, @Param("factoryId") String factoryId);

    /** CCP 偏离扣留: 这些生产批次直接产出的半成品行。 */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId " +
           "AND w.batchId IN :batchIds AND w.deletedAt IS NULL")
    List<SemiFinishedInventory> findByFactoryIdAndBatchIdInForHold(
            @Param("factoryId") String factoryId,
            @Param("batchIds") java.util.Collection<Long> batchIds);

    /** CCP 偏离扣留: 按工序批次号 (逐道录入纯库存道的锚 / 计划锚前缀) 找行。 */
    @Query("SELECT w FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId " +
           "AND (w.intermediateBatchNo IN :batchNos OR w.intermediateBatchNo LIKE CONCAT(:anchorPrefix, '%')) " +
           "AND w.deletedAt IS NULL")
    List<SemiFinishedInventory> findByFactoryIdAndBatchNoOrAnchorPrefixForHold(
            @Param("factoryId") String factoryId,
            @Param("batchNos") java.util.Collection<String> batchNos,
            @Param("anchorPrefix") String anchorPrefix);

    /**
     * CCP 偏离扣留: App 报工的半成品行按产品码<b>跨批次累加</b>, 行上的 batchId 只是第一次建行的批次 ——
     * 所以要从入库流水反查「这些生产批次的报工入过哪些行」。
     */
    @Query("SELECT DISTINCT t.semiFinishedId FROM SemiFinishedInventoryTransaction t " +
           "WHERE t.factoryId = :factoryId AND t.txnType = 'IN' AND t.reportId IN " +
           "(SELECT r.id FROM ProductionReport r WHERE r.batchId IN :batchIds)")
    List<Long> findSemiIdsPostedByProductionBatches(
            @Param("factoryId") String factoryId,
            @Param("batchIds") java.util.Collection<Long> batchIds);
}

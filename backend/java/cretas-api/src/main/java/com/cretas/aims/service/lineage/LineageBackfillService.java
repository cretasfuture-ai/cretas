package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从既有业务记录回填历史血缘边。
 *
 * <h2>只写不删</h2>
 *
 * <p>本服务<b>只调 {@link LineageEdgeRecorder}</b>，⛔ 不自己拼 INSERT、⛔ 不删任何东西。
 * 幂等键与在线路径逐字相同（{@code consumption:<id>} / {@code finishedBatch:<id>} /
 * {@code delivery:<recordId>:<batchId>}），所以：
 * <ul>
 *   <li>重跑安全 —— 已写过的落 {@code ALREADY_EXISTS}；</li>
 *   <li>回填与在线并行也安全 —— 两边算出的是同一个键。</li>
 * </ul>
 * ⚠️ 这正是「不许有两份写边逻辑」的实际收益：如果回填侧自己拼键，只要与在线差一个字符，
 * 重跑就会把同一件事写成两条边，而没有任何测试会因此变红。
 *
 * <h2>读数是五个数不是一个</h2>
 *
 * <p>{@link Counters} 分开报 inserted / alreadyExists / skipped / ambiguous / failed。
 * ⛔ 不许只报「新增 N 条」：N=0 在「已经全在库里」和「一条都没量到」上长得一模一样
 * （硬约束 4 / 硬约束 9）。
 *
 * <h2>出货段的载体</h2>
 *
 * <p>🔴 {@code shipment_records} 全库 0 行且写入端点已冻结（410 GONE），所以出货段的数据源是
 * {@code sales_delivery_item_batch_allocations} × 已发货的 {@code sales_delivery_records}。
 * 没有分配记录的历史发货<b>不造边</b>（那种发货确实没有留下批次级信息，编不出来）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageBackfillService {

    /** 视为「货已经出去了」的发货单状态 —— 只有这些才该有 FINISHED_TO_SHIPMENT 边。 */
    private static final Set<SalesDeliveryStatus> SHIPPED_STATES = EnumSet.of(
            SalesDeliveryStatus.SHIPPED,
            SalesDeliveryStatus.PARTIALLY_SHIPPED,
            SalesDeliveryStatus.DELIVERED);

    private final LineageEdgeRecorder recorder;

    @PersistenceContext
    private EntityManager entityManager;

    /** 一段回填的五态计数。 */
    public static class Counters {
        private int inserted;
        private int alreadyExists;
        private int skipped;
        private int ambiguous;
        private int failed;
        private int scanned;

        void record(LineageRecordOutcome outcome) {
            scanned++;
            switch (outcome) {
                case INSERTED -> inserted++;
                case ALREADY_EXISTS -> alreadyExists++;
                case SKIPPED -> skipped++;
                case AMBIGUOUS -> ambiguous++;
                case FAILED -> failed++;
            }
        }

        public int getInserted() { return inserted; }
        public int getAlreadyExists() { return alreadyExists; }
        public int getSkipped() { return skipped; }
        public int getAmbiguous() { return ambiguous; }
        public int getFailed() { return failed; }
        public int getScanned() { return scanned; }

        @Override
        public String toString() {
            return String.format("扫描 %d / 新增 %d / 已存在 %d / 跳过 %d / 对不齐 %d / 失败 %d",
                    scanned, inserted, alreadyExists, skipped, ambiguous, failed);
        }
    }

    /** 三段各一份计数，外加「预演不写」标记。 */
    public record Report(String factoryId, boolean dryRun,
                         Counters rawToProduction,
                         Counters productionToFinished,
                         Counters finishedToShipment) {

        public String render() {
            return String.format(
                    "[lineage-backfill] factory=%s dryRun=%s%n"
                    + "  RAW_TO_PRODUCTION      %s%n"
                    + "  PRODUCTION_TO_FINISHED %s%n"
                    + "  FINISHED_TO_SHIPMENT   %s",
                    factoryId, dryRun, rawToProduction, productionToFinished, finishedToShipment);
        }
    }

    /**
     * 回填一个工厂的三段血缘。
     *
     * @param dryRun true = 只扫描并报数，一条边都不写
     */
    @Transactional
    public Report backfillFactory(String factoryId, boolean dryRun) {
        Counters raw = backfillRawToProduction(factoryId, dryRun);
        Counters prod = backfillProductionToFinished(factoryId, dryRun);
        Counters ship = backfillFinishedToShipment(factoryId, dryRun);
        Report report = new Report(factoryId, dryRun, raw, prod, ship);
        log.info("{}", report.render());
        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────

    Counters backfillRawToProduction(String factoryId, boolean dryRun) {
        List<MaterialConsumption> rows = entityManager.createQuery(
                        "SELECT m FROM MaterialConsumption m "
                        + "WHERE m.factoryId = :factoryId AND m.productionBatchId IS NOT NULL "
                        + "ORDER BY m.id ASC", MaterialConsumption.class)
                .setParameter("factoryId", factoryId)
                .getResultList();

        Counters counters = new Counters();
        for (MaterialConsumption m : rows) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("consumptionSourceType", m.getSourceType());
            meta.put("origin", "BACKFILL");
            LineageRecordOutcome outcome = dryRun
                    ? dryRunOutcome(m.getBatchId(), m.getProductionBatchId(), m.getQuantity())
                    : recorder.recordRawToProduction(factoryId, m.getBatchId(), m.getProductionBatchId(),
                            m.getQuantity(), null, m.getRecordedBy(),
                            "consumption:" + m.getId(), meta);
            counters.record(outcome);
        }
        return counters;
    }

    Counters backfillProductionToFinished(String factoryId, boolean dryRun) {
        List<FinishedGoodsBatch> rows = entityManager.createQuery(
                        "SELECT f FROM FinishedGoodsBatch f "
                        + "WHERE f.factoryId = :factoryId AND f.productionPlanId IS NOT NULL "
                        + "ORDER BY f.id ASC", FinishedGoodsBatch.class)
                .setParameter("factoryId", factoryId)
                .getResultList();

        Counters counters = new Counters();
        for (FinishedGoodsBatch fg : rows) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("origin", "BACKFILL");
            meta.put("finishedBatchNumber", fg.getBatchNumber());
            LineageRecordOutcome outcome = dryRun
                    ? LineageRecordOutcome.SKIPPED   // 预演不查计划对齐，只报「扫到了多少行」
                    : recorder.recordProductionToFinishedByPlan(factoryId, fg.getProductionPlanId(),
                            fg.getProductTypeId(), fg.getId(), fg.getProducedQuantity(), fg.getUnit(),
                            fg.getCreatedBy(), "finishedBatch:" + fg.getId(), meta);
            counters.record(outcome);
        }
        return counters;
    }

    /**
     * 出货段。
     *
     * <p>⚠️ 只回填<b>已发货</b>的发货单：批次分配表是「先删后写」的可变草稿，草稿阶段的分配
     * 不是事实。血缘边 append-only，把草稿写进去就删不掉了。
     */
    Counters backfillFinishedToShipment(String factoryId, boolean dryRun) {
        List<SalesDeliveryItemBatchAllocation> allocations = entityManager.createQuery(
                        "SELECT a FROM SalesDeliveryItemBatchAllocation a "
                        + "WHERE a.factoryId = :factoryId ORDER BY a.id ASC",
                        SalesDeliveryItemBatchAllocation.class)
                .setParameter("factoryId", factoryId)
                .getResultList();

        Counters counters = new Counters();
        if (allocations.isEmpty()) {
            return counters;
        }

        List<Long> itemIds = new ArrayList<>();
        for (SalesDeliveryItemBatchAllocation a : allocations) {
            try {
                itemIds.add(Long.valueOf(a.getDeliveryItemId()));
            } catch (NumberFormatException e) {
                log.warn("[lineage-backfill] 发货行 id 不是数字, 跳过: {}", a.getDeliveryItemId());
            }
        }
        Map<Long, SalesDeliveryItem> itemById = itemIds.isEmpty() ? Map.of()
                : entityManager.createQuery(
                                "SELECT i FROM SalesDeliveryItem i WHERE i.id IN :ids", SalesDeliveryItem.class)
                        .setParameter("ids", itemIds)
                        .getResultList().stream()
                        .collect(Collectors.toMap(SalesDeliveryItem::getId, i -> i, (a, b) -> a));

        Set<String> recordIds = itemById.values().stream()
                .map(SalesDeliveryItem::getDeliveryRecordId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, SalesDeliveryRecord> recordById = recordIds.isEmpty() ? Map.of()
                : entityManager.createQuery(
                                "SELECT r FROM SalesDeliveryRecord r WHERE r.id IN :ids", SalesDeliveryRecord.class)
                        .setParameter("ids", recordIds)
                        .getResultList().stream()
                        .collect(Collectors.toMap(SalesDeliveryRecord::getId, r -> r, (a, b) -> a));

        for (SalesDeliveryItemBatchAllocation a : allocations) {
            SalesDeliveryItem item = null;
            try {
                item = itemById.get(Long.valueOf(a.getDeliveryItemId()));
            } catch (NumberFormatException ignored) {
                // 上面已经 warn 过
            }
            SalesDeliveryRecord record = item == null ? null
                    : recordById.get(item.getDeliveryRecordId());
            if (record == null || !SHIPPED_STATES.contains(record.getStatus())) {
                // 草稿/已取消/已退回 —— 不是「货出去了」这件事实，不写边。
                counters.record(LineageRecordOutcome.SKIPPED);
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("origin", "BACKFILL");
            meta.put("deliveryItemId", item.getId());
            meta.put("finishedBatchNumber", a.getBatchNumber());
            LineageRecordOutcome outcome = dryRun
                    ? dryRunOutcome(a.getFinishedGoodsBatchId(), record.getId(), a.getAllocatedQty())
                    : recorder.recordFinishedToShipment(factoryId, a.getFinishedGoodsBatchId(),
                            record.getId(), a.getAllocatedQty(), a.getUnit(), record.getShippedBy(),
                            "delivery:" + record.getId() + ":" + a.getFinishedGoodsBatchId(), meta);
            counters.record(outcome);
        }
        return counters;
    }

    /**
     * 预演：只判「这行构不构成一条边」，不查库、不写库。
     *
     * <p>⚠️ 预演报出来的 INSERTED 是<b>上界</b>（其中一部分真跑时会落 ALREADY_EXISTS），
     * 这一点必须在报数时说清楚，⛔ 不能把它当成「将新增 N 条」。
     */
    private LineageRecordOutcome dryRunOutcome(Object sourceId, Object targetId, java.math.BigDecimal qty) {
        if (sourceId == null || String.valueOf(sourceId).isBlank()
                || targetId == null || String.valueOf(targetId).isBlank()) {
            return LineageRecordOutcome.SKIPPED;
        }
        if (qty != null && qty.signum() <= 0) {
            return LineageRecordOutcome.SKIPPED;
        }
        return LineageRecordOutcome.INSERTED;
    }
}

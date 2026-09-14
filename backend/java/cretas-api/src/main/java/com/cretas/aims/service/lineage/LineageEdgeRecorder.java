package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 血缘边的<b>唯一</b>写入口 —— 在线业务路径与历史回填共用同一个方法。
 *
 * <h2>为什么必须只有一份</h2>
 *
 * <p>同一件事有两份实现它一定会漂（见 {@code measurement-and-wiring.md} 形态 D）。
 * 血缘边这件事上「漂」的代价特别隐蔽：回填脚本和在线路径各自算一遍幂等键，
 * 只要有一处算法不同，回填就会把在线已经写过的边<b>再写一遍</b> —— 不报错、不变红，
 * 只是闭包里多出一条重复路径，而闭包表有唯一约束、边表没有，症状只会在
 * 「同一个原料出现两次」这种下游读数里显形。所以回填命令必须走这个类，
 * ⛔ 不许在回填侧另写一份 INSERT。
 *
 * <h2>fail-soft 的边界</h2>
 *
 * <p>与既有的 {@code WipInventoryServiceImpl#recordWipLineageEdge} 同款：
 * 写边失败只 warn，<b>绝不阻塞业务主流程</b>（报工/入库/发货不能因为审计边写不进去而失败）。
 * 但 fail-soft ≠ 静默：每一次「没写成」都通过 {@link LineageRecordOutcome} 返回一个**可计数**的
 * 读数，回填命令据此报「新增 N / 已存在 M / 跳过 K / 对不齐 A / 失败 F」。
 *
 * <h2>幂等键</h2>
 *
 * <p>键是 {@code (factoryId, edgeType, sourceType+sourceId, targetType+targetId, meta.refKey)}。
 * {@code refKey} 是业务侧那条记录的身份（consumptionId / finishedBatchId / deliveryItemId…），
 * 它存在的意义是区分「同一对批次之间的两次真实投料」和「同一次投料被写了两遍」：
 * 前者 refKey 不同（两条 consumption 行），后者 refKey 相同。
 *
 * <p>⚠️ 没有 refKey 时退化成「同一对批次同一 edgeType 只留一条」。这是刻意的保守选择：
 * 宁可少写一条真边，也不要在重跑回填时造出重复边。
 *
 * @author Cretas Team
 * @since 2026-09-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageEdgeRecorder {

    // ── 节点类型（与 batch_lineage_edges.source_type 的取值集一致） ────────────
    public static final String NODE_MATERIAL_BATCH = "MATERIAL_BATCH";
    public static final String NODE_PRODUCTION_BATCH = "PRODUCTION_BATCH";
    public static final String NODE_FINISHED_BATCH = "FINISHED_BATCH";
    public static final String NODE_SHIPMENT_RECORD = "SHIPMENT_RECORD";

    // ── 边类型 ────────────────────────────────────────────────────────────────
    public static final String EDGE_RAW_TO_PRODUCTION = "RAW_TO_PRODUCTION";
    public static final String EDGE_PRODUCTION_TO_FINISHED = "PRODUCTION_TO_FINISHED";
    public static final String EDGE_FINISHED_TO_SHIPMENT = "FINISHED_TO_SHIPMENT";

    /** meta 里存幂等键的字段名。 */
    public static final String META_REF_KEY = "refKey";

    /**
     * meta 里标明「这条边的出货节点实际落在哪张表」。
     *
     * <p>🔴 {@code shipment_records} 这张表在生产库<b>全库 0 行</b>，而且它的写入端点
     * 已经被冻结成 410 GONE（见 {@code LegacyShipmentWriteFreezeControllerTest}）——
     * 真正承载出货的是 {@code sales_delivery_records}。节点类型仍沿用
     * {@link #NODE_SHIPMENT_RECORD}（那是既有枚举，不改），但载体要在 meta 里写清楚，
     * 否则下一个人拿 shipment_record_id 去 join {@code shipment_records} 会查出 0 行，
     * 然后把「表是空的」读成「没有出货」。
     */
    public static final String META_SHIPMENT_CARRIER = "shipmentCarrier";

    public static final String SHIPMENT_CARRIER_SALES_DELIVERY = "SALES_DELIVERY_RECORD";

    private final BatchLineageEdgeRepository edgeRepository;
    private final FinishedBatchProductionAligner productionAligner;

    /**
     * 原料批次 → 生产批次（投料）。
     *
     * @param materialBatchId   来源原料/半成品库存批次 id（{@code material_batches.id}）
     * @param productionBatchId 目标生产批次 id
     * @param refKey            幂等键，通常是那条 {@code material_consumptions} 行的身份
     */
    public LineageRecordOutcome recordRawToProduction(String factoryId,
                                                      String materialBatchId,
                                                      Long productionBatchId,
                                                      BigDecimal quantity,
                                                      String unit,
                                                      Long operatorId,
                                                      String refKey,
                                                      Map<String, Object> extraMeta) {
        return record(factoryId, EDGE_RAW_TO_PRODUCTION,
                NODE_MATERIAL_BATCH, materialBatchId,
                NODE_PRODUCTION_BATCH, productionBatchId == null ? null : String.valueOf(productionBatchId),
                quantity, unit, operatorId, refKey, extraMeta);
    }

    /**
     * 生产批次 → 成品批次（产出入库）。
     *
     * @param finishedBatchId 目标成品批次 id（{@code finished_goods_batches.id}）
     */
    public LineageRecordOutcome recordProductionToFinished(String factoryId,
                                                           Long productionBatchId,
                                                           String finishedBatchId,
                                                           BigDecimal quantity,
                                                           String unit,
                                                           Long operatorId,
                                                           String refKey,
                                                           Map<String, Object> extraMeta) {
        return record(factoryId, EDGE_PRODUCTION_TO_FINISHED,
                NODE_PRODUCTION_BATCH, productionBatchId == null ? null : String.valueOf(productionBatchId),
                NODE_FINISHED_BATCH, finishedBatchId,
                quantity, unit, operatorId, refKey, extraMeta);
    }

    /**
     * 生产批次 → 成品批次，但调用方<b>只知道计划 id</b>（成品表没有 production_batch_id 列）。
     *
     * <p>对齐规则集中在 {@link FinishedBatchProductionAligner}，对不齐时返回
     * {@link LineageRecordOutcome#AMBIGUOUS} 并且<b>不写边</b>。
     * ⛔ 调用方不得把 AMBIGUOUS 当成 SKIPPED 一起吞掉 —— 那是两件事：
     * 前者说明「数据里确实有东西但我们分不出」，是需要被报出来的缺口。
     */
    public LineageRecordOutcome recordProductionToFinishedByPlan(String factoryId,
                                                                 String productionPlanId,
                                                                 String finishedProductTypeId,
                                                                 String finishedBatchId,
                                                                 BigDecimal quantity,
                                                                 String unit,
                                                                 Long operatorId,
                                                                 String refKey,
                                                                 Map<String, Object> extraMeta) {
        FinishedBatchProductionAligner.Alignment alignment =
                productionAligner.align(factoryId, productionPlanId, finishedProductTypeId);
        if (alignment.batch().isEmpty()) {
            return alignment.asFailureOutcome();
        }
        Map<String, Object> meta = new HashMap<>();
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        meta.put("productionPlanId", productionPlanId);
        meta.put("planBatchCandidates", alignment.candidateCount());
        return recordProductionToFinished(factoryId, alignment.batch().get().getId(), finishedBatchId,
                quantity, unit, operatorId, refKey, meta);
    }

    /**
     * 成品批次 → 出货单（发货）。
     *
     * @param shipmentId 出货节点 id —— 当前载体是 {@code sales_delivery_records.id}，
     *                   见 {@link #META_SHIPMENT_CARRIER}
     */
    public LineageRecordOutcome recordFinishedToShipment(String factoryId,
                                                         String finishedBatchId,
                                                         String shipmentId,
                                                         BigDecimal quantity,
                                                         String unit,
                                                         Long operatorId,
                                                         String refKey,
                                                         Map<String, Object> extraMeta) {
        Map<String, Object> meta = new HashMap<>();
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        meta.put(META_SHIPMENT_CARRIER, SHIPMENT_CARRIER_SALES_DELIVERY);
        return record(factoryId, EDGE_FINISHED_TO_SHIPMENT,
                NODE_FINISHED_BATCH, finishedBatchId,
                NODE_SHIPMENT_RECORD, shipmentId,
                quantity, unit, operatorId, refKey, meta);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 唯一实现
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 所有三段共用的实现。⛔ 不要为某一段单独复制一份 —— 幂等判据一旦有两份就会漂。
     */
    LineageRecordOutcome record(String factoryId,
                                String edgeType,
                                String sourceType, String sourceId,
                                String targetType, String targetId,
                                BigDecimal quantity, String unit,
                                Long operatorId,
                                String refKey,
                                Map<String, Object> extraMeta) {
        if (isBlank(factoryId) || isBlank(sourceId) || isBlank(targetId)) {
            // 「这条业务记录不构成一条边」是合法状态（例：不挂生产批次的手工出库），
            // ⛔ 不要在这里兜底造一个 id —— 那是假数据，比没有边糟得多。
            log.debug("[lineage] 缺少构成边的 id, 不写边: factory={} edge={} source={}/{} target={}/{}",
                    factoryId, edgeType, sourceType, sourceId, targetType, targetId);
            return LineageRecordOutcome.SKIPPED;
        }
        if (quantity != null && quantity.signum() <= 0) {
            // 负数/零是冲销或调整，血缘边是 append-only 的，写不进「减少」这个语义。
            // 登记为 SKIPPED 让回填能把它报出来，⛔ 不是静默丢弃。
            log.debug("[lineage] 数量非正({}), 不写边(append-only 表达不了冲销): edge={} {}→{}",
                    quantity, edgeType, sourceId, targetId);
            return LineageRecordOutcome.SKIPPED;
        }
        try {
            List<BatchLineageEdge> existing = edgeRepository.findMatchingEdges(
                    factoryId, edgeType, sourceType, sourceId, targetType, targetId);
            if (alreadyRecorded(existing, refKey)) {
                return LineageRecordOutcome.ALREADY_EXISTS;
            }

            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            edge.setEdgeType(edgeType);
            edge.setSourceType(sourceType);
            edge.setSourceId(sourceId);
            edge.setTargetType(targetType);
            edge.setTargetId(targetId);
            edge.setQuantityUsed(quantity);
            edge.setUnit(unit);
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(operatorId);

            Map<String, Object> meta = new HashMap<>();
            if (extraMeta != null) {
                extraMeta.forEach((k, v) -> {
                    if (k != null && v != null) {
                        meta.put(k, v);
                    }
                });
            }
            if (!isBlank(refKey)) {
                meta.put(META_REF_KEY, refKey);
            }
            edge.setMeta(meta);

            edgeRepository.save(edge);
            return LineageRecordOutcome.INSERTED;
        } catch (Exception e) {
            // fail-soft: 审计边写不进去绝不能把报工/入库/发货拖挂。
            log.warn("[lineage] 边写入失败 (fail-soft, 不阻塞业务): edge={} {}:{} → {}:{} qty={} refKey={}",
                    edgeType, sourceType, sourceId, targetType, targetId, quantity, refKey, e);
            return LineageRecordOutcome.FAILED;
        }
    }

    /**
     * 幂等判定。
     *
     * <p>有 refKey → 只有<b>同 refKey</b> 的边算「已写过」；同一对批次之间多次不同投料
     * 各自写各自的边（这是真实业务，不是重复）。
     *
     * <p>没有 refKey → 退化成「同一对批次同一 edgeType 已有任何边就算写过」。
     */
    private boolean alreadyRecorded(List<BatchLineageEdge> existing, String refKey) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        if (isBlank(refKey)) {
            return true;
        }
        return existing.stream().anyMatch(e -> {
            Map<String, Object> meta = e.getMeta();
            return meta != null && Objects.equals(String.valueOf(meta.get(META_REF_KEY)), refKey);
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

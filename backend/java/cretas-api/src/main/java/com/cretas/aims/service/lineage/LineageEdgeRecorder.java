package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
    public static final String EDGE_MATERIAL_TRANSFER = "MATERIAL_TRANSFER";

    /**
     * 生产批次 → 它产出的在制库存批（{@code material_batches}，{@code source_doc_type='PRODUCTION_BATCH'}）。
     *
     * <p>🔴 2026-10-24 prod 实测：文员逐道录入每道中间产出一个 WIP 库存批，下一道把它当原料投入
     * （那一跳 RAW_TO_PRODUCTION 一直有），但「谁产出了这个 WIP 库存批」只记在
     * {@code source_doc_id} 上、<b>从来没有边</b> ⇒ 闭包在每一道中间产出处断开，
     * 近 60 天 12 个终端成品批 0/12 追得到首道原料。
     *
     * <p>为什么是新边型而不是复用 {@link #EDGE_PRODUCTION_TO_FINISHED}：目标节点是
     * MATERIAL_BATCH 不是 FINISHED_BATCH；按 edge_type 统计「成品入库」的读者会把它数进去。
     * {@code edge_type} 列是 VARCHAR(30) 无 CHECK 约束（V20260821_02），不需要迁移。
     */
    public static final String EDGE_PRODUCTION_TO_WIP = "PRODUCTION_TO_WIP";

    /**
     * 半成品（{@code semi_finished_inventory}）被另一个生产批次领用：产出它的生产批次 → 领用它的生产批次。
     *
     * <p>App 路径（{@code WipInventoryServiceImpl#recordWipLineageEdge}）历史上直接写这个边型；
     * web 逐道录入的三个扣减点（回料正式报工 / 小结 / 结单）经 {@link SemiFeedLineageRecorder} 写同一个边型。
     */
    public static final String EDGE_WIP_CONSUME = "WIP_CONSUME";

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

    // ── 幂等键：在线路径与回填共用（⛔ 不许在调用方各拼一份 —— 差一个字符重跑就双写）──

    /** 一个在制库存批只有一个产出它的生产批次，所以键只需要库存批 id。 */
    public static String wipStockRefKey(String wipMaterialBatchId) {
        return "wipStock:" + wipMaterialBatchId;
    }

    /** App 报工 {@code material_batch_refs} 里的一个原料批次（同一报工同一批次的多条引用已求和）。 */
    public static String reportMaterialRefKey(Long reportId, String materialBatchId) {
        return "report:" + reportId + ":material:" + materialBatchId;
    }

    /**
     * 真正落库的那一层（独立事务 + 立即 flush）。
     * ⚠️ 必须是<b>另一个 bean</b>：Spring 的 @Transactional 走代理，同类自调用不生效。
     */
    private final LineageEdgeWriter edgeWriter;
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
        return recordProductionToFinishedByPlan(factoryId, productionPlanId, finishedProductTypeId,
                null, finishedBatchId, quantity, unit, operatorId, refKey, extraMeta);
    }

    /**
     * 同上，但带一个「产出它的生产批次号」线索（见 {@link FinishedBatchProductionAligner#align(String, String, String, String)}）。
     *
     * <p>🔴 2026-10-24 prod 实测：结单「按终端产出逐行入库」({@code createFinishedGoodsForOutputLine})
     * 建的成品批次号<b>就是</b>报工生产批次号（CLK-B-… / PB-PLAN-…），而同一计划下有 3 个同产品的正式批次 ⇒
     * 只按计划对齐必然 AMBIGUOUS ⇒ 3 个成品批一条 PRODUCTION_TO_FINISHED 都没有。批次号是精确键，先用它。
     */
    public LineageRecordOutcome recordProductionToFinishedByPlan(String factoryId,
                                                                 String productionPlanId,
                                                                 String finishedProductTypeId,
                                                                 String producingBatchNumberHint,
                                                                 String finishedBatchId,
                                                                 BigDecimal quantity,
                                                                 String unit,
                                                                 Long operatorId,
                                                                 String refKey,
                                                                 Map<String, Object> extraMeta) {
        FinishedBatchProductionAligner.Alignment alignment = productionAligner.align(
                factoryId, productionPlanId, finishedProductTypeId, producingBatchNumberHint);
        if (alignment.batch().isEmpty()) {
            return alignment.asFailureOutcome();
        }
        Map<String, Object> meta = new HashMap<>();
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        meta.put("productionPlanId", productionPlanId);
        meta.put("planBatchCandidates", alignment.candidateCount());
        meta.put("alignedBy", alignment.method());
        return recordProductionToFinished(factoryId, alignment.batch().get().getId(), finishedBatchId,
                quantity, unit, operatorId, refKey, meta);
    }

    /**
     * 生产批次 → 它产出的在制库存批（{@link #EDGE_PRODUCTION_TO_WIP}）。
     *
     * @param wipMaterialBatchId {@code material_batches.id}（{@code source_doc_type='PRODUCTION_BATCH'}）
     * @param refKey             应为 {@link #wipStockRefKey(String)}
     */
    public LineageRecordOutcome recordProductionToWipStock(String factoryId,
                                                           Long productionBatchId,
                                                           String wipMaterialBatchId,
                                                           BigDecimal quantity,
                                                           String unit,
                                                           Long operatorId,
                                                           String refKey,
                                                           Map<String, Object> extraMeta) {
        return record(factoryId, EDGE_PRODUCTION_TO_WIP,
                NODE_PRODUCTION_BATCH, productionBatchId == null ? null : String.valueOf(productionBatchId),
                NODE_MATERIAL_BATCH, wipMaterialBatchId,
                quantity, unit, operatorId, refKey, extraMeta);
    }

    /** dry-run 版的生产批次→在制库存批入口。 */
    public LineageRecordOutcome previewProductionToWipStock(String factoryId, Long productionBatchId,
                                                            String wipMaterialBatchId, BigDecimal quantity,
                                                            String unit, String refKey) {
        return preview(factoryId, EDGE_PRODUCTION_TO_WIP,
                NODE_PRODUCTION_BATCH, productionBatchId == null ? null : String.valueOf(productionBatchId),
                NODE_MATERIAL_BATCH, wipMaterialBatchId,
                quantity, unit, null, refKey, null);
    }

    /**
     * 半成品领用：产出它的生产批次 → 领用它的生产批次（{@link #EDGE_WIP_CONSUME}）。
     *
     * <p>⚠️ 来源生产批次必须由调用方<b>可靠解析</b>出来（{@link SemiSourceBatchResolver}）。
     * ⛔ 解析不了时不许拿目标批次顶替 —— 那正是 prod 上 64/70 条 WIP_CONSUME 自环的来历。
     */
    public LineageRecordOutcome recordWipConsume(String factoryId,
                                                 Long sourceProductionBatchId,
                                                 Long targetProductionBatchId,
                                                 BigDecimal quantity,
                                                 String unit,
                                                 Long operatorId,
                                                 String refKey,
                                                 Map<String, Object> extraMeta) {
        return record(factoryId, EDGE_WIP_CONSUME,
                NODE_PRODUCTION_BATCH, sourceProductionBatchId == null ? null : String.valueOf(sourceProductionBatchId),
                NODE_PRODUCTION_BATCH, targetProductionBatchId == null ? null : String.valueOf(targetProductionBatchId),
                quantity, unit, operatorId, refKey, extraMeta);
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

    /**
     * 同厂调拨：原料批次 → 调入方原料批次。
     *
     * <p>调用方必须按实际 FEFO 扣减逐批调用；{@code refKey} 应包含调拨单、明细和来源批次，
     * 这样同一来源批次的重放只会保留一条边，而一次调拨跨多个来源批次仍能表达多条真实边。</p>
     */
    public LineageRecordOutcome recordMaterialTransfer(String factoryId,
                                                       String sourceMaterialBatchId,
                                                       String targetMaterialBatchId,
                                                       BigDecimal quantity,
                                                       String unit,
                                                       Long operatorId,
                                                       String refKey,
                                                       Map<String, Object> extraMeta) {
        return record(factoryId, EDGE_MATERIAL_TRANSFER,
                NODE_MATERIAL_BATCH, sourceMaterialBatchId,
                NODE_MATERIAL_BATCH, targetMaterialBatchId,
                quantity, unit, operatorId, refKey, extraMeta);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 唯一实现
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 所有三段共用的实现。⛔ 不要为某一段单独复制一份 —— 幂等判据一旦有两份就会漂。
     *
     * <h3>两条落库路径，按「当前有没有活动事务」分（⛔ 不要合并成一条）</h3>
     *
     * <table border="1">
     *   <tr><th>场景</th><th>怎么写</th><th>为什么</th></tr>
     *   <tr>
     *     <td>有活动事务（报工 / 发货 / 结单 / 小结）</td>
     *     <td>注册 {@code afterCommit} 回调，回调里走 REQUIRES_NEW + saveAndFlush</td>
     *     <td>① 调用方回滚 ⇒ 回调不执行 ⇒ 不留永久假边（本表 append-only，写错删不掉）；<br>
     *         ② 边写失败时调用方已经提交完了 ⇒ 确认发货 / 自动扣料绝不会被拖挂</td>
     *   </tr>
     *   <tr>
     *     <td>无活动事务（回填 runner / 无事务的 controller 直调）</td>
     *     <td>直接 REQUIRES_NEW + saveAndFlush，同步拿结果</td>
     *     <td>没有更大的原子单元可以被连坐，回填也因此拿得到精确的五态读数</td>
     *   </tr>
     * </table>
     *
     * <h3>⛔ 为什么「只加 REQUIRES_NEW」不够</h3>
     *
     * <p>REQUIRES_NEW 会<b>立即独立提交</b>。直接在业务事务里调它，一旦调用方随后回滚，
     * 边已经落库且再也删不掉 —— 血缘图上会出现一条「这批料投进了一个根本不存在的批次」的假边。
     * 所以必须挂在 afterCommit 上。
     *
     * <h3>⛔ 为什么「只加 afterCommit」也不够</h3>
     *
     * <p>afterCommit 回调跑在业务事务<b>已经提交之后</b>，此时没有活动事务；
     * 若回调里只调 {@code save()}，Hibernate 的 assigned-id 实体不会立即 flush，
     * INSERT 会落进一个临时 session 的边界，异常时机再次变得不可控。
     * 所以回调里走的是 {@link LineageEdgeWriter}（REQUIRES_NEW + saveAndFlush）。
     */
    LineageRecordOutcome record(String factoryId,
                                String edgeType,
                                String sourceType, String sourceId,
                                String targetType, String targetId,
                                BigDecimal quantity, String unit,
                                Long operatorId,
                                String refKey,
                                Map<String, Object> extraMeta) {
        BatchLineageEdge edge = buildEdge(factoryId, edgeType, sourceType, sourceId,
                targetType, targetId, quantity, unit, operatorId, refKey, extraMeta);
        if (edge == null) {
            return LineageRecordOutcome.SKIPPED;
        }
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                // 整体 try/catch: afterCommit 里抛异常会被 Spring 往上抛,
                                // 而此时业务事务已经提交 —— 让它冒出去只会把一个已经成功的
                                // 请求变成 500。fail-soft 的语义在这里才真正兑现。
                                try {
                                    edgeWriter.writeInNewTransaction(edge, refKey);
                                } catch (Exception e) {
                                    log.warn("[lineage] afterCommit 写边失败 (fail-soft, 业务已提交): "
                                                    + "edge={} {}:{} → {}:{} refKey={}",
                                            edgeType, sourceType, sourceId, targetType, targetId, refKey, e);
                                }
                            }
                        });
                return LineageRecordOutcome.SCHEDULED;
            }
            return edgeWriter.writeInNewTransaction(edge, refKey);
        } catch (Exception e) {
            log.warn("[lineage] 边登记失败 (fail-soft, 不阻塞业务): edge={} {}:{} → {}:{} qty={} refKey={}",
                    edgeType, sourceType, sourceId, targetType, targetId, quantity, refKey, e);
            return LineageRecordOutcome.FAILED;
        }
    }

    /**
     * 只判定不写 —— 回填 dry-run 用。
     *
     * <p>⚠️ 与 {@link #record} 共用 {@link #buildEdge} 与
     * {@link LineageEdgeWriter} 的查重判据，所以 dry-run 报出来的
     * SKIPPED / ALREADY_EXISTS / INSERTED 与真跑逐条一致。
     * ⛔ 不许给 dry-run 另写一套判定（形态 D：两边会给出不同的数，而没有测试会红）。
     */
    LineageRecordOutcome preview(String factoryId,
                                 String edgeType,
                                 String sourceType, String sourceId,
                                 String targetType, String targetId,
                                 BigDecimal quantity, String unit,
                                 Long operatorId,
                                 String refKey,
                                 Map<String, Object> extraMeta) {
        BatchLineageEdge edge = buildEdge(factoryId, edgeType, sourceType, sourceId,
                targetType, targetId, quantity, unit, operatorId, refKey, extraMeta);
        if (edge == null) {
            return LineageRecordOutcome.SKIPPED;
        }
        return edgeWriter.previewInNewTransaction(edge, refKey);
    }

    /** dry-run 版的 by-plan 入口：跑真实的对齐（只读），⛔ 不无条件报 SKIPPED。 */
    public LineageRecordOutcome previewProductionToFinishedByPlan(String factoryId,
                                                                  String productionPlanId,
                                                                  String finishedProductTypeId,
                                                                  String finishedBatchId,
                                                                  BigDecimal quantity,
                                                                  String unit,
                                                                  String refKey) {
        return previewProductionToFinishedByPlan(factoryId, productionPlanId, finishedProductTypeId,
                null, finishedBatchId, quantity, unit, refKey);
    }

    /** dry-run 版，带生产批次号线索（与在线 / 真跑同一条对齐规则）。 */
    public LineageRecordOutcome previewProductionToFinishedByPlan(String factoryId,
                                                                  String productionPlanId,
                                                                  String finishedProductTypeId,
                                                                  String producingBatchNumberHint,
                                                                  String finishedBatchId,
                                                                  BigDecimal quantity,
                                                                  String unit,
                                                                  String refKey) {
        FinishedBatchProductionAligner.Alignment alignment = productionAligner.align(
                factoryId, productionPlanId, finishedProductTypeId, producingBatchNumberHint);
        if (alignment.batch().isEmpty()) {
            return alignment.asFailureOutcome();
        }
        return preview(factoryId, EDGE_PRODUCTION_TO_FINISHED,
                NODE_PRODUCTION_BATCH, String.valueOf(alignment.batch().get().getId()),
                NODE_FINISHED_BATCH, finishedBatchId,
                quantity, unit, null, refKey, null);
    }

    /** dry-run 版的原料→生产批次入口。 */
    public LineageRecordOutcome previewRawToProduction(String factoryId, String materialBatchId,
                                                       Long productionBatchId, BigDecimal quantity,
                                                       String refKey) {
        return preview(factoryId, EDGE_RAW_TO_PRODUCTION,
                NODE_MATERIAL_BATCH, materialBatchId,
                NODE_PRODUCTION_BATCH, productionBatchId == null ? null : String.valueOf(productionBatchId),
                quantity, null, null, refKey, null);
    }

    /** dry-run 版的成品→出货入口。 */
    public LineageRecordOutcome previewFinishedToShipment(String factoryId, String finishedBatchId,
                                                          String shipmentId, BigDecimal quantity,
                                                          String unit, String refKey) {
        return preview(factoryId, EDGE_FINISHED_TO_SHIPMENT,
                NODE_FINISHED_BATCH, finishedBatchId,
                NODE_SHIPMENT_RECORD, shipmentId,
                quantity, unit, null, refKey, null);
    }

    public LineageRecordOutcome previewFinishedToShipment(String factoryId, String finishedBatchId,
            String shipmentId, BigDecimal quantity, String unit, String refKey, Map<String,Object> extraMeta) {
        Map<String,Object> meta=new HashMap<>();
        if(extraMeta!=null)meta.putAll(extraMeta);
        meta.put(META_SHIPMENT_CARRIER,SHIPMENT_CARRIER_SALES_DELIVERY);
        return preview(factoryId,EDGE_FINISHED_TO_SHIPMENT,NODE_FINISHED_BATCH,finishedBatchId,
                NODE_SHIPMENT_RECORD,shipmentId,quantity,unit,null,refKey,meta);
    }

    /**
     * 构造一条待写的边；入参不足以构成一条边时返回 {@code null}（调用方记 SKIPPED）。
     *
     * <p>⛔ 缺 id 时不兜底造一个 —— 那是假数据，比没有边糟得多。
     */
    private BatchLineageEdge buildEdge(String factoryId, String edgeType,
                                       String sourceType, String sourceId,
                                       String targetType, String targetId,
                                       BigDecimal quantity, String unit, Long operatorId,
                                       String refKey, Map<String, Object> extraMeta) {
        if (isBlank(factoryId) || isBlank(sourceId) || isBlank(targetId)) {
            // 「这条业务记录不构成一条边」是合法状态（例：不挂生产批次的手工出库）。
            log.debug("[lineage] 缺少构成边的 id, 不写边: factory={} edge={} source={}/{} target={}/{}",
                    factoryId, edgeType, sourceType, sourceId, targetType, targetId);
            return null;
        }
        if (quantity != null && quantity.signum() <= 0) {
            // 负数/零是冲销或调整，血缘边是 append-only 的，写不进「减少」这个语义。
            log.debug("[lineage] 数量非正({}), 不写边(append-only 表达不了冲销): edge={} {}→{}",
                    quantity, edgeType, sourceId, targetId);
            return null;
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
        return edge;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

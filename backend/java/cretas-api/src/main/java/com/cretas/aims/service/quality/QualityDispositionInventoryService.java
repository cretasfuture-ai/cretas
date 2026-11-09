package com.cretas.aims.service.quality;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 质检处置 → 库存桥接服务 (2026-07-05).
 *
 * <p><b>问题</b>: {@code QualityDispositionController} 的「放行 (RELEASE)」原本只写
 * {@code DecisionAuditLog} (记录人的决策) 却<b>从不翻转库存状态</b> —— 一个 QC 失败被 #1229
 * 隔离 (DEFECTIVE) 的批次即使质检经理放行, 依旧停留在 DEFECTIVE 不可售, 放行变成 no-op。
 * 本服务把「人的处置决策」真正<b>执行到库存上</b>。
 *
 * <h3>批次解析 (只动 <b>本次质检</b> 隔离的批次, 不越权释放其他批次)</h3>
 * <ul>
 *   <li><b>成品</b> (镜像 #1229 反向): {@code QualityInspection.productionBatchId →
 *       ProductionBatch.productionPlanId → 该计划下的 DEFECTIVE FinishedGoodsBatch} (不比 SKU, Q2)。
 *       成品批号是 FG-{计划}-S{序号}-{产品} / FG-AUTO-…, 永远不等于生产批号 PB-…, 所以<b>不能</b>
 *       按批号对: 此前那样写, 放行在真实数据上一个成品都翻不动 (D-S302-01)。成品表没有指向生产批次的
 *       外键, 隔离按计划做, 解除也按计划做; 同计划还有不合格未解除的批次就继续隔离。要区分批次时 (报废 / 召回批次的货
 *       留下) 按血缘边 / 批号判归属, 分不清就整个计划不放 (Q2, 见 releaseFinishedGoods)。</li>
 *   <li><b>原料</b> (仅当质检 material-scoped): {@code QualityInspection.materialBatchId →
 *       MaterialBatch}。</li>
 * </ul>
 *
 * <h3>动作语义 (食品安全: 只有 RELEASE / CONDITIONAL_RELEASE 把批次放回可售)</h3>
 * <table border=1>
 *   <tr><th>动作</th><th>成品 (FG)</th><th>原料 (MaterialBatch)</th></tr>
 *   <tr><td>RELEASE / CONDITIONAL_RELEASE</td><td>DEFECTIVE → AVAILABLE (需检产品 → 待检)</td><td>→ AVAILABLE</td></tr>
 *   <tr><td>SCRAP</td><td>可售 / 待检 → DEFECTIVE (FG 无 SCRAPPED 状态; 物理报废 + DisposalRecord
 *       走处置/报损 UI 人工流, 本服务不重复处理); 复核通过且计划里别的批次都已解除时, 别的批次的成品一并放行 (Q2)
 *       </td><td>DEFECTIVE → SCRAPPED (终态)</td></tr>
 *   <tr><td>REWORK</td><td>可售 / 待检 → DEFECTIVE</td><td>不动</td></tr>
 *   <tr><td>HOLD / SPECIAL_APPROVAL</td><td>不动 (保持隔离)</td><td>不动</td></tr>
 * </table>
 *
 * <h3>批次质量状态 (Owner 2026-11-04: 不合格批次只能走处置和复核改状态, 批次和成品不许分叉)</h3>
 * 批次状态由这里写, 与成品翻转同一事务: RELEASE → PASSED, CONDITIONAL_RELEASE → PARTIAL_PASS,
 * REWORK → REWORK_REQUIRED, SCRAP → SCRAPPED, HOLD / SPECIAL_APPROVAL 不动。
 *
 * <h3>审批门禁</h3>
 * 库存翻转只在<b>最终执行</b>时发生 —— 调用方 {@code QualityDispositionRuleServiceImpl.executeDisposition}
 * 仅在<b>直接执行分支</b> (非 approval-initiated 分支) 调用本服务; 未审批的申请 (applyDisposition 只建
 * PENDING 审计日志) 或被拒绝的审批 (approveDisposition approved=false 不调 executeDisposition) <b>不会</b>
 * 触发翻转。
 *
 * <h3>幂等 + 食安正确性</h3>
 * <ul>
 *   <li>成品只把 <b>DEFECTIVE</b> 翻回 AVAILABLE —— 重复审批时已是 AVAILABLE 的批次被跳过, 不会双翻。</li>
 *   <li>原料翻转经 {@link #evaluateMaterialFlip} 守卫: 源状态==目标 → 幂等跳过; 终态 → 拒绝。</li>
 *   <li>只解析<b>本次质检</b>关联的批次 (plan-scoped + material-scoped), 不越权释放兄弟批次。</li>
 * </ul>
 *
 * <p>销售退货使用独立 RTN 批号，不随原生产批次放行；同计划报废 / 召回批次的成品不释放, 质检放行时单独判了不合格的
 * 成品批也不释放, 工序时限超时复检判不合格的成品批 (Q7) 也不释放 —— 这几类留在隔离里, 数量写进
 * {@link InventoryDispositionResult#getFinishedGoodsHeld()} 如实告诉操作人。
 *
 * <p>把成品写成 DEFECTIVE 的全部入口 (2026-11-09 数清 7 处) 与放行的关系:
 * 质检判 FAILED / 处置判返工报废的计划隔离 ({@code FinishedGoodsQuarantine})、先判不合格后入库
 * ({@code QcInspectionPolicy.finishedGoodsStatusForOutput}、小结 {@code InterimSettleServiceImpl}) —— 这三处是
 * 计划级质检隔离, 正是放行要解除的; 销售退货子批 ({@code ReturnOrderServiceImpl}, RTN) / 质检放行判不合格
 * ({@code QcReleaseService}) / 工序时限判废 ({@code ProcessTimeLimitService.writeOff}) —— 这三处是对某一批货
 * 本身的结论, 放行一律不解除。召回冻结 ({@code FactoryRecallService.create}) 同样不解除: 本批召回中整个放行 409,
 * 同计划任一批次有过召回 (进行中或已完成) 时成品一律留在隔离 (Q4)。
 *
 * @since 2026-07-05 (feat/quality-disposition-inventory-bridge)
 */
@Slf4j
@Service
public class QualityDispositionInventoryService {

    /**
     * 可翻转的原料<b>源</b>状态白名单 (single source of truth, {@link com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool}
     * 共用)。DEPLETED / USED_UP / EXPIRED / SCRAPPED 等终态不在其中。
     * AVAILABLE / DEFECTIVE 作为可互转的源状态在 {@link #evaluateMaterialFlip} 中单独放行。
     */
    public static final Set<MaterialBatchStatus> RELEASABLE_FROM = Set.of(
            MaterialBatchStatus.INSPECTING,
            MaterialBatchStatus.FRESH,
            MaterialBatchStatus.FROZEN,
            MaterialBatchStatus.IN_STOCK,
            MaterialBatchStatus.RESERVED
    );

    /** 纯函数守卫的翻转判定结果 (无副作用)。 */
    public enum FlipDecision {
        /** 可翻转。 */
        FLIP,
        /** 源状态已是目标状态 (幂等跳过)。 */
        ALREADY_IN_TARGET,
        /** 源状态为终态, 不可翻转。 */
        INVALID_TERMINAL,
        /**
         * 缺口 11 (审查低 10): 逐道录入的在制批次 (sourceDocType=PRODUCTION_BATCH) 处于待检 ——
         * 只能走「质检放行」({@code QcReleaseService}: 查 CCP 偏离、留放行记录), 不许在这里直接放成可用。
         */
        QC_RELEASE_REQUIRED
    }

    public static final String QC_RELEASE_REQUIRED_MESSAGE =
            "这是生产过程中的在制批次，正在待检：请到「质量管理 → 质检放行」处理（会先核对关键控制点偏离并留下放行记录）";

    /**
     * 带批次身份的守卫: 在制批次的待检只能经质检放行解除; 其余同 {@link #evaluateMaterialFlip(MaterialBatchStatus, MaterialBatchStatus)}。
     */
    public static FlipDecision evaluateMaterialFlip(MaterialBatch batch, MaterialBatchStatus target) {
        MaterialBatchStatus current = batch == null ? null : batch.getStatus();
        if (batch != null && current == MaterialBatchStatus.INSPECTING && target == MaterialBatchStatus.AVAILABLE
                && com.cretas.aims.service.quality.release.QcInspectionGuard.WIP_SOURCE_DOC_TYPE
                .equals(batch.getSourceDocType())) {
            return FlipDecision.QC_RELEASE_REQUIRED;
        }
        return evaluateMaterialFlip(current, target);
    }

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * 成品/生产批次 repo — {@code required=false} 镜像 {@code QualityInspectionServiceImpl}, 使未 wire 这些
     * bean 的单测不破坏; 生产/成品未关联时桥接 graceful no-op。
     */
    @Autowired(required = false)
    private ProductionBatchRepository productionBatchRepository;

    @Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Autowired
    private FactoryRecallService factoryRecallService;

    /** 放行时认出「质检放行单独判了不合格」的成品批: 那是对这批货本身的结论, 处置批次不能把它洗掉。 */
    @Autowired
    private com.cretas.aims.repository.quality.QcReleaseRecordRepository qcReleaseRecordRepository;

    /**
     * 认出「工序时限超时、复检判不合格」写成 DEFECTIVE 的成品 (缺口 14 {@code ProcessTimeLimitService.writeOff})。
     * 那是对这批货本身的判废, 与计划级质检隔离无关, 计划放行不能把它放回可售 (Q7, 2026-11-09)。
     */
    @Autowired
    private com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository processTimeLimitEventRepository;

    /** 成品归属 (Q2): 血缘边 PRODUCTION_TO_FINISHED 是第一依据, 见 {@link #attribute}。 */
    @Autowired
    private BatchLineageEdgeRepository batchLineageEdgeRepository;

    /** 需检产品解除隔离后回待检, 走质检放行, 不直接可售。未注入 (单测) ⇒ 不叠加这一层。 */
    @Autowired(required = false)
    private com.cretas.aims.service.quality.release.QcInspectionPolicy qcInspectionPolicy;

    // ==================== 共享纯函数守卫 (ReleaseDecisionTool 复用, 防漂移) ====================

    /**
     * 原料批次状态翻转的<b>纯函数</b>守卫 (无副作用, 不持久化)。判定给定当前状态能否翻到目标状态。
     *
     * <p>ReleaseDecisionTool (AI-chat 放行/退货) 与本桥接 (放行/退货/报废) 共用此判定, 保证白名单/终态/
     * 幂等逻辑<b>唯一实现</b>不漂移。持久化由各自 repo 负责 (职责分离)。
     *
     * @param current 当前状态 (可为 null)
     * @param target  目标状态 (AVAILABLE 放行 / DEFECTIVE 退货 / SCRAPPED 报废)
     */
    public static FlipDecision evaluateMaterialFlip(MaterialBatchStatus current, MaterialBatchStatus target) {
        if (current == target) {
            return FlipDecision.ALREADY_IN_TARGET;
        }
        // 终态守卫: 不在白名单, 且不是 AVAILABLE/DEFECTIVE 这两个可互转源 → 终态不可改
        if (current != null && !RELEASABLE_FROM.contains(current)
                && current != MaterialBatchStatus.AVAILABLE
                && current != MaterialBatchStatus.DEFECTIVE) {
            return FlipDecision.INVALID_TERMINAL;
        }
        return FlipDecision.FLIP;
    }

    /** 这条质检对应的批次是否不合格未解除 (放行它要走复核, 不能直接执行)。 */
    public boolean isAwaitingDisposition(QualityInspection inspection) {
        ProductionBatch pb = loadBatch(inspection);
        return pb != null && pb.getQualityStatus() != null && pb.getQualityStatus().isDispositionLocked();
    }

    private ProductionBatch loadBatch(QualityInspection inspection) {
        if (productionBatchRepository == null || inspection == null || inspection.getProductionBatchId() == null) {
            return null;
        }
        return productionBatchRepository.findById(inspection.getProductionBatchId())
                .filter(pb -> Objects.equals(inspection.getFactoryId(), pb.getFactoryId()))
                .orElse(null);
    }

    private String writeBatchStatus(ProductionBatch pb, QualityStatus target) {
        if (pb == null) {
            return null;
        }
        if (pb.getQualityStatus() != target) {
            pb.setQualityStatus(target);
            productionBatchRepository.save(pb);
        }
        return target.name();
    }

    /** 先给本计划成品加行锁 (按 id 顺序), 再做隔离 / 放行 —— 与召回冻结、时限判废串行。 */
    private void lockPlanGoods(ProductionBatch pb) {
        if (pb == null || finishedGoodsBatchRepository == null || pb.getProductionPlanId() == null) {
            return;
        }
        finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdForUpdate(pb.getFactoryId(), pb.getProductionPlanId());
    }

    /** 返工 / 报废: 批次已判定不能卖, 同计划还可售 / 待检的成品一起隔离, 否则批次和成品分叉。 */
    private void quarantineFinishedGoods(ProductionBatch pb) {
        if (pb == null || finishedGoodsBatchRepository == null) {
            return;
        }
        FinishedGoodsQuarantine.quarantinePlan(finishedGoodsBatchRepository, pb.getFactoryId(),
                pb.getProductionPlanId());
    }

    // ==================== 桥接主入口 ====================

    /**
     * 把一次质检处置<b>最终执行</b>时的决策映射到库存翻转。只由
     * {@code QualityDispositionRuleServiceImpl.executeDisposition} 的直接执行分支调用 (非 approval-initiated)。
     *
     * @param inspection 质检记录 (提供 productionBatchId / materialBatchId 定位隔离批次)
     * @param action     已确定要执行的处置动作
     * @param reviewed   这次处置是不是另一人复核通过后执行的 ({@code executeApprovedDisposition})。
     *                   ⛔ 成品只在复核通过的处置里解除隔离 (Q4, 2026-11-09): 未锁定批次的直接放行只改批次状态,
     *                   不翻转计划里的任何成品 —— 否则一次无人复核的放行就把整个计划的隔离成品放回可售。
     *                   故意没有不带这个参数的重载: 调用方必须说清楚, Mockito 的 {@code never()} 也不会因为
     *                   落在另一个重载上而假绿。
     * @return 翻转结果 (供日志/审计, 不改变调用方原有 DispositionExecutionResult 结构)
     */
    @Transactional
    public InventoryDispositionResult applyDisposition(QualityInspection inspection, DispositionAction action,
                                                       boolean reviewed) {
        if (inspection == null || action == null) {
            return InventoryDispositionResult.builder().action(action).build();
        }

        switch (action) {
            case RELEASE:
            case CONDITIONAL_RELEASE: {
                if (inspection.getProductionBatchId() != null && factoryRecallService != null) {
                    factoryRecallService.assertReleaseAllowed(inspection.getFactoryId(), inspection.getProductionBatchId());
                }
                ProductionBatch pb = loadBatch(inspection);
                if (pb != null && pb.getQualityStatus() == QualityStatus.SCRAPPED) {
                    throw new com.cretas.aims.exception.BusinessException(409, "批次已判报废，不能放行")
                            .withCode("QUALITY_BATCH_SCRAPPED")
                            .withHint("报废是终态；实物按废弃处理流程处理");
                }
                String batchStatus = writeBatchStatus(pb, action == DispositionAction.RELEASE
                        ? QualityStatus.PASSED : QualityStatus.PARTIAL_PASS);
                FinishedGoodsRelease fg = releaseFinishedGoods(inspection, pb, reviewed);
                FlipDecision mat = flipMaterial(inspection, MaterialBatchStatus.AVAILABLE);
                InventoryDispositionResult r = InventoryDispositionResult.builder()
                        .action(action)
                        .finishedGoodsReleased(fg.released())
                        .finishedGoodsHeld(fg.held())
                        .finishedGoodsNotice(fg.notice())
                        .batchQualityStatus(batchStatus)
                        .materialFlip(mat)
                        .build();
                log.info("质检处置库存桥接 — {} inspectionId={} 批次→{}, 成品解除隔离 {} 个, 仍隔离 {} 个, 原料={}",
                        action, inspection.getId(), batchStatus, fg.released(), fg.held(), mat);
                return r;
            }
            case SCRAP: {
                // 成品无 SCRAPPED 状态 → DEFECTIVE (物理报废走处置/报损 UI 人工流, 不重复处理)。
                // 原料 DEFECTIVE→SCRAPPED (真实终态)。二者都<b>不</b>放回 AVAILABLE。
                ProductionBatch pb = loadBatch(inspection);
                String batchStatus = writeBatchStatus(pb, QualityStatus.SCRAPPED);
                lockPlanGoods(pb);
                quarantineFinishedGoods(pb);
                // Q2 (Owner 2026-11-09): 报废批次不算「未解除」。这次报废若是计划里最后一个待处置的批次, 其余批次的成品
                // 现在就该放行 (报废批的货留在隔离) —— 否则先放行、后报废的顺序下, 别的批次的成品永远没人放。
                FinishedGoodsRelease fg = releaseFinishedGoods(inspection, pb, reviewed);
                FlipDecision mat = flipMaterial(inspection, MaterialBatchStatus.SCRAPPED);
                log.info("质检处置库存桥接 — SCRAP inspectionId={} 批次→{}, 其余批次成品解除隔离 {} 个, 仍隔离 {} 个, 原料报废={}",
                        inspection.getId(), batchStatus, fg.released(), fg.held(), mat);
                return InventoryDispositionResult.builder()
                        .action(action)
                        .finishedGoodsReleased(fg.released())
                        .finishedGoodsHeld(fg.held())
                        .finishedGoodsNotice(fg.notice())
                        .batchQualityStatus(batchStatus)
                        .materialFlip(mat)
                        .build();
            }
            case REWORK: {
                ProductionBatch pb = loadBatch(inspection);
                String batchStatus = writeBatchStatus(pb, QualityStatus.REWORK_REQUIRED);
                quarantineFinishedGoods(pb);
                log.info("质检处置库存桥接 — REWORK inspectionId={} 批次→{}", inspection.getId(), batchStatus);
                return InventoryDispositionResult.builder().action(action).batchQualityStatus(batchStatus).build();
            }
            default:
                // HOLD / SPECIAL_APPROVAL — 还没有结论, 批次和库存都不动。
                log.debug("质检处置库存桥接 — {} inspectionId={} 无状态变化", action, inspection.getId());
                return InventoryDispositionResult.builder().action(action).build();
        }
    }

    // ==================== 成品 (FG) — 镜像 #1229 反向 ====================

    /**
     * 解除<b>本次质检</b>所在计划的成品隔离。只动 DEFECTIVE, 已放出去的不重复翻; FROZEN / 待检 / 过期等别的状态
     * 一律不碰 (它们不是质检隔离)。留在隔离里的算进 held 并在 notice 里说原因。
     * 销售退货子批次 (RTN) 是独立的货, 既不解除也不计数。
     *
     * <p>Q4 (2026-11-09): 成品先按计划加行锁再读, 判断用的是锁后的状态; 只有复核通过的处置才翻转。
     *
     * <p>Q2 (Owner 2026-11-09 裁定):
     * <ol>
     *   <li>计划里所有批次都解除隔离后, 一次放行这个计划的全部成品 (不再比 SKU: 联产计划的副产品同样是这个计划的货);</li>
     *   <li>还有批次没解除 ⇒ 都不放, 如实说还有几批;</li>
     *   <li>报废批次不算「未解除」: 它的成品留在隔离, 其余放行; 召回过的批次同样处理 (Q4: 有召回的不放);</li>
     *   <li>要按批次区分 (有报废 / 召回的批次) 而分不清成品属于哪个批次 ⇒ 整个计划不放并如实提示, 不猜。</li>
     * </ol>
     * 成品归属见 {@link #attribute}。
     */
    private FinishedGoodsRelease releaseFinishedGoods(QualityInspection inspection, ProductionBatch pb,
                                                      boolean reviewed) {
        if (finishedGoodsBatchRepository == null || pb == null) {
            return FinishedGoodsRelease.NONE;
        }
        String planId = pb.getProductionPlanId();
        if (planId == null || planId.isBlank()) {
            return FinishedGoodsRelease.NONE;
        }
        String factoryId = inspection.getFactoryId();
        List<FinishedGoodsBatch> candidates = finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId).stream()
                .filter(fg -> Objects.equals(factoryId, fg.getFactoryId())
                        && FinishedGoodsBatch.Status.DEFECTIVE.equals(fg.getStatus()) && !isReturnChild(fg))
                .toList();
        if (candidates.isEmpty()) {
            return FinishedGoodsRelease.NONE;
        }
        if (!reviewed) {
            return FinishedGoodsRelease.allHeld(candidates.size(), "成品解除隔离要经另一人复核通过的处置，这次直接放行只改了批次状态");
        }
        List<ProductionBatch> planBatches = planBatches(factoryId, planId, pb);
        long uncleared = planBatches.stream()
                .filter(other -> !Objects.equals(other.getId(), pb.getId()) && other.getQualityStatus() != null
                        && other.getQualityStatus().isDispositionLocked()
                        && other.getQualityStatus() != QualityStatus.SCRAPPED)
                .count();
        if (uncleared > 0) {
            return FinishedGoodsRelease.allHeld(candidates.size(),
                    "同计划还有 " + uncleared + " 个批次没有解除隔离，等这些批次处置完，整个计划的成品一次放行");
        }
        // 报废 / 召回过的批次: 它们的成品留在隔离, 其余放行 —— 这时才需要分清每个成品属于哪个批次
        Map<Long, String> keepBack = new LinkedHashMap<>();
        for (ProductionBatch b : planBatches) {
            if (b.getId() == null) {
                continue;
            }
            if (b.getQualityStatus() == QualityStatus.SCRAPPED) {
                keepBack.put(b.getId(), "报废批次的成品");
            } else if (factoryRecallService.hasRecallRecord(factoryId, b.getId())) {
                keepBack.put(b.getId(), "召回批次的成品");
            }
        }
        Ownership ownership = attribute(factoryId, candidates, planBatches);
        if (ownership.outsidePlan() != null) {
            return FinishedGoodsRelease.allHeld(candidates.size(),
                    ownership.outsidePlan() + "，分不清成品归属，整个计划的成品先不放行");
        }
        if (!keepBack.isEmpty() && ownership.unknown() != null) {
            return FinishedGoodsRelease.allHeld(candidates.size(), "本计划有报废或召回的批次，但" + ownership.unknown()
                    + "，分不清哪些成品属于它们，整个计划的成品先不放行");
        }
        Map<String, Integer> heldBy = new LinkedHashMap<>();
        int released = 0;
        for (FinishedGoodsBatch fg : candidates) {
            String reason = null;
            for (ProductionBatch b : planBatches) {
                String kept = b.getId() == null ? null : keepBack.get(b.getId());
                // 归属 (血缘 / 批号 / 单批计划) 指到它, 或成品批号就是它的批号 (召回冻结认的正是批号)
                if (kept != null && (ownership.of(fg).contains(b.getId())
                        || Objects.equals(b.getBatchNumber(), fg.getBatchNumber()))) {
                    reason = kept;
                    break;
                }
            }
            if (reason == null && rejectedAtQcRelease(factoryId, fg)) {
                reason = "质检放行判不合格的成品";
            }
            if (reason == null && rejectedByTimeLimit(factoryId, fg)) {
                reason = "工序超时判废的成品";
            }
            if (reason != null) {
                heldBy.merge(reason, 1, Integer::sum);
                continue;
            }
            fg.setStatus(statusAfterRelease(factoryId, planId, fg));
            finishedGoodsBatchRepository.save(fg);
            released++;
        }
        int held = heldBy.values().stream().mapToInt(Integer::intValue).sum();
        String notice = held == 0 ? null : "其中" + heldBy.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + " 个").collect(java.util.stream.Collectors.joining("、"));
        return new FinishedGoodsRelease(released, held, notice);
    }

    /** 计划下本厂的全部生产批次, 本批一定在里面 (用调用方手上这份, 它带着这次刚写的状态)。 */
    private List<ProductionBatch> planBatches(String factoryId, String planId, ProductionBatch pb) {
        List<ProductionBatch> result = new ArrayList<>();
        result.add(pb);
        for (ProductionBatch b : productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId)) {
            if (Objects.equals(factoryId, b.getFactoryId()) && !Objects.equals(b.getId(), pb.getId())) {
                result.add(b);
            }
        }
        return result;
    }

    /**
     * 成品归属 (Owner 2026-11-09): 先用血缘边 PRODUCTION_TO_FINISHED; 边不全 (有的成品有边、有的没有) 或指向计划外
     * 算分不清; 一条边都没有时, 批号相同或计划只有一批算确定。⛔ 不按 SKU、不按时间先后猜。
     *
     * @return outsidePlan != null: 有边指向本计划以外的生产批次 (无论要不要区分都不放);
     *         unknown != null: 有成品分不清归属 (只在需要区分时才挡)
     */
    private Ownership attribute(String factoryId, List<FinishedGoodsBatch> goods, List<ProductionBatch> planBatches) {
        if (batchLineageEdgeRepository == null) {
            throw new IllegalStateException("血缘边仓储未注入，无法判断成品属于哪个生产批次");
        }
        Map<String, FinishedGoodsBatch> byId = new LinkedHashMap<>();
        goods.forEach(fg -> byId.put(fg.getId(), fg));
        Set<Long> planIds = new HashSet<>();
        planBatches.forEach(b -> planIds.add(b.getId()));
        Map<String, Set<Long>> edges = new HashMap<>();
        String outsidePlan = null;
        for (BatchLineageEdge edge : batchLineageEdgeRepository.findByFactoryIdAndTargetIdIn(factoryId, byId.keySet())) {
            if (!LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED.equals(edge.getEdgeType())
                    || !LineageEdgeRecorder.NODE_FINISHED_BATCH.equals(edge.getTargetType())
                    || !byId.containsKey(edge.getTargetId())) {
                continue;
            }
            Long source = LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(edge.getSourceType())
                    ? parseLong(edge.getSourceId()) : null;
            if (source == null || !planIds.contains(source)) {
                outsidePlan = "成品批 " + byId.get(edge.getTargetId()).getBatchNumber() + " 的血缘指向本计划以外的生产批次";
                continue;
            }
            edges.computeIfAbsent(edge.getTargetId(), k -> new LinkedHashSet<>()).add(source);
        }
        Map<String, Set<Long>> owners = new HashMap<>();
        String unknown = null;
        for (FinishedGoodsBatch fg : goods) {
            Set<Long> fromEdges = edges.get(fg.getId());
            if (fromEdges != null) {
                owners.put(fg.getId(), fromEdges);
            } else if (!edges.isEmpty()) {
                unknown = "成品批 " + fg.getBatchNumber() + " 没有血缘记录而同计划别的成品有（血缘不全）";
            } else if (planBatches.size() == 1) {
                owners.put(fg.getId(), idSet(planBatches.get(0)));
            } else {
                ProductionBatch sameNumber = planBatches.stream()
                        .filter(b -> b.getBatchNumber() != null && b.getBatchNumber().equals(fg.getBatchNumber()))
                        .findFirst().orElse(null);
                if (sameNumber != null) {
                    owners.put(fg.getId(), idSet(sameNumber));
                } else {
                    unknown = "成品批 " + fg.getBatchNumber() + " 没有血缘记录，批号也对不上本计划的任何生产批次";
                }
            }
        }
        return new Ownership(owners, unknown, outsidePlan);
    }

    private static Set<Long> idSet(ProductionBatch batch) {
        return batch.getId() == null ? Set.of() : Set.of(batch.getId());
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Ownership(Map<String, Set<Long>> byGoods, String unknown, String outsidePlan) {
        Set<Long> of(FinishedGoodsBatch fg) {
            return byGoods.getOrDefault(fg.getId(), Set.of());
        }
    }

    private static boolean isReturnChild(FinishedGoodsBatch fg) {
        return fg.getReturnOrderItemId() != null
                || (fg.getBatchNumber() != null && fg.getBatchNumber().startsWith("RTN-"));
    }

    private boolean rejectedAtQcRelease(String factoryId, FinishedGoodsBatch fg) {
        var records = qcReleaseRecordRepository.findByTarget(factoryId,
                com.cretas.aims.entity.quality.QcReleaseRecord.TargetType.FINISHED_GOODS, fg.getId());
        return !records.isEmpty() && com.cretas.aims.entity.quality.QcReleaseRecord.Decision.REJECTED
                .equals(records.get(0).getDecision());
    }

    /**
     * 工序时限超时、复检判不合格 (事件 REJECTED) 的成品批。判废是对这批货本身的结论, 只要有一条就不放。
     * ⛔ 仓储没注入时不能当「没有判废」放过去 —— 那正是 Q7 的形状 (放行看不见判废来源)。
     */
    private boolean rejectedByTimeLimit(String factoryId, FinishedGoodsBatch fg) {
        if (processTimeLimitEventRepository == null) {
            throw new IllegalStateException("工序时限处置记录仓储未注入，无法判断成品是否已因超时判废");
        }
        return !processTimeLimitEventRepository.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(factoryId,
                com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService.TargetType.FINISHED_GOODS,
                fg.getId(), com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent.Status.REJECTED).isEmpty();
    }

    /** 需检产品 / 有未处理偏离: 回待检让质检放行 (缺口 11/13), 处置不替代那一步。 */
    private String statusAfterRelease(String factoryId, String planId, FinishedGoodsBatch fg) {
        if (qcInspectionPolicy == null) {
            return FinishedGoodsBatch.Status.AVAILABLE;
        }
        // ⛔ 不走 finishedGoodsStatusForOutput: 它见计划里有 SCRAPPED 批次就改回 DEFECTIVE ——
        //    报废批次不算「未解除」(Q2), 放不放的判断上面已经做完, 这里只叠加需检 / 未处理偏离的待检。
        boolean hold = qcInspectionPolicy.shouldHoldFinished(factoryId,
                new com.cretas.aims.service.quality.release.QcInspectionPolicy.OutputRef(fg.getProductTypeId(), null,
                        com.cretas.aims.service.quality.release.QcInspectionPolicy.batchNumbers(fg.getBatchNumber()),
                        planId, null));
        return com.cretas.aims.service.quality.release.QcInspectionPolicy.finishedGoodsStatusAfterOutput(
                FinishedGoodsBatch.Status.AVAILABLE, hold);
    }

    /** @param notice 有成品留在隔离时给操作人的原因 (中文); 没有 = null。 */
    private record FinishedGoodsRelease(int released, int held, String notice) {
        private static final FinishedGoodsRelease NONE = new FinishedGoodsRelease(0, 0, null);

        private static FinishedGoodsRelease allHeld(int held, String notice) {
            return new FinishedGoodsRelease(0, held, notice);
        }
    }

    // ==================== 原料 (MaterialBatch) — 与 ReleaseDecisionTool 共用守卫 ====================

    /**
     * 若质检是 material-scoped (materialBatchId 非空), 把该原料批次翻到 {@code target}。经共享纯函数守卫,
     * 幂等 + 终态保护。返回判定结果; 非 material-scoped 返回 null。
     */
    private FlipDecision flipMaterial(QualityInspection inspection, MaterialBatchStatus target) {
        String materialBatchId = inspection.getMaterialBatchId();
        if (materialBatchId == null || materialBatchId.isBlank()) {
            return null; // 非 material-scoped
        }
        MaterialBatch mb = materialBatchRepository
                .findByIdAndFactoryId(materialBatchId, inspection.getFactoryId())
                .orElse(null);
        if (mb == null) {
            log.warn("质检处置库存桥接 — materialBatchId={} 不存在或非本工厂 factory={}, 跳过原料翻转",
                    materialBatchId, inspection.getFactoryId());
            return null;
        }
        FlipDecision decision = evaluateMaterialFlip(mb, target);
        if (decision == FlipDecision.QC_RELEASE_REQUIRED) {
            log.warn("质检处置库存桥接 — 在制批次 {} 待检, 不在处置桥接里放行 (须走质检放行)", mb.getBatchNumber());
        }
        if (decision == FlipDecision.FLIP) {
            MaterialBatchStatus before = mb.getStatus();
            mb.setStatus(target);
            materialBatchRepository.save(mb);
            // 缺料实例据此把「质检中」挪成「已放行」(只是通知, 不影响这里的判定)
            com.cretas.aims.event.MaterialBatchReleasedEvent.publishIfBecameAvailable(eventPublisher, this, mb, before);
        }
        return decision;
    }

    /** 桥接结果 (供调用方日志/审计)。 */
    @Data
    @Builder
    public static class InventoryDispositionResult {
        private DispositionAction action;
        /** 解除隔离的成品批次数 (放行时)。 */
        private int finishedGoodsReleased;
        /** 放行后仍留在隔离里的成品批次数 (见 releaseFinishedGoods), 操作人要看到它。 */
        private int finishedGoodsHeld;
        /** 为什么还有成品留在隔离 (中文, 直接给操作人看); 没有成品留下 = null。 */
        private String finishedGoodsNotice;
        /** 这次处置写到批次上的质量状态; null = 没有批次或动作不改批次。 */
        private String batchQualityStatus;
        /** 原料翻转判定 (material-scoped 时非 null)。 */
        private FlipDecision materialFlip;
    }
}

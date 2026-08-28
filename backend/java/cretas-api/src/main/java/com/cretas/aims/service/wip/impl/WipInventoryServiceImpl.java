package com.cretas.aims.service.wip.impl;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.inventory.FeedUnitConverter;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.WorkflowByproductNodes;
import com.cretas.aims.service.workflow.WorkflowReworkNodes;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.ReworkPurityGuard;
import com.cretas.aims.service.wip.WipCostAllocation;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.wip.WipLedgerEntries;
import com.cretas.aims.service.wip.WipSemiOutputPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WipInventoryServiceImpl implements WipInventoryService {

    private final SemiFinishedInventoryRepository wipRepo;
    private final SemiFinishedInventoryTransactionRepository txnRepo;
    private final ProductionReportRepository reportRepo;
    private final BatchLineageEdgeRepository lineageEdgeRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository workProcessRepo;
    private final ProductTypeRepository productTypeRepo;
    /** SP1-T4 Wave2 — Workflow(画布)任务的产出结构来源 (画布不写 WorkProcess.semiFinishedOutputCode)。 */
    private final ProductionWorkflowInstanceRepository workflowInstanceRepo;
    private final WorkflowTaskPortRepository workflowTaskPortRepo;
    /** 产品族自动识别 (以原料为主) — SFI 防呆过滤的"同族"信号来源, 就地识别零手填. */
    private final ProductFamilyResolver productFamilyResolver;
    /** SP3: 成本更新事件发布 — 异步回填+预警. */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * W8 BUG-SP1-NEW-ROW 修复: 自引用代理, 用于在 {@code REQUIRES_NEW} 子事务中创建新行,
     * 使 Spring 事务 AOP 代理生效 (同类内 this.method() 调用不走代理, 注解失效)。
     * {@code @Lazy} 打破构造期自引用循环。
     */
    @Autowired
    @Lazy
    private WipInventoryServiceImpl self;

    @Override
    public SemiFinishedInventory validateSourceWip(
            String factoryId, String sourceWipNo, BigDecimal inputQuantity, String inputUnit, Long excludeReportId) {
        if (sourceWipNo == null || sourceWipNo.isBlank()) {
            return null;
        }
        SemiFinishedInventory sourceWip = loadSourceWipForValidation(factoryId, sourceWipNo)
                .orElseThrow(() -> new BusinessException(404, "源半成品库存不存在: " + sourceWipNo)
                        .withHint("请重新选择要领用的上道半成品批次")
                        .withHintTarget("sourceWipNo"));
        if (inputQuantity == null) {
            throw new BusinessException(409, "领用半成品时必须填写本道投入量")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("已选择上道半成品，请填写本道实际投入量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
        validateUnit(sourceWip, inputUnit);
        validateAvailable(sourceWip, inputQuantity, pendingReserved(factoryId, sourceWipNo, excludeReportId));
        return sourceWip;
    }

    private java.util.Optional<SemiFinishedInventory> loadSourceWipForValidation(String factoryId, String sourceWipNo) {
        if (factoryId == null || factoryId.isBlank()) {
            return wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(sourceWipNo);
        }
        return wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, sourceWipNo);
    }

    @Override
    @Transactional
    public void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId) {
        if (report == null || task == null || task.getProductionBatchId() == null) {
            return;
        }
        if (Boolean.TRUE.equals(report.getCustomFields() == null ? null : report.getCustomFields().get("wipPosted"))) {
            log.info("Skip WIP posting for report {}: already posted", report.getId());
            return;
        }
        if (report.getSourceWipNo() != null && !report.getSourceWipNo().isBlank()
                && report.getInputQuantity() != null) {
            BigDecimal sourceWipQuantity = sourceWipQuantity(report);
            SemiFinishedInventory sourceWip = validateSourceWip(
                    factoryId, report.getSourceWipNo(), sourceWipQuantity, report.getInputUnit(), report.getId());
            consumeSourceWip(sourceWip, sourceWipQuantity, report, task, operatorId);
        }
        String outputKind = report.getOutputKind();
        // SP1: SEMI/BOTH → post semi-finished ledger; FINISHED/null(legacy) → existing WIP path
        boolean isSemi = "SEMI".equals(outputKind) || "BOTH".equals(outputKind);
        boolean isFinished = outputKind == null || "FINISHED".equals(outputKind) || "BOTH".equals(outputKind);

        // 多产出优先: semiOutputs 非空时逐条入账 (画布 1 进 N 出)。
        // ⛔ 为空时必须走原来的单产出路径 —— 旧版 App 与存量报工零回归。
        List<Map<String, Object>> multiOutputs = WipSemiOutputPlan.postableEntries(report.getSemiOutputs());
        boolean finishedPosts = isFinished && report.getOutputQuantity() != null
                && report.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0;

        // ═══ Critical 2 (2026-08-28 对抗性审计) ═══
        // RN 报工屏在有任何半成品产出时发的是 outputKind='BOTH' (YieldStepReportScreen.tsx:1590),
        // 同时照发 outputQuantity (:1608) ⇒ isSemi 与 isFinished 【同时】为真, 两个分支都跑。
        //
        // 两个分支同时跑本身【不是】缺陷 —— BOTH 的契约 (ProductionReport.outputKind javadoc) 就是
        // 「双产出」: outputQuantity 与 semiOutputs 是两个不同的量, 各入各的行。
        // 缺陷是【成本被两个分支各 claim 一次全额】: SEMI 侧按量分摊完 ¥100, FINISHED 侧又拿
        // reportTotalCost 算一次全额 ¥100 ⇒ 一份 ¥100 的报工入账 ¥200; 且回料那条的分母只有它自己
        // (50) ⇒ 单价 ¥2.00/kg 是良品的 19 倍 —— 正是 C-1 修掉的形状在两个分支之间原样保留。
        // 第三重后果: 两个分支各发一次 ProductionCostUpdatedEvent, 而消费方是 @Async +
        // 「costUnitPrice == null 才写」⇒ 哪条先到哪条赢。
        //
        // ⇒ 分摊的分母是【这份报工实际入账的全部量】: N 条 semi + (跑了的话) FINISHED 那条。
        //   FINISHED 排在最后一槽 ⇒ 尾数归它, N+1 份之和恰好等于一份报工的成本。
        // ⛔ semiOutputs 为空时 alloc 保持 null → 两条单产出路径逐字节不变。
        WipCostAllocation alloc = null;
        BigDecimal reportCost = null;
        boolean postedAnySemi = false;
        if (!multiOutputs.isEmpty()) {
            reportCost = reportTotalCost(factoryId, report, task);
            List<BigDecimal> slotQuantities = new ArrayList<>(multiOutputs.size() + 1);
            for (Map<String, Object> entry : multiOutputs) {
                slotQuantities.add(WipSemiOutputPlan.quantityOf(entry));
            }
            if (finishedPosts) {
                slotQuantities.add(report.getOutputQuantity());
            }
            alloc = WipCostAllocation.byQuantity(reportCost, slotQuantities);
            postedAnySemi = postMultiSemiOutputs(factoryId, report, task, operatorId, multiOutputs, alloc);
        } else if (isSemi) {
            postSemiOutputLedger(factoryId, report, task, operatorId);
        }

        if (finishedPosts) {
            BigDecimal rollLabor = report.getLaborCost();
            BigDecimal rollMaterial = report.getMaterialCost();
            if ("OUTPUT".equals(report.getReportKind())) {
                CostRollup rollup = outputRollupWithBatchInputMaterial(factoryId, task);
                rollLabor = rollup.laborCost();
                rollMaterial = rollup.materialCost();
            }
            // 多产出在场时这条只拿它那一槽 (最后一槽); alloc == null = 单产出路径, 自算全额 (零回归)。
            AllocatedCost finishedAllocated = alloc == null
                    ? null
                    : new AllocatedCost(alloc.costAt(multiOutputs.size()), alloc.unitCost());
            SemiFinishedInventory producedWip = upsertProducedWip(factoryId, report, task, rollLabor, rollMaterial,
                    operatorId, finishedAllocated);
            // W4 成本链修复: FINISHED/legacy 路径也发 SP3 成本事件 → 回填 SalesOrderItem.costUnitPrice.
            // 此前仅 SEMI 路径 (postSemiOutputLedger) 发事件, 导致整合/旧式报工 (output_kind=null,
            // F006 实际数据全走此路径) 即使算出 WIP unitCost 也永不回填 costUnitPrice → 财审 actualCost 永远 null。
            // 诚实 null 传播: unitCost 为 null (无工价/无料价) 时不发事件 (无成本可回填)。
            // ⛔ 多产出在场时这里不发 —— 一份报工一次事件, 统一在下面发。
            if (alloc == null && producedWip != null && producedWip.getUnitCost() != null
                    && task.getProductionBatchId() != null) {
                eventPublisher.publishEvent(new ProductionCostUpdatedEvent(
                        this,
                        factoryId,
                        task.getProductionBatchId(),
                        task.getProductTypeId(),
                        producedWip.getUnitCost(),
                        producedWip.getAccumulatedCost()
                ));
            }
            postedAnySemi |= producedWip != null;
        }

        // SP3: 一份报工 = 一次成本事件 (多产出路径)。带的是分摊后的单价 —— 各槽相同 ⇒ 与顺序无关, 确定性。
        // ⛔ 不发某一条 SFI 行的移动均价: 那取决于挑哪一行以及那行跟本次报工无关的历史批次,
        //    在 @Async 消费端就是非确定性的。
        // 一条都没真入账 (全被幂等跳过) 时不发 —— 与单产出路径「跳过就不发」一致。
        if (alloc != null && postedAnySemi && task.getProductionBatchId() != null) {
            eventPublisher.publishEvent(new ProductionCostUpdatedEvent(
                    this,
                    factoryId,
                    task.getProductionBatchId(),
                    task.getProductTypeId(),
                    alloc.unitCost(),
                    reportCost
            ));
        }
        markWipPosted(report);
    }

    /**
     * SP1: 半成品产出 ledger posting.
     *
     * <p>Rules enforced:
     * <ol>
     *   <li>R1 — SELECT FOR UPDATE on SemiFinishedInventory to prevent concurrent moving-average divergence.</li>
     *   <li>R2 — Runs inside caller's @Transactional (single transaction).</li>
     *   <li>R3 — SemiFinishedInventoryTransaction rows are immutable (no update, only REVERSE rows).</li>
     *   <li>R4 — semiCode/semiOutputQuantity null → skip gracefully (backward compat for old reports).</li>
     *   <li>Fool-Proof Rule 4 — idempotency: same (factoryId, semiCode, IN) guard against double-post.</li>
     * </ol>
     *
     * <p>Moving-average cost formula:
     * <pre>newUnitCost = (oldQty × oldUnitCost + inQty × inUnitCost) / (oldQty + inQty)</pre>
     * Scale-4, ROUND_HALF_UP.  inUnitCost = (laborCost + materialCost) / semiOutputQuantity (honest null if zero qty).
     */
    private void postSemiOutputLedger(String factoryId, ProductionReport report,
                                       WorkProcessTask task, Long operatorId) {
        // allocated == null ⇒ 单产出路径: 自算全额 + 自己发事件, 逐字节零回归 (见 postSemiOutputLedgerFor)。
        // reworkProductTypeId == null: 单产出是旧版工序管理那条路 (semiCode 来自
        // WorkProcess.semiFinishedOutputCode, 不是画布端口), 本来就没有回料概念。
        postSemiOutputLedgerFor(factoryId, report, task, operatorId,
                report.getSemiCode(), report.getSemiOutputQuantity(), null, null);
    }

    /**
     * 画布 1 进 N 出: 把<b>一份</b>报工的成本按量分摊到 N 条产出上, 逐条入账, 事件只发一次。
     *
     * <h2>C-1 修复 (2026-08-28 整分支终审)</h2>
     *
     * <p>修复前是 for 循环对每条各调一次 {@link #postSemiOutputLedgerFor}, 而它内部算出的
     * {@code totalCost} <b>与条目无关</b> —— 每条都重算成同一个<b>全额</b>值。金检报工
     * (良品 950 / 回料 50, 本道成本 ¥10,000) 于是变成:
     * <pre>
     *   良品 950 kg  unit_cost_at_txn = 10.53   accumulated_cost += 10,000
     *   回料  50 kg  unit_cost_at_txn = 200.00  accumulated_cost += 10,000   ← 再来一份全额
     * </pre>
     * (1) ¥10,000 记成 ¥20,000; (2) 回料单价是良品的 19 倍, 而回料按配方要占下一批的 5%~19.5%,
     * 按移动加权领进去下一批材料成本翻数倍; (3) 每条各发一次 {@link ProductionCostUpdatedEvent},
     * 而 {@code OrderCostBackfillListener} 是 {@code @Async} + 「{@code costUnitPrice == null} 才写」
     * ⇒ 哪条先到哪条赢, 销售订单行的成本单价非确定性 (回料那条先到就是 ¥200/kg)。
     *
     * <h2>口径</h2>
     *
     * <p>按量分摊: {@code thisEntryCost = totalCost × thisQty / Σqty} ⇒ 各条
     * {@code inUnitCost = totalCost / Σqty} <b>相同</b>。这正符合业务事实 —— 回料是几乎全价的肉,
     * 它和良品是同一锅料出来的。
     *
     * <p>尾数归<b>最后一条</b>: 前 n-1 条按 scale-2 (= {@code accumulated_cost} 的列精度) HALF_UP 分摊,
     * 最后一条拿 {@code totalCost − Σ已分摊}, 保证 N 条之和<b>恰好</b>等于 totalCost, 一分不多一分不少。
     * ⛔ 顺带保证单条 (N=1) 时唯一那条拿到全额、无舍入损失 ⇒ 与单产出路径逐字节一致。
     *
     * <p>事件只发一次, 带的是<b>分摊后的单价</b>: 各条相同 ⇒ 与条目顺序无关, 确定性。
     * ⛔ 不发某一条 SFI 行的移动均价 —— 那取决于挑哪一行、以及那行跟本次报工无关的历史批次,
     * 在 {@code @Async} 消费端就是非确定性的 (这正是本次要修的第三重后果)。
     *
     * <p>诚实 null: {@code totalCost} 读不出来时逐条 {@code entryCost}/{@code unitCost} 一律 null,
     * ⛔ 不摊成 0 —— 一个假的 0 会顺着移动加权把整条链的单价摊薄, 而且不报错。
     */
    private boolean postMultiSemiOutputs(String factoryId, ProductionReport report, WorkProcessTask task,
                                         Long operatorId, List<Map<String, Object>> multiOutputs,
                                         WipCostAllocation alloc) {
        // 「这一条是不是回料」——判据是画布 (WorkflowReworkNodes), 不是请求体里那个同名字段。
        // 整份报工算一次 (循环外), ⛔ 不要逐条各查一次库。
        Map<String, String> reworkCodeToProductType = reworkSemiCodes(factoryId, task);

        boolean postedAny = false;
        for (int i = 0; i < multiOutputs.size(); i++) {
            Map<String, Object> entry = multiOutputs.get(i);
            String semiCode = WipSemiOutputPlan.semiCodeOf(entry);
            postedAny |= postSemiOutputLedgerFor(factoryId, report, task, operatorId,
                    semiCode, WipSemiOutputPlan.quantityOf(entry),
                    new AllocatedCost(alloc.costAt(i), alloc.unitCost()),
                    semiCode == null ? null : reworkCodeToProductType.get(semiCode));
        }
        // ⛔ 事件不在这里发 —— 一份报工一次, 由 postApprovedOutput 在 SEMI/FINISHED 两个分支都跑完之后统一发。
        //   (Critical 2: 在这里发会与 FINISHED 分支各发一次, @Async 消费端就是哪条先到哪条赢)
        return postedAny;
    }

    /**
     * 本任务的画布产出端口里, 被标记为<b>回料</b>的那些半成品:
     * {@code ProductType.code} (= {@code semiCode}) → {@code ProductType.id}。
     *
     * <p><b>判据来源唯一</b>: 画布 {@code nodes_json} 的 {@code data.isRework}
     * ({@link WorkflowReworkNodes}) 经 {@code workflow_task_ports.material_node_id} 反查。
     * ⛔ <b>不读</b> {@code semi_outputs} 条目里客户端带上来的同名字段 —— 那是给 RN 算
     * {@code defectQuantity} 用的展示值, 未经服务端校验; 拿它当判据等于让客户端自行决定
     * 哪些料可以绕过投料下拉的防回锅闸。
     *
     * <p>值 (ProductType.id) 是给回料行的 {@code productTypeId} 用的 —— 回料行记的是<b>回料自己</b>
     * 的产品, 不是批次的成品 (见 {@link #postSemiOutputLedgerFor} 里的说明)。顺手带出来,
     * ⛔ 不再按 code 反查第二次。
     *
     * <p>画布之外的路径 (旧版工序管理 / 无 workflow 实例) 返回空 Map —— 那些路径本来就没有回料概念,
     * 也不多花一次查询 (只在 {@code semiOutputs} 非空即画布多产出路径上被调用)。
     */
    private Map<String, String> reworkSemiCodes(String factoryId, WorkProcessTask task) {
        if (task.getProductionBatchId() == null) {
            return Map.of();
        }
        ProductionWorkflowInstance instance = workflowInstanceRepo
                .findByFactoryIdAndProductionBatchId(factoryId, task.getProductionBatchId())
                .orElse(null);
        if (instance == null) {
            return Map.of();
        }
        Set<String> reworkNodeIds = WorkflowReworkNodes.reworkMaterialNodeIds(instance.getNodesJson());
        if (reworkNodeIds.isEmpty()) {
            return Map.of();
        }
        List<String> reworkSkuIds = workflowTaskPortRepo
                .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId()).stream()
                .filter(p -> WorkflowTaskPort.Direction.OUTPUT.equals(p.getDirection()))
                .filter(p -> WORKFLOW_MATERIAL_KIND_SEMI_FINISHED.equals(p.getMaterialKind()))
                .filter(p -> task.getId() != null && task.getId().equals(p.getTaskId()))
                .filter(p -> reworkNodeIds.contains(p.getMaterialNodeId()))
                .map(WorkflowTaskPort::getSkuId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (reworkSkuIds.isEmpty()) {
            return Map.of();
        }
        // semiCode 就是 ProductType.code —— 与 workflowOutputOptions 发给 RN 的那个值同源。
        return productTypeRepo.findByFactoryIdAndIdIn(factoryId, reworkSkuIds).stream()
                .filter(pt -> pt.getCode() != null && !pt.getCode().isBlank())
                .collect(Collectors.toMap(ProductType::getCode, ProductType::getId, (a, b) -> a));
    }

    /**
     * 这份报工的本道成本 (人工 + 材料)。OUTPUT 阶段走 batch 级 rollup (料记在 INPUT 报工上)。
     *
     * <p>C-1 修复时抽出 —— 此前 {@link #postApprovedOutput} 与 {@link #postSemiOutputLedgerFor} 各写一份,
     * 同一个口径两处实现一定会漂。
     */
    private BigDecimal reportTotalCost(String factoryId, ProductionReport report, WorkProcessTask task) {
        BigDecimal rollLabor = report.getLaborCost();
        BigDecimal rollMaterial = report.getMaterialCost();
        if ("OUTPUT".equals(report.getReportKind())) {
            CostRollup rollup = outputRollupWithBatchInputMaterial(factoryId, task);
            rollLabor = rollup.laborCost();
            rollMaterial = rollup.materialCost();
        }
        return nullSafeAdd(null, rollLabor, rollMaterial);
    }

    /**
     * 多产出下这条产出<b>分到</b>的成本 + 分摊后的单价 (各条相同)。
     *
     * <p>{@code null} 传给 {@link #postSemiOutputLedgerFor} = <b>单产出路径</b>:
     * 自算全额成本、自己发事件 —— 逐字节零回归。
     */
    private record AllocatedCost(BigDecimal totalCost, BigDecimal unitCost) {}

    /**
     * Task 4 Step 8: 单产出路径抽出的通用入账体 —— 多产出 ({@code semiOutputs} 非空) 时
     * 由 {@link #postMultiSemiOutputs} 对每个条目各调一次 (带分摊后的成本);
     * 单产出路径由 {@link #postSemiOutputLedger} 委派
     * {@code report.getSemiCode()}/{@code report.getSemiOutputQuantity()} 调这里, 行为零回归。
     *
     * @param allocated 多产出下这条分到的成本; {@code null} = 单产出路径 (自算全额 + 自己发事件)
     * @param reworkProductTypeId 这条是回料时 = 回料自己的 {@code ProductType.id} (判据见
     *        {@link #reworkSemiCodes}); {@code null} = 不是回料 (单产出路径恒传 null)
     * @return 是否真的入了一笔账 (false = 字段缺失或幂等跳过) —— 调用方据此决定发不发成本事件
     */
    private boolean postSemiOutputLedgerFor(String factoryId, ProductionReport report,
                                          WorkProcessTask task, Long operatorId,
                                          String semiCode, BigDecimal inQty, AllocatedCost allocated,
                                          String reworkProductTypeId) {
        // R4: backward-compat — skip if fields absent
        if (semiCode == null || semiCode.isBlank() || inQty == null || inQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[SP1-semi] skip postSemiOutputLedger for report {}: semiCode={} qty={}", report.getId(), semiCode, inQty);
            return false;
        }

        // Fool-Proof Rule 4 — idempotency guard: (report_id, sourceRef=semiCode, IN) 为幂等键。
        // ⚠️ BUG-GOLD-RERUN-WEIGHTED-AVG-SKIP: 更早的实现用过 (factoryId, semiCode, IN) 判重, 但那会让
        //   同一半成品码的合法第二批生产被误判"已存在"跳过, 移动均价不累加 —— 因此改成 report_id 为主键。
        // Task 4 Step 8: 多产出 (画布 1 进 N 出) 下同一份报工要入 N 笔 IN, 只按 report_id 判重会把
        //   第二个产出误判成"已入账"跳过 —— 回料就永远进不了库, 且不报错。补上 sourceRef=semiCode 这一条件,
        //   使幂等键实际是 (factoryId, reportId, semiCode, IN)。单产出路径 sourceRef 本来就等于 semiCode
        //   (见下方 txn.builder().sourceRef(semiCode))，这条件对旧路径是恒真式, 不改变旧行为。
        boolean alreadyPosted = txnRepo.findByFactoryIdAndReportId(factoryId, report.getId()).stream()
                .anyMatch(t -> SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())
                        && semiCode.equals(t.getSourceRef()));
        if (alreadyPosted) {
            log.info("[SP1-semi] idempotent skip for report {} semiCode={}: IN txn already exists for this report+semiCode", report.getId(), semiCode);
            return false;
        }

        String outputUnit = firstNonBlank(report.getSemiOutputUnit(),
                firstNonBlank(report.getOutputUnit(), task.getPlannedUnit()));

        // 多产出: 成本已由 postMultiSemiOutputs 按量分摊好 (各条单价相同), 这里直接用。
        // 单产出 (allocated == null): 自算这份报工的全额成本, 分母就是本条的量 —— 与分摊口径
        //   在 Σqty == inQty 时完全一致, 行为逐字节不变。
        BigDecimal totalCost;
        BigDecimal inUnitCost;
        if (allocated != null) {
            totalCost = allocated.totalCost();
            inUnitCost = allocated.unitCost();
        } else {
            totalCost = reportTotalCost(factoryId, report, task);
            inUnitCost = null;
            if (totalCost != null && inQty.signum() > 0) {
                inUnitCost = totalCost.divide(inQty, 4, RoundingMode.HALF_UP);
            }
        }

        // R1 + W8 BUG-SP1-NEW-ROW 修复 — ensure-row-then-lock:
        //
        // postSemiOutputLedger 嵌在调用方 approval @Transactional 内 (propagation REQUIRED 加入同一事务)。
        // 历史 bug: findForUpdate 返回 empty 时直接 build+save 新行无并发保护, 两线程同时进 new-row 分支:
        //   - H2 (修复前无 unique) → 2 行重复 = WIP 库存翻倍;
        //   - PG (Flyway partial unique) → 第二个 insert 撞约束 → 整个 approval 事务 doom → 500 + WIP 漏记。
        //
        // 修复: 不存在行时先在 REQUIRES_NEW 子事务里 insert 一条 **0 量空占位行** (ensureRowExists),
        //   独立 commit 与调用方事务解耦。并发竞争撞 unique → 子事务隔离 → catch (对方已建好同行)。
        //   之后主流程总能 findForUpdate 拿到锁 → 走 existing-row moving-average 累加 inQty (在调用方事务内)。
        //
        // 为何 0 量占位安全 (而非全量行): 若调用方 approval 事务事后回滚, 残留的是 0 量空行 (无量无成本),
        //   且 IN ledger txn 也随之回滚 → 下次重试 idempotency guard 放行 → findForUpdate 命中 0 量行 →
        //   累加 0+inQty=inQty → **不双计**。全量行残留则会被重试再次累加 = 双计 bug; 0 量占位天然幂等自愈。
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, semiCode)
                .orElse(null);
        if (sfi == null) {
            // 回料行的 productTypeId 记【回料自己】的产品, 不是批次的成品 (Critical-B):
            //   web-admin 投料下拉按上游端口的 skuId 白名单过滤 (sfiMatchesUpstreamPort), 记成品
            //   就永远筛不中 —— 阶段过滤放行了也照样到不了操作员眼前; 显示名/每盒克重也会印成成品的。
            // ⛔ 只对回料这么记: 普通半成品行「productTypeId = 批次成品」是存量语义, 备货看板
            //   (RestockBoardService.sumAvailableByProduct) 按成品汇总在制半成品量依赖它, 本次不动。
            ensureRowExists(factoryId, semiCode, task, outputUnit,
                    reworkProductTypeId != null ? reworkProductTypeId : task.getProductTypeId(),
                    reworkProductTypeId != null);
            sfi = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, semiCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "[SP1-semi] row missing after ensureRowExists, semiCode=" + semiCode));
        }
        // 既有行 (跨批次复用同一个回料产品的那一行) 也要补标 —— 一旦为 true 不再翻回 false。
        if (reworkProductTypeId != null && !Boolean.TRUE.equals(sfi.getIsRework())) {
            sfi.setIsRework(Boolean.TRUE);
        }
        // 统一 moving-average 累加路径 (新建占位行 oldQty=0 → 累加结果与原全量新行字节一致; 既有行直接累加)
        applyMovingAverageIn(sfi, inQty, inUnitCost, totalCost, outputUnit, report.getMaterialBatchRefs());
        wipRepo.save(sfi);

        // Write immutable ledger entry (R3 — no update ever, only REVERSE rows for cancellation)
        BigDecimal balanceAfter = sfi.getAvailableQuantity();
        BigDecimal balanceCostAfter = sfi.getUnitCost();
        SemiFinishedInventoryTransaction txn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(semiCode)
                .quantity(inQty)
                .unitCostAtTxn(inUnitCost)
                .balanceAfter(balanceAfter)
                .balanceCostAfter(balanceCostAfter)
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
        txnRepo.save(txn);
        log.info("[SP1-semi] posted IN txn for report {} semiCode={} qty={} unitCost={} balanceAfter={}",
                report.getId(), semiCode, inQty, inUnitCost, balanceAfter);

        // SP3: 发布成本更新事件 → 异步回填 costUnitPrice + 超支预警。
        // ⛔ 仅单产出路径在这里发 —— 多产出由 postMultiSemiOutputs 在 N 条全部入完后统一发一次
        //    (每条各发一次会让 @Async 的 OrderCostBackfillListener 变成「哪条先到哪条赢」)。
        if (allocated == null && task.getProductionBatchId() != null) {
            eventPublisher.publishEvent(new ProductionCostUpdatedEvent(
                    this,
                    factoryId,
                    task.getProductionBatchId(),
                    task.getProductTypeId(),
                    sfi.getUnitCost(),
                    sfi.getAccumulatedCost()
            ));
        }
        return true;
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — moving-average IN 累加 (新建占位行 / 既有行统一走此路径)。
     *
     * <p>把本次 IN ({@code inQty} @ {@code inUnitCost}, totalCost) 累加进 {@code sfi}:
     * <pre>newUnitCost = (oldQty×oldUnitCost + inQty×inUnitCost) / (oldQty + inQty)</pre>
     * Scale-4 HALF_UP。同时更新 produced/available/status/unit/materialBatchRefs/accumulatedCost。
     *
     * <p>新建占位行 (oldQty=0, oldUnitCost=null) 累加: newProduced=inQty, newUnitCost=inUnitCost,
     * accumulatedCost=totalCost → 与历史"全量新行"行为字节一致 (单一累加路径防分叉)。
     *
     * <p>调用前提: {@code sfi} 已持有 PESSIMISTIC_WRITE 行锁 (existing-row 直接拿锁 / 新建占位行重拿锁),
     * 累加在锁保护下串行化。
     */
    private void applyMovingAverageIn(SemiFinishedInventory sfi, BigDecimal inQty, BigDecimal inUnitCost,
                                      BigDecimal totalCost, String outputUnit,
                                      java.util.List<java.util.Map<String, Object>> materialBatchRefs) {
        BigDecimal oldQty = nz(sfi.getProducedQuantity());
        BigDecimal oldUnitCost = sfi.getUnitCost();        // 累加前旧单价 (可能 null), 不在此处强制 ZERO
        BigDecimal newProduced = oldQty.add(inQty);
        sfi.setProducedQuantity(newProduced);
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        // 盘点修正量 (default 0) 纳入余额 (禁止盘点修正被产出重算覆盖; produced/成本不动)
        sfi.setAvailableQuantity(newProduced.subtract(consumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        if (sfi.getUnit() == null) {
            sfi.setUnit(outputUnit);
        }
        if (sfi.getMaterialBatchRefs() == null && materialBatchRefs != null) {
            sfi.setMaterialBatchRefs(materialBatchRefs);
        }
        // R4 (🔴红线) 诚实 null: 移动均价缺成本不得当 ¥0 摊薄。
        //   只有"两侧成本对各自正数量都已知"才算混合单价; 任一侧"有量但成本未知" → 整体 newUnitCost 诚实 null,
        //   不退化成假摊薄值 (历史 bug: 老 100kg@¥10 + 新 50kg@null → 假 6.6667; 反向 → 假 5.3333)。
        //
        //   oldKnown / inKnown 判据: 该侧 qty 为 0 (无成本贡献, 视为已知) 或其 unitCost 非 null。
        //   - 占位行 first-IN (oldQty=0, oldUnitCost=null) → oldKnown=true → newUnitCost=inUnitCost (字节一致)。
        //   - 两侧都已知 → 正常加权 (oldQty=0 时 oldUnitCost coerce ZERO 不影响, 0×0=0)。
        boolean oldKnown = oldQty.signum() == 0 || oldUnitCost != null;
        boolean inKnown = inQty.signum() == 0 || inUnitCost != null;
        boolean anyPositiveQtyWithCost =
                (oldQty.signum() > 0 && oldUnitCost != null) || (inQty.signum() > 0 && inUnitCost != null);
        BigDecimal newUnitCost = null;
        if (newProduced.signum() > 0 && oldKnown && inKnown && anyPositiveQtyWithCost) {
            BigDecimal oldComponent = oldQty.multiply(oldUnitCost == null ? BigDecimal.ZERO : oldUnitCost);
            BigDecimal inComponent = inUnitCost == null ? BigDecimal.ZERO : inQty.multiply(inUnitCost);
            newUnitCost = oldComponent.add(inComponent).divide(newProduced, 4, RoundingMode.HALF_UP);
        }
        sfi.setUnitCost(newUnitCost);
        sfi.setAccumulatedCost(nullSafeAdd(sfi.getAccumulatedCost(), totalCost));
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — 确保 (factoryId, semiCode) 的 WIP 行存在 (并发安全, 幂等)。
     *
     * <p>在独立 {@code REQUIRES_NEW} 子事务里 insert 一条 **0 量空占位行** 并 commit:
     * <ul>
     *   <li>producedQuantity / consumedQuantity / availableQuantity = 0, accumulatedCost / unitCost = null。
     *       真正的量+成本由调用方在外层事务的 moving-average 路径累加 (0+inQty=inQty)。</li>
     *   <li>子事务独立 commit → 与调用方 approval 事务解耦 → 残留占位行天然幂等自愈 (见 postSemiOutputLedger 注释)。</li>
     * </ul>
     *
     * <p>并发竞争: 另一线程先 insert 同 (factoryId, semiCode) → 撞 unique 约束 →
     * {@link DataIntegrityViolationException}。子事务 REQUIRES_NEW 隔离 → 仅子事务 rollback-only,
     * 不污染调用方事务 → 此处 catch (对方已建好同行, 目标"行存在"已达成) → 正常返回。
     *
     * <p>经 Spring 代理 ({@code self}) 调用使 REQUIRES_NEW 生效 (同类 this 调用绕过 AOP 代理)。
     * 单测无 Spring 代理 (self == null) → fallback 直调 {@link #commitEmptySemiRow} (无真实事务, repo 已 mock)。
     *
     * <p>F1 修复 (2026-06-14): {@code intermediateBatchNo} 参数化 — 同一 ensure-row-then-lock 同时服务
     * SEMI ledger ({@code semiCode}) 与 FINISHED 产出 ({@code wipNo}) 两条路径, 占位行键由调用方传入,
     * 保持与后续 {@code findForUpdate} 的键一致。
     */
    private void ensureRowExists(String factoryId, String intermediateBatchNo, WorkProcessTask task,
                                 String outputUnit) {
        ensureRowExists(factoryId, intermediateBatchNo, task, outputUnit, task.getProductTypeId(), false);
    }

    /**
     * @param productTypeId 这一行装的东西的产品 id —— 回料行记<b>回料自己</b>的产品,
     *        其余一律沿用 {@code task.getProductTypeId()} (批次成品, 存量语义)
     * @param isRework 这一行装的是不是回料。⛔ {@code process_order} 仍诚实记<b>产出它的那道</b>,
     *        不为了穿过投料下拉的阶段过滤把它改成 0 —— 那一列还被出成率卡片用来排序并串成本链
     *        (上一行的 produced = 下一行的投入), 回料排最前会把良品那道的出成率算成 1900%;
     *        {@code resolveSourceWipNo} 也按 {@code processOrder == 本道-1} 找源, 0 对不上任何一道。
     */
    private void ensureRowExists(String factoryId, String intermediateBatchNo, WorkProcessTask task,
                                 String outputUnit, String productTypeId, boolean isRework) {
        SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
                .factoryId(factoryId)
                .batchId(task.getProductionBatchId())
                .intermediateBatchNo(intermediateBatchNo)
                .sourceWorkProcessTaskId(task.getId())
                .processOrder(task.getProcessOrder())
                .productTypeId(productTypeId)
                .isRework(isRework)
                .producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(BigDecimal.ZERO)
                .unit(outputUnit)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .accumulatedCost(null)
                .unitCost(null)
                .build();
        try {
            if (self != null) {
                self.commitEmptySemiRow(placeholder);
            } else {
                commitEmptySemiRow(placeholder);
            }
        } catch (DataIntegrityViolationException dup) {
            // 并发竞争输掉 insert race → 对方已建好同行, 目标 (行存在) 已达成 → 安全继续
            log.info("[wip] new-row race for intermediateBatchNo={}: lost insert race (constraint hit), "
                    + "row already created by concurrent tx", intermediateBatchNo);
        }
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — 在 {@code REQUIRES_NEW} 子事务里 insert 占位行并独立 commit。
     *
     * <p>{@code saveAndFlush} 强制立即 flush → unique 约束冲突即时抛出 (而非延迟到子事务 commit 边界),
     * 使 {@link #ensureSemiRowExists} catch 时机明确。{@code public} + 经 {@code self} 代理调用是
     * REQUIRES_NEW 生效的硬性要求。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitEmptySemiRow(SemiFinishedInventory placeholder) {
        wipRepo.saveAndFlush(placeholder);
    }

    // ==================== G3 小结 (interim-settle) — task-free SFI in/out ====================

    /**
     * G3 小结半成品入库 (SFI IN) — 见接口 Javadoc。
     *
     * <p>ensure-row-then-lock (复用 W8 BUG-SP1-NEW-ROW 并发安全范式): {@code findForUpdate} 拿悲观写锁;
     * 不存在 → REQUIRES_NEW 子事务建 0 量占位行 (撞 unique 约束 catch 幂等) → 重 {@code findForUpdate} 拿锁;
     * 单一 {@link #applyMovingAverageIn} 累加路径 (诚实 null 成本)。
     */
    @Override
    @Transactional
    public void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
                                BigDecimal inQty, String unit, BigDecimal inUnitCost,
                                List<Map<String, Object>> materialBatchRefs, Integer processOrder) {
        postClerkOutput(factoryId, intermediateBatchNo, productTypeId, inQty, unit, inUnitCost,
                materialBatchRefs, processOrder, false);
    }

    @Override
    @Transactional
    public void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
                                BigDecimal inQty, String unit, BigDecimal inUnitCost,
                                List<Map<String, Object>> materialBatchRefs, Integer processOrder,
                                boolean rework) {
        if (inQty == null || inQty.signum() <= 0) {
            return;
        }
        if (wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .isEmpty()) {
            SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .intermediateBatchNo(intermediateBatchNo)
                    .productTypeId(productTypeId)
                    .unit(unit)
                    .processOrder(processOrder)
                    .isRework(rework)
                    .producedQuantity(BigDecimal.ZERO)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(BigDecimal.ZERO)
                    .accumulatedCost(null)
                    .unitCost(null)
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .build();
            try {
                if (self != null) {
                    self.commitEmptySemiRow(placeholder);
                } else {
                    commitEmptySemiRow(placeholder);
                }
            } catch (DataIntegrityViolationException dup) {
                // 并发竞争输掉 insert race → 对方已建好同行, 目标 (行存在) 已达成 → 安全继续
                log.info("[wip] postClerkOutput new-row race for intermediateBatchNo={}: row already created by concurrent tx",
                        intermediateBatchNo);
            }
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new IllegalStateException("SFI row missing after create: " + intermediateBatchNo));
        // 回料角色 sticky true：同一锚后续普通入账不能把已确认回料翻回普通半成品。
        if (rework && !Boolean.TRUE.equals(sfi.getIsRework())) {
            sfi.setIsRework(Boolean.TRUE);
        }
        // 锚跨多次小结可被不同 processOrder 的道喂 → 取 MIN (最早道)。picker 阶段过滤
        // (listWipByFactory: processOrder != null && < maxProcessOrder) 严格排除 null, 故必须落值;
        // 取最早道 → 新产 SFI 在尽可能靠后的复用道 (maxProcessOrder 大) 仍可见 (宁缺勿藏)。
        // 既有行 processOrder=null (旧路径) → 用本次 incoming 填; 双非空 → 保留更小者。
        if (processOrder != null
                && (sfi.getProcessOrder() == null || processOrder.compareTo(sfi.getProcessOrder()) < 0)) {
            sfi.setProcessOrder(processOrder);
        }
        BigDecimal totalCost = inUnitCost == null ? null : inUnitCost.multiply(inQty);
        applyMovingAverageIn(sfi, inQty, inUnitCost, totalCost, unit, materialBatchRefs);
        wipRepo.save(sfi);

        // 🔴 2026-08-18: 这条路一直在改余额却【从不记流水】(PR#2870 登记为「未修」)。
        //    ⚠️ 它必须与 consumeClerkSemi / consumeClerkSemiStrict 的 OUT 【成对】补 ——
        //    只补 IN 会让这一行族出现「进 10 出 0 而余额说领了 5」的可见矛盾, 比空账更糟。
        //    本次两侧同时补, 见下面那两个方法。
        //
        //    ⚠️ 流水行在库存行【累加完之后】构造 —— clerkProductionRow 直接读改完的 availableQuantity。
        //    ⛔ 不做幂等去重: postClerkOutput 每调一次就真的累加一次余额 (编辑走
        //    reverseClerkOutput + postClerkOutput 一对), 流水必须一比一映射余额变动。
        SemiFinishedInventoryTransaction inTxn =
                WipLedgerEntries.clerkProductionRow(factoryId, sfi, inQty, inUnitCost, null);
        if (inTxn != null) {
            txnRepo.save(inTxn);
        }
        log.info("[wip] postClerkOutput SFI IN: factory={}, batchNo={}, inQty={}, newProduced={}, available={}, ledgered={}",
                factoryId, intermediateBatchNo, inQty, sfi.getProducedQuantity(), sfi.getAvailableQuantity(),
                inTxn != null);
    }

    /**
     * G3 小结半成品出库 (SFI OUT) — 见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → {@code consumedQuantity += qty} →
     * {@code availableQuantity = produced - consumed}。守卫 not-below-zero (行缺失 no-op / 超扣 clamp)。
     */
    @Override
    @Transactional
    public void consumeClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        java.util.Optional<SemiFinishedInventory> opt = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo);
        if (opt.isEmpty()) {
            // 守卫: 无 SFI 行 = 没有库存可扣 (该半成品未曾入库) → no-op, 不建负行不报错。
            log.warn("[wip] consumeClerkSemi: no SFI row for batchNo={} (factory={}) — skip (nothing stocked)",
                    intermediateBatchNo, factoryId);
            return;
        }
        SemiFinishedInventory sfi = opt.get();
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal priorConsumed = nz(sfi.getConsumedQuantity());
        BigDecimal newConsumed = priorConsumed.add(qty);
        if (newConsumed.compareTo(produced) > 0) {
            // 守卫 not-below-zero: 超扣 clamp 到 produced (available 不为负)。诚实告警, 不静默吞。
            log.warn("[wip] consumeClerkSemi over-draw: batchNo={}, attemptConsumed={} > produced={} — clamping",
                    intermediateBatchNo, newConsumed, produced);
            newConsumed = produced;
        }
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);

        // 🔴 2026-08-18: OUT 侧记流水 —— 与 postClerkOutput 的 IN 【成对】补 (见那边注释)。
        //    🔴 记的是【实际扣减量】(newConsumed − priorConsumed), ⛔ 不是入参 qty:
        //    上面那道 clamp 会让真正扣掉的比请求的少, 按 qty 记就等于账本自己说了一个没发生的数。
        //    clamp 到一点都没扣时 actualOut = 0 ⇒ clerkConsumptionRow 返回 null ⇒ 不造空流水。
        BigDecimal actualOut = newConsumed.subtract(priorConsumed);
        SemiFinishedInventoryTransaction outTxn =
                WipLedgerEntries.clerkConsumptionRow(factoryId, sfi, actualOut, null);
        if (outTxn != null) {
            txnRepo.save(outTxn);
        }
        log.info("[wip] consumeClerkSemi SFI OUT: factory={}, batchNo={}, requestedQty={}, actualOut={}, "
                        + "consumed={}, available={}, ledgered={}",
                factoryId, intermediateBatchNo, qty, actualOut, sfi.getConsumedQuantity(),
                sfi.getAvailableQuantity(), outTxn != null);
    }

    /**
     * G3 小结半成品出库 (SFI OUT) — 严格版 (SFI 投料, 禁止降级)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → 校验存在 + 足量 → 扣减。缺失/不足 <b>抛</b> (不 no-op/clamp)。
     */
    @Override
    @Transactional
    public BigDecimal consumeClerkSemiStrict(String factoryId, String intermediateBatchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new BusinessException(409, "半成品库存不存在: " + intermediateBatchNo)
                        .withCode("SFI_NOT_FOUND")
                        .withHint("请重新选择仍有库存的半成品批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget(intermediateBatchNo));
        // 🟠 盒⇄kg 折算: 计数单位半成品 (盒/个/件/只) 作 kg 道投料来源时, 把 kg 投料量折算为盒数扣减
        //   (依据 ProductType.gramsPerUnit)。kg/重量单位来源 → 原样 (deductQty = qty, 行为不变; 不查产品避免多余 IO)。
        //   🔴 诚实 null (禁止降级): 计数单位来源缺每盒克重 → 无法折算 → loud-fail, 绝不臆造 1盒=1kg。
        BigDecimal deductQty;
        if (FeedUnitConverter.isCountUnit(sfi.getUnit())) {
            deductQty = FeedUnitConverter.feedKgToSourceUnit(
                    qty, sfi.getUnit(), resolveGramsPerUnit(sfi.getProductTypeId()));
            if (deductQty == null) {
                throw new BusinessException(409, "半成品批次 " + intermediateBatchNo + " 为" + sfi.getUnit()
                        + "装但未配置每盒标准克重(每盒克重), 无法把 kg 投料折算为" + sfi.getUnit() + "扣减")
                        .withCode("SFI_NO_GRAMS_PER_UNIT")
                        .withHint("请先在产品配置为该半成品设置标准克重(每盒克重), 再重试投料")
                        .withSeverity("BLOCKING")
                        .withHintTarget(intermediateBatchNo);
            }
        } else {
            deductQty = qty; // kg / 重量单位: 直投 (行为不变)
        }
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        BigDecimal available = produced.subtract(consumed);
        if (deductQty.compareTo(available) > 0) {
            // 禁止降级: 不足即抛 (不 clamp), 防 phantom/不足库存生产成品。余/需按来源原生单位 (盒装时为盒数)。
            String u = sfi.getUnit() != null ? sfi.getUnit() : "";
            throw new BusinessException(409, "半成品库存不足: " + intermediateBatchNo
                    + " 余" + available.stripTrailingZeros().toPlainString() + u
                    + " 需" + deductQty.stripTrailingZeros().toPlainString() + u)
                    .withCode("SFI_INSUFFICIENT")
                    .withHint("请减少投料量或选择其他半成品批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        BigDecimal newConsumed = consumed.add(deductQty);
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);

        // 🔴 2026-08-18: OUT 侧记流水 —— 与 postClerkOutput 的 IN 【成对】补 (见那边注释)。
        //    🔴 记 deductQty 而不是入参 qty: 盒装来源时 qty 是 kg 投料量, 真正扣掉的是折算后的【盒数】
        //    (见上面 FeedUnitConverter 那段)。记 qty 就会让流水与库存行用两个不同单位的数, 对不了账。
        //    余额口径: clerkConsumptionRow 读【扣减后】的 availableQuantity, 故必须放在 save 之后。
        SemiFinishedInventoryTransaction outTxn =
                WipLedgerEntries.clerkConsumptionRow(factoryId, sfi, deductQty, null);
        if (outTxn != null) {
            txnRepo.save(outTxn);
        }
        // 返回<b>实际扣减量</b> (来源原生单位: kg 源=qty; 盒源=盒数) — 撤销小结据此 restoreClerkSemi 精确还回 (口径一致)。
        log.info("[wip] consumeClerkSemiStrict SFI OUT: factory={}, batchNo={}, feedKg={}, deducted={}{}, consumed={}, available={}, ledgered={}",
                factoryId, intermediateBatchNo, qty, deductQty, sfi.getUnit() != null ? sfi.getUnit() : "",
                sfi.getConsumedQuantity(), sfi.getAvailableQuantity(), outTxn != null);
        return deductQty;
    }

    /**
     * 盒⇄kg 成本口径: 把 kg 投料量折算为半成品来源的原生单位数量 (计数单位→盒数; kg 源→原值),
     * 供小结成本核算 (成本 = 折算后数量 × 每单位 unitCost)。与 {@link #consumeClerkSemiStrict} 折算同源 (口径一致)。
     *
     * <p>🔴 诚实 null: 批次缺失 / 计数单位来源缺每盒克重 → null (调用方判本道产出成本未知, 禁止臆造)。只读不锁。
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolveSemiFeedQtyInSourceUnit(String factoryId, String intermediateBatchNo, BigDecimal feedKg) {
        if (feedKg == null || feedKg.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        SemiFinishedInventory sfi = wipRepo
                .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElse(null);
        if (sfi == null) {
            return null; // 诚实 null: 批次缺失 → 成本未知
        }
        if (!FeedUnitConverter.isCountUnit(sfi.getUnit())) {
            return feedKg; // kg / 重量单位: 原值 (不查产品避免多余 IO)
        }
        return FeedUnitConverter.feedKgToSourceUnit(feedKg, sfi.getUnit(), resolveGramsPerUnit(sfi.getProductTypeId()));
    }

    /** 读产品每盒标准克重 (ProductType.gramsPerUnit); 缺 productTypeId / 产品缺失 / 未配 → null (诚实 null)。 */
    private BigDecimal resolveGramsPerUnit(String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) {
            return null;
        }
        return productTypeRepo.findById(productTypeId).map(ProductType::getGramsPerUnit).orElse(null);
    }

    /**
     * G3 成本传导 — 读常驻半成品库存(SFI)的移动均价 unitCost (见接口 Javadoc)。
     *
     * <p>只读非锁 finder ({@code findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull}): 与小结 OUT
     * 同事务读值稳定 (OUT 不改 unitCost)。🔴 诚实 null: 行缺失 / unitCost 未知 → null (不伪造 ¥0)。
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal getSemiUnitCost(String factoryId, String intermediateBatchNo) {
        if (intermediateBatchNo == null || intermediateBatchNo.isBlank()) {
            return null;
        }
        return wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .map(SemiFinishedInventory::getUnitCost)
                .orElse(null);
    }

    /**
     * 撤销小结 — {@link #postClerkOutput} 的逆 (SFI IN un-stock)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → 下游已消耗守卫 (available_after&lt;0 抛) → produced/accumulatedCost 冲销 +
     * unitCost 重算 → 写 REVERSE/REVERSAL 流水。
     */
    @Override
    @Transactional
    public void reverseClerkOutput(String factoryId, String intermediateBatchNo, BigDecimal qty,
                                   BigDecimal totalCost, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new BusinessException(409, "半成品库存行不存在, 无法撤销入库: " + intermediateBatchNo)
                        .withCode("SFI_NOT_FOUND")
                        .withHint("该半成品运行余额行已不存在, 可能已被其它操作清除")
                        .withSeverity("BLOCKING")
                        .withHintTarget(intermediateBatchNo));
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        BigDecimal adj = nz(sfi.getAdjustmentQuantity());
        BigDecimal newProduced = produced.subtract(qty);
        BigDecimal newAvailable = newProduced.subtract(consumed).add(adj);
        if (newAvailable.signum() < 0) {
            // 🔴 冲销会致负库存 (禁止降级): loud-fail, 不产 phantom。
            // 消息准确性修复 (fool-proof Rule 4/5): 短缺真实原因可能是 下游已消耗(consumed>0) 和/或
            // 盘点已发生盘亏(adjustmentQuantity<0, adj) —— 旧版只报"已被下游消耗", 在纯盘亏场景(consumed=0
            // 而 adj<0)会把真因错报给不存在的"下游消耗", remedy("请先撤销下游消耗")也是不存在的 dead-end。
            // 可撤销上限 = 撤销前当前可用量 = produced − consumed + adj (不能只减 consumed)。
            BigDecimal reversibleNow = produced.subtract(consumed).add(adj).max(BigDecimal.ZERO);
            StringBuilder cause = new StringBuilder();
            if (consumed.signum() > 0) {
                cause.append("已被下游消耗 ").append(consumed.stripTrailingZeros().toPlainString());
            }
            if (adj.signum() < 0) {
                if (cause.length() > 0) {
                    cause.append("、");
                }
                cause.append("已发生半成品盘亏 ").append(adj.negate().stripTrailingZeros().toPlainString())
                        .append(" (盘点损耗)");
            }
            if (cause.length() == 0) {
                // 防御性兜底: 理论上不该到这 (newAvailable<0 必由 consumed>0 或 adj<0 导致)。
                cause.append("可用余量不足");
            }
            String remedyHint = (adj.signum() < 0)
                    ? ("盘亏已过账 (不随撤销自动冲销), 无法通过撤销小结恢复"
                        + (consumed.signum() > 0 ? "; 下游消耗部分请先撤销下游相关小结" : ""))
                    : "该批次半成品已被后续工序/计划领用, 请先撤销下游相关小结再重试";
            throw new BusinessException(409, "半成品 " + intermediateBatchNo + " " + cause
                    + ", 当前可撤销入库仅 " + reversibleNow.stripTrailingZeros().toPlainString()
                    + " (需撤销 " + qty.stripTrailingZeros().toPlainString() + "), 无法撤销小结; " + remedyHint)
                    .withCode("SFI_DOWNSTREAM_CONSUMED")
                    .withHint(remedyHint)
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        sfi.setProducedQuantity(newProduced);
        // 成本冲销 (诚实): totalCost null → accumulatedCost 未曾加此段, 不减; 否则减去确切金额。
        BigDecimal acc = sfi.getAccumulatedCost();
        if (acc != null && totalCost != null) {
            acc = acc.subtract(totalCost);
            sfi.setAccumulatedCost(acc);
        }
        // 重算 unitCost = accumulatedCost / producedQuantity (produced≤0 或 accumulatedCost null → null, 诚实)。
        //   #6 已知 cost-only edge (数量安全, 不影响本次 diff 的数量正确性): produced/accumulatedCost 精确复原;
        //   但若同锚仍残留其它"成本未知(null)"的 IN, 本重算得 acc/produced 非 null, 而诚实值应保持 null (poison
        //   应延续)。F006 罕见 (同锚 IN 通常成本口径一致/全 null)。可 re-derive, 故留作已知限制不在此处修。
        sfi.setUnitCost((newProduced.signum() > 0 && acc != null)
                ? acc.divide(newProduced, 4, RoundingMode.HALF_UP) : null);
        sfi.setAvailableQuantity(newAvailable);
        if (newAvailable.signum() <= 0) {
            if (!SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
                sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
            }
        } else if (!SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(sfi);

        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("interim-settle-reverse-in:" + intermediateBatchNo)
                .quantity(qty.negate())                 // IN 冲销 = 负量
                .unitCostAtTxn(sfi.getUnitCost())
                .balanceAfter(newAvailable)
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[interim-reverse] reverseClerkOutput SFI IN 冲销: factory={}, batchNo={}, qty={}, "
                        + "newProduced={}, newAvailable={}, unitCost={}",
                factoryId, intermediateBatchNo, qty, newProduced, newAvailable, sfi.getUnitCost());
    }

    /**
     * 撤销小结 — {@link #consumeClerkSemi} / {@link #consumeClerkSemiStrict} 的逆 (SFI OUT restore)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → consumed 反冲 (缺失容忍 / 反冲不为负守卫) → 写 REVERSE/REVERSAL 流水。
     */
    @Override
    @Transactional
    public void restoreClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        java.util.Optional<SemiFinishedInventory> opt = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo);
        if (opt.isEmpty()) {
            // 还量容忍: 源库存行已不存在 → 无处可还, 跳过 (不产负行, 不阻塞撤销)。诚实告警。
            log.warn("[interim-reverse] restoreClerkSemi: no SFI row for batchNo={} (factory={}) — skip (nothing to restore)",
                    intermediateBatchNo, factoryId);
            return;
        }
        SemiFinishedInventory sfi = opt.get();
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal newConsumed = nz(sfi.getConsumedQuantity()).subtract(qty);
        if (newConsumed.signum() < 0) {
            // 诚实: 还量超过历史消耗量 → 报错而非静默置 0。
            throw new BusinessException(409, "撤销出库量超过已消耗量: " + intermediateBatchNo
                    + " 请求还 " + qty.stripTrailingZeros().toPlainString()
                    + " 已消耗 " + nz(sfi.getConsumedQuantity()).stripTrailingZeros().toPlainString())
                    .withCode("SFI_REVERSE_OVER_CONSUMED")
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(sfi);

        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("interim-settle-reverse-out:" + intermediateBatchNo)
                .quantity(qty)                          // OUT 冲销 = 正量 (还回)
                .unitCostAtTxn(sfi.getUnitCost())
                .balanceAfter(sfi.getAvailableQuantity())
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[interim-reverse] restoreClerkSemi SFI OUT 还回: factory={}, batchNo={}, qty={}, consumed={}, available={}",
                factoryId, intermediateBatchNo, qty, newConsumed, sfi.getAvailableQuantity());
    }

    private void markWipPosted(ProductionReport report) {
        Map<String, Object> fields = report.getCustomFields();
        if (fields == null) {
            fields = new HashMap<>();
        } else {
            fields = new HashMap<>(fields);
        }
        fields.put("wipPosted", true);
        fields.put("wipPostedAt", LocalDateTime.now().toString());
        report.setCustomFields(fields);
    }

    /**
     * F1 修复 (2026-06-14) — FINISHED/legacy 产出 WIP upsert, ensure-row-then-lock 并发安全。
     *
     * <p><b>历史 bug</b>: 此前用 {@code findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(...).orElse(null)}
     * 无悲观锁。{@code wipNo = generateBatchNo(task)} 是 task 级稳定键 (同 task 跨天/多次报工命中同行)。
     * 并发审批同一 wipNo → 两事务各读旧行 → save 时 {@code @Version} 乐观锁冲突 → 审批事务回滚 →
     * WIP 入账静默丢失 (#713 只修了半成品 SEMI 路径, FINISHED 路径漏修)。
     *
     * <p><b>修复</b>: 复用 SEMI 路径同款 ensure-row-then-lock (W8 BUG-SP1-NEW-ROW):
     * <ol>
     *   <li>{@code findForUpdate(wipNo)} 拿悲观写锁; 不存在 → {@link #ensureRowExists} 在 REQUIRES_NEW
     *       子事务建 0 量占位行 (撞 unique 约束 catch 幂等) → 重 {@code findForUpdate} 拿锁。</li>
     *   <li>单一累加路径 (existing-row 直接拿锁 / 新建占位行重拿锁), 累加在悲观锁保护下串行化, 无乐观锁冲突。</li>
     * </ol>
     *
     * <p>成本语义不变 (FINISHED 用 {@code accumulatedCost / producedQuantity}, 非 SEMI 的 moving-average
     * inUnitCost 加权): 0 量占位行 accumulatedCost=null → 首次累加后 = 本次 rollup, unitCost = accumulated/produced,
     * 与历史 new-row / existing-row 行为字节一致。诚实 null: accumulatedCost 为 null (无工价无料价) → unitCost null。
     */
    @Override
    @Transactional
    public void reverseReportPosting(String factoryId, ProductionReport report,
                                     WorkProcessTask task, Long operatorId) {
        if (report == null || task == null || report.getId() == null) {
            return;
        }
        // 幂等: 已经冲销过就不再退第二次。判据是「这条报工名下有没有 REVERSE 流水」——
        // ⛔ 不看报工的 approvalStatus: 那个字段可以被反复改, 而流水是只增不改的事实。
        boolean alreadyReversed = txnRepo.findByFactoryIdAndReportId(factoryId, report.getId()).stream()
                .anyMatch(t -> SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType()));
        if (alreadyReversed) {
            log.info("[wip] report {} 已冲销过, 本次 no-op", report.getId());
            return;
        }
        String ref = "REVERSE-REPORT-" + report.getId();

        // ① 本道产出退回
        BigDecimal out = nz(report.getOutputQuantity());
        if (out.signum() > 0) {
            SemiFinishedInventory wip = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                            factoryId, generateBatchNo(task))
                    .orElse(null);
            if (wip != null) {
                BigDecimal consumed = nz(wip.getConsumedQuantity());
                // ⛔ 下游已经领走了就拒绝 —— 允许冲销会让下游那条报工建立在一批
                //    已经不存在的料上, 而且不报错。事先拦住优于事后不自洽。
                if (consumed.signum() > 0) {
                    throw new BusinessException(409, "这笔产出已被下一道领用 "
                            + consumed.stripTrailingZeros().toPlainString()
                            + firstNonBlank(wip.getUnit(), "") + ", 不能直接驳回")
                            .withHint("请先处理领用它的那道工序的报工, 再回来驳回本条");
                }
                BigDecimal produced = nz(wip.getProducedQuantity()).subtract(out).max(BigDecimal.ZERO);
                wip.setProducedQuantity(produced);
                wip.setAvailableQuantity(
                        produced.subtract(consumed).add(nz(wip.getAdjustmentQuantity())));
                if (wip.getAvailableQuantity().signum() <= 0
                        && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
                    wip.setStatus(SemiFinishedInventory.Status.DEPLETED);
                }
                // 退空了就把成本一起清掉 —— 留着一个没有数量的均价, 下一次入库会被它带偏。
                if (produced.signum() == 0) {
                    wip.setAccumulatedCost(null);
                    wip.setUnitCost(null);
                }
                wipRepo.save(wip);
                // 🔴 流水在【改完之后】构造 —— reversalRow 直接读改完的 availableQuantity,
                //    与 consumptionRow(读扣减前) 口径相反, 混用会算出负余额。
                SemiFinishedInventoryTransaction t =
                        WipLedgerEntries.reversalRow(wip, out.negate(), report, ref, operatorId);
                if (t != null) {
                    txnRepo.save(t);
                }
            }
        }

        // ② 本道领用还回上道
        String srcNo = report.getSourceWipNo();
        if (srcNo != null && !srcNo.isBlank()) {
            BigDecimal back = nz(sourceWipQuantity(report));
            if (back.signum() > 0) {
                SemiFinishedInventory src = wipRepo
                        .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, srcNo)
                        .orElse(null);
                if (src != null) {
                    BigDecimal consumed = nz(src.getConsumedQuantity()).subtract(back).max(BigDecimal.ZERO);
                    src.setConsumedQuantity(consumed);
                    src.setAvailableQuantity(nz(src.getProducedQuantity())
                            .subtract(consumed).add(nz(src.getAdjustmentQuantity())));
                    if (src.getAvailableQuantity().signum() > 0
                            && !SemiFinishedInventory.Status.RETURNED.equals(src.getStatus())) {
                        src.setStatus(SemiFinishedInventory.Status.AVAILABLE);
                    }
                    wipRepo.save(src);
                    SemiFinishedInventoryTransaction t =
                            WipLedgerEntries.reversalRow(src, back, report, ref, operatorId);
                    if (t != null) {
                        txnRepo.save(t);
                    }
                }
            }
        }
    }

    /**
     * @param allocated 多产出在场时这条 FINISHED 产出分到的成本 (Critical 2: 一份报工的成本要在
     *        semiOutputs 的 N 条与本条之间<b>一起</b>分摊, 各拿各的份额);
     *        {@code null} = 单产出路径, 自算 {@code rollLabor + rollMaterial} 全额 (逐字节零回归)
     */
    private SemiFinishedInventory upsertProducedWip(String factoryId, ProductionReport report, WorkProcessTask task,
                                   BigDecimal rollLaborCost, BigDecimal rollMaterialCost,
                                   Long operatorId, AllocatedCost allocated) {
        String wipNo = generateBatchNo(task);
        BigDecimal out = nz(report.getOutputQuantity());
        String outputUnit = firstNonBlank(report.getOutputUnit(), task.getPlannedUnit());

        // ensure-row-then-lock: 拿悲观写锁, 不存在则先建 0 量占位行 (REQUIRES_NEW 子事务) 再重拿锁。
        SemiFinishedInventory wip = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                .orElse(null);
        if (wip == null) {
            ensureRowExists(factoryId, wipNo, task, outputUnit);
            wip = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                    .orElseThrow(() -> new IllegalStateException(
                            "[wip] row missing after ensureRowExists, wipNo=" + wipNo));
        }

        // 修2: 累加前快照历史产出量 + 单价 (用于诚实 null 毒化判定, 必须在 setProducedQuantity 前取)。
        BigDecimal wipPriorProduced = wip.getProducedQuantity();
        BigDecimal wipPriorUnitCost = wip.getUnitCost();

        // 统一累加路径 (占位行 producedQuantity=0 → 累加结果与历史全量新行字节一致; 既有行直接累加)。
        BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
        BigDecimal consumed = nz(wip.getConsumedQuantity());
        wip.setProducedQuantity(produced);
        wip.setAvailableQuantity(produced.subtract(consumed).add(nz(wip.getAdjustmentQuantity())));
        if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
            wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        if (wip.getUnit() == null) {
            wip.setUnit(outputUnit);
        }
        if (wip.getMaterialBatchRefs() == null && report.getMaterialBatchRefs() != null) {
            wip.setMaterialBatchRefs(report.getMaterialBatchRefs());
        }

        // 修2 (🔴红线) 诚实 null: FINISHED 路径与半成品 applyMovingAverageIn 对齐 —
        //   "有正产出量但本次/历史成本未知" 不得当 ¥0 摊薄稀释 unitCost。
        //   历史 bug: 老 100kg@accumulated=1000 + 新 50kg@(rollLabor=null,rollMaterial=null) →
        //            accumulatedCost 仍 1000, produced=150 → unitCost=1000/150=6.67 (把未知 50kg 当 ¥0)。
        //
        //   判据 (任一为真 → 整体 unitCost 诚实 null):
        //   - 本次贡献: out>0 且本次 rollup 成本完全未知 (rollLabor 与 rollMaterial 都 null);
        //   - 历史已被毒化: 既有行 producedQuantity>0 但 unitCost 为 null (此前某次"有量无成本"已置 null) —
        //     既然历史段成本未知, 累加后仍无法诚实摊。
        //   仅当两者皆"成本已知/不毒化"时才计算 accumulatedCost/produced 加权 (与历史字节一致)。
        //
        // 多产出在场时 (allocated != null) 本次贡献是【分摊后】的那一份, 不是全额 rollup ——
        // 判「本次成本未知」也必须跟着换成分摊值是不是 null (Critical 2)。
        BigDecimal thisRollup = allocated != null
                ? allocated.totalCost()
                : nullSafeAdd(null, rollLaborCost, rollMaterialCost);
        BigDecimal priorProduced = nz(wipPriorProduced);
        boolean priorPoisoned = priorProduced.signum() > 0 && wipPriorUnitCost == null;
        boolean inCostUnknown = out.signum() > 0 && thisRollup == null;

        wip.setAccumulatedCost(nullSafeAdd(wip.getAccumulatedCost(), thisRollup));
        if (!priorPoisoned && !inCostUnknown
                && wip.getAccumulatedCost() != null && produced.signum() > 0) {
            wip.setUnitCost(wip.getAccumulatedCost().divide(produced, 4, RoundingMode.HALF_UP));
        } else {
            wip.setUnitCost(null);
        }
        SemiFinishedInventory saved = wipRepo.save(wip);

        // 🔴 2026-08-18: 这条路一直在改余额却<b>从不记流水</b>。F006 生产库实测 8 行流水里
        //    IN 是 0 行, 而 4 行库存各挂着 2/240/300/10 的 produced —— 余额凭空出现,
        //    追溯链断在这里。OUT 侧 2026-08-17 已补 (consumeSourceWip), IN 侧就是这一处。
        //
        //    ⚠️ 流水行在库存行【累加完之后】构造 —— productionRow 直接读改完的 availableQuantity,
        //       与 consumptionRow(读扣减前) 口径相反, 混用会算出错的 balanceAfter。
        //
        //    幂等: 同一份报工在同一条库存行上只允许有一条 IN。撤回是【逐 IN 行】写冲销的,
        //    多写一条 IN 就会被冲销两次 —— 所以这里不能只靠外层 wipPosted 标记。
        // 多产出在场时用分摊后的共享单价 (各槽相同 ⇒ 回料与良品同一个单价, 与 SEMI 侧口径一致)。
        BigDecimal inUnitCost = allocated != null
                ? allocated.unitCost()
                : ((thisRollup != null && out.signum() > 0)
                        ? thisRollup.divide(out, 4, RoundingMode.HALF_UP)
                        : null);   // 诚实 null: 无工价无料价 → 成本未知, ⛔ 不摊成 ¥0
        boolean inAlreadyLedgered = report.getId() != null
                && txnRepo.findByFactoryIdAndReportId(factoryId, report.getId()).stream()
                        .anyMatch(t -> SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())
                                && java.util.Objects.equals(t.getSemiFinishedId(), saved.getId()));
        if (!inAlreadyLedgered) {
            SemiFinishedInventoryTransaction inTxn =
                    WipLedgerEntries.productionRow(saved, out, inUnitCost, report, wipNo, operatorId);
            if (inTxn != null) {
                txnRepo.save(inTxn);
                log.info("[wip] upsertProducedWip 记 IN 流水: factory={}, wipNo={}, qty={}, unitCost={}, balanceAfter={}",
                        factoryId, wipNo, out, inUnitCost, saved.getAvailableQuantity());
            }
        }
        return saved;
    }

    private CostRollup calculateTaskCostRollup(String factoryId, Long workProcessTaskId) {
        BigDecimal labor = null;
        BigDecimal material = null;
        for (ProductionReport r : reportRepo.findYieldReportsByTask(factoryId, workProcessTaskId)) {
            if (r.getLaborCost() != null) {
                labor = (labor == null ? BigDecimal.ZERO : labor).add(r.getLaborCost());
            }
            if (r.getMaterialCost() != null) {
                material = (material == null ? BigDecimal.ZERO : material).add(r.getMaterialCost());
            }
        }
        return new CostRollup(labor, material);
    }

    /**
     * P0 修 (2026-06-12, Codex gold 深测): 两点报工 OUTPUT task 成本滚动 = 本 task per-task rollup
     * + 同批次 {@code __MATERIAL_INPUT__} 哨兵 task 的料成本.
     *
     * <p>根因: 两点模式 (F006) 料成本记在 INPUT task ({@code __MATERIAL_INPUT__}), 最终产出在 OUTPUT task
     * ({@code __FINAL_OUTPUT__}). 旧 {@code calculateTaskCostRollup(outputTaskId)} 只聚合 OUTPUT task 自身
     * report (material 为 null) → 产出 WIP unitCost 永 null → ProductionCostUpdatedEvent 不发 →
     * SalesOrderItem.costUnitPrice 永不回填 → 财审 actualCost null + 撤回自愈/多段链/混合计价全部 deep-close 受阻.
     *
     * <p>逐道模式 (F001): 每道工序 task 各自有料+产出, 无 {@code __MATERIAL_INPUT__} 哨兵 task →
     * {@link #calculateBatchInputMaterialCost} 返 null → 本方法等价于原 per-task rollup. Mode-aware by construction,
     * 不破坏逐道. 诚实 null: 无 INPUT 料且 OUTPUT 自身无料 → material 仍 null (不伪造 0).
     */
    private CostRollup outputRollupWithBatchInputMaterial(String factoryId, WorkProcessTask outputTask) {
        CostRollup own = calculateTaskCostRollup(factoryId, outputTask.getId());
        BigDecimal material = own.materialCost();
        BigDecimal inputMaterial = calculateBatchInputMaterialCost(factoryId, outputTask.getProductionBatchId());
        if (inputMaterial != null) {
            material = (material == null ? BigDecimal.ZERO : material).add(inputMaterial);
        }
        return new CostRollup(own.laborCost(), material);
    }

    /**
     * 同批次所有 INPUT-kind YIELD 报工的料成本合计.
     *
     * <p>覆盖两类 INPUT 料: (a) 两点模式 {@code __MATERIAL_INPUT__} 哨兵 task 的领料 INPUT 报工;
     * (b) 二次加工/多段链"领用上道半成品 + 本段原料"的 INPUT 报工 —— 消耗上游 WIP 的成本
     * (sourceWip.unitCost × inputQty) 在报工时已算入该 INPUT report 的 {@code materialCost}.
     * OUTPUT 报工的 {@code sourceWipNo} 在三阶段落库时被置空, 故必须从 batch 的 INPUT-kind 报工聚合,
     * 不能依赖 OUTPUT report 自身。
     *
     * <p>Mode-aware by construction: 逐道模式用 legacy null-kind 整合报工 (料+产出同 report, reportKind=null,
     * 非 INPUT) → 本方法返 null, OUTPUT rollup 退回 per-task own 成本, 逐道行为不变。纯成品直产无 INPUT 报工 → null。
     * 诚实 null: 无 INPUT 料则返 null (不伪造 0)。
     *
     * <p>修复历史: 2026-06-12 P0 两点桥首版只聚合哨兵 task 的料; Codex 多段 rerun 暴露二次加工的 INPUT 报工落在
     * 普通 task 上 (非哨兵) → 旧逻辑漏聚合 → semiB unitCost 永 null。改为按 {@code reportKind=INPUT} 聚合,
     * 统一覆盖两点 (哨兵 INPUT 报工也是 INPUT-kind, 结果不变) + 二次加工两类, 不破坏逐道。
     */
    private BigDecimal calculateBatchInputMaterialCost(String factoryId, Long productionBatchId) {
        if (productionBatchId == null) {
            return null;
        }
        BigDecimal material = null;
        boolean unknownInputMaterial = false;
        for (ProductionReport r : reportRepo.findYieldReportsByBatch(factoryId, productionBatchId)) {
            if ("INPUT".equals(r.getReportKind())) {
                if (r.getMaterialCost() == null) {
                    unknownInputMaterial = true;
                } else {
                    material = (material == null ? BigDecimal.ZERO : material).add(r.getMaterialCost());
                }
            }
        }
        if (unknownInputMaterial) {
            return null;
        }
        return material;
    }

    private record CostRollup(BigDecimal laborCost, BigDecimal materialCost) {}

    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport report, WorkProcessTask task, Long operatorId) {
        // 🔴 必须在改字段【之前】构造流水行 —— consumptionRow 用的是【扣减前】状态。
        //    2026-08-17 实测踩过: 我第一版把这一行放在字段已改之后, 于是
        //    consumed 已经是 2.00, 算出 balanceAfter = 2.00 - (2.00+2.00) = -2.00,
        //    生产上真的落了一条 balance_after=-2.000000 的流水。
        //    ⚠️ 单测没抓到(它自己 new 了个 consumed=0 的对象), 接线测试也没抓到
        //      (只断言了 quantity 和 reportId, 漏了派生出来的 balanceAfter)——
        //      是生产 E2E 抓到的。
        //
        //    这条流水为什么必须写: 走查发现整条链走通后流水里只有 IN 没有 OUT,
        //    余额扣了却查不到是谁领走的。OUT 的语义早就写在实体 javadoc 里, 只是没人写。
        //    由 SemiConsumptionWritesLedgerTest 钉住(含 balanceAfter 断言)。
        SemiFinishedInventoryTransaction outTxn = com.cretas.aims.service.wip.WipLedgerEntries
                .consumptionRow(sourceWip, input, report, task, operatorId);

        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed).add(nz(sourceWip.getAdjustmentQuantity())));
        if (sourceWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            sourceWip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }

        wipRepo.save(sourceWip);
        if (outTxn != null) {
            txnRepo.save(outTxn);
            log.info("[SP1-semi] posted OUT txn for report {} sfi={} qty=-{} balanceAfter={}",
                    report.getId(), sourceWip.getId(), input, outTxn.getBalanceAfter());
        }
        recordWipLineageEdge(report.getFactoryId(), sourceWip, task, input, operatorId);
    }

    private void validateUnit(SemiFinishedInventory sourceWip, String inputUnit) {
        String wipUnit = sourceWip.getUnit();
        if (wipUnit != null && !wipUnit.isBlank()
                && inputUnit != null && !inputUnit.isBlank()
                && !wipUnit.equals(inputUnit)) {
            throw new BusinessException(409, "半成品单位与本道投入单位不一致")
                    .withCode("WIP_UNIT_MISMATCH")
                    .withHint(String.format("WIP 单位为 %s, 本道投入单位为 %s, 跨单位领用需先配置换算系数",
                            wipUnit, inputUnit))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputUnit");
        }
    }

    private BigDecimal pendingReserved(String factoryId, String sourceWipNo, Long excludeReportId) {
        if (factoryId == null || factoryId.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal pending = reportRepo.sumPendingInputBySourceWipNo(factoryId, sourceWipNo, excludeReportId);
        return pending == null ? BigDecimal.ZERO : pending;
    }

    private BigDecimal sourceWipQuantity(ProductionReport report) {
        BigDecimal qty = null;
        if (report.getCustomFields() != null) {
            Object value = report.getCustomFields().get("sourceWipQuantity");
            if (value instanceof BigDecimal bd) {
                qty = bd;
            } else if (value instanceof Number number) {
                qty = BigDecimal.valueOf(number.doubleValue());
            } else if (value instanceof String str && !str.isBlank()) {
                qty = new BigDecimal(str);
            }
        }
        if (qty == null) {
            qty = report.getInputQuantity();
        }
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409, "半成品领用数量必须大于 0")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("请填写实际领用的半成品数量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        if (qty != null && report.getInputQuantity() != null && qty.compareTo(report.getInputQuantity()) > 0) {
            throw new BusinessException(409, "半成品领用数量不能超过本次总投入量")
                    .withCode("WIP_INPUT_EXCEEDS_TOTAL")
                    .withHint("请核对原料领用量和半成品领用量；总投入量必须等于两者合计")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        return qty;
    }

    private void validateAvailable(SemiFinishedInventory sourceWip, BigDecimal inputQuantity, BigDecimal pendingReserved) {
        if (inputQuantity == null) {
            return;
        }
        BigDecimal avail = nz(sourceWip.getAvailableQuantity());
        BigDecimal reserved = nz(pendingReserved);
        BigDecimal claimable = avail.subtract(reserved).max(BigDecimal.ZERO);
        if (inputQuantity.compareTo(claimable) > 0) {
            String u = sourceWip.getUnit() == null ? "" : sourceWip.getUnit();
            if (reserved.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessException(409, "半成品可领余额不足（含待审批占用）")
                        .withCode("WIP_RESERVED_INSUFFICIENT")
                        .withHint(String.format("库存剩余 %s %s，待审批已占用 %s %s，本次申请 %s %s，最多还能申请 %s %s。请减少投入量，或先审批/驳回前面的报工。",
                                avail.stripTrailingZeros().toPlainString(), u,
                                reserved.stripTrailingZeros().toPlainString(), u,
                                inputQuantity.stripTrailingZeros().toPlainString(), u,
                                claimable.stripTrailingZeros().toPlainString(), u))
                        .withSeverity("BLOCKING")
                        .withHintTarget("inputQuantity");
            }
            throw new BusinessException(409, "领用量超过半成品余额")
                    .withCode("WIP_INSUFFICIENT")
                    .withHint(String.format("WIP 余额仅 %s %s, 不能领 %s %s",
                            avail.stripTrailingZeros().toPlainString(), u,
                            inputQuantity.stripTrailingZeros().toPlainString(), u))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
    }

    private void recordWipLineageEdge(String factoryId, SemiFinishedInventory sourceWip,
                                      WorkProcessTask task, BigDecimal qty, Long operatorId) {
        try {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            edge.setEdgeType("WIP_CONSUME");
            edge.setSourceType("PRODUCTION_BATCH");
            edge.setSourceId(String.valueOf(sourceWip.getBatchId() == null
                    ? task.getProductionBatchId() : sourceWip.getBatchId()));
            edge.setTargetType("PRODUCTION_BATCH");
            edge.setTargetId(String.valueOf(task.getProductionBatchId()));
            edge.setQuantityUsed(qty);
            edge.setUnit(sourceWip.getUnit());
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(operatorId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceWipNo", sourceWip.getIntermediateBatchNo());
            meta.put("targetWorkProcessTaskId", task.getId());
            meta.put("targetProcessOrder", task.getProcessOrder());
            edge.setMeta(meta);
            lineageEdgeRepo.save(edge);
        } catch (Exception e) {
            log.warn("[lineage] WIP 领用边写入失败 (fail-soft, 不阻塞报工): sourceWipNo={} batchId={} qty={}",
                    sourceWip.getIntermediateBatchNo(), task.getProductionBatchId(), qty, e);
        }
    }

    private String generateBatchNo(WorkProcessTask task) {
        return String.format("%s-B%d-S%d-%d",
                task.getProductTypeId() == null ? "NA" : task.getProductTypeId(),
                task.getProductionBatchId(),
                task.getProcessOrder() == null ? 0 : task.getProcessOrder(),
                task.getId());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nullSafeAdd(BigDecimal base, BigDecimal... values) {
        BigDecimal out = base;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            out = out == null ? value : out.add(value);
        }
        return out;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    // ========== SP2 新增方法 ====================

    /**
     * SP2 二次加工 — 悲观锁扣减 WIP 余量（二次加工计划创建时调用）。
     * 调用方必须持有 @Transactional。
     */
    @Override
    public void deductForSecondaryPlan(Long wipId, BigDecimal qty, String factoryId, Long operatorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "扣减数量必须大于 0");
        }
        // 悲观写锁防并发超扣
        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                .orElseThrow(() -> new BusinessException(404, "半成品库存行不存在: id=" + wipId));
        if (!factoryId.equals(wip.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配，无权操作该 WIP");
        }
        BigDecimal avail = nz(wip.getAvailableQuantity());
        if (qty.compareTo(avail) > 0) {
            throw new BusinessException(409, String.format(
                    "WIP 余量不足: 请求 %.2f, 可用 %.2f (批次号: %s)",
                    qty, avail, wip.getIntermediateBatchNo()))
                    .withCode("WIP_INSUFFICIENT")
                    .withHint("请减少领用数量或选择其他 WIP 批次");
        }
        BigDecimal newAvail = avail.subtract(qty);
        wip.setAvailableQuantity(newAvail);
        wip.setConsumedQuantity(nz(wip.getConsumedQuantity()).add(qty));
        if (newAvail.compareTo(BigDecimal.ZERO) == 0) {
            wip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(wip);

        // 写 OUT/SECONDARY_CONSUME 流水
        SemiFinishedInventoryTransaction outTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(wip.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("secondary-plan-deduct:" + wipId)
                .quantity(qty.negate())
                .unitCostAtTxn(wip.getUnitCost())
                .balanceAfter(newAvail)
                .balanceCostAfter(wip.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(outTxn);
        log.info("[SP2] deductForSecondaryPlan wipId={} qty={} newAvail={} factoryId={}",
                wipId, qty, newAvail, factoryId);
    }

    /**
     * 修1 (🔴) 二次加工反冲 — {@link #deductForSecondaryPlan} 的逆: 还回开工扣的半成品余量。
     *
     * <p>调用方必须持有 @Transactional。findByIdForUpdate 悲观写锁防并发 (与 deduct 对称)。
     */
    @Override
    public void reverseSecondaryDeduct(Long wipId, BigDecimal qty, String factoryId, Long operatorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "反冲数量必须大于 0");
        }
        // 悲观写锁 (与 deductForSecondaryPlan 对称)
        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                .orElseThrow(() -> new BusinessException(404, "半成品库存行不存在: id=" + wipId));
        if (!factoryId.equals(wip.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配，无权操作该 WIP");
        }
        BigDecimal newAvail = nz(wip.getAvailableQuantity()).add(qty);
        BigDecimal newConsumed = nz(wip.getConsumedQuantity()).subtract(qty);
        // 防御: consumed 不应反冲到负 (反冲量超过历史扣减量)。诚实报错而非静默置 0。
        if (newConsumed.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(409, String.format(
                    "反冲数量超过已消耗量: 请求 %s, 已消耗 %s (批次号: %s)",
                    qty, nz(wip.getConsumedQuantity()), wip.getIntermediateBatchNo()))
                    .withCode("WIP_REVERSE_OVER_CONSUMED");
        }
        wip.setAvailableQuantity(newAvail);
        wip.setConsumedQuantity(newConsumed);
        // 还量后有余量 → 从 DEPLETED 恢复 AVAILABLE (RETURNED 终态不动)。
        if (newAvail.compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
            wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(wip);

        // 写 REVERSE/REVERSAL 反向流水 (数量为正 = 还回); 不修改原 OUT 行 (流水不可变)。
        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(wip.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("secondary-plan-reverse:" + wipId)
                .quantity(qty)
                .unitCostAtTxn(wip.getUnitCost())
                .balanceAfter(newAvail)
                .balanceCostAfter(wip.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[修1] reverseSecondaryDeduct wipId={} qty={} newAvail={} newConsumed={} factoryId={}",
                wipId, qty, newAvail, newConsumed, factoryId);
    }

    /**
     * SP2 二次加工 — 列出工厂所有可用 WIP。
     */
    @Override
    public List<SemiFinishedInventory> listAvailableWip(String factoryId) {
        return wipRepo.findAvailableByFactory(factoryId);
    }

    // ==================== C3: 工厂级半成品重量库存视图 ====================

    /**
     * C3 — 工厂级半成品重量库存快照 (全状态; 不暴露成本字段)。
     *
     * <p>实现策略:
     * <ol>
     *   <li>从 repo 取全状态 WIP 行 (findByFactoryIdForWeightView)。</li>
     *   <li>批量回填 productTypeName: 先收集所有 productTypeId, 一次 findByIdIn,
     *       构建 id→name Map, 避免 N+1 查询。</li>
     * </ol>
     * 成本字段 (accumulatedCost / unitCost) 故意不映射到 DTO — 不暴露给客户侧。
     */
    @Override
    public List<WipRowDTO> listWipByFactory(String factoryId) {
        return listWipByFactory(factoryId, null, null);
    }

    @Override
    public List<WipRowDTO> listWipByFactory(String factoryId, String productTypeId, Integer maxProcessOrder) {
        List<SemiFinishedInventory> rows = wipRepo.findByFactoryIdForWeightView(factoryId);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 防呆过滤 (可选) — 逐道 SFI 投料下拉收窄可选半成品:
        //   阶段: maxProcessOrder 非空 → 仅更早阶段 (processOrder < max, 防回锅); null processOrder 行严格排除 (不变)。
        //   同族: productTypeId 非空 → 解析为"产品族"(以原料为主自动识别), 仅同族半成品 (猪蹄不显牛肉)。
        //         注意: 参数名仍是 productTypeId (前端传当前计划产品), 内部解析成族键再比较 —— 不是按 productTypeId 精确匹配。
        //         07-01 客户澄清: 熟制前半成品在同产品族内通用 (卤猪蹄/椒麻猪蹄/猪蹄冠 共用"猪蹄"半成品),
        //         按 productTypeId 精确过滤会误藏这个通用半成品; 故必须按族过滤 (见 ProductFamilyResolver)。
        // 两参数皆 null → 全量 (向后兼容)。
        final boolean filterFamily = productTypeId != null && !productTypeId.isBlank();
        final boolean filterStage = maxProcessOrder != null;

        // 1) 阶段过滤 (先做, 无 repo, 且缩小待识别族的产品集)。null processOrder 严格排除 (防回锅, 不变)。
        //
        // 🔴 回料放行 (2026-08-28 对抗性审计 Critical 1): 这道闸的本意是防半成品在同一批内回流,
        //    而回料 (金检/X光剔出的不良品) 的业务目的【恰恰是】跨批次回到靠前的工序 —— 它是合法的回锅。
        //    金检 (processOrder=5) 产出的回料, 下一批在绞肉 (maxProcessOrder=1) 开下拉时 5 < 1 为假
        //    ⇒ 回料行不出现, 操作员领不到, 界面上没有任何解释 ⇒「不良品变成下一批领得到的回料」是假的。
        //    ⛔ 修法不是把回料行的 processOrder 改成 0 (那会把出成率链的第一段算成 1900%, 见
        //       SemiFinishedInventory.isRework 的说明), 而是让这道闸认识回料。
        //    ⚠️ 回料行是【工厂级、按 semiCode 跨批次累加】的一行 (与所有半成品行同构), 天然不区分
        //       「哪一批产的」—— 它就是一个工厂级的回料料池, 放行给靠前的工序正是它该有的语义。
        if (filterStage) {
            rows = rows.stream()
                    .filter(w -> w.getProcessOrder() != null
                            && (w.getProcessOrder() < maxProcessOrder || Boolean.TRUE.equals(w.getIsRework())))
                    .collect(Collectors.toList());
            if (rows.isEmpty()) {
                return List.of();
            }
        }

        // 2) 同族过滤: 解析当前计划产品 + 所有候选半成品产品 → 族键, 保留同族。
        //    宁缺勿藏 (诚实, 不过度收窄): 仅排除"族已知且与计划族不同"的行 —— 族未知的候选一律放行
        //    (不隐藏可能合法复用的项); 计划族本身识别不出 → 无从比较, 全放行 (只按阶段过滤)。
        if (filterFamily) {
            Set<String> ptForFamily = rows.stream()
                    .map(SemiFinishedInventory::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            ptForFamily.add(productTypeId);
            Map<String, String> familyMap = productFamilyResolver.resolveFamilies(factoryId, ptForFamily);
            String planFamily = familyMap.get(productTypeId);
            if (planFamily != null) {
                rows = rows.stream()
                        .filter(w -> {
                            String f = w.getProductTypeId() == null ? null : familyMap.get(w.getProductTypeId());
                            return f == null || f.equals(planFamily);   // 族未知放行; 族相同保留; 族不同(牛肉)排除
                        })
                        .collect(Collectors.toList());
                if (rows.isEmpty()) {
                    return List.of();
                }
            }
            // planFamily == null → 计划族识别不出, 不按族过滤 (务实: 不因缺族信息清空可选项)。
        }

        // 批量取 productType 名称 — 避免 N+1
        Set<String> ptIds = rows.stream()
                .map(SemiFinishedInventory::getProductTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        // 3) 回料纯度闸要拿目标产品自己的等级/开关, 所以它也得进这一次批量查询 ——
        //    ⛔ 不要为它单开一次 findById (那是本方法一直在避免的 N+1 的开头)。
        Set<String> ptLookupIds = new HashSet<>(ptIds);
        if (filterFamily) {
            ptLookupIds.add(productTypeId);
        }
        Map<String, String> ptNameMap = new HashMap<>();
        // 盒⇄kg 折算依据: 每盒克重 (计数单位半成品作 kg 道投料来源时前端换算用)。诚实 null: 未配 → null。
        Map<String, BigDecimal> ptGramsMap = new HashMap<>();
        Map<String, ProductType> ptById = new HashMap<>();
        if (!ptLookupIds.isEmpty()) {
            productTypeRepo.findByIdIn(ptLookupIds).forEach(pt -> {
                ptById.put(pt.getId(), pt);
                ptNameMap.put(pt.getId(), pt.getName());
                ptGramsMap.put(pt.getId(), pt.getGramsPerUnit());
            });
        }

        // 3) 回料纯度过滤 —— 「哪些回料能投进这个产品」。⚠️ 只作用于 isRework 行:
        //    良品半成品的可选性由上面的阶段/同族两道闸决定, 与纯度无关, ⛔ 不要顺手一起收窄。
        //
        //    🔴 在此之前四层都不守 (2026-08 逐层实测): 画布连线 (方案丙已移除接线) /
        //       同族过滤 (粒度只有牛肉 vs 猪蹄, 且 planFamily 为 null 时整段跳过) /
        //       阶段过滤 (它管的是回锅, 与纯度无关, 且本来就对回料放行) / RN 下拉 (列全部)
        //       ⇒ 操作员能把熟肉饼的回料领给美式汉堡饼, 系统不拦。
        //
        //    判定本身在 ReworkPurityGuard (纯函数, 无 IO) —— 这里只负责把两侧的产品档案喂给它。
        final ProductType targetProduct = filterFamily ? ptById.get(productTypeId) : null;
        final Map<String, ReworkPurityGuard.Verdict> reworkVerdicts = new HashMap<>();
        if (filterFamily) {
            rows = rows.stream()
                    .filter(w -> {
                        if (!Boolean.TRUE.equals(w.getIsRework())) {
                            return true;
                        }
                        ProductType source = w.getProductTypeId() == null
                                ? null : ptById.get(w.getProductTypeId());
                        ReworkPurityGuard.Verdict verdict = ReworkPurityGuard.evaluate(
                                source == null ? null : source.getReworkPurityLevel(),
                                targetProduct == null ? null : targetProduct.getReworkPurityLevel(),
                                targetProduct == null ? null : targetProduct.getAcceptsRework());
                        if (verdict.allowed() && w.getIntermediateBatchNo() != null) {
                            reworkVerdicts.put(w.getIntermediateBatchNo(), verdict);
                        }
                        return verdict.allowed();
                    })
                    .collect(Collectors.toList());
            if (rows.isEmpty()) {
                return List.of();
            }
        }

        // ② 批次下拉补 生产日期 / 成本: 仅逐道 SFI 投料下拉 (picker context = 带 productTypeId 过滤) 填充,
        //    无参 (看板/盘点) 快照不暴露成本 (C3 "只做重量库存" 口径不变)。
        final boolean pickerContext = filterFamily;
        return rows.stream().map(w -> WipRowDTO.builder()
                .intermediateBatchNo(w.getIntermediateBatchNo())
                .sourceWorkProcessTaskId(w.getSourceWorkProcessTaskId())
                .processOrder(w.getProcessOrder())
                // processName not stored on entity; enrichment via WorkProcess join is N+1 heavy
                // and not needed for factory-level weight view (client groups by productTypeName)
                .productTypeId(w.getProductTypeId())
                .producedQuantity(w.getProducedQuantity())
                .consumedQuantity(w.getConsumedQuantity())
                .availableQuantity(w.getAvailableQuantity())
                .unit(w.getUnit())
                .status(w.getStatus())
                .productTypeName(ptNameMap.get(w.getProductTypeId()))   // null if no match
                .batchId(w.getBatchId())
                // ② picker-only: 生产日期 (createdAt 日期) + 成本 (unitCost, 诚实 null)
                .productionDate(pickerContext && w.getCreatedAt() != null ? w.getCreatedAt().toLocalDate() : null)
                .unitCost(pickerContext ? w.getUnitCost() : null)
                // ② picker-only: 每盒克重 (盒装半成品作 kg 道投料来源时前端 kg⇄盒 折算; 诚实 null)
                .gramsPerUnit(pickerContext ? ptGramsMap.get(w.getProductTypeId()) : null)
                // 回料标记 —— 无参快照也给, 它不是成本信息, 看板同样需要区分回料池和良品。
                .isRework(w.getIsRework())
                // 纯度判定标记: 只有 picker 上下文 (有目标产品) 才谈得上判定; 非回料行恒 null。
                // 🔴 UNCONFIGURED 说的是「没判」而不是「判过了没问题」—— 见 WipRowDTO 的说明。
                .reworkPurityStatus(reworkPurityStatusOf(reworkVerdicts.get(w.getIntermediateBatchNo())))
                .build()
        ).collect(Collectors.toList());
    }

    /**
     * 把纯度判定档位翻成给前端的标记。⛔ 只有两个值 —— 被拦下的行根本不在结果里, 没有 BLOCKED 档。
     *
     * @param verdict 非回料行 / 非 picker 上下文传 null → 返回 null (诚实: 这一行没有纯度判定这回事)
     */
    private static String reworkPurityStatusOf(ReworkPurityGuard.Verdict verdict) {
        if (verdict == null) {
            return null;
        }
        return switch (verdict) {
            case ALLOWED -> "ENFORCED";
            case ALLOWED_PURITY_UNCONFIGURED -> "UNCONFIGURED";
            // 被拦下的行不会走到这里 (上面过滤掉了)。留着让新增档位漏接时编译不过, 而不是静默落 null。
            case BLOCKED_TARGET_REJECTS_REWORK, BLOCKED_SOURCE_DIRTIER_THAN_TARGET -> null;
        };
    }

    // ========== SP1 T4 — Output options endpoint ==========

    /** 与 WorkflowClerkSheetServiceImpl 内同名常量同值 — workflow_task_ports.material_kind 的 SEMI_FINISHED 取值。 */
    private static final String WORKFLOW_MATERIAL_KIND_SEMI_FINISHED = "SEMI_FINISHED";

    @Override
    public OutputOptionsResponse getOutputOptions(String factoryId, Long batchId) {
        List<WorkProcessTask> tasks =
                taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batchId);

        List<OutputOptionsResponse.OutputOptionItem> items = new ArrayList<>();
        for (WorkProcessTask task : tasks) {
            if (task.getWorkProcessId() == null) {
                continue;
            }
            workProcessRepo.findByFactoryIdAndId(factoryId, task.getWorkProcessId())
                    .filter(wp -> wp.getSemiFinishedOutputCode() != null
                            && !wp.getSemiFinishedOutputCode().isBlank())
                    .ifPresent(wp -> items.add(
                            OutputOptionsResponse.OutputOptionItem.builder()
                                    .taskId(task.getId())
                                    .processName(wp.getProcessName())
                                    .semiCode(wp.getSemiFinishedOutputCode())
                                    .processOrder(task.getProcessOrder())
                                    .build()
                    ));
        }

        // Workflow(画布)任务不写 WorkProcess.semiFinishedOutputCode —— 那是旧版"工序管理"模型的
        // 字段。画布任务的产出结构表达在 workflow_task_ports (direction=OUTPUT), 上面那段 legacy
        // 循环对它们恒空。半成品产出候选项改从这里取, 与 legacy 结果合并返回。
        items.addAll(workflowOutputOptions(factoryId, batchId, tasks));

        log.debug("[SP1-T4] getOutputOptions factoryId={} batchId={} → {} items", factoryId, batchId, items.size());
        return OutputOptionsResponse.builder().items(items).build();
    }

    /**
     * SP1 T4 workflow(画布)分支 — 从 {@code workflow_task_ports} 取本批次各任务的半成品产出端口
     * (direction=OUTPUT, materialKind=SEMI_FINISHED), 映射成 {@link OutputOptionsResponse.OutputOptionItem}。
     *
     * <ul>
     *   <li>{@code semiCode} 用 {@code ProductType.code}(如 PTSEMI-F006-2001) —— 工厂内唯一
     *       (product_types 表 (factory_id, code) 唯一约束), 人类可读 (禁止把 skuId UUID 甩给用户),
     *       且与画布上"这是同一个半成品产品"的语义天然对齐(同产品多批次共用一条移动加权 WIP 行,
     *       与旧版 WorkProcess.semiFinishedOutputCode 按工序固定的口径一致)。</li>
     *   <li>副产 (画布 {@code data.isByproduct} 标记) 排除 —— 与 PR #2846 把副产接到报工端时
     *       "不再显示成半成品也不再独立成行"的语义保持一致, 不把副产误当"半成品产出选项"提供。
     *       唯一判据收敛在 {@link WorkflowByproductNodes}, 不在此处另写一份。</li>
     *   <li>回料 (画布 {@code data.isRework} 标记) 与副产相反 —— 刻意<b>保留</b>在选项里, 只是打上
     *       {@code isRework=true} 标记, 不排除: 金检/X光剔出的不良品要能被选成产出目标, 回料才有落库
     *       的地方(见 Task 4 {@code postApprovedOutput} 对 {@code semiOutputs} 逐条入账)。唯一判据收敛
     *       在 {@link WorkflowReworkNodes}, 不在此处另写一份。</li>
     *   <li>一个工序任务在画布上可以有多个 SEMI_FINISHED 产出端口(2B.2 多产出), 因此一个 taskId
     *       可能对应多条 item —— 与 legacy 单产出假设不同, 调用方(RN 报工屏)目前只消费
     *       {@code items[0]}, 多产出的选择 UI 是已知的后续缺口, 不在本次修复范围内。</li>
     * </ul>
     */
    private List<OutputOptionsResponse.OutputOptionItem> workflowOutputOptions(
            String factoryId, Long batchId, List<WorkProcessTask> tasks) {
        ProductionWorkflowInstance instance = workflowInstanceRepo
                .findByFactoryIdAndProductionBatchId(factoryId, batchId)
                .orElse(null);
        if (instance == null) {
            return List.of();
        }
        Set<String> byproductNodeIds =
                WorkflowByproductNodes.byproductMaterialNodeIds(instance.getNodesJson());
        // 回料端口必须出现在选项里 (与副产相反 —— 副产是被过滤掉的)。判据唯一来源 WorkflowReworkNodes。
        Set<String> reworkNodeIds =
                WorkflowReworkNodes.reworkMaterialNodeIds(instance.getNodesJson());

        Map<Long, WorkProcessTask> tasksById = tasks.stream()
                .collect(Collectors.toMap(WorkProcessTask::getId, t -> t, (a, b) -> a));

        List<WorkflowTaskPort> semiOutputPorts = workflowTaskPortRepo
                .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId()).stream()
                .filter(p -> WorkflowTaskPort.Direction.OUTPUT.equals(p.getDirection()))
                .filter(p -> WORKFLOW_MATERIAL_KIND_SEMI_FINISHED.equals(p.getMaterialKind()))
                .filter(p -> !byproductNodeIds.contains(p.getMaterialNodeId()))
                .filter(p -> tasksById.containsKey(p.getTaskId()))
                .collect(Collectors.toList());
        if (semiOutputPorts.isEmpty()) {
            return List.of();
        }

        List<String> skuIds = semiOutputPorts.stream()
                .map(WorkflowTaskPort::getSkuId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, ProductType> productTypesBySku = productTypeRepo
                .findByFactoryIdAndIdIn(factoryId, skuIds).stream()
                .collect(Collectors.toMap(ProductType::getId, pt -> pt, (a, b) -> a));

        List<String> workProcessIds = semiOutputPorts.stream()
                .map(p -> tasksById.get(p.getTaskId()).getWorkProcessId())
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<String, WorkProcess> workProcessesById = workProcessRepo
                .findByFactoryIdAndIdIn(factoryId, workProcessIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp, (a, b) -> a));

        List<OutputOptionsResponse.OutputOptionItem> items = new ArrayList<>();
        for (WorkflowTaskPort port : semiOutputPorts) {
            ProductType productType = productTypesBySku.get(port.getSkuId());
            if (productType == null || productType.getCode() == null || productType.getCode().isBlank()) {
                // 禁止降级处理: 缺可读产品编码时明确跳过并留痕, 不要把 skuId UUID 当 semiCode 塞给用户。
                log.warn("[SP1-T4][workflow] 半成品产出端口缺少可用产品编码, 跳过: factoryId={} taskId={} "
                                + "workflowPortId={} skuId={}",
                        factoryId, port.getTaskId(), port.getWorkflowPortId(), port.getSkuId());
                continue;
            }
            WorkProcessTask task = tasksById.get(port.getTaskId());
            WorkProcess wp = workProcessesById.get(task.getWorkProcessId());
            items.add(OutputOptionsResponse.OutputOptionItem.builder()
                    .taskId(task.getId())
                    .processName(wp != null ? wp.getProcessName() : productType.getName())
                    .semiCode(productType.getCode())
                    .processOrder(task.getProcessOrder())
                    .isRework(reworkNodeIds.contains(port.getMaterialNodeId()))
                    .build());
        }
        return items;
    }
}

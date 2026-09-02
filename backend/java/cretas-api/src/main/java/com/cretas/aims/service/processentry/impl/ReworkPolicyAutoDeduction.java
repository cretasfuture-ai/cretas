package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.workflow.WorkflowReworkDeduction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 正式报工时按画布 {@code reworkPolicy} <b>自动追加</b>回料投料 —— {@link WorkflowReworkDeduction} 的落账端。
 *
 * <h3>它做什么</h3>
 * <ol>
 *   <li>找到这一行对应的画布工序节点（计划钉的 Workflow 运行时快照 → 按 processOrder 找 descriptor）；</li>
 *   <li>主料投入量 = 报工行 {@code inputQuantity}(kg) − 操作员已手工投入的回料。
 *       ⛔ 分母是主料，不含回料（owner: 投 100kg 主料 + 8.5% ⇒ 回料 8.5kg，本工序共投 108.5kg）；</li>
 *   <li>对每条 {@code reworkPolicy.sources}：目标量 = 主料 × 比例；减去已手工投的同 SKU 回料；
 *       缺口从<b>本产品/本工序已授权</b>的回料 SFI（{@link WipInventoryService#listWipByFactory(String, String, Integer)}
 *       的 {@code isRework} 行，与手工选回料走的同一道纯度闸）按生产日期 FIFO 取；</li>
 *   <li>取到的量以 {@code semiFinished=true, automaticRework=true} 追加进 {@code upstreamSources}，
 *       并把 {@code inputQuantity} 加上这部分 —— 之后由 {@code consumeReworkInputs} 与手工回料同一条路严格扣减、
 *       同一条路进小结/结单成本。</li>
 * </ol>
 *
 * <h3>它不做什么</h3>
 * <ul>
 *   <li>⛔ 不拦报工：库存不够只记缺口（{@link ProcessSheetRowResult.ReworkAutoDeduction#getShortfallKg()} + warning）；</li>
 *   <li>⛔ 不兜 0：主料投入量拿不到 ⇒ 这一行不扣、记一条 UNRESOLVED，与
 *       {@link WorkflowReworkDeduction.Outcome#UNRESOLVED_MAIN_INPUT} 同口径；</li>
 *   <li>⛔ 不碰非回料 SFI：候选只来自 {@code is_rework=true} 的行；</li>
 *   <li>只在<b>正式提交</b>跑（草稿保存不扣），且只跑一次（submitRow 本身对已提交行 409）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReworkPolicyAutoDeduction {

    /** 手工投的回料按「SFI 的 productTypeId」归到对应政策行；追加的引用带这个标记好让前端/账本认出来。 */
    public static final String SOURCE_TYPE_AUTO = "REWORK_AUTO";

    private final WorkflowClerkSheetService workflowClerkSheetService;
    private final ProductionWorkflowInstanceRepository workflowInstanceRepository;
    private final SemiFinishedInventoryRepository sfiRepository;
    private final WipInventoryService wipInventoryService;

    /** 本次自动追加的结果：明细给响应，warnings 给行级提示。 */
    public record Outcome(List<ProcessSheetRowResult.ReworkAutoDeduction> lines, List<String> warnings) {
        public static Outcome empty() {
            return new Outcome(List.of(), List.of());
        }
    }

    /**
     * 在 {@code req} 上追加自动回料投料。⚠️ 会修改 {@code req.upstreamSources} 与 {@code req.inputQuantity}。
     *
     * @return 没钉 Workflow / 该工序没配 reworkPolicy 时返回空结果，req 不变。
     */
    public Outcome apply(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (req == null || req.getProcessOrder() == null) {
            return Outcome.empty();
        }
        WorkflowClerkSheetConfigDTO config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        if (config == null || config.getProcesses() == null || config.getWorkflowBatchId() == null) {
            return Outcome.empty(); // 没钉 Workflow 的计划没有 reworkPolicy 可言
        }
        WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor = config.getProcesses().stream()
                .filter(p -> Objects.equals(p.getProcessOrder(), req.getProcessOrder()))
                .findFirst().orElse(null);
        if (descriptor == null || descriptor.getWorkflowNodeId() == null || descriptor.getWorkflowNodeId().isBlank()) {
            return Outcome.empty();
        }
        String nodesJson = workflowInstanceRepository
                .findByFactoryIdAndProductionBatchId(factoryId, config.getWorkflowBatchId())
                .map(ProductionWorkflowInstance::getNodesJson)
                .orElse(null);
        if (nodesJson == null || nodesJson.isBlank()) {
            return Outcome.empty();
        }
        String processNodeId = descriptor.getWorkflowNodeId();

        // 先看这道工序有没有政策 —— 没有就一个字都不动 (绝大多数工序走这条 return)。
        boolean hasPolicy = WorkflowReworkDeduction.compute(nodesJson, Map.of()).stream()
                .anyMatch(line -> processNodeId.equals(line.processNodeId()));
        if (!hasPolicy) {
            return Outcome.empty();
        }

        // 操作员已手工投的回料: 按 SFI 的 productTypeId 分桶 (只认服务端 is_rework=true 的行)。
        Map<String, BigDecimal> manualReworkBySku = new HashMap<>();
        BigDecimal manualReworkTotal = BigDecimal.ZERO;
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if (ref == null || !ref.isSemiFinished() || ref.getSourceBatchNumber() == null
                        || ref.getSourceBatchNumber().isBlank()) {
                    continue;
                }
                SemiFinishedInventory source = sfiRepository
                        .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                factoryId, ref.getSourceBatchNumber().trim())
                        .orElse(null);
                if (source == null || !Boolean.TRUE.equals(source.getIsRework())) {
                    continue;
                }
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (feed.signum() <= 0) {
                    continue;
                }
                manualReworkBySku.merge(source.getProductTypeId(), feed, BigDecimal::add);
                manualReworkTotal = manualReworkTotal.add(feed);
            }
        }

        // 主料 = 行投入量 − 已投回料。inputQuantity 为 null ⇒ 不知道 ⇒ UNRESOLVED (⛔ 不当 0)。
        Map<String, BigDecimal> mainInputByNode = new HashMap<>();
        if (req.getInputQuantity() != null) {
            BigDecimal main = req.getInputQuantity().subtract(manualReworkTotal);
            mainInputByNode.put(processNodeId, main.signum() < 0 ? BigDecimal.ZERO : main);
        }
        List<WorkflowReworkDeduction.Line> lines = WorkflowReworkDeduction.compute(nodesJson, mainInputByNode).stream()
                .filter(line -> processNodeId.equals(line.processNodeId()))
                .toList();

        List<ProcessSheetRowResult.ReworkAutoDeduction> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<WipRowDTO> authorizedRework = null; // 懒取: 只有真要扣时才查
        BigDecimal drawnTotal = BigDecimal.ZERO;
        for (WorkflowReworkDeduction.Line line : lines) {
            ProcessSheetRowResult.ReworkAutoDeduction item = new ProcessSheetRowResult.ReworkAutoDeduction();
            item.setReworkNodeId(line.reworkNodeId());
            item.setReworkSkuId(line.reworkSkuId());
            item.setInputRatioPercent(line.inputRatioPercent());
            item.setMainInputKg(line.mainInputQuantity());
            item.setTargetKg(line.deductQuantity());
            item.setOutcome(line.outcome().name());
            item.setDrawnKg(BigDecimal.ZERO);
            item.setBatches(new ArrayList<>());
            if (line.outcome() == WorkflowReworkDeduction.Outcome.UNRESOLVED_MAIN_INPUT) {
                item.setShortfallKg(null);
                warnings.add("回料未自动扣减：本工序主料投入量未知（比例 "
                        + plain(line.inputRatioPercent()) + "%）");
                out.add(item);
                continue;
            }
            if (line.reworkSkuId() == null || line.reworkSkuId().isBlank()) {
                item.setOutcome("REWORK_SKU_UNBOUND");
                item.setShortfallKg(line.deductQuantity());
                warnings.add("回料未自动扣减：画布上的回料 Cell 没有绑定 SKU（比例 "
                        + plain(line.inputRatioPercent()) + "%）");
                out.add(item);
                continue;
            }
            BigDecimal already = manualReworkBySku.getOrDefault(line.reworkSkuId(), BigDecimal.ZERO);
            BigDecimal remaining = line.deductQuantity().subtract(already);
            item.setManualKg(already);
            if (remaining.signum() <= 0) {
                item.setShortfallKg(BigDecimal.ZERO);
                out.add(item);
                continue; // 操作员手工投的已经够了 (或超了) —— 不再追加
            }
            if (authorizedRework == null) {
                authorizedRework = wipInventoryService
                        .listWipByFactory(factoryId, req.getProductTypeId(), req.getProcessOrder());
            }
            List<WipRowDTO> candidates = authorizedRework.stream()
                    .filter(w -> Boolean.TRUE.equals(w.getIsRework()))
                    .filter(w -> Objects.equals(w.getProductTypeId(), line.reworkSkuId()))
                    .filter(w -> w.getIntermediateBatchNo() != null && nz(w.getAvailableQuantity()).signum() > 0)
                    .sorted(Comparator
                            .comparing((WipRowDTO w) -> w.getProductionDate() == null ? LocalDate.MAX : w.getProductionDate())
                            .thenComparing(WipRowDTO::getIntermediateBatchNo))
                    .toList();
            Map<String, BigDecimal> drawnByBatch = new LinkedHashMap<>();
            for (WipRowDTO candidate : candidates) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal take = candidate.getAvailableQuantity().min(remaining);
                drawnByBatch.merge(candidate.getIntermediateBatchNo(), take, BigDecimal::add);
                remaining = remaining.subtract(take);
            }
            for (Map.Entry<String, BigDecimal> entry : drawnByBatch.entrySet()) {
                ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
                ref.setSourceBatchNumber(entry.getKey());
                ref.setFeedQuantityKg(entry.getValue());
                ref.setSemiFinished(true);
                ref.setAutomaticRework(true);
                ref.setSkuId(line.reworkSkuId());
                ref.setMaterialNodeId(line.reworkNodeId());
                if (req.getUpstreamSources() == null) {
                    req.setUpstreamSources(new ArrayList<>());
                } else if (!(req.getUpstreamSources() instanceof ArrayList)) {
                    req.setUpstreamSources(new ArrayList<>(req.getUpstreamSources()));
                }
                req.getUpstreamSources().add(ref);
                ProcessSheetRowResult.ReworkAutoDeductionBatch b = new ProcessSheetRowResult.ReworkAutoDeductionBatch();
                b.setBatchNumber(entry.getKey());
                b.setQuantityKg(entry.getValue());
                item.getBatches().add(b);
                item.setDrawnKg(item.getDrawnKg().add(entry.getValue()));
                drawnTotal = drawnTotal.add(entry.getValue());
            }
            item.setShortfallKg(remaining.signum() > 0 ? remaining : BigDecimal.ZERO);
            if (remaining.signum() > 0) {
                warnings.add("回料库存不足：按比例应投 " + plain(line.deductQuantity()) + "kg，实际自动扣减 "
                        + plain(item.getDrawnKg().add(already)) + "kg，缺口 " + plain(remaining) + "kg（未拦报工）");
            }
            out.add(item);
        }
        if (drawnTotal.signum() > 0 && req.getInputQuantity() != null) {
            // 回料是真实投入: 本工序共投 = 主料 + 回料 (owner 的 100 / 108.5 例子)。
            req.setInputQuantity(req.getInputQuantity().add(drawnTotal));
        }
        if (!out.isEmpty()) {
            log.info("[rework-auto] factory={} plan={} process#{} node={} lines={} drawn={}kg warnings={}",
                    factoryId, planId, req.getProcessOrder(), processNodeId, out.size(),
                    plain(drawnTotal), warnings.size());
        }
        return new Outcome(out, warnings);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}

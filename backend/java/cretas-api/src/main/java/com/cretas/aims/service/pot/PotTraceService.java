package com.cretas.aims.service.pot;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.pot.ProductionPot;
import com.cretas.aims.entity.pot.ProductionPotInput;
import com.cretas.aims.entity.pot.ProductionPotOutput;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.pot.ProductionPotInputRepository;
import com.cretas.aims.repository.pot.ProductionPotOutputRepository;
import com.cretas.aims.repository.pot.ProductionPotRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S3-16 按锅追溯 / 召回按锅收窄。
 *
 * <p>🔴 收窄规则 (召回漏一个成品就是事故, 所以只在<b>能证明</b>时收窄):
 * <ol>
 *   <li>某个投入节点 (原料批 / 在制批) 在该生产批次的量<b>已全部分到锅上</b> (各锅之和 ≥ 批次级边数量)
 *       ⇒ 受影响的锅 = 分到了这个节点的锅; 否则 (没分完 / 批次量未知) ⇒ 全部锅都算受影响 (不收窄)。</li>
 *   <li>产出节点同理: 该节点的批次量已全部分到锅上且没有一锅来自受影响的锅 ⇒ 才能排除; 否则照算受影响。</li>
 *   <li>没有锅次记录的生产批次 ⇒ 按整批 (批次级召回影响面本来就包含它)。</li>
 * </ol>
 * 批次级召回影响面 ({@code /traceability/impact}) 一行不减; 这里只是加一层「具体是哪几锅」。
 * 待确认的锅 (时间未确认 / 关联不明) 照样计入, 并标 pending。
 */
@Service
@RequiredArgsConstructor
public class PotTraceService {

    private final ProductionPotRepository potRepository;
    private final ProductionPotInputRepository inputRepository;
    private final ProductionPotOutputRepository outputRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final BatchLineageClosureRepository closureRepository;
    private final PotNodeIndex nodeIndex;
    private final ProductionPotService potService;

    public record PotBrief(Long potId, Long productionBatchId, String processCode, String processName,
                           Integer potNumber, String equipmentLabel, LocalDateTime startedAt, LocalDateTime endedAt,
                           boolean pending, BigDecimal inputQuantity, String inputUnit) {}

    public record NodeRef(String nodeType, String nodeId, String batchNumber) {}

    /** 一个投入节点 × 一个有锅的生产批次: 这个节点进了哪几锅、出到哪里。 */
    public record BatchPotScope(
            Long productionBatchId,
            String productionBatchNumber,
            String inputNodeType,
            String inputNodeId,
            String inputBatchNumber,
            int potCount,
            boolean narrowed,
            String reason,
            BigDecimal batchQuantity,
            BigDecimal allocatedQuantity,
            String unit,
            List<PotBrief> affectedPots,
            int pendingAffectedPots,
            boolean outputsNarrowed,
            List<NodeRef> affectedOutputs,
            List<NodeRef> excludedOutputs,
            List<NodeRef> downstreamFinishedGoods) {}

    public record MaterialPotTrace(String nodeType, String nodeId, String batchNumber, List<BatchPotScope> batches) {}

    public record PotForwardTrace(ProductionPotService.PotView pot, List<NodeRef> directOutputs,
                                  List<NodeRef> finishedGoods) {}

    public record OutputPotTrace(String nodeType, String nodeId, String batchNumber, Long productionBatchId,
                                 boolean narrowed, String reason, List<ProductionPotService.PotView> pots) {}

    // ==================== 原料批 → 锅 → 成品 ====================

    /** 哪些锅用了这个批次 (直接投入, 以及经在制批传下去后进入有锅的批次)。 */
    @Transactional(readOnly = true)
    public MaterialPotTrace potsConsuming(String factoryId, String nodeType, String nodeId) {
        requireNode(factoryId, nodeType, nodeId);
        Set<String> impacted = new LinkedHashSet<>();
        impacted.add(PotNodeIndex.key(nodeType, nodeId));
        for (BatchLineageClosure c : closureRepository.findDescendants(factoryId, nodeId, nodeType)) {
            impacted.add(PotNodeIndex.key(c.getDescendantType(), c.getDescendantId()));
        }
        List<BatchPotScope> scopes = scopesFor(factoryId, impacted, PotNodeIndex.key(nodeType, nodeId));
        String label = nodeIndex.labels(factoryId, List.of(PotNodeIndex.key(nodeType, nodeId)))
                .get(PotNodeIndex.key(nodeType, nodeId));
        return new MaterialPotTrace(nodeType, nodeId, label, scopes);
    }

    /**
     * 召回影响面的按锅收窄 (LineageGraphAssembler#assembleImpact 调用)。
     *
     * @param impactedKeys 影响面里的全部节点 ("TYPE#id", 含根)
     * @param potlessRootKey 这个节点直接进入的无锅批次也列出来 (说明「没有锅次, 按整批」); null = 只列有锅的
     */
    @Transactional(readOnly = true)
    public List<BatchPotScope> scopesFor(String factoryId, Collection<String> impactedKeys, String potlessRootKey) {
        List<BatchPotScope> out = new ArrayList<>();
        for (String key : impactedKeys) {
            int i = key.indexOf('#');
            String type = key.substring(0, i);
            String id = key.substring(i + 1);
            if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(type)
                    || LineageEdgeRecorder.NODE_SHIPMENT_RECORD.equals(type)) {
                continue;
            }
            for (Long pbId : nodeIndex.productionBatchesConsuming(factoryId, type, id)) {
                BatchPotScope scope = scope(factoryId, pbId, type, id, key.equals(potlessRootKey));
                if (scope != null) {
                    out.add(scope);
                }
            }
        }
        return out;
    }

    private BatchPotScope scope(String factoryId, Long pbId, String inType, String inId, boolean includePotless) {
        List<ProductionPot> pots = potRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessCodeAscPotNumberAscIdAsc(factoryId, pbId).stream()
                .filter(p -> !p.isVoided())
                .toList();
        if (pots.isEmpty() && !includePotless) {
            return null;
        }
        String inKey = PotNodeIndex.key(inType, inId);
        Map<String, PotNodeIndex.BatchNode> inNodes = nodeIndex.inputsOf(factoryId, pbId);
        Map<String, PotNodeIndex.BatchNode> outNodes = nodeIndex.outputsOf(factoryId, pbId);
        PotNodeIndex.BatchNode inNode = inNodes.get(inKey);
        Set<String> labelKeys = new HashSet<>(outNodes.keySet());
        labelKeys.add(inKey);
        labelKeys.add(PotNodeIndex.key(LineageEdgeRecorder.NODE_PRODUCTION_BATCH, String.valueOf(pbId)));
        Map<String, String> labels = nodeIndex.labels(factoryId, labelKeys);
        String pbLabel = labels.get(PotNodeIndex.key(LineageEdgeRecorder.NODE_PRODUCTION_BATCH, String.valueOf(pbId)));

        if (pots.isEmpty()) {
            List<NodeRef> allOut = refs(outNodes.keySet(), labels);
            return new BatchPotScope(pbId, pbLabel, inType, inId, labels.get(inKey), 0, false,
                    "该生产批次没有锅次记录, 按整批召回",
                    inNode == null ? null : inNode.quantity(), BigDecimal.ZERO, inNode == null ? null : inNode.unit(),
                    List.of(), 0, false, allOut, List.of(), finishedGoodsFrom(factoryId, outNodes.keySet(), labels));
        }

        List<Long> potIds = pots.stream().map(ProductionPot::getId).toList();
        List<ProductionPotInput> inputs = inputRepository.findByFactoryIdAndPotIdIn(factoryId, potIds);
        List<ProductionPotOutput> outputs = outputRepository.findByFactoryIdAndPotIdIn(factoryId, potIds);
        Map<Long, BigDecimal> qtyByPot = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (ProductionPotInput in : inputs) {
            if (inKey.equals(PotNodeIndex.key(in.getSourceType(), in.getSourceId()))) {
                qtyByPot.merge(in.getPotId(), in.getQuantity(), BigDecimal::add);
                allocated = allocated.add(in.getQuantity());
            }
        }
        boolean complete = inNode != null && inNode.quantity() != null && allocated.compareTo(inNode.quantity()) >= 0;
        String reason;
        List<ProductionPot> affected;
        if (complete) {
            affected = pots.stream().filter(p -> qtyByPot.containsKey(p.getId())).toList();
            reason = "该批次投入已全部分到锅上, 按锅收窄到 " + affected.size() + " / " + pots.size() + " 锅";
        } else {
            affected = pots;
            reason = inNode == null || inNode.quantity() == null
                    ? "批次投入数量未知, 无法按锅收窄, 全部锅都算受影响"
                    : "已分到锅上 " + plain(allocated) + " / " + plain(inNode.quantity())
                            + Objects.toString(inNode.unit(), "") + ", 未分完, 全部锅都算受影响";
        }
        Set<Long> affectedIds = affected.stream().map(ProductionPot::getId).collect(Collectors.toSet());

        // 产出: 被受影响的锅出到的节点必算; 其它节点只有「批次量已全部分到锅上且不含受影响的锅」才能排除
        Map<String, BigDecimal> outAllocated = ProductionPotService.sumOutputs(outputs);
        Set<String> touched = outputs.stream().filter(o -> affectedIds.contains(o.getPotId()))
                .map(o -> PotNodeIndex.key(o.getTargetType(), o.getTargetId())).collect(Collectors.toSet());
        List<String> affectedOut = new ArrayList<>();
        List<String> excludedOut = new ArrayList<>();
        for (PotNodeIndex.BatchNode n : outNodes.values()) {
            boolean fullyAllocated = n.quantity() != null
                    && outAllocated.getOrDefault(n.key(), BigDecimal.ZERO).compareTo(n.quantity()) >= 0;
            if (!touched.contains(n.key()) && fullyAllocated) {
                excludedOut.add(n.key());
            } else {
                affectedOut.add(n.key());
            }
        }

        List<PotBrief> briefs = affected.stream()
                .map(p -> new PotBrief(p.getId(), p.getProductionBatchId(), p.getProcessCode(), p.getProcessName(),
                        p.getPotNumber(), p.getEquipmentLabel(), p.getStartedAt(), p.getEndedAt(),
                        ProductionPotService.isPending(p), qtyByPot.get(p.getId()),
                        inNode == null ? null : inNode.unit()))
                .toList();
        int pending = (int) affected.stream().filter(ProductionPotService::isPending).count();
        return new BatchPotScope(pbId, pbLabel, inType, inId, labels.get(inKey), pots.size(), complete, reason,
                inNode == null ? null : inNode.quantity(), allocated, inNode == null ? null : inNode.unit(),
                briefs, pending, !excludedOut.isEmpty(), refs(affectedOut, labels), refs(excludedOut, labels),
                finishedGoodsFrom(factoryId, affectedOut, labels));
    }

    // ==================== 锅 → 成品 ====================

    @Transactional(readOnly = true)
    public PotForwardTrace forward(String factoryId, Long potId) {
        ProductionPotService.PotView pot = potService.get(factoryId, potId);
        List<String> keys = pot.outputs().stream()
                .map(o -> PotNodeIndex.key(o.nodeType(), o.nodeId())).toList();
        Map<String, String> labels = nodeIndex.labels(factoryId, keys);
        return new PotForwardTrace(pot, refs(keys, labels), finishedGoodsFrom(factoryId, keys, labels));
    }

    // ==================== 成品 / 在制批 → 来自哪几锅 ====================

    @Transactional(readOnly = true)
    public OutputPotTrace potsProducing(String factoryId, String nodeType, String nodeId) {
        requireNode(factoryId, nodeType, nodeId);
        String key = PotNodeIndex.key(nodeType, nodeId);
        String label = nodeIndex.labels(factoryId, List.of(key)).get(key);
        Set<Long> producingBatches = new LinkedHashSet<>();
        for (Long pb : producingBatchesOf(factoryId, nodeType, nodeId)) {
            producingBatches.add(pb);
        }
        List<ProductionPot> pots = potRepository.findByFactoryIdAndProductionBatchIdIn(factoryId, producingBatches).stream()
                .filter(p -> !p.isVoided()).toList();
        if (pots.isEmpty()) {
            return new OutputPotTrace(nodeType, nodeId, label, producingBatches.stream().findFirst().orElse(null),
                    false, "产出它的生产批次没有锅次记录", List.of());
        }
        List<ProductionPotOutput> rows = outputRepository.findByFactoryIdAndPotIdIn(factoryId,
                pots.stream().map(ProductionPot::getId).toList());
        BigDecimal allocated = BigDecimal.ZERO;
        Set<Long> fromPots = new HashSet<>();
        for (ProductionPotOutput o : rows) {
            if (key.equals(PotNodeIndex.key(o.getTargetType(), o.getTargetId()))) {
                allocated = allocated.add(o.getQuantity());
                fromPots.add(o.getPotId());
            }
        }
        BigDecimal batchQty = null;
        for (Long pb : producingBatches) {
            PotNodeIndex.BatchNode n = nodeIndex.outputsOf(factoryId, pb).get(key);
            if (n == null || n.quantity() == null) {
                batchQty = null;
                break;
            }
            batchQty = batchQty == null ? n.quantity() : batchQty.add(n.quantity());
        }
        boolean complete = batchQty != null && allocated.compareTo(batchQty) >= 0;
        List<ProductionPot> result = complete ? pots.stream().filter(p -> fromPots.contains(p.getId())).toList() : pots;
        String reason = complete ? "产出已全部分到锅上, 来自 " + result.size() + " 锅"
                : "产出未全部分到锅上, 可能来自该批次任一锅";
        return new OutputPotTrace(nodeType, nodeId, label, producingBatches.stream().findFirst().orElse(null),
                complete, reason, potService.toViews(factoryId, result));
    }

    // ==================== 内部 ====================

    private Set<Long> producingBatchesOf(String factoryId, String nodeType, String nodeId) {
        Set<Long> out = new LinkedHashSet<>();
        for (BatchLineageClosure c : closureRepository.findAncestors(factoryId, nodeId, nodeType)) {
            if (c.getDepth() != null && c.getDepth() == 1
                    && LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(c.getAncestorType())) {
                Long pb = PotNodeIndex.parseLong(c.getAncestorId());
                if (pb != null) {
                    out.add(pb);
                }
            }
        }
        // 闭包缺失时 (触发器之外写入的边) 兜底读直接边: 结果相同, 只是来源不同
        out.addAll(nodeIndex.productionBatchesProducing(factoryId, nodeType, nodeId));
        return out;
    }

    /** 这些节点 (含自身) 往下游的成品批。 */
    private List<NodeRef> finishedGoodsFrom(String factoryId, Collection<String> keys, Map<String, String> knownLabels) {
        Set<String> fg = new LinkedHashSet<>();
        for (String k : keys) {
            int i = k.indexOf('#');
            String type = k.substring(0, i);
            String id = k.substring(i + 1);
            if (LineageEdgeRecorder.NODE_FINISHED_BATCH.equals(type)) {
                fg.add(k);
            }
            for (BatchLineageClosure c : closureRepository.findDescendants(factoryId, id, type)) {
                if (LineageEdgeRecorder.NODE_FINISHED_BATCH.equals(c.getDescendantType())) {
                    fg.add(PotNodeIndex.key(c.getDescendantType(), c.getDescendantId()));
                }
            }
        }
        Map<String, String> labels = new LinkedHashMap<>(knownLabels);
        Set<String> missing = fg.stream().filter(k -> !labels.containsKey(k)).collect(Collectors.toSet());
        labels.putAll(nodeIndex.labels(factoryId, missing));
        return refs(fg, labels);
    }

    private static List<NodeRef> refs(Collection<String> keys, Map<String, String> labels) {
        List<NodeRef> out = new ArrayList<>();
        for (String k : keys) {
            int i = k.indexOf('#');
            out.add(new NodeRef(k.substring(0, i), k.substring(i + 1), labels.get(k)));
        }
        return out;
    }

    private void requireNode(String factoryId, String nodeType, String nodeId) {
        boolean ok = switch (nodeType == null ? "" : nodeType) {
            case LineageEdgeRecorder.NODE_MATERIAL_BATCH -> materialBatchRepository.findById(nodeId)
                    .filter(m -> factoryId.equals(m.getFactoryId())).isPresent();
            case LineageEdgeRecorder.NODE_FINISHED_BATCH -> finishedGoodsBatchRepository.findById(nodeId)
                    .filter(f -> factoryId.equals(f.getFactoryId())).isPresent();
            default -> throw new BusinessException(400, "按锅追溯只支持原料/在制批次 (MATERIAL_BATCH) 和成品批次 (FINISHED_BATCH)");
        };
        if (!ok) {
            throw new BusinessException(404, "当前工厂未找到该批次")
                    .withHint("请核对批次类型和 ID; 查不到不等于没有用到")
                    .withHintTarget("batchId");
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}

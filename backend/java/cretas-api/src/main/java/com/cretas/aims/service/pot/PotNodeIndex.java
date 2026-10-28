package com.cretas.aims.service.pot;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S3-16 锅次: 一个生产批次的批次级投入 / 产出节点 (batch_lineage_edges ∪ 事务内的投料 / 在制批记录,
 * 全部按工厂过滤), 以及节点的展示批号。锅次分摊只能落在这些节点上。
 */
@Component
@RequiredArgsConstructor
public class PotNodeIndex {

    private final BatchLineageEdgeRepository edgeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final MaterialConsumptionRepository consumptionRepository;

    static final String SOURCE_DOC_PRODUCTION_BATCH = "PRODUCTION_BATCH";

    /** 节点键 "TYPE#id"。 */
    public static String key(String type, String id) {
        return type + "#" + id;
    }

    /**
     * 批次级的一个节点: 所有同向边的数量之和。任一条边没有数量或单位不一致 ⇒
     * {@code quantity == null}: 不能做上限校验, 也不能据此判定「已全部分到锅上」。
     */
    public record BatchNode(String type, String id, BigDecimal quantity, String unit) {
        public String key() {
            return PotNodeIndex.key(type, id);
        }
    }

    /**
     * 进入该生产批次的节点 (key → 节点)。
     *
     * <p>🔴 血缘边在业务事务<b>提交之后</b>才写 (LineageEdgeRecorder afterCommit, fail-soft), 报工同一事务里
     * 还读不到; 所以同时读事务内就有的权威记录 —— 投料 {@code material_consumptions} (血缘边本身就是从它派生的,
     * 同一批次两边都有时以投料为准, 不重复计)。
     */
    public Map<String, BatchNode> inputsOf(String factoryId, Long productionBatchId) {
        Map<String, BatchNode> out = aggregate(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(
                factoryId, String.valueOf(productionBatchId), LineageEdgeRecorder.NODE_PRODUCTION_BATCH), true);
        Map<String, List<MaterialConsumption>> byBatch = new LinkedHashMap<>();
        for (MaterialConsumption c : consumptionRepository.findByProductionBatchIdAndFactoryId(productionBatchId, factoryId)) {
            if (c.getBatchId() != null && c.getQuantity() != null) {
                byBatch.computeIfAbsent(c.getBatchId(), k -> new ArrayList<>()).add(c);
            }
        }
        if (!byBatch.isEmpty()) {
            Map<String, String> units = new HashMap<>();
            for (MaterialBatch mb : materialBatchRepository.findAllById(byBatch.keySet())) {
                if (factoryId.equals(mb.getFactoryId())) {
                    units.put(mb.getId(), mb.getQuantityUnit());
                }
            }
            byBatch.forEach((batchId, rows) -> {
                BigDecimal sum = rows.stream().map(MaterialConsumption::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
                String k = key(LineageEdgeRecorder.NODE_MATERIAL_BATCH, batchId);
                out.put(k, new BatchNode(LineageEdgeRecorder.NODE_MATERIAL_BATCH, batchId, sum, units.get(batchId)));
            });
        }
        return out;
    }

    /**
     * 该生产批次产出的节点 (key → 节点)。同上, 血缘边之外再读事务内就有的在制库存批
     * ({@code material_batches.source_doc_type = PRODUCTION_BATCH}); 它的产出量取生产批次产量
     * (在制批的 receiptQuantity 会随下游消耗减少, 不能当产出量)。
     */
    public Map<String, BatchNode> outputsOf(String factoryId, Long productionBatchId) {
        Map<String, BatchNode> out = aggregate(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(
                factoryId, String.valueOf(productionBatchId), LineageEdgeRecorder.NODE_PRODUCTION_BATCH), false);
        materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                        factoryId, SOURCE_DOC_PRODUCTION_BATCH, String.valueOf(productionBatchId))
                .ifPresent(wip -> {
                    String k = key(LineageEdgeRecorder.NODE_MATERIAL_BATCH, wip.getId());
                    if (!out.containsKey(k)) {
                        ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(productionBatchId, factoryId)
                                .orElse(null);
                        // 文员逐道录入只写 quantity (= 本道产出; 只有产出 > 0 才物化在制批, 所以这里不会是
                        // 无产出时的占位 1), actualQuantity 由其它报工入口写; 两者都没有 ⇒ 未知, 不收窄
                        BigDecimal qty = pb == null ? null
                                : pb.getActualQuantity() != null ? pb.getActualQuantity() : pb.getQuantity();
                        String unit = pb != null && pb.getUnit() != null ? pb.getUnit() : wip.getQuantityUnit();
                        out.put(k, new BatchNode(LineageEdgeRecorder.NODE_MATERIAL_BATCH, wip.getId(), qty, unit));
                    }
                });
        return out;
    }

    /** 某节点作为投入进入了哪些生产批次 (血缘边 ∪ 投料记录)。 */
    public Set<Long> productionBatchesConsuming(String factoryId, String type, String id) {
        Set<Long> out = new LinkedHashSet<>();
        for (BatchLineageEdge e : edgeRepository.findByFactoryIdAndSourceIdAndSourceType(factoryId, id, type)) {
            if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getTargetType())) {
                Long pb = parseLong(e.getTargetId());
                if (pb != null) {
                    out.add(pb);
                }
            }
        }
        if (LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(type)) {
            for (MaterialConsumption c : consumptionRepository.findByFactoryIdAndBatchId(factoryId, id)) {
                if (c.getProductionBatchId() != null) {
                    out.add(c.getProductionBatchId());
                }
            }
        }
        return out;
    }

    /** 哪些生产批次产出了这个节点 (血缘边 ∪ 在制批的来源单据)。 */
    public Set<Long> productionBatchesProducing(String factoryId, String type, String id) {
        Set<Long> out = new LinkedHashSet<>();
        for (BatchLineageEdge e : edgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, id, type)) {
            if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getSourceType())) {
                Long pb = parseLong(e.getSourceId());
                if (pb != null) {
                    out.add(pb);
                }
            }
        }
        if (LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(type)) {
            materialBatchRepository.findByIdAndFactoryId(id, factoryId)
                    .filter(mb -> SOURCE_DOC_PRODUCTION_BATCH.equals(mb.getSourceDocType()))
                    .map(mb -> parseLong(mb.getSourceDocId()))
                    .ifPresent(out::add);
        }
        return out;
    }

    private static Map<String, BatchNode> aggregate(List<BatchLineageEdge> edges, boolean sourceSide) {
        Map<String, List<BatchLineageEdge>> grouped = new LinkedHashMap<>();
        for (BatchLineageEdge e : edges) {
            String type = sourceSide ? e.getSourceType() : e.getTargetType();
            String id = sourceSide ? e.getSourceId() : e.getTargetId();
            if (type == null || id == null) {
                continue;
            }
            grouped.computeIfAbsent(key(type, id), k -> new ArrayList<>()).add(e);
        }
        Map<String, BatchNode> out = new LinkedHashMap<>();
        grouped.forEach((k, list) -> {
            BatchLineageEdge first = list.get(0);
            String type = sourceSide ? first.getSourceType() : first.getTargetType();
            String id = sourceSide ? first.getSourceId() : first.getTargetId();
            BigDecimal sum = BigDecimal.ZERO;
            String unit = null;
            boolean known = true;
            for (BatchLineageEdge e : list) {
                if (e.getQuantityUsed() == null) {
                    known = false;
                    break;
                }
                if (unit == null) {
                    unit = e.getUnit();
                } else if (e.getUnit() != null && !sameUnit(unit, e.getUnit())) {
                    known = false;
                    break;
                }
                sum = sum.add(e.getQuantityUsed());
            }
            out.put(k, new BatchNode(type, id, known ? sum : null, known ? unit : first.getUnit()));
        });
        return out;
    }

    public static boolean sameUnit(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    /** 节点展示批号 (只取本厂的实体; 查不到时为 null, 前端显示 id)。 */
    public Map<String, String> labels(String factoryId, Collection<String> keys) {
        Set<String> materialIds = new HashSet<>();
        Set<String> finishedIds = new HashSet<>();
        Set<Long> productionIds = new HashSet<>();
        for (String k : keys) {
            int i = k.indexOf('#');
            if (i < 0) {
                continue;
            }
            String type = k.substring(0, i);
            String id = k.substring(i + 1);
            switch (type) {
                case LineageEdgeRecorder.NODE_MATERIAL_BATCH -> materialIds.add(id);
                case LineageEdgeRecorder.NODE_FINISHED_BATCH -> finishedIds.add(id);
                case LineageEdgeRecorder.NODE_PRODUCTION_BATCH -> {
                    Long pb = parseLong(id);
                    if (pb != null) {
                        productionIds.add(pb);
                    }
                }
                default -> { }
            }
        }
        Map<String, String> out = new HashMap<>();
        for (MaterialBatch mb : materialBatchRepository.findAllById(materialIds)) {
            if (factoryId.equals(mb.getFactoryId())) {
                out.put(key(LineageEdgeRecorder.NODE_MATERIAL_BATCH, mb.getId()), mb.getBatchNumber());
            }
        }
        for (FinishedGoodsBatch fg : finishedGoodsBatchRepository.findAllById(finishedIds)) {
            if (factoryId.equals(fg.getFactoryId())) {
                out.put(key(LineageEdgeRecorder.NODE_FINISHED_BATCH, fg.getId()), fg.getBatchNumber());
            }
        }
        for (ProductionBatch pb : productionBatchRepository.findAllById(productionIds)) {
            if (factoryId.equals(pb.getFactoryId())) {
                out.put(key(LineageEdgeRecorder.NODE_PRODUCTION_BATCH, String.valueOf(pb.getId())),
                        pb.getBatchNumber());
            }
        }
        return out;
    }

    static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

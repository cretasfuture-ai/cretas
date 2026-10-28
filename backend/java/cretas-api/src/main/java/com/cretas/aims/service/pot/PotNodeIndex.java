package com.cretas.aims.service.pot;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S3-16 锅次: 一个生产批次的批次级投入 / 产出节点 (取自 batch_lineage_edges, 按工厂过滤),
 * 以及节点的展示批号。锅次分摊只能落在这些节点上。
 */
@Component
@RequiredArgsConstructor
public class PotNodeIndex {

    private final BatchLineageEdgeRepository edgeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ProductionBatchRepository productionBatchRepository;

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

    /** 进入该生产批次的节点 (key → 节点)。 */
    public Map<String, BatchNode> inputsOf(String factoryId, Long productionBatchId) {
        return aggregate(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(
                factoryId, String.valueOf(productionBatchId), LineageEdgeRecorder.NODE_PRODUCTION_BATCH), true);
    }

    /** 该生产批次产出的节点 (key → 节点)。 */
    public Map<String, BatchNode> outputsOf(String factoryId, Long productionBatchId) {
        return aggregate(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(
                factoryId, String.valueOf(productionBatchId), LineageEdgeRecorder.NODE_PRODUCTION_BATCH), false);
    }

    /** 某节点作为投入进入了哪些生产批次。 */
    public Set<Long> productionBatchesConsuming(String factoryId, String type, String id) {
        Set<Long> out = new HashSet<>();
        for (BatchLineageEdge e : edgeRepository.findByFactoryIdAndSourceIdAndSourceType(factoryId, id, type)) {
            if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getTargetType())) {
                Long pb = parseLong(e.getTargetId());
                if (pb != null) {
                    out.add(pb);
                }
            }
        }
        return out;
    }

    /** 哪些生产批次产出了这个节点 (直接边)。 */
    public Set<Long> productionBatchesProducing(String factoryId, String type, String id) {
        Set<Long> out = new HashSet<>();
        for (BatchLineageEdge e : edgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, id, type)) {
            if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getSourceType())) {
                Long pb = parseLong(e.getSourceId());
                if (pb != null) {
                    out.add(pb);
                }
            }
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

package com.cretas.aims.service.quality;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成品属于计划里哪个生产批次 (Owner 2026-11-09 裁定) —— 计划级放行 ({@link QualityDispositionInventoryService})
 * 和质检放行 ({@code QcReleaseService}) 共用这一份判据, 不各写一份 (形态 D)。
 */
public final class PlanGoodsOwnership {

    private PlanGoodsOwnership() {
    }

    /**
     * 成品归属 (Owner 2026-11-09): 先用血缘边 PRODUCTION_TO_FINISHED; 边不全 (有的成品有边、有的没有) 或指向计划外
     * 算分不清; 一条边都没有时, 批号相同或计划只有一批算确定。⛔ 不按 SKU、不按时间先后猜。
     *
     * @return outsidePlan != null: 有边指向本计划以外的生产批次 (无论要不要区分都不放);
     *         unknown != null: 有成品分不清归属 (只在需要区分时才挡)
     */
    public static Ownership attribute(BatchLineageEdgeRepository batchLineageEdgeRepository, String factoryId,
                                      List<FinishedGoodsBatch> goods, List<ProductionBatch> planBatches) {
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

    public record Ownership(Map<String, Set<Long>> byGoods, String unknown, String outsidePlan) {
        public Set<Long> of(FinishedGoodsBatch fg) {
            return byGoods.getOrDefault(fg.getId(), Set.of());
        }
    }
}

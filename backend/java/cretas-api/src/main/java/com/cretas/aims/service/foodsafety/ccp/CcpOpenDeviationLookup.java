package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.service.quality.release.QcReleaseBlocker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 缺口 13 —— 「这一批有没有未处理的 CCP 偏离」的<b>唯一判定口径</b>。
 *
 * <h2>口径: 这一批 + 它的下游 (Steve 2026-11-03)</h2>
 * 先按「批次号命中 或 同一生产计划」找出候选偏离, 再逐条问 {@link CcpDeviationScope.Scope#covers}:
 * 产出在偏离那一道之后且工艺图上走得到 / 就是出事的这一批 → 挡; 更早的道 → 不挡
 * (以前同计划一律挡, 连偏离之前就产出的上游料也被扣)。不知道在哪一道的仍然挡 (宁可多挡)。
 * 条数按偏离记录去重 (以前同一条既命中批号又命中计划会数两次, 放行页显示「2 条未处理」而实际 1 条)。
 *
 * <p>用在三处 (⛔ 不在别处再写一遍): 质检放行前的拦截 ({@link QcReleaseBlocker})、
 * 产出入账时要不要扣成待检 ({@code QcInspectionPolicy})、出货时的 HACCP 放行闸
 * ({@code SalesServiceImpl#assertHaccpReleaseAllowed} 用 {@link #isOpenDeviation})。
 */
@Component
@RequiredArgsConstructor
public class CcpOpenDeviationLookup implements QcReleaseBlocker {

    /** 逐道录入常驻半成品锚 {@code WipInventoryService#clerkSemiAnchor}: CLK-SEMI-<计划前8位>-<产品前8位>。 */
    static final String CLERK_SEMI_ANCHOR_PREFIX = "CLK-SEMI-";

    private final HaccpMonitoringRecordRepository recordRepo;
    private final ProductionBatchRepository productionBatchRepo;

    /** 反查半成品行的入账批次 (审查高 3)。required=false: 只用到出货判据的单测可不注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryRepository semiRepo;

    /** 偏离管到哪儿。required=false: 不注入 (旧单测) = 同计划全挡, 即旧口径。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CcpDeviationScope scope;

    public void setScope(CcpDeviationScope scope) {
        this.scope = scope;
    }

    /** 测试 / 装配用。 */
    public void setSemiRepo(com.cretas.aims.repository.SemiFinishedInventoryRepository semiRepo) {
        this.semiRepo = semiRepo;
    }

    /** 出货闸等只拿到记录行的地方用这一个判据: 偏离且未处理。 */
    public static boolean isOpenDeviation(HaccpMonitoringRecord r) {
        return r != null && r.isOpenDeviation();
    }

    /** 未处理偏离条数 (不分道): 批次号集合 ∪ 生产计划, 按记录去重。 */
    public long countOpen(String factoryId, Collection<String> batchNumbers, String planId) {
        if (factoryId == null) {
            return 0L;
        }
        return candidates(factoryId, clean(batchNumbers),
                planId == null || planId.isBlank() ? Set.of() : Set.of(planId), false).size();
    }

    /** 产出入账时: 这次产出 (在第 processOrder 道; null = 由生产批次推, 推不出 = 不分道) 有没有归它管的未处理偏离。 */
    public boolean hasOpen(String factoryId, Collection<String> batchNumbers, String planId, Long productionBatchId,
                           Integer processOrder) {
        Set<String> nos = clean(batchNumbers);
        String plan = planId;
        Integer order = processOrder;
        if (order == null && scope != null) {
            order = scope.orderOfProductionBatch(factoryId, productionBatchId);
        }
        if (productionBatchId != null) {
            ProductionBatch pb = productionBatchRepo.findByIdAndFactoryId(productionBatchId, factoryId).orElse(null);
            if (pb != null) {
                if (pb.getBatchNumber() != null) {
                    nos.add(pb.getBatchNumber());
                }
                if (plan == null) {
                    plan = pb.getProductionPlanId();
                }
            }
        }
        if (factoryId == null) {
            return false;
        }
        Set<Long> pbIds = productionBatchId == null ? Set.of() : Set.of(productionBatchId);
        return countCovering(factoryId, candidates(factoryId, nos,
                plan == null || plan.isBlank() ? Set.of() : Set.of(plan), true), order, pbIds, nos) > 0;
    }

    // ==================== QcReleaseBlocker ====================

    @Override
    public long openDeviationsForSemi(String factoryId, SemiFinishedInventory semi) {
        if (semi == null) {
            return 0L;
        }
        Set<String> nos = new LinkedHashSet<>();
        if (semi.getIntermediateBatchNo() != null) {
            nos.add(semi.getIntermediateBatchNo());
        }
        // 审查高 3: 这一行可能被多个生产批次入过账 (App 半成品行按产品码跨批次累加) ——
        // 行上的 batchId 只是第一次建行的批次。⇒ 从入库流水反查全部入账批次, 连同它们的计划一起数。
        Set<Long> pbIds = new LinkedHashSet<>();
        if (semi.getBatchId() != null) {
            pbIds.add(semi.getBatchId());
        }
        if (semi.getId() != null && semiRepo != null) {
            pbIds.addAll(semiRepo.findProductionBatchIdsPostedToSemi(factoryId, semi.getId()));
        }
        pbIds.remove(null);
        Set<String> planIds = new LinkedHashSet<>();
        for (Long pbId : pbIds) {
            ProductionBatch pb = productionBatchRepo.findByIdAndFactoryId(pbId, factoryId).orElse(null);
            if (pb != null) {
                if (pb.getBatchNumber() != null) {
                    nos.add(pb.getBatchNumber());
                }
                if (pb.getProductionPlanId() != null && !pb.getProductionPlanId().isBlank()) {
                    planIds.add(pb.getProductionPlanId());
                }
            }
        }
        return countCovering(factoryId, candidates(factoryId, nos, planIds, true),
                semi.getProcessOrder(), pbIds, nos);
    }

    @Override
    public long openDeviationsForWipBatch(String factoryId, MaterialBatch wipBatch) {
        if (wipBatch == null) {
            return 0L;
        }
        Set<String> nos = new LinkedHashSet<>();
        if (wipBatch.getBatchNumber() != null) {
            nos.add(wipBatch.getBatchNumber());
        }
        String planId = null;
        Long pbId = parseLong(wipBatch.getSourceDocId());
        if (pbId != null) {
            ProductionBatch pb = productionBatchRepo.findByIdAndFactoryId(pbId, factoryId).orElse(null);
            if (pb != null) {
                if (pb.getBatchNumber() != null) {
                    nos.add(pb.getBatchNumber());
                }
                planId = pb.getProductionPlanId();
            }
        }
        Integer order = scope == null ? null : scope.orderOfProductionBatch(factoryId, pbId);
        return countCovering(factoryId, candidates(factoryId, nos,
                        planId == null || planId.isBlank() ? Set.of() : Set.of(planId), false),
                order, pbId == null ? Set.of() : Set.of(pbId), nos);
    }

    /** 成品批次是整条链的终点 ⇒ 同计划 / 同批号的偏离都算它的上游, 不分道。 */
    @Override
    public long openDeviationsForFinishedGoods(String factoryId, FinishedGoodsBatch batch) {
        if (batch == null) {
            return 0L;
        }
        return countOpen(factoryId, clean(java.util.List.of(nullToEmpty(batch.getBatchNumber()))),
                batch.getProductionPlanId());
    }

    // ==================== 内部 ====================

    /**
     * 候选未处理偏离 (按记录 id 去重): 批号命中 ∪ 计划命中 ∪ (withAnchors) 锚号 CLK-SEMI-<计划前8位>-… 按计划 id 前缀。
     */
    private java.util.List<HaccpMonitoringRecord> candidates(String factoryId, Set<String> nos,
                                                             Collection<String> planIds, boolean withAnchors) {
        java.util.Map<Object, HaccpMonitoringRecord> out = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<java.util.List<HaccpMonitoringRecord>> add = rs -> rs.forEach(
                r -> out.putIfAbsent(r.getId() != null ? r.getId() : new Object(), r));
        if (!nos.isEmpty()) {
            add.accept(recordRepo.findOpenDeviationsByBatchNumbers(factoryId, nos));
        }
        if (planIds != null && !planIds.isEmpty()) {
            add.accept(recordRepo.findOpenDeviationsByPlanIds(factoryId, planIds));
        }
        if (withAnchors) {
            for (String no : nos) {
                String prefix = planPrefixOfAnchor(no);
                if (prefix != null) {
                    // 第三轮审查 L-1: 前缀里的 % _ 先转义 (查询带 ESCAPE)
                    add.accept(recordRepo.findOpenDeviationsByPlanIdPrefix(factoryId,
                            com.cretas.aims.util.SqlLikeEscaper.escape(prefix)));
                }
            }
        }
        return new java.util.ArrayList<>(out.values());
    }

    /** 候选里归这笔产出管的条数 (判据唯一: {@link CcpDeviationScope.Scope#covers})。 */
    private long countCovering(String factoryId, java.util.List<HaccpMonitoringRecord> records, Integer itemOrder,
                               Collection<Long> itemBatchIds, Collection<String> itemBatchNos) {
        return records.stream()
                .filter(r -> (scope == null ? CcpDeviationScope.Scope.WHOLE_PLAN : scope.of(factoryId, r))
                        .covers(itemOrder, itemBatchIds, itemBatchNos))
                .count();
    }

    public static String planPrefixOfAnchor(String batchNo) {
        if (batchNo == null || !batchNo.startsWith(CLERK_SEMI_ANCHOR_PREFIX)) {
            return null;
        }
        String rest = batchNo.substring(CLERK_SEMI_ANCHOR_PREFIX.length());
        int dash = rest.indexOf('-');
        String head = dash < 0 ? rest : rest.substring(0, dash);
        // 计划缺失时锚里是 8 个 0 (head8(null)), 不是真前缀
        return head.isBlank() || "00000000".equals(head) ? null : head;
    }

    private static Set<String> clean(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

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
 * <h2>口径: 批次号 或 同一生产计划</h2>
 * 一条偏离挡住:
 * <ul>
 *   <li>批次号命中的 —— 报工的生产批号 (App: 任务所在批次; web: 本行的 CLK-W/CLK-B 号或纯库存道锚号);</li>
 *   <li>同一生产计划的全部产出 —— 偏离发生在卤煮那一道, 下游包装出来的成品一样不能放 (整条链共用一个计划)。</li>
 * </ul>
 * 宁可多挡同计划里没出事的那几道, 也不让出事那一道的料从下游溜出去 —— 解除只需要「处理偏离」一步。
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

    /** 测试 / 装配用。 */
    public void setSemiRepo(com.cretas.aims.repository.SemiFinishedInventoryRepository semiRepo) {
        this.semiRepo = semiRepo;
    }

    /** 出货闸等只拿到记录行的地方用这一个判据: 偏离且未处理。 */
    public static boolean isOpenDeviation(HaccpMonitoringRecord r) {
        return r != null && r.isOpenDeviation();
    }

    /** 未处理偏离条数: 批次号集合 ∪ 生产计划。 */
    public long countOpen(String factoryId, Collection<String> batchNumbers, String planId) {
        if (factoryId == null) {
            return 0L;
        }
        long n = 0L;
        Set<String> nos = clean(batchNumbers);
        if (!nos.isEmpty()) {
            n += recordRepo.countOpenDeviationsByBatchNumbers(factoryId, nos);
        }
        if (planId != null && !planId.isBlank()) {
            // 同一条既命中批号又命中计划会被数两次 —— 这里只关心「是否 > 0」与给人看的大致条数,
            // 精确条数在 CCP 记录页按批次列出。
            n += recordRepo.countOpenDeviationsByPlanIds(factoryId, Set.of(planId));
        }
        return n;
    }

    /** 产出入账时: 这次产出所属的批次/计划有没有未处理偏离。 */
    public boolean hasOpen(String factoryId, Collection<String> batchNumbers, String planId, Long productionBatchId) {
        Set<String> nos = clean(batchNumbers);
        String plan = planId;
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
        if (countOpen(factoryId, nos, plan) > 0) {
            return true;
        }
        return countOpenByAnchors(factoryId, nos) > 0;
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
        long n = countOpen(factoryId, nos, null) + countOpenByAnchors(factoryId, nos);
        if (!planIds.isEmpty()) {
            n += recordRepo.countOpenDeviationsByPlanIds(factoryId, planIds);
        }
        return n;
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
        return countOpen(factoryId, nos, planId);
    }

    @Override
    public long openDeviationsForFinishedGoods(String factoryId, FinishedGoodsBatch batch) {
        if (batch == null) {
            return 0L;
        }
        return countOpen(factoryId, clean(java.util.List.of(nullToEmpty(batch.getBatchNumber()))),
                batch.getProductionPlanId());
    }

    // ==================== 内部 ====================

    /** 锚号 CLK-SEMI-<计划前8位>-… ⇒ 按计划 id 前缀数。 */
    private long countOpenByAnchors(String factoryId, Set<String> nos) {
        long n = 0L;
        for (String no : nos) {
            String prefix = planPrefixOfAnchor(no);
            if (prefix != null) {
                n += recordRepo.countOpenDeviationsByPlanIdPrefix(factoryId, prefix);
            }
        }
        return n;
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

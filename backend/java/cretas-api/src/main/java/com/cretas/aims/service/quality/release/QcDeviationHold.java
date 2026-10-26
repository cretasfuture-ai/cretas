package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺口 13 → 缺口 11 的连接: CCP 偏离一出现, 把<b>已经存在</b>的相关产出立刻扣成待检。
 *
 * <p>另一半 (偏离之后才入账的产出) 由 {@link QcInspectionPolicy} 在入账时按同一口径扣。
 * 口径与挡放行完全一致 (批次号 或 同一生产计划, 见 {@code CcpOpenDeviationLookup}):
 * <ul>
 *   <li>半成品库行: 这些生产批次建的行 / 这些批次的报工入过的行 (App 半成品行按产品码跨批次累加) /
 *       批号命中的行 / 该计划的逐道录入锚行;</li>
 *   <li>在制批次 (WIP-CLK-W…): 该计划全部生产批次 (含 CLERK_WIP) 物化出来的, 只动 AVAILABLE;</li>
 *   <li>成品批次: 批号命中或同一计划, 只动 AVAILABLE。</li>
 * </ul>
 * 已判不合格 / 已耗尽 / 已发货的不动 —— 只把「还能被领走、被发走」的拦下来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QcDeviationHold {

    private final SemiFinishedInventoryRepository semiRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final FinishedGoodsBatchRepository finishedGoodsRepo;
    private final ProductionBatchRepository productionBatchRepo;

    /** 扣了多少 (给录入人看的回执)。 */
    public record HoldResult(int semiRows, int wipBatches, int finishedGoodsBatches) {
        public int total() {
            return semiRows + wipBatches + finishedGoodsBatches;
        }
    }

    public HoldResult holdForDeviation(String factoryId, String batchNumber, Long productionBatchId, String planId) {
        Set<String> batchNumbers = new LinkedHashSet<>();
        if (batchNumber != null && !batchNumber.isBlank()) {
            batchNumbers.add(batchNumber);
        }
        Set<Long> pbIds = new LinkedHashSet<>();
        if (productionBatchId != null) {
            pbIds.add(productionBatchId);
        }
        if (planId != null && !planId.isBlank()) {
            for (ProductionBatch pb : productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                    factoryId, planId)) {
                pbIds.add(pb.getId());
                if (pb.getBatchNumber() != null) {
                    batchNumbers.add(pb.getBatchNumber());
                }
            }
        }

        // ── 半成品库行: 先只收集 id, 再加行锁读、锁内改 (审查中 8: 锁住之后不会有并发扣减插进来) ──
        Set<Long> semiIds = new LinkedHashSet<>();
        if (!pbIds.isEmpty()) {
            semiRepo.findByFactoryIdAndBatchIdInForHold(factoryId, pbIds).forEach(s -> semiIds.add(s.getId()));
            semiIds.addAll(semiRepo.findSemiIdsPostedByProductionBatches(factoryId, pbIds));
        }
        String anchorPrefix = planId == null || planId.isBlank()
                ? "#NO-ANCHOR#" : "CLK-SEMI-" + head8(planId) + "-";
        // 哨兵值不能用 NUL 字符: PostgreSQL 文本里不允许 NUL 字节
        semiRepo.findByFactoryIdAndBatchNoOrAnchorPrefixForHold(factoryId,
                        batchNumbers.isEmpty() ? Set.of("#NO-BATCH#") : batchNumbers, anchorPrefix)
                .forEach(s -> semiIds.add(s.getId()));
        semiIds.remove(null);
        int semiHeld = 0;
        for (SemiFinishedInventory s : semiIds.isEmpty() ? List.<SemiFinishedInventory>of()
                : semiRepo.lockForDeviationHold(factoryId, semiIds)) {
            if (holdWholeBalance(s)) {
                semiRepo.save(s);
                semiHeld++;
            }
        }

        // ── 在制批次: 该计划全部生产批次 (含 CLERK_WIP) 物化出来的, 只动 AVAILABLE ──
        int wipHeld = 0;
        if (!pbIds.isEmpty()) {
            for (MaterialBatch mb : materialBatchRepo.lockWipBatchesForDeviationHold(
                    factoryId, pbIds.stream().map(String::valueOf).toList())) {
                if (mb.getStatus() == MaterialBatchStatus.AVAILABLE) {
                    mb.setStatus(MaterialBatchStatus.INSPECTING);
                    materialBatchRepo.save(mb);
                    wipHeld++;
                }
            }
        }

        // ── 成品批次: 批号命中或同一计划, 只动 AVAILABLE ──
        int fgHeld = 0;
        for (FinishedGoodsBatch fg : finishedGoodsRepo.lockForDeviationHold(factoryId,
                batchNumbers.isEmpty() ? Set.of("#NO-BATCH#") : batchNumbers,
                planId == null || planId.isBlank() ? Set.of("#NO-PLAN#") : Set.of(planId))) {
            if (FinishedGoodsBatch.Status.AVAILABLE.equals(fg.getStatus())) {
                fg.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
                finishedGoodsRepo.save(fg);
                fgHeld++;
            }
        }
        HoldResult result = new HoldResult(semiHeld, wipHeld, fgHeld);
        log.warn("[CCP偏离扣留] factory={} batch={} pb={} plan={} → 半成品 {} / 在制 {} / 成品 {} 转待检",
                factoryId, batchNumber, productionBatchId, planId, semiHeld, wipHeld, fgHeld);
        return result;
    }

    /**
     * 偏离归不到具体数量 ⇒ 整个余额转待检。已耗尽 / 余额为 0 / 已全额待检的行不动。
     * @return 是否改了这一行
     */
    static boolean holdWholeBalance(SemiFinishedInventory s) {
        if (s == null || !SemiFinishedInventory.Status.AVAILABLE.equals(s.getStatus())) {
            return false;
        }
        java.math.BigDecimal balance = s.computeBalance();
        if (balance.signum() <= 0) {
            return false;
        }
        java.math.BigDecimal pending = s.pendingInspectionQuantity();
        if (pending != null && pending.compareTo(balance) >= 0
                && s.getInspectionPendingQuantity() != null) {
            return false;
        }
        s.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
        s.setInspectionPendingQuantity(balance);
        return true;
    }

    private static String head8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }
}

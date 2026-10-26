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

        // ── 半成品库行 ──
        Map<Long, SemiFinishedInventory> semis = new HashMap<>();
        if (!pbIds.isEmpty()) {
            semiRepo.findByFactoryIdAndBatchIdInForHold(factoryId, pbIds).forEach(s -> semis.put(s.getId(), s));
            List<Long> postedIds = semiRepo.findSemiIdsPostedByProductionBatches(factoryId, pbIds);
            if (!postedIds.isEmpty()) {
                semiRepo.findAllById(postedIds).stream()
                        .filter(s -> factoryId.equals(s.getFactoryId()) && s.getDeletedAt() == null)
                        .forEach(s -> semis.put(s.getId(), s));
            }
        }
        String anchorPrefix = planId == null || planId.isBlank()
                ? "#NO-ANCHOR#" : "CLK-SEMI-" + head8(planId) + "-";
        // 哨兵值不能用 \0: PostgreSQL 文本里不允许 NUL 字节
        semiRepo.findByFactoryIdAndBatchNoOrAnchorPrefixForHold(factoryId,
                        batchNumbers.isEmpty() ? Set.of("#NO-BATCH#") : batchNumbers, anchorPrefix)
                .forEach(s -> semis.put(s.getId(), s));
        int semiHeld = 0;
        for (SemiFinishedInventory s : semis.values()) {
            String before = s.getInspectionStatus();
            String after = QcInspectionPolicy.semiStatusAfterOutput(before, true);
            if (!SemiFinishedInventory.Status.AVAILABLE.equals(s.getStatus()) || after.equals(before)) {
                continue;
            }
            s.setInspectionStatus(after);
            semiRepo.save(s);
            semiHeld++;
        }

        // ── 在制批次 ──
        int wipHeld = 0;
        if (!pbIds.isEmpty()) {
            List<String> ids = pbIds.stream().map(String::valueOf).toList();
            for (MaterialBatch mb : materialBatchRepo.findWipBatchesByFactoryIdAndSourceDocIdIn(factoryId, ids)) {
                MaterialBatchStatus after = QcInspectionPolicy.wipBatchStatusAfterOutput(mb.getStatus(), true);
                if (after != mb.getStatus()) {
                    mb.setStatus(after);
                    materialBatchRepo.save(mb);
                    wipHeld++;
                }
            }
        }

        // ── 成品批次 ──
        Map<String, FinishedGoodsBatch> fgs = new HashMap<>();
        if (!batchNumbers.isEmpty()) {
            finishedGoodsRepo.findByFactoryIdAndBatchNumberInForHold(factoryId, batchNumbers)
                    .forEach(f -> fgs.put(f.getId(), f));
        }
        if (planId != null && !planId.isBlank()) {
            finishedGoodsRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                    .forEach(f -> fgs.put(f.getId(), f));
        }
        int fgHeld = 0;
        for (FinishedGoodsBatch f : fgs.values()) {
            String after = QcInspectionPolicy.finishedGoodsStatusAfterOutput(f.getStatus(), true);
            if (!after.equals(f.getStatus())) {
                f.setStatus(after);
                finishedGoodsRepo.save(f);
                fgHeld++;
            }
        }
        HoldResult result = new HoldResult(semiHeld, wipHeld, fgHeld);
        log.warn("[CCP偏离扣留] factory={} batch={} pb={} plan={} → 半成品 {} / 在制 {} / 成品 {} 转待检",
                factoryId, batchNumber, productionBatchId, planId, semiHeld, wipHeld, fgHeld);
        return result;
    }

    private static String head8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }
}

package com.cretas.aims.service.quality;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;

/**
 * 🔒🔒 QC 生产门: 把一个生产计划下还能卖的成品批次隔离成 DEFECTIVE。
 *
 * <p>质检判 FAILED 和处置判返工 / 报废走的是同一个动作, 所以只有这一份。只动 AVAILABLE 和待检
 * (缺口 11: 待检不隔离, 质检员之后一放行, 失败计划的成品就成了可售), 不动 DEPLETED / EXPIRED /
 * REVERSED / FROZEN 这些非可售状态。已发货部分不回退。
 */
public final class FinishedGoodsQuarantine {

    private FinishedGoodsQuarantine() {
    }

    /** @return 这次被隔离的批次数 */
    public static int quarantinePlan(FinishedGoodsBatchRepository repository, String factoryId, String planId) {
        if (planId == null || planId.isBlank()) {
            return 0;
        }
        int quarantined = 0;
        for (FinishedGoodsBatch fg : repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)) {
            if (FinishedGoodsBatch.Status.AVAILABLE.equals(fg.getStatus())
                    || FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(fg.getStatus())) {
                fg.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
                repository.save(fg);
                quarantined++;
            }
        }
        return quarantined;
    }
}

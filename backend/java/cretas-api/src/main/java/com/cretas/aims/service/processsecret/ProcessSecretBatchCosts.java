package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionBatch;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前调用者能看的批次成本与产量 (Steve 10-29: 无权的人看到的合计只算他能看的行, 不做「总数 − 保密」)。
 * 生产看板 / 生产统计共用。
 *
 * <p>口径与 {@code ProductionBatchRepository.calculateTotalCostAfter / calculateTotalOutputAfter} 一致:
 * {@code createdAt >= from}; 成本 = SUM(totalCost) (全空 / 无行为 null); 产量 = SUM(actualQuantity), 不含文员在制
 * (CLERK_WIP) 批次 (全空 / 无行为 null)。单位成本 = 可见成本 / 可见产量 (分母分子同一批行)。</p>
 */
public record ProcessSecretBatchCosts(BigDecimal totalCost, BigDecimal totalOutput, int excludedBatches,
                                      int excludedProducts) {

    public boolean partial() {
        return excludedBatches > 0;
    }

    public String note() {
        return partial() ? ProcessSecretLlmCostScope.note(excludedProducts) : null;
    }

    /** 无产量时单位成本算不出来 (null), 不是 0。 */
    public BigDecimal unitCost() {
        if (totalCost == null || totalOutput == null || totalOutput.signum() <= 0) return null;
        return totalCost.divide(totalOutput, 2, java.math.RoundingMode.HALF_UP);
    }

    public static ProcessSecretBatchCosts of(ProcessSecretLlmCostScope.Excluded excluded, List<ProductionBatch> batches,
                                             LocalDateTime from) {
        BigDecimal cost = null, output = null;
        int excludedBatches = 0;
        Set<String> excludedProducts = new LinkedHashSet<>();
        for (ProductionBatch batch : batches) {
            if (batch.getCreatedAt() == null || batch.getCreatedAt().isBefore(from)) continue;
            if (excluded.coversBatch(batch.getProductTypeId(), batch.getProductionPlanId())) {
                excludedBatches++;
                excludedProducts.addAll(excluded.protectedBehind(batch.getProductTypeId(),
                        batch.getProductionPlanId() == null ? List.of() : List.of(batch.getProductionPlanId())));
                continue;
            }
            if (batch.getTotalCost() != null) cost = cost == null ? batch.getTotalCost() : cost.add(batch.getTotalCost());
            if (batch.getActualQuantity() != null && !"CLERK_WIP".equals(batch.getBatchType())) {
                output = output == null ? batch.getActualQuantity() : output.add(batch.getActualQuantity());
            }
        }
        return new ProcessSecretBatchCosts(cost, output, excludedBatches, excludedProducts.size());
    }
}

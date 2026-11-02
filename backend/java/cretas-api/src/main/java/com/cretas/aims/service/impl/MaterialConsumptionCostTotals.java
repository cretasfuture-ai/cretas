package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialConsumptionRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical material-cost read for a production batch.
 *
 * <p>Material-consumption rows already persist the signed accounting amount.
 * Repricing from the current material-batch price would rewrite historical
 * cost, and a missing persisted amount must fail closed rather than becoming
 * a fabricated zero.</p>
 */
final class MaterialConsumptionCostTotals {

    private MaterialConsumptionCostTotals() {
    }

    static BigDecimal forBatch(MaterialConsumptionRepository repository, Long productionBatchId,
                               String factoryId) {
        return sum(repository.findByProductionBatchIdAndFactoryId(productionBatchId, factoryId));
    }

    static BigDecimal sum(List<MaterialConsumption> consumptions) {
        if (consumptions == null) {
            throw new BusinessException(500,
                    "无法读取生产批次的物料消耗记录，无法安全重算物料成本");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (MaterialConsumption consumption : consumptions) {
            if (consumption == null || consumption.getTotalCost() == null) {
                throw new BusinessException(500,
                        "生产批次存在缺少已持久化成本的物料消耗记录，无法安全重算物料成本");
            }
            total = total.add(consumption.getTotalCost());
        }
        return total;
    }
}

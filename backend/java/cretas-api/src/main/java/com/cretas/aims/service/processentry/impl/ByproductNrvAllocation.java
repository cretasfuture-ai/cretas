package com.cretas.aims.service.processentry.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 一进多出的成本比例: 副产先按可变现净值取走固定份额, 剩余按基础比例(重量)分给其余产出。
 * owner 2026-10-24 裁定「按产出重量比例分摊, 副产按可变现净值先扣除」。
 *
 * <p>纯算术, 不查库 —— 价从哪来、找不到价怎么告警由调用方决定。
 */
public final class ByproductNrvAllocation {

    private ByproductNrvAllocation() {
    }

    /**
     * @param baseRatios  基础比例(和为 1), 与产出一一对应
     * @param nrvValues   每个产出的可变现净值(数量 × 单价); 非副产或副产缺价 → null(保留基础比例)
     * @param jointCost   本道联合成本
     * @return 新比例(和为 1); 没有任何有价副产、联合成本 ≤ 0、或副产净值合计超过联合成本 → null(调用方保留基础比例)
     */
    public static List<BigDecimal> ratios(List<BigDecimal> baseRatios, List<BigDecimal> nrvValues, BigDecimal jointCost) {
        if (jointCost == null || jointCost.signum() <= 0 || baseRatios.size() != nrvValues.size()) {
            return null;
        }
        BigDecimal fixedTotal = BigDecimal.ZERO;
        BigDecimal otherBase = BigDecimal.ZERO;
        for (int i = 0; i < baseRatios.size(); i++) {
            if (nrvValues.get(i) != null) {
                fixedTotal = fixedTotal.add(nrvValues.get(i));
            } else {
                otherBase = otherBase.add(baseRatios.get(i));
            }
        }
        if (fixedTotal.signum() == 0 || fixedTotal.compareTo(jointCost) > 0) {
            return null;
        }
        BigDecimal remainingShare = BigDecimal.ONE.subtract(fixedTotal.divide(jointCost, 12, RoundingMode.HALF_UP));
        List<BigDecimal> ratios = new ArrayList<>(baseRatios.size());
        for (int i = 0; i < baseRatios.size(); i++) {
            if (nrvValues.get(i) != null) {
                ratios.add(nrvValues.get(i).divide(jointCost, 12, RoundingMode.HALF_UP));
            } else {
                ratios.add(otherBase.signum() == 0 ? BigDecimal.ZERO
                        : remainingShare.multiply(baseRatios.get(i)).divide(otherBase, 12, RoundingMode.HALF_UP));
            }
        }
        return ratios;
    }
}

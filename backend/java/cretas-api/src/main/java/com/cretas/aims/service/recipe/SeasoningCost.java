package com.cretas.aims.service.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 调料成本算法结果(绝对¥ + per-kg 速率, 供展示/引擎). */
@Data
@AllArgsConstructor
public class SeasoningCost {
    /** 5 参兼容构造: 没有无价调料。 */
    public SeasoningCost(BigDecimal injectionCostPerKg, BigDecimal cookingFullCostPerKg,
                         BigDecimal injectionTotal, BigDecimal cookingTotal, BigDecimal total) {
        this(injectionCostPerKg, cookingFullCostPerKg, injectionTotal, cookingTotal, total, List.of());
    }

    private BigDecimal injectionCostPerKg;   // 注射/kg
    private BigDecimal cookingFullCostPerKg; // 熟制全量/kg
    private BigDecimal injectionTotal;       // 注射总¥
    private BigDecimal cookingTotal;         // 熟制总¥(含锅序)
    private BigDecimal total;                // 合计¥
    /**
     * 参与计算但两个价都没有的调料名 (它们的成本按 0 进了 total)。
     * 调用方必须把这份清单变成一句 warning —— 「不知道」不能安静地变成「0」。
     */
    private List<String> unpricedNames;
}

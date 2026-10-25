package com.cretas.aims.service.processentry;

import com.cretas.aims.service.processentry.impl.ByproductNrvAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一进多出: 副产按可变现净值先扣, 其余按重量 (owner 2026-10-24)。
 * 场景取自 F006【演示L4】整鸡分割: 鸡腿 70 / 鸡胸 60 / 鸡翅 35 / 鸡油(副产) 12 kg, 联合成本 ¥3371.55。
 */
class ByproductNrvAllocationTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static final List<BigDecimal> MASS = List.of(
            d("70").divide(d("177"), 12, java.math.RoundingMode.HALF_UP),
            d("60").divide(d("177"), 12, java.math.RoundingMode.HALF_UP),
            d("35").divide(d("177"), 12, java.math.RoundingMode.HALF_UP),
            d("12").divide(d("177"), 12, java.math.RoundingMode.HALF_UP));

    @Test
    @DisplayName("副产有价: 鸡油 12kg@¥8 = ¥96 先扣, 其余 ¥3275.55 按 70:60:35 分")
    void pricedByproductTakesItsNrvFirstAndTheRestSplitsByMass() {
        BigDecimal joint = d("3371.55");
        List<BigDecimal> ratios = ByproductNrvAllocation.ratios(MASS, Arrays.asList(null, null, null, d("96")), joint);

        assertThat(ratios).isNotNull();
        // 金额按分落地 (allocateMoney 取 2 位), 比例 12 位小数的尾差在分以下
        assertThat(joint.multiply(ratios.get(3)).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(d("96.00"));
        BigDecimal rest = joint.subtract(d("96"));
        assertThat(joint.multiply(ratios.get(0)).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(rest.multiply(d("70")).divide(d("165"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isCloseTo(BigDecimal.ONE, org.assertj.core.data.Offset.offset(d("0.000000001")));
    }

    @Test
    @DisplayName("没有任何有价副产 → null (调用方保留重量比例)")
    void noPricedByproductKeepsBaseRatios() {
        assertThat(ByproductNrvAllocation.ratios(MASS, Arrays.asList(null, null, null, null), d("3371.55"))).isNull();
    }

    @Test
    @DisplayName("副产净值超过联合成本 (价或量录错) → null, 不让主产出成本变负")
    void byproductNrvAboveJointCostIsRejected() {
        assertThat(ByproductNrvAllocation.ratios(MASS, Arrays.asList(null, null, null, d("5000")), d("3371.55"))).isNull();
    }
}

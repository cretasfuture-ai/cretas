package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-W-01: {@link ProductionBatch#calculateMetrics()} 的效率只在同单位下算, 且绝不产出 numeric(5,2) 存不下的数。
 *
 * <p>真库上的溢出与仓库实收真入口见 {@code ReceiptEfficiencyUnitPostgresContractTest} (要 PG 才跑);
 * 本类不依赖数据库, 每条 CI 车道都跑, 守的是实体自己的两条性质。
 */
class ProductionBatchEfficiencyContractTest {

    private static ProductionBatch batch(String planned, String actual, String unit) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(4661L);
        batch.setBatchNumber("PB-SEC-F006-20261109-4661D1");
        batch.setPlannedQuantity(planned == null ? null : new BigDecimal(planned));
        batch.setActualQuantity(actual == null ? null : new BigDecimal(actual));
        batch.setUnit(unit);
        return batch;
    }

    @Test
    void sameUnitEfficiencyIsActualOverPlannedTimesHundred() {
        ProductionBatch batch = batch("100", "95", "kg");
        batch.calculateMetrics();
        assertThat(batch.getEfficiency()).isEqualByComparingTo("95.00");
    }

    @Test
    void aRatioTheColumnCannotHoldIsUnknownNotCappedAndNotZero() {
        ProductionBatch batch = batch("0.3", "3", "kg");   // 1000.00 ≥ numeric(5,2) 上限 999.99
        batch.setEfficiency(new BigDecimal("88.00"));      // 旧值也不许留着冒充这次的结果
        batch.calculateMetrics();
        assertThat(batch.getEfficiency()).isNull();

        ProductionBatch justFits = batch("1", "9.9999", "kg");   // 999.99 恰好存得下
        justFits.calculateMetrics();
        assertThat(justFits.getEfficiency()).isEqualByComparingTo("999.99");
    }

    @Test
    void aCallerThatCheckedUnitsDecidesTheComparableActualEvenAcrossSaves() {
        ProductionBatch converted = batch("0.3", "3", "袋");
        converted.useActualInPlannedUnitForEfficiency(new BigDecimal("0.3"));   // 3 袋 × 100 g
        converted.calculateMetrics();
        assertThat(converted.getEfficiency()).isEqualByComparingTo("100.00");
        converted.onUpdate();   // save 时 @PreUpdate 再算一次, 不能退回 3 ÷ 0.3
        assertThat(converted.getEfficiency()).isEqualByComparingTo("100.00");

        ProductionBatch notComparable = batch("2", "3", "袋");   // 裸除 150.00 存得下, 但它是假数
        notComparable.useActualInPlannedUnitForEfficiency(null);
        notComparable.calculateMetrics();
        assertThat(notComparable.getEfficiency()).isNull();
    }

    @Test
    void p0TwoCrossUnitMarkerStillLeavesEfficiencyUnknown() {
        ProductionBatch batch = batch("2", "3", "份");
        batch.setPlannedUnit("kg");
        batch.calculateMetrics();
        assertThat(batch.getEfficiency()).isNull();
    }
}

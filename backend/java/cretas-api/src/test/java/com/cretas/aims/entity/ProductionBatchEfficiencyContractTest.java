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
    void theSameUnitWrittenDifferentlyIsNotCrossUnit() {
        ProductionBatch batch = batch("100", "95", "kg");
        batch.setPlannedUnit("千克");
        batch.calculateMetrics();
        assertThat(batch.getEfficiency()).as("千克 ≡ kg").isEqualByComparingTo("95.00");
    }

    @Test
    void theEntityNeverCallsTwoUnitsTheSameWhereTheUnitContractWouldNot() {
        // 实体不比服务侧宽: 自由文本只认字面 (服务侧 storageUnit 不折大小写), 只 ≠ 件 (Steve: 三个单位)
        ProductionBatch caseFolded = batch("100", "95", "lianpack");   // 不在内置单位表里的自由文本
        caseFolded.setPlannedUnit("LianPack");
        caseFolded.calculateMetrics();
        assertThat(caseFolded.getEfficiency()).as("LianPack / lianpack: 服务侧 storageUnit 原样保留, 不折大小写").isNull();

        ProductionBatch countLabels = batch("100", "95", "件");
        countLabels.setPlannedUnit("只");
        countLabels.calculateMetrics();
        assertThat(countLabels.getEfficiency()).as("只 ≠ 件").isNull();

        ProductionBatch pieces = batch("100", "95", "pcs");
        pieces.setPlannedUnit("件");
        pieces.calculateMetrics();
        assertThat(pieces.getEfficiency()).as("件 就是 pcs 的中文名").isEqualByComparingTo("95.00");

        ProductionBatch literal = batch("100", "95", "袋");
        literal.setPlannedUnit(" 袋 ");
        literal.calculateMetrics();
        assertThat(literal.getEfficiency()).as("字面相同 (去空白) 一定是同一个单位").isEqualByComparingTo("95.00");
    }

    @Test
    void aPreReleaseRowWithoutPlannedUnitIsOnlyComparedWhenThePlanUnitIsTheBatchUnit() {
        // 存量行: plannedUnit 为 null、挂着计划; 实体靠只读的 sourcePlanUnit (所挂计划的单位) 判
        ProductionBatch bagsUnderKgPlan = batch("2", "3", "袋");
        bagsUnderKgPlan.setProductionPlanId("PLAN-KG");
        bagsUnderKgPlan.setSourcePlanUnit("kg");
        bagsUnderKgPlan.calculateMetrics();
        assertThat(bagsUnderKgPlan.getEfficiency()).as("不知道 2 是 kg 还是袋 ⇒ 不算, 不是 150.00").isNull();

        ProductionBatch kgUnderKgPlan = batch("100", "95", "kg");
        kgUnderKgPlan.setProductionPlanId("PLAN-KG");
        kgUnderKgPlan.setSourcePlanUnit("千克");
        kgUnderKgPlan.calculateMetrics();
        assertThat(kgUnderKgPlan.getEfficiency()).as("两种来源此时一样").isEqualByComparingTo("95.00");

        ProductionBatch planUnitUnknown = batch("100", "95", "kg");
        planUnitUnknown.setProductionPlanId("PLAN-GONE");
        planUnitUnknown.calculateMetrics();
        assertThat(planUnitUnknown.getEfficiency()).as("计划单位读不到 ⇒ 不知道").isNull();

        ProductionBatch noPlan = batch("100", "95", "箱");
        noPlan.calculateMetrics();
        assertThat(noPlan.getEfficiency()).as("没挂计划: 计划量只能是按批次单位填的").isEqualByComparingTo("95.00");
    }

    @Test
    void p0TwoCrossUnitMarkerStillLeavesEfficiencyUnknown() {
        ProductionBatch batch = batch("2", "3", "份");
        batch.setPlannedUnit("kg");
        batch.calculateMetrics();
        assertThat(batch.getEfficiency()).isNull();
    }
}

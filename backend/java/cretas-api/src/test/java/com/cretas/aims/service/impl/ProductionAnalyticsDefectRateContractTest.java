package com.cretas.aims.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionAnalyticsDefectRateContractTest {

    /** 仓库一行 (来源, 工序, 单位); defect / countedOutput 为 null 表示这一组没有任何一笔清点过不良。 */
    private static Map<String, Object> row(String source, String process, String unit, String output,
                                           String defect, String countedOutput, long counted, long reports) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", source);
        m.put("process_category", process);
        m.put("unit", unit);
        m.put("output", new BigDecimal(output));
        m.put("good", null);
        m.put("defect", defect == null ? null : new BigDecimal(defect));
        m.put("counted_output", countedOutput == null ? null : new BigDecimal(countedOutput));
        m.put("defect_counted_count", counted);
        m.put("report_count", reports);
        return m;
    }

    @Test
    void zeroBaseGivesNullNotZero() {
        assertThat(ProductionAnalyticsServiceImpl.defectRate(new BigDecimal("3"), BigDecimal.ZERO)).isNull();
        assertThat(ProductionAnalyticsServiceImpl.defectRate(null, new BigDecimal("10"))).isNull();
        assertThat(ProductionAnalyticsServiceImpl.defectRate(new BigDecimal("1.25"), new BigDecimal("100")))
                .isEqualByComparingTo("0.0125");
    }

    @Test
    void notCountedIsSeparateFromCountedZeroDefects() {
        List<Map<String, Object>> out = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("YIELD", "切丁", "kg", "100", null, null, 0, 3),        // 3 笔都没清点不良
                row("YIELD", "腌制", "kg", "80", "0", "80", 2, 2)));        // 清点了, 零不良
        assertThat(out.get(0)).containsEntry("defectStatus", ProductionAnalyticsServiceImpl.DEFECT_NOT_COUNTED);
        assertThat(out.get(0).get("defectRate")).isNull();
        assertThat(out.get(0).get("defect")).isNull();
        assertThat(out.get(1)).containsEntry("defectStatus", ProductionAnalyticsServiceImpl.DEFECT_COUNTED);
        assertThat((BigDecimal) out.get(1).get("defectRate")).isEqualByComparingTo("0");
    }

    @Test
    void yieldDenominatorIsGoodPlusDefectProgressKeepsOutput() {
        // 称重: 良品 95 + 不良 5 ⇒ 5 / 100 = 5% (旧口径 5/95 会高估)
        List<Map<String, Object>> yield = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("YIELD", "切丁", "kg", "95", "5", "95", 1, 1)));
        assertThat((BigDecimal) yield.get(0).get("defectRate")).isEqualByComparingTo("0.05");
        // 进度报工: 不良 / 产出
        List<Map<String, Object>> progress = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("PROGRESS", "切丁", "kg", "100", "5", "100", 1, 1)));
        assertThat((BigDecimal) progress.get(0).get("defectRate")).isEqualByComparingTo("0.05");
        // 只按清点过的笔算: 产出 200 里只有 50 清点过 (不良 5) ⇒ 5 / (50+5)
        List<Map<String, Object>> partial = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("YIELD", "切丁", "kg", "200", "5", "50", 1, 4)));
        assertThat((BigDecimal) partial.get(0).get("defectRate")).isEqualByComparingTo("0.0909");
        assertThat(partial.get(0)).containsEntry("defect_counted_count", 1L).containsEntry("report_count", 4L);
    }

    @Test
    void sameProcessSameUnitFromTwoSourcesIsOneRowDifferentUnitsStaySeparate() {
        List<Map<String, Object>> out = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("PROGRESS", "装盒", "kg", "100", "5", "100", 1, 1),
                row("YIELD", "装盒", "kg", "45", "5", "45", 1, 1),
                row("YIELD", "装盒", "盒", "30", null, null, 0, 1)));
        assertThat(out).hasSize(2);
        Map<String, Object> kg = out.get(0);
        assertThat(kg).containsEntry("unit", "kg");
        assertThat((BigDecimal) kg.get("output")).isEqualByComparingTo("145");
        assertThat((BigDecimal) kg.get("defect")).isEqualByComparingTo("10");
        // (5 + 5) / (100 + 45 + 5)
        assertThat((BigDecimal) kg.get("defectRate")).isEqualByComparingTo("0.0667");
        assertThat(kg.get("sources")).isEqualTo(List.of("PROGRESS", "YIELD"));
        assertThat(out.get(1)).containsEntry("unit", "盒")
                .containsEntry("defectStatus", ProductionAnalyticsServiceImpl.DEFECT_NOT_COUNTED);
    }

    @Test
    void countedButZeroOutputIsNoOutputNotZeroRate() {
        List<Map<String, Object>> out = ProductionAnalyticsServiceImpl.mergeProcessRows(List.of(
                row("PROGRESS", "装盒", "盒", "0", "0", "0", 1, 1)));
        assertThat(out.get(0).get("defectRate")).isNull();
        assertThat(out.get(0)).containsEntry("defectStatus", ProductionAnalyticsServiceImpl.DEFECT_NO_OUTPUT);
    }
}

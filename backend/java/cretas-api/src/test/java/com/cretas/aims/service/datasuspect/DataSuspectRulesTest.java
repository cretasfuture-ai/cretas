package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 迁移 P5 R30 / R34 数据疑点规则: 每条规则一正一反。 */
class DataSuspectRulesTest {

    /** 真实单位契约 (内置单位 + 别名表), 只把仓储 mock 掉 —— 测的是契约怎么归一, 不是我编的别名表。 */
    private static final UnitContractService UNITS = new UnitContractServiceImpl(
            org.mockito.Mockito.mock(com.cretas.aims.repository.config.UnitOfMeasurementRepository.class),
            org.mockito.Mockito.mock(com.cretas.aims.repository.unit.ProductUnitConversionRepository.class),
            org.mockito.Mockito.mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class),
            org.mockito.Mockito.mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class));

    private static java.util.List<String> reasons(ProductionReport p) {
        return DataSuspectRules.reasonsOf(p, UNITS);
    }

    private static ProductionReport r(String in, String inUnit, String out, String outUnit) {
        ProductionReport p = new ProductionReport();
        p.setFactoryId("F-SUSP");
        p.setInputQuantity(in == null ? null : new BigDecimal(in));
        p.setInputUnit(inUnit);
        p.setOutputQuantity(out == null ? null : new BigDecimal(out));
        p.setOutputUnit(outUnit);
        return p;
    }

    @Test
    @DisplayName("出成率 ≥100% 标疑点 (正好 100% 也标); 99.99% 不标")
    void yieldAtOrAbove100() {
        assertThat(reasons(r("100", "kg", "100", "kg"))).containsExactly("出成率 100.00%，达到或超过 100%");
        assertThat(reasons(r("100", "kg", "120", "kg"))).containsExactly("出成率 120.00%，达到或超过 100%");
        assertThat(reasons(r("100", "kg", "99.99", "kg"))).isEmpty();
    }

    @Test
    @DisplayName("出成率跨质量单位先换 kg 再比 (1 kg 投入 → 900 g 良品不是疑点); 单位不可比 (kg → 盒) 不判")
    void yieldAcrossUnits() {
        assertThat(reasons(r("1", "kg", "900", "g"))).isEmpty();
        assertThat(reasons(r("1", "kg", "1200", "g"))).hasSize(1);
        assertThat(reasons(r("10", "kg", "300", "盒"))).isEmpty();
        assertThat(reasons(r("10", null, "30", null))).containsExactly("出成率 300.00%，达到或超过 100%");
    }

    @Test
    @DisplayName("起止时间相同: 工时段开始 = 结束 标疑点; 不同不标; 报工单起止相同也标")
    void sameStartEnd() {
        ProductionReport same = r("100", "kg", "80", "kg");
        same.setLaborSegments(List.of(Map.of("startTime", "09:00", "endTime", "09:00")));
        assertThat(reasons(same)).containsExactly("开始和结束时间相同");

        ProductionReport diff = r("100", "kg", "80", "kg");
        diff.setLaborSegments(List.of(Map.of("startTime", "09:00", "endTime", "09:30")));
        assertThat(reasons(diff)).isEmpty();

        ProductionReport legacy = r("100", "kg", "80", "kg");
        legacy.setProductionStartTime(LocalTime.of(8, 0));
        legacy.setProductionEndTime(LocalTime.of(8, 0));
        assertThat(reasons(legacy)).containsExactly("开始和结束时间相同");
    }

    @Test
    @DisplayName("单位明显不符: ≥5000 kg 像克当千克; <0.1 kg 像吨当千克; 4999 kg / 0.1 kg 不标; 不是质量单位不判")
    void unitObviouslyWrong() {
        assertThat(reasons(r("5000", "kg", "4000", "kg"))).containsExactly("重量 ≥ 5000 kg，像是把克当成了千克");
        assertThat(reasons(r("4999", "kg", "4000", "kg"))).isEmpty();
        assertThat(reasons(r("0.05", "kg", "0.04", "kg"))).containsExactly("投入不到 0.1 kg，像是把吨当成了千克");
        assertThat(reasons(r("0.1", "kg", "0.08", "kg"))).isEmpty();
        assertThat(reasons(r("8000", "盒", "7000", "盒"))).isEmpty();
    }

    @Test
    @DisplayName("只有产出 (OUTPUT 阶段报工没有投入) 不判出成率")
    void outputOnlyIsNotJudged() {
        assertThat(reasons(r(null, null, "80", "kg"))).isEmpty();
    }
}

package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 迁移 P5 R30 / R34 数据疑点规则: 每条规则一正一反; 判据范围、按厂阈值、单位查询次数 (审查 #1708 第 2、3 条)。 */
class DataSuspectRulesContractTest {

    private static UnitOfMeasurementRepository unitRepo() {
        return mock(UnitOfMeasurementRepository.class);
    }

    /** 真实单位契约 (内置单位 + 别名表), 只把仓储 mock 掉 —— 测的是契约怎么归一, 不是我编的别名表。 */
    private static UnitContractServiceImpl realUnits(UnitOfMeasurementRepository repo) {
        return new UnitContractServiceImpl(
                repo,
                mock(com.cretas.aims.repository.unit.ProductUnitConversionRepository.class),
                mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class),
                mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class));
    }

    private static final UnitContractService UNITS = realUnits(unitRepo());

    private static List<String> reasons(ProductionReport p) {
        return DataSuspectRules.reasonsOf(p, UNITS);
    }

    private static List<String> reasons(ProductionReport p, DataSuspectRules.Thresholds t) {
        return DataSuspectRules.reasonsOf(p, DataSuspectRules.unitLookup(UNITS, p.getFactoryId()), t);
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

    private static ProductionReport kind(ProductionReport p, String kind) {
        p.setReportKind(kind);
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
        assertThat(reasons(r("10", "盒", "30", "盒"))).containsExactly("出成率 300.00%，达到或超过 100%");
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
    @DisplayName("单位明显不符 (默认阈值): ≥5000 kg 像克当千克; <0.1 kg 像吨当千克; 4999 kg / 0.1 kg 不标; 不是质量单位不判")
    void unitObviouslyWrong() {
        assertThat(reasons(r("5000", "kg", "4000", "kg"))).containsExactly("重量 ≥ 5000 kg，像是把克当成了千克");
        assertThat(reasons(r("4999", "kg", "4000", "kg"))).isEmpty();
        assertThat(reasons(r("0.05", "kg", "0.04", "kg"))).containsExactly("投入不到 0.1 kg，像是把吨当成了千克");
        assertThat(reasons(r("0.1", "kg", "0.08", "kg"))).isEmpty();
        assertThat(reasons(r("8000", "盒", "7000", "盒"))).isEmpty();
    }

    @Test
    @DisplayName("只有产出 (没有投入) 不判出成率")
    void outputOnlyIsNotJudged() {
        assertThat(reasons(r(null, null, "80", "kg"))).isEmpty();
    }

    @Test
    @DisplayName("阴性 范围: 三段报工 INPUT 行投入 0.05 kg 盐不标「吨当千克」; OUTPUT 行 6000 kg / 出成率不判; 起止相同照样标")
    void threeStageRowsAreNotJudgedForYieldOrMagnitude() {
        assertThat(reasons(kind(r("0.05", "kg", null, null), "INPUT"))).isEmpty();
        assertThat(reasons(kind(r("100", "kg", "120", "kg"), "OUTPUT"))).isEmpty();
        assertThat(reasons(kind(r(null, null, "6000", "kg"), "OUTPUT"))).isEmpty();
        assertThat(reasons(kind(r("6000", "kg", null, null), "SEGMENT"))).isEmpty();

        ProductionReport inputSame = kind(r("0.05", "kg", null, null), "INPUT");
        inputSame.setLaborSegments(List.of(Map.of("startTime", "09:00", "endTime", "09:00")));
        assertThat(reasons(inputSame)).containsExactly("开始和结束时间相同");
        // 对照: 同样的数放在整合 / 称重报工 (reportKind 为空) 上照判
        assertThat(reasons(r("0.05", "kg", "0.04", "kg"))).hasSize(1);
    }

    @Test
    @DisplayName("按厂阈值: 上限调到 20000 kg、下限调到 0.01 kg ⇒ 6000 kg、0.05 kg 不标; 6000 kg 在默认阈值下标")
    void perFactoryThresholds() {
        DataSuspectRules.Thresholds big = DataSuspectRules.Thresholds.of(new BigDecimal("20000"), new BigDecimal("0.01"));
        assertThat(reasons(r("6000", "kg", "5800", "kg"), big)).isEmpty();
        assertThat(reasons(r("0.05", "kg", "0.04", "kg"), big)).isEmpty();
        assertThat(reasons(r("20000", "kg", "19000", "kg"), big)).containsExactly("重量 ≥ 20000 kg，像是把克当成了千克");
        assertThat(reasons(r("0.005", "kg", "0.004", "kg"), big)).containsExactly("投入不到 0.01 kg，像是把吨当成了千克");
        assertThat(reasons(r("6000", "kg", "5800", "kg"))).containsExactly("重量 ≥ 5000 kg，像是把克当成了千克");
        // 配成 0 / 负数 / 没配 ⇒ 按默认
        assertThat(DataSuspectRules.Thresholds.of(BigDecimal.ZERO, new BigDecimal("-1")))
                .isEqualTo(DataSuspectRules.Thresholds.DEFAULT);
        assertThat(DataSuspectRules.Thresholds.of(null, null)).isEqualTo(DataSuspectRules.Thresholds.DEFAULT);
    }

    @Test
    @DisplayName("真实入口 listSuspects: 本厂阈值从阈值中心读; 200 条报工只按不同单位写法查单位目录, 不是每行查")
    void serviceReadsFactoryThresholdsAndLoadsUnitCatalogOncePerDistinctUnit() {
        ProductionReportRepository repo = mock(ProductionReportRepository.class);
        UnitOfMeasurementRepository unitRepo = unitRepo();
        UnitContractService units = spy(realUnits(unitRepo));
        ThresholdResolverService thresholds = mock(ThresholdResolverService.class);
        when(thresholds.getBigDecimal(eq("F-SUSP"), eq(ThresholdKeys.PRODUCTION_DATA_SUSPECT_MAX_WEIGHT_KG), any()))
                .thenReturn(new BigDecimal("20000"));
        when(thresholds.getBigDecimal(eq("F-SUSP"), eq(ThresholdKeys.PRODUCTION_DATA_SUSPECT_MIN_INPUT_KG), any()))
                .thenReturn(new BigDecimal("0.1"));
        List<ProductionReport> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(i % 2 == 0 ? r("6000", "kg", "5800", "kg") : r("1", "kg", "1200", "g"));
        }
        when(repo.findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                anyString(), anyString(), any(), any(), any())).thenReturn(new PageImpl<>(rows));
        DataSuspectService svc = new DataSuspectService(repo, units);
        ReflectionTestUtils.setField(svc, "thresholdResolver", thresholds);

        DataSuspectService.SuspectList list = svc.listSuspects("F-SUSP", 14, LocalDate.of(2026, 11, 14));

        assertThat(list.scanned()).as("必然读数: 扫了 200 条").isEqualTo(200);
        // 阈值 20000 ⇒ 6000 kg 那 100 条不是疑点; 出成率 120% 那 100 条是
        assertThat(list.items()).hasSize(100)
                .allSatisfy(e -> assertThat(e.suspects()).containsExactly("出成率 120.00%，达到或超过 100%"));
        // 单位写法只有 kg / g 两种 ⇒ 单位契约最多问 2 次, 单位目录 (findAllByFactoryId) 最多读 2 次
        verify(units, atMost(2)).describe(anyString(), anyString());
        verify(units, atMost(0)).areEquivalent(anyString(), anyString(), anyString());
        verify(unitRepo, atMost(2)).findAllByFactoryId("F-SUSP");
        verify(unitRepo, atLeast(1)).findAllByFactoryId("F-SUSP");   // 阳性对照: 确实走了单位契约, 上面的上限不是恒真
    }
}

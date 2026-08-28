package com.cretas.aims.service.report.impl;

import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ShipmentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@code getKPIMetrics}：没有数据时每个指标都是 <b>null</b>，⛔ 不是 0。
 *
 * <h3>改之前那段注释自己就是证据</h3>
 *
 * <pre>无数据 (如纯零售租户 / 无质检 / 无出货) → 返 0, 诚实空, 不造假。</pre>
 *
 * **返 0 恰恰是造假**：
 * <ul>
 *   <li>「良品率 0%」读起来是<b>全不合格</b></li>
 *   <li>「单位成本 ¥0」读起来是<b>不要钱</b></li>
 *   <li>「完成率 0% / 准时交付率 0%」读起来是<b>业绩很差</b></li>
 * </ul>
 *
 * <p>最明显的自证：无质检时它<b>同时</b>报「良品率 0%」和「缺陷率 0%」——
 * 两个互斥的结论同时成立，说明它们都不是读数。
 *
 * <h3>判据</h3>
 *
 * 每条「没数据就是 null」都配一条「有数据就照常算」的阴性对照 ——
 * 否则把这些字段整个删掉也能全绿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KPI：没有数据就说没有，⛔ 不报 0")
class KpiMetricsNoDataTest {

    private static final String FACTORY = "F006";
    private static final LocalDate DAY = LocalDate.of(2026, 8, 29);

    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private QualityInspectionRepository qualityInspectionRepository;
    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;

    @InjectMocks
    private ProductionReportServiceImpl service;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> kpi, String key) {
        return (Map<String, Object>) kpi.get(key);
    }

    private void givenNothing() {
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(productionPlanRepository.calculatePlannedOutputBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.<QualityInspection>of());
        when(shipmentRecordRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.<ShipmentRecord>of());
    }

    @Test
    @DisplayName("零数据 ⇒ 完成率 / 良品率 / 缺陷率 / 准时率 / 单位成本 / OEE 全是 null")
    void noDataYieldsNulls() {
        givenNothing();

        Map<String, Object> kpi = service.getKPIMetrics(FACTORY, DAY);

        assertThat(sub(kpi, "production").get("oee")).as("OEE").isNull();
        assertThat(sub(kpi, "production").get("yield")).as("良品率").isNull();
        assertThat(sub(kpi, "quality").get("fpy")).as("首检合格率").isNull();
        assertThat(sub(kpi, "quality").get("defectRate")).as("缺陷率").isNull();
        assertThat(sub(kpi, "delivery").get("onTimeRate")).as("准时交付率").isNull();
        assertThat(sub(kpi, "cost").get("unitCost")).as("单位成本").isNull();
        assertThat(kpi.get("productionEfficiency")).as("完成率(扁平键)").isNull();
    }

    @Test
    @DisplayName("⛔ 良品率和缺陷率不许同时是 0 —— 那两个结论互斥")
    void yieldAndDefectRateAreNeverBothZero() {
        givenNothing();

        Map<String, Object> kpi = service.getKPIMetrics(FACTORY, DAY);

        Object fpy = sub(kpi, "quality").get("fpy");
        Object defect = sub(kpi, "quality").get("defectRate");
        boolean bothZero = BigDecimal.ZERO.equals(fpy) && BigDecimal.ZERO.equals(defect);
        assertThat(bothZero)
                .as("「全不合格」和「零缺陷」同时成立 —— 这两个都不是读数")
                .isFalse();
    }

    @Test
    @DisplayName("阴性对照：有质检记录时照常算出真实良品率（⛔ 防「一律 null」也能过）")
    void withInspectionsStillComputes() {
        givenNothing();
        QualityInspection pass = new QualityInspection();
        pass.setResult("PASS");
        QualityInspection fail = new QualityInspection();
        fail.setResult("FAIL");
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of(pass, pass, pass, fail));

        Map<String, Object> kpi = service.getKPIMetrics(FACTORY, DAY);

        assertThat((BigDecimal) sub(kpi, "quality").get("fpy")).isEqualByComparingTo("75.00");
        assertThat((BigDecimal) sub(kpi, "quality").get("defectRate")).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("阴性对照：有计划产量时照常算出完成率")
    void withPlannedOutputStillComputes() {
        givenNothing();
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("80"));
        when(productionPlanRepository.calculatePlannedOutputBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("200"));

        Map<String, Object> kpi = service.getKPIMetrics(FACTORY, DAY);

        assertThat((BigDecimal) kpi.get("productionEfficiency")).isEqualByComparingTo("40.00");
    }
}

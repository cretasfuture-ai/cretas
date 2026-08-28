package com.cretas.aims.service.report.impl;

import com.cretas.aims.repository.QualityInspectionRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 没有质检样本时，合格率 / 返工率 / 一次通过率必须是 <b>null</b>，⛔ 不是编出来的数。
 *
 * <h3>改之前是什么样（2026-08-29 逐屏走查）</h3>
 *
 * <pre>
 *   double qualityRate = 98.0;      // 没有样本时直接报 98%
 *   double reworkRate  = 2.0;
 *   report.put("firstPassRate", firstPassRate != null ? firstPassRate : 96.0);
 * </pre>
 *
 * 这三个数<b>会被送到用户面前</b>：{@code ReportQualityTool} / {@code ReportAiQualityTool}
 * 把整个 map 原样喂给模型，{@code /reports/export} 和 {@code /reports/custom} 也带它。
 * 一个从没做过质检的工厂会被告知「合格率 98%」。
 *
 * <p>与本文件同一批被清掉的还有产能利用率那三个（写死的日产能 1000 / 工时 8 / 目标 80）。
 *
 * <h3>判据</h3>
 *
 * 每条正向断言都配一条能让它红的对照 —— 只断言「没样本时是 null」的话，
 * 把这三个字段整个删掉也能全绿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质量报表：没有样本就说没有，⛔ 不报 98%")
class QualityReportNoSamplesTest {

    private static final String FACTORY = "F006";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 22);
    private static final LocalDate TO = LocalDate.of(2026, 8, 29);

    @Mock
    private QualityInspectionRepository qualityInspectionRepository;

    @InjectMocks
    private ProductionReportServiceImpl service;

    private void givenSamples(String total, String pass, String fail, Double firstPass) {
        when(qualityInspectionRepository.calculateTotalSampleSize(anyString(), any(), any()))
                .thenReturn(total == null ? null : new BigDecimal(total));
        when(qualityInspectionRepository.calculateTotalPassCount(anyString(), any(), any()))
                .thenReturn(pass == null ? null : new BigDecimal(pass));
        when(qualityInspectionRepository.calculateTotalFailCount(anyString(), any(), any()))
                .thenReturn(fail == null ? null : new BigDecimal(fail));
        when(qualityInspectionRepository.calculateFirstPassRate(anyString(), any(), any()))
                .thenReturn(firstPass);
    }

    @Test
    @DisplayName("零样本 ⇒ 三个率全是 null（⛔ 不是 98 / 2 / 96）")
    void noSamplesYieldsNulls() {
        givenSamples("0", "0", "0", null);

        Map<String, Object> report = service.getQualityReport(FACTORY, FROM, TO);

        assertThat(report.get("qualityRate")).as("合格率").isNull();
        assertThat(report.get("reworkRate")).as("返工率").isNull();
        assertThat(report.get("firstPassRate")).as("一次通过率").isNull();
        // 计数本身 0 是合法读数 —— 它该留着，⛔ 不要跟着变 null
        assertThat(report.get("totalProduction")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("仓库返回 null（一条记录都没有）⇒ 同样是 null，不 NPE")
    void nullCountsAlsoYieldNulls() {
        givenSamples(null, null, null, null);

        Map<String, Object> report = service.getQualityReport(FACTORY, FROM, TO);

        assertThat(report.get("qualityRate")).isNull();
        assertThat(report.get("reworkRate")).isNull();
        assertThat(report.get("firstPassRate")).isNull();
    }

    @Test
    @DisplayName("阴性对照：有样本时照旧算出真实的率（⛔ 防「一律返回 null」也能过）")
    void withSamplesStillComputesRealRates() {
        givenSamples("200", "190", "10", 93.5);

        Map<String, Object> report = service.getQualityReport(FACTORY, FROM, TO);

        assertThat((Double) report.get("qualityRate")).isEqualTo(95.0);   // 190/200
        assertThat((Double) report.get("reworkRate")).isEqualTo(5.0);     // 10/200
        assertThat((Double) report.get("firstPassRate")).isEqualTo(93.5); // 直接来自仓库
    }

    @Test
    @DisplayName("阴性对照：有样本、但仓库给不出一次通过率 ⇒ 只有它是 null，另两个照常")
    void firstPassRateIsIndependent() {
        givenSamples("200", "190", "10", null);

        Map<String, Object> report = service.getQualityReport(FACTORY, FROM, TO);

        assertThat(report.get("firstPassRate")).isNull();
        assertThat((Double) report.get("qualityRate")).isEqualTo(95.0);
    }

    @Test
    @DisplayName("⛔ 那三个被编出来的数一个都不许再出现")
    void theFabricatedNumbersAreGone() {
        givenSamples("0", "0", "0", null);

        Map<String, Object> report = service.getQualityReport(FACTORY, FROM, TO);

        assertThat(report.values()).doesNotContain(98.0, 2.0, 96.0);
    }
}

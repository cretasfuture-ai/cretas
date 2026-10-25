package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.OeeReportDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 设备可用率：<b>一台设备都没上报运行时长</b> 与 <b>设备确实全停</b> 必须分得开。
 *
 * <h3>实测（2026-08-29，线上 F006）</h3>
 *
 * KPI 看板 {@code production.oee = 0.0}。查下去：8 台设备的
 * {@code totalRunningHours} <b>全是 null</b>，累加器停在 0 ⇒ 可用率 0% ⇒ OEE 0。
 * 屏上读起来是「设备全停」，而真相是这项数据**没有采集**。
 *
 * <h3>顺带修掉的第二个编出来的数</h3>
 *
 * {@code plannedHours = days * 8 * 设备数} —— 又一个写死的 8 小时工作日。
 * 与产能利用率那处的 {@code totalDays * 8} 同源（那处已改成工厂真实工时）。
 * F006 的真实工时是 <b>10</b>。
 *
 * <h3>判据</h3>
 *
 * 「没上报 ⇒ null」这一条必须配一条「上报了 0 ⇒ 就是 0%」的阴性对照 ——
 * 否则把 availability 直接删掉也能过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("设备可用率：没上报和全停必须分得开")
class OeeAvailabilityNoRuntimeTest {

    private static final String FACTORY = "F006";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 22);
    private static final LocalDate TO = LocalDate.of(2026, 8, 29);

    @Mock
    private EquipmentRepository equipmentRepository;
    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private FactorySettingsRepository factorySettingsRepository;

    @InjectMocks
    private ProductionReportServiceImpl service;

    private static FactoryEquipment equipment(Integer runningHours) {
        FactoryEquipment eq = new FactoryEquipment();
        eq.setTotalRunningHours(runningHours);
        return eq;
    }

    private void givenWorkingHours(Integer hours) {
        FactorySettings settings = new FactorySettings();
        if (hours != null) {
            settings.setWorkingHours(hours);
        }
        when(factorySettingsRepository.findByFactoryId(anyString()))
                .thenReturn(hours == null ? Optional.empty() : Optional.of(settings));
    }

    /** 有产量才能让 performance / quality 非 null，从而让 OEE 的判定只取决于 availability。 */
    private void givenBatches() {
        ProductionBatch b = new ProductionBatch();
        b.setPlannedQuantity(new BigDecimal("100"));
        b.setActualQuantity(new BigDecimal("90"));
        // 良品率读真实良品数 (不再是 产出 × 0.95), 不设它 quality 就是 null
        b.setGoodQuantity(new BigDecimal("85"));
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(b));
    }

    @Test
    @DisplayName("设备都没上报运行时长 ⇒ 可用率 null，OEE 也 null（⛔ 不是 0%）")
    void noEquipmentReportsRuntime() {
        givenWorkingHours(10);
        givenBatches();
        when(equipmentRepository.findByFactoryId(anyString()))
                .thenReturn(List.of(equipment(null), equipment(null)));

        OeeReportDTO dto = service.getOeeReport(FACTORY, FROM, TO);

        assertThat(dto.getAvailability()).as("可用率").isNull();
        assertThat(dto.getOeeValue()).as("OEE").isNull();
    }

    @Test
    @DisplayName("阴性对照：上报了 0 小时 ⇒ 就是 0%，与「没上报」分得开")
    void reportedZeroHoursIsZeroPercent() {
        givenWorkingHours(10);
        givenBatches();
        when(equipmentRepository.findByFactoryId(anyString()))
                .thenReturn(List.of(equipment(0), equipment(0)));

        OeeReportDTO dto = service.getOeeReport(FACTORY, FROM, TO);

        assertThat(dto.getAvailability()).as("可用率").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("阴性对照：真有运行时长 ⇒ 照常算（⛔ 防「一律 null」也能过）")
    void realRuntimeStillComputes() {
        givenWorkingHours(10);
        givenBatches();
        // 7 天 x 10 小时 x 2 台 = 140 计划工时; 实跑 70 ⇒ 50%
        when(equipmentRepository.findByFactoryId(anyString()))
                .thenReturn(List.of(equipment(40), equipment(30)));

        OeeReportDTO dto = service.getOeeReport(FACTORY, FROM, TO);

        assertThat(dto.getAvailability()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("工厂没配工时 ⇒ 算不出计划工时 ⇒ 可用率 null（⛔ 不拿 8 小时顶）")
    void withoutWorkingHoursAvailabilityIsNull() {
        givenWorkingHours(null);
        givenBatches();
        when(equipmentRepository.findByFactoryId(anyString()))
                .thenReturn(List.of(equipment(40), equipment(30)));

        OeeReportDTO dto = service.getOeeReport(FACTORY, FROM, TO);

        assertThat(dto.getAvailability()).isNull();
    }
}

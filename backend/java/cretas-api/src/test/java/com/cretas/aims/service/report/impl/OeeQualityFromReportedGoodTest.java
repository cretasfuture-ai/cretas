package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.OeeReportDTO;
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
 * OEE 良品率读批次真实报的良品数，⛔ 不再是「产出 × 0.95」。
 *
 * <p>原来不管批次报了什么，良品率恒为 95%。判据：报了良品就按良品算（阳性），
 * 一个都没报就是 null（⛔ 不是 95，也不是 0），没报良品的批次不进分母。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OEE 良品率：读真实良品数，不乘 0.95")
class OeeQualityFromReportedGoodTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 18);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);

    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock EquipmentRepository equipmentRepository;
    @Mock FactorySettingsRepository factorySettingsRepository;

    @InjectMocks ProductionReportServiceImpl service;

    private static ProductionBatch batch(String actual, String good) {
        ProductionBatch b = new ProductionBatch();
        b.setPlannedQuantity(new BigDecimal("100"));
        b.setActualQuantity(new BigDecimal(actual));
        if (good != null) {
            b.setGoodQuantity(new BigDecimal(good));
        }
        return b;
    }

    private OeeReportDTO run(ProductionBatch... batches) {
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(batches));
        when(equipmentRepository.findByFactoryId(anyString())).thenReturn(List.of());
        when(factorySettingsRepository.findByFactoryId(anyString())).thenReturn(Optional.empty());
        return service.getOeeReport("F006", FROM, TO);
    }

    @Test
    @DisplayName("报了良品 80/100 ⇒ 良品率 80%，不良 20（原来恒 95% / 5）")
    void qualityComesFromReportedGood() {
        OeeReportDTO dto = run(batch("100", "80"));

        assertThat(dto.getQuality()).isEqualByComparingTo("80.00");
        assertThat(dto.getGoodOutput()).isEqualByComparingTo("80.00");
        assertThat(dto.getDefectOutput()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("没有批次报良品 ⇒ 良品率、良品、不良全为 null（⛔ 不是 95%）")
    void noReportedGoodMeansUnknown() {
        OeeReportDTO dto = run(batch("100", null), batch("50", null));

        assertThat(dto.getQuality()).isNull();
        assertThat(dto.getGoodOutput()).isNull();
        assertThat(dto.getDefectOutput()).isNull();
        assertThat(dto.getTotalOutput()).as("产出照常统计").isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("没报良品的批次不进分母：报了的 90/100 + 没报的 100 ⇒ 90%（不是 45%）")
    void unreportedBatchesStayOutOfTheDenominator() {
        OeeReportDTO dto = run(batch("100", "90"), batch("100", null));

        assertThat(dto.getQuality()).isEqualByComparingTo("90.00");
        assertThat(dto.getDefectOutput()).isEqualByComparingTo("10.00");
    }
}

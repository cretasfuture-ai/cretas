package com.cretas.aims.service.impl;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProcessingStageRecordRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 🔒 公开溯源页(消费者扫码)的「认证信息」不许是写死的常量。
 *
 * <p>原实现对所有批次无条件返回「食品安全认证」。目前没有产品 / 工厂级认证的落库来源,
 * 所以必须是 null(前端为 null 时整块不渲染)。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("公开溯源: 认证信息没有来源时为 null, 不写死「食品安全认证」")
class PublicTraceCertificationInfoTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ShipmentRecordRepository shipmentRecordRepository;
    @Mock private ProcessingStageRecordRepository processingStageRecordRepository;
    @Mock private UserRepository userRepository;
    @Mock private FactoryRepository factoryRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TraceabilityServiceImpl service;

    @Test
    @DisplayName("没有认证来源: certificationInfo 为 null; 其余真实字段照常返回")
    void certificationInfoIsNullWithoutASource() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(7001L);
        batch.setFactoryId("F006");
        batch.setBatchNumber("PB-CERT-1");
        batch.setProductName("卤猪蹄");
        batch.setActualQuantity(new BigDecimal("10"));
        batch.setStartTime(LocalDateTime.parse("2026-10-20T08:00:00"));
        Factory factory = new Factory();
        factory.setName("六膳门");

        when(productionBatchRepository.findByBatchNumber("PB-CERT-1")).thenReturn(Optional.of(batch));
        when(factoryRepository.findById("F006")).thenReturn(Optional.of(factory));
        when(materialConsumptionRepository.findByProductionBatchId(7001L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(7001L)).thenReturn(List.of());

        TraceabilityDTO.PublicTraceResponse r = service.getPublicTrace("PB-CERT-1");
        System.out.println("[observe] isValid=" + r.getIsValid() + " product=" + r.getProductName()
                + " certificationInfo=" + r.getCertificationInfo());

        // 一定会发生的读数: 查到了批次, 真实字段在 (否则 null 可能只是没走到构建那一步)
        assertThat(r.getIsValid()).isTrue();
        assertThat(r.getProductName()).isEqualTo("卤猪蹄");
        assertThat(r.getFactoryName()).isEqualTo("六膳门");

        assertThat(r.getCertificationInfo())
                .as("原来对所有批次无条件写死「食品安全认证」")
                .isNull();
    }

    @Test
    @DisplayName("找不到工厂: factoryName 为 null, ⛔ 不回落成写死的「认证工厂」")
    void factoryNameIsNullWhenTheFactoryIsMissing() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(7002L);
        batch.setFactoryId("F-GONE");
        batch.setBatchNumber("PB-CERT-2");
        batch.setProductName("卤猪蹄");

        when(productionBatchRepository.findByBatchNumber("PB-CERT-2")).thenReturn(Optional.of(batch));
        when(factoryRepository.findById("F-GONE")).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(7002L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(7002L)).thenReturn(List.of());

        TraceabilityDTO.PublicTraceResponse r = service.getPublicTrace("PB-CERT-2");

        // 一定会发生的读数: 批次查到了, 响应走完了构建
        assertThat(r.getIsValid()).isTrue();
        assertThat(r.getProductName()).isEqualTo("卤猪蹄");
        assertThat(r.getFactoryName()).as("原来回落成「认证工厂」").isNull();
    }
}

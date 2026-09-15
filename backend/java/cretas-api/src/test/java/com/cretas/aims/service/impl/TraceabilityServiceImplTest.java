package com.cretas.aims.service.impl;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
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
import com.cretas.aims.entity.Label;
import com.cretas.aims.repository.LabelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceabilityServiceImplTest {

    private static final String FACTORY = "F006";

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
    @Mock private LabelRepository labelRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TraceabilityServiceImpl service;

    @Test
    @DisplayName("full trace includes process-sheet mixed lineage and filters other SKU rows")
    void getFullTrace_includesProcessSheetMixedLineageAndFiltersOtherSku() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9440L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("CLK-B-FINAL");
        batch.setProductionPlanId("plan-1");
        batch.setProductTypeId("sku-final");
        batch.setProductName("Final SKU");
        batch.setActualQuantity(new BigDecimal("480"));
        batch.setUnit("盒");
        batch.setStartTime(LocalDateTime.parse("2026-06-27T08:00:00"));

        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "CLK-B-FINAL"))
                .thenReturn(Optional.of(batch));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(9440L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(9440L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(9440L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "CLK-B-FINAL")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-1")).thenReturn(List.of(
                processRow(1L, 1, "mix", null, """
                        {
                          "processName": "mixed-input",
                          "processDate": "2026-06-27",
                          "productTypeId": "sku-final",
                          "inputQuantity": 70,
                          "outputQuantity": 74,
                          "upstreamSources": [
                            { "sourceBatchNumber": "A1", "feedQuantityKg": 25 },
                            { "sourceBatchNumber": "A2", "feedQuantityKg": 15 },
                            { "sourceBatchNumber": "B1", "feedQuantityKg": 30 }
                          ]
                        }
                        """),
                processRow(2L, 3, "pack", "CLK-B-FINAL", """
                        {
                          "processName": "cross-day-pack",
                          "processDate": "2026-06-29",
                          "productTypeId": "sku-final",
                          "inputQuantity": 60,
                          "outputQuantity": 480,
                          "upstreamSources": [
                            { "sourceBatchNumber": "COOK", "feedQuantityKg": 60 }
                          ]
                        }
                        """),
                processRow(3L, 99, "noise", "NOISE-BATCH", """
                        {
                          "processName": "noise-different-sku",
                          "processDate": "2026-06-29",
                          "productTypeId": "sku-noise",
                          "inputQuantity": 1,
                          "outputQuantity": 999
                        }
                        """)
        ));
        // 没打过标签 ⇒ 溯源码为 null(⛔ 不再现造一个扫不回来的码)
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(9440L)).thenReturn(List.of());

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "CLK-B-FINAL");

        assertThat(response.getProcessingStages()).hasSize(2);
        assertThat(response.getProcessingStages())
                .extracting(TraceabilityDTO.ProcessingStageInfo::getStageName)
                .containsExactly("mixed-input", "cross-day-pack");
        assertThat(response.getProcessingStages())
                .extracting(TraceabilityDTO.ProcessingStageInfo::getStageOrder)
                .doesNotContain(99);

        TraceabilityDTO.ProcessingStageInfo mix = response.getProcessingStages().get(0);
        assertThat(mix.getStageType()).isEqualTo("PROCESS_SHEET");
        assertThat(mix.getOperatorName()).contains("A1(25kg)", "A2(15kg)", "B1(30kg)");
        assertThat(mix.getInputWeight()).isEqualTo(70.0);
        assertThat(mix.getOutputWeight()).isEqualTo(74.0);

        TraceabilityDTO.ProcessingStageInfo pack = response.getProcessingStages().get(1);
        assertThat(pack.getStartTime().toLocalDate().toString()).isEqualTo("2026-06-29");
        assertThat(pack.getOutputWeight()).isEqualTo(480.0);
        assertThat(response.getTraceCode()).as("没打过标签就没有溯源码, 不现造").isNull();
    }

    private ProcessSheetRow processRow(Long id, Integer order, String processCode, String batchNumber, String payload) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId("plan-1");
        row.setProcessCode(processCode);
        row.setProcessOrder(order);
        row.setClientRowId("row-" + id);
        row.setBatchNumber(batchNumber);
        row.setRowPayload(payload);
        return row;
    }

    @Test
    @DisplayName("trace code is the persisted label code (newest label wins), never a generated one")
    void getFullTrace_traceCodeComesFromPersistedLabel() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9441L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("SL-DEMO-BATCH-V1");
        batch.setProductTypeId("sku-final");
        batch.setProductName("酱牛肉");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "SL-DEMO-BATCH-V1"))
                .thenReturn(Optional.of(batch));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());

        Label older = new Label();
        older.setTraceCode("TRACE-SL-DEMO-BATCH-V1-OLD");
        older.setCreatedAt(LocalDateTime.parse("2026-09-14T10:00:00"));
        Label newer = new Label();
        newer.setTraceCode("TRACE-SL-DEMO-BATCH-V1-SEED01");
        newer.setCreatedAt(LocalDateTime.parse("2026-09-15T09:56:09"));
        Label blank = new Label();  // 没写 traceCode 的标签不算
        blank.setCreatedAt(LocalDateTime.parse("2026-09-15T12:00:00"));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(9441L))
                .thenReturn(List.of(older, blank, newer));

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "SL-DEMO-BATCH-V1");

        assertThat(response.getTraceCode())
                .as("溯源码必须是标签上印的那个(最新一张), 它才扫得回来")
                .isEqualTo("TRACE-SL-DEMO-BATCH-V1-SEED01");
    }
}

package com.cretas.aims.service.impl;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
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
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.entity.Label;
import com.cretas.aims.repository.LabelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SalesDeliveryItemBatchAllocationRepository salesDeliveryItemBatchAllocationRepository;
    @Mock private SalesDeliveryItemRepository salesDeliveryItemRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private BatchLineageEdgeRepository batchLineageEdgeRepository;
    @Mock private com.cretas.aims.service.unit.UnitContractService unitContractService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TraceabilityServiceImpl service;

    @BeforeEach
    void wireOptionalSalesRepositories() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        // Mockito constructor injection does not subsequently wire optional Spring fields.
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryItemBatchAllocationRepository", salesDeliveryItemBatchAllocationRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryItemRepository", salesDeliveryItemRepository);
        ReflectionTestUtils.setField(service, "salesDeliveryRecordRepository", salesDeliveryRecordRepository);
        ReflectionTestUtils.setField(service, "batchLineageEdgeRepository", batchLineageEdgeRepository);
    }

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
        assertThat(mix.getOperatorName()).as("source allocations are not worker identity").isNull();
        assertThat(mix.getInputWeight()).isEqualTo(70.0);
        assertThat(mix.getOutputWeight()).isEqualTo(74.0);

        TraceabilityDTO.ProcessingStageInfo pack = response.getProcessingStages().get(1);
        assertThat(pack.getStartTime().toLocalDate().toString()).isEqualTo("2026-06-29");
        assertThat(pack.getOutputWeight()).isEqualTo(480.0);
        assertThat(response.getTraceCode()).as("没打过标签就没有溯源码, 不现造").isNull();
    }

    @Test
    @DisplayName("process secret: full trace marks seasoning written from the recipe as automatic")
    void getFullTrace_marksRecipeDerivedSeasoningAsAutomatic() {
        ProductionBatch target = traceBatch(102L, "B-1", "sku-b", "plan-joint");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-1"))
                .thenReturn(Optional.of(target));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        MaterialConsumption seasoning = material("raw-1", 102L, "0.1725");
        seasoning.setSourceType("SEASONING");
        when(materialConsumptionRepository.findByProductionBatchId(102L)).thenReturn(List.of(seasoning));
        when(materialBatchRepository.findAllById(java.util.Set.of("raw-1")))
                .thenReturn(List.of(materialBatch("raw-1", "CINNAMON-1")));
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(102L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(102L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-1")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint")).thenReturn(List.of());
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(102L)).thenReturn(List.of());

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-1");

        assertThat(response.getMaterials()).extracting(TraceabilityDTO.MaterialInfo::isAutomatic)
                .containsExactly(true);
    }

    @Test
    @DisplayName("full trace binds shared material and process row to the exact multi-output report group")
    void getFullTrace_bindsSharedInputsAndStagesToExactMultiOutputGroup() {
        ProductionBatch target = traceBatch(102L, "B-1", "sku-b", "plan-joint");
        ProductionBatch sibling = traceBatch(101L, "A-1", "sku-a", "plan-joint");

        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-1"))
                .thenReturn(Optional.of(target));
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "A-1"))
                .thenReturn(Optional.of(sibling));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(102L)).thenReturn(List.of());
        when(materialConsumptionRepository.findByProductionBatchId(101L)).thenReturn(List.of(material("raw-1", 101L, "2")));
        when(materialBatchRepository.findAllById(java.util.Set.of("raw-1")))
                .thenReturn(List.of(materialBatch("raw-1", "RAW-1")));
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(102L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(102L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-1")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint"))
                .thenReturn(List.of(
                        groupedRow(1L, 101L, FACTORY, "plan-joint", 1, "A-1", "sku-a", "group-a", "1"),
                        groupedRow(2L, 102L, FACTORY, "plan-joint", 1, "B-1", "sku-b", "group-a", "2"),
                        groupedRow(3L, 103L, FACTORY, "plan-joint", 1, "B-2", "sku-b", "group-b", "9"),
                        groupedRow(4L, 104L, FACTORY, "other-plan", 1, "B-3", "sku-b", "group-a", "7"),
                        groupedRow(5L, 105L, "OTHER_FACTORY", "plan-joint", 1, "B-4", "sku-b", "group-a", "8")
                ));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(102L)).thenReturn(List.of());

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-1");

        assertThat(response.getMaterials()).extracting(TraceabilityDTO.MaterialInfo::getBatchNumber)
                .containsExactly("RAW-1");
        assertThat(response.getMaterials().getFirst().getQuantity()).isEqualTo(2.0);
        assertThat(response.getMaterials().getFirst().isAutomatic()).as("人工投料不标自动").isFalse();
        assertThat(response.getSharedInputSourceBatchNumber()).isEqualTo("A-1");
        assertThat(response.getProcessingStages()).extracting(TraceabilityDTO.ProcessingStageInfo::getInputWeight)
                .containsExactly(2.0);
    }

    @Test
    @DisplayName("full trace leaves shared input unknown when multi-output association is malformed or crosses factory/plan")
    void getFullTrace_rejectsMalformedOrCrossScopeMultiOutputAssociations() {
        ProductionBatch target = traceBatch(202L, "B-2", "sku-b", "plan-joint");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-2"))
                .thenReturn(Optional.of(target));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(202L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(202L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(202L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-2")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint"))
                .thenReturn(List.of(
                        groupedRow(11L, 201L, FACTORY, "other-plan", 1, "A-2", "sku-a", "group-a", "4"),
                        groupedRow(12L, 202L, "OTHER_FACTORY", "plan-joint", 1, "A-3", "sku-a", "group-a", "5"),
                        groupedRow(13L, 202L, FACTORY, "plan-joint", 1, "B-2", "sku-b", "", "6")
                ));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(202L)).thenReturn(List.of());

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-2");

        assertThat(response.getMaterials()).isEmpty();
        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
        assertThat(response.getProcessingStages()).isEmpty();
        verify(materialConsumptionRepository, never()).findByProductionBatchId(201L);
    }

    @Test
    @DisplayName("full trace does not label a shared owner when multiple group owners have consumption")
    void getFullTrace_ambiguousSharedOwnersDoNotSetSourceField() {
        ProductionBatch target = traceBatch(302L, "B-3", "sku-b", "plan-joint");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-3"))
                .thenReturn(Optional.of(target));
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "A-3"))
                .thenReturn(Optional.of(traceBatch(301L, "A-3", "sku-a", "plan-joint")));
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "C-3"))
                .thenReturn(Optional.of(traceBatch(303L, "C-3", "sku-c", "plan-joint")));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(302L)).thenReturn(List.of());
        when(materialConsumptionRepository.findByProductionBatchId(301L)).thenReturn(List.of(material("raw-a", 301L, "1")));
        when(materialConsumptionRepository.findByProductionBatchId(303L)).thenReturn(List.of(material("raw-c", 303L, "1")));
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(302L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(302L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-3")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint"))
                .thenReturn(List.of(
                        groupedRow(21L, 301L, FACTORY, "plan-joint", 1, "A-3", "sku-a", "group-c", "1"),
                        groupedRow(22L, 302L, FACTORY, "plan-joint", 1, "B-3", "sku-b", "group-c", "1"),
                        groupedRow(23L, 303L, FACTORY, "plan-joint", 1, "C-3", "sku-c", "group-c", "1")
                ));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(302L)).thenReturn(List.of());

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-3");

        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
        assertThat(response.getMaterials()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "WRONG-NUMBER"})
    void sharedOwnerCannotBorrowNumberFromAnotherReportGroup(String ownerNumber) {
        ProductionBatch target = traceBatch(402L, "B-4", "sku-b", "plan-joint");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-4")).thenReturn(Optional.of(target));
        org.mockito.Mockito.lenient().when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "A-4"))
                .thenReturn(Optional.of(traceBatch(401L, "A-4", "sku-a", "plan-joint")));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint")).thenReturn(List.of(
                groupedRow(31L, 401L, FACTORY, "plan-joint", 1, ownerNumber, "sku-a", "group-e", "1"),
                groupedRow(32L, 402L, FACTORY, "plan-joint", 1, "B-4", "sku-b", "group-e", "1"),
                groupedRow(33L, 401L, FACTORY, "plan-joint", 2, "A-4", "sku-a", "other-group", "9")));
        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-4");
        assertThat(response.getMaterials()).isEmpty();
        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
        verify(materialConsumptionRepository, never()).findByProductionBatchId(401L);
    }

    @Test
    void sharedOwnerRequiresMatchingPersistedBatchIdentity() {
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-5"))
                .thenReturn(Optional.of(traceBatch(502L, "B-5", "sku-b", "plan-joint")));
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "A-5"))
                .thenReturn(Optional.of(traceBatch(599L, "A-5", "sku-a", "plan-joint")));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint")).thenReturn(List.of(
                groupedRow(41L, 501L, FACTORY, "plan-joint", 1, "A-5", "sku-a", "group-f", "1"),
                groupedRow(42L, 502L, FACTORY, "plan-joint", 1, "B-5", "sku-b", "group-f", "1")));
        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-5");
        assertThat(response.getMaterials()).isEmpty();
        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
        verify(materialConsumptionRepository, never()).findByProductionBatchId(501L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousTargetGroupsAreUnknownRegardlessOfRepositoryOrder(boolean reverse) {
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "B-6"))
                .thenReturn(Optional.of(traceBatch(602L, "B-6", "sku-b", "plan-joint")));
        var rows = new java.util.ArrayList<>(List.of(
                groupedRow(51L, 601L, FACTORY, "plan-joint", 1, "A-6", "sku-a", "group-g", "1"),
                groupedRow(52L, 602L, FACTORY, "plan-joint", 1, "B-6", "sku-b", "group-g", "1"),
                groupedRow(53L, 603L, FACTORY, "plan-joint", 1, "C-6", "sku-c", "group-h", "2"),
                groupedRow(54L, 602L, FACTORY, "plan-joint", 1, "B-6", "sku-b", "group-h", "2")));
        if (reverse) java.util.Collections.reverse(rows);
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-joint")).thenReturn(rows);
        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "B-6");
        assertThat(response.getMaterials()).isEmpty();
        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
        assertThat(response.getProcessingStages()).isEmpty();
        verify(materialConsumptionRepository, never()).findByProductionBatchId(601L);
        verify(materialConsumptionRepository, never()).findByProductionBatchId(603L);
    }

    private ProductionBatch traceBatch(Long id, String number, String sku, String plan) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(number);
        batch.setProductTypeId(sku);
        batch.setProductName(sku);
        batch.setProductionPlanId(plan);
        return batch;
    }

    private MaterialConsumption material(String batchId, Long productionBatchId, String quantity) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(FACTORY);
        consumption.setProductionPlanId("plan-joint");
        consumption.setBatchId(batchId);
        consumption.setProductionBatchId(productionBatchId);
        consumption.setQuantity(new BigDecimal(quantity));
        return consumption;
    }

    private MaterialBatch materialBatch(String id, String number) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setBatchNumber(number);
        batch.setMaterialTypeId("raw");
        batch.setQuantityUnit("kg");
        return batch;
    }

    private ProcessSheetRow groupedRow(Long id, Long batchId, String factory, String plan, Integer order,
                                       String batchNumber, String sku, String baseRowId, String input) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(factory);
        row.setPlanId(plan);
        row.setProcessCode("joint-process");
        row.setProcessOrder(order);
        row.setClientRowId("row-" + id);
        row.setBatchNumber(batchNumber);
        row.setBatchId(batchId);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload("{\"processName\":\"joint\",\"processDate\":\"2026-10-19\","
                + "\"productTypeId\":\"" + sku + "\",\"inputQuantity\":" + input
                + ",\"outputQuantity\":1,\"inputUnit\":\"kg\",\"outputUnit\":\"盒\","
                + "\"multiOutputMember\":true,\"multiOutputBaseRowId\":\"" + baseRowId + "\"}");
        return row;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"actual", "foreign", "wrong-source", "wrong-carrier", "missing-time", "no-edge"})
    @DisplayName("full trace projects shipped sales allocations per finished batch and native unit")
    void getFullTrace_projectsShippedSalesAllocationsWithoutMixingSkus(String timeCase) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9442L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("FG-TRACE");
        batch.setProductionPlanId("plan-sales");
        batch.setProductTypeId("sku-final");
        batch.setProductName("Final SKU");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "FG-TRACE"))
                .thenReturn(Optional.of(batch));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(9442L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(9442L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(9442L)).thenReturn(List.of());
        ShipmentRecord legacy = new ShipmentRecord();
        legacy.setShipmentNumber("LEGACY-1");
        legacy.setShipmentDate(java.time.LocalDate.of(2026, 10, 15));
        legacy.setQuantity(new BigDecimal("4"));
        legacy.setUnit("kg");
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "FG-TRACE"))
                .thenReturn(List.of(legacy));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-sales"))
                .thenReturn(List.of());
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(9442L)).thenReturn(List.of());

        FinishedGoodsBatch batchA = finishedGoodsBatch("fg-a", "kg");
        FinishedGoodsBatch batchB = finishedGoodsBatch("fg-b", "盒");
        FinishedGoodsBatch foreignBatch = finishedGoodsBatch("fg-foreign", "kg");
        foreignBatch.setFactoryId("OTHER");
        FinishedGoodsBatch draftBatch = finishedGoodsBatch("fg-draft", "kg");
        FinishedGoodsBatch wrongSkuBatch = finishedGoodsBatch("fg-wrong-sku", "kg");
        wrongSkuBatch.setProductTypeId("sku-other");
        when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, "plan-sales"))
                .thenReturn(List.of(batchA, batchB, foreignBatch, draftBatch, wrongSkuBatch));

        SalesDeliveryItem itemA = deliveryItem(101L, "delivery-a", "fg-a", "sku-final", "kg");
        // A split delivery keeps its first batch on the legacy item field;
        // the allocation still proves that the second batch was shipped.
        SalesDeliveryItem itemB = deliveryItem(102L, "delivery-b", "first-deducted-batch", "sku-final", "盒");
        SalesDeliveryItem itemDraft = deliveryItem(104L, "delivery-draft", "fg-draft", "sku-final", "kg");
        when(salesDeliveryItemRepository.findById(101L)).thenReturn(Optional.of(itemA));
        when(salesDeliveryItemRepository.findById(102L)).thenReturn(Optional.of(itemB));
        when(salesDeliveryItemRepository.findById(104L)).thenReturn(Optional.of(itemDraft));
        SalesDeliveryItemBatchAllocation allocationA = allocation("alloc-a", "101", "fg-a", "4", "kg");
        SalesDeliveryItemBatchAllocation allocationB = allocation("alloc-b", "102", "fg-b", "10", "盒");
        SalesDeliveryItemBatchAllocation foreignAllocation = allocation("alloc-foreign", "999", "fg-b", "99", "盒");
        foreignAllocation.setFactoryId("OTHER");
        SalesDeliveryItemBatchAllocation mismatchedAllocation = allocation("alloc-mismatch", "998", "other-batch", "88", "盒");
        SalesDeliveryItemBatchAllocation allocationDraft = allocation("alloc-draft", "104", "fg-draft", "6", "kg");
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-a"))
                .thenReturn(List.of(allocationA));
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-b"))
                .thenReturn(List.of(allocationB, foreignAllocation, mismatchedAllocation));
        when(salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-draft"))
                .thenReturn(List.of(allocationDraft));
        SalesDeliveryRecord deliveryA = delivery("delivery-a", "SO-A", SalesDeliveryStatus.SHIPPED);
        SalesDeliveryRecord deliveryB = delivery("delivery-b", "SO-B", SalesDeliveryStatus.DELIVERED);
        LocalDateTime actual = LocalDateTime.of(2026, 10, 17, 0, 0, 21);
        deliveryA.setDeliveryDate(java.time.LocalDate.of(2026, 10, 16));
        // A later edit/signature must never become the shipment business timestamp.
        deliveryA.setUpdatedAt(actual.plusDays(2));
        BatchLineageEdge shipmentEdge = new BatchLineageEdge();
        shipmentEdge.setFactoryId("foreign".equals(timeCase) ? "OTHER" : FACTORY);
        shipmentEdge.setSourceType("FINISHED_BATCH");
        shipmentEdge.setSourceId("wrong-source".equals(timeCase) ? "fg-other" : "fg-a");
        shipmentEdge.setTargetType("SHIPMENT_RECORD"); shipmentEdge.setTargetId("delivery-a");
        shipmentEdge.setEdgeType("FINISHED_TO_SHIPMENT");
        shipmentEdge.setEventTime("missing-time".equals(timeCase) ? null : actual);
        shipmentEdge.setMeta(java.util.Map.of("shipmentCarrier", "wrong-carrier".equals(timeCase) ? "OTHER" : "SALES_DELIVERY_RECORD"));
        when(batchLineageEdgeRepository.findByFactoryIdAndTargetIdAndTargetType(
                FACTORY, "delivery-a", "SHIPMENT_RECORD"))
                .thenReturn("no-edge".equals(timeCase) ? List.of() : List.of(shipmentEdge));
        SalesDeliveryRecord draftDelivery = delivery("delivery-draft", "SO-DRAFT", SalesDeliveryStatus.DRAFT);
        when(salesDeliveryRecordRepository.findById("delivery-a")).thenReturn(Optional.of(deliveryA));
        when(salesDeliveryRecordRepository.findById("delivery-b")).thenReturn(Optional.of(deliveryB));
        when(salesDeliveryRecordRepository.findById("delivery-draft")).thenReturn(Optional.of(draftDelivery));

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, "FG-TRACE");
        assertThat(response.getShipments().get(0).getShipmentDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 15));
        assertThat(response.getShipments().get(1).getShipmentDate())
                .isEqualTo("actual".equals(timeCase) ? actual.toLocalDate() : null);
        assertThat(response.getShipments().get(0).getShipmentTimeSource()).isEqualTo("LEGACY_DATE_ONLY");
        assertThat(response.getShipments().get(1).getPlannedDeliveryDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 16));
        assertThat(response.getShipments().get(1).getShippedAt()).isEqualTo("actual".equals(timeCase) ? actual : null);
        assertThat(response.getShipments().get(1).getShipmentTimeSource())
                .isEqualTo("actual".equals(timeCase) ? "LINEAGE_EVENT" : "UNRECORDED");

        assertThat(response.getShipments()).hasSize(3);
        assertThat(response.getShipments())
                .extracting(TraceabilityDTO.ShipmentInfo::getShipmentNumber)
                .containsExactly("LEGACY-1", "SO-A", "SO-B");
        assertThat(response.getShipments())
                .extracting(TraceabilityDTO.ShipmentInfo::getQuantity)
                .containsExactly(4.0, 4.0, 10.0);
        assertThat(response.getShipments())
                .extracting(TraceabilityDTO.ShipmentInfo::getUnit)
                .containsExactly("kg", "kg", "盒");
        verify(salesDeliveryItemBatchAllocationRepository, never())
                .findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-foreign");
        verify(salesDeliveryItemBatchAllocationRepository, never())
                .findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-wrong-sku");
        verify(salesDeliveryItemBatchAllocationRepository)
                .findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-a");
        verify(salesDeliveryItemBatchAllocationRepository)
                .findByFactoryIdAndFinishedGoodsBatchId(FACTORY, "fg-b");
        verify(salesDeliveryItemRepository, never()).findById(999L);
        verify(salesDeliveryItemRepository, never()).findById(998L);
    }

    @Test
    @DisplayName("process-sheet trace keeps real input/output units and does not calculate cross-unit loss")
    void getFullTrace_processSheetUnitsRemainExplicit() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9443L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("UNIT-TRACE");
        batch.setProductionPlanId("plan-units");
        batch.setProductTypeId("sku-final");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "UNIT-TRACE"))
                .thenReturn(Optional.of(batch));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(9443L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(9443L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(9443L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "UNIT-TRACE")).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-units")).thenReturn(List.of(
                processRow(41L, 1, "pack", null, """
                        {
                          "processName":"pack",
                          "processDate":"2026-10-15",
                          "productTypeId":"sku-final",
                          "inputQuantity":10,
                          "outputQuantity":20,
                          "inputUnit":"kg",
                          "outputUnit":"盒",
                          "submittedBy":"should-not-be-an-operator",
                          "rawMaterialInputs":[
                            {"materialBatchId":"raw-uuid-1","quantity":4,"unit":"kg"},
                            {"materialBatchId":"pack-uuid-2","quantity":10,"unit":"盒","materialCategory":"PACKAGING_MATERIAL"}
                          ]
                        }
                        """)));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(9443L)).thenReturn(List.of());

        TraceabilityDTO.ProcessingStageInfo stage = service.getFullTrace(FACTORY, "UNIT-TRACE")
                .getProcessingStages().get(0);

        assertThat(stage.getInputUnit()).isEqualTo("kg");
        assertThat(stage.getOutputUnit()).isEqualTo("盒");
        assertThat(stage.getLossRate()).isNull();
        assertThat(stage.getOperatorName()).as("raw inputs must never be shown as an operator").isNull();
    }

    @Test
    @DisplayName("process-sheet unit inference fails closed when any source lacks a unit")
    void getFullTrace_processSheetUnitInferenceRequiresEverySourceUnit() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9444L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("UNIT-INFER-TRACE");
        batch.setProductionPlanId("plan-unit-infer");
        batch.setProductTypeId("sku-final");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "UNIT-INFER-TRACE"))
                .thenReturn(Optional.of(batch));
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        when(materialConsumptionRepository.findByProductionBatchId(9444L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(9444L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(9444L)).thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY, "UNIT-INFER-TRACE"))
                .thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY, "plan-unit-infer")).thenReturn(List.of(
                processRow(42L, 1, "mixed", null, """
                        {
                          "processName":"mixed",
                          "productTypeId":"sku-final",
                          "inputQuantity":10,
                          "outputQuantity":10,
                          "outputUnit":"kg",
                          "rawMaterialInputs":[
                            {"materialBatchId":"raw-uuid-1","quantity":4,"unit":"kg"},
                            {"materialBatchId":"raw-uuid-2","quantity":2}
                          ]
                        }
                        """)));
        when(labelRepository.findByProductionBatchIdAndDeletedAtIsNull(9444L)).thenReturn(List.of());

        TraceabilityDTO.ProcessingStageInfo stage = service.getFullTrace(FACTORY, "UNIT-INFER-TRACE")
                .getProcessingStages().get(0);

        assertThat(stage.getInputUnit()).isNull();
        assertThat(stage.getOutputUnit()).isEqualTo("kg");
        assertThat(stage.getLossRate()).isNull();
        assertThat(stage.getOperatorName()).isNull();
    }

    private FinishedGoodsBatch finishedGoodsBatch(String id, String unit) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("FG-TRACE");
        batch.setProductTypeId("sku-final");
        batch.setProductionPlanId("plan-sales");
        batch.setUnit(unit);
        return batch;
    }

    private SalesDeliveryItem deliveryItem(Long id, String deliveryId, String finishedGoodsBatchId,
                                           String productTypeId, String unit) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(id);
        item.setDeliveryRecordId(deliveryId);
        item.setFinishedGoodsBatchId(finishedGoodsBatchId);
        item.setProductTypeId(productTypeId);
        item.setUnit(unit);
        return item;
    }

    private SalesDeliveryItemBatchAllocation allocation(String id, String deliveryItemId,
                                                         String finishedGoodsBatchId, String quantity, String unit) {
        SalesDeliveryItemBatchAllocation allocation = new SalesDeliveryItemBatchAllocation();
        allocation.setId(id);
        allocation.setFactoryId(FACTORY);
        allocation.setDeliveryItemId(deliveryItemId);
        allocation.setFinishedGoodsBatchId(finishedGoodsBatchId);
        allocation.setAllocatedQty(new BigDecimal(quantity));
        allocation.setUnit(unit);
        return allocation;
    }

    private SalesDeliveryRecord delivery(String id, String number, SalesDeliveryStatus status) {
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId(id);
        delivery.setFactoryId(FACTORY);
        delivery.setDeliveryNumber(number);
        delivery.setCustomerId("customer-1");
        delivery.setDeliveryDate(java.time.LocalDate.of(2026, 10, 15));
        delivery.setStatus(status);
        return delivery;
    }

    @Test
    void fullTrace_keepsProductionCountsSeparateFromInspectionSampling() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(8L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BOX-QC");
        batch.setActualQuantity(new BigDecimal("22"));
        batch.setGoodQuantity(new BigDecimal("20"));
        batch.setDefectQuantity(new BigDecimal("2"));
        batch.setUnit("盒");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "BOX-QC"))
                .thenReturn(Optional.of(batch));
        QualityInspection inspection = new QualityInspection();
        inspection.setId("qc-sample");
        inspection.setInspectorId(93001L);
        inspection.setSampleSize(new BigDecimal("5"));
        inspection.setPassCount(new BigDecimal("5"));
        inspection.setFailCount(BigDecimal.ZERO);
        inspection.setPassRate(new BigDecimal("100"));
        inspection.setResult("PASS");
        when(qualityInspectionRepository.findByProductionBatchId(8L)).thenReturn(List.of(inspection));

        var trace = service.getFullTrace(FACTORY, "BOX-QC");
        assertThat(trace.getProduction().getBatchId()).isEqualTo(8L);
        assertThat(trace.getProduction().getQuantity()).isEqualTo(22);
        assertThat(trace.getProduction().getGoodQuantity()).isEqualTo(20);
        assertThat(trace.getProduction().getDefectQuantity()).isEqualTo(2);
        var qi = trace.getQualityInspections().getFirst();
        assertThat(qi.getSampleSize()).isEqualTo(5);
        assertThat(qi.getPassCount()).isEqualTo(5);
        assertThat(qi.getFailCount()).isZero();
        assertThat(qi.getPassRate()).isEqualTo(100);
        assertThat(qi.getInspectorName()).isNull();
        assertThat(qi.getInspectorId()).isEqualTo(93001L);
        assertThat(qi.getInspectionDate()).isNull();
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
        assertThat(response.getSharedInputSourceBatchNumber()).isNull();
    }
}

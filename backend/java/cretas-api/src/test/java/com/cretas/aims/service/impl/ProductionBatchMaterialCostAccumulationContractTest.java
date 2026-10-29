package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.WageRecordTriggerService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Material cost is the signed sum of persisted consumption amounts in the
 * current factory scope.  These tests exercise the shared seam and the
 * AUTO_BOM service wiring; they do not duplicate implementation source text.
 */
@ExtendWith(MockitoExtension.class)
class ProductionBatchMaterialCostAccumulationContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 11017L;

    @Mock private ProductionBatchRepository processingBatchRepository;
    @Mock private MaterialBatchRepository processingMaterialBatchRepository;
    @Mock private MaterialConsumptionRepository processingConsumptionRepository;
    @Mock private QualityInspectionRepository processingQualityInspectionRepository;
    @Mock private EquipmentRepository processingEquipmentRepository;
    @Mock private EquipmentAlertRepository processingEquipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository processingBatchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository processingProductionPlanRepository;
    @Mock private SystemLogRepository processingSystemLogRepository;
    @Mock private RawMaterialTypeRepository processingRawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository processingBatchWorkSessionRepository;
    @Mock private UserRepository processingUserRepository;
    @Mock private SupplierRepository processingSupplierRepository;
    @Mock private ProductTypeRepository processingProductTypeRepository;
    @Mock private AIAnalysisService processingAiAnalysisService;
    @Mock private CacheService processingCacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private org.springframework.context.ApplicationEventPublisher processingEventPublisher;
    @Mock private QualityInspectionService processingQualityInspectionService;
    @Mock private ProductionAlertRepository processingProductionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository processingPlanBatchUsageRepository;
    @Mock private ProcessSheetRowRepository processingProcessSheetRowRepository;
    @Mock private WageRecordTriggerService processingWageRecordTriggerService;
    @InjectMocks private ProcessingServiceImpl processingService;

    @Test
    @DisplayName("manual raw 10 plus automatic packaging .30 persists 10.30")
    void sumsManualAndAutomaticPersistedCosts() {
        assertMoney("10.30", rows("10.00", "0.30"));
    }

    @Test
    @DisplayName("repeated manual requests accumulate rather than overwrite")
    void repeatedManualRowsAccumulate() {
        assertMoney("10.50", rows("4.00", "6.50"));
    }

    @Test
    @DisplayName("signed negative adjustment remains part of the ledger total")
    void signedAdjustmentIsIncluded() {
        assertMoney("8.00", rows("10.00", "-2.00"));
    }

    @Test
    @DisplayName("no actual rows is a valid zero cost")
    void zeroActualRowsAreZero() {
        assertMoney("0", List.of());
    }

    @Test
    @DisplayName("unexpected null persisted cost fails closed")
    void nullPersistedCostFailsClosed() {
        MaterialConsumption row = new MaterialConsumption();
        row.setTotalCost(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> MaterialConsumptionCostTotals.sum(List.of(row)));

        assertEquals(500, error.getCode());
    }

    @Test
    @DisplayName("cost read uses the factory-scoped repository method")
    void usesTenantScopedRepositoryMethod() {
        MaterialConsumptionRepository repository = mock(MaterialConsumptionRepository.class);
        when(repository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(rows("10.00", "0.30"));

        assertEquals(0, new BigDecimal("10.30").compareTo(
                MaterialConsumptionCostTotals.forBatch(repository, BATCH_ID, FACTORY)));

        verify(repository).findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY);
        verify(repository, never()).findByProductionBatchId(BATCH_ID);
        verify(repository, never()).calculateTotalCostByProductionBatch(BATCH_ID);
    }

    @Test
    @DisplayName("explicit recalculate uses persisted signed totalCost in the tenant scope")
    void explicitRecalculateUsesCanonicalPersistedCost() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        when(processingBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(java.util.Optional.of(batch));
        when(processingConsumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(rows("10.00", "0.30", "-2.00"));
        when(processingBatchEquipmentUsageRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());
        when(processingBatchWorkSessionRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());
        when(processingBatchRepository.save(any(ProductionBatch.class))).thenReturn(batch);

        processingService.recalculateBatchCost(FACTORY, BATCH_ID.toString());

        assertEquals(0, new BigDecimal("8.30").compareTo(batch.getMaterialCost()));
        verify(processingConsumptionRepository)
                .findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY);
        verify(processingConsumptionRepository, never()).findByProductionBatchId(BATCH_ID);
        verify(processingConsumptionRepository, never()).calculateTotalCostByProductionBatch(BATCH_ID);
    }

    @Test
    @DisplayName("AUTO_BOM writer persists the canonical accumulated batch cost")
    void autoBomWriterUsesCanonicalAccumulatedCost() {
        BomExpansionService bom = mock(BomExpansionService.class);
        MaterialBatchService materialBatchService = mock(MaterialBatchService.class);
        MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
        MaterialConsumptionRepository consumptionRepository = mock(MaterialConsumptionRepository.class);
        ProductionBatchRepository productionBatchRepository = mock(ProductionBatchRepository.class);
        RawMaterialTypeRepository rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
        WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
        BatchConsumptionServiceImpl service = new BatchConsumptionServiceImpl(
                bom, materialBatchService, materialBatchRepository, consumptionRepository,
                productionBatchRepository, rawMaterialTypeRepository, warehouseResolver);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId("PT-1");
        batch.setActualQuantity(BigDecimal.ONE);
        batch.setSupervisorId(1L);

        MaterialRequirement requirement = new MaterialRequirement();
        requirement.setMaterialTypeId("MAT-PACK");
        requirement.setRequiredQuantity(new BigDecimal("0.30"));
        MaterialAllocation allocation = new MaterialAllocation();
        allocation.setMaterialTypeId("MAT-PACK");
        allocation.setMaterialBatchId("MB-PACK");
        allocation.setAllocatedQuantity(new BigDecimal("1"));
        MaterialCheckResult check = new MaterialCheckResult();
        check.setAllocations(List.of(allocation));
        check.setShortfalls(List.of());

        MaterialBatch materialBatch = new MaterialBatch();
        materialBatch.setUnitPrice(new BigDecimal("0.30"));
        when(consumptionRepository.findByProductionBatchIdAndSourceType(BATCH_ID, "AUTO_BOM"))
                .thenReturn(List.of());
        when(bom.expandBOM(eq(FACTORY), eq("PT-1"), any(BigDecimal.class)))
                .thenReturn(List.of(requirement));
        when(bom.checkMaterialAvailability(eq(FACTORY), any())).thenReturn(check);
        when(materialBatchRepository.findById("MB-PACK")).thenReturn(java.util.Optional.of(materialBatch));
        when(consumptionRepository.save(any(MaterialConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Existing manual raw-material row plus the row just saved by AUTO_BOM.
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(rows("10.00", "0.30"));

        service.autoConsumeForBatch(batch);

        assertEquals(0, new BigDecimal("10.30").compareTo(batch.getMaterialCost()));
        verify(consumptionRepository).findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY);
    }

    @Test
    @DisplayName("写入后 canonical read 为空时不得用内存成本或零值伪造批次成本")
    void autoBomFailsClosedWhenPersistedReadIsEmptyAfterWrite() {
        BomExpansionService bom = mock(BomExpansionService.class);
        MaterialBatchService materialBatchService = mock(MaterialBatchService.class);
        MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
        MaterialConsumptionRepository consumptionRepository = mock(MaterialConsumptionRepository.class);
        ProductionBatchRepository productionBatchRepository = mock(ProductionBatchRepository.class);
        RawMaterialTypeRepository rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
        WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
        BatchConsumptionServiceImpl service = new BatchConsumptionServiceImpl(
                bom, materialBatchService, materialBatchRepository, consumptionRepository,
                productionBatchRepository, rawMaterialTypeRepository, warehouseResolver);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId("PT-1");
        batch.setActualQuantity(BigDecimal.ONE);
        batch.setSupervisorId(1L);

        MaterialRequirement requirement = new MaterialRequirement();
        requirement.setMaterialTypeId("MAT-PACK");
        requirement.setRequiredQuantity(new BigDecimal("0.30"));
        MaterialAllocation allocation = new MaterialAllocation();
        allocation.setMaterialTypeId("MAT-PACK");
        allocation.setMaterialBatchId("MB-PACK");
        allocation.setAllocatedQuantity(BigDecimal.ONE);
        MaterialCheckResult check = new MaterialCheckResult();
        check.setAllocations(List.of(allocation));
        check.setShortfalls(List.of());

        MaterialBatch materialBatch = new MaterialBatch();
        materialBatch.setUnitPrice(new BigDecimal("0.30"));
        when(consumptionRepository.findByProductionBatchIdAndSourceType(BATCH_ID, "AUTO_BOM"))
                .thenReturn(List.of());
        when(bom.expandBOM(eq(FACTORY), eq("PT-1"), any(BigDecimal.class)))
                .thenReturn(List.of(requirement));
        when(bom.checkMaterialAvailability(eq(FACTORY), any())).thenReturn(check);
        when(materialBatchRepository.findById("MB-PACK"))
                .thenReturn(java.util.Optional.of(materialBatch));
        when(consumptionRepository.save(any(MaterialConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.autoConsumeForBatch(batch));

        assertEquals(500, error.getCode());
        verify(productionBatchRepository, never()).save(any(ProductionBatch.class));
    }

    private static List<MaterialConsumption> rows(String... costs) {
        return java.util.Arrays.stream(costs).map(cost -> {
            MaterialConsumption row = new MaterialConsumption();
            row.setTotalCost(new BigDecimal(cost));
            return row;
        }).toList();
    }

    private static void assertMoney(String expected, List<MaterialConsumption> rows) {
        assertEquals(0, new BigDecimal(expected).compareTo(MaterialConsumptionCostTotals.sum(rows)));
    }
}

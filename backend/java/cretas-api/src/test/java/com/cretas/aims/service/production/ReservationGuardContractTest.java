package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract coverage for the report reservation write guard.
 * These tests call the service implementation so a source-text-only guard
 * cannot satisfy the contract.
 */
class ReservationGuardContractTest {

    private static final String FACTORY = "F006";
    private static final String WORKSHOP_WAREHOUSE = "WH-WKS-ID";

    @Test
    void validDuplicateReferencesAggregateAndSaveOnce() {
        Rig rig = new Rig();
        MaterialBatch batch = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 20);
        rig.stub("batch-a", batch);

        BigDecimal reserved = rig.service.reserveForReport(FACTORY,
                report(ref("batch-a", "2"), ref("batch-a", "3")));

        assertThat(reserved).isEqualByComparingTo("5");
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo("5");
        verify(rig.materialBatchRepository).save(batch);
    }

    @Test
    void missingBatchIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();

        assertGuardFailsWithHint(rig, report(ref("missing", "1")),
                "WORKSHOP_RESERVATION_BATCH_NOT_FOUND_OR_FOREIGN");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void foreignLockedRowIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();
        MaterialBatch foreign = batch("batch-a", "OTHER", WORKSHOP_WAREHOUSE, 20);
        rig.stub("batch-a", foreign);

        assertGuardFails(rig, report(ref("batch-a", "1")),
                "WORKSHOP_RESERVATION_BATCH_IDENTITY_MISMATCH");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void wrongWarehouseIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();
        MaterialBatch batch = batch("batch-a", FACTORY, "WH-LOG-ID", 20);
        rig.stub("batch-a", batch);

        assertGuardFailsWithHint(rig, report(ref("batch-a", "1")),
                "WORKSHOP_RESERVATION_WAREHOUSE_MISMATCH");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void customerOwnedBatchIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();
        MaterialBatch batch = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 20);
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        rig.stub("batch-a", batch);

        assertGuardFailsWithHint(rig, report(ref("batch-a", "1")),
                "WORKSHOP_RESERVATION_CUSTOMER_OWNED_FORBIDDEN");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void pseudoWipBatchIsBusinessErrorAndDoesNotSave() {
        for (String sourceType : List.of("PRODUCTION_BATCH", "production_batch", "Production_Batch")) {
            Rig rig = new Rig();
            MaterialBatch batch = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 20);
            batch.setSourceDocType(sourceType);
            rig.stub("batch-a", batch);

            assertGuardFailsWithHint(rig, report(ref("batch-a", "1")),
                    "WORKSHOP_RESERVATION_WIP_FORBIDDEN");

            verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
        }
    }

    @Test
    void zeroAvailableBatchIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();
        MaterialBatch batch = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 0);
        rig.stub("batch-a", batch);

        assertGuardFailsWithHint(rig, report(ref("batch-a", "1")),
                "BATCH_OVER_CONSUMED");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void overQuantityIsBusinessErrorAndDoesNotSave() {
        Rig rig = new Rig();
        MaterialBatch batch = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 5);
        rig.stub("batch-a", batch);

        assertGuardFailsWithHint(rig, report(ref("batch-a", "6")),
                "BATCH_OVER_CONSUMED");

        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void secondInvalidRowLeavesFirstRowUntouchedAndLocksInIdOrder() {
        Rig rig = new Rig();
        MaterialBatch first = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 20);
        MaterialBatch second = batch("batch-b", FACTORY, WORKSHOP_WAREHOUSE, 20);
        second.setStatus(MaterialBatchStatus.FROZEN);
        rig.stub("batch-a", first);
        rig.stub("batch-b", second);

        assertGuardFails(rig, report(ref("batch-b", "1"), ref("batch-a", "1")),
                "WORKSHOP_RESERVATION_BATCH_NOT_AVAILABLE");

        assertThat(first.getReservedQuantity()).isZero();
        assertThat(second.getReservedQuantity()).isZero();
        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
        InOrder order = inOrder(rig.materialBatchRepository, rig.entityManager);
        order.verify(rig.materialBatchRepository)
                .findByIdAndFactoryId("batch-a", FACTORY);
        order.verify(rig.entityManager)
                .refresh(first, LockModeType.PESSIMISTIC_WRITE);
        order.verify(rig.materialBatchRepository)
                .findByIdAndFactoryId("batch-b", FACTORY);
        order.verify(rig.entityManager)
                .refresh(second, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void refreshesFreshDatabaseStateBeforeFinalValidation() {
        Rig rig = new Rig();
        MaterialBatch stale = batch("batch-a", FACTORY, WORKSHOP_WAREHOUSE, 10);
        rig.stub("batch-a", stale);
        doAnswer(invocation -> {
            MaterialBatch refreshed = invocation.getArgument(0);
            refreshed.setReservedQuantity(new BigDecimal("10"));
            return null;
        }).when(rig.entityManager).refresh(any(MaterialBatch.class), eq(LockModeType.PESSIMISTIC_WRITE));

        assertGuardFails(rig, report(ref("batch-a", "1")), "BATCH_OVER_CONSUMED");

        assertThat(stale.getReservedQuantity()).isEqualByComparingTo("10");
        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void nonPositiveReferenceIsBusinessErrorBeforeLockingRows() {
        Rig rig = new Rig();

        assertGuardFails(rig, report(ref("batch-a", "0")),
                "WORKSHOP_RESERVATION_REFERENCE_INVALID");

        verify(rig.materialBatchRepository, never()).findByIdAndFactoryId(any(), any());
        verify(rig.materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    private static void assertGuardFails(Rig rig, ProductionReport report, String errorCode) {
        assertThatThrownBy(() -> rig.service.reserveForReport(FACTORY, report))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException error = (BusinessException) thrown;
                    assertThat(error.getErrorCode()).isEqualTo(errorCode);
                    assertThat(error.getActionHint()).isNotBlank();
                });
    }

    private static void assertGuardFailsWithHint(Rig rig, ProductionReport report,
                                                  String errorCode) {
        assertThatThrownBy(() -> rig.service.reserveForReport(FACTORY, report))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException error = (BusinessException) thrown;
                    assertThat(error.getErrorCode()).isEqualTo(errorCode);
                    assertThat(error.getActionHint()).isNotBlank();
                    String hint = error.getActionHint();
                    if ("WORKSHOP_RESERVATION_WAREHOUSE_MISMATCH".equals(errorCode)) {
                        assertThat(hint).contains("正规领料");
                    } else if ("WORKSHOP_RESERVATION_CUSTOMER_OWNED_FORBIDDEN".equals(errorCode)) {
                        assertThat(hint).contains("客供");
                    } else if ("WORKSHOP_RESERVATION_WIP_FORBIDDEN".equals(errorCode)) {
                        assertThat(hint).contains("半成品");
                    } else if ("BATCH_OVER_CONSUMED".equals(errorCode)) {
                        assertThat(hint).contains("库存不足");
                    } else {
                        assertThat(hint).contains("重新选择当前工厂");
                    }
                });
    }

    private static MaterialBatch batch(String id, String factoryId, String warehouseId, int receipt) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setReceiptQuantity(BigDecimal.valueOf(receipt));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setWarehouseId(warehouseId);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        return batch;
    }

    @SafeVarargs
    private static ProductionReport report(Map<String, Object>... refs) {
        ProductionReport report = new ProductionReport();
        report.setFactoryId(FACTORY);
        report.setMaterialBatchRefs(new ArrayList<>(List.of(refs)));
        return report;
    }

    private static Map<String, Object> ref(String batchId, String quantity) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("materialBatchId", batchId);
        ref.put("quantity", quantity);
        ref.put("unit", "kg");
        return ref;
    }

    private static final class Rig {
        final MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
        final WorkshopReportReservationService service;

        Rig() {
            when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn(WORKSHOP_WAREHOUSE);
            when(materialBatchRepository.save(any(MaterialBatch.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            service = new WorkshopReportReservationService(
                    materialBatchRepository,
                    mock(ProductionBatchRepository.class),
                    mock(ProductionReportRepository.class),
                    warehouseResolver,
                    entityManager);
        }

        void stub(String id, MaterialBatch batch) {
            when(materialBatchRepository.findByIdAndFactoryId(id, FACTORY))
                    .thenReturn(Optional.of(batch));
        }
    }
}

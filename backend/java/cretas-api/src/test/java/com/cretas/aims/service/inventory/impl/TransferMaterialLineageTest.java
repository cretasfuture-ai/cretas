package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferMaterialLineageTest {

    private static final String FACTORY = "F006";
    private static final String MATERIAL_TYPE = "MT-BEEF";

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private InventoryLowStockEventPublisher lowStockEventPublisher;
    @Mock private LineageEdgeRecorder lineageEdgeRecorder;
    @Mock private UnitContractService unitContractService;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(transferRepository, transferItemRepository,
                materialBatchRepository, finishedGoodsBatchRepository, null, materialBatchService,
                rawMaterialTypeRepository);
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", materialConsumptionRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", lowStockEventPublisher);
        ReflectionTestUtils.setField(service, "lineageEdgeRecorder", lineageEdgeRecorder);
    }

    @Test
    void sameFactoryFefoAllocationsCreateEdgesAfterTargetAndKeepNativeUnitAndSupplier() {
        MaterialBatch first = batch("MB-1", "SUP-1", "25");
        MaterialBatch second = batch("MB-2", "SUP-1", "30");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        InternalTransferItem item = transfer.getItems().get(0);
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(first, second));
        stubSameFactoryConfirmationPersistence();

        List<Object[]> calls = new ArrayList<>();
        doAnswer(invocation -> {
            calls.add(invocation.getArguments());
            return null;
        }).when(lineageEdgeRecorder).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), eq(99L), anyString(), any());

        service.confirmTransfer(FACTORY, transfer.getId(), 99L);

        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(args -> args[0]).containsOnly(FACTORY);
        assertThat(calls).extracting(args -> args[1]).containsExactly("MB-1", "MB-2");
        assertThat(calls).extracting(args -> args[3])
                .containsExactly(new BigDecimal("25"), new BigDecimal("15"));
        assertThat(calls).extracting(args -> args[4]).containsOnly("kg");
        assertThat(calls).extracting(args -> args[6])
                .containsExactly("TR-LINEAGE-1:41:MB-1", "TR-LINEAGE-1:41:MB-2");
        assertThat(calls.get(0)[2]).isEqualTo(calls.get(1)[2]);

        org.mockito.ArgumentCaptor<MaterialBatch> targetCaptor =
                org.mockito.ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(targetCaptor.capture());
        MaterialBatch target = targetCaptor.getValue();
        assertThat(target.getReceiptQuantity()).isEqualByComparingTo("40");
        assertThat(target.getQuantityUnit()).isEqualTo("kg");
        assertThat(target.getSupplierId())
                .as("a single source supplier may be preserved through the original source batches")
                .isEqualTo("SUP-1");
        assertThat(target.getSourceDocId()).isEqualTo("TR-LINEAGE-1");

        InOrder order = inOrder(materialBatchRepository, lineageEdgeRecorder);
        order.verify(materialBatchRepository).save(any(MaterialBatch.class));
        order.verify(lineageEdgeRecorder, times(2)).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), eq(99L), anyString(), any());
    }

    @Test
    void mixedSourceSuppliersDoNotCopyAnArbitrarySupplierToTarget() {
        MaterialBatch first = batch("MB-1", "SUP-1", "25");
        MaterialBatch second = batch("MB-2", "SUP-2", "30");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(first, second));
        stubSameFactoryConfirmationPersistence();

        service.confirmTransfer(FACTORY, transfer.getId(), 99L);

        org.mockito.ArgumentCaptor<MaterialBatch> targetCaptor =
                org.mockito.ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(targetCaptor.capture());
        assertThat(targetCaptor.getValue().getSupplierId())
                .as("mixed source batches must not inherit whichever supplier happened to be first")
                .isNull();
    }

    @Test
    void fefoSkipsEarlierDifferentUnitAndDeductsOnlyMatchingNativeStock() {
        MaterialBatch wrongUnit = batch("MB-BOX", "SUP-1", "100");
        wrongUnit.setQuantityUnit("box");
        MaterialBatch matchingUnit = batch("MB-KG", "SUP-1", "40");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(wrongUnit, matchingUnit));
        stubSameFactoryConfirmationPersistence();

        service.confirmTransfer(FACTORY, transfer.getId(), 99L);

        assertThat(wrongUnit.getUsedQuantity()).isZero();
        assertThat(matchingUnit.getUsedQuantity()).isEqualByComparingTo("40");
        verify(materialBatchRepository, never()).saveAndFlush(wrongUnit);
        verify(materialBatchRepository).saveAndFlush(matchingUnit);
        verify(lineageEdgeRecorder).recordMaterialTransfer(
                eq(FACTORY), eq("MB-KG"), anyString(), eq(new BigDecimal("40")), eq("kg"),
                eq(99L), anyString(), any());
    }

    @Test
    void equivalentNativeUnitAliasIsEligibleButEdgeUsesImmutableNativeUnit() {
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        when(unitContractService.describe(eq(FACTORY), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1, String.class);
            if ("kg".equals(raw) || "公斤".equals(raw)) {
                return Optional.of(new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE,
                        "千克", 3));
            }
            return Optional.empty();
        });
        MaterialBatch aliasUnit = batch("MB-ALIAS", "SUP-1", "40");
        aliasUnit.setQuantityUnit("公斤");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(aliasUnit));
        stubSameFactoryConfirmationPersistence();

        service.confirmTransfer(FACTORY, transfer.getId(), 99L);

        verify(materialBatchRepository).saveAndFlush(aliasUnit);
        verify(lineageEdgeRecorder).recordMaterialTransfer(
                eq(FACTORY), eq("MB-ALIAS"), anyString(), eq(new BigDecimal("40")), eq("kg"),
                eq(99L), anyString(), any());
    }

    @Test
    void preselectedDifferentUnitIsRejectedBeforeAnyDeduction() {
        MaterialBatch wrongUnit = batch("MB-BOX", "SUP-1", "40");
        wrongUnit.setQuantityUnit("box");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        transfer.getItems().get(0).setSourceBatchId("MB-BOX");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(wrongUnit));

        assertThatThrownBy(() -> service.confirmTransfer(FACTORY, transfer.getId(), 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("指定的原料批次 MB-BOX 不可用");

        assertThat(wrongUnit.getUsedQuantity()).isZero();
        verify(materialBatchRepository, never()).saveAndFlush(any(MaterialBatch.class));
        verify(lineageEdgeRecorder, never()).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    void failedDeductionDoesNotCreateTargetOrLineageEdges() {
        MaterialBatch only = batch("MB-1", "SUP-1", "10");
        MaterialBatch wrongUnit = batch("MB-BOX", "SUP-1", "100");
        wrongUnit.setQuantityUnit("box");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(wrongUnit, only));
        assertThatThrownBy(() -> service.confirmTransfer(FACTORY, transfer.getId(), 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原料库存不足");

        assertThat(only.getUsedQuantity()).isZero();
        assertThat(wrongUnit.getUsedQuantity()).isZero();
        verify(materialBatchRepository, never()).saveAndFlush(any(MaterialBatch.class));
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
        verify(lineageEdgeRecorder, never()).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    void failedTargetCreationDoesNotCreateLineageEdges() {
        MaterialBatch source = batch("MB-1", "SUP-1", "40");
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.APPROVED, "F006", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                FACTORY, MATERIAL_TYPE, "WH-RAW")).thenReturn(List.of(source));
        when(transferItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("target insert failed"))
                .when(materialBatchRepository).save(any(MaterialBatch.class));

        assertThatThrownBy(() -> service.confirmTransfer(FACTORY, transfer.getId(), 99L))
                .isInstanceOf(IllegalStateException.class);
        verify(lineageEdgeRecorder, never()).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    void crossFactoryConfirmationDoesNotRecordMaterialTransferEdges() {
        InternalTransfer transfer = sameFactoryTransfer(TransferStatus.RECEIVED, "F007", "40");
        when(transferRepository.findByIdAndEitherFactoryId("TR-LINEAGE-1", "F007"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialTypeRepository.findById(anyString())).thenReturn(Optional.empty());

        service.confirmTransfer("F007", transfer.getId(), 99L);

        verify(lineageEdgeRecorder, never()).recordMaterialTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
    }

    private void stubSameFactoryConfirmationPersistence() {
        when(transferItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialTypeRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private static MaterialBatch batch(String id, String supplierId, String receipt) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setBatchNumber("BN-" + id);
        batch.setFactoryId(FACTORY);
        batch.setWarehouseId("WH-RAW");
        batch.setMaterialTypeId(MATERIAL_TYPE);
        batch.setReceiptQuantity(new BigDecimal(receipt));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setSupplierId(supplierId);
        batch.setUnitPrice(new BigDecimal("10.00"));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    private static InternalTransfer sameFactoryTransfer(TransferStatus status,
                                                         String targetFactory, String quantity) {
        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TR-LINEAGE-1");
        transfer.setTransferNumber("TRF-LINEAGE-1");
        transfer.setSourceFactoryId(FACTORY);
        transfer.setTargetFactoryId(targetFactory);
        transfer.setSourceWarehouseId("WH-RAW");
        transfer.setTargetWarehouseId("WH-WKS");
        transfer.setTransferType(TransferType.WAREHOUSE_TO_WAREHOUSE);
        transfer.setStatus(status);

        InternalTransferItem item = new InternalTransferItem();
        item.setId(41L);
        item.setTransferId(transfer.getId());
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId(MATERIAL_TYPE);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("bag");
        item.setPackageUnitSnapshot("bag");
        item.setInventoryBaseUnitSnapshot("kg");
        transfer.setItems(new ArrayList<>(List.of(item)));
        return transfer;
    }
}

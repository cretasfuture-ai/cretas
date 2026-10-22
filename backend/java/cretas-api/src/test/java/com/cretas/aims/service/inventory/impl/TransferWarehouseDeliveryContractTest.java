package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.WarehouseTransferReceiptRequest;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferWarehouseDeliveryContractTest {
    @Mock InternalTransferRepository transfers; @Mock InternalTransferItemRepository items;
    @Mock MaterialBatchRepository batches; @Mock FinishedGoodsBatchRepository finished;
    @Mock ApplicationEventPublisher events; @Mock MaterialBatchService batchService;
    @Mock RawMaterialTypeRepository materialTypes; @Mock WarehouseTransferMovementRepository movements;
    @Mock WarehouseResolver warehouses; @Mock EntityManager entityManager; @Mock Query lockQuery, warehouseQuery;
    @Mock MaterialConsumptionRepository consumptions; @Mock LineageEdgeRecorder lineage;
    TransferServiceImpl service; InternalTransfer transfer; InternalTransferItem item;
    MaterialBatch source;
    List<WarehouseTransferMovement> history;

    @BeforeEach void setUp() {
        service = new TransferServiceImpl(transfers, items, batches, finished, events, batchService, materialTypes);
        ReflectionTestUtils.setField(service, "warehouseMovementRepository", movements);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouses);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", consumptions);
        ReflectionTestUtils.setField(service, "lineageEdgeRecorder", lineage);
        when(entityManager.createNativeQuery(startsWith("select id from internal_transfers"))).thenReturn(lockQuery);
        when(entityManager.createNativeQuery(startsWith("select id from factory_warehouses"))).thenReturn(warehouseQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(warehouseQuery.setParameter(anyString(), any())).thenReturn(warehouseQuery);
        when(lockQuery.getResultList()).thenReturn(List.of("T1"));
        when(warehouseQuery.getResultList()).thenReturn(List.of("W1", "W2"));
        transfer = new InternalTransfer(); transfer.setId("T1"); transfer.setTransferNumber("TRF-1");
        transfer.setSourceFactoryId("F1"); transfer.setTargetFactoryId("F1");
        transfer.setSourceWarehouseId("W1"); transfer.setTargetWarehouseId("W2"); transfer.setStatus(TransferStatus.SHIPPED);
        item = new InternalTransferItem(); item.setId(7L); item.setTransferId("T1"); item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId("M1"); item.setItemName("raw"); item.setQuantity(new BigDecimal("0.10")); item.setUnit("kg");
        item.setSourceBatchId("SB1"); item.setReceivedQuantity(BigDecimal.ZERO); transfer.setItems(new ArrayList<>(List.of(item)));
        when(transfers.findByIdAndEitherFactoryId("T1", "F1")).thenReturn(Optional.of(transfer));
        history = new ArrayList<>();
        var dispatched = new WarehouseTransferMovement(); dispatched.setFactoryId("F1"); dispatched.setTransferId("T1");
        dispatched.setTransferItemId(7L); dispatched.setKind(WarehouseTransferMovement.Kind.DISPATCH);
        dispatched.setQuantity(new BigDecimal("0.10")); dispatched.setUnit("kg"); dispatched.setSourceBatchId("SB1");
        history.add(dispatched);
        when(movements.findTransferHistoryIncludingDeleted("T1")).thenAnswer(inv -> new ArrayList<>(history));
        when(movements.findRequestHistoryIncludingDeleted(anyString(), eq("F1"), eq("T1")))
                .thenAnswer(inv -> history.stream().filter(m -> Objects.equals(m.getRequestKey(), inv.getArgument(0))).toList());
        when(movements.save(any())).thenAnswer(inv -> { history.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(warehouses.resolveLogisticsId("F1")).thenReturn("W2");
        when(transfers.save(transfer)).thenReturn(transfer);
        when(batches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(batches.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialTypes.findById("M1")).thenReturn(Optional.empty());
        source = new MaterialBatch(); source.setId("SB1"); source.setFactoryId("F1"); source.setWarehouseId("W1");
        source.setMaterialTypeId("M1"); source.setQuantityUnit("kg"); source.setOwnership(InventoryOwnership.COMPANY_OWNED);
        source.setStatus(MaterialBatchStatus.AVAILABLE); source.setReceiptQuantity(new BigDecimal("0.10"));
        source.setUsedQuantity(BigDecimal.ZERO); source.setReservedQuantity(BigDecimal.ZERO); source.setSupplierId("SUP1");
        source.setUnitPrice(new BigDecimal("2.50"));
        when(batches.findByIdAndFactoryId("SB1", "F1")).thenReturn(Optional.of(source));
        when(batches.findByIdAndFactoryIdForUpdate("SB1", "F1")).thenReturn(Optional.of(source));
    }

    @Test void partialReceiptsCreateDeltaBatchesAndCumulativeState() {
        service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(7L, new BigDecimal("0.04"))));
        assertEquals(new BigDecimal("0.04"), item.getReceivedQuantity());
        assertEquals(TransferStatus.SHIPPED, transfer.getStatus());
        service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(7L, new BigDecimal("0.06"))));
        assertEquals(new BigDecimal("0.10"), item.getReceivedQuantity());
        assertEquals(TransferStatus.CONFIRMED, transfer.getStatus());
        ArgumentCaptor<MaterialBatch> captured = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batches, times(2)).save(captured.capture());
        List<BigDecimal> targetQuantities = captured.getAllValues().stream()
                .map(MaterialBatch::getReceiptQuantity).toList();
        assertEquals(List.of(new BigDecimal("0.04"), new BigDecimal("0.06")), targetQuantities);
        assertEquals(2, captured.getAllValues().stream().map(MaterialBatch::getId).distinct().count());
        assertEquals(List.of("SUP1", "SUP1"), captured.getAllValues().stream().map(MaterialBatch::getSupplierId).toList());
        verify(lineage, times(2)).recordMaterialTransfer(eq("F1"), eq("SB1"), anyString(), any(), eq("kg"), eq(9L), anyString(), isNull());
        verify(events, times(1)).publishEvent(any(com.cretas.aims.event.TransferConfirmedEvent.class));
    }

    @Test void emptyReceiptHasNoMutation() {
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of())));
        verify(batches, never()).save(any()); verify(movements, never()).save(any());
    }

    @Test void dispatchDebitsExactBatchAndRecordsConsumptionBeforeAnyReceipt() {
        history.clear(); transfer.setStatus(TransferStatus.APPROVED);
        service.dispatchWarehouseTransfer("F1", "T1", 9L);
        assertEquals(new BigDecimal("0.10"), source.getUsedQuantity());
        assertEquals(MaterialBatchStatus.DEPLETED, source.getStatus());
        assertEquals(TransferStatus.SHIPPED, transfer.getStatus());
        assertEquals(1, history.size()); assertEquals(WarehouseTransferMovement.Kind.DISPATCH, history.get(0).getKind());
        assertEquals(new BigDecimal("2.5000"), item.getUnitPrice());
        verify(consumptions).save(argThat(c -> "TRANSFER_OUT".equals(c.getSourceType()) && "SB1".equals(c.getBatchId())
                && new BigDecimal("0.10").compareTo(c.getQuantity()) == 0));
        verify(batches, never()).save(any()); verifyNoInteractions(events);
    }

    @Test void packagingAndFinishedGoodsRemainOutsideStagedDeliveryWithoutWrites() {
        var dispatched = history.get(0);
        for (TransferItemType type : List.of(TransferItemType.PACKAGING_MATERIAL, TransferItemType.FINISHED_GOODS)) {
            history.clear(); history.add(dispatched); transfer.setStatus(TransferStatus.SHIPPED);
            item.setItemType(type);
            assertFalse(service.getWarehouseDelivery("F1", "T1").isSupported());
            assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L, request("0.04")));
            history.clear(); transfer.setStatus(TransferStatus.APPROVED);
            assertThrows(RuntimeException.class, () -> service.dispatchWarehouseTransfer("F1", "T1", 9L));
        }
        verify(batches, never()).save(any()); verify(batches, never()).saveAndFlush(any());
        verify(consumptions, never()).save(any()); verify(movements, never()).save(any());
        assertEquals(BigDecimal.ZERO, source.getUsedQuantity());
        assertEquals(BigDecimal.ZERO, item.getReceivedQuantity());
    }
    @Test void dispatchRejectsCombinedDemandBeforeAnyWrite() {
        history.clear(); transfer.setStatus(TransferStatus.APPROVED);
        var second = new InternalTransferItem(); second.setId(8L); second.setItemType(TransferItemType.RAW_MATERIAL);
        second.setMaterialTypeId("M1"); second.setSourceBatchId("SB1"); second.setUnit("kg"); second.setQuantity(new BigDecimal("0.04"));
        transfer.getItems().add(second);
        assertThrows(RuntimeException.class, () -> service.dispatchWarehouseTransfer("F1", "T1", 9L));
        verify(batches, never()).saveAndFlush(any()); verify(consumptions, never()).save(any());
        verify(movements, never()).save(any()); assertEquals(BigDecimal.ZERO, source.getUsedQuantity());
    }

    @Test void missingInactiveWarehouseAndWrongUnitsCannotDispatch() {
        history.clear(); transfer.setStatus(TransferStatus.APPROVED);
        when(warehouseQuery.getResultList()).thenReturn(List.of("W1"));
        assertThrows(RuntimeException.class, () -> service.dispatchWarehouseTransfer("F1", "T1", 9L));
        when(warehouseQuery.getResultList()).thenReturn(List.of("W1", "W2")); source.setQuantityUnit("box");
        assertThrows(RuntimeException.class, () -> service.dispatchWarehouseTransfer("F1", "T1", 9L));
        source.setQuantityUnit("kg"); source.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        assertThrows(RuntimeException.class, () -> service.dispatchWarehouseTransfer("F1", "T1", 9L));
        verify(batches, never()).saveAndFlush(any()); verify(movements, never()).save(any());
    }

    @Test void unmappedOrForeignTransferHasNoWrites() {
        when(lockQuery.getResultList()).thenReturn(List.of());
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F2", "T1", 9L, request("0.04")));
        verify(batches, never()).save(any()); verify(movements, never()).save(any());
        verify(transfers, never()).save(any());
    }

    @Test void legacyShippedWithoutDispatchCannotCreateReceipt() {
        history.clear();
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L, request("0.04")));
        verify(batches, never()).save(any()); verify(movements, never()).save(any());
        assertFalse(service.getWarehouseDelivery("F1", "T1").isStaged());
        assertEquals(BigDecimal.ZERO, service.getWarehouseDelivery("F1", "T1").getItems().get(0).getShippedQuantity());
    }

    @Test void receiptBoundaryErrorsAreAtomic() {
        for (String qty : List.of("-0.01", "0", "0.10001", "0.11")) {
            assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L, request(qty)));
        }
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(777L, BigDecimal.ONE))));
        var nullQty = new HashMap<Long, BigDecimal>(); nullQty.put(7L, null);
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), nullQty)));
        verify(batches, never()).save(any()); verify(movements, never()).save(any());
        assertEquals(BigDecimal.ZERO, item.getReceivedQuantity());
    }

    @Test void duplicateIncludingTombstoneCannotWriteOrChangeOriginalReceipt() {
        var request = request("0.04");
        service.receiveWarehouseTransfer("F1", "T1", 9L, request);
        history.get(1).setDeletedAt(java.time.LocalDateTime.now());
        request.setItemQuantities(Map.of(7L, new BigDecimal("0.06")));
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L, request));
        verify(batches, times(1)).save(any()); assertEquals(new BigDecimal("0.04"), item.getReceivedQuantity());
        assertEquals(new BigDecimal("0.06"), service.getWarehouseDelivery("F1", "T1").getItems().get(0).getPendingQuantity());
    }

    private WarehouseTransferReceiptRequest request(String quantity) {
        return new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(7L, new BigDecimal(quantity)));
    }
}

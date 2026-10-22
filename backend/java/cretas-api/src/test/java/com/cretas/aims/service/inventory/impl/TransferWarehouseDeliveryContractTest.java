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
import jakarta.persistence.TypedQuery;
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
    @Mock WarehouseResolver warehouses; @Mock EntityManager entityManager; @Mock TypedQuery<InternalTransfer> query;
    TransferServiceImpl service; InternalTransfer transfer; InternalTransferItem item;

    @BeforeEach void setUp() {
        service = new TransferServiceImpl(transfers, items, batches, finished, events, batchService, materialTypes);
        ReflectionTestUtils.setField(service, "warehouseMovementRepository", movements);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouses);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(entityManager.createQuery(anyString(), eq(InternalTransfer.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setLockMode(any())).thenReturn(query);
        transfer = new InternalTransfer(); transfer.setId("T1"); transfer.setTransferNumber("TRF-1");
        transfer.setSourceFactoryId("F1"); transfer.setTargetFactoryId("F1");
        transfer.setSourceWarehouseId("W1"); transfer.setTargetWarehouseId("W2"); transfer.setStatus(TransferStatus.SHIPPED);
        item = new InternalTransferItem(); item.setId(7L); item.setTransferId("T1"); item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId("M1"); item.setItemName("raw"); item.setQuantity(new BigDecimal("0.10")); item.setUnit("kg");
        item.setSourceBatchId("SB1"); item.setReceivedQuantity(BigDecimal.ZERO); transfer.setItems(new ArrayList<>(List.of(item)));
        when(query.getResultStream()).thenAnswer(inv -> java.util.stream.Stream.of(transfer));
        when(movements.findByRequestKeyAndFactoryIdAndTransferId(anyString(), eq("F1"), eq("T1"))).thenReturn(List.of());
        when(movements.findByTransferIdOrderByIdAsc("T1")).thenReturn(List.of());
        when(warehouses.resolveLogisticsId("F1")).thenReturn("W2");
        when(transfers.save(transfer)).thenReturn(transfer);
        when(batches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialTypes.findById("M1")).thenReturn(Optional.empty());
    }

    @Test void partialReceiptsCreateDeltaBatchesAndCumulativeState() {
        service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(7L, new BigDecimal("0.04"))));
        assertEquals(new BigDecimal("0.04"), item.getReceivedQuantity());
        service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(7L, new BigDecimal("0.06"))));
        assertEquals(new BigDecimal("0.10"), item.getReceivedQuantity());
        ArgumentCaptor<MaterialBatch> captured = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batches, times(2)).save(captured.capture());
        List<BigDecimal> targetQuantities = captured.getAllValues().stream()
                .map(MaterialBatch::getReceiptQuantity).toList();
        assertEquals(List.of(new BigDecimal("0.04"), new BigDecimal("0.06")), targetQuantities);
    }

    @Test void emptyReceiptHasNoMutation() {
        assertThrows(RuntimeException.class, () -> service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of())));
        verify(batches, never()).save(any()); verify(movements, never()).save(any());
    }
}

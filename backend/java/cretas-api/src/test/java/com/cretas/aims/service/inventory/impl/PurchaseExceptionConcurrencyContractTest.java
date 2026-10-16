package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.ReturnOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service method contract for the exception decision transaction boundary. */
@ExtendWith(MockitoExtension.class)
class PurchaseExceptionConcurrencyContractTest {
    @Mock PurchaseExceptionRepository exceptionRepository;
    @Mock ReturnOrderService returnOrderService;
    @Mock PurchaseOrderItemRepository itemRepository;
    @Mock PurchaseOrderRepository orderRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;

    @Test
    void decisionReadsThroughFactoryScopedLockAndDoesNotUseUnlockedLookup() {
        PurchaseException exception = pending();
        when(exceptionRepository.findByIdAndFactoryIdForUpdate("EXC-1", "F1"))
                .thenReturn(Optional.of(exception));
        when(exceptionRepository.save(any(PurchaseException.class))).thenAnswer(inv -> inv.getArgument(0));

        service().decideException("EXC-1", "F1", ExceptionDecision.ACCEPT_OVER,
                "accept", 7L, null);

        verify(exceptionRepository).findByIdAndFactoryIdForUpdate("EXC-1", "F1");
        verify(exceptionRepository, never()).findById(anyString());
    }

    @Test
    void unexpectedChildFailureIsPropagatedAndCannotBecomeFalseSuccess() {
        PurchaseException exception = pending();
        when(exceptionRepository.findByIdAndFactoryIdForUpdate("EXC-1", "F1"))
                .thenReturn(Optional.of(exception));
        when(exceptionRepository.save(any(PurchaseException.class))).thenAnswer(inv -> inv.getArgument(0));
        sourceLine();
        when(returnOrderService.createReturnOrder(eq("F1"), any(), eq(7L)))
                .thenThrow(new IllegalStateException("child write failed"));

        assertThrows(IllegalStateException.class, () -> service().decideException(
                "EXC-1", "F1", ExceptionDecision.RETURN_OVER, null, 7L, null));
        verify(exceptionRepository, times(1)).save(any(PurchaseException.class));
        verify(returnOrderService).createReturnOrder(eq("F1"), any(), eq(7L));
    }

    private PurchaseExceptionServiceImpl service() {
        return new PurchaseExceptionServiceImpl(exceptionRepository, returnOrderService,
                itemRepository, orderRepository);
    }

    private PurchaseException pending() {
        PurchaseException e = new PurchaseException();
        e.setId("EXC-1"); e.setFactoryId("F1"); e.setExceptionNumber("EX-1");
        e.setReceiveRecordId("REC-1"); e.setPurchaseOrderId("PO-1"); e.setSupplierId("SUP-1");
        e.setMaterialTypeId("MAT-1"); e.setMaterialName("material");
        e.setExceptionType(ReceiveExceptionType.OVER_RECEIVE); e.setPoQuantity(BigDecimal.TEN);
        e.setReceivedQuantity(BigDecimal.valueOf(12)); e.setExceptionQty(BigDecimal.valueOf(2));
        e.setUnit("kg"); e.setStatus("PENDING"); e.setCreatedBy(7L); e.setOwnerUserId(7L);
        return e;
    }

    private void sourceLine() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("PO-1"); order.setFactoryId("F1");
        when(orderRepository.findByIdAndFactoryId("PO-1", "F1")).thenReturn(Optional.of(order));
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterialTypeId("MAT-1"); item.setUnit("kg"); item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE); item.setUnitPrice(BigDecimal.TEN);
        item.setTaxRate(BigDecimal.ZERO); item.setPurchasePackageUnitSnapshot("kg");
        item.setInventoryBaseUnitSnapshot("kg"); item.setPackageToBaseFactorSnapshot(BigDecimal.ONE);
        when(itemRepository.findByPurchaseOrderId("PO-1")).thenReturn(List.of(item));
    }
}

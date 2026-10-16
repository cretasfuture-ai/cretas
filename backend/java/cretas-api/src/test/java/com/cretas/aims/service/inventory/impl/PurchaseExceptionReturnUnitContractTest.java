package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contract tests for the DTO boundary between purchase exceptions and generic returns. */
@ExtendWith(MockitoExtension.class)
class PurchaseExceptionReturnUnitContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-RETURN-001";
    private static final String MATERIAL_ID = "MAT-RETURN-001";
    private static final String SUPPLIER_ID = "SUP-RETURN-001";

    @Mock private PurchaseExceptionRepository exceptionRepository;
    @Mock private ReturnOrderService returnOrderService;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private UnitContractService unitContractService;
    @Mock private NotificationService notificationService;

    @InjectMocks private PurchaseExceptionServiceImpl service;

    @BeforeEach
    void wireOptionalAuthorities() throws Exception {
        var unitField = PurchaseExceptionServiceImpl.class.getDeclaredField("unitContractService");
        unitField.setAccessible(true);
        unitField.set(service, unitContractService);
        var materialField = PurchaseExceptionServiceImpl.class.getDeclaredField("rawMaterialTypeRepository");
        materialField.setAccessible(true);
        materialField.set(service, rawMaterialTypeRepository);
    }

    @Test
    void boxToKg_usesImmutableSnapshotAndTaxInclusiveBasePrice() {
        PurchaseOrderItem line = line("box", "kg", "10", "44.2478", "13", "1");
        PurchaseException exception = exception("box", "2");
        stubSource(line);

        capture(exception);

        CreateReturnOrderRequest.ReturnOrderItemDTO item = capturedItem();
        assertEquals(0, new BigDecimal("20").compareTo(item.getQuantity()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(item.getUnitPrice()));
        assertEquals(0, new BigDecimal("100.00").compareTo(item.getQuantity().multiply(item.getUnitPrice())));
    }

    @Test
    void packToPiece_usesSnapshotAndTaxInclusiveBasePrice() {
        PurchaseOrderItem line = line("包", "个", "10", "2.6549", "13", "1");
        PurchaseException exception = exception("包", "3");
        stubSource(line);

        capture(exception);

        CreateReturnOrderRequest.ReturnOrderItemDTO item = capturedItem();
        assertEquals(0, new BigDecimal("30").compareTo(item.getQuantity()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(item.getUnitPrice()));
        assertEquals(0, new BigDecimal("9.00").compareTo(item.getQuantity().multiply(item.getUnitPrice())));
    }

    @Test
    void nonOneQuantityToPriceFactor_isAppliedBeforeBasePriceConversion() {
        PurchaseOrderItem line = line("case", "kg", "10", "0.5000", "0", "2");
        PurchaseException exception = exception("case", "2");
        stubSource(line);

        capture(exception);

        CreateReturnOrderRequest.ReturnOrderItemDTO item = capturedItem();
        assertEquals(0, new BigDecimal("20").compareTo(item.getQuantity()));
        assertEquals(0, new BigDecimal("0.1000").compareTo(item.getUnitPrice()));
        assertEquals(0, new BigDecimal("2.00").compareTo(item.getQuantity().multiply(item.getUnitPrice())));
    }

    @Test
    void zeroTax_doesNotAddTax() {
        PurchaseOrderItem line = line("box", "kg", "10", "50.0000", "0", "1");
        PurchaseException exception = exception("box", "2");
        stubSource(line);

        capture(exception);

        assertEquals(0, new BigDecimal("5.0000").compareTo(capturedItem().getUnitPrice()));
    }

    @Test
    void legacyBasicKg_isAnExplicitIdentityConversion() {
        PurchaseOrderItem line = line("kg", "kg", null, "5.0000", "0", "1");
        line.setPurchasePackageUnitSnapshot(null);
        line.setInventoryBaseUnitSnapshot(null);
        PurchaseException exception = exception("kg", "2");
        stubSource(line);
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY_ID);
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));

        capture(exception);

        assertEquals(0, new BigDecimal("2").compareTo(capturedItem().getQuantity()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(capturedItem().getUnitPrice()));
    }

    @Test
    void convertedQuantityBelowInventoryPrecision_isRejectedBeforeReturnCreation() {
        PurchaseOrderItem line = line("g", "kg", "0.001", "5.0000", "0", "1");
        PurchaseException exception = exception("g", "1");
        stubSource(line);

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    @Test
    void convertedQuantityAtInventoryPrecision_isPreserved() {
        PurchaseOrderItem line = line("g", "kg", "0.001", "5.0000", "0", "1");
        PurchaseException exception = exception("g", "10");
        stubSource(line);

        capture(exception);

        assertEquals(0, new BigDecimal("0.01").compareTo(capturedItem().getQuantity()));
    }

    @Test
    void nonPurchaseExceptionUnit_usesActualQuantityInAuthoritativeConversion() {
        PurchaseOrderItem line = line("box", "kg", "10", "5.0000", "0", "1");
        PurchaseException exception = exception("g", "250");
        stubSource(line);
        when(unitContractService.convert(any(BigDecimal.class), any(UnitConversionContext.class)))
                .thenReturn(new UnitConversionResult(UnitConversionStatus.CONVERTED, new BigDecimal("0.25"),
                        "g", "kg", List.of("g", "kg"), null, null, null));

        capture(exception);

        assertEquals(0, new BigDecimal("0.25").compareTo(capturedItem().getQuantity()));
        ArgumentCaptor<BigDecimal> quantityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(unitContractService).convert(quantityCaptor.capture(), any(UnitConversionContext.class));
        assertEquals(0, new BigDecimal("250").compareTo(quantityCaptor.getValue()));
    }

    @Test
    void foreignSourcePo_isRejectedBeforeReturnCreation() {
        PurchaseException exception = exception("box", "2");
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    @Test
    void missingConversion_isRejectedBeforeReturnCreation() {
        PurchaseOrderItem line = line("box", "kg", null, "44.2478", "13", "1");
        PurchaseException exception = exception("box", "2");
        stubSource(line);

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    @Test
    void missingSameFactoryMaterialBaseUnit_isRejectedBeforeReturnCreation() {
        PurchaseOrderItem line = line("kg", null, null, "5.0000", "0", "1");
        line.setInventoryBaseUnitSnapshot(null);
        PurchaseException exception = exception("kg", "2");
        stubSource(line);

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    @Test
    void foreignMaterialBaseUnit_isRejectedBeforeReturnCreation() {
        PurchaseOrderItem line = line("kg", null, null, "5.0000", "0", "1");
        line.setInventoryBaseUnitSnapshot(null);
        PurchaseException exception = exception("kg", "2");
        stubSource(line);
        RawMaterialType foreign = new RawMaterialType();
        foreign.setId(MATERIAL_ID);
        foreign.setFactoryId("F999");
        foreign.setUnit("kg");
        when(rawMaterialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(foreign));

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    @Test
    void ambiguousSourceMaterialLines_areRejectedBeforeReturnCreation() {
        PurchaseException exception = exception("box", "2");
        PurchaseOrder source = sourceOrder();
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID)).thenReturn(Optional.of(source));
        PurchaseOrderItem first = line("box", "kg", "10", "44.2478", "13", "1");
        PurchaseOrderItem second = line("box", "kg", "10", "44.2478", "13", "1");
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(first, second));

        assertThrows(BusinessException.class, () -> service.createReturnOrderForException(exception, 42L, null));
        verify(returnOrderService, never()).createReturnOrder(any(), any(), any());
    }

    private void stubSource(PurchaseOrderItem line) {
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID)).thenReturn(Optional.of(sourceOrder()));
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(line));
    }

    private void capture(PurchaseException exception) {
        when(returnOrderService.createReturnOrder(eq(FACTORY_ID), any(CreateReturnOrderRequest.class), eq(42L)))
                .thenReturn(stubReturnOrder());
        service.createReturnOrderForException(exception, 42L, null);
    }

    private CreateReturnOrderRequest.ReturnOrderItemDTO capturedItem() {
        ArgumentCaptor<CreateReturnOrderRequest> captor = ArgumentCaptor.forClass(CreateReturnOrderRequest.class);
        verify(returnOrderService).createReturnOrder(eq(FACTORY_ID), captor.capture(), eq(42L));
        return captor.getValue().getItems().get(0);
    }

    private PurchaseOrder sourceOrder() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY_ID);
        return po;
    }

    private PurchaseException exception(String unit, String quantity) {
        PurchaseException exception = new PurchaseException();
        exception.setFactoryId(FACTORY_ID);
        exception.setPurchaseOrderId(PO_ID);
        exception.setSupplierId(SUPPLIER_ID);
        exception.setMaterialTypeId(MATERIAL_ID);
        exception.setMaterialName("测试物料");
        exception.setExceptionNumber("EXC-RETURN-001");
        exception.setUnit(unit);
        exception.setExceptionQty(new BigDecimal(quantity));
        return exception;
    }

    private PurchaseOrderItem line(String purchaseUnit, String baseUnit, String packageFactor,
                                   String unitPrice, String taxRate, String priceFactor) {
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setMaterialTypeId(MATERIAL_ID);
        line.setUnit(purchaseUnit);
        line.setPriceUnit(purchaseUnit);
        line.setQuantityToPriceFactor(new BigDecimal(priceFactor));
        line.setPurchasePackageUnitSnapshot(purchaseUnit);
        line.setInventoryBaseUnitSnapshot(baseUnit);
        line.setPackageToBaseFactorSnapshot(packageFactor == null ? null : new BigDecimal(packageFactor));
        line.setUnitPrice(new BigDecimal(unitPrice));
        line.setTaxRate(new BigDecimal(taxRate));
        return line;
    }

    private ReturnOrder stubReturnOrder() {
        ReturnOrder order = new ReturnOrder();
        order.setId("RO-RETURN-001");
        order.setReturnType(ReturnType.PURCHASE_RETURN);
        order.setStatus(ReturnOrderStatus.DRAFT);
        return order;
    }
}

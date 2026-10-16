package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.bom.BomRecipe.Status;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderApprovalRule;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderApprovalRuleRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceImplPurchasePriceBaseUnitContractTest {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "PO-PRICE-BASE-1";

    @Mock private PurchaseOrderRepository orderRepository;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialRepository;
    @Mock private MaterialBatchRepository batchRepository;
    @Mock private BomRecipeItemRepository bomRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher events;
    @Mock private MaterialBatchService batchService;
    @Mock private PurchaseOrderApprovalRuleRepository approvalRuleRepository;
    @Mock private WorkflowEngineService workflowEngine;
    @Mock private com.cretas.aims.repository.SupplierMaterialRepository supplierMaterialRepository;

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, receiveRepository,
                supplierRepository, materialRepository, batchRepository, bomRepository,
                arApService, events, batchService);
        ReflectionTestUtils.setField(service, "approvalRuleRepository", approvalRuleRepository);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterialRepository);
        ReflectionTestUtils.setField(service, "self", service);

        lenient().when(approvalRuleRepository
                .findByFactoryIdAndEnabledTrueOrderByCreatedAtDesc(FACTORY)).thenReturn(List.of());
        lenient().when(bomRepository.findCurrentByMaterialAndStatus(anyString(), anyString(),
                any(Status.class)))
                .thenReturn(List.of());
        lenient().when(orderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void orderPriceComparisonUsesLockedPackageAndPriceFactorsForAllUnits() {
        PurchaseOrder order = order();
        PurchaseOrderItem flour = line("flour", "面粉", "箱", "箱", BigDecimal.ONE,
                "箱", "kg", new BigDecimal("10"), new BigDecimal("44.2478"));
        PurchaseOrderItem bag = line("bag2", "Bag2", "包", "包", BigDecimal.ONE,
                "包", "个", new BigDecimal("10"), new BigDecimal("2.6549"));
        // Production-shaped legacy row: the old row has package/base snapshots but no
        // price-unit or quantity-price snapshot. It must use the package snapshot, not factor=1.
        PurchaseOrderItem legacy = line("legacy", "Legacy", "case", null, null,
                "case", "kg", new BigDecimal("10"), new BigDecimal("44.2478"));
        PurchaseOrderItem ordinary = line("ordinary", "普通基本单位", "kg", "kg", BigDecimal.ONE,
                "kg", "kg", BigDecimal.ONE, new BigDecimal("7.50"));
        PurchaseOrderItem grams = line("grams", "克计量", "g", "g", BigDecimal.ONE,
                "g", "kg", new BigDecimal("0.001"), new BigDecimal("0.00466"));
        PurchaseOrderItem basePricedBox = line("box", "按基本单位计价", "box", "kg", new BigDecimal("10"),
                "box", "kg", new BigDecimal("10"), new BigDecimal("4.66"));

        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID))
                .thenReturn(List.of(flour, bag, legacy, ordinary, grams, basePricedBox));
        when(materialRepository.findById("flour")).thenReturn(Optional.of(material("flour", "面粉", "kg", "4.66")));
        when(materialRepository.findById("bag2")).thenReturn(Optional.of(material("bag2", "Bag2", "个", "0.28")));
        when(materialRepository.findById("legacy")).thenReturn(Optional.of(material("legacy", "Legacy", "kg", "4.66")));
        when(materialRepository.findById("ordinary")).thenReturn(Optional.of(material("ordinary", "普通基本单位", "kg", "7.50")));
        when(materialRepository.findById("grams")).thenReturn(Optional.of(material("grams", "克计量", "kg", "4.66")));
        when(materialRepository.findById("box")).thenReturn(Optional.of(material("box", "按基本单位计价", "kg", "4.66")));

        var result = service.getOrderPriceComparison(FACTORY, ORDER_ID);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).getCurrentPrice()).isEqualByComparingTo("4.424780000000");
        assertThat(result.get(1).getCurrentPrice()).isEqualByComparingTo("0.265490000000");
        assertThat(result.get(2).getCurrentPrice()).isEqualByComparingTo("4.424780000000");
        assertThat(result.get(3).getCurrentPrice()).isEqualByComparingTo("7.50");
        assertThat(result.get(4).getCurrentPrice()).isEqualByComparingTo("4.66");
        assertThat(result.get(5).getCurrentPrice()).isEqualByComparingTo("4.66");
        assertThat(result).allMatch(dto -> !Boolean.TRUE.equals(dto.getPriceAlert()));
    }

    @Test
    void submitWorkflowContextCountsBaseUnitPriceAlertsThroughRealCaller() {
        PurchaseOrder order = order();
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setSupplierId("supplier-1");
        PurchaseOrderItem flour = line("flour", "面粉", "箱", "箱", BigDecimal.ONE,
                "箱", "kg", new BigDecimal("10"), new BigDecimal("44.2478"));
        when(orderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(flour));
        when(materialRepository.findById("flour")).thenReturn(Optional.of(material("flour", "面粉", "kg", "4.66")));
        Supplier supplier = new Supplier();
        supplier.setId("supplier-1");
        supplier.setFactoryId(FACTORY);
        supplier.setIsActive(true);
        when(supplierRepository.findByIdAndFactoryId("supplier-1", FACTORY)).thenReturn(Optional.of(supplier));
        when(supplierMaterialRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, "supplier-1", "flour")).thenReturn(true);
        when(workflowEngine.startWorkflowIfConfigured(eq(FACTORY), eq("PURCHASE_ORDER"), eq(ORDER_ID),
                anyMap(), eq(1309L))).thenReturn(Optional.empty());

        PurchaseOrder result = service.submitOrder(FACTORY, ORDER_ID, 1309L);

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).startWorkflowIfConfigured(eq(FACTORY), eq("PURCHASE_ORDER"), eq(ORDER_ID),
                context.capture(), eq(1309L));
        assertThat(context.getValue()).containsEntry("priceVarianceItemCount", 0);
    }

    @Test
    void legacyApprovalTriggerUsesBaseUnitPriceAndDoesNotRaiseFalseAlert() {
        PurchaseOrder order = order();
        order.setStatus(PurchaseOrderStatus.SUBMITTED);
        PurchaseOrderItem flour = line("flour", "面粉", "箱", "箱", BigDecimal.ONE,
                "箱", "kg", new BigDecimal("10"), new BigDecimal("44.2478"));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(flour));
        when(materialRepository.findById("flour")).thenReturn(Optional.of(material("flour", "面粉", "kg", "4.66")));

        PurchaseOrder result = service.approveOrder(FACTORY, ORDER_ID, 1400L);

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        verify(orderRepository).save(order);
    }

    @Test
    void invalidLockedFactorFailsClosedInsteadOfAssumingOne() {
        PurchaseOrder order = order();
        PurchaseOrderItem item = line("flour", "面粉", "箱", "箱", BigDecimal.ONE,
                "箱", "kg", BigDecimal.ZERO, new BigDecimal("44.2478"));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(item));

        assertThatThrownBy(() -> service.getOrderPriceComparison(FACTORY, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("PURCHASE_PRICE_CONVERSION_REQUIRED");
    }

    @Test
    void legacyWithoutSnapshotsRequiresMaterialBaseAndAuthoritativeConversion() {
        var units = mock(com.cretas.aims.service.unit.UnitContractService.class);
        ReflectionTestUtils.setField(service, "unitContractService", units);
        when(units.normalize(eq(FACTORY), anyString())).thenAnswer(invocation -> {
            String unit = invocation.getArgument(1);
            return new com.cretas.aims.service.unit.UnitNormalizationResult(unit, unit,
                    new com.cretas.aims.service.unit.CanonicalUnit(unit, null, unit,
                            BigDecimal.ONE, unit, 4));
        });
        when(units.convert(eq(BigDecimal.ONE), any())).thenAnswer(invocation -> {
            com.cretas.aims.service.unit.UnitConversionContext context = invocation.getArgument(1);
            assertThat(context.factoryId()).isEqualTo(FACTORY);
            assertThat(context.productTypeId()).isEqualTo("legacy");
            assertThat(context.fromUnit()).isEqualTo("case");
            assertThat(context.toUnit()).isEqualTo("kg");
            return new com.cretas.aims.service.unit.UnitConversionResult(
                    com.cretas.aims.service.unit.UnitConversionStatus.CONVERTED,
                    new BigDecimal("10"), "case", "kg", List.of(), null, null, null);
        });
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order()));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(
                line("legacy", "Legacy", "case", null, null, null, null, null, new BigDecimal("44.2478"))));
        when(materialRepository.findById("legacy"))
                .thenReturn(Optional.of(material("legacy", "Legacy", "kg", "4.66")));

        assertThat(service.getOrderPriceComparison(FACTORY, ORDER_ID).get(0).getCurrentPrice())
                .isEqualByComparingTo("4.42478");
    }

    @Test
    void legacyWithoutSnapshotsCannotTreatCaseAsBaseWhenConversionUnavailable() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order()));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(
                line("legacy", "Legacy", "case", null, null, null, null, null, new BigDecimal("44.2478"))));
        when(materialRepository.findById("legacy"))
                .thenReturn(Optional.of(material("legacy", "Legacy", "kg", "4.66")));
        assertThatThrownBy(() -> service.getOrderPriceComparison(FACTORY, ORDER_ID))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo("PURCHASE_PRICE_CONVERSION_REQUIRED");
    }

    @Test
    void submitRejectsInvalidConversionBeforeWorkflowOrOrderWrite() {
        PurchaseOrder order = order();
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setSupplierId("supplier-1");
        when(orderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(
                line("flour", "面粉", "箱", "箱", BigDecimal.ONE, "箱", "kg", BigDecimal.ZERO, new BigDecimal("44.2478"))));
        Supplier supplier = new Supplier();
        supplier.setId("supplier-1"); supplier.setFactoryId(FACTORY); supplier.setIsActive(true);
        when(supplierRepository.findByIdAndFactoryId("supplier-1", FACTORY)).thenReturn(Optional.of(supplier));
        when(supplierMaterialRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, "supplier-1", "flour")).thenReturn(true);

        assertThatThrownBy(() -> service.submitOrder(FACTORY, ORDER_ID, 1309L))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo("PURCHASE_PRICE_CONVERSION_REQUIRED");
        verify(workflowEngine, never()).startWorkflowIfConfigured(anyString(), anyString(), anyString(), anyMap(), any());
        verify(orderRepository, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    @Test
    void legacyApprovalRejectsInvalidConversionBeforeSavingApproval() {
        PurchaseOrder order = order();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(ORDER_ID)).thenReturn(List.of(
                line("flour", "面粉", "箱", "箱", BigDecimal.ONE, "箱", "kg", BigDecimal.ZERO, new BigDecimal("44.2478"))));
        assertThatThrownBy(() -> service.approveOrder(FACTORY, ORDER_ID, 1400L))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo("PURCHASE_PRICE_CONVERSION_REQUIRED");
        verify(orderRepository, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
    }

    private PurchaseOrder order() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("PO-PRICE-BASE-1");
        order.setCreatedBy(1309L);
        order.setStatus(PurchaseOrderStatus.SUBMITTED);
        return order;
    }

    private PurchaseOrderItem line(String materialId, String name, String unit, String priceUnit,
            BigDecimal quantityToPrice, String packageUnit, String baseUnit,
            BigDecimal packageToBase, BigDecimal unitPrice) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(ORDER_ID);
        item.setMaterialTypeId(materialId);
        item.setMaterialName(name);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit(unit);
        item.setPriceUnit(priceUnit);
        item.setQuantityToPriceFactor(quantityToPrice);
        item.setPurchasePackageUnitSnapshot(packageUnit);
        item.setInventoryBaseUnitSnapshot(baseUnit);
        item.setPackageToBaseFactorSnapshot(packageToBase);
        item.setUnitPrice(unitPrice);
        return item;
    }

    private RawMaterialType material(String id, String name, String unit, String movingAvgPrice) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(FACTORY);
        material.setName(name);
        material.setUnit(unit);
        material.setMovingAvgPrice(new BigDecimal(movingAvgPrice));
        return material;
    }
}

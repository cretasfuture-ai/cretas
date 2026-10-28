package com.cretas.aims.service.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.common.BusinessLink;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Real service behavior contract for factory scoped recall evidence and return limits. */
@ExtendWith(MockitoExtension.class)
class FactoryRecallServiceContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 41L;

    @InjectMocks
    private FactoryRecallService service;
    @Mock private RecallEventRepository recallEventRepository;
    @Mock private RecallActionRepository recallActionRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private SalesDeliveryItemRepository salesDeliveryItemRepository;
    @Mock private SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderService returnOrderService;
    @Mock private TraceabilityService traceabilityService;
    @Mock private LinkArrayService linkArrayService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private EntityManager entityManager;
    @Mock private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    @Mock private DisposalRecordRepository disposalRecordRepository;
    @Mock private UnitContractService unitContractService;

    private ProductionBatch batch;
    private FinishedGoodsBatch source;
    private SalesDeliveryRecord delivery;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(anyString(), anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "finishedGoodsAdjustmentLogRepository",
                finishedGoodsAdjustmentLogRepository);
        ReflectionTestUtils.setField(service, "disposalRecordRepository", disposalRecordRepository);
        batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("PB-41");
        batch.setProductTypeId("SKU-A");
        batch.setProductName("A");
        batch.setUnit("kg");

        source = new FinishedGoodsBatch();
        source.setId("fg-41");
        source.setFactoryId(FACTORY);
        source.setBatchNumber("PB-41");
        source.setProductTypeId("SKU-A");
        source.setProductName("A");
        source.setProducedQuantity(new BigDecimal("10"));
        source.setShippedQuantity(BigDecimal.ZERO);
        source.setReservedQuantity(BigDecimal.ZERO);
        source.setUnit("kg");
        source.setStatus(FinishedGoodsBatch.Status.AVAILABLE);

        delivery = new SalesDeliveryRecord();
        delivery.setId("delivery-1");
        delivery.setFactoryId(FACTORY);
        delivery.setDeliveryNumber("SO-DEL-1");
        delivery.setCustomerId("customer-1");
        delivery.setSalesOrderId("sales-order-1");
        delivery.setDeliveryDate(LocalDate.of(2026, 10, 16));
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setFinishedGoodsBatchId(source.getId());
        item.setProductTypeId("SKU-A");
        item.setUnit("kg");
        item.setDeliveredQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("12.50"));
        delivery.setItems(List.of(item));

        lenient().when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(Optional.of(batch));
        lenient().when(productionBatchRepository.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY))
                .thenReturn(Optional.of(batch));
        lenient().when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-41"))
                .thenReturn(Optional.of(source));
        lenient().when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "PB-41"))
                .thenReturn(Optional.of(source));
        lenient().when(salesDeliveryRecordRepository.findByFactoryIdAndDeliveryNumber(FACTORY, "SO-DEL-1"))
                .thenReturn(Optional.of(delivery));
        lenient().when(allocationRepository.findByFactoryIdAndFinishedGoodsBatchId(FACTORY, source.getId()))
                .thenReturn(List.of());
        lenient().when(traceabilityService.getFullTrace(FACTORY, "PB-41"))
                .thenReturn(TraceabilityDTO.FullTraceResponse.builder()
                        .shipments(List.of(TraceabilityDTO.ShipmentInfo.builder()
                                .shipmentNumber("SO-DEL-1").shipmentDate(delivery.getDeliveryDate())
                                .customerName("Customer 1").status("SHIPPED")
                                .quantity(10d).unit("kg").build())).build());
        lenient().when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of());
        lenient().when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(anyLong()))
                .thenReturn(List.of());
        lenient().when(linkArrayService.getInboundLinks(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(linkArrayService.getOutboundLinks(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
    }

    @Test
    void previewUsesOnlyShippedSameBatchSkuAndNativeUnitEvidence() {
        FactoryRecallDTO.RecallView view = service.preview(FACTORY, BATCH_ID);

        assertEquals("kg", view.getUnit());
        assertEquals(0, view.getShipments().get(0).getQuantity().compareTo(new BigDecimal("10")));
        assertEquals("delivery-1", view.getShipments().get(0).getShipmentId());
        assertEquals("kg", view.getShipments().get(0).getUnit());
        verify(salesDeliveryRecordRepository).findByFactoryIdAndDeliveryNumber(FACTORY, "SO-DEL-1");
    }

    @Test
    void previewKeepsSecondSplitFinishedGoodsBatchAllocation() {
        delivery.setItems(List.of());
        SalesDeliveryItemBatchAllocation allocation = new SalesDeliveryItemBatchAllocation();
        allocation.setId("alloc-2");
        allocation.setFactoryId(FACTORY);
        allocation.setDeliveryItemId("101");
        allocation.setFinishedGoodsBatchId(source.getId());
        allocation.setBatchNumber(source.getBatchNumber());
        allocation.setAllocatedQty(new BigDecimal("4"));
        allocation.setUnit("kg");
        SalesDeliveryItem allocatedItem = new SalesDeliveryItem();
        allocatedItem.setId(101L);
        allocatedItem.setDeliveryRecordId(delivery.getId());
        allocatedItem.setProductTypeId("SKU-A");
        allocatedItem.setUnit("kg");
        allocatedItem.setUnitPrice(new BigDecimal("12.50"));
        when(allocationRepository.findByFactoryIdAndFinishedGoodsBatchId(FACTORY, source.getId()))
                .thenReturn(List.of(allocation));
        when(salesDeliveryItemRepository.findById(101L)).thenReturn(Optional.of(allocatedItem));

        FactoryRecallDTO.RecallView view = service.preview(FACTORY, BATCH_ID);

        assertEquals(new BigDecimal("4"), view.getShipments().get(0).getQuantity());
    }

    @Test
    void createFreezesSourceAndKeepsProductionBatchIdInRecallAction() {
        RecallEvent saved = RecallEvent.builder().id(901L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-ABC12345").status("FROZEN")
                .triggerReason("pathogen").build();
        when(recallEventRepository.findByEventCode(anyString())).thenReturn(Optional.empty());
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class))).thenReturn(saved);
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FactoryRecallDTO.RecallView view = service.create(FACTORY, BATCH_ID, "pathogen", 7L);

        assertEquals("FROZEN", view.getStatus());
        assertEquals(FinishedGoodsBatch.Status.DEFECTIVE, source.getStatus());
        verify(linkArrayService).link(eq(FACTORY), eq("RECALL_EVENT"), eq("901"),
                eq("produce"), eq("PRODUCTION_BATCH"), eq(String.valueOf(BATCH_ID)),
                anyString(), eq("7"));
        verify(recallActionRepository).save(argThat(action ->
                "FREEZE_INVENTORY".equals(action.getActionType())
                        && BATCH_ID.equals(action.getTargetEntityId())));
    }

    /** 缺口 11 审查中 5: 召回冻结覆盖待检批次 —— 否则召回中的批次之后被质检放行成可售。 */
    @Test
    void createFreezesPendingInspectionSourceToo() {
        source.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        RecallEvent saved = RecallEvent.builder().id(902L).factoryId(FACTORY)
                .eventCode("RECALL-20261027-QC000001").status("FROZEN").triggerReason("ccp").build();
        when(recallEventRepository.findByEventCode(anyString())).thenReturn(Optional.empty());
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class))).thenReturn(saved);
        when(recallActionRepository.save(any(RecallAction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(FACTORY, BATCH_ID, "ccp", 7L);

        assertEquals(FinishedGoodsBatch.Status.DEFECTIVE, source.getStatus());
    }

    /** 阴性对照: 已耗尽 (DEPLETED) 的源批次不被冻结改写 —— 冻结只针对还能流出去的可售 / 待检。 */
    @Test
    void createLeavesDepletedSourceAlone() {
        source.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        RecallEvent saved = RecallEvent.builder().id(903L).factoryId(FACTORY)
                .eventCode("RECALL-20261027-QC000002").status("FROZEN").triggerReason("ccp").build();
        when(recallEventRepository.findByEventCode(anyString())).thenReturn(Optional.empty());
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class))).thenReturn(saved);
        when(recallActionRepository.save(any(RecallAction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(FACTORY, BATCH_ID, "ccp", 7L);

        assertEquals(FinishedGoodsBatch.Status.DEPLETED, source.getStatus());
    }

    @Test
    void unmanagedLegacyEventDoesNotActivateReleaseGuard() {
        RecallEvent event = RecallEvent.builder().id(902L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-LEGACY").status("FROZEN").build();
        when(recallEventRepository.findById(902L)).thenReturn(Optional.of(event));

        assertFalse(service.isManagedRecall(FACTORY, 902L));
        verify(linkArrayService).getOutboundLinks(FACTORY, "RECALL_EVENT", "902");
    }

    @Test
    void managedRecallRequiresFactoryOwnedProductionBatchLink() {
        RecallEvent event = RecallEvent.builder().id(903L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-MANAGED").status("FROZEN").build();
        BusinessLink batchLink = new BusinessLink();
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(recallEventRepository.findById(903L)).thenReturn(Optional.of(event));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "903"))
                .thenReturn(List.of(batchLink));

        assertTrue(service.isManagedRecall(FACTORY, 903L));

        event.setStatus("COMPLETED");
        assertTrue(service.isManagedRecall(FACTORY, 903L));
    }

    @Test
    void releaseGuardLocksBatchBeforeCheckingManagedRecall() {
        service.assertReleaseAllowed(FACTORY, BATCH_ID);

        verify(productionBatchRepository).findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY);
        verify(linkArrayService).getInboundLinks(FACTORY, "PRODUCTION_BATCH", String.valueOf(BATCH_ID));
    }

    @Test
    void closeIgnoresUnrelatedSkuItemsWhenReadingBackReturnInventory() {
        RecallEvent event = RecallEvent.builder().id(911L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-MULTISKU").status("FROZEN").build();
        when(recallEventRepository.findById(911L)).thenReturn(Optional.of(event));
        when(recallEventRepository.saveAndFlush(event)).thenReturn(event);
        BusinessLink batchLink = new BusinessLink();
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "911"))
                .thenReturn(List.of(batchLink));
        RecallAction ack = RecallAction.builder().actionType("CUSTOMER_REPLY")
                .executionNotes("{\"shipmentId\":\"delivery-1\"}").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(911L))
                .thenReturn(List.of(ack));
        ReturnOrder completed = returnOrder("rtn-multi", ReturnOrderStatus.COMPLETED,
                new BigDecimal("10"));
        ReturnOrderItem stable = completed.getItems().get(0);
        stable.setId(110L);
        stable.setReturnOrderId(completed.getId());
        stable.setSourceFinishedGoodsBatchId(source.getId());
        ReturnOrderItem unrelated = new ReturnOrderItem();
        unrelated.setProductTypeId("SKU-B");
        unrelated.setBatchNumber("PB-B");
        unrelated.setQuantity(new BigDecimal("99"));
        completed.setItems(List.of(completed.getItems().get(0), unrelated));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(completed));
        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setFactoryId(FACTORY);
        returned.setId("returned-multi");
        returned.setReturnOrderItemId(110L);
        returned.setBatchNumber("RTN-RTN-1-1");
        returned.setProductTypeId("SKU-A");
        returned.setUnit("kg");
        returned.setProducedQuantity(new BigDecimal("10.0000"));
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        returned.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 110L))
                .thenReturn(List.of(returned));
        when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(returned)));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);

        FactoryRecallDTO.RecallView view = service.close(FACTORY, 911L, "done", 7L);

        assertEquals("COMPLETED", view.getStatus());
        verify(recallEventRepository).saveAndFlush(event);
    }

    @Test
    void closeUsesStableReturnItemLinkForMultiLineReturnInventory() {
        RecallEvent event = RecallEvent.builder().id(920L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-STABLE-LINE").status("FROZEN").build();
        when(recallEventRepository.findById(920L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "920"))
                .thenReturn(List.of(batchLink));
        RecallAction ack = RecallAction.builder().actionType("CUSTOMER_REPLY")
                .executionNotes("{\"shipmentId\":\"delivery-1\"}").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(920L))
                .thenReturn(List.of(ack));

        ReturnOrder order = returnOrder("stable-lines", ReturnOrderStatus.COMPLETED,
                new BigDecimal("10"));
        order.setSourceOrderId("sales-order-1");
        ReturnOrderItem stable = order.getItems().get(0);
        stable.setId(220L);
        stable.setReturnOrderId(order.getId());
        stable.setSourceFinishedGoodsBatchId(source.getId());
        ReturnOrderItem unrelated = new ReturnOrderItem();
        unrelated.setId(221L);
        unrelated.setProductTypeId("SKU-OTHER");
        unrelated.setBatchNumber("PB-OTHER");
        unrelated.setQuantity(new BigDecimal("4"));
        order.setItems(List.of(stable, unrelated));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "sales-order-1")).thenReturn(List.of(order));

        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setId("returned-stable");
        returned.setFactoryId(FACTORY);
        returned.setBatchNumber("RTN-stable-lines-1");
        returned.setReturnOrderItemId(220L);
        returned.setProductTypeId("SKU-A");
        returned.setUnit("kg");
        returned.setProducedQuantity(new BigDecimal("10"));
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        returned.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 220L))
                .thenReturn(List.of(returned));
        when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(returned)));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FactoryRecallDTO.RecallView result = service.close(FACTORY, 920L, "stable source", 7L);

        assertEquals("COMPLETED", result.getStatus());
        // close() validates the return before saving, then eventView() rebuilds
        // the blockers/shipments for the returned response. Both reads are the
        // same stable ReturnOrderItem -> RTN child lookup.
        verify(finishedGoodsBatchRepository, times(2))
                .findByFactoryIdAndReturnOrderItemId(FACTORY, 220L);
        verify(recallEventRepository).saveAndFlush(any(RecallEvent.class));
    }

    @Test
    void closeAcceptsFullyDisposedReturnedBatchOnlyWithExactScrapEvidence() {
        RecallEvent event = RecallEvent.builder().id(912L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-DISPOSED").status("FROZEN").build();
        when(recallEventRepository.findById(912L)).thenReturn(Optional.of(event));
        when(recallEventRepository.saveAndFlush(event)).thenReturn(event);
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "912"))
                .thenReturn(List.of(batchLink));
        RecallAction ack = RecallAction.builder().actionType("CUSTOMER_REPLY")
                .executionNotes("{\"shipmentId\":\"delivery-1\"}").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(912L))
                .thenReturn(List.of(ack));
        ReturnOrder completed = returnOrder("rtn-disposed", ReturnOrderStatus.COMPLETED,
                new BigDecimal("10"));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(completed));
        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setId("returned-disposed");
        returned.setFactoryId(FACTORY);
        returned.setBatchNumber("RTN-RTN-1-1");
        returned.setProductTypeId("SKU-A");
        returned.setUnit("kg");
        returned.setProducedQuantity(BigDecimal.ZERO);
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        returned.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(returned)));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        FinishedGoodsAdjustmentLog scrap = FinishedGoodsAdjustmentLog.builder()
                .id(1201L).factoryId(FACTORY).batchId(returned.getId())
                .adjustmentQuantity(new BigDecimal("-10"))
                .beforeProduced(new BigDecimal("10"))
                .afterProduced(BigDecimal.ZERO)
                .reason("报损审批 [id=6101] 类型: SCRAP; unitCostBefore=6.4165; unitCostAfter=6.4165; unit=kg")
                .referenceType("SCRAP")
                .operatorId(7L)
                .createdAt(LocalDateTime.of(2026, 10, 16, 12, 0))
                .build();
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(scrap));
        DisposalRecord disposal = DisposalRecord.builder()
                .id(6101L).factoryId(FACTORY).finishedGoodsBatchId(returned.getId())
                .disposalQuantity(new BigDecimal("10"))
                .isApproved(true).approvalStatus("APPROVED").approvedBy(7)
                .approvalDate(LocalDateTime.of(2026, 10, 16, 12, 1)).build();
        when(disposalRecordRepository.findById(6101L)).thenReturn(Optional.of(disposal));

        FactoryRecallDTO.RecallView view = service.close(FACTORY, 912L, "done", 7L);

        assertEquals("COMPLETED", view.getStatus());
        verify(recallEventRepository).saveAndFlush(event);
    }

    @Test
    void closeRejectsDisposedReturnedBatchWithoutExactScrapEvidence() {
        RecallEvent event = RecallEvent.builder().id(913L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-DISPOSED-NOLOG").status("FROZEN").build();
        when(recallEventRepository.findById(913L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "913"))
                .thenReturn(List.of(batchLink));
        RecallAction ack = RecallAction.builder().actionType("CUSTOMER_REPLY")
                .executionNotes("{\"shipmentId\":\"delivery-1\"}").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(913L))
                .thenReturn(List.of(ack));
        ReturnOrder completed = returnOrder("rtn-disposed-no-log", ReturnOrderStatus.COMPLETED,
                new BigDecimal("10"));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(completed));
        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setId("returned-disposed-no-log");
        returned.setFactoryId(FACTORY);
        returned.setBatchNumber("RTN-RTN-1-1");
        returned.setProductTypeId("SKU-A");
        returned.setUnit("kg");
        returned.setProducedQuantity(BigDecimal.ZERO);
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        returned.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(returned)));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 913L, "done", 7L));

        assertEquals("RECALL_CLOSE_BLOCKED", ex.getErrorCode());
        verify(recallEventRepository, never()).saveAndFlush(event);
    }

    @Test
    void closeRejectsScrapEvidenceFromWrongFactoryOrNonScrapSource() {
        FinishedGoodsBatch returned = stubDisposedClose(914L, "returned-disposed-source");
        FinishedGoodsAdjustmentLog wrongFactory = scrapLog(1401L, "OTHER-FACTORY", returned.getId(),
                "SCRAP", new BigDecimal("10"), BigDecimal.ZERO);
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(wrongFactory));

        BusinessException wrongFactoryError = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 914L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", wrongFactoryError.getErrorCode());

        FinishedGoodsAdjustmentLog otherSource = scrapLog(1402L, FACTORY, returned.getId(),
                "MANUAL_ADJUSTMENT", new BigDecimal("10"), BigDecimal.ZERO);
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(otherSource));

        BusinessException otherSourceError = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 914L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", otherSourceError.getErrorCode());

        FinishedGoodsAdjustmentLog positive = scrapLog(1403L, FACTORY, returned.getId(),
                "SCRAP", BigDecimal.ZERO, new BigDecimal("10"));
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(positive));
        BusinessException positiveError = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 914L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", positiveError.getErrorCode());

        FinishedGoodsAdjustmentLog pendingApproval = scrapLog(1404L, FACTORY, returned.getId(),
                "SCRAP", new BigDecimal("10"), BigDecimal.ZERO);
        pendingApproval.setOperatorId(7L);
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(pendingApproval));
        DisposalRecord pendingDisposal = DisposalRecord.builder()
                .id(7404L).factoryId(FACTORY).finishedGoodsBatchId(returned.getId())
                .disposalQuantity(new BigDecimal("10")).isApproved(true)
                .approvalStatus("PENDING").approvedBy(7).build();
        when(disposalRecordRepository.findById(7404L)).thenReturn(Optional.of(pendingDisposal));
        BusinessException pendingError = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 914L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", pendingError.getErrorCode());

        pendingDisposal.setApprovalStatus("APPROVED");
        pendingDisposal.setApprovedBy(8);
        pendingDisposal.setApprovalDate(LocalDateTime.of(2026, 10, 16, 13, 1));
        BusinessException wrongOperatorError = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 914L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", wrongOperatorError.getErrorCode());
        verify(recallEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void closeAcceptsReturnedCustomerGoodsHandedBackToCustomer() {
        // Steve 2026-10-28: 客户自有退回货「退还客户」与报废同样算处置完成; 但扣减类型必须与单据一致
        FinishedGoodsBatch returned = stubDisposedClose(915L, "returned-customer-goods");
        FinishedGoodsAdjustmentLog handBack = scrapLog(1501L, FACTORY, returned.getId(),
                "RETURN_TO_CUSTOMER", new BigDecimal("10"), BigDecimal.ZERO);
        handBack.setOperatorId(7L);
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(handBack));
        DisposalRecord scrapRecord = DisposalRecord.builder()
                .id(7501L).factoryId(FACTORY).finishedGoodsBatchId(returned.getId())
                .disposalType("SCRAP").disposalQuantity(new BigDecimal("10")).build();
        scrapRecord.approve(7, "财务");
        when(disposalRecordRepository.findById(7501L)).thenReturn(Optional.of(scrapRecord));

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 915L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", mismatch.getErrorCode());

        scrapRecord.setDisposalType("RETURN_TO_CUSTOMER");
        RecallEvent event = recallEventRepository.findById(915L).orElseThrow();
        when(recallEventRepository.saveAndFlush(event)).thenReturn(event);

        FactoryRecallDTO.RecallView view = service.close(FACTORY, 915L, "handed back", 7L);

        assertEquals("COMPLETED", view.getStatus());
    }

    @Test
    void createReturnRejectsQuantityAboveShipmentWithoutCreatingSalesReturn() {
        RecallEvent event = RecallEvent.builder().id(901L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-ABC12345").status("FROZEN").build();
        when(recallEventRepository.findById(901L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "901"))
                .thenReturn(List.of(batchLink));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createReturn(FACTORY, 901L, "delivery-1",
                        new BigDecimal("10.01"), "damage", 7L));

        assertEquals("RECALL_RETURN_QUANTITY_EXCEEDED", ex.getErrorCode());
        verifyNoInteractions(returnOrderService);
    }

    @Test
    void samePendingQuantityIsIdempotentBeforeRemainingLimit() {
        RecallEvent event = RecallEvent.builder().id(901L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-ABC12345").status("FROZEN").build();
        when(recallEventRepository.findById(901L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "901"))
                .thenReturn(List.of(batchLink));
        ReturnOrder pending = returnOrder("rtn-1", ReturnOrderStatus.DRAFT, new BigDecimal("10"));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(pending));

        FactoryRecallDTO.ReturnOrderSummary summary = service.createReturn(
                FACTORY, 901L, "delivery-1", new BigDecimal("10"), "damage", 7L);

        assertEquals("rtn-1", summary.getId());
        verifyNoInteractions(returnOrderService);
        verify(entityManager).lock(event, LockModeType.PESSIMISTIC_WRITE);
        verify(entityManager).refresh(event);
    }

    @Test
    void writeStopsWhenPessimisticRecallLockFails() {
        RecallEvent event = RecallEvent.builder().id(904L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-LOCK").status("FROZEN").build();
        when(recallEventRepository.findById(904L)).thenReturn(Optional.of(event));
        doThrow(new IllegalStateException("lock unavailable"))
                .when(entityManager).lock(event, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(IllegalStateException.class, () -> service.createReturn(
                FACTORY, 904L, "delivery-1", new BigDecimal("2"), "damage", 7L));
        verifyNoInteractions(returnOrderService, linkArrayService);
        verify(entityManager, never()).refresh(any());
    }

    @Test
    void newReturnUsesRegistrationDateRatherThanShipmentDate() {
        RecallEvent event = RecallEvent.builder().id(901L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-ABC12345").status("FROZEN").build();
        when(recallEventRepository.findById(901L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "901"))
                .thenReturn(List.of(batchLink));
        ReturnOrder created = returnOrder("rtn-2", ReturnOrderStatus.DRAFT, new BigDecimal("2"));
        when(returnOrderService.createReturnOrder(eq(FACTORY), any(CreateReturnOrderRequest.class), eq(7L)))
                .thenReturn(created);

        service.createReturn(FACTORY, 901L, "delivery-1", new BigDecimal("2"), "damage", 7L);

        verify(returnOrderService).createReturnOrder(eq(FACTORY), argThat(request ->
                LocalDate.now().equals(request.getReturnDate())), eq(7L));
    }

    @Test
    void historicalReturnOverageBlocksAnotherReturn() {
        RecallEvent event = RecallEvent.builder().id(905L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-OVER").status("FROZEN").build();
        when(recallEventRepository.findById(905L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "905"))
                .thenReturn(List.of(batchLink));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(returnOrder("rtn-over", ReturnOrderStatus.COMPLETED,
                        new BigDecimal("11"))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createReturn(FACTORY, 905L, "delivery-1",
                        new BigDecimal("1"), "damage", 7L));

        assertEquals("RECALL_RETURN_OVERAGE", ex.getErrorCode());
        verifyNoInteractions(returnOrderService);
    }

    @Test
    void refundOnlyOrderDoesNotCountAsPhysicallyReturnedGoods() {
        RecallEvent event = RecallEvent.builder().id(907L).factoryId(FACTORY).status("FROZEN").build();
        when(recallEventRepository.findById(907L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "907"))
                .thenReturn(List.of(batchLink));
        ReturnOrder refund = returnOrder("refund-only", ReturnOrderStatus.COMPLETED, new BigDecimal("10"));
        refund.setWithGoods(false);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "sales-order-1")).thenReturn(List.of(refund));

        FactoryRecallDTO.ShipmentView shipment = service.get(FACTORY, 907L).getShipments().get(0);

        assertEquals(0, shipment.getReturnedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, shipment.getRemainingReturnQuantity().compareTo(new BigDecimal("10")));
        assertTrue(shipment.getReturnOrders().isEmpty());
    }

    @Test
    void closeRejectsRecallWithoutQuantifiableShipmentEvidence() {
        RecallEvent event = RecallEvent.builder().id(906L).factoryId(FACTORY)
                .eventCode("RECALL-20261016-NOSHIP").status("FROZEN").build();
        when(recallEventRepository.findById(906L)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setOwnerType("RECALL_EVENT");
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", "906"))
                .thenReturn(List.of(batchLink));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        when(traceabilityService.getFullTrace(FACTORY, "PB-41"))
                .thenReturn(TraceabilityDTO.FullTraceResponse.builder().shipments(List.of()).build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 906L, "none", 7L));

        assertEquals("RECALL_CLOSE_BLOCKED", ex.getErrorCode());
        verify(recallEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void closeRejectsTwoContinuousLogsReusingOneApprovedDisposal() {
        FinishedGoodsBatch returned = stubDisposedClose(918L, "returned-duplicate-disposal");
        FinishedGoodsAdjustmentLog first = scrapLog(2001L, FACTORY, returned.getId(),
                "SCRAP", new BigDecimal("10"), new BigDecimal("5"));
        FinishedGoodsAdjustmentLog second = scrapLog(2002L, FACTORY, returned.getId(),
                "SCRAP", new BigDecimal("5"), BigDecimal.ZERO);
        first.setOperatorId(7L);
        second.setOperatorId(7L);
        second.setReason(first.getReason());
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(second, first));
        when(disposalRecordRepository.findById(8001L))
                .thenReturn(Optional.of(approvedFiveUnitDisposal(8001L, returned.getId())));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 918L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", error.getErrorCode());
        verify(recallEventRepository, never()).saveAndFlush(any());

        // Only the second approval identity changes: two distinct approved
        // five-unit disposals now prove all ten units were actually handled.
        second.setReason("报损审批 [id=8002] 类型: SCRAP");
        when(disposalRecordRepository.findById(8002L))
                .thenReturn(Optional.of(approvedFiveUnitDisposal(8002L, returned.getId())));
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals("COMPLETED", service.close(FACTORY, 918L, "evidence corrected", 7L).getStatus());
        verify(recallEventRepository).saveAndFlush(any(RecallEvent.class));
    }

    @Test
    void closeRejectsContinuousScrapChainEndingOutsideCurrentInventory() {
        FinishedGoodsBatch returned = stubDisposedClose(919L, "returned-wrong-chain-tail");
        FinishedGoodsAdjustmentLog first = scrapLog(2011L, FACTORY, returned.getId(),
                "SCRAP", new BigDecimal("11"), new BigDecimal("6"));
        FinishedGoodsAdjustmentLog second = scrapLog(2012L, FACTORY, returned.getId(),
                "SCRAP", new BigDecimal("6"), BigDecimal.ONE);
        first.setOperatorId(7L);
        second.setOperatorId(7L);
        when(finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(returned.getId()))
                .thenReturn(List.of(second, first));
        when(disposalRecordRepository.findById(8011L))
                .thenReturn(Optional.of(approvedFiveUnitDisposal(8011L, returned.getId())));
        when(disposalRecordRepository.findById(8012L))
                .thenReturn(Optional.of(approvedFiveUnitDisposal(8012L, returned.getId())));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.close(FACTORY, 919L, "done", 7L));
        assertEquals("RECALL_CLOSE_BLOCKED", error.getErrorCode());
        verify(recallEventRepository, never()).saveAndFlush(any());

        // Keep both approvals and quantities; anchor the chain to current zero
        // stock to prove the endpoint mismatch is the reason for rejection.
        first.setBeforeProduced(new BigDecimal("10"));
        first.setAfterProduced(new BigDecimal("5"));
        second.setBeforeProduced(new BigDecimal("5"));
        second.setAfterProduced(BigDecimal.ZERO);
        when(recallEventRepository.saveAndFlush(any(RecallEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals("COMPLETED", service.close(FACTORY, 919L, "evidence corrected", 7L).getStatus());
        verify(recallEventRepository).saveAndFlush(any(RecallEvent.class));
    }

    private DisposalRecord approvedFiveUnitDisposal(long id, String batchId) {
        DisposalRecord record = DisposalRecord.builder().id(id).factoryId(FACTORY)
                .finishedGoodsBatchId(batchId).disposalQuantity(new BigDecimal("5")).build();
        record.approve(7, "财务");
        return record;
    }

    private ReturnOrder returnOrder(String id, ReturnOrderStatus status, BigDecimal quantity) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setReturnNumber("RTN-1");
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(status);
        ReturnOrderItem item = new ReturnOrderItem();
        item.setProductTypeId("SKU-A");
        item.setBatchNumber("PB-41");
        item.setQuantity(quantity);
        order.setItems(List.of(item));
        return order;
    }

    private FinishedGoodsBatch stubDisposedClose(long eventId, String returnedId) {
        RecallEvent event = RecallEvent.builder().id(eventId).factoryId(FACTORY)
                .eventCode("RECALL-20261016-DISPOSED-" + eventId).status("FROZEN").build();
        when(recallEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        BusinessLink batchLink = new BusinessLink();
        batchLink.setTargetType("PRODUCTION_BATCH");
        batchLink.setTargetId(String.valueOf(BATCH_ID));
        when(linkArrayService.getOutboundLinks(FACTORY, "RECALL_EVENT", String.valueOf(eventId)))
                .thenReturn(List.of(batchLink));
        RecallAction ack = RecallAction.builder().actionType("CUSTOMER_REPLY")
                .executionNotes("{\"shipmentId\":\"delivery-1\"}").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(eventId))
                .thenReturn(List.of(ack));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq("sales-order-1")))
                .thenReturn(List.of(returnOrder("rtn-disposed-" + eventId,
                        ReturnOrderStatus.COMPLETED, new BigDecimal("10"))));
        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setId(returnedId);
        returned.setFactoryId(FACTORY);
        returned.setBatchNumber("RTN-RTN-1-1");
        returned.setProductTypeId("SKU-A");
        returned.setUnit("kg");
        returned.setProducedQuantity(BigDecimal.ZERO);
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        returned.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(new PageImpl<>(List.of(returned)));
        source.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        return returned;
    }

    private FinishedGoodsAdjustmentLog scrapLog(long id, String factoryId, String batchId,
                                                 String referenceType, BigDecimal before,
                                                 BigDecimal after) {
        return FinishedGoodsAdjustmentLog.builder()
                .id(id).factoryId(factoryId).batchId(batchId)
                .adjustmentQuantity(after.subtract(before))
                .beforeProduced(before).afterProduced(after)
                .reason("报损审批 [id=" + (6000 + id) + "] 类型: SCRAP")
                .referenceType(referenceType)
                .createdAt(LocalDateTime.of(2026, 10, 16, 13, 0))
                .build();
    }
}

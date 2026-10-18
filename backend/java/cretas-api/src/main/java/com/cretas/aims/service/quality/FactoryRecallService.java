package com.cretas.aims.service.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.common.BusinessLink;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Factory-scoped recall orchestration.  It deliberately stores no new business
 * facts: RecallEvent/RecallAction are the audit ledger, links hold the batch
 * identity, and ReturnOrder remains the existing sales-return state machine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryRecallService {

    private static final String EVENT_OWNER = "RECALL_EVENT";
    private static final String BATCH_TARGET = "PRODUCTION_BATCH";
    private static final String DELIVERY_TARGET = "SALES_DELIVERY";
    private static final String RETURN_TARGET = "RETURN_ORDER";
    private static final String LINK_PRODUCE = "produce";
    private static final String LINK_SALE = "sale";
    private static final String FROZEN = "FROZEN";
    private static final String COMPLETED = "COMPLETED";

    private final RecallEventRepository recallEventRepository;
    private final RecallActionRepository recallActionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    private final SalesDeliveryItemRepository salesDeliveryItemRepository;
    private final SalesDeliveryItemBatchAllocationRepository allocationRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderService returnOrderService;
    private final TraceabilityService traceabilityService;
    private final LinkArrayService linkArrayService;
    private final ObjectMapper objectMapper;

    /** Existing adjustment history is the only source of proof for post-receipt disposal. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    /** Optional only for lightweight constructor tests; production always has this repository. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DisposalRecordRepository disposalRecordRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public FactoryRecallDTO.RecallView preview(String factoryId, Long batchId) {
        ProductionBatch batch = loadBatch(factoryId, batchId, false);
        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        return previewView(factoryId, batch, evidence);
    }

    @Transactional(readOnly = true)
    public List<FactoryRecallDTO.RecallSummary> list(String factoryId, Long batchId) {
        List<RecallEvent> events;
        if (batchId != null) {
            events = eventsForBatch(factoryId, batchId);
        } else {
            events = recallEventRepository.findByFactoryIdOrderByTriggerTimeDesc(
                    factoryId, Pageable.unpaged()).getContent().stream()
                    .filter(event -> hasBatchLink(factoryId, event)).toList();
        }
        List<FactoryRecallDTO.RecallSummary> result = new ArrayList<>();
        for (RecallEvent event : nullSafe(events)) {
            ProductionBatch batch = batchForEvent(factoryId, event);
            result.add(FactoryRecallDTO.RecallSummary.builder()
                    .id(event.getId())
                    .eventCode(event.getEventCode())
                    .batchId(batch == null ? null : batch.getId())
                    .batchNumber(batch == null ? null : batch.getBatchNumber())
                    .productName(batch == null ? event.getAffectedProductCategory() : batch.getProductName())
                    .status(event.getStatus())
                    .reason(event.getTriggerReason())
                    .completedAt(event.getCompletedAt())
                    .build());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public FactoryRecallDTO.RecallView get(String factoryId, Long eventId) {
        RecallEvent event = loadEvent(factoryId, eventId);
        ProductionBatch batch = requireBatchForEvent(factoryId, event);
        return eventView(factoryId, event, batch);
    }

    @Transactional
    public FactoryRecallDTO.RecallView create(String factoryId, Long batchId, String reason, Long actorId) {
        if (isBlank(reason)) {
            throw new BusinessException(400, "召回原因不能为空");
        }
        ProductionBatch batch = loadBatch(factoryId, batchId, true);
        RecallEvent existing = findActiveEventForBatch(factoryId, batchId);
        if (existing != null) {
            return eventView(factoryId, existing, batch);
        }

        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        if (evidence.isEmpty()) {
            throw new BusinessException(409, "该批次没有可量化的已发货记录，不能发起召回")
                    .withCode("RECALL_SHIPMENT_EVIDENCE_REQUIRED");
        }

        FinishedGoodsBatch finishedGoodsBatch = lockSourceFinishedGoods(factoryId, batch);
        if (FinishedGoodsBatch.Status.AVAILABLE.equals(finishedGoodsBatch.getStatus())
                && physicalQuantity(finishedGoodsBatch).signum() > 0) {
            finishedGoodsBatch.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
            finishedGoodsBatchRepository.save(finishedGoodsBatch);
        }

        RecallEvent event = RecallEvent.builder()
                .factoryId(factoryId)
                .eventCode(nextEventCode())
                .triggerReason(reason.trim())
                .affectedProductCategory(batch.getProductName())
                .triggerTime(LocalDateTime.now())
                .triggeredByUserId(actorId)
                .status(FROZEN)
                .build();
        event = recallEventRepository.saveAndFlush(event);
        if (event.getId() == null) {
            throw new BusinessException(500, "召回事件保存后缺少事件 ID");
        }
        link(factoryId, EVENT_OWNER, String.valueOf(event.getId()), LINK_PRODUCE,
                BATCH_TARGET, String.valueOf(batch.getId()), actorId, "召回事件关联生产批次");
        for (ShipmentEvidence item : evidence) {
            link(factoryId, EVENT_OWNER, String.valueOf(event.getId()), LINK_SALE,
                    DELIVERY_TARGET, item.shipmentId(), actorId, "召回事件关联真实出货单");
        }
        Map<String, Object> freezeFacts = new LinkedHashMap<>();
        freezeFacts.put("batchId", batch.getId());
        freezeFacts.put("batchNumber", batch.getBatchNumber());
        freezeFacts.put("remainingInventory", physicalQuantity(finishedGoodsBatch));
        freezeFacts.put("shipmentIds", evidence.stream().map(ShipmentEvidence::shipmentId).toList());
        freezeFacts.put("actorId", actorId);
        saveAction(event, "FREEZE_INVENTORY", batch.getId(), actorId, notes(freezeFacts),
                true);
        return eventView(factoryId, event, batch);
    }

    @Transactional
    public FactoryRecallDTO.RecallView customerAck(String factoryId, Long eventId,
                                                    String shipmentId, String note, Long actorId) {
        if (isBlank(shipmentId) || isBlank(note)) {
            throw new BusinessException(400, "出货单和客户回复凭据不能为空");
        }
        RecallEvent event = loadEventForUpdate(factoryId, eventId);
        ProductionBatch batch = requireBatchForEvent(factoryId, event);
        for (RecallAction action : recallActions(eventId)) {
            if ("CUSTOMER_REPLY".equals(action.getActionType())
                    && action.getExecutionNotes() != null
                    && action.getExecutionNotes().contains("\"shipmentId\":\"" + shipmentId + "\"")) {
                return eventView(factoryId, event, batch);
            }
        }
        ensureOpen(event);
        findShipment(factoryId, shipmentEvidence(factoryId, batch), shipmentId);
        Map<String, Object> ackFacts = new LinkedHashMap<>();
        ackFacts.put("shipmentId", shipmentId);
        ackFacts.put("note", note.trim());
        ackFacts.put("actorId", actorId);
        saveAction(event, "CUSTOMER_REPLY", batch.getId(), actorId, notes(ackFacts), true);
        return eventView(factoryId, event, batch);
    }

    @Transactional
    public FactoryRecallDTO.ReturnOrderSummary createReturn(String factoryId, Long eventId, String shipmentId,
                                                             BigDecimal quantity, String reason, Long actorId) {
        if (quantity == null || quantity.signum() <= 0 || isBlank(shipmentId) || isBlank(reason)) {
            throw new BusinessException(400, "出货单、退货数量和退货原因不能为空");
        }
        RecallEvent event = loadEventForUpdate(factoryId, eventId);
        ensureOpen(event);
        ProductionBatch batch = requireBatchForEvent(factoryId, event);
        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        ShipmentEvidence shipment = findShipment(factoryId, evidence, shipmentId);
        ReturnLedger ledger = returnLedger(factoryId, event.getId(), batch, evidence, shipment);
        if (ledger.overage().signum() > 0) {
            throw new BusinessException(409, "历史退货数量超过该出货单实际数量: " + ledger.overage())
                    .withCode("RECALL_RETURN_OVERAGE");
        }
        for (ReturnOrder order : ledger.orders()) {
            if (isPending(order)) {
                BigDecimal existing = quantityForBatch(order, batch);
                if (existing.compareTo(quantity) == 0) {
                    return returnSummary(order, batch);
                }
                throw new BusinessException(409, "该出货单已有待处理退货，请先完成或驳回原退货单")
                        .withCode("RECALL_RETURN_PENDING");
            }
        }
        if (quantity.compareTo(ledger.remaining()) > 0) {
            throw new BusinessException(409, "退货数量超过该出货单剩余可登记数量: " + ledger.remaining())
                    .withCode("RECALL_RETURN_QUANTITY_EXCEEDED");
        }

        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.SALES_RETURN.name());
        request.setCounterpartyId(shipment.customerId());
        request.setSourceOrderId(shipment.sourceOrderId());
        request.setReturnDate(LocalDate.now());
        request.setReason(reason.trim());
        request.setWithGoods(true);
        request.setRemark(returnMarker(eventId, shipmentId));
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setProductTypeId(batch.getProductTypeId());
        item.setItemName(batch.getProductName());
        item.setQuantity(quantity);
        item.setUnitPrice(shipment.unitPrice());
        item.setBatchNumber(batch.getBatchNumber());
        item.setReason(reason.trim());
        request.setItems(List.of(item));
        ReturnOrder order = returnOrderService.createReturnOrder(factoryId, request, actorId);
        if (order == null || isBlank(order.getId())) {
            throw new BusinessException(500, "退货单创建后缺少单据 ID");
        }
        link(factoryId, EVENT_OWNER, String.valueOf(eventId), LINK_SALE,
                RETURN_TARGET, order.getId(), actorId, "召回事件关联退货单");
        link(factoryId, RETURN_TARGET, order.getId(), LINK_SALE,
                DELIVERY_TARGET, shipmentId, actorId, "退货单关联原出货单");
        return returnSummary(order, batch);
    }

    @Transactional
    public FactoryRecallDTO.RecallView close(String factoryId, Long eventId, String note, Long actorId) {
        RecallEvent event = loadEventForUpdate(factoryId, eventId);
        if (COMPLETED.equals(event.getStatus())) {
            return get(factoryId, eventId);
        }
        ensureOpen(event);
        ProductionBatch batch = requireBatchForEvent(factoryId, event);
        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        List<FactoryRecallDTO.Blocker> blockers = new ArrayList<>();
        if (evidence.isEmpty()) {
            blockers.add(blocker("SHIPMENT_EVIDENCE_REQUIRED", "没有可量化的真实出货证据", null, null));
        }
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumberForUpdate(factoryId, batch.getBatchNumber()).orElse(null);
        if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())
                || (batch.getProductionPlanId() != null
                && !Objects.equals(batch.getProductionPlanId(), source.getProductionPlanId()))
                || !originalStockUnavailable(source)) {
            blockers.add(blocker("ORIGINAL_STOCK_NOT_ISOLATED", "原成品批次尚未隔离为不良品", null, null));
        }
        for (ShipmentEvidence shipment : evidence) {
            ReturnLedger ledger = returnLedger(factoryId, event.getId(), batch, evidence, shipment);
            if (!ledger.acknowledged()) {
                blockers.add(blocker("CUSTOMER_ACK_REQUIRED", "缺少客户联系/回复凭据", shipment.shipmentId(), null));
            }
            if (ledger.pending().compareTo(BigDecimal.ZERO) > 0) {
                blockers.add(blocker("RETURN_PENDING", "退货单尚未完成", shipment.shipmentId(), ledger.pending()));
            }
            if (ledger.overage().signum() > 0) {
                blockers.add(blocker("RETURN_OVERAGE", "历史退货数量超过出货数量", shipment.shipmentId(), ledger.overage()));
            }
            if (ledger.remaining().compareTo(BigDecimal.ZERO) > 0) {
                blockers.add(blocker("RETURN_REMAINING", "该出货单仍有未收回数量", shipment.shipmentId(), ledger.remaining()));
            }
            for (ReturnOrder order : ledger.orders().stream()
                    .filter(candidate -> candidate.getStatus() == ReturnOrderStatus.COMPLETED).toList()) {
                if (!hasIsolatedReturn(factoryId, order, batch)) {
                    blockers.add(blocker("RETURN_INVENTORY_NOT_READ_BACK", "退货已完成但不良品库存回读不匹配",
                            shipment.shipmentId(), quantityForBatch(order, batch)));
                }
            }
        }
        if (!blockers.isEmpty()) {
            throw new BusinessException(409, blockers.stream().map(FactoryRecallDTO.Blocker::getMessage)
                    .collect(Collectors.joining("；"))).withCode("RECALL_CLOSE_BLOCKED");
        }

        event.setStatus(COMPLETED);
        event.setCompletedAt(LocalDateTime.now());
        event = recallEventRepository.saveAndFlush(event);
        Map<String, Object> completionFacts = new LinkedHashMap<>();
        completionFacts.put("note", note == null ? "" : note);
        completionFacts.put("batchId", batch.getId());
        completionFacts.put("shipmentCount", evidence.size());
        completionFacts.put("actorId", actorId);
        saveAction(event, "COMPLETION_AUDIT", batch.getId(), actorId, notes(completionFacts), true);
        return eventView(factoryId, event, batch);
    }

    @Transactional(readOnly = true)
    public boolean isManagedRecall(String factoryId, Long eventId) {
        Optional<RecallEvent> event = recallEventRepository.findById(eventId);
        if (event.isEmpty() || !Objects.equals(factoryId, event.get().getFactoryId())) {
            return false;
        }
        return hasBatchLink(factoryId, event.get());
    }

    @Transactional
    public void assertReleaseAllowed(String factoryId, Long batchId) {
        loadBatch(factoryId, batchId, true);
        RecallEvent event = findActiveEventForBatch(factoryId, batchId);
        if (event != null) {
            throw new BusinessException(409, "批次处于召回处理中，完成召回前不得放行库存")
                    .withCode("RECALL_ACTIVE_RELEASE_BLOCKED");
        }
    }

    private FactoryRecallDTO.RecallView previewView(String factoryId, ProductionBatch batch,
                                                     List<ShipmentEvidence> evidence) {
        return FactoryRecallDTO.RecallView.builder()
                .batchId(batch.getId())
                .batchNumber(batch.getBatchNumber())
                .productName(batch.getProductName())
                .unit(sourceUnit(factoryId, batch))
                .remainingInventory(remainingInventory(factoryId, batch))
                .shipments(evidence.stream().map(this::toShipmentView).toList())
                .canClose(false)
                .blockers(List.of())
                .build();
    }

    private FactoryRecallDTO.RecallView eventView(String factoryId, RecallEvent event, ProductionBatch batch) {
        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        List<FactoryRecallDTO.Blocker> blockers = closeBlockers(factoryId, event, batch, evidence);
        return FactoryRecallDTO.RecallView.builder()
                .id(event.getId())
                .eventCode(event.getEventCode())
                .batchId(batch.getId())
                .batchNumber(batch.getBatchNumber())
                .productName(batch.getProductName())
                .unit(sourceUnit(factoryId, batch))
                .remainingInventory(remainingInventory(factoryId, batch))
                .status(event.getStatus())
                .reason(event.getTriggerReason())
                .completedAt(event.getCompletedAt())
                .canClose(blockers.isEmpty() && !evidence.isEmpty())
                .blockers(blockers)
                .shipments(evidence.stream()
                        .map(shipment -> detailedShipmentView(factoryId, event, batch, evidence, shipment))
                        .toList())
                .build();
    }

    private List<FactoryRecallDTO.Blocker> closeBlockers(String factoryId, RecallEvent event,
                                                          ProductionBatch batch,
                                                          List<ShipmentEvidence> evidence) {
        List<FactoryRecallDTO.Blocker> blockers = new ArrayList<>();
        if (evidence.isEmpty()) {
            blockers.add(blocker("SHIPMENT_EVIDENCE_REQUIRED", "没有可量化的真实出货证据", null, null));
        }
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())
                || (batch.getProductionPlanId() != null
                && !Objects.equals(batch.getProductionPlanId(), source.getProductionPlanId()))
                || !originalStockUnavailable(source)) {
            blockers.add(blocker("ORIGINAL_STOCK_NOT_ISOLATED", "原成品批次尚未隔离为不良品", null, null));
        }
        for (ShipmentEvidence shipment : evidence) {
            ReturnLedger ledger = returnLedger(factoryId, event.getId(), batch, evidence, shipment);
            if (!ledger.acknowledged()) blockers.add(blocker("CUSTOMER_ACK_REQUIRED", "缺少客户联系/回复凭据", shipment.shipmentId(), null));
            if (ledger.pending().signum() > 0) blockers.add(blocker("RETURN_PENDING", "退货单尚未完成", shipment.shipmentId(), ledger.pending()));
            if (ledger.overage().signum() > 0) blockers.add(blocker("RETURN_OVERAGE", "历史退货数量超过出货数量", shipment.shipmentId(), ledger.overage()));
            if (ledger.remaining().signum() > 0) blockers.add(blocker("RETURN_REMAINING", "该出货单仍有未收回数量", shipment.shipmentId(), ledger.remaining()));
            for (ReturnOrder order : ledger.orders().stream()
                    .filter(candidate -> candidate.getStatus() == ReturnOrderStatus.COMPLETED).toList()) {
                if (!hasIsolatedReturn(factoryId, order, batch)) {
                    blockers.add(blocker("RETURN_INVENTORY_NOT_READ_BACK", "退货已完成但不良品库存回读不匹配",
                            shipment.shipmentId(), quantityForBatch(order, batch)));
                }
            }
        }
        return blockers;
    }

    private List<ShipmentEvidence> shipmentEvidence(String factoryId, ProductionBatch batch) {
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())
                || (batch.getProductionPlanId() != null
                && !Objects.equals(batch.getProductionPlanId(), source.getProductionPlanId()))) {
            return List.of();
        }
        com.cretas.aims.dto.traceability.TraceabilityDTO.FullTraceResponse trace =
                traceabilityService.getFullTrace(factoryId, batch.getBatchNumber());
        if (trace == null || trace.getShipments() == null) return List.of();
        Map<String, ShipmentEvidenceBuilder> byDelivery = new LinkedHashMap<>();
        Set<String> seenDeliveries = new HashSet<>();
        for (com.cretas.aims.dto.traceability.TraceabilityDTO.ShipmentInfo row : trace.getShipments()) {
            if (isBlank(row.getShipmentNumber()) || row.getQuantity() == null
                    || row.getQuantity() <= 0d || isBlank(row.getUnit())) continue;
            SalesDeliveryRecord delivery = salesDeliveryRecordRepository
                    .findByFactoryIdAndDeliveryNumber(factoryId, row.getShipmentNumber()).orElse(null);
            if (delivery == null || (delivery.getStatus() != com.cretas.aims.entity.enums.SalesDeliveryStatus.SHIPPED
                    && delivery.getStatus() != com.cretas.aims.entity.enums.SalesDeliveryStatus.DELIVERED)
                    || isBlank(delivery.getId()) || isBlank(delivery.getCustomerId())
                    || isBlank(delivery.getSalesOrderId()) || !Objects.equals(factoryId, delivery.getFactoryId())) continue;
            if (!Objects.equals(source.getUnit(), row.getUnit())) continue;
            if (seenDeliveries.contains(delivery.getId())) continue;
            List<SalesDeliveryItem> matching = nullSafe(delivery.getItems()).stream()
                    .filter(item -> item != null && Objects.equals(source.getId(), item.getFinishedGoodsBatchId())
                            && Objects.equals(batch.getProductTypeId(), item.getProductTypeId())
                            && Objects.equals(source.getUnit(), item.getUnit()))
                    .toList();
            AllocationEvidence allocation = allocationEvidence(factoryId, source, batch, delivery);
            if (matching.isEmpty() && allocation.quantity().signum() <= 0) continue;
            seenDeliveries.add(delivery.getId());
            Set<BigDecimal> prices = new HashSet<>(matching.stream().map(SalesDeliveryItem::getUnitPrice)
                    .filter(Objects::nonNull).map(BigDecimal::stripTrailingZeros)
                    .collect(Collectors.toSet()));
            prices.addAll(allocation.prices());
            boolean missingPrice = matching.stream().anyMatch(item -> item.getUnitPrice() == null)
                    || allocation.missingPrice();
            if (missingPrice || prices.size() != 1) {
                throw new BusinessException(409, "出货单同 SKU 实际单价不唯一，不能安全生成召回退货")
                        .withCode("RECALL_SHIPMENT_PRICE_AMBIGUOUS");
            }
            ShipmentEvidenceBuilder builder = byDelivery.computeIfAbsent(delivery.getId(), ignored ->
                    new ShipmentEvidenceBuilder(delivery, prices.iterator().next(), source.getUnit()));
            BigDecimal actualQuantity = allocation.quantity().signum() > 0
                    ? allocation.quantity() : BigDecimal.valueOf(row.getQuantity());
            builder.quantity = builder.quantity.add(actualQuantity);
        }
        return byDelivery.values().stream().map(ShipmentEvidenceBuilder::build).toList();
    }

    private AllocationEvidence allocationEvidence(String factoryId, FinishedGoodsBatch source,
                                                   ProductionBatch batch, SalesDeliveryRecord delivery) {
        if (allocationRepository == null || salesDeliveryItemRepository == null) {
            return AllocationEvidence.empty();
        }
        List<SalesDeliveryItemBatchAllocation> allocations = nullSafe(allocationRepository
                .findByFactoryIdAndFinishedGoodsBatchId(factoryId, source.getId()));
        BigDecimal quantity = BigDecimal.ZERO;
        Set<BigDecimal> prices = new HashSet<>();
        boolean missingPrice = false;
        for (SalesDeliveryItemBatchAllocation allocation : allocations) {
            if (allocation == null || allocation.getAllocatedQty() == null
                    || allocation.getAllocatedQty().signum() <= 0
                    || !Objects.equals(factoryId, allocation.getFactoryId())
                    || !Objects.equals(source.getId(), allocation.getFinishedGoodsBatchId())) continue;
            Long itemId;
            try {
                itemId = Long.valueOf(allocation.getDeliveryItemId());
            } catch (RuntimeException ex) {
                continue;
            }
            SalesDeliveryItem item = salesDeliveryItemRepository.findById(itemId).orElse(null);
            if (item == null || !Objects.equals(delivery.getId(), item.getDeliveryRecordId())
                    || !Objects.equals(batch.getProductTypeId(), item.getProductTypeId())) continue;
            String unit = allocation.getUnit() == null ? item.getUnit() : allocation.getUnit();
            if (!Objects.equals(source.getUnit(), unit)) continue;
            quantity = quantity.add(allocation.getAllocatedQty());
            if (item.getUnitPrice() == null) missingPrice = true;
            else prices.add(item.getUnitPrice().stripTrailingZeros());
        }
        return new AllocationEvidence(quantity, prices, missingPrice);
    }

    private ReturnLedger returnLedger(String factoryId, Long eventId, ProductionBatch batch,
                                      List<ShipmentEvidence> allShipments, ShipmentEvidence shipment) {
        List<ReturnOrder> orders = nullSafe(returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.SALES_RETURN, shipment.sourceOrderId()));
        boolean multiShipmentSameOrder = allShipments.stream()
                .filter(item -> Objects.equals(item.sourceOrderId(), shipment.sourceOrderId())).count() > 1;
        List<ReturnOrder> relevant = new ArrayList<>();
        for (ReturnOrder order : orders) {
            BigDecimal qty = quantityForBatch(order, batch);
            if (qty.signum() <= 0) continue;
            if (hasShipmentMarker(order, shipment.shipmentId())) {
                relevant.add(order);
            } else if (multiShipmentSameOrder) {
                throw new BusinessException(409, "历史退货缺少精确出货单关联，无法在多个 shipment 间猜分配")
                        .withCode("RECALL_RETURN_SHIPMENT_AMBIGUOUS");
            } else {
                relevant.add(order);
            }
        }
        BigDecimal completed = relevant.stream().filter(order -> order.getStatus() == ReturnOrderStatus.COMPLETED)
                .map(order -> quantityForBatch(order, batch)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = relevant.stream().filter(this::isPending)
                .map(order -> quantityForBatch(order, batch)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overage = completed.add(pending).subtract(shipment.quantity());
        if (overage.signum() < 0) overage = BigDecimal.ZERO;
        BigDecimal remaining = shipment.quantity().subtract(completed).subtract(pending);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;
        return new ReturnLedger(relevant, completed, pending, remaining, overage,
                hasCustomerAck(eventId, shipment));
    }

    private boolean hasCustomerAck(Long eventId, ShipmentEvidence shipment) {
        return recallActions(eventId).stream().anyMatch(action -> "CUSTOMER_REPLY".equals(action.getActionType())
                && action.getExecutionNotes() != null
                && action.getExecutionNotes().contains("\"shipmentId\":\"" + shipment.shipmentId() + "\""));
    }

    private FactoryRecallDTO.ShipmentView toShipmentView(ShipmentEvidence shipment) {
        // Event-specific return figures are filled by eventView's detailed path.
        return FactoryRecallDTO.ShipmentView.builder()
                .shipmentId(shipment.shipmentId()).shipmentNumber(shipment.shipmentNumber())
                .customerName(shipment.customerName()).quantity(shipment.quantity()).unit(shipment.unit())
                .returnedQuantity(BigDecimal.ZERO).pendingReturnQuantity(BigDecimal.ZERO)
                .remainingReturnQuantity(shipment.quantity()).acknowledged(false)
                .returnOrders(List.of()).build();
    }

    private FactoryRecallDTO.ShipmentView detailedShipmentView(String factoryId, RecallEvent event,
                                                                 ProductionBatch batch,
                                                                 List<ShipmentEvidence> allShipments,
                                                                 ShipmentEvidence shipment) {
        ReturnLedger ledger = returnLedger(factoryId, event.getId(), batch, allShipments, shipment);
        List<FactoryRecallDTO.ReturnOrderSummary> orders = ledger.orders().stream()
                .map(order -> FactoryRecallDTO.ReturnOrderSummary.builder()
                        .id(order.getId()).returnNumber(order.getReturnNumber())
                        .status(order.getStatus() == null ? null : order.getStatus().name())
                        .quantity(quantityForBatch(order, batch)).build())
                .toList();
        return FactoryRecallDTO.ShipmentView.builder()
                .shipmentId(shipment.shipmentId()).shipmentNumber(shipment.shipmentNumber())
                .customerName(shipment.customerName()).quantity(shipment.quantity()).unit(shipment.unit())
                .returnedQuantity(ledger.completed()).pendingReturnQuantity(ledger.pending())
                .remainingReturnQuantity(ledger.remaining()).acknowledged(ledger.acknowledged())
                .returnOrders(orders).build();
    }

    private FactoryRecallDTO.ReturnOrderSummary returnSummary(ReturnOrder order, ProductionBatch batch) {
        return FactoryRecallDTO.ReturnOrderSummary.builder()
                .id(order.getId()).returnNumber(order.getReturnNumber())
                .status(order.getStatus() == null ? null : order.getStatus().name())
                .quantity(quantityForBatch(order, batch)).build();
    }

    private FinishedGoodsBatch lockSourceFinishedGoods(String factoryId, ProductionBatch batch) {
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumberForUpdate(factoryId, batch.getBatchNumber()).orElse(null);
        if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())
                || (batch.getProductionPlanId() != null
                && !Objects.equals(batch.getProductionPlanId(), source.getProductionPlanId()))) {
            throw new BusinessException(409, "找不到与生产批次严格匹配的成品库存批次")
                    .withCode("RECALL_SOURCE_FINISHED_BATCH_REQUIRED");
        }
        return source;
    }

    private boolean hasIsolatedReturn(String factoryId, ReturnOrder order, ProductionBatch batch) {
        if (order == null || order.getStatus() != ReturnOrderStatus.COMPLETED) return false;
        if (finishedGoodsBatchRepository == null) return false;
        List<FinishedGoodsBatch> batches = finishedGoodsBatchRepository
                .findByFactoryIdOrderByCreatedAtDesc(factoryId, Pageable.unpaged()).getContent();
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())) return false;
        List<ReturnOrderItem> items = nullSafe(order.getItems());
        boolean matched = false;
        for (int index = 0; index < items.size(); index++) {
            ReturnOrderItem item = items.get(index);
            if (item == null) continue;
            if (!Objects.equals(batch.getProductTypeId(), item.getProductTypeId())
                    || !Objects.equals(batch.getBatchNumber(), item.getBatchNumber())) continue;
            matched = true;
            boolean hasSourceLink = !isBlank(item.getSourceFinishedGoodsBatchId());
            boolean stableLineLink = item.getId() != null
                    && hasSourceLink
                    && Objects.equals(source.getId(), item.getSourceFinishedGoodsBatchId());
            if (hasSourceLink && !stableLineLink) return false;
            if (!stableLineLink && items.size() != 1) return false;
            String expected = "RTN-" + order.getReturnNumber() + "-" + (index + 1);
            List<FinishedGoodsBatch> candidates = stableLineLink
                    ? finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(
                            factoryId, item.getId())
                    : batches;
            if (candidates == null || (stableLineLink && candidates.size() != 1)) return false;
            boolean found = candidates.stream().anyMatch(candidate -> candidate != null
                    && Objects.equals(factoryId, candidate.getFactoryId())
                    && (stableLineLink || expected.equals(candidate.getBatchNumber()))
                    && (!stableLineLink || Objects.equals(item.getId(), candidate.getReturnOrderItemId()))
                    && item.getQuantity() != null && candidate.getProducedQuantity() != null
                    && returnedBatchStatusAllowsReceipt(candidate, factoryId, item.getQuantity())
                    && Objects.equals(batch.getProductTypeId(), candidate.getProductTypeId())
                    && Objects.equals(source.getUnit(), candidate.getUnit())
                    && zero(candidate.getShippedQuantity())
                    && zero(candidate.getReservedQuantity())
                    && returnedQuantityProven(candidate, item.getQuantity(), factoryId));
            if (!found) return false;
        }
        return matched;
    }

    /**
     * A completed return can have been disposed after receipt.  In that case
     * the child batch's produced quantity is smaller than the return line, so
     * equality with producedQuantity alone would erase the historical receipt.
     * Only a real SCRAP adjustment written for this exact child batch can prove
     * the missing quantity; no other inventory row or status is accepted.
     */
    private boolean returnedQuantityProven(FinishedGoodsBatch returnedBatch, BigDecimal returnedQuantity,
                                           String factoryId) {
        BigDecimal physical = physicalQuantity(returnedBatch);
        if (returnedQuantity.compareTo(physical) == 0
                && FinishedGoodsBatch.Status.DEFECTIVE.equals(returnedBatch.getStatus())) return true;
        if (returnedQuantity.compareTo(physical) < 0) return false;
        BigDecimal scrapped = scrappedReturnQuantity(factoryId, returnedBatch.getId(),
                returnedBatch.getProducedQuantity());
        return scrapped != null && returnedQuantity.compareTo(physical.add(scrapped)) == 0;
    }

    private boolean returnedBatchStatusAllowsReceipt(FinishedGoodsBatch returnedBatch,
                                                      String factoryId,
                                                      BigDecimal returnedQuantity) {
        if (FinishedGoodsBatch.Status.DEFECTIVE.equals(returnedBatch.getStatus())) {
            return true;
        }
        return FinishedGoodsBatch.Status.DEPLETED.equals(returnedBatch.getStatus())
                && physicalQuantity(returnedBatch).signum() == 0
                && scrappedReturnQuantity(factoryId, returnedBatch.getId(),
                returnedBatch.getProducedQuantity()) != null
                && returnedQuantityProven(returnedBatch, returnedQuantity, factoryId);
    }

    private BigDecimal scrappedReturnQuantity(String factoryId, String batchId,
                                              BigDecimal currentProduced) {
        if (isBlank(factoryId) || isBlank(batchId) || finishedGoodsAdjustmentLogRepository == null
                || disposalRecordRepository == null || currentProduced == null
                || currentProduced.signum() < 0) return null;
        List<FinishedGoodsAdjustmentLog> history;
        try {
            history = finishedGoodsAdjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        } catch (RuntimeException ex) {
            log.warn("读取退回成品 SCRAP 调整证据失败: factory={}, batchId={}", factoryId, batchId, ex);
            return null;
        }
        List<FinishedGoodsAdjustmentLog> scrap = nullSafe(history).stream()
                .filter(entry -> entry != null
                        && Objects.equals(factoryId, entry.getFactoryId())
                        && Objects.equals(batchId, entry.getBatchId())
                        && "SCRAP".equals(entry.getReferenceType()))
                .sorted((left, right) -> {
                    LocalDateTime l = left.getCreatedAt();
                    LocalDateTime r = right.getCreatedAt();
                    if (l == null && r == null) return Long.compare(nullSafeId(left), nullSafeId(right));
                    if (l == null) return -1;
                    if (r == null) return 1;
                    int byTime = l.compareTo(r);
                    return byTime != 0 ? byTime : Long.compare(nullSafeId(left), nullSafeId(right));
                })
                .toList();
        if (scrap.isEmpty()) return null;

        BigDecimal disposed = BigDecimal.ZERO;
        BigDecimal expectedBefore = null;
        Set<Long> disposalIds = new HashSet<>();
        for (FinishedGoodsAdjustmentLog entry : scrap) {
            BigDecimal adjustment = entry.getAdjustmentQuantity();
            BigDecimal before = entry.getBeforeProduced();
            BigDecimal after = entry.getAfterProduced();
            Long disposalId = parseDisposalId(entry.getReason());
            if (adjustment == null || before == null || after == null
                    || adjustment.signum() >= 0
                    || before.signum() < 0 || after.signum() < 0
                    || before.subtract(adjustment.abs()).compareTo(after) != 0
                    || disposalId == null || !disposalIds.add(disposalId)
                    || !approvedDisposalEvidence(factoryId, batchId, entry, disposalId)) {
                return null;
            }
            if (expectedBefore != null && expectedBefore.compareTo(before) != 0) return null;
            expectedBefore = after;
            disposed = disposed.add(adjustment.abs());
        }
        if (expectedBefore == null || expectedBefore.compareTo(currentProduced) != 0) return null;
        return disposed.signum() > 0 ? disposed : null;
    }

    private boolean approvedDisposalEvidence(String factoryId, String batchId,
                                              FinishedGoodsAdjustmentLog entry, Long disposalId) {
        String reason = entry.getReason();
        if (isBlank(reason) || !reason.startsWith("报损审批 [id=")) return false;
        Optional<DisposalRecord> record;
        try {
            record = disposalRecordRepository.findById(disposalId);
        } catch (RuntimeException ex) {
            return false;
        }
        if (record.isEmpty()) return false;
        DisposalRecord disposal = record.get();
        return Boolean.TRUE.equals(disposal.getIsApproved())
                && "APPROVED".equals(disposal.getApprovalStatus())
                && disposal.getApprovedBy() != null
                && entry.getOperatorId() != null
                && Objects.equals(entry.getOperatorId(), disposal.getApprovedBy().longValue())
                && disposal.getApprovalDate() != null
                && Objects.equals(factoryId, disposal.getFactoryId())
                && Objects.equals(batchId, disposal.getFinishedGoodsBatchId())
                && disposal.getDisposalQuantity() != null
                && disposal.getDisposalQuantity().compareTo(entry.getAdjustmentQuantity().abs()) == 0;
    }

    private Long parseDisposalId(String reason) {
        if (isBlank(reason) || !reason.startsWith("报损审批 [id=")) return null;
        int end = reason.indexOf(']', "报损审批 [id=".length());
        if (end < 0) return null;
        try {
            return Long.valueOf(reason.substring("报损审批 [id=".length(), end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long nullSafeId(FinishedGoodsAdjustmentLog entry) {
        return entry.getId() == null ? Long.MIN_VALUE : entry.getId();
    }

    private boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private List<RecallEvent> eventsForBatch(String factoryId, Long batchId) {
        return nullSafe(linkArrayService.getInboundLinks(factoryId, BATCH_TARGET, String.valueOf(batchId))).stream()
                .filter(link -> EVENT_OWNER.equals(link.getOwnerType()))
                .map(BusinessLink::getOwnerId).map(this::parseLong)
                .filter(Objects::nonNull).map(id -> recallEventRepository.findById(id).orElse(null))
                .filter(Objects::nonNull).filter(event -> Objects.equals(factoryId, event.getFactoryId())).toList();
    }

    private boolean hasBatchLink(String factoryId, RecallEvent event) {
        return nullSafe(linkArrayService.getOutboundLinks(
                factoryId, EVENT_OWNER, String.valueOf(event.getId()))).stream()
                .anyMatch(link -> {
                    Long batchId = parseLong(link.getTargetId());
                    return BATCH_TARGET.equals(link.getTargetType()) && batchId != null
                            && productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).isPresent();
                });
    }

    private RecallEvent findActiveEventForBatch(String factoryId, Long batchId) {
        return eventsForBatch(factoryId, batchId).stream()
                .filter(event -> !COMPLETED.equals(event.getStatus())).findFirst().orElse(null);
    }

    private ProductionBatch batchForEvent(String factoryId, RecallEvent event) {
        for (BusinessLink link : nullSafe(linkArrayService.getOutboundLinks(
                factoryId, EVENT_OWNER, String.valueOf(event.getId())))) {
            if (BATCH_TARGET.equals(link.getTargetType())) {
                Long id = parseLong(link.getTargetId());
                if (id != null) return productionBatchRepository.findByIdAndFactoryId(id, factoryId).orElse(null);
            }
        }
        return null;
    }

    private ProductionBatch requireBatchForEvent(String factoryId, RecallEvent event) {
        ProductionBatch batch = batchForEvent(factoryId, event);
        if (batch == null) throw new BusinessException(409, "召回事件缺少生产批次关联");
        return batch;
    }

    /** Package-private read helpers shared by the follow-up projection. */
    RecallEvent followUpEvent(String factoryId, Long eventId) {
        return loadEvent(factoryId, eventId);
    }

    ProductionBatch followUpBatch(String factoryId, RecallEvent event) {
        return requireBatchForEvent(factoryId, event);
    }

    List<RecallAction> followUpActions(Long eventId) {
        return recallActions(eventId);
    }

    List<ReturnOrder> followUpReturnOrders(String factoryId, RecallEvent event, ProductionBatch batch) {
        List<ShipmentEvidence> evidence = shipmentEvidence(factoryId, batch);
        Set<String> seen = new HashSet<>();
        List<ReturnOrder> result = new ArrayList<>();
        for (ShipmentEvidence shipment : evidence) {
            for (ReturnOrder order : returnLedger(factoryId, event.getId(), batch, evidence, shipment).orders()) {
                if (order != null && !isBlank(order.getId()) && seen.add(order.getId())) result.add(order);
            }
        }
        return result;
    }

    BigDecimal followUpQuantityForBatch(ReturnOrder order, ProductionBatch batch) {
        return quantityForBatch(order, batch);
    }

    BigDecimal followUpPhysicalQuantity(FinishedGoodsBatch batch) {
        return physicalQuantity(batch);
    }

    BigDecimal followUpScrappedQuantity(String factoryId, FinishedGoodsBatch batch) {
        return scrappedReturnQuantity(factoryId, batch.getId(), batch.getProducedQuantity());
    }

    boolean followUpStatusAllowsReceipt(String factoryId, FinishedGoodsBatch batch,
                                        BigDecimal receivedQuantity) {
        return returnedBatchStatusAllowsReceipt(batch, factoryId, receivedQuantity);
    }

    private ProductionBatch loadBatch(String factoryId, Long batchId, boolean lock) {
        Optional<ProductionBatch> result = lock
                ? productionBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                : productionBatchRepository.findByIdAndFactoryId(batchId, factoryId);
        return result.orElseThrow(() -> new BusinessException(404, "生产批次不存在或不属于当前工厂"));
    }

    private RecallEvent loadEvent(String factoryId, Long eventId) {
        RecallEvent event = recallEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(404, "召回事件不存在"));
        if (!Objects.equals(factoryId, event.getFactoryId())) {
            throw new BusinessException(403, "召回事件不属于当前工厂");
        }
        return event;
    }

    private RecallEvent loadEventForUpdate(String factoryId, Long eventId) {
        RecallEvent event = loadEvent(factoryId, eventId);
        if (entityManager == null) {
            throw new IllegalStateException("召回写操作缺少事务锁管理器");
        }
        entityManager.lock(event, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(event);
        return event;
    }

    private void ensureOpen(RecallEvent event) {
        if (COMPLETED.equals(event.getStatus())) {
            throw new BusinessException(409, "召回事件已结案");
        }
    }

    private void link(String factoryId, String ownerType, String ownerId, String linkType, String targetType,
                      String targetId, Long actorId, String description) {
        linkArrayService.link(factoryId, ownerType, ownerId,
                linkType, targetType, targetId, description, actorId == null ? null : String.valueOf(actorId));
    }

    private void saveAction(RecallEvent event, String actionType, Long batchId, Long actorId,
                            String executionNotes, boolean completed) {
        RecallAction action = RecallAction.builder()
                .recallEventId(event.getId()).actionType(actionType)
                .targetEntityType(BATCH_TARGET).targetEntityId(batchId)
                .status(completed ? "COMPLETED" : "PENDING")
                .executedAt(completed ? LocalDateTime.now() : null)
                .executionNotes(executionNotes).build();
        recallActionRepository.save(action);
    }

    private List<RecallAction> recallActions(Long eventId) {
        return nullSafe(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(eventId));
    }

    private String nextEventCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "RECALL-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if (recallEventRepository.findByEventCode(code).isEmpty()) return code;
        }
        throw new BusinessException(503, "暂时无法生成唯一召回事件编号");
    }

    private String notes(Map<String, Object> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (JsonProcessingException e) { throw new BusinessException(500, "召回审计信息序列化失败"); }
    }

    private String returnMarker(Long eventId, String shipmentId) {
        return "RECALL_EVENT_ID=" + eventId + ";SHIPMENT_ID=" + shipmentId + ";";
    }

    private boolean hasShipmentMarker(ReturnOrder order, String shipmentId) {
        return order.getRemark() != null && order.getRemark().contains("SHIPMENT_ID=" + shipmentId + ";");
    }

    private BigDecimal quantityForBatch(ReturnOrder order, ProductionBatch batch) {
        if (order == null || order.getReturnType() != ReturnType.SALES_RETURN
                || Boolean.FALSE.equals(order.getWithGoods())) return BigDecimal.ZERO;
        return nullSafe(order.getItems()).stream().filter(item -> item != null
                        && Objects.equals(batch.getProductTypeId(), item.getProductTypeId())
                        && Objects.equals(batch.getBatchNumber(), item.getBatchNumber())
                        && item.getQuantity() != null && item.getQuantity().signum() > 0)
                .map(ReturnOrderItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isPending(ReturnOrder order) {
        return order != null && order.getStatus() != ReturnOrderStatus.REJECTED
                && order.getStatus() != ReturnOrderStatus.COMPLETED;
    }

    private ShipmentEvidence findShipment(String factoryId, List<ShipmentEvidence> evidence, String shipmentId) {
        return evidence.stream().filter(item -> Objects.equals(item.shipmentId(), shipmentId)).findFirst()
                .orElseThrow(() -> new BusinessException(409, "出货单不是该召回批次的真实出货记录"));
    }

    private String sourceUnit(String factoryId, ProductionBatch batch) {
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        return source == null ? batch.getUnit() : source.getUnit();
    }

    private BigDecimal physicalQuantity(FinishedGoodsBatch source) {
        BigDecimal produced = source.getProducedQuantity() == null
                ? BigDecimal.ZERO : source.getProducedQuantity();
        BigDecimal shipped = source.getShippedQuantity() == null
                ? BigDecimal.ZERO : source.getShippedQuantity();
        return produced.subtract(shipped);
    }

    private boolean originalStockUnavailable(FinishedGoodsBatch source) {
        return FinishedGoodsBatch.Status.DEFECTIVE.equals(source.getStatus())
                || (FinishedGoodsBatch.Status.DEPLETED.equals(source.getStatus())
                && physicalQuantity(source).signum() <= 0);
    }

    private BigDecimal remainingInventory(String factoryId, ProductionBatch batch) {
        FinishedGoodsBatch source = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        return source == null ? BigDecimal.ZERO : physicalQuantity(source);
    }

    private FactoryRecallDTO.Blocker blocker(String code, String message, String shipmentId, BigDecimal quantity) {
        return FactoryRecallDTO.Blocker.builder().code(code).message(message)
                .shipmentId(shipmentId).quantity(quantity).build();
    }

    private Long parseLong(String value) {
        try { return value == null ? null : Long.valueOf(value); } catch (NumberFormatException e) { return null; }
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }

    private static <T> List<T> nullSafe(List<T> source) { return source == null ? Collections.emptyList() : source; }

    private record ShipmentEvidence(String shipmentId, String shipmentNumber, String customerId,
                                    String customerName, String sourceOrderId, LocalDate deliveryDate,
                                    BigDecimal quantity, String unit, BigDecimal unitPrice) { }

    private static final class ShipmentEvidenceBuilder {
        private final SalesDeliveryRecord delivery;
        private final BigDecimal unitPrice;
        private final String unit;
        private BigDecimal quantity = BigDecimal.ZERO;

        private ShipmentEvidenceBuilder(SalesDeliveryRecord delivery, BigDecimal unitPrice, String unit) {
            this.delivery = delivery; this.unitPrice = unitPrice; this.unit = unit;
        }

        private ShipmentEvidence build() {
            return new ShipmentEvidence(delivery.getId(), delivery.getDeliveryNumber(), delivery.getCustomerId(),
                    delivery.getCustomerName(), delivery.getSalesOrderId(), delivery.getDeliveryDate(),
                    quantity, unit, unitPrice);
        }
    }

    private record ReturnLedger(List<ReturnOrder> orders, BigDecimal completed, BigDecimal pending,
                                BigDecimal remaining, BigDecimal overage, boolean acknowledged) { }

    private record AllocationEvidence(BigDecimal quantity, Set<BigDecimal> prices,
                                      boolean missingPrice) {
        private static AllocationEvidence empty() {
            return new AllocationEvidence(BigDecimal.ZERO, Set.of(), false);
        }
    }
}

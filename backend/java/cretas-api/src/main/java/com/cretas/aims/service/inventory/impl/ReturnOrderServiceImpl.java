package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.inventory.ReturnOrderRowLock;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReturnOrderServiceImpl implements ReturnOrderService {

    private static final Logger log = LoggerFactory.getLogger(ReturnOrderServiceImpl.class);

    /** #3(a): 挂在 AR/AP 调整上的来源单据类型, 使退货单驳回时可级联撤销挂起调整. 与 ReturnVoucherGenerator.BUSINESS_TYPE 一致. */
    private static final String ADJUSTMENT_SOURCE_TYPE = "RETURN_ORDER";

    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderItemRepository returnOrderItemRepository;
    private final ArApService arApService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Purchase-return source lock/capacity gate. Optional only for legacy unit-test construction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PurchaseReturnCapacityService purchaseReturnCapacityService;
    /** Synchronous rejection accounting must share the rejection transaction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VoucherService voucherService;

    /**
     * Serialize completion side effects for one return order.  The entity @Version
     * check protects the final status update, but AR/AP and inbound writes happen
     * before that update, so optimistic locking alone is too late for a duplicate
     * concurrent completion.
     */
    @PersistenceContext
    private EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.finance.AccountingPeriodService accountingPeriodService;

    // T-RTA Phase C (issue #571): create DEFECTIVE FinishedGoodsBatch in the factory's
    // 成品仓 (WH-FG, via WarehouseResolver#resolveFinishedGoodsId) on sales return
    // completion (with-goods path). ⚠️ 2026-08-19: 落点从 WH-LOG 物流仓改为成品仓 —— 退回来的
    // 是成品, 落物流仓是错的; status 仍为 DEFECTIVE (隔离待质检) 是设计意图, 未改。
    // @Autowired(required=false) so prior wiring still works if these beans aren't
    // available in some test contexts.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UnitContractService unitContractService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderItemRepository salesOrderItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesDeliveryItemBatchAllocationRepository salesDeliveryItemBatchAllocationRepository;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /**
     * Issue #795 — PURCHASE_RETURN with-goods 不良品入库.
     * 镜像 SALES_RETURN with-goods 在 FinishedGoodsBatchRepository 上的写入语义,
     * 但作用于 MaterialBatch (原料退给供应商前先入库到 WH-LOG, status=DEFECTIVE).
     * required=false 兼容单元测试 mock 场景.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialBatchRepository materialBatchRepository;

    /**
     * BLOCKER 2 fix: PURCHASE_RETURN with-goods 完成时从源库存扣减 (货物理离开退回供应商),
     * 写 MaterialBatchAdjustment 负数留痕 (镜像 DisposalRecordService 扣减语义)。
     * required=false 兼容单元测试 mock 场景。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;

    /** Round 11 T2 — Canvas Integration Template hook 1: DB-driven validation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 11 T2 — Canvas Integration Template hook 2: dynamic field persist. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /** Sprint 3 Track-F: unified cross-business link service (double-write w/ sourceOrderId). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    // Bug 4 fix (2026-07): 详情页名称解析 (fool-proof-design Rule 2 — 上下文必带身份信息).
    // ReturnOrder 只存 counterpartyId/sourceOrderId/approvedBy(userId), 详情页此前直接显示
    // 原始 UUID/user id。required=false 兼容既有单元测试 mock 场景 (无这些 repo 时优雅降级为
    // 不 enrich, 而非 NPE — fail-soft, 因为名称展示不是退货单状态机的关键路径).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.CustomerRepository customerRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SupplierRepository supplierRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.SalesOrderRepository salesOrderRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    public ReturnOrderServiceImpl(ReturnOrderRepository returnOrderRepository,
                                   ReturnOrderItemRepository returnOrderItemRepository,
                                   ArApService arApService,
                                   ApplicationEventPublisher applicationEventPublisher) {
        this.returnOrderRepository = returnOrderRepository;
        this.returnOrderItemRepository = returnOrderItemRepository;
        this.arApService = arApService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public ReturnOrder createReturnOrder(String factoryId, CreateReturnOrderRequest request, Long userId) {
        ReturnType returnType = ReturnType.valueOf(request.getReturnType());
        PurchaseReturnCapacityService.Preparation purchasePreparation = null;
        if (returnType == ReturnType.PURCHASE_RETURN) {
            if (purchaseReturnCapacityService == null) {
                throw new BusinessException(503, "采购退货容量组件未就绪").withCode("PURCHASE_RETURN_CAPACITY_UNAVAILABLE");
            }
            purchasePreparation = purchaseReturnCapacityService.prepare(factoryId, request, userId);
            if (purchasePreparation.isReplay()) {
                return purchasePreparation.existingOrder();
            }
        }
        Map<Integer, String> sourceBatchIdsByLine = Map.of();
        boolean physicalSalesReturn = returnType == ReturnType.SALES_RETURN
                && (request.getWithGoods() == null || Boolean.TRUE.equals(request.getWithGoods()));

        if (physicalSalesReturn && (request.getSourceOrderId() == null || request.getSourceOrderId().isBlank())) {
            throw salesReturnEvidenceError("销售实物退货必须关联源销售订单",
                    "请提供真实销售订单号后再登记实物退货；无货退款不需要入库证据");
        }
        if (physicalSalesReturn) {
            sourceBatchIdsByLine = deriveOrValidateSalesReturnBatchEvidence(factoryId, request, null);
        }

        // Round 11 T2: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "sales_return", "CREATE",
                        java.util.Map.of(
                            "returnType", returnType.name(),
                            "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                            "counterpartyId", request.getCounterpartyId() != null ? request.getCounterpartyId() : ""));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        String returnNumber = generateReturnNumber(factoryId, returnType);

        ReturnOrder order = new ReturnOrder();
        if (purchasePreparation != null && purchasePreparation.deterministicId() != null) {
            order.setId(purchasePreparation.deterministicId());
        }
        order.setFactoryId(factoryId);
        order.setReturnNumber(returnNumber);
        order.setReturnType(returnType);
        order.setStatus(ReturnOrderStatus.DRAFT);
        order.setCounterpartyId(request.getCounterpartyId());
        order.setSourceOrderId(request.getSourceOrderId());
        order.setReturnDate(request.getReturnDate());
        order.setReason(request.getReason());
        order.setRemark(request.getRemark());
        // T-RTA business logic (issue #571): default TRUE (实物退货, customer's primary case).
        // Caller may explicitly set false for refund-only path.
        order.setWithGoods(request.getWithGoods() != null ? request.getWithGoods() : Boolean.TRUE);
        order.setCreatedBy(userId);

        if (purchasePreparation != null && purchasePreparation.deterministicId() != null) {
            if (entityManager == null) {
                throw new BusinessException(503, "采购退货幂等持久化组件未就绪")
                        .withCode("PURCHASE_RETURN_IDEMPOTENCY_PERSIST_UNAVAILABLE");
            }
            // A retry key owns this primary key. persist() is deliberate: save() may
            // choose merge() for a non-null id and overwrite a concurrent row.
            entityManager.persist(order);
            entityManager.flush();
        } else {
            order = returnOrderRepository.save(order);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ReturnOrderItem> items = new ArrayList<>();

        for (int itemIndex = 0; itemIndex < request.getItems().size(); itemIndex++) {
            CreateReturnOrderRequest.ReturnOrderItemDTO itemDTO = request.getItems().get(itemIndex);
            ReturnOrderItem item = new ReturnOrderItem();
            item.setReturnOrderId(order.getId());
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            item.setItemName(itemDTO.getItemName());
            item.setQuantity(itemDTO.getQuantity());
            item.setUnitPrice(itemDTO.getUnitPrice());
            item.setBatchNumber(itemDTO.getBatchNumber());
            item.setReason(itemDTO.getReason());

            BigDecimal lineAmount = BigDecimal.ZERO;
            if (itemDTO.getUnitPrice() != null && itemDTO.getQuantity() != null) {
                lineAmount = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                        .setScale(2, BigDecimal.ROUND_HALF_UP);
            }
            item.setLineAmount(lineAmount);
            item.setSourceFinishedGoodsBatchId(sourceBatchIdsByLine.get(itemIndex));
            items.add(item);
            totalAmount = totalAmount.add(lineAmount);
        }

        returnOrderItemRepository.saveAll(items);
        order.setTotalAmount(totalAmount);
        if (purchasePreparation != null && purchasePreparation.deterministicId() != null) {
            entityManager.flush();
        } else {
            order = returnOrderRepository.save(order);
        }

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink double-write.
        // ReturnOrder.sourceOrderId stays for backward compat; new code reads via
        // LinkArrayService.getOutboundLinks(RETURN_ORDER, id).
        if (linkArrayService != null && order.getSourceOrderId() != null && !order.getSourceOrderId().isBlank()) {
            String targetType = returnType == ReturnType.SALES_RETURN ? "SALES_ORDER" : "PURCHASE_ORDER";
            String linkType = returnType == ReturnType.SALES_RETURN ? "sale" : "free";
            try {
                linkArrayService.link(factoryId,
                        "RETURN_ORDER", order.getId(),
                        linkType,
                        targetType, order.getSourceOrderId(),
                        "退货源单", userId != null ? userId.toString() : null);
            } catch (Exception e) {
                log.warn("BusinessLink double-write failed for return order {}: {}", order.getId(), e.getMessage());
            }
        }

        // Round 11 T2: Canvas Integration Template hook 2 — persist dynamic fields.
        // Customer-configured fields (退货照片, 客诉单号, 退货责任方) land in cf_*
        // columns on return_orders. Silent failure must not break the return creation.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_return", order.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for return order {}: {}", order.getId(), e.getMessage());
            }
        }

        // Round 11 T2: Canvas Integration Template hook 3 — publish event for trigger chains.
        // Fires on DRAFT creation of both SALES_RETURN and PURCHASE_RETURN. Factories
        // can now react via configured trigger chains (e.g., "大额退货通知财务").
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.ReturnOrderCreatedEvent(
                    this, factoryId, order.getId(), order.getReturnNumber(),
                    returnType.name(), order.getCounterpartyId(),
                    order.getSourceOrderId(), order.getTotalAmount()));
        } catch (Exception e) {
            log.warn("Publish ReturnOrderCreatedEvent failed for {}: {}", order.getId(), e.getMessage());
        }

        log.info("创建退货单: factoryId={}, returnNumber={}, type={}, items={}",
                factoryId, returnNumber, returnType, items.size());
        return order;
    }

    /**
     * Preserve the ordinary sales-order return入口 while keeping physical returns tied to
     * actual shipped stock. The web client historically omitted batchNumber; only a unique
     * allocation for one unique sales-order line may fill it. An explicitly supplied batch
     * still has to be present in a shipped/delivered allocation for this order and line.
     */
    private Map<Integer, String> deriveOrValidateSalesReturnBatchEvidence(
            String factoryId, CreateReturnOrderRequest request, String excludedReturnOrderId) {
        if (salesOrderItemRepository == null || salesDeliveryRecordRepository == null
                || salesDeliveryItemBatchAllocationRepository == null || finishedGoodsBatchRepository == null) {
            throw salesReturnEvidenceError("当前销售订单缺少可核验的实际发货批次记录",
                    "请打开销售发货批次明细后按实际批次分别登记退货");
        }
        List<SalesOrderItem> orderLines = salesOrderItemRepository
                .findBySalesOrderIdAndSalesOrderFactoryId(request.getSourceOrderId(), factoryId);
        if (orderLines == null) orderLines = List.of();
        Map<Integer, String> sourceBatchIdsByLine = new LinkedHashMap<>();
        Map<Integer, SalesReturnBatchEvidence> evidenceByLine = new LinkedHashMap<>();
        Set<String> sourceBatchIdsToLock = new TreeSet<>();
        String requestedShipmentId = shipmentIdFromRemark(request.getRemark());
        if (containsShipmentMarker(request.getRemark()) && isBlank(requestedShipmentId)) {
            throw salesReturnEvidenceError("销售退货发货单关联凭据无效",
                    "请提供完整的 SHIPMENT_ID，并确保它属于当前销售订单的已发货单");
        }
        for (int itemIndex = 0; itemIndex < request.getItems().size(); itemIndex++) {
            CreateReturnOrderRequest.ReturnOrderItemDTO item = request.getItems().get(itemIndex);
            if (item == null || item.getProductTypeId() == null || item.getProductTypeId().isBlank()) {
                continue;
            }
            List<SalesOrderItem> matchingLines = orderLines.stream()
                    .filter(line -> line != null
                            && Objects.equals(item.getProductTypeId(), line.getProductTypeId()))
                    .toList();
            if (matchingLines.size() != 1) {
                throw salesReturnEvidenceError("销售退货 SKU 对应的订单行不唯一，无法安全归属批次",
                        "请按实际发货批次分别登记退货，并先拆分相同 SKU 的订单行");
            }
            SalesOrderItem orderLine = matchingLines.get(0);
            List<SalesDeliveryItem> shippedItems = shippedItemsForOrderLine(
                    factoryId, request.getSourceOrderId(), orderLine);
            List<SalesDeliveryItemBatchAllocation> allocations = new ArrayList<>();
            Map<String, String> shipmentIdByDeliveryItemId = new HashMap<>();
            for (SalesDeliveryItem deliveryItem : shippedItems) {
                if (deliveryItem == null || deliveryItem.getId() == null) continue;
                String deliveryItemId = String.valueOf(deliveryItem.getId());
                shipmentIdByDeliveryItemId.put(deliveryItemId, trimToNull(deliveryItem.getDeliveryRecordId()));
                List<SalesDeliveryItemBatchAllocation> rows =
                        salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndDeliveryItemId(
                                factoryId, deliveryItemId);
                if (rows != null) {
                    rows.stream()
                            .filter(row -> row != null
                                    && Objects.equals(factoryId, row.getFactoryId())
                                    && Objects.equals(deliveryItemId,
                                            trimToNull(row.getDeliveryItemId())))
                            .forEach(allocations::add);
                }
            }
            String requestedBatch = trimToNull(item.getBatchNumber());
            List<SalesDeliveryItemBatchAllocation> candidates = allocations.stream()
                    .filter(allocation -> allocation != null
                            && allocation.getAllocatedQty() != null
                            && allocation.getAllocatedQty().signum() > 0
                            && !isBlank(allocation.getFinishedGoodsBatchId())
                            && !isBlank(allocation.getBatchNumber())
                            && (requestedBatch == null
                                || requestedBatch.equals(allocation.getBatchNumber().trim())))
                    .toList();
            if (candidates.isEmpty()) {
                throw salesReturnEvidenceError("销售退货没有对应的已发货成品批次: " + item.getProductTypeId(),
                        "请按实际发货批次分别登记退货；当前订单没有可核验的 SHIPPED/DELIVERED 批次");
            }
            Set<String> candidateShipmentIds = candidates.stream()
                    .map(allocation -> shipmentIdByDeliveryItemId.get(trimToNull(allocation.getDeliveryItemId())))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (candidateShipmentIds.stream().anyMatch(ReturnOrderServiceImpl::isBlank)
                    || candidateShipmentIds.isEmpty()) {
                throw salesReturnEvidenceError("销售退货缺少可核验的实际发货单据身份",
                        "请使用真实 SHIPPED/DELIVERED 发货单后再登记退货");
            }
            if (!isBlank(requestedShipmentId)) {
                if (!candidateShipmentIds.contains(requestedShipmentId)) {
                    throw salesReturnEvidenceError("销售退货关联的 shipment 不属于当前订单或未实际发货",
                            "请按当前销售订单实际 SHIPPED/DELIVERED 发货单登记退货");
                }
                candidates = candidates.stream()
                        .filter(allocation -> Objects.equals(requestedShipmentId,
                                shipmentIdByDeliveryItemId.get(trimToNull(allocation.getDeliveryItemId()))))
                        .toList();
            } else if (candidateShipmentIds.size() > 1) {
                throw salesReturnEvidenceError("同一销售订单存在多个实际发货 shipment，不能自动猜分配",
                        "请提供真实 SHIPMENT_ID 后按发货单分别登记退货");
            }
            Map<String, List<SalesDeliveryItemBatchAllocation>> byBatch = candidates.stream()
                    .collect(Collectors.groupingBy(a -> a.getFinishedGoodsBatchId().trim(), LinkedHashMap::new,
                            Collectors.toList()));
            if (requestedBatch == null && byBatch.size() != 1) {
                throw salesReturnEvidenceError("销售退货对应多个实际成品批次，不能自动猜批次",
                        "请按实际发货批次分别登记退货，每个批次单独提交");
            }
            if (byBatch.size() != 1) {
                throw salesReturnEvidenceError("销售退货批次证据不唯一，无法核验指定批次",
                        "请按实际发货批次分别登记退货");
            }
            List<SalesDeliveryItemBatchAllocation> selected = byBatch.values().iterator().next();
            String batchId = byBatch.keySet().iterator().next();
            evidenceByLine.put(itemIndex, new SalesReturnBatchEvidence(
                    itemIndex, item, orderLine, selected, batchId, requestedShipmentId, candidateShipmentIds));
            sourceBatchIdsToLock.add(batchId);
        }

        // Lock every distinct source batch in stable ID order before validating any
        // line. This keeps reverse client line order from acquiring the same locks in
        // opposite order during concurrent create/complete requests.
        Map<String, FinishedGoodsBatch> lockedSources = new LinkedHashMap<>();
        for (String batchId : sourceBatchIdsToLock) {
            FinishedGoodsBatch source = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> salesReturnEvidenceError("销售退货原成品批次不存在或不属于当前工厂",
                            "请按实际发货批次分别登记退货"));
            lockedSources.put(batchId, source);
        }

        for (SalesReturnBatchEvidence evidence : evidenceByLine.values()) {
            int itemIndex = evidence.itemIndex();
            CreateReturnOrderRequest.ReturnOrderItemDTO item = evidence.item();
            SalesOrderItem orderLine = evidence.orderLine();
            List<SalesDeliveryItemBatchAllocation> selected = evidence.selected();
            String batchId = evidence.batchId();
            String requestedShipmentIdForLine = evidence.requestedShipmentId();
            Set<String> candidateShipmentIds = evidence.candidateShipmentIds();
            FinishedGoodsBatch source = lockedSources.get(batchId);
            String orderUnit = trimToNull(orderLine.getUnit());
            String sourceUnit = trimToNull(source.getUnit());
            if (!Objects.equals(batchId, source.getId())
                    || !Objects.equals(factoryId, source.getFactoryId())
                    || !Objects.equals(item.getProductTypeId(), trimToNull(source.getProductTypeId()))
                    || isBlank(source.getBatchNumber())) {
                throw salesReturnEvidenceError("销售退货实际发货批次与当前工厂或 SKU 不一致",
                        "请按实际发货批次分别登记退货");
            }
            for (SalesDeliveryItemBatchAllocation allocation : selected) {
                if (!Objects.equals(trimToNull(source.getBatchNumber()), trimToNull(allocation.getBatchNumber()))
                        || !unitsEquivalent(factoryId, orderUnit, allocation.getUnit())
                        || !unitsEquivalent(factoryId, orderUnit, sourceUnit)) {
                    throw salesReturnEvidenceError("销售退货订单单位与实际发货/源批次单位不一致",
                            "请按实际发货批次分别登记退货；系统不会把盒直接当作 kg");
                }
            }
            BigDecimal shippedQuantity = selected.stream()
                    .map(SalesDeliveryItemBatchAllocation::getAllocatedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal alreadyReturned = existingSalesReturnQuantity(
                    factoryId, request.getSourceOrderId(), item.getProductTypeId(),
                    trimToNull(source.getBatchNumber()), excludedReturnOrderId,
                    requestedShipmentIdForLine, candidateShipmentIds);
            BigDecimal requestedQuantity = requestedQuantityForBatch(
                    request, item.getProductTypeId(), trimToNull(source.getBatchNumber()));
            if (requestedQuantity.signum() <= 0
                    || requestedQuantity.add(alreadyReturned).compareTo(shippedQuantity) > 0) {
                throw salesReturnEvidenceError("销售退货数量超过该实际发货批次可退上限",
                        "请减少退货数量，或按实际发货批次分别登记退货");
            }
            item.setBatchNumber(trimToNull(source.getBatchNumber()));
            sourceBatchIdsByLine.put(itemIndex, source.getId());
        }
        return sourceBatchIdsByLine;
    }

    private record SalesReturnBatchEvidence(
            int itemIndex,
            CreateReturnOrderRequest.ReturnOrderItemDTO item,
            SalesOrderItem orderLine,
            List<SalesDeliveryItemBatchAllocation> selected,
            String batchId,
            String requestedShipmentId,
            Set<String> candidateShipmentIds) {
    }

    private BigDecimal requestedQuantityForBatch(
            CreateReturnOrderRequest request, String productTypeId, String batchNumber) {
        BigDecimal total = BigDecimal.ZERO;
        for (CreateReturnOrderRequest.ReturnOrderItemDTO line : request.getItems()) {
            if (line == null || !Objects.equals(productTypeId, line.getProductTypeId())
                    || line.getQuantity() == null) continue;
            String requestedBatch = trimToNull(line.getBatchNumber());
            if (requestedBatch == null || Objects.equals(batchNumber, requestedBatch)) {
                total = total.add(line.getQuantity());
            }
        }
        return total;
    }

    private void validateExistingSalesReturnShipmentEvidence(String factoryId, ReturnOrder order) {
        if (isBlank(order.getSourceOrderId())) {
            throw salesReturnEvidenceError("销售实物退货必须关联源销售订单",
                    "历史退货单缺少真实源销售订单，不能完成入库");
        }
        if (order.getItems() == null) return;
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.SALES_RETURN.name());
        request.setSourceOrderId(order.getSourceOrderId());
        request.setWithGoods(true);
        request.setRemark(order.getRemark());
        List<CreateReturnOrderRequest.ReturnOrderItemDTO> lines = new ArrayList<>();
        for (int index = 0; index < order.getItems().size(); index++) {
            ReturnOrderItem item = order.getItems().get(index);
            if (item == null) continue;
            if (isBlank(item.getProductTypeId()) || item.getQuantity() == null
                    || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || isBlank(item.getBatchNumber())) {
                throw new BusinessException(409, "销售实物退货第 " + (index + 1) + " 行缺少完整源批次证据")
                        .withCode("SALES_RETURN_SOURCE_BATCH_REQUIRED")
                        .withHint("必须提供原成品批号、产品 SKU 和大于 0 的退货数量，系统不会猜测单位或批次");
            }
            CreateReturnOrderRequest.ReturnOrderItemDTO line =
                    new CreateReturnOrderRequest.ReturnOrderItemDTO();
            line.setProductTypeId(item.getProductTypeId());
            line.setQuantity(item.getQuantity());
            line.setBatchNumber(item.getBatchNumber());
            lines.add(line);
        }
        request.setItems(lines);
        deriveOrValidateSalesReturnBatchEvidence(factoryId, request, order.getId());
    }

    private List<SalesDeliveryItem> shippedItemsForOrderLine(
            String factoryId, String sourceOrderId, SalesOrderItem orderLine) {
        List<SalesDeliveryRecord> deliveries = salesDeliveryRecordRepository.findBySalesOrderId(sourceOrderId);
        if (deliveries == null) return List.of();
        List<SalesDeliveryItem> result = new ArrayList<>();
        for (SalesDeliveryRecord delivery : deliveries) {
            if (delivery == null || !Objects.equals(factoryId, delivery.getFactoryId())
                    || !Objects.equals(sourceOrderId, trimToNull(delivery.getSalesOrderId()))
                    || (delivery.getStatus() != SalesDeliveryStatus.SHIPPED
                        && delivery.getStatus() != SalesDeliveryStatus.DELIVERED)) continue;
            List<SalesDeliveryItem> items = delivery.getItems() == null
                    ? List.of() : delivery.getItems();
            for (SalesDeliveryItem item : items) {
                if (item == null || !Objects.equals(orderLine.getProductTypeId(), item.getProductTypeId())) continue;
                if (isBlank(delivery.getId())
                        || !Objects.equals(trimToNull(delivery.getId()), trimToNull(item.getDeliveryRecordId()))) continue;
                if (orderLine.getId() != null && Objects.equals(orderLine.getId(), item.getSalesOrderItemId())) {
                    result.add(item);
                } else if (orderLine.getId() == null && item.getSalesOrderItemId() == null) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private BigDecimal existingSalesReturnQuantity(
            String factoryId, String sourceOrderId, String productTypeId, String batchNumber,
            String excludedReturnOrderId, String requestedShipmentId, Set<String> candidateShipmentIds) {
        List<ReturnOrder> existing = returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.SALES_RETURN, sourceOrderId);
        BigDecimal total = BigDecimal.ZERO;
        boolean shipmentAmbiguous = candidateShipmentIds != null && candidateShipmentIds.size() > 1;
        for (ReturnOrder order : existing == null ? List.<ReturnOrder>of() : existing) {
            if (order == null || order.getStatus() == ReturnOrderStatus.REJECTED
                    || Objects.equals(excludedReturnOrderId, order.getId())
                    || order.getItems() == null) continue;
            if (Boolean.FALSE.equals(order.getWithGoods())) continue;
            List<ReturnOrderItem> matchingItems = order.getItems().stream()
                    .filter(item -> item != null && Objects.equals(productTypeId, item.getProductTypeId())
                            && Objects.equals(batchNumber, trimToNull(item.getBatchNumber())))
                    .toList();
            if (matchingItems.isEmpty()) continue;
            String existingShipmentId = shipmentIdFromRemark(order.getRemark());
            if (containsShipmentMarker(order.getRemark()) && isBlank(existingShipmentId)) {
                throw salesReturnEvidenceError("已有销售实物退货缺少可核验的发货单关联",
                        "请先补齐每个实物退货对应的真实 SHIPMENT_ID");
            }
            if (!isBlank(existingShipmentId)
                    && (candidateShipmentIds == null || !candidateShipmentIds.contains(existingShipmentId))) {
                throw salesReturnEvidenceError("已有销售实物退货的 shipment 关联无法核验",
                        "退货累计只能基于当前订单实际发货单计算");
            }
            if (shipmentAmbiguous && isBlank(existingShipmentId)) {
                throw salesReturnEvidenceError("已有销售实物退货未关联具体 shipment，无法安全累计",
                        "请先为历史实物退货补充真实 SHIPMENT_ID");
            }
            if (!isBlank(requestedShipmentId) && !isBlank(existingShipmentId)
                    && !Objects.equals(requestedShipmentId, existingShipmentId)) continue;
            for (ReturnOrderItem item : matchingItems) {
                total = total.add(item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity());
            }
        }
        return total;
    }

    private static boolean containsShipmentMarker(String remark) {
        return remark != null && remark.contains("SHIPMENT_ID=");
    }

    private static String shipmentIdFromRemark(String remark) {
        if (!containsShipmentMarker(remark)) return null;
        int start = remark.indexOf("SHIPMENT_ID=") + "SHIPMENT_ID=".length();
        int end = remark.indexOf(';', start);
        if (end < 0) return null;
        return trimToNull(remark.substring(start, end));
    }

    private boolean unitsEquivalent(String factoryId, String left, String right) {
        if (left == null || right == null) return false;
        String canonicalLeft = unitContractService == null
                ? left : trimToNull(unitContractService.storageUnit(factoryId, left));
        String canonicalRight = unitContractService == null
                ? right : trimToNull(unitContractService.storageUnit(factoryId, right));
        return canonicalLeft != null && Objects.equals(canonicalLeft, canonicalRight);
    }

    private BusinessException salesReturnEvidenceError(String message, String hint) {
        return new BusinessException(409, message)
                .withCode("SALES_RETURN_SHIPMENT_EVIDENCE_REQUIRED")
                .withHint(hint);
    }

    @Override
    public ReturnOrder getReturnOrderById(String factoryId, String returnOrderId) {
        ReturnOrder order = returnOrderRepository.findById(returnOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("退货单不存在"));
        if (!order.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该退货单")
                    .withHint("当前退货单不属于该工厂, 无法访问");
        }
        DataScopeResolver.requireRecordAccess(dataScopeResolver, factoryId, order.getCreatedBy(), "return_orders");
        return order;
    }

    /**
     * Bug 4 fix (2026-07): 详情页专用 — getReturnOrderById 的结果基础上 enrich 只读展示字段
     * (对方名称/源销售单号/审批人姓名), 供 ReturnOrderController#getReturnOrder 使用。
     * 不影响 getReturnOrderById 本身 (状态机 transition 方法仍走原始无 enrich 版本, 避免给
     * 写路径徒增查询)。任一 enrich 查询失败 fail-soft (log only) —— 名称展示不阻断详情页加载。
     */
    @Override
    public ReturnOrder getReturnOrderDetail(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        try {
            if (order.getCounterpartyId() != null) {
                if (order.getReturnType() == ReturnType.SALES_RETURN && customerRepository != null) {
                    customerRepository.findById(order.getCounterpartyId())
                            .ifPresent(c -> order.setCounterpartyName(c.getName()));
                } else if (order.getReturnType() == ReturnType.PURCHASE_RETURN && supplierRepository != null) {
                    supplierRepository.findById(order.getCounterpartyId())
                            .ifPresent(s -> order.setCounterpartyName(s.getName()));
                }
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 对方名称失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        try {
            if (order.getSourceOrderId() != null && salesOrderRepository != null) {
                salesOrderRepository.findById(order.getSourceOrderId())
                        .ifPresent(so -> order.setSourceOrderNumber(so.getOrderNumber()));
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 源订单号失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        try {
            if (order.getApprovedBy() != null && userRepository != null) {
                userRepository.findById(order.getApprovedBy())
                        .ifPresent(u -> order.setApprovedByName(
                                u.getFullName() != null && !u.getFullName().isBlank()
                                        ? u.getFullName() : u.getUsername()));
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 审批人姓名失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        return order;
    }

    @Override
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<ReturnOrder> getReturnOrders(String factoryId, ReturnType returnType,
                                                      ReturnOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Sprint 6 W2-B: 应用 DataScope. 走 unified findByFactoryIdAndFiltersAndCreatedByIn
        // 当 scope 是 SELF / SELF_AND_BELOW / DEPT_AND_BELOW (DB-side IN list).
        // ALL scope 保持原 4-branch 行为.
        DataScopeContext dsCtx = DataScopeContext.current();
        if (dsCtx != null && dsCtx.isFiltered() && dsCtx.getUserId() != null) {
            com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
            List<Long> chain;
            if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
                chain = List.of(dsCtx.getUserId());
            } else if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                    || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
                chain = dataScopeResolver != null
                        ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                        : List.of(dsCtx.getUserId());
                if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
            } else {
                chain = null; // CUSTOM — fallback ALL
            }
            if (chain != null) {
                log.debug("DataScope {} for return orders: chain size={}", scope, chain.size());
                Page<ReturnOrder> result = returnOrderRepository.findByFactoryIdAndFiltersAndCreatedByIn(
                        factoryId, returnType, status, chain, pageRequest);
                List<ReturnOrder> scoped = result.getContent();
                enrichForDisplay(scoped);
                return PageResponse.of(scoped, page, size, result.getTotalElements());
            }
        }

        Page<ReturnOrder> result;
        if (returnType != null && status != null) {
            result = returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusOrderByCreatedAtDesc(
                    factoryId, returnType, status, pageRequest);
        } else if (returnType != null) {
            result = returnOrderRepository.findByFactoryIdAndReturnTypeOrderByCreatedAtDesc(
                    factoryId, returnType, pageRequest);
        } else if (status != null) {
            result = returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    factoryId, status, pageRequest);
        } else {
            result = returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }

        List<ReturnOrder> rows = result.getContent();
        enrichForDisplay(rows);
        return PageResponse.of(rows, page, size, result.getTotalElements());
    }

    /**
     * 列表页的展示字段 enrich（对方名称 / 源销售单号 / 审批人姓名）。
     *
     * <h4>🔴 为什么需要它（2026-08-28 生产实测）</h4>
     *
     * 「销售退货」列表把**客户**和**源订单**两列渲染成裸 UUID：
     * <pre>
     *   客户      3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e
     *   源订单    89967b19-e5a6-447c-bf88-5b2c073a545c
     * </pre>
     *
     * 前端**没写错** —— 它写的是 {@code counterpartyName || customerName || counterpartyId}，
     * 只是后端把 {@code counterpartyName} / {@code sourceOrderNumber} 返成了 {@code null}，
     * 回退链只好落到 ID。
     *
     * 成因是「机制在、没接上」：{@code getReturnOrderDetail} 早就会 enrich 这三个字段
     * （见它上面那段 Bug 4 fix 注释），而**列表这条路从来不调它** ——
     * 详情页看得到名字，列表页看不到。
     *
     * <h4>⚠️ 必须批量，⛔ 不能逐行 findById</h4>
     *
     * 详情页一次只处理一单，逐个 {@code findById} 无所谓；列表一页 200 行就是 600 次查询。
     * 这里按 id 去重后一次 {@code findAllById}，每类最多一次查询。
     *
     * <h4>fail-soft</h4>
     *
     * 与详情页同一纪律：任一 enrich 失败只 log，⛔ 不阻断列表加载 ——
     * 名称展示不是退货单状态机的关键路径。
     */
    private void enrichForDisplay(List<ReturnOrder> orders) {
        if (orders == null || orders.isEmpty()) return;

        try {
            java.util.Set<String> customerIds = new java.util.HashSet<>();
            java.util.Set<String> supplierIds = new java.util.HashSet<>();
            for (ReturnOrder o : orders) {
                if (o.getCounterpartyId() == null) continue;
                if (o.getReturnType() == ReturnType.SALES_RETURN) customerIds.add(o.getCounterpartyId());
                else if (o.getReturnType() == ReturnType.PURCHASE_RETURN) supplierIds.add(o.getCounterpartyId());
            }
            java.util.Map<String, String> nameById = new java.util.HashMap<>();
            if (!customerIds.isEmpty() && customerRepository != null) {
                customerRepository.findAllById(customerIds)
                        .forEach(c -> nameById.put(c.getId(), c.getName()));
            }
            if (!supplierIds.isEmpty() && supplierRepository != null) {
                supplierRepository.findAllById(supplierIds)
                        .forEach(s -> nameById.put(s.getId(), s.getName()));
            }
            for (ReturnOrder o : orders) {
                if (o.getCounterpartyId() != null) {
                    o.setCounterpartyName(nameById.get(o.getCounterpartyId()));
                }
            }
        } catch (Exception e) {
            log.warn("退货单列表 enrich 对方名称失败 (不阻断列表加载): count={}", orders.size(), e);
        }

        try {
            java.util.Set<String> sourceOrderIds = new java.util.HashSet<>();
            for (ReturnOrder o : orders) {
                if (o.getSourceOrderId() != null) sourceOrderIds.add(o.getSourceOrderId());
            }
            if (!sourceOrderIds.isEmpty() && salesOrderRepository != null) {
                java.util.Map<String, String> numberById = new java.util.HashMap<>();
                salesOrderRepository.findAllById(sourceOrderIds)
                        .forEach(so -> numberById.put(so.getId(), so.getOrderNumber()));
                for (ReturnOrder o : orders) {
                    if (o.getSourceOrderId() != null) {
                        o.setSourceOrderNumber(numberById.get(o.getSourceOrderId()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("退货单列表 enrich 源订单号失败 (不阻断列表加载): count={}", orders.size(), e);
        }

        try {
            java.util.Set<Long> approverIds = new java.util.HashSet<>();
            for (ReturnOrder o : orders) {
                if (o.getApprovedBy() != null) approverIds.add(o.getApprovedBy());
            }
            if (!approverIds.isEmpty() && userRepository != null) {
                java.util.Map<Long, String> approverById = new java.util.HashMap<>();
                userRepository.findAllById(approverIds).forEach(u -> approverById.put(
                        u.getId(),
                        u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername()));
                for (ReturnOrder o : orders) {
                    if (o.getApprovedBy() != null) {
                        o.setApprovedByName(approverById.get(o.getApprovedBy()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("退货单列表 enrich 审批人姓名失败 (不阻断列表加载): count={}", orders.size(), e);
        }
    }

    @Override
    @Transactional
    public ReturnOrder submitReturnOrder(String factoryId, String returnOrderId) {
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的退货单可以提交")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.SUBMITTED);
        log.info("提交退货单: returnOrderId={}, returnNumber={}", returnOrderId, order.getReturnNumber());
        return returnOrderRepository.save(order);
    }

    @Override
    @Transactional
    public ReturnOrder approveReturnOrder(String factoryId, String returnOrderId, Long approverId) {
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的退货单可以审批")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.APPROVED);
        order.setApprovedBy(approverId);
        order.setApprovedAt(LocalDateTime.now());

        // T-RTA business logic (issue #571, audit BLOCKER B1): branch by withGoods.
        //
        // withGoods=true  (实物退货): defer AR/AP adjustment to completeReturnOrder. Warehouse
        //                            keeper must physically receive goods first; financial
        //                            credit happens when 不良品 inbound is confirmed.
        // withGoods=false (退款 only): trigger AR/AP adjustment now (existing behavior). No
        //                            inventory action needed since no physical goods involved.
        //
        // Customer transcript (第四次:956-1037): "有食物的话, 库存入库到总仓 ... 无食物的话就退款"
        // — the order of operations matters because real-world workflow can't credit before
        // verifying physical receipt (would create accounting/audit gap).
        boolean withGoods = Boolean.TRUE.equals(order.getWithGoods());
        if (!withGoods) {
            // No-goods path: AR/AP adjustment immediately on approve (current behavior).
            try {
                if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
                    // #3(a): link adjustment → 退货单 (sourceType/sourceId) so financeReject can cascade-cancel it.
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "采购退货冲减(无货)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    log.info("采购退货冲减应付(无货, 审批立即触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "销售退货冲减(无货)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    log.info("销售退货冲减应收(无货, 审批立即触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                }
            } catch (Exception e) {
                log.error("退货AR/AP冲减失败 (无货path): returnOrderId={}", returnOrderId, e);
            }
        } else {
            // With-goods path: AR/AP冲减 deferred until 仓库实物入库 in completeReturnOrder.
            log.info("审批退货单(有货): returnOrderId={} — AR冲减 + 库存入库 deferred to completion", returnOrderId);
        }

        log.info("审批退货单: returnOrderId={}, approvedBy={}, withGoods={}", returnOrderId, approverId, withGoods);
        return returnOrderRepository.save(order);
    }

    /**
     * Resolve every physical sales-return line against its immutable source FG batch
     * before AR/AP or any new inbound batch is written.  A current product catalog
     * unit is deliberately not consulted: the source batch is the historical unit
     * authority for the returned goods.
     */
    private List<ResolvedSalesReturnLine> resolveSalesReturnSources(String factoryId, ReturnOrder order) {
        if (finishedGoodsBatchRepository == null || warehouseResolver == null) {
            throw new BusinessException(500, "退货完成失败: 成品源批次校验依赖未初始化")
                    .withHint("请联系管理员检查成品批次与成品仓配置后重试");
        }
        String finishedGoodsWarehouseId = warehouseResolver.resolveFinishedGoodsId(factoryId);
        if (finishedGoodsWarehouseId == null || finishedGoodsWarehouseId.isBlank()) {
            throw new BusinessException(409, "退货成品仓不存在，不能完成实物退货")
                    .withHint("请先配置当前工厂的成品仓");
        }
        List<ReturnOrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(409, "销售实物退货缺少退货行，不能完成")
                    .withHint("请补齐原销售成品批次、产品和数量后重试");
        }
        List<ResolvedSalesReturnLine> resolved = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            final int lineNumber = index + 1;
            ReturnOrderItem item = items.get(index);
            if (item == null || isBlank(item.getProductTypeId())
                    || item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || isBlank(item.getBatchNumber())) {
                throw new BusinessException(409, "销售实物退货第 " + lineNumber + " 行缺少完整源批次证据")
                        .withCode("SALES_RETURN_SOURCE_BATCH_REQUIRED")
                        .withHint("必须提供原成品批号、产品 SKU 和大于 0 的退货数量，系统不会猜测单位或批次");
            }
            String sourceBatchNumber = item.getBatchNumber().trim();
            FinishedGoodsBatch source = finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumberForUpdate(factoryId, sourceBatchNumber)
                    .orElseThrow(() -> new BusinessException(409,
                            "销售实物退货第 " + lineNumber + " 行找不到原成品批次: " + sourceBatchNumber)
                            .withCode("SALES_RETURN_SOURCE_BATCH_NOT_FOUND")
                            .withHint("请核对原销售出库批号和当前工厂，不要改用其他批次代替"));
            if (!Objects.equals(factoryId, source.getFactoryId())
                    || !Objects.equals(sourceBatchNumber, trimToNull(source.getBatchNumber()))) {
                throw new BusinessException(409, "销售实物退货原成品批次不属于当前工厂或批号不一致")
                        .withCode("SALES_RETURN_SOURCE_BATCH_SCOPE_MISMATCH")
                        .withHint("请核对原批次归属工厂和批号");
            }
            if (!Objects.equals(item.getProductTypeId().trim(), trimToNull(source.getProductTypeId()))) {
                throw new BusinessException(409, "销售实物退货原成品批次与退货 SKU 不一致")
                        .withCode("SALES_RETURN_SOURCE_PRODUCT_MISMATCH")
                        .withHint("退货行必须引用同一产品的原成品批次，系统不会按当前 SKU 覆盖历史批次");
            }
            String sourceUnit = trimToNull(source.getUnit());
            if (sourceUnit == null) {
                throw new BusinessException(409, "销售实物退货原成品批次缺少不可变单位")
                        .withCode("SALES_RETURN_SOURCE_UNIT_REQUIRED")
                        .withHint("请先补齐原成品批次单位，再完成退货入库");
            }
            if (unitContractService != null
                    && isBlank(unitContractService.storageUnit(factoryId, sourceUnit))) {
                throw new BusinessException(409, "销售实物退货原成品批次单位无法核验")
                        .withCode("SALES_RETURN_SOURCE_UNIT_INVALID")
                        .withHint("请使用原批次的有效计量单位；系统不会回退为件");
            }
            resolved.add(new ResolvedSalesReturnLine(item, source, sourceUnit));
            if (item.getSourceFinishedGoodsBatchId() != null
                    && !Objects.equals(item.getSourceFinishedGoodsBatchId(), source.getId())) {
                throw new BusinessException(409, "销售退货行的原成品批次关联已改变，不能完成")
                        .withCode("SALES_RETURN_SOURCE_BATCH_LINK_MISMATCH")
                        .withHint("请重新打开退货单并核对原成品批次");
            }
        }
        return resolved;
    }

    private record ResolvedSalesReturnLine(ReturnOrderItem item, FinishedGoodsBatch source,
                                           String sourceUnit) {
    }

    /**
     * 六扇门 Tier0 #16 (catalog 行2399-2416): 退货财务审批门。
     * 客户原话: "退货单发财务审批，审批后给仓管把实物拿走" / "退货跟钱有关要先财务审批确认；
     * 跟钱有关的东西都要审批"。
     *
     * <p>状态机: APPROVED → FINANCE_APPROVED。只有 finance:read_write (财务角色) 可调用
     * (Controller 层 @RequirePermission 把关)。财务审批后才能 completeReturnOrder 交仓管出货。
     *
     * <p>幂等/防呆: 非 APPROVED 状态调用 → 409 + 明确 hint。
     */
    @Override
    @Transactional
    public ReturnOrder financeApproveReturnOrder(String factoryId, String returnOrderId, Long financeUserId) {
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.APPROVED) {
            throw new BusinessException(409, "只有已通过业务审批(已审批)的退货单可以提交财务审批")
                    .withHint("当前状态: " + order.getStatus().getDisplayName()
                            + "。退货跟资金相关，需先完成业务审批再由财务审批，请刷新列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
        order.setFinanceApprovedBy(financeUserId);
        order.setFinanceApprovedAt(LocalDateTime.now());
        log.info("财务审批退货单: returnOrderId={}, returnNumber={}, financeApprovedBy={}",
                returnOrderId, order.getReturnNumber(), financeUserId);
        return returnOrderRepository.save(order);
    }

    @Override
    @Transactional
    public ReturnOrder financeRejectReturnOrder(String factoryId, String returnOrderId, Long financeUserId) {
        com.cretas.aims.service.finance.ProductionCostConsistency.lockFactory(entityManager, factoryId);
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.APPROVED) {
            throw new BusinessException(409, "只有已通过业务审批的退货单可以由财务驳回")
                    .withHint("当前状态: " + order.getStatus().getDisplayName()
                            + "。请刷新退货单列表查看最新状态，避免重复审批或越级处理。");
        }
        voidReturnVoucherInTransaction(factoryId, order, financeUserId);
        order.setStatus(ReturnOrderStatus.REJECTED);
        order.setFinanceApprovedBy(financeUserId);
        order.setFinanceApprovedAt(LocalDateTime.now());

        // #3(a): 无货退款单在业务审批时已挂起一条 PENDING AR/AP 冲减 (recordAdjustment)。财务驳回退货单
        // 后, 这条挂起调整必须一并作废, 否则它仍留在审批队列可被第 2 位审批人通过 → 为已驳回退货单变动
        // 客户/供应商余额 (资金泄漏)。系统级联撤销 (非手工 4-眼), 幂等 (有货 path 无挂起调整 → 0 no-op)。
        try {
            int cancelled = arApService.cancelPendingAdjustmentsBySource(factoryId,
                    ADJUSTMENT_SOURCE_TYPE, order.getId(), financeUserId,
                    "退货单财务驳回, 撤销待审批冲减-" + order.getReturnNumber());
            if (cancelled > 0) {
                log.info("财务驳回退货单级联撤销挂起冲减: returnOrderId={}, cancelled={}", returnOrderId, cancelled);
            }
        } catch (Exception e) {
            // 撤销失败必须 fail-loud — 否则一条能被通过的挂起冲减留在队列 = 资金泄漏隐患。
            log.error("财务驳回退货单撤销挂起冲减失败: returnOrderId={}", returnOrderId, e);
            throw new BusinessException(500, "退货单驳回失败: 关联的待审批冲减撤销失败, 请重试")
                    .withHint("为避免已驳回退货单的余额被误变动, 系统未提交驳回。请稍后重试或联系管理员。");
        }

        log.info("财务驳回退货单: returnOrderId={}, returnNumber={}, financeUserId={}",
                returnOrderId, order.getReturnNumber(), financeUserId);
        ReturnOrder saved = returnOrderRepository.save(order);
        publishReturnOrderRejected(factoryId, saved.getId(), financeUserId);
        return saved;
    }

    @Override
    @Transactional
    public ReturnOrder rejectReturnOrder(String factoryId, String returnOrderId, Long actorId) {
        com.cretas.aims.service.finance.ProductionCostConsistency.lockFactory(entityManager, factoryId);
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的退货单可以驳回")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        voidReturnVoucherInTransaction(factoryId, order, actorId);
        order.setStatus(ReturnOrderStatus.REJECTED);
        log.info("驳回退货单: returnOrderId={}, returnNumber={}", returnOrderId, order.getReturnNumber());
        ReturnOrder saved = returnOrderRepository.save(order);
        publishReturnOrderRejected(factoryId, saved.getId(), actorId);
        return saved;
    }

    /**
     * Bug 9 修复 (2026-07-04): 发布退货单驳回事件 → AFTER_COMMIT listener 作废对应 RETURN 凭证。
     * fail-soft: 发布失败不影响驳回主流程。
     */
    private void publishReturnOrderRejected(String factoryId, String returnOrderId, Long actorId) {
        try {
            applicationEventPublisher.publishEvent(
                    new com.cretas.aims.event.ReturnOrderRejectedEvent(this, factoryId, returnOrderId, actorId));
        } catch (Exception e) {
            log.error("发布ReturnOrderRejectedEvent失败: RO={}", returnOrderId, e);
        }
    }

    /**
     * Rejecting a return is a financial mutation.  Keep voucher void/reversal in the
     * same factory-lock then return-row-lock transaction so a period/ledger failure
     * rolls the rejection back as one unit.
     */
    private void voidReturnVoucherInTransaction(String factoryId, ReturnOrder order, Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new BusinessException(401, "请先以工厂账号登录再驳回退货单");
        }
        if (voucherService == null) {
            throw new IllegalStateException("退货驳回缺少凭证服务，拒绝提交财务状态");
        }
        Optional<Voucher> voucher = voucherService.findBySourceBusiness(ADJUSTMENT_SOURCE_TYPE, order.getId());
        if (voucher.isEmpty()) {
            return;
        }
        Voucher current = voucher.get();
        // The source lookup is intentionally not trusted for tenant isolation.  Validate
        // scope and identity before terminal-state idempotency, otherwise a foreign VOID/
        // REVERSED voucher could make a local rejection appear successful.
        if (!Objects.equals(factoryId, current.getFactoryId())
                || !Objects.equals(ADJUSTMENT_SOURCE_TYPE, current.getSourceBusinessType())
                || !Objects.equals(order.getId(), current.getSourceBusinessId())) {
            throw new BusinessException(403, "退货凭证不属于当前工厂或来源单据不匹配")
                    .withCode("RETURN_ORDER_VOUCHER_SCOPE_MISMATCH");
        }
        if (current.getStatus() == VoucherStatus.VOID || current.getStatus() == VoucherStatus.REVERSED) {
            return;
        }
        voucherService.voidVoucher(factoryId, current.getId(), "退货单驳回自动作废", actorId);
    }

    @Override
    @Transactional
    public ReturnOrder completeReturnOrder(String factoryId, String returnOrderId) {
        com.cretas.aims.service.finance.ProductionCostConsistency.lockFactory(entityManager, factoryId);
        ReturnOrder order = loadReturnOrderForWrite(factoryId, returnOrderId);
        // 六扇门 Tier0 #16: 财务审批门 — 退货跟钱有关，完成/出货前必须先经财务审批。
        // 完成的前置状态从 APPROVED 收紧为 FINANCE_APPROVED。防呆: 未财务审批直接完成 → 拒绝 + hint。
        if (order.getStatus() != ReturnOrderStatus.FINANCE_APPROVED) {
            String hint = order.getStatus() == ReturnOrderStatus.APPROVED
                    ? "该退货单已通过业务审批，但尚未经财务审批。退货涉及资金，需财务审批通过后才能交仓管完成/出货"
                    : "请刷新退货单列表查看最新状态";
            throw new BusinessException(409, "只有财务审批通过(财务已审)的退货单可以完成")
                    .withHint(hint);
        }

        // T-RTA business logic (issue #571 Phase B): for withGoods=true, trigger
        // deferred AR/AP冲减 now (after physical receipt confirmed). For withGoods=false,
        // AR/AP冲减 already happened at approve time — just flip status.
        boolean withGoods = Boolean.TRUE.equals(order.getWithGoods());
        if (withGoods) {
            if (order.getReturnDate() == null) throw new BusinessException(409, "实物退货缺少会计日期，请核对后重试")
                    .withHint("请停止完成退货并联系管理员核对原退货日期，确认来源完整且会计期间开放后再继续");
            accountingPeriodService.assertOpen(factoryId, order.getReturnDate().getYear(), order.getReturnDate().getMonthValue());
        }
        List<ResolvedSalesReturnLine> resolvedSalesReturnLines = List.of();
        if (withGoods && order.getReturnType() == ReturnType.SALES_RETURN) {
            validateExistingSalesReturnShipmentEvidence(factoryId, order);
            resolvedSalesReturnLines = resolveSalesReturnSources(factoryId, order);
        }
        if (withGoods) {
            // Deferred AR/AP冲减 from approve.
            // #3(b): 不再吞异常。此前 catch(Exception) log.error 会让"冲减失败"却仍 COMPLETE + 移库存,
            // 客户/供应商余额永远不冲减且无人知晓 (silent finance hole)。改为 fail-loud: 冲减失败整体
            // 回滚 (退货单不 COMPLETED, 库存不动), 让操作员看到明确错误并重试。
            boolean adjustmentRecorded = false;
            try {
                if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "采购退货冲减(实物已入库)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    adjustmentRecorded = true;
                    log.info("采购退货冲减应付(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "销售退货冲减(实物已入库)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    adjustmentRecorded = true;
                    log.info("销售退货冲减应收(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                }
            } catch (BusinessException e) {
                throw e; // 明确业务错误原样上抛 (客户/供应商不存在等), 保留 message + hint。
            } catch (Exception e) {
                log.error("退货AR/AP冲减失败 (有货 completion path, fail-loud 回滚): returnOrderId={}", returnOrderId, e);
                throw new BusinessException(500, "退货完成失败: 应收/应付冲减未成功, 退货单未完成")
                        .withHint("退货涉及资金, 余额冲减失败时不能完成出货。请稍后重试或联系财务/管理员核实。");
            }
            // 防呆 Rule 2/5: recordAdjustment 建的是 PENDING 冲减 (余额未变, 待财务审批调整)。
            // 完成响应必须提示操作员余额尚未变动, 避免误以为客户/供应商欠款已冲平。
            if (adjustmentRecorded) {
                order.setCompletionHint("退货已完成并移库。余额冲减 " + order.getTotalAmount().stripTrailingZeros().toPlainString()
                        + " 元已提交, 待财务审批后才会变动"
                        + (order.getReturnType() == ReturnType.PURCHASE_RETURN ? "供应商应付" : "客户应收") + "余额。");
            }
            // T-RTA Phase C (issue #571): SALES_RETURN with-goods → create DEFECTIVE
            // FinishedGoodsBatch. status='DEFECTIVE' auto-excludes from existing
            // `WHERE status='AVAILABLE'` queries (调拨/销售/生产 pickers), so no schema or
            // repository refactor needed.
            //
            // 🔴 2026-08-19 三处修正 (测试同学 8-07 报「显示会自动退回至成品仓, 但是没有」):
            //
            //  (1) 仓库: 原来落 `resolveLogisticsId` = 物流仓 WH-LOG。退回来的是【成品】,
            //      落点就该是**成品仓** —— `resolveFinishedGoodsId` 一直存在, F006 也一直有
            //      WH-FG 成品仓。prod 实测三条 `不良品入库完成 ... warehouse=WH-LOG` 全落错仓。
            //      ⚠️ status 仍然是 DEFECTIVE 且**这是设计意图**: 退回来的货不可直接再卖,
            //      被 27 个 `status='AVAILABLE'` 查询过滤掉是对的, 不是缺陷。改的只是落点。
            //
            //  (2) 吞异常: 原来 `catch (Exception)` 只 log.error 就放过, 而同一方法下面的
            //      PURCHASE_RETURN 分支是 fail-loud 的 —— 同一件事两份相反行为(形态 D)。
            //      入库失败 = 账实背离, 与采购退货扣减同级, 必须阻断完成让操作员看见。
            //
            //  (3) 假读数: 原来无论实际存了几条, 都按 `getItems().size()` 报「完成」。
            //      跳过的行(无 productTypeId / 数量非正)不该被算成入库成功 —— 改成数真正 save 的。
            if (order.getReturnType() == ReturnType.SALES_RETURN
                    && finishedGoodsBatchRepository != null && warehouseResolver != null) {
                String fgWarehouseId = warehouseResolver.resolveFinishedGoodsId(factoryId);
                int itemIdx = 0;
                int savedCount = 0;
                int skippedCount = 0;
                try {
                    for (ResolvedSalesReturnLine resolved : resolvedSalesReturnLines) {
                        ReturnOrderItem item = resolved.item();
                        itemIdx++;
                        FinishedGoodsBatch batch = new FinishedGoodsBatch();
                        batch.setFactoryId(factoryId);
                        batch.setBatchNumber("RTN-" + order.getReturnNumber() + "-" + itemIdx);
                        batch.setReturnOrderItemId(item.getId());
                        batch.setProductTypeId(resolved.source().getProductTypeId());
                        batch.setProductName(item.getItemName());
                        batch.setProducedQuantity(item.getQuantity());
                        batch.setShippedQuantity(BigDecimal.ZERO);
                        batch.setReservedQuantity(BigDecimal.ZERO);
                        batch.setUnit(resolved.sourceUnit());
                        batch.setUnitPrice(item.getUnitPrice());
                        // Preserve the source batch's native inventory cost.  The
                        // return line price is a selling price and must not become
                        // the cost basis for later disposal/accounting.
                        batch.setUnitCost(resolved.source().getUnitCost());
                        batch.setProductionDate(LocalDate.now());
                        batch.setWarehouseId(fgWarehouseId);
                        batch.setStatus("DEFECTIVE");
                        batch.setCreatedBy(order.getApprovedBy());
                        batch.setRemark("销售退货入库(不良品待质检) - " + order.getReturnNumber()
                                + " - 原因: " + (item.getReason() != null ? item.getReason() : order.getReason()));
                        finishedGoodsBatchRepository.save(batch);
                        savedCount++;
                    }
                } catch (BusinessException e) {
                    throw e; // 明确业务错误原样上抛, 保留 message + hint。
                } catch (Exception e) {
                    // fail-loud, 与下面 PURCHASE_RETURN 分支同一纪律: 入库失败不能静默 COMPLETED。
                    log.error("销售退货 成品入库失败 (阻断完成): returnOrderId={}", returnOrderId, e);
                    throw new BusinessException(500, "退货完成失败: 退货成品入库未成功, 退货单未完成")
                            .withHint("请稍后重试或联系管理员核实成品仓库存后再完成该退货单。");
                }
                // 数的是真正 save 进去的条数, 不是 items.size()。
                log.info("销售退货 成品入库完成: returnNumber={}, saved={}, skipped={}, warehouse=WH-FG",
                        order.getReturnNumber(), savedCount, skippedCount);
            } else if (order.getReturnType() == ReturnType.PURCHASE_RETURN
                    && materialBatchRepository != null) {
                // BLOCKER 2 fix (取代 issue #795 反向逻辑): PURCHASE_RETURN with-goods 完成
                // = 货物理离开退回供应商 → 从现有库存【扣减】退货数量, 而非创建新批次。
                //
                // 此前 (#795) 镜像 SALES_RETURN 的"货退回来→加库存"逻辑, 给 PURCHASE_RETURN 创建
                // 一个新 DEFECTIVE MaterialBatch。但语义相反:
                //   - SALES_RETURN  (客户退货回来): 货物进厂 → 加库存 (现状, 上面分支保留)。
                //   - PURCHASE_RETURN (退给供应商): 货物离厂 → 扣库存 (本分支)。
                // 超收的量在采购收货时已加进库存 (createMaterialBatchFromReceiveItem 用全部收货量),
                // 退货完成时若再创建一个新批次 = 账面双计 (130 + 30 = 160)。正确应扣回 30 → 100。
                //
                // 扣减方式镜像 DisposalRecordService: 增 usedQuantity (不减 receiptQuantity),
                // 使 currentQuantity = receiptQuantity - usedQuantity - reservedQuantity 正确反映,
                // 并写 MaterialBatchAdjustment 负数留痕。源批次按 FEFO 跨同物料类型批次扣减
                // (ReturnOrderItem 不可靠引用单一批次; 退货物料 = exceptionQty 的同物料类型库存)。
                // #2 fix: 采购退货 = 原料退回供应商, 必须从【原料仓/采购入库仓】扣减, 而非跨所有仓 FEFO。
                // 跨仓 FEFO (findAvailableBatchesFEFO) 只排除了 PRODUCTION_BATCH (WIP), 未排除 #1171 领料搬库
                // 到车间仓 (WH-WKS) 的 sourceDocType='MATERIAL_REQUISITION' 批次 (status=AVAILABLE, 继承源
                // expireDate 常排 FEFO 最前) → 采购退货会优先吃掉生产在库的车间仓库存而非物理退回供应商的
                // 原料仓库存, 造成跨仓账实背离 + 报工待拣批次凭空消失。改用 warehouse-scoped FEFO 锁定采购
                // 入库仓 (resolvePurchaseInboundWh, 默认 WH-LOG), 车间仓批次天然不在此仓范围内。
                String rawWarehouseId = warehouseResolver != null
                        ? warehouseResolver.resolvePurchaseInboundWh(factoryId) : null;
                try {
                    int deducted = 0;
                    for (ReturnOrderItem item : order.getItems()) {
                        if (item.getMaterialTypeId() == null || item.getQuantity() == null
                                || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                            // Skip 行: PURCHASE_RETURN items 必须有 materialTypeId。
                            continue;
                        }
                        deductMaterialForPurchaseReturn(factoryId, order, item, rawWarehouseId);
                        deducted++;
                    }
                    log.info("采购退货 库存扣减完成 (BLOCKER 2): returnNumber={}, lines={}/{} items, 货退回供应商",
                            order.getReturnNumber(), deducted,
                            order.getItems() != null ? order.getItems().size() : 0);
                } catch (BusinessException e) {
                    // 库存不足 = 真错误 (账面/物理不一致), 必须 fail-loud 阻断完成, 不能静默吞。
                    // 退货单停在 FINANCE_APPROVED, 仓管员核实库存后重试。
                    throw e;
                } catch (Exception e) {
                    // 非预期错误同样 fail-loud — 退货扣减是出库可靠性红线, 不能像加库存那样 best-effort。
                    log.error("采购退货 库存扣减失败 (BLOCKER 2, 阻断完成): returnOrderId={}", returnOrderId, e);
                    throw new BusinessException(500, "采购退货库存扣减失败, 退货单未完成")
                            .withHint("请联系管理员核实库存后重试: " + e.getMessage());
                }
            }
        }

        order.setStatus(ReturnOrderStatus.COMPLETED);
        log.info("完成退货单: returnOrderId={}, returnNumber={}, withGoods={}", returnOrderId, order.getReturnNumber(), withGoods);
        return returnOrderRepository.save(order);
    }

    private ReturnOrder loadReturnOrderForWrite(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (!ReturnOrderRowLock.lockAndRefresh(entityManager, order, factoryId)) {
            throw new BusinessException(409, "退货单不存在或不属于当前工厂")
                    .withHint("请刷新退货单并确认当前工厂后重试");
        }
        return order;
    }

    /**
     * BLOCKER 2: PURCHASE_RETURN with-goods 完成时, 从现有库存扣减退货数量 (货退回供应商, 物理离厂)。
     *
     * <p>扣减策略: 按 FEFO 跨同物料类型 (item.materialTypeId) 的可用批次扣减 item.quantity。
     * ReturnOrderItem 不可靠引用单一源批次 (采购入库批次不存 sourceDocId 关联到 receiveRecord),
     * 故按物料类型聚合扣减 —— 退的就是该物料类型的库存, FEFO 顺序确定性。
     *
     * <p>扣减方式镜像 {@code DisposalRecordService}: 增 usedQuantity (不减 receiptQuantity),
     * 使 {@code currentQuantity = receiptQuantity - usedQuantity - reservedQuantity} 正确反映,
     * 并写 {@link MaterialBatchAdjustment} 负数留痕。库存不足抛 409 (账面/物理不一致, fail-loud)。
     *
     * @param rawWarehouseId 采购入库仓 (原料仓) id — 退货从此仓扣减。null 时回退跨仓 FEFO
     *                       (仅 warehouseResolver 未注入的单测场景; 生产环境恒非 null)。
     * @throws BusinessException 库存不足 (409) — 阻断退货完成, 不扣成负数
     */
    private void deductMaterialForPurchaseReturn(String factoryId, ReturnOrder order, ReturnOrderItem item,
                                                 String rawWarehouseId) {
        BigDecimal returnQty = item.getQuantity().setScale(2, RoundingMode.HALF_UP);

        // #2 fix: 优先按【采购入库仓】warehouse-scoped FEFO 取批次 — 排除车间仓 (WH-WKS) 领料搬库批次,
        // 只扣物理退回供应商的原料仓库存。rawWarehouseId 为 null (单测无 resolver) 才回退跨仓 FEFO。
        List<MaterialBatch> batches = rawWarehouseId != null
                ? materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                        factoryId, item.getMaterialTypeId(), rawWarehouseId)
                : materialBatchRepository.findAvailableBatchesFEFO(factoryId, item.getMaterialTypeId());

        // 先校验总可用量 >= 退货量, 不足则整体阻断 (不部分扣减留下不一致)。
        BigDecimal totalAvailable = batches.stream()
                .map(b -> b.getCurrentQuantity() != null ? b.getCurrentQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAvailable.compareTo(returnQty) < 0) {
            throw new BusinessException(409,
                    "退货物料「" + (item.getItemName() != null ? item.getItemName() : item.getMaterialTypeId())
                            + "」可用库存 " + totalAvailable.stripTrailingZeros().toPlainString()
                            + " 不足以退货 " + returnQty.stripTrailingZeros().toPlainString() + ", 无法完成退货")
                    .withHint("货物退回供应商需从库存扣减, 当前可用库存不足。请核实库存或退货数量。")
                    .withHintTarget("quantity");
        }

        BigDecimal remaining = returnQty;
        for (MaterialBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal available = batch.getCurrentQuantity() != null
                    ? batch.getCurrentQuantity() : BigDecimal.ZERO;
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal deductFromBatch = available.min(remaining);
            BigDecimal usedBefore = batch.getUsedQuantity() != null
                    ? batch.getUsedQuantity() : BigDecimal.ZERO;
            BigDecimal usedAfter = usedBefore.add(deductFromBatch);
            BigDecimal availableAfter = available.subtract(deductFromBatch);

            // adjustment 留痕: before/after 取可用量, 负数变更 (与 DisposalRecordService 一致)。
            MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
            adj.setId(UUID.randomUUID().toString());
            adj.setMaterialBatchId(batch.getId());
            adj.setAdjustmentType("PURCHASE_RETURN");
            adj.setQuantityBefore(available.setScale(2, RoundingMode.HALF_UP));
            adj.setAdjustmentQuantity(deductFromBatch.negate()); // 负数 (出库)
            adj.setQuantityAfter(availableAfter.setScale(2, RoundingMode.HALF_UP));
            adj.setReason("采购退货出库 [退货单 " + order.getReturnNumber() + "] 货退回供应商"
                    + (order.getReason() != null ? " - " + order.getReason() : ""));
            adj.setAdjustmentTime(LocalDateTime.now());
            adj.setAdjustedBy(order.getApprovedBy());
            adj.setNotes("returnOrderId=" + order.getId() + " materialTypeId=" + item.getMaterialTypeId());
            if (materialBatchAdjustmentRepository != null) {
                materialBatchAdjustmentRepository.save(adj);
            }

            batch.setUsedQuantity(usedAfter.setScale(2, RoundingMode.HALF_UP));
            batch.setLastUsedAt(LocalDateTime.now());
            // 批次扣空 → 标 USED_UP (与领料/报损一致, FEFO 查询自动排除)。
            if (batch.getCurrentQuantity() != null
                    && batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                batch.setStatus(MaterialBatchStatus.USED_UP);
            }
            materialBatchRepository.save(batch);

            remaining = remaining.subtract(deductFromBatch);
        }
        log.info("采购退货扣减库存: returnNumber={}, materialTypeId={}, qty={}",
                order.getReturnNumber(), item.getMaterialTypeId(), returnQty.stripTrailingZeros().toPlainString());
    }

    @Override
    public Map<String, Object> getReturnOrderStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE);

        Page<ReturnOrder> allOrders = returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, all);
        List<ReturnOrder> orders = allOrders.getContent();

        long purchaseReturnCount = orders.stream().filter(o -> o.getReturnType() == ReturnType.PURCHASE_RETURN).count();
        long salesReturnCount = orders.stream().filter(o -> o.getReturnType() == ReturnType.SALES_RETURN).count();
        long pendingApproval = orders.stream().filter(o -> o.getStatus() == ReturnOrderStatus.SUBMITTED).count();

        BigDecimal totalReturnAmount = orders.stream()
                .filter(o -> o.getStatus() != ReturnOrderStatus.REJECTED)
                .map(ReturnOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("purchaseReturnCount", purchaseReturnCount);
        stats.put("salesReturnCount", salesReturnCount);
        stats.put("pendingApprovalCount", pendingApproval);
        stats.put("totalReturnAmount", totalReturnAmount);
        return stats;
    }

    // ==================== 内部方法 ====================

    private String generateReturnNumber(String factoryId, ReturnType returnType) {
        String prefix = returnType == ReturnType.PURCHASE_RETURN ? "RT-PUR" : "RT-SAL";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = returnOrderRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        return String.format("%s-%s-%04d", prefix, dateStr, count + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

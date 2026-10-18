package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.BusinessRowScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** One fail-closed owner boundary shared by AR/AP, invoice, and payment workflows. */
@Component
public class FinanceRowScopeQuery {
    private final ArApTransactionRepository transactionRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesDeliveryRecordRepository deliveryRepository;
    private final PurchaseReceiveRecordRepository receiveRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ArApPaymentAllocationRepository allocationRepository;

    @Autowired(required = false)
    private BusinessRowScope businessRowScope;

    @Autowired
    private com.cretas.aims.repository.inventory.PaymentRequestRepository paymentRequestRepository;

    public FinanceRowScopeQuery(ArApTransactionRepository transactionRepository,
                                SalesOrderRepository salesOrderRepository,
                                PurchaseOrderRepository purchaseOrderRepository,
                                SalesDeliveryRecordRepository deliveryRepository,
                                PurchaseReceiveRecordRepository receiveRepository,
                                ReturnOrderRepository returnOrderRepository,
                                ArApPaymentAllocationRepository allocationRepository) {
        this.transactionRepository = transactionRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.deliveryRepository = deliveryRepository;
        this.receiveRepository = receiveRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.allocationRepository = allocationRepository;
    }

    /** null means the original unrestricted/internal contract; empty means no visible rows. */
    public List<Long> creators(String factoryId) {
        return BusinessRowScope.creators(businessRowScope, factoryId);
    }

    /** Invoice/payment list contract: applies inherited non-ALL scopes too. */
    public List<Long> recordCreators(String factoryId) {
        return BusinessRowScope.recordCreators(businessRowScope, factoryId);
    }

    public void requireOwner(String factoryId, Long owner) {
        BusinessRowScope.requireOwner(businessRowScope, factoryId, owner);
    }

    /** Global finance decisions must run only for an unrestricted (ALL) scope. */
    public void requireAll(String factoryId) {
        if (creators(factoryId) != null) {
            throw new BusinessException(403, "该操作需要全厂财务范围");
        }
    }

    public void requireSalesOrderOwner(String factoryId, String salesOrderId) {
        if (salesOrderId == null || salesOrderId.isBlank()) return;
        Long owner = salesOrderRepository.findByIdAndFactoryId(salesOrderId, factoryId)
                .map(o -> o.getCreatedBy())
                .orElseThrow(() -> new BusinessException(404, "销售订单不存在或不属于当前工厂"));
        requireOwner(factoryId, owner);
    }

    public void requirePurchaseOrderOwner(String factoryId, String purchaseOrderId) {
        if (purchaseOrderId == null || purchaseOrderId.isBlank()) return;
        Long owner = purchaseOrderRepository.findByIdAndFactoryId(purchaseOrderId, factoryId)
                .map(o -> o.getCreatedBy())
                .orElseThrow(() -> new BusinessException(404, "采购订单不存在或不属于当前工厂"));
        requireOwner(factoryId, owner);
    }

    public void requireSourceOwner(String factoryId, String sourceType, String sourceId) {
        if ("SALES_DELIVERY".equals(sourceType)) {
            if (!hasText(sourceId)) {
                requireOwner(factoryId, null);
                return;
            }
            Long owner = deliveryRepository.findByIdAndFactoryId(sourceId, factoryId)
                    .flatMap(d -> hasText(d.getSalesOrderId())
                            ? salesOrderRepository.findByIdAndFactoryId(d.getSalesOrderId(), factoryId)
                            : Optional.empty())
                    .map(o -> o.getCreatedBy()).orElse(null);
            requireOwner(factoryId, owner);
            return;
        }
        if ("PURCHASE_RECEIVE".equals(sourceType)) {
            if (!hasText(sourceId)) {
                requireOwner(factoryId, null);
                return;
            }
            Long owner = receiveRepository.findByIdAndFactoryId(sourceId, factoryId)
                    .flatMap(r -> hasText(r.getPurchaseOrderId())
                            ? purchaseOrderRepository.findByIdAndFactoryId(r.getPurchaseOrderId(), factoryId)
                            : Optional.empty())
                    .map(o -> o.getCreatedBy()).orElse(null);
            requireOwner(factoryId, owner);
            return;
        }
        if ("RETURN_ORDER".equals(sourceType)) {
            if (!hasText(sourceId)) {
                requireOwner(factoryId, null);
                return;
            }
            Long owner = returnOrderRepository.findByIdAndFactoryId(sourceId, factoryId)
                    .flatMap(r -> resolveReturnOrderOwner(factoryId, r, null, null))
                    .orElse(null);
            requireOwner(factoryId, owner);
            return;
        }
        // Unknown source types are not safe to attribute to the operator.
        requireOwner(factoryId, null);
    }

    /** Loads a transaction in its tenant and rejects hidden, damaged, or cross-source rows. */
    public ArApTransaction requireTransactionAccess(String factoryId, String transactionId) {
        if (!hasText(transactionId)) {
            throw new BusinessException(400, "交易记录ID不能为空");
        }
        ArApTransaction transaction = transactionRepository.findByIdAndFactoryId(transactionId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "交易记录不存在或不属于当前工厂"));
        requireOwner(factoryId, resolveOwner(transaction));
        return transaction;
    }

    /** Resolves the authoritative source owner; null is intentionally fail-closed. */
    public Long resolveOwner(ArApTransaction transaction) {
        if (transaction == null || transaction.getFactoryId() == null) return null;
        String factoryId = transaction.getFactoryId();
        if ("SALES_DELIVERY".equals(transaction.getSourceType())) {
            if (!hasText(transaction.getSourceId())) return null;
            return deliveryRepository.findByIdAndFactoryId(transaction.getSourceId(), factoryId)
                    .filter(d -> !hasText(transaction.getSalesOrderId())
                            || transaction.getSalesOrderId().equals(d.getSalesOrderId()))
                    .flatMap(d -> hasText(d.getSalesOrderId())
                            ? salesOrderRepository.findByIdAndFactoryId(d.getSalesOrderId(), factoryId)
                            : Optional.empty())
                    .map(o -> o.getCreatedBy()).orElse(null);
        }
        if ("PURCHASE_RECEIVE".equals(transaction.getSourceType())) {
            if (!hasText(transaction.getSourceId())) return null;
            return receiveRepository.findByIdAndFactoryId(transaction.getSourceId(), factoryId)
                    .filter(r -> !hasText(transaction.getPurchaseOrderId())
                            || transaction.getPurchaseOrderId().equals(r.getPurchaseOrderId()))
                    .flatMap(r -> hasText(r.getPurchaseOrderId())
                            ? purchaseOrderRepository.findByIdAndFactoryId(r.getPurchaseOrderId(), factoryId)
                            : Optional.empty())
                    .map(o -> o.getCreatedBy()).orElse(null);
        }
        if ("RETURN_ORDER".equals(transaction.getSourceType())) {
            if (!hasText(transaction.getSourceId())) return null;
            return returnOrderRepository.findByIdAndFactoryId(transaction.getSourceId(), factoryId)
                    .flatMap(r -> resolveReturnOrderOwner(factoryId, r,
                            transaction.getSalesOrderId(), transaction.getPurchaseOrderId()))
                    .orElse(null);
        }
        if ("RETURN_REFUND".equals(transaction.getSourceType())
                || "RETURN_REFUND_REVERSAL".equals(transaction.getSourceType())) {
            return resolveRefundOwner(transaction);
        }
        if ("PAYABLE_SETTLEMENT".equals(transaction.getSourceType())) {
            return resolvePayableSettlementOwner(transaction);
        }
        if (hasText(transaction.getSourceType()) || hasText(transaction.getSourceId())) return null;
        if (hasText(transaction.getSalesOrderId())) {
            return salesOrderRepository.findByIdAndFactoryId(transaction.getSalesOrderId(), factoryId)
                    .map(o -> o.getCreatedBy()).orElse(null);
        }
        if (hasText(transaction.getPurchaseOrderId())) {
            return purchaseOrderRepository.findByIdAndFactoryId(transaction.getPurchaseOrderId(), factoryId)
                    .map(o -> o.getCreatedBy()).orElse(null);
        }
        if (!hasText(transaction.getSourceType()) && !hasText(transaction.getSourceId())) {
            return transaction.getOperatedBy();
        }
        return null;
    }

    /** A refund and its cancellation inherit the same intact request/return/order owner. */
    private Long resolveRefundOwner(ArApTransaction transaction) {
        boolean original = "RETURN_REFUND".equals(transaction.getSourceType());
        if (paymentRequestRepository == null || !hasText(transaction.getSourceId())
                || transaction.getTransactionType() != (original
                    ? com.cretas.aims.entity.enums.ArApTransactionType.AR_REFUND
                    : com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT)
                || transaction.getCounterpartyType() != com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER
                || transaction.getPurchaseOrderId() != null) return null;
        String factoryId = transaction.getFactoryId();
        var request = paymentRequestRepository.findById(transaction.getSourceId())
                .filter(p -> factoryId.equals(p.getFactoryId()) && p.getDeletedAt() == null
                        && p.getSourceType() == com.cretas.aims.entity.enums.PaymentSourceType.SALES
                        && p.getPurchaseOrderId() == null
                        && hasText(p.getReturnOrderId()) && hasText(p.getSalesOrderId())
                        && p.getSalesOrderId().equals(transaction.getSalesOrderId())
                        && hasText(p.getCustomerId()) && p.getCustomerId().equals(transaction.getCounterpartyId()))
                .orElse(null);
        if (request == null) return null;
        return returnOrderRepository.findByIdAndFactoryId(request.getReturnOrderId(), factoryId)
                .filter(r -> factoryId.equals(r.getFactoryId()) && r.getDeletedAt() == null
                        && r.getReturnType() == com.cretas.aims.entity.enums.ReturnType.SALES_RETURN
                        && request.getCustomerId().equals(r.getCounterpartyId()))
                .flatMap(r -> resolveReturnOrderOwner(factoryId, r, request.getSalesOrderId(), null))
                .orElse(null);
    }

    /**
     * Settlement source IDs are idempotency keys, so ownership must follow the persisted
     * allocation to the AP invoice's purchase order.  The operator and source key are never
     * accepted as an ownership fallback.
     */
    private Long resolvePayableSettlementOwner(ArApTransaction payment) {
        if (allocationRepository == null || payment == null || !hasText(payment.getId())
                || payment.getTransactionType() != com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT
                || hasText(payment.getSalesOrderId())) return null;
        var allocations = allocationRepository.findByFactoryIdAndPaymentTransactionIdOrderByCreatedAtAsc(
                payment.getFactoryId(), payment.getId());
        if (allocations.size() != 1 || allocations.get(0).getPayableTransactionId() == null) return null;
        ArApTransaction payable = transactionRepository.findByIdAndFactoryId(
                        allocations.get(0).getPayableTransactionId(), payment.getFactoryId())
                .orElse(null);
        if (payable == null || payable.getTransactionType() != com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE
                || payable.getCounterpartyType() != com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER
                || (hasText(payment.getPurchaseOrderId())
                    && !payment.getPurchaseOrderId().equals(payable.getPurchaseOrderId()))) return null;
        if (hasText(payable.getSalesOrderId())) return null;
        if (hasText(payable.getSourceType()) && !"PURCHASE_RECEIVE".equals(payable.getSourceType())) return null;
        return resolveOwner(payable);
    }

    /**
     * Creates the HTTP projection for restricted finance responses.  Internal service callers
     * keep receiving the managed entity; only restricted HTTP payloads use this defensive copy.
     */
    public ArApTransaction responseTransaction(String factoryId, ArApTransaction source) {
        if (source == null || creators(factoryId) == null) return source;
        ArApTransaction projection = new ArApTransaction();
        BeanUtils.copyProperties(source, projection);
        projection.setBalanceAfter(null);
        return projection;
    }

    public PageResponse<ArApTransaction> responseTransactions(String factoryId,
                                                               PageResponse<ArApTransaction> source) {
        if (source == null || creators(factoryId) == null) return source;
        List<ArApTransaction> projected = source.getContent() == null ? List.of()
                : source.getContent().stream().map(row -> {
                    ArApTransaction copy = new ArApTransaction();
                    BeanUtils.copyProperties(row, copy);
                    copy.setBalanceAfter(null);
                    return copy;
                }).toList();
        return PageResponse.of(projected, source.getPage(), source.getSize(), source.getTotalElements());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Optional<Long> resolveReturnOrderOwner(String factoryId, ReturnOrder returnOrder,
                                                   String salesOrderId, String purchaseOrderId) {
        if (returnOrder == null || returnOrder.getReturnType() == null
                || !hasText(returnOrder.getSourceOrderId())) {
            return Optional.empty();
        }
        if ("SALES_RETURN".equals(returnOrder.getReturnType().name())) {
            if (hasText(purchaseOrderId)
                    || (hasText(salesOrderId) && !salesOrderId.equals(returnOrder.getSourceOrderId()))) {
                return Optional.empty();
            }
            return salesOrderRepository.findByIdAndFactoryId(returnOrder.getSourceOrderId(), factoryId)
                    .map(order -> order.getCreatedBy());
        }
        if ("PURCHASE_RETURN".equals(returnOrder.getReturnType().name())) {
            if (hasText(salesOrderId)
                    || (hasText(purchaseOrderId) && !purchaseOrderId.equals(returnOrder.getSourceOrderId()))) {
                return Optional.empty();
            }
            return purchaseOrderRepository.findByIdAndFactoryId(returnOrder.getSourceOrderId(), factoryId)
                    .map(order -> order.getCreatedBy());
        }
        return Optional.empty();
    }
}

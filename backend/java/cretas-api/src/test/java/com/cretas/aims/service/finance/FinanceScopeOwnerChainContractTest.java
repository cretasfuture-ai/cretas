package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.BusinessRowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceScopeOwnerChainContractTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private ArApPaymentAllocationRepository allocationRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private BusinessRowScope businessRowScope;
    @Mock private com.cretas.aims.repository.inventory.PaymentRequestRepository paymentRequestRepository;

    private FinanceRowScopeQuery query;

    @BeforeEach
    void setUp() {
        query = new FinanceRowScopeQuery(transactionRepository, salesOrderRepository,
                purchaseOrderRepository, deliveryRepository, receiveRepository, returnOrderRepository,
                allocationRepository);
        ReflectionTestUtils.setField(query, "businessRowScope", businessRowScope);
        ReflectionTestUtils.setField(query, "paymentRequestRepository", paymentRequestRepository);
        org.mockito.Mockito.lenient().when(businessRowScope.creatorIds("F1")).thenReturn(List.of(11L));
    }

    @Test
    void unknownSourceCannotFallBackToVisibleSalesOrderOrOperator() {
        ArApTransaction transaction = transaction("UNKNOWN", 11L, "SO-OWN", "UNKNOWN", "source-1");
        when(transactionRepository.findByIdAndFactoryId("UNKNOWN", "F1"))
                .thenReturn(Optional.of(transaction));

        assertThat(query.resolveOwner(transaction)).isNull();
        assertThatThrownBy(() -> query.requireTransactionAccess("F1", "UNKNOWN"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
    }

    @Test
    void mismatchedDeliveryCannotFallBackToDirectSalesOrder() {
        ArApTransaction transaction = transaction("BROKEN", 11L, "SO-OTHER", "SALES_DELIVERY", "DEL-OWN");
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("DEL-OWN");
        delivery.setFactoryId("F1");
        delivery.setSalesOrderId("SO-OWN");
        when(deliveryRepository.findByIdAndFactoryId("DEL-OWN", "F1"))
                .thenReturn(Optional.of(delivery));

        assertThat(query.resolveOwner(transaction)).isNull();
    }

    @Test
    void returnOrderSourceUsesUpstreamSalesOrderCreator() {
        ArApTransaction transaction = transaction("RETURN", 99L, null, "RETURN_ORDER", "RET-OWN");
        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setId("RET-OWN");
        returnOrder.setFactoryId("F1");
        returnOrder.setReturnType(ReturnType.SALES_RETURN);
        returnOrder.setSourceOrderId("SO-OWN");
        when(returnOrderRepository.findByIdAndFactoryId("RET-OWN", "F1"))
                .thenReturn(Optional.of(returnOrder));
        SalesOrder sourceOrder = new SalesOrder();
        sourceOrder.setId("SO-OWN");
        sourceOrder.setFactoryId("F1");
        sourceOrder.setCreatedBy(11L);
        when(salesOrderRepository.findByIdAndFactoryId("SO-OWN", "F1"))
                .thenReturn(Optional.of(sourceOrder));

        assertThat(query.resolveOwner(transaction)).isEqualTo(11L);
    }

    @Test
    void mismatchedReturnOrderCannotFallBackToOperatorOrDirectOrder() {
        ArApTransaction transaction = transaction("RETURN-BROKEN", 11L, "SO-OTHER",
                "RETURN_ORDER", "RET-OWN");
        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setId("RET-OWN");
        returnOrder.setFactoryId("F1");
        returnOrder.setReturnType(ReturnType.SALES_RETURN);
        returnOrder.setSourceOrderId("SO-OWN");
        when(returnOrderRepository.findByIdAndFactoryId("RET-OWN", "F1"))
                .thenReturn(Optional.of(returnOrder));

        assertThat(query.resolveOwner(transaction)).isNull();
    }

    @Test
    void payableSettlementUsesAllocatedPurchaseOrderOwner() {
        ArApTransaction payment = transaction("SETTLED", 99L, "PO-OWN",
                "PAYABLE_SETTLEMENT", "idempotency-key");
        payment.setPurchaseOrderId("PO-OWN");
        ArApPaymentAllocation allocation = new ArApPaymentAllocation();
        allocation.setFactoryId("F1");
        allocation.setPaymentTransactionId("SETTLED");
        allocation.setPayableTransactionId("AP-OWN");
        when(allocationRepository.findByFactoryIdAndPaymentTransactionIdOrderByCreatedAtAsc("F1", "SETTLED"))
                .thenReturn(List.of(allocation));
        ArApTransaction payable = transaction("AP-OWN", 77L, "PO-OWN", null, null);
        payable.setSalesOrderId(null);
        payable.setPurchaseOrderId("PO-OWN");
        payable.setTransactionType(com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE);
        payable.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER);
        when(transactionRepository.findByIdAndFactoryId("AP-OWN", "F1")).thenReturn(Optional.of(payable));
        var purchaseOrder = new com.cretas.aims.entity.inventory.PurchaseOrder();
        purchaseOrder.setId("PO-OWN");
        purchaseOrder.setFactoryId("F1");
        purchaseOrder.setCreatedBy(11L);
        when(purchaseOrderRepository.findByIdAndFactoryId("PO-OWN", "F1"))
                .thenReturn(Optional.of(purchaseOrder));

        assertThat(query.resolveOwner(payment)).isEqualTo(11L);
    }

    @Test
    void damagedPayableSettlementCannotFallBackToPurchaseOrderOrOperator() {
        ArApTransaction payment = transaction("SETTLED-BROKEN", 11L, "PO-OTHER",
                "PAYABLE_SETTLEMENT", "idempotency-key");
        ArApPaymentAllocation allocation = new ArApPaymentAllocation();
        allocation.setFactoryId("F1");
        allocation.setPaymentTransactionId("SETTLED-BROKEN");
        allocation.setPayableTransactionId("AP-BROKEN");
        when(allocationRepository.findByFactoryIdAndPaymentTransactionIdOrderByCreatedAtAsc(
                "F1", "SETTLED-BROKEN")).thenReturn(List.of(allocation));
        ArApTransaction payable = transaction("AP-BROKEN", 77L, "PO-OWN", null, null);
        payable.setSalesOrderId(null);
        payable.setPurchaseOrderId("PO-OWN");
        payable.setTransactionType(com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE);
        payable.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER);
        when(transactionRepository.findByIdAndFactoryId("AP-BROKEN", "F1")).thenReturn(Optional.of(payable));

        assertThat(query.resolveOwner(payment)).isNull();
    }

    @Test
    void multiplyAllocatedSettlementFailsClosed() {
        ArApTransaction payment = transaction("SETTLED-MULTI", 11L, "PO-OWN",
                "PAYABLE_SETTLEMENT", "idempotency-key");
        ArApPaymentAllocation first = new ArApPaymentAllocation();
        first.setFactoryId("F1");
        first.setPaymentTransactionId("SETTLED-MULTI");
        first.setPayableTransactionId("AP-1");
        ArApPaymentAllocation second = new ArApPaymentAllocation();
        second.setFactoryId("F1");
        second.setPaymentTransactionId("SETTLED-MULTI");
        second.setPayableTransactionId("AP-2");
        when(allocationRepository.findByFactoryIdAndPaymentTransactionIdOrderByCreatedAtAsc(
                "F1", "SETTLED-MULTI")).thenReturn(List.of(first, second));

        assertThat(query.resolveOwner(payment)).isNull();
    }

    @Test
    void restrictedHttpProjectionHidesRunningBalanceWithoutMutatingManagedRow() {
        ArApTransaction managed = transaction("VISIBLE", 11L, null, null, null);
        managed.setBalanceAfter(new java.math.BigDecimal("9999.00"));

        ArApTransaction projection = query.responseTransaction("F1", managed);

        assertThat(projection).isNotSameAs(managed);
        assertThat(projection.getBalanceAfter()).isNull();
        assertThat(managed.getBalanceAfter()).isEqualByComparingTo("9999.00");
        when(businessRowScope.creatorIds("F1")).thenReturn(null);
        assertThat(query.responseTransaction("F1", managed)).isSameAs(managed);
    }

    @Test
    void restrictedHttpPageProjectionHidesRunningBalanceAndKeepsPageMetadata() {
        ArApTransaction managed = transaction("PAGE-VISIBLE", 11L, null, null, null);
        managed.setBalanceAfter(new java.math.BigDecimal("9999.00"));
        PageResponse<ArApTransaction> page = PageResponse.of(List.of(managed), 2, 1, 3L);

        PageResponse<ArApTransaction> projection = query.responseTransactions("F1", page);

        assertThat(projection.getPage()).isEqualTo(2);
        assertThat(projection.getSize()).isEqualTo(1);
        assertThat(projection.getTotalElements()).isEqualTo(3L);
        assertThat(projection.getContent()).singleElement()
                .satisfies(row -> assertThat(row.getBalanceAfter()).isNull());
        assertThat(managed.getBalanceAfter()).isEqualByComparingTo("9999.00");
    }

    @Test
    void globalOperationsRejectFilteredScopeButAllowAll() {
        assertThatThrownBy(() -> query.requireAll("F1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
        when(businessRowScope.creatorIds("F1")).thenReturn(null);
        query.requireAll("F1");
    }

    @Test void allocationInheritsManualPayableOwnerAndRejectsUnknownParentSource() {
        ArApTransaction payment=transaction("PAY-MANUAL",99L,null,"PAYABLE_SETTLEMENT","key");
        ArApTransaction payable=transaction("AP-MANUAL",11L,null,null,null);
        payable.setTransactionType(com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE);
        payable.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER);
        ArApPaymentAllocation allocation=new ArApPaymentAllocation();
        allocation.setPayableTransactionId("AP-MANUAL");
        when(allocationRepository.findByFactoryIdAndPaymentTransactionIdOrderByCreatedAtAsc("F1","PAY-MANUAL"))
                .thenReturn(List.of(allocation));
        when(transactionRepository.findByIdAndFactoryId("AP-MANUAL","F1")).thenReturn(Optional.of(payable));
        assertThat(query.resolveOwner(payment)).isEqualTo(11L);
        payable.setSourceType("UNKNOWN_SOURCE");
        payable.setSourceId("broken");
        payable.setPurchaseOrderId("PO-OWN");
        assertThat(query.resolveOwner(payment)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RETURN_REFUND", "RETURN_REFUND_REVERSAL"})
    void refundAndReversalShareTheIntactSourceOwnerAndNeverFallBack(String source) {
        var tx = transaction("RF-TX", 99L, "SO-OWN", source, "RF-REQUEST");
        var type = "RETURN_REFUND".equals(source)
                ? com.cretas.aims.entity.enums.ArApTransactionType.AR_REFUND
                : com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT;
        tx.setTransactionType(type);
        tx.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER);
        tx.setCounterpartyId("C1");
        var request = new com.cretas.aims.entity.inventory.PaymentRequest();
        request.setId("RF-REQUEST"); request.setFactoryId("F1");
        request.setSourceType(com.cretas.aims.entity.enums.PaymentSourceType.SALES);
        request.setSalesOrderId("SO-OWN"); request.setCustomerId("C1"); request.setReturnOrderId("RF-RETURN");
        var returned = new ReturnOrder(); returned.setId("RF-RETURN"); returned.setFactoryId("F1");
        returned.setReturnType(ReturnType.SALES_RETURN); returned.setSourceOrderId("SO-OWN"); returned.setCounterpartyId("C1");
        var order = new SalesOrder(); order.setId("SO-OWN"); order.setFactoryId("F1"); order.setCreatedBy(11L);
        when(paymentRequestRepository.findById("RF-REQUEST")).thenReturn(Optional.of(request));
        when(returnOrderRepository.findByIdAndFactoryId("RF-RETURN", "F1")).thenReturn(Optional.of(returned));
        when(salesOrderRepository.findByIdAndFactoryId("SO-OWN", "F1")).thenReturn(Optional.of(order));
        when(transactionRepository.findByIdAndFactoryId("RF-TX", "F1")).thenReturn(Optional.of(tx));
        assertThat(query.requireTransactionAccess("F1", "RF-TX")).isSameAs(tx);
        assertThat(query.resolveOwner(tx)).isEqualTo(11L);
        request.setStatus(com.cretas.aims.entity.enums.PaymentRequestStatus.CANCELLED);
        assertThat(query.resolveOwner(tx)).isEqualTo(11L); // cancellation must retain historical ownership
        request.setFactoryId("F2"); assertThat(query.resolveOwner(tx)).isNull(); request.setFactoryId("F1");
        request.softDelete(); assertThat(query.resolveOwner(tx)).isNull(); request.restore();
        request.setPurchaseOrderId("PO-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); request.setPurchaseOrderId(null);
        request.setCustomerId("C-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); request.setCustomerId("C1");
        request.setSourceType(com.cretas.aims.entity.enums.PaymentSourceType.PURCHASE);
        assertThat(query.resolveOwner(tx)).isNull(); request.setSourceType(com.cretas.aims.entity.enums.PaymentSourceType.SALES);
        tx.setSalesOrderId("SO-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); tx.setSalesOrderId("SO-OWN");
        tx.setPurchaseOrderId("PO-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); tx.setPurchaseOrderId(null);
        tx.setTransactionType(com.cretas.aims.entity.enums.ArApTransactionType.AR_INVOICE);
        assertThat(query.resolveOwner(tx)).isNull(); tx.setTransactionType(type);
        tx.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER);
        assertThat(query.resolveOwner(tx)).isNull(); tx.setCounterpartyType(com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER);
        returned.setCounterpartyId("C-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); returned.setCounterpartyId("C1");
        returned.setSourceOrderId("SO-OTHER"); assertThat(query.resolveOwner(tx)).isNull(); returned.setSourceOrderId("SO-OWN");
        returned.setFactoryId("F2"); assertThat(query.resolveOwner(tx)).isNull(); returned.setFactoryId("F1");
        tx.setSourceId("MISSING"); assertThat(query.resolveOwner(tx)).isNull(); tx.setSourceId("RF-REQUEST");
        order.setCreatedBy(22L);
        assertThatThrownBy(() -> query.requireTransactionAccess("F1", "RF-TX"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403));
    }

    private static ArApTransaction transaction(String id, Long operator, String salesOrderId,
                                                String sourceType, String sourceId) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        transaction.setFactoryId("F1");
        transaction.setOperatedBy(operator);
        if ("PAYABLE_SETTLEMENT".equals(sourceType)) {
            transaction.setPurchaseOrderId(salesOrderId);
            transaction.setTransactionType(com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT);
        } else transaction.setSalesOrderId(salesOrderId);
        transaction.setSourceType(sourceType);
        transaction.setSourceId(sourceId);
        return transaction;
    }
}

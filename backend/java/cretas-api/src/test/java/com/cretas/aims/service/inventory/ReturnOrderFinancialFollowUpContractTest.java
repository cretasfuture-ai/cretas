package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnOrderFinancialFollowUpContractTest {
    @Mock ReturnOrderRepository returns;
    @Mock ArApTransactionRepository transactions;
    @Mock VoucherRepository vouchers;
    @Mock ArApService arAp;
    @Mock EntityManager entityManager;
    @Mock Query lockQuery;
    ReturnOrderFinancialFollowUpService service;
    ReturnOrder order;

    @BeforeEach void setup() {
        service = new ReturnOrderFinancialFollowUpService(returns, transactions, vouchers, arAp);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        order = new ReturnOrder();
        order.setId("return-a"); order.setFactoryId("A"); order.setReturnNumber("RT-A");
        order.setReturnType(ReturnType.SALES_RETURN); order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true); order.setTotalAmount(new BigDecimal("150.00")); order.setCounterpartyId("customer-a");
        when(returns.findById("return-a")).thenReturn(Optional.of(order));
    }

    private ArApTransaction row(String id, ArApApprovalStatus status) {
        ArApTransaction row = new ArApTransaction();
        row.setId(id); row.setFactoryId("A"); row.setSourceType("RETURN_ORDER"); row.setSourceId("return-a");
        row.setTransactionType(ArApTransactionType.AR_ADJUSTMENT); row.setCounterpartyType(CounterpartyType.CUSTOMER);
        row.setCounterpartyId("customer-a"); row.setAmount(new BigDecimal("-150.00")); row.setApprovalStatus(status);
        return row;
    }
    private void ledger(ArApTransaction... rows) {
        when(transactions.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                "A", "RETURN_ORDER", List.of("return-a"), ArApTransactionType.AR_ADJUSTMENT)).thenReturn(List.of(rows));
    }

    private void parentLock() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getResultList()).thenReturn(List.of("return-a"));
    }

    @Test void rejectedCreditIsResubmittedThroughExistingPendingServiceWithOriginalSource() {
        ArApTransaction old = row("old-rejected", ArApApprovalStatus.REJECTED);
        ArApTransaction pending = row("new-pending", ArApApprovalStatus.PENDING);
        ledger(old);
        parentLock();
        when(arAp.recordAdjustment(eq("A"), eq(CounterpartyType.CUSTOMER), eq("customer-a"),
                eq(new BigDecimal("-150.00")), eq(7L), anyString(), eq("RETURN_ORDER"), eq("return-a")))
                .thenReturn(pending);
        assertThat(service.resubmit("A", "return-a", 7L, "已核对原单")).isSameAs(pending);
        var sequence = inOrder(entityManager, lockQuery, transactions, arAp);
        sequence.verify(entityManager).createNativeQuery(contains("FOR UPDATE"));
        sequence.verify(lockQuery).setParameter("returnOrderId", "return-a");
        sequence.verify(lockQuery).setParameter("factoryId", "A");
        sequence.verify(lockQuery).getResultList();
        sequence.verify(entityManager).refresh(order);
        sequence.verify(transactions).findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                "A", "RETURN_ORDER", List.of("return-a"), ArApTransactionType.AR_ADJUSTMENT);
        sequence.verify(arAp).recordAdjustment(eq("A"), eq(CounterpartyType.CUSTOMER), eq("customer-a"),
                eq(new BigDecimal("-150.00")), eq(7L), contains("old-rejected"), eq("RETURN_ORDER"), eq("return-a"));
        assertThat(old.getApprovalStatus()).isEqualTo(ArApApprovalStatus.REJECTED);
        verify(returns, never()).save(any());
        verifyNoInteractions(vouchers);
    }

    @Test void repeatRequestReusesOnePendingAdjustmentWithoutAnotherCredit() {
        parentLock();
        ArApTransaction pending = row("existing-pending", ArApApprovalStatus.PENDING);
        ledger(row("rejected", ArApApprovalStatus.REJECTED), pending);
        assertThat(service.resubmit("A", "return-a", 7L, "重复重提")).isSameAs(pending);
        verifyNoInteractions(arAp, vouchers);
    }

    @Test void approvedAdjustmentCannotBeResubmitted() {
        parentLock();
        ledger(row("approved", ArApApprovalStatus.APPROVED));
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo("RETURN_ADJUSTMENT_ALREADY_APPROVED"));
        verifyNoInteractions(arAp);
    }

    @Test void duplicatePendingOrWrongAmountEvidenceBlocksRecovery() {
        parentLock();
        ledger(row("pending-1", ArApApprovalStatus.PENDING), row("pending-2", ArApApprovalStatus.PENDING));
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo("RETURN_ADJUSTMENT_INCONSISTENT"));
        ArApTransaction wrong = row("wrong-amount", ArApApprovalStatus.REJECTED);
        wrong.setAmount(new BigDecimal("-149.00")); ledger(wrong);
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(arAp);
    }

    @Test void unfinishedOrRefundOnlyReturnCannotTriggerRecovery() {
        parentLock();
        order.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提")).isInstanceOf(BusinessException.class);
        order.setStatus(ReturnOrderStatus.COMPLETED); order.setWithGoods(false);
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transactions, arAp);
    }

    @Test void crossFactoryIsRejectedBeforeAnyLockOrFinancialQuery() {
        assertThatThrownBy(() -> service.get("B", "return-a"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.resubmit("B", "return-a", 7L, "重提")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(entityManager, transactions, vouchers, arAp);
    }

    @Test void failedParentLockNeverCreatesAnotherAdjustment() {
        when(entityManager.createNativeQuery(anyString())).thenThrow(new IllegalStateException("lock unavailable"));
        assertThatThrownBy(() -> service.resubmit("A", "return-a", 7L, "重提")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transactions, vouchers, arAp);
    }

    @Test void readbackShowsDraftVoucherAndRejectedHistoryWithoutPostingOrCashPayment() {
        ledger(row("rejected", ArApApprovalStatus.REJECTED));
        Voucher voucher = new Voucher(); voucher.setId("voucher-a"); voucher.setFactoryId("A");
        voucher.setVoucherNumber("V-A"); voucher.setStatus(VoucherStatus.DRAFT);
        when(vouchers.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "return-a"))
                .thenReturn(Optional.of(voucher));
        var view = service.get("A", "return-a");
        assertThat(view.getStatus()).isEqualTo("REJECTED"); assertThat(view.getCanResubmit()).isTrue();
        assertThat(view.getVoucher().getStatus()).isEqualTo("DRAFT");
        assertThat(view.getAdjustments()).hasSize(1);
        verifyNoInteractions(entityManager, arAp);
    }

    @Test void foreignVoucherIsAnErrorRatherThanMissingOrPosted() {
        ledger();
        Voucher voucher = new Voucher(); voucher.setFactoryId("B");
        when(vouchers.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "return-a"))
                .thenReturn(Optional.of(voucher));
        assertThatThrownBy(() -> service.get("A", "return-a"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        verifyNoInteractions(arAp);
    }

    @Test void missingPositiveCreditRemainsVisibleAndZeroAmountIsNotInvented() {
        ledger();
        assertThat(service.get("A", "return-a").getStatus()).isEqualTo("MISSING");
        order.setTotalAmount(BigDecimal.ZERO);
        var zero = service.get("A", "return-a");
        assertThat(zero.getStatus()).isEqualTo("NOT_REQUIRED"); assertThat(zero.getCanResubmit()).isFalse();
        order.setTotalAmount(null);
        assertThat(service.get("A", "return-a").getStatus()).isEqualTo("INCONSISTENT");
        verifyNoInteractions(arAp);
    }
}

package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 🔴🔒 账龄表 CUSTOMER/SUPPLIER 统一按 outstandingAmount 消费 (2026-09-04, follow-up to #577)。
 *
 * <p>形态 C‴ — 这份测试文件此前(#577 终审第 2 条)钉的是一个<b>历史形状</b>:
 * {@code recordArPayment} 从不维护 AR_INVOICE.outstandingAmount, 所以 CUSTOMER 账龄必须恒用
 * {@code invoice.getAmount()}(挂账原值), 否则会读到一个客户已经部分付款、但从未更新过的假数字。
 *
 * <p>现在 {@code recordArPayment} 已经通过
 * {@code ArApServiceImpl#applyPaymentToOpenArInvoices}(FIFO 分配, 见其 javadoc)维护
 * outstandingAmount —— 那份「读它就是读假数字」的前提不再成立, 所以本文件<b>改断言, 不删断言</b>:
 * 两侧统一改成「outstandingAmount 非空就用它, 为空(NEEDS_RECONCILIATION 存量行)才退回 amount」,
 * 并新增一条阴性对照钉住 NEEDS_RECONCILIATION 兜底仍然有效 —— 不能把守卫直接拆掉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl getAgingAnalysis (CUSTOMER/SUPPLIER 统一消费 outstandingAmount, NULL 才退回 amount)")
class ArApServiceImplGetAgingAnalysisTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;

    private ArApServiceImpl service() {
        return new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
    }

    private static final String FACTORY = "F006";

    /** 挂账原值 100, 已被 recordArPayment 核销 70 之后 outstandingAmount = 30 (真实的「还欠多少」)。 */
    private ArApTransaction partiallySettledArInvoice() {
        ArApTransaction t = new ArApTransaction();
        t.setId("AR-PARTIAL-1");
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AR_INVOICE);
        t.setCounterpartyType(CounterpartyType.CUSTOMER);
        t.setCounterpartyId("CUST-001");
        t.setCounterpartyName("测试客户");
        t.setAmount(new BigDecimal("100.00"));
        t.setBalanceAfter(new BigDecimal("100.00"));
        t.setSettledAmount(new BigDecimal("70.00"));
        t.setOutstandingAmount(new BigDecimal("30.00"));
        t.setPaymentStatus(PayablePaymentStatus.PARTIALLY_PAID);
        t.setDueDate(null); // -> bucket "current"
        return t;
    }

    /** 存量回填批次里无法唯一归因的行: settled/outstanding 恒为 NULL (ck_ar_ap_open_item 约束保证)。 */
    private ArApTransaction needsReconciliationArInvoice() {
        ArApTransaction t = new ArApTransaction();
        t.setId("AR-NEEDS-RECON-1");
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AR_INVOICE);
        t.setCounterpartyType(CounterpartyType.CUSTOMER);
        t.setCounterpartyId("CUST-002");
        t.setCounterpartyName("存量客户");
        t.setAmount(new BigDecimal("100.00"));
        t.setBalanceAfter(new BigDecimal("100.00"));
        t.setSettledAmount(null);
        t.setOutstandingAmount(null);
        t.setPaymentStatus(PayablePaymentStatus.NEEDS_RECONCILIATION);
        t.setDueDate(null);
        return t;
    }

    @Test
    @DisplayName("🔴 CUSTOMER 账龄现在按 outstandingAmount(30) 算, 不再是挂账原值 100")
    void customerAgingNowUsesOutstandingAmountAfterPayment() {
        when(transactionRepository.findOverdueReceivables(eq(FACTORY), any(LocalDate.class)))
                .thenReturn(List.of(partiallySettledArInvoice()));

        List<Map<String, Object>> aging = service().getAgingAnalysis(FACTORY, CounterpartyType.CUSTOMER);

        assertEquals(1, aging.size());
        Map<String, Object> row = aging.get(0);
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) row.get("total")),
                "账龄总额必须是 outstandingAmount(30), 不能再是挂账原值 100 —— "
                        + "recordArPayment 现在会维护它, 继续读 amount 就是对已收到的 70 视而不见");
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) row.get("current")),
                "current 桶同样必须是 30, 不是 100");
    }

    @Test
    @DisplayName("🔒 阴性对照: NEEDS_RECONCILIATION 行 outstandingAmount 为 NULL, 必须退回 amount, 不能读出 0/NPE")
    void needsReconciliationRowFallsBackToAmount() {
        when(transactionRepository.findOverdueReceivables(eq(FACTORY), any(LocalDate.class)))
                .thenReturn(List.of(needsReconciliationArInvoice()));

        List<Map<String, Object>> aging = service().getAgingAnalysis(FACTORY, CounterpartyType.CUSTOMER);

        assertEquals(1, aging.size());
        Map<String, Object> row = aging.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("total")),
                "NEEDS_RECONCILIATION 行 outstandingAmount 是 NULL, 必须保守退回挂账原值 100 —— "
                        + "宁可多算「可能还欠着」, 也不能把未知误读成 0 (那会把可能没结清的应收藏起来)");
    }

    @Test
    @DisplayName("SUPPLIER 侧行为不变: outstandingAmount 非空就用它 (回归钉, 防止本次改动误伤 SUPPLIER 分支)")
    void supplierAgingStillUsesOutstandingAmount() {
        ArApTransaction payable = new ArApTransaction();
        payable.setId("AP-1");
        payable.setFactoryId(FACTORY);
        payable.setTransactionType(ArApTransactionType.AP_INVOICE);
        payable.setCounterpartyType(CounterpartyType.SUPPLIER);
        payable.setCounterpartyId("SUP-001");
        payable.setCounterpartyName("测试供应商");
        payable.setAmount(new BigDecimal("200.00"));
        payable.setBalanceAfter(new BigDecimal("200.00"));
        payable.setOutstandingAmount(new BigDecimal("50.00"));
        payable.setDueDate(null);
        when(transactionRepository.findOverduePayables(eq(FACTORY), any(LocalDate.class)))
                .thenReturn(List.of(payable));

        List<Map<String, Object>> aging = service().getAgingAnalysis(FACTORY, CounterpartyType.SUPPLIER);

        assertEquals(1, aging.size());
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) aging.get(0).get("total")));
    }
}

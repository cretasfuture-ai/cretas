package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
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
 * 🔴🔒 账龄表 SUPPLIER-only 守卫回归钉 (2026-09-04, #577 终审第 2 条)。
 *
 * <p>{@link ArApServiceImpl#getAgingAnalysis} 里那句 {@code counterpartyType == CounterpartyType.SUPPLIER}
 * 不是历史遗留写法, 是**必须的**: AR_INVOICE 的 outstandingAmount 目前只在挂账时写一次
 * ({@code initializeOpenItem}), {@code recordArPayment} 从不维护它 —— 客户付款之后它仍是挂账时
 * 那个值, 不再等于「这张发票还欠多少」。CUSTOMER 侧必须继续用 {@code invoice.getAmount()}
 * (挂账原值), 直到 AR 有一条真正的按发票结算路径为止。
 *
 * <p>这条测试直接构造「outstandingAmount 非空且 ≠ amount」的 AR_INVOICE, 逼近「守卫被误判成
 * 已经安全」的最危险场景 —— 如果守卫被放宽到两侧, 这条测试会把账龄算成 30 而不是 100。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl getAgingAnalysis (CUSTOMER 侧必须按 amount 算, 不能按 outstandingAmount)")
class ArApServiceImplGetAgingAnalysisTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;

    private ArApServiceImpl service() {
        return new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
    }

    private static final String FACTORY = "F006";

    /** 挂账原值 100, 而 outstandingAmount 被(错误地)存成了 30 —— 模拟一笔挂账后又被客户部分
     *  付款、但 recordArPayment 从不回写 AR_INVOICE 的场景: outstandingAmount 停留在挂账时的
     *  值(这里故意设成与 amount 不同的 30, 制造一个「两个字段分家」的最坏样本)。 */
    private ArApTransaction staleOutstandingArInvoice() {
        ArApTransaction t = new ArApTransaction();
        t.setId("AR-STALE-1");
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AR_INVOICE);
        t.setCounterpartyType(CounterpartyType.CUSTOMER);
        t.setCounterpartyId("CUST-001");
        t.setCounterpartyName("测试客户");
        t.setAmount(new BigDecimal("100.00"));
        t.setBalanceAfter(new BigDecimal("100.00"));
        t.setOutstandingAmount(new BigDecimal("30.00"));
        t.setDueDate(null); // -> bucket "current"
        return t;
    }

    @Test
    @DisplayName("🔴 回归钉: CUSTOMER 账龄按 amount(100) 算, 即使 outstandingAmount(30) 非空且更小")
    void customerAgingUsesInvoiceAmountNotStaleOutstanding() {
        when(transactionRepository.findOverdueReceivables(eq(FACTORY), any(LocalDate.class)))
                .thenReturn(List.of(staleOutstandingArInvoice()));

        List<Map<String, Object>> aging = service().getAgingAnalysis(FACTORY, CounterpartyType.CUSTOMER);

        assertEquals(1, aging.size());
        Map<String, Object> row = aging.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("total")),
                "账龄总额必须是挂账原值 100, 不能是 outstandingAmount 30 —— "
                        + "AR 的 outstandingAmount 目前只写不读, 消费它就是在读一个可能已经过期的值");
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("current")),
                "current 桶同样必须是 100, 不是 30");
    }
}

package com.cretas.aims.service.report.impl;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PROTO-DEMO-ARAP (2026-11-18): 「经营分析 → 往来余额」与「财务管理 → 应收应付」必须是同一个数、同一份实现。
 *
 * <p>prod 实测 (LSMDEMO, 由 F006 整套复制): ar_ap_transactions 51 行全部 deleted_at 非空 ⇒ 应收应付页
 * (ArApService#getFinanceOverview, 挂账单据口径) 显示 ¥0; 经营分析页 (/reports/finance) 却读
 * customers / suppliers.current_balance 缓存 ⇒ 应收 22,276 / 应付 2,888,709.72, 与任何未删除挂账单据都对不上。
 *
 * <p>这里用真 JPA (H2) 落挂账单据, 客户/供应商仓库故意返回那组过期缓存 —— 报表若还读缓存就会红。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.finance")
@DisplayName("经营分析往来余额 == 应收应付页 (同一份挂账单据实现)")
class FinanceReportArApSameSourceJpaContractTest {

    private static final String FACTORY = "F-ARAP-SAME-A";
    private static final String OTHER_FACTORY = "F-ARAP-SAME-B";
    private static final BigDecimal STALE_CUSTOMER_BALANCE = new BigDecimal("22276.00");
    private static final BigDecimal STALE_SUPPLIER_BALANCE = new BigDecimal("2888709.72");
    private static final BigDecimal STALE_PREPAYMENT = new BigDecimal("11433.62");

    @Autowired private ArApTransactionRepository transactions;

    private ArApService arApService;
    private ProductionReportServiceImpl reports;

    @BeforeEach
    void setUp() throws Exception {
        CustomerRepository customers = mock(CustomerRepository.class);
        SupplierRepository suppliers = mock(SupplierRepository.class);
        // 主数据上的 current_balance 缓存 (LSMDEMO 实测值): 报表若还读它, 下面每条断言都会红
        when(customers.calculateTotalOutstandingBalance(anyString())).thenReturn(STALE_CUSTOMER_BALANCE);
        when(customers.calculateTotalPrepayments(anyString())).thenReturn(STALE_PREPAYMENT);
        when(suppliers.calculateTotalOutstandingBalance(anyString())).thenReturn(STALE_SUPPLIER_BALANCE);

        arApService = new ArApServiceImpl(transactions, customers, suppliers);

        Constructor<?> ctor = ProductionReportServiceImpl.class.getConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == CustomerRepository.class) args[i] = customers;
            else if (types[i] == SupplierRepository.class) args[i] = suppliers;
            else args[i] = mock(types[i]);
        }
        reports = (ProductionReportServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(reports, "arApService", arApService);
    }

    @Test
    @DisplayName("LSMDEMO 形状: 挂账单据全被软删 ⇒ 两页都是 0, 且如实标「尚未生成挂账单据」, 不拿主数据缓存凑数")
    void softDeletedLedgerOnly_bothPagesZero_andSayNoLedger() {
        save(FACTORY, ArApTransactionType.AR_INVOICE, CounterpartyType.CUSTOMER, "C1", "1372.50", true);
        save(FACTORY, ArApTransactionType.AP_INVOICE, CounterpartyType.SUPPLIER, "S1", "9758.93", true);
        // 阴性对照: 另一个厂的未删除单据不能计入本厂
        save(OTHER_FACTORY, ArApTransactionType.AR_INVOICE, CounterpartyType.CUSTOMER, "C9", "99999.00", false);
        save(OTHER_FACTORY, ArApTransactionType.AP_INVOICE, CounterpartyType.SUPPLIER, "S9", "77777.00", false);

        Map<String, Object> arApPage = arApService.getFinanceOverview(FACTORY);
        Map<String, Object> analysisPage = reports.getFinanceReport(FACTORY, LocalDate.of(2020, 1, 1), LocalDate.now());

        assertThat((BigDecimal) arApPage.get("totalReceivable")).isEqualByComparingTo("0");
        assertThat((BigDecimal) arApPage.get("totalPayable")).isEqualByComparingTo("0");
        assertThat(arApPage.get("hasLedgerEntries")).isEqualTo(false);
        assertThat(arApPage.get("ledgerEntryCount")).isEqualTo(0L);

        assertThat((BigDecimal) analysisPage.get("accountsReceivable"))
                .isEqualByComparingTo((BigDecimal) arApPage.get("totalReceivable"))
                .isNotEqualByComparingTo(STALE_CUSTOMER_BALANCE);
        assertThat((BigDecimal) analysisPage.get("accountsPayable"))
                .isEqualByComparingTo((BigDecimal) arApPage.get("totalPayable"))
                .isNotEqualByComparingTo(STALE_SUPPLIER_BALANCE);
        assertThat((BigDecimal) analysisPage.get("customerPrepayments")).isEqualByComparingTo("0");
        assertThat(analysisPage.get("hasArApLedgerEntries")).isEqualTo(false);
        assertThat((String) analysisPage.get("arApNote")).contains("尚未生成挂账单据");
    }

    @Test
    @DisplayName("有挂账单据: 经营分析 == 应收应付页 (净额/预收/条数), 跨厂单据不计入")
    void liveLedger_analysisEqualsArApPage_crossFactoryExcluded() {
        save(FACTORY, ArApTransactionType.AR_INVOICE, CounterpartyType.CUSTOMER, "C1", "1000.00", false);
        save(FACTORY, ArApTransactionType.AR_PAYMENT, CounterpartyType.CUSTOMER, "C1", "-300.00", false);
        // C2 先收款未发货 ⇒ 预收 150, 在应收净额中抵减
        save(FACTORY, ArApTransactionType.AR_PAYMENT, CounterpartyType.CUSTOMER, "C2", "-150.00", false);
        save(FACTORY, ArApTransactionType.AP_INVOICE, CounterpartyType.SUPPLIER, "S1", "800.00", false);
        // 已软删的不算
        save(FACTORY, ArApTransactionType.AP_INVOICE, CounterpartyType.SUPPLIER, "S1", "5000.00", true);
        // 阴性对照: 跨厂
        save(OTHER_FACTORY, ArApTransactionType.AR_INVOICE, CounterpartyType.CUSTOMER, "C9", "99999.00", false);
        save(OTHER_FACTORY, ArApTransactionType.AP_INVOICE, CounterpartyType.SUPPLIER, "S9", "77777.00", false);

        Map<String, Object> arApPage = arApService.getFinanceOverview(FACTORY);
        Map<String, Object> analysisPage = reports.getFinanceReport(FACTORY, LocalDate.of(2020, 1, 1), LocalDate.now());

        assertThat((BigDecimal) arApPage.get("totalReceivable")).isEqualByComparingTo("550.00");
        assertThat((BigDecimal) arApPage.get("totalPayable")).isEqualByComparingTo("800.00");
        assertThat((BigDecimal) arApPage.get("customerPrepayments")).isEqualByComparingTo("150.00");
        assertThat(arApPage.get("ledgerEntryCount")).isEqualTo(4L);
        assertThat(arApPage.get("hasLedgerEntries")).isEqualTo(true);

        assertThat((BigDecimal) analysisPage.get("accountsReceivable"))
                .isEqualByComparingTo((BigDecimal) arApPage.get("totalReceivable"));
        assertThat((BigDecimal) analysisPage.get("accountsPayable"))
                .isEqualByComparingTo((BigDecimal) arApPage.get("totalPayable"));
        assertThat((BigDecimal) analysisPage.get("customerPrepayments"))
                .isEqualByComparingTo((BigDecimal) arApPage.get("customerPrepayments"));
        assertThat(analysisPage.get("arApLedgerEntryCount")).isEqualTo(4L);
        assertThat(analysisPage).doesNotContainKey("arApNote");
    }

    private void save(String factoryId, ArApTransactionType type, CounterpartyType cpType, String cpId,
                      String amount, boolean deleted) {
        ArApTransaction t = new ArApTransaction();
        t.setFactoryId(factoryId);
        t.setTransactionNumber("T-" + System.nanoTime());
        t.setTransactionType(type);
        t.setCounterpartyType(cpType);
        t.setCounterpartyId(cpId);
        t.setCounterpartyName(cpId);
        t.setAmount(new BigDecimal(amount));
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setTransactionDate(LocalDate.now());
        t.setApprovalStatus(ArApApprovalStatus.APPROVED);
        if (deleted) t.setDeletedAt(LocalDateTime.now());
        transactions.saveAndFlush(t);
    }
}

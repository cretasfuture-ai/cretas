package com.cretas.aims.controller.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 应收单维度清单 (G6): 真实 JPA Context 跑原生 SQL; 待收为负不算逾期, 未知待收不冒充逾期. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ReceivableItemsRepositoryQueryValidationTest {

    private static final String F = "IT-AR-ITEMS";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    @Autowired private ArApTransactionRepository repo;
    private int seq;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        add("A", "CUS-1", ArApTransactionType.AR_INVOICE, "1000", "400", TODAY.minusDays(10));   // 逾期 10 天
        add("B", "CUS-1", ArApTransactionType.AR_INVOICE, "500", "-50", TODAY.minusDays(5));     // 待收为负, 不逾期
        add("C", "CUS-2", ArApTransactionType.AR_INVOICE, "300", "300", TODAY.plusDays(3));      // 未到期
        add("D", "CUS-1", ArApTransactionType.AR_INVOICE, "200", "0", TODAY.minusDays(20));      // 已结清
        add("E", "CUS-1", ArApTransactionType.AR_INVOICE, "100", null, TODAY.minusDays(30));     // 待收未知
        add("F", "CUS-1", ArApTransactionType.AR_PAYMENT, "100", "100", TODAY.minusDays(30));    // 非应收单
        repo.flush();
    }

    private void add(String no, String cp, ArApTransactionType type, String amount, String outstanding, LocalDate due) {
        ArApTransaction t = new ArApTransaction();
        t.setFactoryId(F);
        t.setTransactionNumber("AR-" + no + (++seq));
        t.setTransactionType(type);
        t.setCounterpartyType(CounterpartyType.CUSTOMER);
        t.setCounterpartyId(cp);
        t.setCounterpartyName("客户" + cp);
        t.setAmount(new BigDecimal(amount));
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setOutstandingAmount(outstanding == null ? null : new BigDecimal(outstanding));
        t.setTransactionDate(TODAY.minusDays(40));
        t.setDueDate(due);
        repo.save(t);
    }

    private List<String> nos(org.springframework.data.domain.Page<ArApTransaction> p) {
        return p.getContent().stream().map(x -> x.getTransactionNumber().substring(3, 4)).toList();
    }

    private org.springframework.data.domain.Page<ArApTransaction> q(String cp, int unsettled, int overdue,
                                                                    LocalDate from, LocalDate to) {
        return repo.findReceivableItems(F, cp, from, to, unsettled, overdue, TODAY, PageRequest.of(0, 20));
    }

    @Test
    void filtersByOverdueUnsettledCounterpartyAndDueRange() {
        assertThat(nos(q(null, 0, 0, null, null))).containsExactly("E", "D", "A", "B", "C"); // 仅应收单, 按到期日升序
        assertThat(nos(q(null, 0, 1, null, null))).containsExactly("A");                    // 逾期: 待收>0 且已过期
        assertThat(nos(q(null, 1, 0, null, null))).containsExactly("A", "C");              // 未结清: 待收>0
        assertThat(nos(q("CUS-2", 0, 0, null, null))).containsExactly("C");
        assertThat(nos(q(null, 0, 0, TODAY.minusDays(12), TODAY.minusDays(4)))).containsExactly("A", "B");
        assertThat(q(null, 0, 1, null, null).getTotalElements()).isEqualTo(1);
    }

    @Test
    void rowComputesOverdueDaysAndNeverFlagsNegativeOrUnknown() {
        Map<String, ArApTransaction> by = new java.util.HashMap<>();
        for (ArApTransaction t : q(null, 0, 0, null, null).getContent()) {
            by.put(t.getTransactionNumber().substring(3, 4), t);
        }
        Map<String, Object> a = ArApController.receivableRow(by.get("A"), TODAY);
        assertThat(a.get("overdue")).isEqualTo(true);
        assertThat(a.get("overdueDays")).isEqualTo(10L);

        Map<String, Object> b = ArApController.receivableRow(by.get("B"), TODAY);
        assertThat(b.get("overdue")).isEqualTo(false);   // 待收为负
        assertThat(b.get("overdueDays")).isEqualTo(0L);

        Map<String, Object> e = ArApController.receivableRow(by.get("E"), TODAY);
        assertThat(e.get("overdue")).isNull();           // 待收未知: 不冒充逾期也不冒充未逾期
        assertThat(e.get("overdueDays")).isNull();

        assertThat(ArApController.receivableRow(by.get("C"), TODAY).get("overdue")).isEqualTo(false);
    }
}

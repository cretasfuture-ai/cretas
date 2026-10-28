package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S5-11: 当月没有会计期间行时，凭证期间闸不能静默放行 —— 若其后某月已结账，该月视同已结账。
 * 真实 JPA 仓库 + 生产的 assertOpen；另一个工厂的结账不影响本厂（租户阴性对照）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class AccountingPeriodMissingRowGateJpaTest {

    @Autowired private AccountingPeriodRepository repo;
    @Autowired private EntityManager em;

    private void period(String factory, int year, int month, AccountingPeriod.Status status) {
        em.persist(AccountingPeriod.builder().factoryId(factory).year(year).month(month).status(status).build());
        em.flush();
    }

    @Test
    void monthWithoutRowBeforeAClosedMonthIsRejected() {
        period("S511-A", 2026, 9, AccountingPeriod.Status.CLOSED);
        AccountingPeriodServiceImpl service = new AccountingPeriodServiceImpl(repo);

        BusinessException e = assertThrows(BusinessException.class, () -> service.assertOpen("S511-A", 2026, 3));
        assertThat(e.getMessage()).contains("2026-03").contains("2026-09");
        assertThat(e.getErrorCode()).isEqualTo("PERIOD_MISSING_BEFORE_CLOSED");
        // 跨年同理
        assertThrows(BusinessException.class, () -> service.assertOpen("S511-A", 2025, 12));
    }

    @Test
    void monthWithoutRowAfterLastCloseOrOtherFactoryCloseStaysOpen() {
        period("S511-B", 2026, 9, AccountingPeriod.Status.CLOSED);
        period("S511-B", 2026, 8, AccountingPeriod.Status.OPEN);
        period("S511-OTHER", 2026, 12, AccountingPeriod.Status.CLOSED);
        AccountingPeriodServiceImpl service = new AccountingPeriodServiceImpl(repo);

        assertDoesNotThrow(() -> service.assertOpen("S511-B", 2026, 10));   // 最后一次结账之后
        assertDoesNotThrow(() -> service.assertOpen("S511-B", 2026, 8));    // 有 OPEN 行按行判
        assertDoesNotThrow(() -> service.assertOpen("S511-C", 2026, 3));    // 从没结过账
        // 别的工厂 12 月结账不影响本厂 10 月
        assertDoesNotThrow(() -> service.assertOpen("S511-B", 2026, 11));
    }
}

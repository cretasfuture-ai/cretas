package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.service.voucher.VoucherPostingLock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class VoucherPostingRepositoryQueryValidationContractTest {
    @Autowired private EntityManager entityManager;
    @Autowired private VoucherRepository repository;

    @Test
    void scopedRowLockRefreshesStatusAndEntriesInRealHibernateContext() {
        Voucher voucher = Voucher.builder().factoryId("P5-LOCK-A").voucherNumber("V-LOCK-1")
                .voucherType(VoucherType.EXPENSE).voucherDate(LocalDate.now())
                .sourceBusinessType("LOCK_TEST").sourceBusinessId("source")
                .status(VoucherStatus.DRAFT).totalDebit(BigDecimal.TEN).totalCredit(BigDecimal.TEN).build();
        VoucherEntry debit = VoucherEntry.builder().voucher(voucher).lineNo(1).subjectCode("1122")
                .subjectName("应收账款").debit(BigDecimal.TEN).credit(BigDecimal.ZERO).build();
        VoucherEntry credit = VoucherEntry.builder().voucher(voucher).lineNo(2).subjectCode("1001")
                .subjectName("库存现金").debit(BigDecimal.ZERO).credit(BigDecimal.TEN).build();
        voucher.getEntries().add(debit); voucher.getEntries().add(credit);
        repository.saveAndFlush(voucher);
        entityManager.clear();
        Voucher loaded = repository.findByIdAndFactoryIdAndDeletedAtIsNull(voucher.getId(), "P5-LOCK-A").orElseThrow();
        assertEquals(VoucherStatus.DRAFT, loaded.getStatus());
        entityManager.createNativeQuery("update vouchers set status='POSTED' where id=:id")
                .setParameter("id", voucher.getId()).executeUpdate();
        assertTrue(VoucherPostingLock.lockAndRefresh(entityManager, loaded, "P5-LOCK-A"));
        assertEquals(VoucherStatus.POSTED, loaded.getStatus());
        assertEquals(2, loaded.getEntries().size());
        loaded.validateBalanced();
        assertFalse(VoucherPostingLock.lockAndRefresh(entityManager, loaded, "P5-LOCK-B"));
        assertTrue(repository.findByIdAndFactoryIdAndDeletedAtIsNull(voucher.getId(), "P5-LOCK-B").isEmpty());
    }
}

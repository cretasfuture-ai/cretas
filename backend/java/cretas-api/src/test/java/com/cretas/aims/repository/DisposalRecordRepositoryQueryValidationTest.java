package com.cretas.aims.repository;

import com.cretas.aims.entity.DisposalRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate repository startup gate for disposal applicant audit columns. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class DisposalRecordRepositoryQueryValidationTest {

    @Autowired DisposalRecordRepository repository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test void disposalVoucherHistoryQueryIsFactoryAndExactRecordScoped() {
        for (String[] row : new String[][]{{"a","F-A","5"},{"b","F-B","5#r3"},{"c","F-A","5#r2"},{"d","F-A","50#r2"}}) {
            var voucher=com.cretas.aims.entity.finance.Voucher.builder().id(row[0]).factoryId(row[1]).voucherNumber("V-"+row[0])
                    .voucherType(com.cretas.aims.entity.enums.VoucherType.EXPENSE).voucherDate(java.time.LocalDate.of(2026,10,18))
                    .sourceBusinessType(row[2].contains("#")?"DISPOSAL_RECORD_REVISION":"DISPOSAL_RECORD").sourceBusinessId(row[2])
                    .status(com.cretas.aims.entity.enums.VoucherStatus.POSTED).totalDebit(BigDecimal.ONE).totalCredit(BigDecimal.ONE).build();
            entityManager.persist(voucher);
        }
        entityManager.flush();entityManager.clear();
        var service=new com.cretas.aims.service.finance.DisposalCostAccountingService(null,entityManager,null,null);
        assertThat(service.getAccountingHistory("F-A",5L)).extracting(com.cretas.aims.entity.finance.Voucher::getId).containsExactly("a","c");
    }

    @Test
    void applicantAuditFieldsPersistAndRepositoryStarts() {
        DisposalRecord record = new DisposalRecord();
        record.setFactoryId("F-JPA-DISPOSAL");
        record.setDisposalType("QUALITY_ISSUE");
        record.setDisposalQuantity(BigDecimal.ONE);
        record.setApplicantId(1309);
        record.setApplicantName("f006_admin");

        DisposalRecord saved = repository.saveAndFlush(record);
        DisposalRecord reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getApplicantId()).isEqualTo(1309);
        assertThat(reloaded.getApplicantName()).isEqualTo("f006_admin");
    }
}

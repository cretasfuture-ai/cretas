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

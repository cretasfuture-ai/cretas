package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the real Hibernate repository so the tenant-scoped pessimistic query is parsed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReportReversalLogRepositoryQueryValidationTest {

    @Autowired
    private ReportReversalLogRepository repository;

    @Test
    void tenantScopedApprovalLockQueryBoots() {
        assertThat(repository).isNotNull();
    }
}

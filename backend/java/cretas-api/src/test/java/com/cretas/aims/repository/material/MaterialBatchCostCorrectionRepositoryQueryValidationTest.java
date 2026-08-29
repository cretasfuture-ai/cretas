package com.cretas.aims.repository.material;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class MaterialBatchCostCorrectionRepositoryQueryValidationTest {
    @Autowired MaterialBatchCostCorrectionRepository repository;

    @Test
    void derivedAuditFinderBootsInRealJpaContext() {
        assertThat(repository.findByMaterialBatchIdOrderByCorrectedAtDesc("not-found")).isEmpty();
    }
}

package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionReportCorrection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductionReportCorrectionRepositoryQueryValidationTest {
    @Autowired ProductionReportCorrectionRepository repository;

    @Test
    void correctionEntityPersistsAndDerivedQueriesStart() {
        ProductionReportCorrection correction = ProductionReportCorrection.builder()
                .factoryId("F-JPA-CORRECTION").productionReportId(991L)
                .productionPlanId("PLAN-JPA-1").productTypeId("SKU-JPA-1")
                .status(ProductionReportCorrection.Status.PENDING_APPROVAL)
                .correctionReason("核对差异").submittedBy(1L).build();
        ProductionReportCorrection saved = repository.saveAndFlush(correction);

        assertThat(repository.findByIdAndFactoryId(saved.getId(), "F-JPA-CORRECTION")).isPresent();
        assertThat(repository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                "F-JPA-CORRECTION", 991L, ProductionReportCorrection.Status.PENDING_APPROVAL)).isPresent();
        assertThat(repository.findByFactoryIdAndProductionPlanIdInAndStatusOrderByAppliedAtDesc(
                "F-JPA-CORRECTION", java.util.List.of("PLAN-JPA-1"),
                ProductionReportCorrection.Status.PENDING_APPROVAL)).hasSize(1);
        assertThat(repository.findByFactoryIdAndProductTypeIdInAndStatusOrderByAppliedAtDesc(
                "F-JPA-CORRECTION", java.util.List.of("SKU-JPA-1"),
                ProductionReportCorrection.Status.PENDING_APPROVAL)).hasSize(1);
    }
}

package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Hibernate and executes the effective pending predicate against real
 * rows.  This protects tenant/soft-delete/completed-batch scoping, terminal
 * NOT EXISTS semantics, duplicate-PENDING fail-closed behavior, and the
 * count/page contract consumed by the quality task queue.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Transactional
class QualityInspectionPendingRepositoryQueryValidationTest {

    private static final String FACTORY = "QI-PENDING-FACTORY";

    @Test
    void completionLockQueryBootsAndIsFactoryScoped() {
        assertThat(repository.findByFactoryIdAndProductionBatchIdForUpdate(
                "__missing_factory__", 1L)).isEmpty();
        assertThat(repository.findByIdAndFactoryIdForUpdate(
                "__missing_factory__", "missing-inspection")).isEmpty();
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private QualityInspectionRepository repository;

    @Test
    void effectivePendingQueryCountsAndPagesOnlyOneUnambiguousOpenTaskPerFactory() {
        User inspector = persistFactoryAndInspector();
        ProductionBatch eligibleA = persistBatch("eligible-a", ProductionBatchStatus.COMPLETED);
        ProductionBatch eligibleB = persistBatch("eligible-b", ProductionBatchStatus.COMPLETED);
        ProductionBatch passBatch = persistBatch("pass", ProductionBatchStatus.COMPLETED);
        ProductionBatch failBatch = persistBatch("fail", ProductionBatchStatus.COMPLETED);
        ProductionBatch conditionalBatch = persistBatch("conditional", ProductionBatchStatus.COMPLETED);
        ProductionBatch duplicateBatch = persistBatch("duplicate", ProductionBatchStatus.COMPLETED);
        ProductionBatch openBatch = persistBatch("open", ProductionBatchStatus.IN_PROGRESS);
        ProductionBatch deletedBatch = persistBatch("deleted", ProductionBatchStatus.COMPLETED);
        ProductionBatch otherFactoryBatch = persistBatch("other", ProductionBatchStatus.COMPLETED);
        otherFactoryBatch.setFactoryId("QI-PENDING-OTHER");

        persistInspection(inspector, eligibleA, " PENDING ");
        persistInspection(inspector, eligibleB, "PENDING");
        persistInspection(inspector, passBatch, "PENDING");
        persistInspection(inspector, passBatch, "PASS");
        persistInspection(inspector, failBatch, "PENDING");
        persistInspection(inspector, failBatch, "FAILED");
        persistInspection(inspector, conditionalBatch, "PENDING");
        persistInspection(inspector, conditionalBatch, "CONDITIONAL");
        persistInspection(inspector, duplicateBatch, "PENDING");
        persistInspection(inspector, duplicateBatch, "PENDING");
        persistInspection(inspector, openBatch, "PENDING");
        QualityInspection deleted = persistInspection(inspector, deletedBatch, "PENDING");
        deleted.setDeletedAt(LocalDateTime.now());
        QualityInspection other = persistInspection(inspector, otherFactoryBatch, "PENDING");
        other.setFactoryId("QI-PENDING-OTHER");
        entityManager.flush();
        entityManager.clear();

        PageRequest first = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "inspectionDate"));
        Page<QualityInspection> firstPage = repository.findEffectivePendingByFactoryId(FACTORY, first);
        Page<QualityInspection> secondPage = repository.findEffectivePendingByFactoryId(
                FACTORY, first.next());

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent()).extracting(QualityInspection::getProductionBatchId)
                .doesNotContain(passBatch.getId(), failBatch.getId(), conditionalBatch.getId(),
                        duplicateBatch.getId(), openBatch.getId(), deletedBatch.getId(), otherBatchId(otherFactoryBatch));
        assertThat(secondPage.getContent()).extracting(QualityInspection::getProductionBatchId)
                .doesNotContain(passBatch.getId(), failBatch.getId(), conditionalBatch.getId(),
                        duplicateBatch.getId(), openBatch.getId(), deletedBatch.getId(), otherBatchId(otherFactoryBatch));
    }

    private Long otherBatchId(ProductionBatch batch) {
        return batch.getId();
    }

    private User persistFactoryAndInspector() {
        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setName(FACTORY);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);

        User inspector = new User();
        inspector.setFactoryId(FACTORY);
        inspector.setUsername("qi-pending-" + UUID.randomUUID());
        inspector.setPasswordHash("test-only");
        inspector.setIsActive(true);
        entityManager.persist(inspector);
        entityManager.flush();
        return inspector;
    }

    private ProductionBatch persistBatch(String suffix, ProductionBatchStatus status) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("QIP-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setProductTypeId("QI-PENDING-PRODUCT");
        batch.setPlannedQuantity(BigDecimal.ONE);
        batch.setQuantity(BigDecimal.ONE);
        batch.setUnit("kg");
        batch.setStatus(status);
        batch.setCustomFields(null);
        entityManager.persist(batch);
        entityManager.flush();
        return batch;
    }

    private QualityInspection persistInspection(User inspector, ProductionBatch batch, String result) {
        QualityInspection inspection = new QualityInspection();
        inspection.setId(UUID.randomUUID().toString());
        inspection.setFactoryId(batch.getFactoryId());
        inspection.setProductionBatchId(batch.getId());
        inspection.setInspectorId(inspector.getId());
        inspection.setInspectionDate(LocalDate.of(2026, 10, 29));
        inspection.setSampleSize(BigDecimal.ONE);
        inspection.setPassCount("PASS".equals(result) ? BigDecimal.ONE : BigDecimal.ZERO);
        inspection.setFailCount("PASS".equals(result) ? BigDecimal.ZERO : BigDecimal.ONE);
        inspection.setPassRate("PASS".equals(result) ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        inspection.setResult(result);
        inspection.setCustomFields(null);
        entityManager.persist(inspection);
        return inspection;
    }
}

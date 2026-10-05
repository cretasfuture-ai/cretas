package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and non-empty predicate checks for R54 inventory scope queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r54_inventory_scope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class MaterialBatchInventoryScopedRepositoryQueryValidationTest {
    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";

    @Autowired
    private MaterialBatchRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void allowIsolatedRows() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    @Test
    void statusScopeQueriesApplyFactoryCreatorStatusWarehouseAndMaterialPredicates() {
        repository.saveAndFlush(batch("A", FACTORY, 101L, MaterialBatchStatus.AVAILABLE, "RM-1", "WH-RAW"));
        repository.saveAndFlush(batch("B", FACTORY, 202L, MaterialBatchStatus.AVAILABLE, "RM-1", "WH-RAW"));
        repository.saveAndFlush(batch("WIP", FACTORY, 101L, MaterialBatchStatus.PRODUCING_RESERVED, "RM-1", "WH-RAW"));
        repository.saveAndFlush(batch("FOREIGN", OTHER_FACTORY, 101L, MaterialBatchStatus.AVAILABLE, "RM-1", "WH-RAW"));

        assertThat(repository.findByFactoryIdAndStatusAndCreatedByIn(
                FACTORY, MaterialBatchStatus.AVAILABLE, List.of(101L)))
                .extracting(MaterialBatch::getId).containsExactly("A");
        assertThat(repository.findByFactoryIdAndStatusAndCreatedByInAndWarehouseId(
                FACTORY, MaterialBatchStatus.AVAILABLE, List.of(101L), "WH-RAW"))
                .extracting(MaterialBatch::getId).containsExactly("A");
        assertThat(repository.findByFactoryIdAndStatusAndMaterialTypeIdInAndWarehouseId(
                FACTORY, MaterialBatchStatus.AVAILABLE, List.of("RM-1"), "WH-RAW"))
                .extracting(MaterialBatch::getId).containsExactly("A", "B");
    }

    @Test
    void expiringAndExpiredScopeQueriesApplyCreatorAndDatePredicates() {
        repository.saveAndFlush(expiringBatch("EXP-A", FACTORY, 101L, 2));
        repository.saveAndFlush(expiringBatch("EXP-B", FACTORY, 202L, 2));
        repository.saveAndFlush(expiringBatch("OLD-A", FACTORY, 101L, -2));

        assertThat(repository.findExpiringBatchesByCreatedByIn(
                FACTORY, LocalDate.now().plusDays(7), List.of(101L)))
                .extracting(MaterialBatch::getId).containsExactly("EXP-A");
        assertThat(repository.findExpiredBatchesByCreatedByIn(
                FACTORY, LocalDate.now(), List.of(101L)))
                .extracting(MaterialBatch::getId).containsExactly("OLD-A");
    }

    private static MaterialBatch batch(String id, String factoryId, Long creator,
                                       MaterialBatchStatus status, String materialTypeId,
                                       String warehouseId) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("BN-" + id);
        batch.setCreatedBy(creator);
        batch.setMaterialTypeId(materialTypeId);
        batch.setWarehouseId(warehouseId);
        batch.setReceiptDate(LocalDate.now().minusDays(10));
        batch.setExpireDate(LocalDate.now().plusDays(10));
        batch.setReceiptQuantity(new BigDecimal("10"));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setStatus(status);
        return batch;
    }

    private static MaterialBatch expiringBatch(String id, String factoryId, Long creator, long days) {
        MaterialBatch batch = batch(id, factoryId, creator, MaterialBatchStatus.AVAILABLE, "RM-1", "WH-RAW");
        batch.setExpireDate(LocalDate.now().plusDays(days));
        return batch;
    }
}

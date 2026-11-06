package com.cretas.aims.repository.ai;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and mutation gate for the product-create receipt locator/CAS queries. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:product_create_receipt_query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = ToolExecutionIdempotencyRecord.class)
@EnableJpaRepositories(basePackageClasses = ToolExecutionIdempotencyRepository.class)
class ProductCreateReceiptRepositoryQueryValidationTest {

    @Autowired
    private ToolExecutionIdempotencyRepository repository;

    @Test
    void startsHibernateAndAdvancesOnlyMatchingFullLocator() {
        ToolExecutionIdempotencyRecord row = record("receipt-query-1", "command-1", "confirmation-1");
        repository.saveAndFlush(row);

        assertThat(repository.findByFullProductCreateLocator(
                "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-1"), hash("command-1"), hash("confirmation-1"))).isPresent();
        assertThat(repository.attachProductCreateReference(
                "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-1"), hash("command-1"), hash("confirmation-1"),
                ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                "PRODUCT_TYPE", "P-1", "PRODUCT_CREATED")).isEqualTo(1);

        ToolExecutionIdempotencyRecord attached = repository.findById(row.getId()).orElseThrow();
        assertThat(attached.getResultEntityType()).isEqualTo("PRODUCT_TYPE");
        assertThat(attached.getResultEntityId()).isEqualTo("P-1");
        assertThat(attached.getResultReferenceStage()).isEqualTo("PRODUCT_CREATED");
        assertThat(repository.advanceProductCreateReference(
                row.getId(), "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-1"), hash("command-1"), hash("confirmation-1"),
                ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                "PRODUCT_TYPE", "P-1", "PRODUCT_CREATED", "COPY_COMMITTED")).isEqualTo(1);

        ToolExecutionIdempotencyRecord completed = repository.findById(row.getId()).orElseThrow();
        assertThat(repository.completeFromState(
                row.getId(), ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionIdempotencyRecord.State.SUCCEEDED, ToolExecutionStatus.SUCCEEDED,
                "TOOL_SUCCEEDED", LocalDateTime.now())).isEqualTo(1);
        ToolExecutionIdempotencyRecord replay = repository.findById(row.getId()).orElseThrow();
        assertThat(replay.getResultEntityId()).isEqualTo(completed.getResultEntityId());
        assertThat(replay.getResultReferenceStage()).isEqualTo("COPY_COMMITTED");
        assertThat(repository.advanceProductCreateReference(
                row.getId(), "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("wrong"), hash("command-1"), hash("confirmation-1"),
                ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                "PRODUCT_TYPE", "P-1", "COPY_COMMITTED", "READBACK_VERIFIED")).isZero();
    }

    @Test
    void migrationRetainsNullOldRowsAndRejectsPartialReferenceShapeByContract() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261106_2__product_create_command_result_reference.sql"));
        assertThat(sql).contains("result_entity_type VARCHAR(64)")
                .contains("result_entity_id VARCHAR(128)")
                .contains("result_reference_stage VARCHAR(32)")
                .contains("num_nonnulls(result_entity_type, result_entity_id, result_reference_stage) = 0")
                .contains("num_nonnulls(result_entity_type, result_entity_id, result_reference_stage) = 3")
                .doesNotContain("idx_tei_product_result_reference");
    }

    private static ToolExecutionIdempotencyRecord record(String id, String command, String confirmation) {
        return ToolExecutionIdempotencyRecord.reserve(
                id, "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-1"), hash(command), hash(confirmation), "audit-" + id, LocalDateTime.now());
    }

    private static String hash(String value) {
        return ToolCommandDigest.persistentSecretFingerprint(value);
    }
}

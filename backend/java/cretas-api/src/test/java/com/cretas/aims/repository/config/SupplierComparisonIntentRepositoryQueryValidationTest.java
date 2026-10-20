package com.cretas.aims.repository.config;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.execution.SupplierComparisonReadIntent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real repository authority; an inactive or foreign row must never become an absent-row default. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = AIIntentConfig.class)
@EnableJpaRepositories(basePackageClasses = AIIntentConfigRepository.class)
class SupplierComparisonIntentRepositoryQueryValidationTest {
    @Autowired AIIntentConfigRepository repository;
    @Autowired EntityManager entityManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/cretas_workflow_verify_test")) {
                throw new IllegalArgumentException("Real PG verification requires the owned loopback test database");
            }
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:supplier_comparison;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void bindActualDatabaseEvidence() {
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            var metadata = connection.getMetaData();
            System.out.printf("[SUPPLIER_COMPARISON_DB] product=%s version=%s driver=%s%n",
                    metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(), metadata.getDriverName());
            if (System.getenv("CRETAS_WORKFLOW_PG_URL") != null) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
                String major = System.getenv("CRETAS_EXPECTED_PG_MAJOR");
                if (major != null) assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(Integer.parseInt(major));
                assertThat(connection.getCatalog()).isEqualTo("cretas_workflow_verify_test");
            }
        });
    }

    @Test
    void missingRowResolvesWithoutPersistingAConfiguration() {
        long before = repository.count();
        assertThat(resolve()).hasValueSatisfying(intent -> {
            assertThat(intent.getToolName()).isEqualTo("supplier_price_comparison");
            assertThat(intent.getId()).isNull();
        });
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void actualQueryRetainsInactiveRestriction() {
        save("F006", false);
        assertThat(repository.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE))
                .hasValueSatisfying(row -> assertThat(row.getIsActive()).isFalse());
        assertThat(resolve()).isEmpty();
    }

    @Test
    void foreignFactoryCannotFallThroughToCodeOwnedMetadata() {
        save("OTHER", true);
        assertThat(resolve()).isEmpty();
    }

    @Test
    void platformRestrictionIsReturnedVerbatim() {
        AIIntentConfig saved = save(null, true);
        assertThat(resolve()).hasValueSatisfying(row -> {
            assertThat(row.getId()).isEqualTo(saved.getId());
            assertThat(row.getRequiredPermission()).isEqualTo("restricted:read");
            assertThat(row.getRequiresApproval()).isTrue();
        });
    }

    private Optional<AIIntentConfig> resolve() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(SupplierComparisonReadIntent.TOOL))
                .thenReturn(Optional.of(new SupplierPriceComparisonTool()));
        return SupplierComparisonReadIntent.resolve("F006", registry, repository);
    }

    private AIIntentConfig save(String factoryId, boolean active) {
        // This lane only reads existing metadata. Seed minimal real database columns directly:
        // This does not claim to verify legacy String/jsonb entity write compatibility.
        entityManager.createNativeQuery("INSERT INTO ai_intent_configs "
                + "(id,intent_code,intent_name,tool_name,factory_id,is_active,required_permission,requires_approval,created_at,updated_at) "
                + "VALUES (:id,:code,'comparison',:tool,:factory,:active,'restricted:read',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .setParameter("id", java.util.UUID.randomUUID().toString())
                .setParameter("code", SupplierComparisonReadIntent.CODE)
                .setParameter("tool", SupplierComparisonReadIntent.TOOL)
                .setParameter("factory", factoryId).setParameter("active", active).executeUpdate();
        entityManager.clear();
        return repository.findByIntentCodeAndDeletedAtIsNull(SupplierComparisonReadIntent.CODE).orElseThrow();
    }
}

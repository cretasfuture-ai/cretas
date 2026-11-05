package com.cretas.aims.repository;

import com.cretas.aims.entity.Attachment;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.security.AuthenticatedActorType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real JPA startup/persistence gate for namespace-aware Attachment actor columns. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = Attachment.class)
@EnableJpaRepositories(basePackageClasses = AttachmentRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = AttachmentRepository.class))
class AttachmentSopRepositoryStartupGuardTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
            String separator = safe.contains("?") ? "&" : "?";
            registry.add("spring.datasource.url", () -> safe + separator + "stringtype=unspecified");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired AttachmentRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired DataSource dataSource;

    @Test
    void actorNamespaceColumnsPersistFactoryAndPlatformWithoutNumericCollision() {
        Attachment factory = attachment("factory", AuthenticatedActorType.FACTORY_USER, 7L, 7L);
        Attachment platform = attachment("platform", AuthenticatedActorType.PLATFORM_ADMIN, 7L, null);
        repository.save(factory);
        repository.save(platform);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById("factory")).get().satisfies(a -> {
            assertThat(a.getUploadedActorType()).isEqualTo(AuthenticatedActorType.FACTORY_USER);
            assertThat(a.getUploadedActorId()).isEqualTo(7L);
            assertThat(a.getUploadedBy()).isEqualTo(7L);
        });
        assertThat(repository.findById("platform")).get().satisfies(a -> {
            assertThat(a.getUploadedActorType()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
            assertThat(a.getUploadedActorId()).isEqualTo(7L);
            assertThat(a.getUploadedBy()).isNull();
        });
    }

    @Test
    void optInPostgresTargetIsReallyPostgres() throws SQLException {
        assumeTrue(System.getenv("CRETAS_WORKFLOW_PG_URL") != null
                && !System.getenv("CRETAS_WORKFLOW_PG_URL").isBlank());
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .containsIgnoringCase("PostgreSQL");
        }
    }

    private static Attachment attachment(String id, AuthenticatedActorType actorType, long actorId, Long uploadedBy) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setFactoryId("F-SOP");
        a.setEntityType(Attachment.EntityType.WORK_PROCESS);
        a.setEntityId("wp-1");
        a.setFileName(id + ".pdf");
        a.setFileUrl("https://media.invalid/" + id);
        a.setFileSize(12L);
        a.setFileType("application/pdf");
        a.setFileCategory(Attachment.FileCategory.DOCUMENT);
        a.setFileStorage("OSS");
        a.setUploadedAt(LocalDateTime.now());
        a.setUploadSource("WEB");
        a.setUploadedActorType(actorType);
        a.setUploadedActorId(actorId);
        a.setUploadedBy(uploadedBy);
        return a;
    }
}

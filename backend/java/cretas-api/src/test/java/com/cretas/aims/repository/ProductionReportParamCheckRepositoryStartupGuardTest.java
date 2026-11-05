package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/native-query gate for ProductionReportRepository.findParamCheckReports. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ProductionReportRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = ProductionReportRepository.class))
class ProductionReportParamCheckRepositoryStartupGuardTest {

    private static final String FACTORY = "PRPC-F1";
    private static final String OTHER_FACTORY = "PRPC-F2";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
            String bound = safe + "?stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired TestEntityManager em;
    @Autowired ProductionReportRepository reports;
    @Autowired javax.sql.DataSource dataSource;

    private ProductionReport valid;

    @BeforeEach
    void fixture() {
        valid = report(FACTORY, "YIELD", DAY, null);
        report(OTHER_FACTORY, "YIELD", DAY, null);
        report(FACTORY, "YIELD", DAY.minusDays(10), null);
        report(FACTORY, "YIELD", DAY, LocalDateTime.of(2026, 11, 5, 12, 0));
        report(FACTORY, "PROGRESS", DAY, null);
        em.flush();
    }

    @Test
    void paramCheckQueryIsFactoryDateSoftDeleteAndYieldScoped() throws Exception {
        String pgUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (pgUrl != null && !pgUrl.isBlank()) {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            }
        }
        List<Map<String, Object>> rows = reports.findParamCheckReports(
                FACTORY, DAY.minusDays(1), DAY.plusDays(1));

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.getFirst().get("report_id")).longValue()).isEqualTo(valid.getId());
        assertThat(rows.getFirst().get("custom_fields_json").toString()).contains("paramChecks");
    }

    private ProductionReport report(String factory, String type, LocalDate date, LocalDateTime deletedAt) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", "oilTemp");
        check.put("value", new BigDecimal("170"));
        ProductionReport row = ProductionReport.builder()
                .factoryId(factory).workerId(1L).reportType(type).reportDate(date)
                .customFields(Map.of("paramChecks", List.of(check))).deletedAt(deletedAt).build();
        return reports.saveAndFlush(row);
    }
}

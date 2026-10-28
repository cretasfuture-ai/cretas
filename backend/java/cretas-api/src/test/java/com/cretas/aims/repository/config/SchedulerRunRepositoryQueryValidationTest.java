package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.SchedulerRunResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Run history queries are keyed by factory: another factory's task code never matches (S6-03). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity.config")
@EnableJpaRepositories(basePackageClasses = {SchedulerRunResultRepository.class})
@DisplayName("Scheduler run result repositories real JPA startup gate")
class SchedulerRunRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired SchedulerRunResultRepository runs;
    @Autowired EntityManager em;

    private SchedulerRunResult run(String runId, String factory, LocalDateTime started) {
        return SchedulerRunResult.builder().runId(runId).configId(1L).factoryId(factory).taskCode("daily")
                .startedAt(started).finishedAt(started.plusSeconds(1)).status("UNKNOWN").rawStatus("TIMEOUT").build();
    }

    @Test
    void historyLatestAndRulingLookupAreFactoryScoped() {
        // H2 cannot round-trip the jsonb columns, so reading whole entities needs PostgreSQL (CI sets this).
        Assumptions.assumeTrue(System.getenv("CRETAS_WORKFLOW_PG_URL") != null
                && !System.getenv("CRETAS_WORKFLOW_PG_URL").isBlank(), "needs PostgreSQL for jsonb");
        LocalDateTime t = LocalDateTime.of(2026, 10, 28, 9, 0);
        runs.save(run("a-old", "F001", t));
        runs.save(run("a-new", "F001", t.plusHours(1)));
        runs.save(run("b-only", "F002", t.plusHours(2)));
        em.flush();
        em.clear();

        assertThat(runs.findFirstByFactoryIdAndTaskCodeOrderByStartedAtDesc("F001", "daily").orElseThrow().getRunId())
                .isEqualTo("a-new");
        assertThat(runs.findByFactoryIdAndTaskCodeOrderByStartedAtDesc("F001", "daily",
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(runs.findByRunIdAndFactoryId("b-only", "F001")).isEmpty();
        assertThat(runs.findForUpdate("b-only", "F001")).isEmpty();
        assertThat(runs.findForUpdate("b-only", "F002")).isPresent();
    }

    @Test
    void mappingPersistsAndIsFactoryKeyedOnAnyDatabase() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 28, 9, 0);
        runs.save(run("x-1", "F001", t));
        runs.save(run("x-2", "F002", t));
        em.flush();
        em.clear();
        assertThat(em.createQuery("select r.runId from SchedulerRunResult r where r.factoryId = 'F001'"
                + " and r.taskCode = 'daily'", String.class).getResultList()).containsExactly("x-1");
        assertThat(em.createQuery("select r.status from SchedulerRunResult r where r.runId = 'x-2'", String.class)
                .getSingleResult()).isEqualTo("UNKNOWN");
    }
}

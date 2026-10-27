package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate mapping of the run-as user on cron schedules and event trigger chains: the value
 * the config endpoints save is the value the runners read back through the queries they use.
 * H2 by default; set CRETAS_WORKFLOW_PG_URL to run the same assertions on PostgreSQL.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {FactorySchedulerConfig.class, FactoryTriggerChain.class})
@EnableJpaRepositories(basePackageClasses = {FactorySchedulerConfigRepository.class, FactoryTriggerChainRepository.class})
class AutonomousRunAsRepositoryQueryValidationTest {

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

    @Autowired TestEntityManager entityManager;
    @Autowired FactorySchedulerConfigRepository schedules;
    @Autowired FactoryTriggerChainRepository chains;

    @Test void runAsUserIsPersistedAndReadBackOnTheMappedColumn() {
        persistFixtures();
        // Scalar projections read the mapped column without re-hydrating the jsonb fields, which the
        // H2 test database cannot round-trip; PostgreSQL runs the full-entity test below too.
        var em = entityManager.getEntityManager();
        assertThat(em.createQuery("select c.runAsUserId from FactorySchedulerConfig c where c.factoryId = 'F-RUNAS'"
                + " and c.taskCode = 'expiry'", Long.class).getSingleResult()).isEqualTo(42L);
        assertThat(em.createQuery("select c.runAsUserId from FactorySchedulerConfig c where c.factoryId = 'F-RUNAS'"
                + " and c.taskCode = 'legacy'", Long.class).getResultList()).containsExactly((Long) null);
        assertThat(em.createQuery("select c.runAsUserId from FactoryTriggerChain c where c.factoryId = 'F-RUNAS'",
                Long.class).getSingleResult()).isEqualTo(43L);
        assertThat(((Number) em.createNativeQuery("select run_as_user_id from factory_scheduler_configs"
                + " where task_code = 'expiry'").getSingleResult()).longValue()).isEqualTo(42L);
    }

    @Test void runnersQueriesReturnTheRunAsUserOnPostgres() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "full-entity jsonb round trip needs PostgreSQL");
        persistFixtures();
        assertThat(schedules.findByFactoryIdAndTaskCode("F-RUNAS", "expiry").orElseThrow().getRunAsUserId()).isEqualTo(42L);
        assertThat(schedules.findByFactoryIdAndTaskCode("F-RUNAS", "legacy").orElseThrow().getRunAsUserId()).isNull();
        assertThat(schedules.findByEnabledTrue()).extracting(FactorySchedulerConfig::getRunAsUserId)
                .containsExactlyInAnyOrder(42L, null);
        assertThat(chains.findByFactoryIdAndEventTypeAndEnabledTrue("F-RUNAS", "MATERIAL_RECEIVED"))
                .singleElement().extracting(FactoryTriggerChain::getRunAsUserId).isEqualTo(43L);
    }

    private void persistFixtures() {
        schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-RUNAS").taskCode("expiry")
                .cronExpression("0 0 7 * * ?").enabled(true).toolOrMethod("material_expired_query")
                .params(Map.of("limit", 5)).runAsUserId(42L).build());
        schedules.saveAndFlush(FactorySchedulerConfig.builder().factoryId("F-RUNAS").taskCode("legacy")
                .cronExpression("0 0 8 * * ?").enabled(true).toolOrMethod("material_expired_query").build());
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId("F-RUNAS");
        chain.setChainCode("expiry-chain");
        chain.setEventType("MATERIAL_RECEIVED");
        chain.setEnabled(true);
        chain.setSteps(List.of(Map.of("tool", "material_expired_query")));
        chain.setRunAsUserId(43L);
        chains.saveAndFlush(chain);
        entityManager.clear();
    }
}

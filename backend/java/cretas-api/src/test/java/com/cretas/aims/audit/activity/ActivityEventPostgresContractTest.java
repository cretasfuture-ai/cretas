package com.cretas.aims.audit.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run through run-with-test-postgres.sh after applying the activity migration. */
class ActivityEventPostgresContractTest {
    @Test
    void commitsWithAutoCommitDisabledAndDeduplicatesOnlyWithinActor() throws Exception {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "disposable PostgreSQL required");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("CRETAS_WORKFLOW_PG_USER"));
        config.setPassword(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        config.setAutoCommit(false);
        config.setMaximumPoolSize(2);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            LocalContainerEntityManagerFactoryBean entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
            entityManagerFactory.setDataSource(dataSource);
            entityManagerFactory.setPackagesToScan(ActivityEventPostgresContractTest.class.getPackageName());
            entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            entityManagerFactory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
            entityManagerFactory.afterPropertiesSet();
            try {
                JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory.getObject());
                transactionManager.setDataSource(dataSource);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                ActivityEventStore store = new ActivityEventStore(jdbc, new ObjectMapper(), transactionManager);
                store.start();
                try {
                    Instant now = Instant.now();
                    submit(store, "pg-duplicate-1", "F006", 9942L, now);
                    submit(store, "pg-duplicate-1", "F006", 9942L, now);
                    submit(store, "pg-new-2", "F006", 9942L, now);
                    submit(store, "pg-duplicate-1", "F007", 9943L, now);
                    awaitRows(jdbc, 3);
                    assertThat(store.find("F006", 9942L, null, null, null, null, 100, 0)).hasSize(2);
                    assertThat(store.find("F007", 9943L, null, null, null, null, 100, 0)).hasSize(1);

                    submit(store, "pg-expired-3", "F006", 9942L, now.minusSeconds(181L * 86400));
                    awaitRows(jdbc, 4);
                    store.purgeExpired();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_events WHERE event_id LIKE 'pg-%'", Integer.class)).isEqualTo(3);
                    assertThat(store.find("F006", 9942L, null, null,
                            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, 100, 0)).hasSize(2);
                } finally {
                    store.stop();
                }
            } finally {
                entityManagerFactory.destroy();
            }
        }
    }

    private static void submit(ActivityEventStore store, String id, String factory, long user, Instant at) {
        assertThat(store.submit(new ActivityEvent(id, factory, user, "click", "purchase.submit",
                "/purchase", Map.of("quantity", 1), null, null, "web-admin", at))).isTrue();
    }

    private static void awaitRows(JdbcTemplate jdbc, int expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM activity_events WHERE event_id LIKE 'pg-%'", Integer.class) >= expected) return;
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_events WHERE event_id LIKE 'pg-%'", Integer.class)).isEqualTo(expected);
    }

    @Entity
    @Table(name = "activity_pg_test_probe")
    public static class ProbeEntity {
        @Id
        private Long id;
    }
}

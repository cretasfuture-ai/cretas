package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WarehouseTransferMovement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real Hibernate metadata, Spring Data query creation and executed scoped queries. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseTransferMovementRepositoryQueryValidationTest {
    private EntityManagerFactory factory;
    private EntityManager em;
    private WarehouseTransferMovementRepository repository;
    private String postgresUrl;
    private String user;
    private String password;
    private String schema;

    @BeforeAll void startRealJpa() throws Exception {
        postgresUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean postgres = postgresUrl != null && !postgresUrl.isBlank();
        user = postgres ? System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres") : "sa";
        password = postgres ? System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "") : "";
        schema = "s308_query_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String url;
        if (postgres) {
            try (var connection = DriverManager.getConnection(postgresUrl, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            url = postgresUrl + (postgresUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        } else {
            url = "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        }
        var dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName(postgres ? "org.postgresql.Driver" : "org.h2.Driver");
        var bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setManagedTypes(PersistenceManagedTypes.of(WarehouseTransferMovement.class.getName()));
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.dialect", postgres ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect",
                "hibernate.show_sql", "false"));
        bean.afterPropertiesSet();
        factory = bean.getObject();
        assertNotNull(factory);
    }

    @BeforeEach void transaction() {
        em = factory.createEntityManager();
        repository = new JpaRepositoryFactory(em).getRepository(WarehouseTransferMovementRepository.class);
        assertNotNull(repository);
        assertNotNull(em.getMetamodel().entity(WarehouseTransferMovement.class));
        em.getTransaction().begin();
    }

    @AfterEach void rollback() {
        if (em != null) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    @AfterAll void stopRealJpa() throws Exception {
        if (factory != null) factory.close();
        if (postgresUrl != null && !postgresUrl.isBlank() && schema != null) {
            try (var connection = DriverManager.getConnection(postgresUrl, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test void realJpaContextParsesQueriesAndScopesRequestByTenantTransfer() {
        em.persist(movement("F1", "T1", 11L, "same-request"));
        em.persist(movement("F2", "T2", 22L, "same-request"));
        em.flush(); em.clear();
        assertEquals(1, repository.findRequestHistoryIncludingDeleted("same-request", "F1", "T1").size());
        assertTrue(repository.findRequestHistoryIncludingDeleted("same-request", "F1", "T2").isEmpty());
        assertTrue(repository.findRequestHistoryIncludingDeleted("same-request", "F2", "T1").isEmpty());
        assertEquals(1, repository.findTransferHistoryIncludingDeleted("T1").size());
    }

    @Test void tombstonesRemainInRequestHistoryAndCannotUnlockTheBusinessKey() {
        var own = movement("F1", "T1", 11L, "consumed-request");
        own.setDeletedAt(LocalDateTime.now());
        em.persist(own); em.flush(); em.clear();
        var history = repository.findRequestHistoryIncludingDeleted("consumed-request", "F1", "T1");
        assertEquals(1, history.size());
        assertNotNull(history.get(0).getDeletedAt());
        assertEquals(1, repository.findTransferHistoryIncludingDeleted("T1").size());
        // Native history deliberately hydrates deleted facts. A fresh context is
        // required to test the normal entity filter rather than the identity map.
        em.clear();
        assertTrue(repository.findById(history.get(0).getId()).isEmpty());
        assertThrows(RuntimeException.class, () -> {
            em.persist(movement("F1", "T1", 11L, "consumed-request"));
            em.flush();
        });
    }

    @Test void repositoryDeletionRetainsAuditHistoryAndConsumedRequestKey() {
        var fact = movement("F1", "T-DELETE", 55L, "deleted-via-repository");
        em.persist(fact); em.flush();
        Long id = fact.getId();
        repository.deleteById(id); em.flush(); em.clear();
        assertTrue(repository.findById(id).isEmpty());
        var history = repository.findRequestHistoryIncludingDeleted("deleted-via-repository", "F1", "T-DELETE");
        assertEquals(1, history.size());
        assertEquals(id, history.get(0).getId());
        assertNotNull(history.get(0).getDeletedAt());
        assertEquals(0, new BigDecimal("0.04").compareTo(history.get(0).getQuantity()));
        assertEquals(1, repository.findTransferHistoryIncludingDeleted("T-DELETE").size());
        assertThrows(RuntimeException.class, () -> {
            em.persist(movement("F1", "T-DELETE", 55L, "deleted-via-repository"));
            em.flush();
        });
    }

    private WarehouseTransferMovement movement(String tenant, String transfer, Long item, String key) {
        var movement = new WarehouseTransferMovement();
        movement.setFactoryId(tenant); movement.setTransferId(transfer); movement.setTransferItemId(item);
        movement.setKind(WarehouseTransferMovement.Kind.RECEIPT);
        movement.setOperationId(UUID.randomUUID().toString()); movement.setRequestKey(key);
        movement.setSourceBatchId("SOURCE"); movement.setTargetBatchId("TARGET");
        movement.setQuantity(new BigDecimal("0.04")); movement.setUnit("kg"); movement.setActorId(9L);
        return movement;
    }
}

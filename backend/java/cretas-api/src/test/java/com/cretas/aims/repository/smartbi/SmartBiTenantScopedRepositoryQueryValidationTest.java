package com.cretas.aims.repository.smartbi;

import com.cretas.aims.entity.smartbi.SmartBiFinanceData;
import com.cretas.aims.entity.smartbi.SmartBiSalesData;
import com.cretas.aims.entity.smartbi.enums.RecordType;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgAnalysisResult;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgAnalysisResultRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate metadata, Spring Data query parsing, and tenant-scoped upload reads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmartBiTenantScopedRepositoryQueryValidationTest {
    private EntityManagerFactory factory;
    private EntityManager em;
    private SmartBiSalesDataRepository sales;
    private SmartBiFinanceDataRepository finance;
    private SmartBiPgAnalysisResultRepository analyses;
    private String postgresUrl;
    private String user;
    private String password;
    private String schema;

    @BeforeAll
    void startRealJpa() throws Exception {
        postgresUrl = System.getenv("SC28_REPAIR_PG_JDBC_URL");
        boolean postgres = postgresUrl != null && !postgresUrl.isBlank();
        user = postgres ? "postgres" : "sa";
        password = "";
        schema = "sc28_smartbi_query_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String url;
        if (postgres) {
            assertThat(postgresUrl)
                    .matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):55439/cretas_sc28_disposable_test");
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
        bean.setManagedTypes(PersistenceManagedTypes.of(
                SmartBiSalesData.class.getName(),
                SmartBiFinanceData.class.getName(),
                SmartBiPgAnalysisResult.class.getName()));
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.put("hibernate.dialect", postgres
                ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.show_sql", "false");
        bean.setJpaPropertyMap(properties);
        bean.afterPropertiesSet();
        factory = bean.getObject();
        assertThat(factory).isNotNull();
    }

    @BeforeEach
    void openRepositoryFactory() {
        em = factory.createEntityManager();
        var repositories = new JpaRepositoryFactory(em);
        sales = repositories.getRepository(SmartBiSalesDataRepository.class);
        finance = repositories.getRepository(SmartBiFinanceDataRepository.class);
        analyses = repositories.getRepository(SmartBiPgAnalysisResultRepository.class);
        assertThat(em.getMetamodel().entity(SmartBiSalesData.class)).isNotNull();
        assertThat(em.getMetamodel().entity(SmartBiFinanceData.class)).isNotNull();
        assertThat(em.getMetamodel().entity(SmartBiPgAnalysisResult.class)).isNotNull();
        em.getTransaction().begin();
    }

    @AfterEach
    void rollback() {
        if (em != null) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    @AfterAll
    void stopRealJpa() throws Exception {
        if (factory != null) factory.close();
        if (postgresUrl != null && !postgresUrl.isBlank() && schema != null) {
            try (var connection = DriverManager.getConnection(postgresUrl, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void uploadIdCollisionsNeverCrossFactoryForSalesFinanceOrCachedAnalysis() {
        em.persist(sales("F006", 700L));
        em.persist(sales("F007", 700L));
        em.persist(sales("F006", 701L));
        em.persist(finance("F006", 700L));
        em.persist(finance("F007", 700L));
        em.persist(finance("F006", 701L));
        persistAnalysis("F006", 700L, "insight");
        persistAnalysis("F007", 700L, "insight");
        persistAnalysis("F006", 700L, "forecast");
        em.flush();
        em.clear();

        assertThat(sales.findByFactoryIdAndUploadId("F006", 700L))
                .extracting(SmartBiSalesData::getFactoryId)
                .containsExactly("F006");
        assertThat(finance.findByFactoryIdAndUploadId("F006", 700L))
                .extracting(SmartBiFinanceData::getFactoryId)
                .containsExactly("F006");
        SmartBiPgAnalysisResult scopedInsight = analyses
                .findByFactoryIdAndUploadIdAndAnalysisType("F006", 700L, "insight")
                .orElseThrow();
        assertThat(scopedInsight.getFactoryId()).isEqualTo("F006");
        assertThat(scopedInsight.getAnalysisResult())
                .containsEntry("factory", "F006")
                .containsEntry("type", "insight");
        assertThat(analyses.findByFactoryIdAndUploadIdAndAnalysisType("F007", 700L, "forecast"))
                .isEmpty();
    }

    @Test
    void emptyOtherTenantAndDifferentUploadRemainNegativeWithoutGlobalUploadAssumption() {
        em.persist(sales("F007", 900L));
        em.persist(finance("F007", 900L));
        persistAnalysis("F007", 900L, "insight");
        em.persist(sales("F006", 901L));
        em.persist(finance("F006", 901L));
        persistAnalysis("F006", 901L, "insight");
        em.flush();
        em.clear();

        assertThat(sales.findByFactoryIdAndUploadId("F006", 900L)).isEmpty();
        assertThat(finance.findByFactoryIdAndUploadId("F006", 900L)).isEmpty();
        assertThat(analyses.findByFactoryIdAndUploadIdAndAnalysisType("F006", 900L, "insight"))
                .isEmpty();
        assertThat(sales.findByFactoryIdAndUploadId("F006", 901L)).hasSize(1);
        assertThat(finance.findByFactoryIdAndUploadId("F006", 901L)).hasSize(1);
        assertThat(analyses.findByFactoryIdAndUploadIdAndAnalysisType("F006", 901L, "insight"))
                .isPresent();
    }

    private SmartBiSalesData sales(String factoryId, Long uploadId) {
        return SmartBiSalesData.builder()
                .factoryId(factoryId).uploadId(uploadId).orderDate(LocalDate.of(2026, 10, 1))
                .productId("SKU-" + factoryId).quantity(BigDecimal.ONE)
                .amount(new BigDecimal("10.00")).build();
    }

    private SmartBiFinanceData finance(String factoryId, Long uploadId) {
        return SmartBiFinanceData.builder()
                .factoryId(factoryId).uploadId(uploadId).recordDate(LocalDate.of(2026, 10, 1))
                .recordType(RecordType.COST).totalCost(new BigDecimal("10.00")).build();
    }

    private SmartBiPgAnalysisResult analysis(String factoryId, Long uploadId, String type) {
        return SmartBiPgAnalysisResult.builder()
                .factoryId(factoryId).uploadId(uploadId).analysisType(type)
                .analysisResult(Map.of("factory", factoryId, "type", type)).build();
    }

    /**
     * H2's PostgreSQL-compatibility mode exposes JSONB as a text-backed type.  Binding a
     * Map through JsonBinaryType therefore stores a quoted JSON string, which cannot be
     * read back as a Map.  Seed H2 with H2's native JSON expression while keeping the
     * PostgreSQL branch on the real entity/JsonBinaryType ORM path.
     */
    private void persistAnalysis(String factoryId, Long uploadId, String type) {
        if (postgresUrl != null && !postgresUrl.isBlank()) {
            em.persist(analysis(factoryId, uploadId, type));
            return;
        }
        String json = "{\"factory\":\"" + factoryId + "\",\"type\":\"" + type + "\"}";
        em.createNativeQuery("INSERT INTO smart_bi_pg_analysis_results "
                        + "(upload_id, factory_id, analysis_type, analysis_result, created_at) "
                        + "VALUES (:uploadId, :factoryId, :analysisType, '" + json + "' FORMAT JSON, CURRENT_TIMESTAMP)")
                .setParameter("uploadId", uploadId)
                .setParameter("factoryId", factoryId)
                .setParameter("analysisType", type)
                .executeUpdate();
    }
}

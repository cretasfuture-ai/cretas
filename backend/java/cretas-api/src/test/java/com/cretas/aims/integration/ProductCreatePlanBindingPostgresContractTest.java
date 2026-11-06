package com.cretas.aims.integration;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.impl.ProductWorkProcessServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/** Opt-in real PostgreSQL/JPA proof for the prepared product-chain binding. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackageClasses = {
        ProductWorkProcess.class, ProductWorkProcessAssignee.class, WorkProcess.class})
@EnableJpaRepositories(basePackageClasses = {
        ProductWorkProcessRepository.class, ProductWorkProcessAssigneeRepository.class,
        WorkProcessRepository.class})
@Import(ProductWorkProcessServiceImpl.class)
class ProductCreatePlanBindingPostgresContractTest {

    private static final String PRIVATE_SCHEMA =
            "pc_plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    private String factoryId;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        createPrivateSchema(url);
        registry.add("spring.datasource.url", () -> appendQuery(url,
                "currentSchema=" + PRIVATE_SCHEMA + "&stringtype=unspecified&ApplicationName=cretas_product_plan_binding_test"));
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @org.junit.jupiter.api.AfterAll
    static void dropPrivateSchema() throws Exception {
        String rawUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (Connection connection = DriverManager.getConnection(
                rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + PRIVATE_SCHEMA + "\" CASCADE");
        }
    }

    private static void createPrivateSchema(String rawUrl) {
        try (Connection connection = DriverManager.getConnection(
                rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + PRIVATE_SCHEMA + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create private PostgreSQL test schema", exception);
        }
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockBean private ProductTypeRepository productTypes;
    @Autowired private ProductWorkProcessRepository productProcesses;
    @Autowired private WorkProcessRepository workProcesses;
    @Autowired private ProductWorkProcessService service;

    @MockBean private ProductWorkProcessAssigneeRepository assignees;
    @MockBean private UserRepository users;
    @MockBean private ProcessSecretRecipeGuard secretGuard;

    private String sourceId;
    private String targetId;
    private String workProcessId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedData());
    }

    private void seedData() {
        factoryId = "PB-PG-" + UUID.randomUUID().toString().replace("-", "");
        sourceId = "PB-SRC-" + UUID.randomUUID().toString().replace("-", "");
        targetId = "PB-TGT-" + UUID.randomUUID().toString().replace("-", "");
        workProcessId = "PB-WP-" + UUID.randomUUID().toString().replace("-", "");

        entityManager.createNativeQuery("""
                insert into factories
                    (id, name, type, level, is_active, manually_verified, ai_weekly_quota,
                     inference_data, created_at, updated_at)
                values (:id, :name, 'FACTORY', 0, true, false, 20,
                        cast(:inference as jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter("id", factoryId)
                .setParameter("name", "Product Binding PG Factory " + factoryId)
                .setParameter("inference", "{}")
                .executeUpdate();

        insertProduct(sourceId, "PB source");
        insertProduct(targetId, "PB target");
        when(productTypes.findByIdAndFactoryId(sourceId, factoryId))
                .thenReturn(Optional.of(product(sourceId, "PB source")));

        WorkProcess master = WorkProcess.builder()
                .id(workProcessId).factoryId(factoryId).processName("PG 切片")
                .unit("kg").sortOrder(1).isActive(true).lockVersion(0L)
                .defaultOutputMaterialKind(
                        com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED)
                .build();
        entityManager.persist(master);
        productProcesses.saveAndFlush(ProductWorkProcess.builder()
                .factoryId(factoryId).productTypeId(sourceId).workProcessId(workProcessId)
                .processOrder(1).unitOverride("kg").estimatedMinutesOverride(12)
                .reportingRequired(true).allowSemiFinishedInjection(false)
                .allowMultipleUpstreamSources(false).allowFinishedGoodsSource(false)
                .defaultCostCategory("RAW_MATERIAL").standardYieldRate(new java.math.BigDecimal("0.8500"))
                .auxBasis("INPUT").build());
        entityManager.flush();
        entityManager.clear();
        doNothing().when(secretGuard).requireCopySource(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        doNothing().when(secretGuard).requireProduct(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void postgresFreshMasterPinRejectsRevisionDriftAndCommittedReadbackMatches() {
        assertThat(databaseProduct()).isEqualTo("PostgreSQL");
        ProductWorkProcessService.PreparedChain prepared = service.prepareChain(factoryId, sourceId);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("update work_processes set lock_version = lock_version + 1 where id = '"
                    + workProcessId + "'");
        } catch (Exception exception) {
            throw new AssertionError("could not advance master lock version", exception);
        }

        assertThatThrownBy(() -> service.validatePreparedChain(factoryId, sourceId, prepared))
                .hasMessageContaining("已变化");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void postgresCopyUsesPreparedSnapshotAndReadsCommittedTarget() {
        ProductWorkProcessService.PreparedChain prepared = service.prepareChain(factoryId, sourceId);
        assertThat(service.copyPreparedChain(factoryId, sourceId, targetId, prepared)).isEqualTo(1);
        service.verifyPreparedChainReadback(factoryId, targetId, prepared);
        entityManager.clear();
        assertThat(productProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetId))
                .hasSize(1)
                .first().extracting(ProductWorkProcess::getWorkProcessId).isEqualTo(workProcessId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void postgresMasterLockWaitsThenReleasesWithoutChangingPreparedResult() throws Exception {
        ProductWorkProcessService.PreparedChain prepared = service.prepareChain(factoryId, sourceId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("select id from work_processes where id = '" + workProcessId + "' for update");
            Future<Integer> future = executor.submit(() -> {
                started.countDown();
                return service.copyPreparedChain(factoryId, sourceId, targetId, prepared);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitLockWait()).isTrue();
            holder.commit();
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean awaitLockWait() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select count(*) from pg_stat_activity where datname = current_database() "
                                 + "and application_name = 'cretas_product_plan_binding_test' "
                                 + "and pid <> pg_backend_pid() and wait_event_type = 'Lock'")) {
                rows.next();
                if (rows.getInt(1) > 0) return true;
            }
            Thread.sleep(50L);
        }
        return false;
    }

    private String databaseProduct() {
        try (Connection connection = dataSource.getConnection()) {
            if (!PRIVATE_SCHEMA.equals(connection.getSchema())) {
                throw new AssertionError("PostgreSQL proof escaped its private schema");
            }
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception exception) {
            throw new AssertionError("database metadata unavailable", exception);
        }
    }

    private void insertProduct(String id, String name) {
        entityManager.createNativeQuery("""
                insert into product_types
                    (id, factory_id, code, name, unit, category, product_category,
                     inspection_required, is_active, processing_steps, skill_requirements,
                     equipment_ids, quality_check_ids, created_at, updated_at)
                values (:id, :factory, :code, :name, 'kg', 'FINISHED_PRODUCT', 'FINISHED_PRODUCT',
                        false, true, cast('[]' as jsonb), cast('{}' as jsonb),
                        cast('[]' as jsonb), cast('[]' as jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter("id", id)
                .setParameter("factory", factoryId)
                .setParameter("code", id)
                .setParameter("name", name)
                .executeUpdate();
    }

    private ProductType product(String id, String name) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factoryId);
        product.setCode(id);
        product.setName(name);
        product.setProductCategory("FINISHED_PRODUCT");
        product.setUnit("kg");
        product.setProcessingSteps("[]");
        product.setSkillRequirements("{}");
        product.setEquipmentIds("[]");
        product.setQualityCheckIds("[]");
        return product;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for PostgreSQL proof");
        }
        return value;
    }
}

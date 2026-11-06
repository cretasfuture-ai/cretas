package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.ProcessParamDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.shortage.ProcessParamDeviationCaseService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Opt-in real PostgreSQL/JPA lifecycle proof for the source-specific case service. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ProcessParamDeviationCasePostgresLifecycleContractTest.ServiceConfig.class)
@EntityScan(basePackageClasses = {ShortageCase.class, ShortageCaseEvent.class, ProcessParamDeviationCaseDetail.class})
@EnableJpaRepositories(basePackageClasses = ShortageCaseRepository.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ShortageCaseRepository.class, ProcessParamDeviationCaseDetailRepository.class,
                        ShortageCaseEventRepository.class}))
class ProcessParamDeviationCasePostgresLifecycleContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ServiceConfig {
        @Bean
        ProcessParamDeviationCaseService processParamDeviationCaseService(
                ShortageCaseRepository cases,
                ProcessParamDeviationCaseDetailRepository details,
                ShortageCaseEventRepository events,
                UserRepository users,
                ProductionBatchRepository batches,
                ProcessSecretAggregateExclusions exclusions,
                PermissionService permissions) {
            return new ProcessParamDeviationCaseService(cases, details, events, users, batches, exclusions,
                    permissions, true);
        }
    }

    private static final String SCHEMA = "param_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    private static final String FACTORY = "PG-PARAM-CASE";
    private static final String URL;
    private static final String USER;
    private static final String PASSWORD;

    static {
        String rawUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String rawUser = System.getenv("CRETAS_WORKFLOW_PG_USER");
        if (rawUrl == null || rawUrl.isBlank() || rawUser == null || rawUser.isBlank()) {
            URL = null;
            USER = null;
            PASSWORD = null;
        } else {
            URL = DisposablePostgresTargetGuard.requireSafeUrl(rawUrl);
            USER = rawUser;
            PASSWORD = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                createBaseline(connection, SCHEMA);
                connection.setAutoCommit(false);
                runMigration(connection, SCHEMA);
                connection.commit();
            } catch (Exception failure) {
                throw new ExceptionInInitializerError("PG fixture setup failed: " + failure.getClass().getSimpleName());
            }
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (URL == null) return;
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + "&stringtype=unspecified");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ShortageCaseRepository cases;
    @Autowired private ProcessParamDeviationCaseDetailRepository details;
    @Autowired private ShortageCaseEventRepository events;
    @Autowired private ProcessParamDeviationCaseService service;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private UserRepository users;
    @MockBean private ProductionBatchRepository batches;
    @MockBean private ProcessSecretAggregateExclusions exclusions;
    @MockBean private PermissionService permissions;

    @BeforeEach
    void cleanFixtureRows() {
        when(exclusions.forCurrentCaller(FACTORY, "process param deviation case"))
                .thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        events.deleteAll();
        details.deleteAll();
        cases.deleteAll();
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        if (URL == null) return;
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @Test
    void realJpaServicePersistsClerkFanoutAndClosedReplayDoesNotReopen() {
        for (int report = 0; report < 3; report++) {
            service.recordReport(report(100L + report));
        }
        assertThat(cases.count()).isEqualTo(1);
        assertThat(details.count()).isEqualTo(1);

        ShortageCase open = cases.findByIdAndFactoryIdAndSourceTypeIn("missing", FACTORY,
                ProcessParamDeviationCaseService.SOURCES).orElseGet(() ->
                cases.findForFactoryBySources(FACTORY, true, ProcessParamDeviationCaseService.SOURCES,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0));
        open.setOwnerUserId(7L);
        open.setStage(ShortageCase.Stage.EXECUTING);
        cases.saveAndFlush(open);
        // Read the version from the managed row after the owner/stage update; the
        // optimistic-lock value is allowed to advance during saveAndFlush.
        open = cases.findById(open.getId()).orElseThrow();
        long version = open.getVersion();
        service.resolve(FACTORY, open.getId(), ProcessParamDeviationCaseDetail.Resolution.CORRECTED,
                "已核查", version, new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L));

        assertThat(service.apply(fact(), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.ALREADY_RESOLVED);
        ShortageCase closed = cases.findById(open.getId()).orElseThrow();
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(details.count()).isEqualTo(1);
    }

    @Test
    void realTransactionRollbackLeavesNoCaseOrDetail() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            service.apply(fact(), null);
            status.setRollbackOnly();
        });
        assertThat(cases.count()).isZero();
        assertThat(details.count()).isZero();
    }

    @Test
    void concurrentRealTransactionsConvergeOnOneSourceFact() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    return transaction.execute(status -> service.apply(fact(), null));
                } catch (Throwable failure) {
                    return failure;
                }
            }));
        }
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter(ProcessParamDeviationCaseService.Applied.CREATED::equals).count())
                .isEqualTo(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome instanceof ProcessParamDeviationCaseService.Applied
                || expectedUniqueConflict(outcome)).isTrue());
        assertThat(cases.count()).isEqualTo(1);
        assertThat(details.count()).isEqualTo(1);
    }

    @Test
    void concurrentRefreshAndCloseUseCaseThenDetailLockOrder() throws Exception {
        service.apply(fact(), null);
        ShortageCase open = cases.findForFactoryBySources(FACTORY, true, ProcessParamDeviationCaseService.SOURCES,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0);
        open.setOwnerUserId(7L);
        open.setStage(ShortageCase.Stage.EXECUTING);
        cases.saveAndFlush(open);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<String> close = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                return transaction.execute(status -> {
                    ShortageCase locked = cases.findForUpdateByIdAndFactoryIdAndSourceTypeIn(open.getId(), FACTORY,
                            ProcessParamDeviationCaseService.SOURCES).orElseThrow();
                    long version = locked.getVersion();
                    await(barrier);
                    service.resolve(FACTORY, locked.getId(), ProcessParamDeviationCaseDetail.Resolution.CORRECTED,
                            "并发关闭", version, new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L));
                    return "CLOSED";
                });
            });
            Future<ProcessParamDeviationCaseService.Applied> refresh = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                return transaction.execute(status -> {
                    await(barrier);
                    return service.apply(changedFact(), null);
                });
            });

            assertThat(close.get(30, TimeUnit.SECONDS)).isEqualTo("CLOSED");
            assertThat(refresh.get(30, TimeUnit.SECONDS))
                    .isEqualTo(ProcessParamDeviationCaseService.Applied.ALREADY_RESOLVED);
        } finally {
            executor.shutdownNow();
        }

        ShortageCase closed = cases.findById(open.getId()).orElseThrow();
        ProcessParamDeviationCaseDetail detail = details.findByCaseIdAndFactoryId(open.getId(), FACTORY)
                .orElseThrow();
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getVersion()).isGreaterThan(open.getVersion());
        assertThat(detail.getMeasuredValue()).isEqualByComparingTo("171");
        assertThat(detail.getResolutionCode()).isEqualTo(ProcessParamDeviationCaseDetail.Resolution.CORRECTED);
        assertThat(detail.getResolvedAt()).isNotNull();
    }

    private ProductionReport report(long id) {
        return ProductionReport.builder().id(id).factoryId(FACTORY).batchId(7L).productTypeId("PT-PG")
                .reportType("YIELD")
                .customFields(Map.of("processEntryStepKey", "3|炒制|加工",
                        "processEntryProcessName", "炒制",
                        "paramChecks", List.of(Map.of("key", "temp", "label", "温度", "unit", "℃",
                                "value", new BigDecimal("170"), "standardValue", new BigDecimal("150"),
                                "min", new BigDecimal("140"), "max", new BigDecimal("160"), "status", "HIGH"))))
                .build();
    }

    private ProcessParamDeviationCaseService.Fact fact() {
        return new ProcessParamDeviationCaseService.Fact(FACTORY,
                ProcessParamDeviationCaseDetail.SourceKind.PROCESS_SHEET_ROW,
                "process-sheet:7:3|炒制|加工:temp", 101L, 7L, null, "PLAN-PG", "3|炒制|加工",
                "炒制", "PG-BATCH-1", "PT-PG", "temp", "温度", "℃", new BigDecimal("170"),
                new BigDecimal("150"), new BigDecimal("140"), new BigDecimal("160"), "HIGH",
                LocalDateTime.of(2026, 11, 6, 12, 0));
    }

    private ProcessParamDeviationCaseService.Fact changedFact() {
        return new ProcessParamDeviationCaseService.Fact(FACTORY,
                ProcessParamDeviationCaseDetail.SourceKind.PROCESS_SHEET_ROW,
                "process-sheet:7:3|炒制|加工:temp", 101L, 7L, null, "PLAN-PG", "3|炒制|加工",
                "炒制", "PG-BATCH-1", "PT-PG", "temp", "温度", "℃", new BigDecimal("171"),
                new BigDecimal("150"), new BigDecimal("140"), new BigDecimal("160"), "HIGH",
                LocalDateTime.of(2026, 11, 6, 12, 5));
    }

    private static boolean expectedUniqueConflict(Object outcome) {
        if (!(outcome instanceof Throwable)) return false;
        Throwable current = (Throwable) outcome;
        while (current != null) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("barrier interrupted", interrupted);
        } catch (Exception broken) {
            throw new IllegalStateException("barrier failed", broken);
        }
    }

    private static void createBaseline(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            statement.execute("SET search_path TO \"" + schema + "\"");
            statement.execute("CREATE TABLE shortage_cases (id VARCHAR(36) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                    + "source_type VARCHAR(32) NOT NULL, source_id VARCHAR(191) NOT NULL, "
                    + "stage VARCHAR(32) NOT NULL, owner_role VARCHAR(64) NOT NULL, owner_user_id BIGINT, gap_line_count INTEGER NOT NULL DEFAULT 0, "
                    + "input_fingerprint VARCHAR(64) NOT NULL, last_refreshed_at TIMESTAMP NOT NULL, last_refresh_reason VARCHAR(64) NOT NULL, "
                    + "gap_resolved_at TIMESTAMP, closed_at TIMESTAMP, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL, "
                     + "updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP, "
                     + "CHECK (source_type IN ('SALES_ORDER', 'COST_VARIANCE', 'MATERIAL_BALANCE', 'DELIVERY_RISK')))" );
        }
    }

    private static void runMigration(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/flyway/V20261106_1__process_param_deviation_case.sql"));
    }
}

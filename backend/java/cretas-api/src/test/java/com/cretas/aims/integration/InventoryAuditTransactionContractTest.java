package com.cretas.aims.integration;

import com.cretas.aims.audit.inventory.InventoryAuditConfiguration;
import com.cretas.aims.config.datasource.PrimaryDataSourceConfig;
import com.cretas.aims.controller.datacenter.OperationLogController;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.production.TaskMaterialBatchPickerService;
import com.cretas.aims.service.production.WorkshopReportReservationService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.CostReconcileService;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/** Same actual Hibernate/SQL behavior runs in CI on isolated H2, and on disposable PG for release. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.jdbc.batch_size=100", "spring.jpa.properties.hibernate.order_inserts=true",
        "spring.jpa.properties.hibernate.order_updates=true",
        "spring.flyway.enabled=false", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = InventoryAuditTransactionContractTest.Config.class)
@Import({InventoryAuditConfiguration.class, PrimaryDataSourceConfig.class,
        WorkshopReportReservationService.class, YieldReportServiceImpl.class,
        YieldCalculationServiceImpl.class, TaskMaterialBatchPickerService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryAuditTransactionContractTest {
    private static final String H2_URL = "jdbc:h2:mem:inventory_audit_" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON";
    @Configuration
    static class Config {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor batchProbe() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!"primaryDataSource".equals(name) || !(bean instanceof javax.sql.DataSource source)) return bean;
                    return new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
                        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                            return probe(super.getConnection());
                        }
                        @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
                            return probe(super.getConnection(user, password));
                        }
                    };
                }
            };
        }

        @org.springframework.context.annotation.Bean
        static ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final List<Integer> STOCK_BATCH_SIZES = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static java.sql.Connection probe(java.sql.Connection connection) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                InventoryAuditTransactionContractTest.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if ("prepareStatement".equals(method.getName()) && args[0] instanceof String sql
                            && sql.toLowerCase(java.util.Locale.ROOT).startsWith("insert into material_batches ")
                            && result instanceof java.sql.PreparedStatement statement) {
                        int[] pending = {0};
                        return java.lang.reflect.Proxy.newProxyInstance(
                                InventoryAuditTransactionContractTest.class.getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    Object value = invoke(statement, statementMethod, statementArgs);
                                    if ("addBatch".equals(statementMethod.getName())) pending[0]++;
                                    if ("executeBatch".equals(statementMethod.getName()) || "executeLargeBatch".equals(statementMethod.getName())) {
                                        STOCK_BATCH_SIZES.add(pending[0]); pending[0] = 0;
                                    }
                                    if ("clearBatch".equals(statementMethod.getName())) pending[0] = 0;
                                    return value;
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
    }

    @Test
    void stockInsertsRetainActualJdbcBatchingWithCompleteAudits() {
        STOCK_BATCH_SIZES.clear();
        List<MaterialBatch> batches = java.util.stream.IntStream.range(0, 30).mapToObj(index -> batch()).toList();
        tx(manager -> batches.forEach(manager::persist));
        assertThat(STOCK_BATCH_SIZES).anyMatch(size -> size > 1);
        assertThat(STOCK_BATCH_SIZES.stream().mapToInt(Integer::intValue).sum()).isEqualTo(30);
        for (MaterialBatch batch : batches) assertThat(logs(batch.getId())).hasSize(1);
    }

    @Test
    void inputReportReservationFailureRollsBackSavedReportAndEveryBatch() {
        Object yieldTarget = AopTestUtils.getTargetObject(yieldReports);
        assertThat(ReflectionTestUtils.getField(yieldTarget, "workshopReservationService"))
                .isSameAs(reservations);
        assertThat(ReflectionTestUtils.getField(yieldTarget, "workflowTaskPortRepository")).isNotNull();

        String taskFactory = factory;
        long batchId = 10944L;
        long workerId = actor;
        WorkProcessTask task = task(batchId, workerId);
        tx(manager -> workProcessTasks.save(task));

        MaterialBatch first = reservationBatch("A-001", BigDecimal.TEN);
        MaterialBatch second = reservationBatch("B-002", BigDecimal.ONE);
        tx(manager -> {
            materialBatches.save(first);
            materialBatches.save(second);
        });
        ProductionBatch productionBatch = new ProductionBatch();
        productionBatch.setId(batchId);
        productionBatch.setFactoryId(taskFactory);
        productionBatch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        org.mockito.Mockito.when(productionBatchRepository.findByIdAndFactoryId(batchId, taskFactory))
                .thenReturn(java.util.Optional.of(productionBatch));
        org.mockito.Mockito.when(warehouseResolver.resolveWorkshopId(taskFactory)).thenReturn("WH-WORK");

        YieldReportRequest request = inputRequest(task.getId(), "reserve-failure-001");
        request.setMaterialBatchRefs(List.of(ref(first.getId(), "5"), ref(second.getId(), "5")));

        Throwable failure = catchThrowable(() -> yieldReports.submitReport(taskFactory, batchId, workerId, request));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getErrorCode()).isEqualTo("BATCH_OVER_CONSUMED");
        org.mockito.Mockito.verify(productionReports).save(org.mockito.ArgumentMatchers.any(ProductionReport.class));

        tx(manager -> {
            manager.clear();
            assertThat(productionReports.findFirstByFactoryIdAndWorkProcessTaskIdAndClientRequestIdAndDeletedAtIsNull(
                    taskFactory, task.getId(), request.getClientRequestId())).isEmpty();
            assertThat(materialBatches.findById(first.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("0");
            assertThat(materialBatches.findById(second.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("0");
        });

        tx(manager -> materialBatches.findById(second.getId()).orElseThrow()
                .setReceiptQuantity(BigDecimal.TEN));
        request.setClientRequestId("reserve-success-001");
        Map<String, Object> success = yieldReports.submitReport(taskFactory, batchId, workerId, request);
        assertThat(success.get("reportId")).isNotNull();
        org.mockito.Mockito.verify(productionReports, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(ProductionReport.class));
        tx(manager -> {
            manager.clear();
            assertThat(productionReports.findFirstByFactoryIdAndWorkProcessTaskIdAndClientRequestIdAndDeletedAtIsNull(
                    taskFactory, task.getId(), request.getClientRequestId())).isPresent();
            assertThat(materialBatches.findById(first.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("5");
            assertThat(materialBatches.findById(second.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("5");
        });
    }

    @Test
    void rawStockPickerAndInputReportPreserveEachBatchNativeUnit() {
        long batchId = 10945L;
        WorkProcessTask task = task(batchId, actor);
        task.setWorkflowInstanceId(7001L);
        tx(manager -> workProcessTasks.save(task));
        String sku = "RAW-" + UUID.randomUUID();
        RawMaterialType rawMaterial = rawMaterial(sku);
        tx(manager -> manager.persist(rawMaterial));
        tx(manager -> {
            manager.persist(port(task, WorkflowTaskPort.Direction.INPUT, sku, "kg", "kg"));
            manager.persist(port(task, WorkflowTaskPort.Direction.OUTPUT, "OUT-001", "kg", "kg"));
        });

        MaterialBatch grams = reservationBatch("G-001", new BigDecimal("500"));
        grams.setMaterialTypeId(sku);
        grams.setQuantityUnit("g");
        grams.setUnitPrice(new BigDecimal("0.02"));
        MaterialBatch kilos = reservationBatch("K-002", new BigDecimal("0.5"));
        kilos.setMaterialTypeId(sku);
        kilos.setQuantityUnit("kg");
        kilos.setUnitPrice(new BigDecimal("4.00"));
        MaterialBatch packaged = reservationBatch("X-003", BigDecimal.ONE);
        packaged.setMaterialTypeId(sku);
        packaged.setQuantityUnit("box");
        packaged.setUnitPrice(new BigDecimal("9.00"));
        tx(manager -> {
            materialBatches.save(grams);
            materialBatches.save(kilos);
            materialBatches.save(packaged);
        });

        ProductionBatch productionBatch = new ProductionBatch();
        productionBatch.setId(batchId);
        productionBatch.setFactoryId(factory);
        productionBatch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        org.mockito.Mockito.when(productionBatchRepository.findByIdAndFactoryId(batchId, factory))
                .thenReturn(java.util.Optional.of(productionBatch));
        org.mockito.Mockito.when(warehouseResolver.resolveWorkshopId(factory)).thenReturn("WH-WORK");
        org.mockito.Mockito.when(recipeRepository.findActiveByFactoryIdAndWorkProcessId(factory, "legacy-process"))
                .thenReturn(List.of());

        var candidates = materialPicker.list(factory, batchId, task.getId());
        assertThat(candidates).hasSize(2).extracting("quantityUnit").containsOnly("kg");
        assertThat(candidates).extracting("currentQuantity")
                .allSatisfy(quantity -> assertThat((BigDecimal) quantity).isEqualByComparingTo("0.5"));

        YieldReportRequest request = inputRequest(task.getId(), "native-units-001");
        request.setInputQuantity(BigDecimal.ONE);
        request.setMaterialBatchRefs(List.of(ref(grams.getId(), "0.5"), ref(kilos.getId(), "0.5")));
        Map<String, Object> result = yieldReports.submitReport(factory, batchId, actor, request);
        assertThat(result.get("reportId")).isNotNull();

        tx(manager -> {
            manager.clear();
            ProductionReport report = productionReports.findById((Long) result.get("reportId")).orElseThrow();
            assertThat(report.getMaterialBatchRefs()).extracting(ref -> ref.get("materialBatchId"))
                    .containsExactly(grams.getId(), kilos.getId());
            assertThat(new BigDecimal(report.getMaterialBatchRefs().get(0).get("quantity").toString()))
                    .isEqualByComparingTo("500");
            assertThat(new BigDecimal(report.getMaterialBatchRefs().get(1).get("quantity").toString()))
                    .isEqualByComparingTo("0.5");
            assertThat(report.getMaterialBatchRefs()).extracting(ref -> ref.get("unit"))
                    .containsExactly("g", "kg");
            assertThat(report.getMaterialCost()).isEqualByComparingTo("12.00");
            assertThat(materialBatches.findById(grams.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("500");
            assertThat(materialBatches.findById(kilos.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("0.5");
            assertThat(materialBatches.findById(packaged.getId()).orElseThrow().getReservedQuantity())
                    .isEqualByComparingTo("0");
        });
    }

    @Test
    void pgConcurrentReportsSerializeReservationAndOnlyOneCommits() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY")),
                "concurrency lock proof requires the disposable PostgreSQL gate");
        long batchId = 10946L;
        WorkProcessTask task = task(batchId, actor);
        tx(manager -> workProcessTasks.save(task));
        String sku = "RAW-" + UUID.randomUUID();
        tx(manager -> manager.persist(rawMaterial(sku)));
        MaterialBatch stock = reservationBatch("CONCURRENT-001", BigDecimal.TEN);
        stock.setMaterialTypeId(sku);
        stock.setQuantityUnit("kg");
        stock.setUnitPrice(new BigDecimal("2.00"));
        tx(manager -> materialBatches.save(stock));

        ProductionBatch productionBatch = new ProductionBatch();
        productionBatch.setId(batchId);
        productionBatch.setFactoryId(factory);
        productionBatch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        org.mockito.Mockito.when(productionBatchRepository.findByIdAndFactoryId(batchId, factory))
                .thenReturn(java.util.Optional.of(productionBatch));
        org.mockito.Mockito.when(warehouseResolver.resolveWorkshopId(factory)).thenReturn("WH-WORK");
        org.mockito.Mockito.when(recipeRepository.findActiveByFactoryIdAndWorkProcessId(factory, "legacy-process"))
                .thenReturn(List.of());

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var first = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
        var second = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Integer holderPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                em.find(MaterialBatch.class, stock.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                executor.submit(() -> submitOnThread(first, task.getId(), batchId, "concurrent-001"));
                executor.submit(() -> submitOnThread(second, task.getId(), batchId, "concurrent-002"));
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                int waiting = 0;
                while (System.nanoTime() < deadline && waiting < 2) {
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    waiting = jdbc.queryForObject("""
                            with recursive blocked(pid) as (
                                select a.pid
                                from pg_stat_activity a
                                where a.datname = current_database()
                                  and a.wait_event_type = 'Lock'
                                  and ? = any(pg_blocking_pids(a.pid))
                                union
                                select a.pid
                                from pg_stat_activity a
                                join blocked b on b.pid = any(pg_blocking_pids(a.pid))
                                where a.datname = current_database()
                                  and a.wait_event_type = 'Lock'
                            )
                            select count(*) from blocked
                            """, Integer.class, holderPid);
                    if (waiting < 2) {
                        try { Thread.sleep(25); } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted while proving PG lock wait", interrupted);
                        }
                    }
                }
                assertThat(waiting)
                        .withFailMessage("expected two material_batches waiters, got %s; first=%s second=%s",
                                waiting, futureDescription(first), futureDescription(second))
                        .isGreaterThanOrEqualTo(2);
            });

            Map<String, Object> firstResult = awaitOutcome(first);
            Map<String, Object> secondResult = awaitOutcome(second);
            Throwable firstFailure = awaitFailure(first);
            Throwable secondFailure = awaitFailure(second);
            assertThat((firstResult != null) ^ (secondResult != null))
                    .as("exactly one concurrent report must return success").isTrue();
            Throwable reservationFailure = firstFailure != null ? firstFailure : secondFailure;
            assertThat(reservationFailure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) reservationFailure).getErrorCode()).isEqualTo("BATCH_OVER_CONSUMED");

            tx(manager -> {
                manager.clear();
                assertThat(productionReports.findYieldReportsByTask(factory, task.getId())).hasSize(1);
                assertThat(materialBatches.findById(stock.getId()).orElseThrow().getReservedQuantity())
                        .isEqualByComparingTo("7");
            });
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent report workers did not terminate");
            }
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        if ("true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"))) {
            String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
            String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
            String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
            // The outer run-with-test-postgres wrapper owns and removes this disposable cluster.
            String schema = "inventory_audit_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            properties.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
            properties.add("spring.datasource.username", () -> user);
            properties.add("spring.datasource.password", () -> password);
            properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            properties.add("spring.datasource.url", () -> H2_URL);
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            properties.add("spring.datasource.username", () -> "sa");
            properties.add("spring.datasource.password", () -> "");
            properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
            properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        }
    }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OperationLogRepository operationLogs;
    @Autowired MaterialBatchRepository materialBatches;
    @SpyBean ProductionReportRepository productionReports;
    @Autowired WorkProcessTaskRepository workProcessTasks;
    @Autowired WorkflowTaskPortRepository taskPorts;
    @Autowired YieldReportService yieldReports;
    @Autowired WorkshopReportReservationService reservations;
    @Autowired TaskMaterialBatchPickerService materialPicker;

    @MockBean WarehouseResolver warehouseResolver;
    @MockBean ProcessingService processingService;
    @MockBean FactorySettingsRepository factorySettingsRepository;
    @MockBean ProductTypeRepository productTypeRepository;
    @MockBean ProductionBatchRepository productionBatchRepository;
    @MockBean ProductionPlanRepository productionPlanRepository;
    @MockBean WorkProcessRepository workProcessRepository;
    @MockBean SemiFinishedInventoryRepository wipRepository;
    @MockBean BatchLineageEdgeRepository lineageEdgeRepository;
    @MockBean ProcessMaterialRecipeRepository recipeRepository;
    @MockBean WipInventoryService wipInventoryService;
    @MockBean ProductWorkProcessAssigneeRepository pwpAssigneeRepository;
    @MockBean ProductWorkProcessRepository productWorkProcessRepository;
    @MockBean CostReconcileService costReconcileService;
    @MockBean ProcessSheetRowRepository processSheetRowRepository;
    private final ObjectMapper json = new ObjectMapper();
    private String factory;
    private String otherFactory;
    private Long actor;

    @BeforeEach
    void fixtures() {
        factory = "AUDIT_" + UUID.randomUUID().toString().replace("-", "");
        otherFactory = factory + "_B";
        // Seed unrelated parent fixtures through SQL: Factory's legacy inference_data
        // String/jsonb binding is not part of the inventory write path under test.
        tx(manager -> {
            for (String id : List.of(factory, otherFactory)) {
                jdbc.update("""
                        insert into factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at)
                        values (?,?,'FACTORY',0,true,false,20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """, id, "audit fixture " + id);
            }
            String username = "audit-" + UUID.randomUUID();
            jdbc.update("""
                    insert into users (factory_id,username,password_hash,is_active,created_at,updated_at)
                    values (?,?,'fixture-not-a-credential',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, factory, username);
            actor = jdbc.queryForObject("select id from users where username=?", Long.class, username);
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/" + factory + "/transfers");
        request.setAttribute("factoryId", factory); request.setAttribute("userId", actor); request.setAttribute("username", "audit-operator");
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_HEADER, "audit-request-001");
        // Incoming spoofed identity never replaces attributes established by authentication.
        request.addHeader("X-User-ID", "999999");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void committedRawStockHasExactBeforeAfterAndTrustedActorForBothOwnerships() throws Exception {
        for (InventoryOwnership ownership : InventoryOwnership.values()) {
            MaterialBatch batch = batch(); batch.setOwnership(ownership);
            batch.setUnitPrice(new BigDecimal("123.45")); batch.setNotes("private text must not enter audit");
            tx(manager -> manager.persist(batch));
            tx(manager -> {
                MaterialBatch loaded = manager.find(MaterialBatch.class, batch.getId());
                loaded.setUsedQuantity(new BigDecimal("2.125")); loaded.setReservedQuantity(new BigDecimal("1.25"));
            });
            var rows = logs(batch.getId()); assertThat(rows).hasSize(2);
            Map<String, Object> update = rows.get(1);
            assertThat(update.get("user_id")).isEqualTo(actor);
            assertThat(update.get("factory_id")).isEqualTo(factory);
            assertThat(update.get("summary").toString()).contains("correlation=audit-request-001", "authenticated_request");
            JsonNode before = value(update, "old_value"), after = value(update, "new_value");
            assertThat(before.path("usedQuantity").decimalValue()).isEqualByComparingTo("0");
            assertThat(after.path("usedQuantity").decimalValue()).isEqualByComparingTo("2.125");
            assertThat(after.path("availableQuantity").decimalValue()).isEqualByComparingTo("6.625");
            assertThat(after.path("physicalQuantity").decimalValue()).isEqualByComparingTo("7.875");
            assertThat(after.path("ownership").asText()).isEqualTo(ownership.name());
            assertThat(after.toString()).doesNotContain("unitPrice", "private text", "notes", "password");
            assertThat(value(update, "diff").toString()).contains("usedQuantity", "reservedQuantity", "availableQuantity");
            assertThat(jdbc.queryForObject("select used_quantity from material_batches where id=?", BigDecimal.class, batch.getId()))
                    .isEqualByComparingTo("2.125");
        }
    }

    @Test
    void flushedBusinessRollbackRemovesStockAndItsSuccessAudit() {
        MaterialBatch batch = batch();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(batch); em.flush();
            assertThat(logs(batch.getId())).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(logs(batch.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from material_batches where id=?", Long.class, batch.getId())).isZero();
    }

    @Test
    void recordedQuantitiesMatchDatabaseRoundingInsteadOfExcessInputPrecision() throws Exception {
        MaterialBatch batch = batch(); batch.setReceiptQuantity(new BigDecimal("9.123456789"));
        tx(manager -> manager.persist(batch));
        tx(manager -> manager.find(MaterialBatch.class, batch.getId()).setUsedQuantity(new BigDecimal("1.0000004")));
        JsonNode after = value(logs(batch.getId()).get(1), "new_value");
        BigDecimal received = jdbc.queryForObject("select receipt_quantity from material_batches where id=?", BigDecimal.class, batch.getId());
        BigDecimal used = jdbc.queryForObject("select used_quantity from material_batches where id=?", BigDecimal.class, batch.getId());
        assertThat(after.path("receiptQuantity").decimalValue()).isEqualByComparingTo(received);
        assertThat(after.path("usedQuantity").decimalValue()).isEqualByComparingTo(used);
        assertThat(after.path("availableQuantity").decimalValue()).isEqualByComparingTo(received.subtract(used));
    }

    @Test
    void auditStorageFailureCannotCommitStockEvenWhenCallerCatchesIt() {
        MaterialBatch batch = batch(); tx(manager -> manager.persist(batch));
        tx(manager -> jdbc.execute("alter table operation_logs add constraint inventory_audit_failure_control check (entity_id <> '"
                + batch.getId() + "' or module <> 'INVENTORY' or action <> 'UPDATE')"));
        try {
            assertThatThrownBy(() -> tx(manager -> {
                manager.find(MaterialBatch.class, batch.getId()).setUsedQuantity(BigDecimal.ONE);
                try { manager.flush(); } catch (RuntimeException ignored) { /* simulate a service swallowing the error */ }
            })).isInstanceOf(RuntimeException.class);
        } finally {
            tx(manager -> jdbc.execute("alter table operation_logs drop constraint inventory_audit_failure_control"));
        }
        assertThat(logs(batch.getId())).hasSize(1);
        assertThat(jdbc.queryForObject("select used_quantity from material_batches where id=?", BigDecimal.class, batch.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    void noOpAndPriceOnlyEditsDoNotPretendToBeStockChangesAndSoftDeleteIsRetained() {
        MaterialBatch batch = batch(); tx(manager -> manager.persist(batch));
        tx(manager -> manager.find(MaterialBatch.class, batch.getId()).setReceiptQuantity(new BigDecimal("10.000000")));
        tx(manager -> manager.find(MaterialBatch.class, batch.getId()).setUnitPrice(new BigDecimal("1.50")));
        assertThat(logs(batch.getId())).hasSize(1);
        tx(manager -> manager.remove(manager.find(MaterialBatch.class, batch.getId())));
        assertThat(logs(batch.getId())).hasSize(2);
        assertThat(logs(batch.getId()).get(1).get("action")).isEqualTo("DELETE");
        assertThat(jdbc.queryForObject("select deleted_at is not null from material_batches where id=?", Boolean.class, batch.getId())).isTrue();
    }

    @Test
    void requisitionLineDiffUsesUninitializedParentIdentifier() throws Exception {
        FactoryMaterialRequisition requisition = new FactoryMaterialRequisition();
        requisition.setFactoryId(factory); requisition.setRequisitionNo("REQ-" + UUID.randomUUID());
        requisition.setProductionPlanId("audit-plan"); requisition.setSourceWarehouseId("WH-MAIN"); requisition.setTargetWarehouseId("WH-WORK");
        tx(manager -> manager.persist(requisition));
        FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
        tx(manager -> {
            item.setRequisition(manager.getReference(FactoryMaterialRequisition.class, requisition.getId()));
            item.setMaterialTypeId("audit-material"); item.setRequiredQty(new BigDecimal("2.125")); item.setUnit("kg");
            manager.persist(item);
        });
        tx(manager -> manager.find(FactoryMaterialRequisitionItem.class, item.getId()).setIssuedQty(new BigDecimal("2.125")));
        assertThat(logs(item.getId())).hasSize(2);
        JsonNode after = value(logs(item.getId()).get(1), "new_value");
        assertThat(after.path("requisitionId").asText()).isEqualTo(requisition.getId());
        assertThat(after.path("issuedQty").decimalValue()).isEqualByComparingTo("2.125");
        assertThat(logs(item.getId()).get(1).get("factory_id")).isEqualTo(factory);
        FactoryMaterialRequisitionItem detachedParentItem = new FactoryMaterialRequisitionItem();
        detachedParentItem.setRequisition(requisition);
        detachedParentItem.setMaterialTypeId("audit-detached-parent");
        detachedParentItem.setRequiredQty(BigDecimal.ONE); detachedParentItem.setUnit("kg");
        tx(manager -> manager.persist(detachedParentItem));
        assertThat(logs(detachedParentItem.getId())).hasSize(1);
        assertThat(value(logs(detachedParentItem.getId()).get(0), "new_value").path("requisitionId").asText()).isEqualTo(requisition.getId());
    }

    @Test
    void newRequisitionAndItsLinesAreAuditedWithinTheSameBatchedFlush() {
        assertThat(em.getEntityManagerFactory().unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class)
                .getSessionFactoryOptions().getJdbcBatchSize()).isEqualTo(100);
        FactoryMaterialRequisition requisition = new FactoryMaterialRequisition();
        requisition.setFactoryId(factory); requisition.setRequisitionNo("REQ-" + UUID.randomUUID());
        requisition.setProductionPlanId("audit-plan");
        for (int index = 0; index < 3; index++) {
            FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
            item.setRequisition(requisition); item.setMaterialTypeId("audit-material-" + index);
            item.setRequiredQty(new BigDecimal("2.125")); item.setUnit("kg");
            requisition.getItems().add(item);
        }
        tx(manager -> manager.persist(requisition));
        assertThat(logs(requisition.getId())).hasSize(1);
        for (FactoryMaterialRequisitionItem item : requisition.getItems()) {
            assertThat(logs(item.getId())).hasSize(1);
            assertThat(logs(item.getId()).get(0).get("factory_id")).isEqualTo(factory);
        }
    }

    @Test
    void transferAuditIsVisibleOnlyToParticipantsAndNotDuplicatedForSameFactory() {
        for (String target : List.of(factory, otherFactory)) {
            InternalTransfer transfer = new InternalTransfer();
            transfer.setSourceFactoryId(factory); transfer.setTargetFactoryId(target);
            transfer.setTransferNumber("TR-" + UUID.randomUUID()); transfer.setTransferDate(LocalDate.now());
            transfer.setTransferType(TransferType.WAREHOUSE_TO_WAREHOUSE);
            tx(manager -> manager.persist(transfer));
            InternalTransferItem item = new InternalTransferItem();
            item.setTransferId(transfer.getId()); item.setItemType(TransferItemType.RAW_MATERIAL);
            item.setQuantity(new BigDecimal("2.125")); item.setUnit("kg"); item.setSourceBatchId("source"); item.setTargetBatchId("target");
            tx(manager -> manager.persist(item));
            assertThat(logs(transfer.getId())).hasSize(factory.equals(target) ? 1 : 2);
            assertThat(logs(String.valueOf(item.getId()))).extracting(row -> row.get("factory_id"))
                    .containsExactlyInAnyOrderElementsOf(factory.equals(target) ? List.of(factory) : List.of(factory, target));
        }
    }

    @Test
    void finishedStockAndSystemActorAreRecordedWithoutInventingAnOperator() throws Exception {
        String productId = UUID.randomUUID().toString();
        tx(manager -> jdbc.update("""
                insert into product_types (id,factory_id,code,name,unit,created_by,is_active,created_at,updated_at)
                values (?,?,?,'audit product','kg',?,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, productId, factory, "AUD-" + productId, actor));
        RequestContextHolder.resetRequestAttributes();
        FinishedGoodsBatch batch = new FinishedGoodsBatch(); batch.setFactoryId(factory); batch.setBatchNumber("FG-" + UUID.randomUUID());
        batch.setProductTypeId(productId); batch.setProducedQuantity(new BigDecimal("10.12345")); batch.setUnit("kg"); batch.setWarehouseId("WH-WORK");
        tx(manager -> manager.persist(batch));
        tx(manager -> manager.find(FinishedGoodsBatch.class, batch.getId()).setShippedQuantity(new BigDecimal("3.5")));
        assertThat(logs(batch.getId())).hasSize(2);
        var update = logs(batch.getId()).get(1);
        assertThat(update.get("user_id")).isNull(); assertThat(update.get("summary").toString()).contains("origin=system");
        assertThat(value(update, "new_value").path("producedQuantity").decimalValue()).isEqualByComparingTo("10.1235");
        assertThat(value(update, "new_value").path("availableQuantity").decimalValue()).isEqualByComparingTo("6.6235");
        assertThat(jdbc.queryForObject("select produced_quantity from finished_goods_batches where id=?", BigDecimal.class, batch.getId()))
                .isEqualByComparingTo(value(update, "new_value").path("producedQuantity").decimalValue());
    }

    @Test
    void legalLongStockIdIsPreservedWithoutOverflowingTheExistingLogColumn() throws Exception {
        MaterialBatch batch = batch(); batch.setId("long-stock-" + "x".repeat(140));
        tx(manager -> manager.persist(batch));
        var rows = jdbc.queryForList("select * from operation_logs where factory_id=? and module='INVENTORY'", factory);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("entity_id").toString()).startsWith("sha256:").hasSize(71);
        assertThat(value(rows.get(0), "new_value").path("recordId").asText()).isEqualTo(batch.getId());
        if ("true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"))) {
            // PostgreSQL verifies both JSONB deserialization and the real controller/repository filter.
            var response = new OperationLogController(operationLogs).list(factory, "INVENTORY", null, null,
                    "MaterialBatch", batch.getId(), null, null, 0, 50);
            assertThat(response.getBody().getData().getContent()).hasSize(1);
            assertThat(response.getBody().getData().getContent().get(0).getNewValue().get("recordId")).isEqualTo(batch.getId());
        }
    }

    @Test
    void changedTenantDoesNotCopyTheOtherTenantsSnapshot() throws Exception {
        MaterialBatch batch = batch(); tx(manager -> manager.persist(batch));
        tx(manager -> manager.find(MaterialBatch.class, batch.getId()).setFactoryId(otherFactory));
        var rows = logs(batch.getId()); assertThat(rows).hasSize(3);
        var departed = rows.get(1); var arrived = rows.get(2);
        assertThat(departed.get("factory_id")).isEqualTo(factory);
        assertThat(departed.get("new_value")).isNull();
        assertThat(value(departed, "old_value").path("factoryId").asText()).isEqualTo(factory);
        assertThat(arrived.get("factory_id")).isEqualTo(otherFactory);
        assertThat(arrived.get("old_value")).isNull();
        assertThat(value(arrived, "new_value").path("factoryId").asText()).isEqualTo(otherFactory);
    }

    @Test
    void untrustedRequestDoesNotForgeTheInventoryOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/" + otherFactory + "/transfers");
        request.setAttribute("factoryId", factory); request.setAttribute("userId", actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MaterialBatch batch = batch(); tx(manager -> manager.persist(batch));
        assertThat(logs(batch.getId()).get(0).get("user_id")).isNull();
        assertThat(logs(batch.getId()).get(0).get("summary").toString()).contains("origin=unattributed_request");
    }

    private WorkProcessTask task(long productionBatchId, long workerId) {
        WorkProcessTask task = new WorkProcessTask();
        task.setFactoryId(factory);
        task.setProductionBatchId(productionBatchId);
        task.setWorkProcessId("legacy-process");
        task.setProductTypeId("legacy-product");
        task.setProcessOrder(1);
        task.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        task.setAssignedTo(workerId);
        task.setPlannedUnit("kg");
        return task;
    }

    private RawMaterialType rawMaterial(String sku) {
        RawMaterialType raw = new RawMaterialType();
        raw.setId(sku);
        raw.setFactoryId(factory);
        raw.setCode(sku);
        raw.setName("Audit raw material " + sku);
        raw.setCategory("肉类");
        raw.setUnit("kg");
        raw.setIsActive(true);
        raw.setIsByproduct(false);
        raw.setSelfSupplied(false);
        return raw;
    }

    private MaterialBatch reservationBatch(String id, BigDecimal receiptQuantity) {
        MaterialBatch batch = batch();
        batch.setId(id);
        batch.setBatchNumber("RES-" + id);
        batch.setReceiptQuantity(receiptQuantity);
        batch.setWarehouseId("WH-WORK");
        return batch;
    }

    private YieldReportRequest inputRequest(Long taskId, String clientRequestId) {
        YieldReportRequest request = new YieldReportRequest();
        request.setWorkProcessTaskId(taskId);
        request.setClientRequestId(clientRequestId);
        request.setReportKind("INPUT");
        request.setInputQuantity(BigDecimal.TEN);
        request.setInputUnit("kg");
        return request;
    }

    private WorkflowTaskPort port(WorkProcessTask task, WorkflowTaskPort.Direction direction,
                                  String skuId, String unit, String unitCode) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId(factory);
        port.setWorkflowInstanceId(task.getWorkflowInstanceId());
        port.setTaskId(task.getId());
        port.setWorkflowPortId(direction.name() + "-PORT-" + skuId);
        port.setDirection(direction);
        port.setOrdinal(direction == WorkflowTaskPort.Direction.INPUT ? 1 : 2);
        port.setMaterialNodeId(direction.name() + "-NODE-" + skuId);
        port.setMaterialKind(direction == WorkflowTaskPort.Direction.INPUT ? "RAW_MATERIAL" : "OUTPUT");
        port.setSkuId(skuId);
        port.setUnit(unit);
        port.setUnitCode(unitCode);
        port.setRequired(true);
        return port;
    }

    private void submitOnThread(java.util.concurrent.CompletableFuture<Map<String, Object>> outcome,
                                Long taskId, long batchId, String requestId) {
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/" + factory + "/reports");
            request.setAttribute("factoryId", factory);
            request.setAttribute("userId", actor);
            request.setAttribute("username", "audit-concurrent-operator");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            YieldReportRequest report = inputRequest(taskId, requestId);
            report.setInputQuantity(BigDecimal.valueOf(7));
            report.setMaterialBatchRefs(List.of(ref("CONCURRENT-001", "7")));
            outcome.complete(yieldReports.submitReport(factory, batchId, actor, report));
        } catch (Throwable failure) {
            outcome.completeExceptionally(failure);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private Map<String, Object> awaitOutcome(java.util.concurrent.CompletableFuture<Map<String, Object>> outcome)
            throws Exception {
        try {
            return outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            return null;
        }
    }

    private Throwable awaitFailure(java.util.concurrent.CompletableFuture<Map<String, Object>> outcome)
            throws Exception {
        try {
            outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            return cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null
                    ? cause.getCause() : cause;
        }
    }

    private String futureDescription(java.util.concurrent.CompletableFuture<?> future) {
        if (!future.isDone()) return "RUNNING";
        try {
            return "SUCCESS:" + future.getNow(null);
        } catch (java.util.concurrent.CompletionException failure) {
            return "FAILED:" + failure.getCause();
        }
    }

    private MaterialBatchRef ref(String batchId, String quantity) {
        MaterialBatchRef ref = new MaterialBatchRef();
        ref.setMaterialBatchId(batchId);
        ref.setQuantity(new BigDecimal(quantity));
        ref.setUnit("kg");
        return ref;
    }

    private MaterialBatch batch() {
        MaterialBatch batch = new MaterialBatch(); batch.setId(UUID.randomUUID().toString()); batch.setFactoryId(factory);
        batch.setCustomFields(null); // unrelated JsonBinaryType map is not portable to H2's JSON string cast
        batch.setBatchNumber("MB-" + UUID.randomUUID()); batch.setReceiptQuantity(BigDecimal.TEN); batch.setQuantityUnit("kg");
        batch.setReceiptDate(LocalDate.now()); batch.setWarehouseId("WH-MAIN"); batch.setCreatedBy(actor);
        batch.setSourceDocType("TRANSFER_IN"); batch.setSourceDocId("audit-transfer"); return batch;
    }

    private void tx(Consumer<EntityManager> work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.accept(em));
    }

    private List<Map<String, Object>> logs(String id) {
        return jdbc.queryForList("select * from operation_logs where module='INVENTORY' and entity_id=? order by id", id);
    }

    private JsonNode value(Map<String, Object> row, String column) throws Exception {
        Object raw = row.get(column);
        JsonNode node = json.readTree(raw instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : raw.toString());
        // H2's JSON cast stores a JSON string; PG stores the JSON object. Compare their parsed values.
        return node.isTextual() ? json.readTree(node.asText()) : node;
    }
}

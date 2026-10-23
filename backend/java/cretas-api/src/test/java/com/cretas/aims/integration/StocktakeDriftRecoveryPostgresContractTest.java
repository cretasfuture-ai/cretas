package com.cretas.aims.integration;

import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeRecountRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.ProductionReportCorrectionService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in real JPA/PostgreSQL proof for the drift recovery boundary.  The test owns
 * a random schema and calls the real service entry; only unrelated external services
 * are mocked.  No inventory adjustment or workflow callback is faked as the recovery.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import(FactoryStocktakeServiceImpl.class)
class StocktakeDriftRecoveryPostgresContractTest {

    private static final String FACTORY = "S307-PG";
    private static final String WAREHOUSE = "S307-WH";
    private static final String SCHEMA = "s307_recount_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired private EntityManager entityManager;
    @Autowired private FactoryStocktakeRepository stocktakeRepository;
    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private FactoryStocktakeService stocktakeService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @MockBean private UnitContractService unitContractService;
    @MockBean private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @MockBean private ProductionReportCorrectionService productionReportCorrectionService;
    @MockBean private VoucherService voucherService;
    @MockBean private WorkflowEngineService workflowEngineService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        createSchema(safeUrl, user, password);
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @AfterAll
    static void dropOwnedSchema() {
        String rawUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (rawUrl == null || rawUrl.isBlank()) return;
        String url = DisposablePostgresTargetGuard.requireSafeUrl(rawUrl);
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to drop owned PostgreSQL schema " + SCHEMA, error);
        }
    }

    @Test
    void realServiceRecoversDriftAndPersistsOnlyAuditPlusFreshSnapshot() {
        Fixture fixture = persistFixture("ST-DRIFT-1", new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("12"));
        FactoryStocktake approvedBefore = fixture.source();
        Long submittedByBefore = approvedBefore.getSubmittedBy();
        LocalDateTime submittedAtBefore = approvedBefore.getSubmittedAt();
        Long approvedByBefore = approvedBefore.getApprovedBy();
        LocalDateTime approvedAtBefore = approvedBefore.getApprovedAt();
        String workflowBefore = approvedBefore.getWorkflowInstanceId();
        StocktakeRecountRequest request = request(approvedBefore.getVersion(), "同仓调拨后重新盘点");

        assertThatThrownBy(() -> stocktakeService.recountAfterDrift(
                fixture.source().getId(), FACTORY, request, 7001L, "operator"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(403);
                    assertThat(error.getErrorCode()).isEqualTo("STOCKTAKE_RECOUNT_ROLE_FORBIDDEN");
                });
        entityManager.clear();
        assertThat(stocktakeRepository.findById(fixture.source().getId()).orElseThrow().getStatus())
                .isEqualTo(FactoryStocktake.Status.APPROVED);

        StocktakeDTO child = stocktakeService.recountAfterDrift(
                fixture.source().getId(), FACTORY, request, 7001L, "warehouse_manager");
        entityManager.flush();
        entityManager.clear();

        FactoryStocktake source = stocktakeRepository.findById(fixture.source().getId()).orElseThrow();
        FactoryStocktake fresh = stocktakeRepository.findById(child.getId()).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(FactoryStocktake.Status.RECOUNT_REQUIRED);
        assertThat(source.getSubmittedBy()).isEqualTo(submittedByBefore);
        assertThat(source.getSubmittedAt()).isEqualTo(submittedAtBefore);
        assertThat(source.getApprovedBy()).isEqualTo(approvedByBefore);
        assertThat(source.getApprovedAt()).isEqualTo(approvedAtBefore);
        assertThat(source.getWorkflowInstanceId()).isEqualTo(workflowBefore);
        assertThat(source.getItems().get(0).getSystemQty()).isEqualByComparingTo("10");
        assertThat(source.getItems().get(0).getActualQty()).isEqualByComparingTo("11");
        assertThat(source.getRecountStocktakeId()).isEqualTo(fresh.getId());
        assertThat(fresh.getStatus()).isEqualTo(FactoryStocktake.Status.INITIATED);
        assertThat(fresh.getRecountSourceStocktakeId()).isEqualTo(source.getId());
        assertThat(fresh.getItems().get(0).getSystemQty()).isEqualByComparingTo("12");
        assertThat(fresh.getItems().get(0).getActualQty()).isNull();
        assertThat(materialBatchRepository.findById(fixture.batch().getId()).orElseThrow()
                .getReceiptQuantity()).isEqualByComparingTo("12");

        StocktakeDTO idempotent = stocktakeService.recountAfterDrift(
                source.getId(), FACTORY, request, 7001L, "warehouse_manager");
        assertThat(idempotent.getId()).isEqualTo(fresh.getId());
        assertThat(stocktakeRepository.findByFactoryIdAndPeriodMonth(FACTORY, source.getPeriodMonth()))
                .extracting(FactoryStocktake::getStatus)
                .containsExactlyInAnyOrder(FactoryStocktake.Status.RECOUNT_REQUIRED,
                        FactoryStocktake.Status.INITIATED);
    }

    @Test
    void realServiceRejectsStaleVersionAndNoDriftWithoutMutation() {
        Fixture stale = persistFixture("ST-STALE", new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("12"));
        StocktakeRecountRequest staleRequest = request(stale.source().getVersion() - 1, "stale");
        assertThatThrownBy(() -> stocktakeService.recountAfterDrift(
                stale.source().getId(), FACTORY, staleRequest, 7001L, "warehouse_manager"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("STALE_STOCKTAKE_VERSION"));
        entityManager.clear();
        assertThat(stocktakeRepository.findById(stale.source().getId()).orElseThrow().getStatus())
                .isEqualTo(FactoryStocktake.Status.APPROVED);

        Fixture noDrift = persistFixture("ST-NO-DRIFT", new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("10"));
        StocktakeRecountRequest noDriftRequest = request(noDrift.source().getVersion(), "误操作");
        assertThatThrownBy(() -> stocktakeService.recountAfterDrift(
                noDrift.source().getId(), FACTORY, noDriftRequest, 7001L, "warehouse_manager"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("STOCKTAKE_RECOUNT_NOT_REQUIRED"));
        entityManager.clear();
        assertThat(stocktakeRepository.findById(noDrift.source().getId()).orElseThrow().getStatus())
                .isEqualTo(FactoryStocktake.Status.APPROVED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRecoveryRequestsShareOneLockedChild() throws Exception {
        Fixture fixture = persistFixture("ST-CONCURRENT", new BigDecimal("10"),
                new BigDecimal("11"), new BigDecimal("12"));
        Long version = fixture.source().getVersion();
        StocktakeRecountRequest request = request(version, "并发恢复");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockConnection = dataSource.getConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM factory_stocktakes WHERE id = ? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setString(1, fixture.sourceId);
            lock.executeQuery().close();

            Future<StocktakeDTO> first = executor.submit(() -> stocktakeService.recountAfterDrift(
                    fixture.sourceId, FACTORY, request, 7001L, "warehouse_manager"));
            assertThatThrownBy(() -> first.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            Future<StocktakeDTO> second = executor.submit(() -> stocktakeService.recountAfterDrift(
                    fixture.sourceId, FACTORY, request, 7002L, "warehouse_manager"));
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            lockConnection.commit();
            StocktakeDTO firstResult = first.get(10, TimeUnit.SECONDS);
            StocktakeDTO secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.getId()).isEqualTo(secondResult.getId());
            entityManager.clear();
            assertThat(stocktakeRepository.findByFactoryIdAndPeriodMonth(FACTORY, "2026-10"))
                    .filteredOn(stocktake -> stocktake.getRecountSourceStocktakeId() != null)
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture persistFixture(String stocktakeNo, BigDecimal systemQty,
            BigDecimal actualQty, BigDecimal liveQty) {
        return new TransactionTemplate(transactionManager).execute(status -> {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("S307-BATCH-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setMaterialTypeId("S307-MATERIAL");
        batch.setReceiptDate(LocalDate.now());
        batch.setWarehouseId(WAREHOUSE);
        batch.setReceiptQuantity(liveQty);
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(7000L);
        materialBatchRepository.save(batch);

        FactoryStocktake source = new FactoryStocktake();
        source.setId(UUID.randomUUID().toString());
        source.setFactoryId(FACTORY);
        source.setStocktakeNo(stocktakeNo);
        source.setWarehouseId(WAREHOUSE);
        source.setInventoryScope(FactoryStocktake.InventoryScope.RAW_MATERIAL);
        source.setPeriodMonth("2026-10");
        source.setInventoryCutoffAt(LocalDateTime.now().minusMinutes(10));
        source.setReconciliationStartAt(LocalDateTime.now().minusDays(1));
        source.setReconciliationEndAt(source.getInventoryCutoffAt());
        source.setReconciliationPreset("LAST_7_DAYS");
        source.setStatus(FactoryStocktake.Status.APPROVED);
        source.setInitiatedBy(6000L);
        source.setInitiatedAt(LocalDateTime.now().minusMinutes(9));
        source.setSubmittedBy(6001L);
        source.setSubmittedAt(LocalDateTime.now().minusMinutes(8));
        source.setApprovedBy(7000L);
        source.setApprovedAt(LocalDateTime.now().minusMinutes(7));
        source.setWorkflowInstanceId("oa-s307-1");
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setStocktake(source);
        item.setMaterialBatchId(batch.getId());
        item.setRawMaterialTypeId(batch.getMaterialTypeId());
        item.setQuantityUnit("kg");
        item.setSystemQty(systemQty);
        item.setActualQty(actualQty);
        item.setDifferenceQty(actualQty.subtract(systemQty));
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);
        source.setItems(List.of(item));
        stocktakeRepository.save(source);
        entityManager.flush();
        entityManager.clear();
        return new Fixture(source.getId(), batch.getId());
        });
    }

    private StocktakeRecountRequest request(Long version, String reason) {
        StocktakeRecountRequest request = new StocktakeRecountRequest();
        request.setExpectedVersion(version);
        request.setReason(reason);
        return request;
    }

    private final class Fixture {
        private final String sourceId;
        private final String batchId;

        private Fixture(String sourceId, String batchId) {
            this.sourceId = sourceId;
            this.batchId = batchId;
        }

        FactoryStocktake source() {
            return stocktakeRepository.findById(sourceId).orElseThrow();
        }

        MaterialBatch batch() {
            return materialBatchRepository.findById(batchId).orElseThrow();
        }
    }

    private static void createSchema(String url, String user, String password) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to create owned PostgreSQL schema " + SCHEMA, error);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}

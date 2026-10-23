package com.cretas.aims.integration;

import com.cretas.aims.dto.production.ProductionReportCorrectionDTO;
import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionReportCorrection;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportCorrectionRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.impl.ProductionReportCorrectionServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opt-in real-JPA proof that plan locks and settlement races stop correction writes.
 * The test creates its own schema inside the disposable local PostgreSQL database so it
 * cannot create/drop public tables shared by other PostgreSQL tests.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import(ProductionReportCorrectionServiceImpl.class)
class ProductionReportCorrectionLockedStatePostgresContractTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "S305-LOCK-SKU";
    private static final String SCHEMA = "s305_lock_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired private EntityManager entityManager;
    @Autowired private ProductionPlanRepository planRepository;
    @Autowired private ProductionBatchRepository batchRepository;
    @Autowired private ProductionReportRepository reportRepository;
    @Autowired private ProductionReportCorrectionRepository correctionRepository;
    @Autowired private WorkProcessTaskRepository taskRepository;
    @Autowired private FactoryRepository factoryRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductionReportCorrectionServiceImpl applicationService;

    @MockBean private WorkflowEngineService workflowEngine;
    @MockBean private WipInventoryService wipInventoryService;

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
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @AfterAll
    static void dropOwnedSchema() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) return;
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(url);
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(safeUrl, user, password);
             Statement statement = connection.createStatement();
             Statement tableQuery = connection.createStatement();
             Statement tableDrop = connection.createStatement()) {
            try (var tables = tableQuery.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + SCHEMA + "'")) {
                while (tables.next()) {
                    String table = tables.getString(1).replace("\"", "\"\"");
                    tableDrop.execute("DROP TABLE IF EXISTS " + SCHEMA + ".\"" + table + "\" CASCADE");
                }
            }
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to drop owned PostgreSQL schema " + SCHEMA, error);
        }
    }

    @Test
    void unlockedPlanAllowsSubmitAndPersistsPendingCorrection() {
        Fixture fixture = persistFixture();
        ApprovalWorkflowInstance workflow = runningWorkflow();
        when(workflowEngine.startWorkflowIfConfigured(eq(FACTORY), eq(ProductionReportCorrectionServiceImpl.MODULE_CODE),
                any(), anyMap(), eq(fixture.actorId()))).thenReturn(Optional.of(workflow));

        ProductionReportCorrectionDTO submitted = submit(fixture);

        entityManager.flush();
        entityManager.clear();
        assertThat(correctionRepository.findByIdAndFactoryId(submitted.getId(), FACTORY))
                .get().extracting(ProductionReportCorrection::getStatus)
                .isEqualTo(ProductionReportCorrection.Status.PENDING_APPROVAL);
        assertThat(planRepository.findById(fixture.planId()).orElseThrow().getIsLocked()).isFalse();
        verify(workflowEngine).startWorkflowIfConfigured(eq(FACTORY),
                eq(ProductionReportCorrectionServiceImpl.MODULE_CODE), eq(submitted.getId()), anyMap(), eq(fixture.actorId()));
    }

    @Test
    void persistedPlanLockRejectsApprovalAfterSubmitWithoutApplyingOrTransitioning() {
        Fixture fixture = persistFixture();
        ApprovalWorkflowInstance workflow = runningWorkflow();
        when(workflowEngine.startWorkflowIfConfigured(eq(FACTORY), eq(ProductionReportCorrectionServiceImpl.MODULE_CODE),
                any(), anyMap(), eq(fixture.actorId()))).thenReturn(Optional.of(workflow));
        ProductionReportCorrectionDTO submitted = submit(fixture);

        ProductionPlan locked = planRepository.findById(fixture.planId()).orElseThrow();
        locked.setIsLocked(true);
        planRepository.saveAndFlush(locked);
        entityManager.clear();
        long correctionCountBefore = correctionRepository.count();
        long reportCountBefore = reportRepository.count();
        ProductionReport before = reportRepository.findById(fixture.reportId()).orElseThrow();
        BigDecimal outputBefore = before.getOutputQuantity();
        long taskCountBefore = countTasks();

        assertThatThrownBy(() -> applicationService.applyWorkflowAction(FACTORY, submitted.getId(),
                submitted.getWorkflowInstanceId(), fixture.actorId(), "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_REPORT_CORRECTION_PLAN_LOCKED");
                })
                .hasMessageContaining("生产计划已锁定");

        entityManager.clear();
        assertThat(correctionRepository.count()).isEqualTo(correctionCountBefore);
        assertThat(reportRepository.count()).isEqualTo(reportCountBefore);
        assertThat(reportRepository.findById(fixture.reportId()).orElseThrow().getOutputQuantity())
                .isEqualByComparingTo(outputBefore);
        assertThat(countTasks()).isEqualTo(taskCountBefore);
        assertThat(planRepository.findById(fixture.planId()).orElseThrow().getIsLocked()).isTrue();
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(wipInventoryService, never()).correctApprovedOutputQuantity(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistedSettlementAfterSubmitRejectsApprovalBeforeTransitionWithoutApplying() {
        Fixture fixture = persistFixture();
        ApprovalWorkflowInstance workflow = runningWorkflow();
        when(workflowEngine.startWorkflowIfConfigured(eq(FACTORY), eq(ProductionReportCorrectionServiceImpl.MODULE_CODE),
                any(), anyMap(), eq(fixture.actorId()))).thenReturn(Optional.of(workflow));
        ProductionReportCorrectionDTO submitted = submit(fixture);

        ProductionReport settled = reportRepository.findById(fixture.reportId()).orElseThrow();
        settled.setSettled(true);
        reportRepository.saveAndFlush(settled);
        entityManager.clear();
        long correctionCountBefore = correctionRepository.count();
        long reportCountBefore = reportRepository.count();
        ProductionReport before = reportRepository.findById(fixture.reportId()).orElseThrow();
        BigDecimal outputBefore = before.getOutputQuantity();
        long taskCountBefore = countTasks();

        assertThatThrownBy(() -> applicationService.applyWorkflowAction(FACTORY, submitted.getId(),
                submitted.getWorkflowInstanceId(), fixture.actorId(), "configured_role", HistoryAction.APPROVE, "同意"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_REPORT_CORRECTION_SETTLEMENT_RACE");
                })
                .hasMessageContaining("已结单");

        entityManager.clear();
        assertThat(correctionRepository.count()).isEqualTo(correctionCountBefore);
        assertThat(reportRepository.count()).isEqualTo(reportCountBefore);
        ProductionReport after = reportRepository.findById(fixture.reportId()).orElseThrow();
        assertThat(after.getSettled()).isTrue();
        assertThat(after.getOutputQuantity()).isEqualByComparingTo(outputBefore);
        assertThat(countTasks()).isEqualTo(taskCountBefore);
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());
        verify(wipInventoryService, never()).correctApprovedOutputQuantity(
                any(), any(), any(), any(), any(), any(), any());
    }

    private ProductionReportCorrectionDTO submit(Fixture fixture) {
        return applicationService.submit(FACTORY, fixture.reportId(),
                new ProductionReportCorrectionRequest(new BigDecimal("9"), new BigDecimal("8"),
                        BigDecimal.ONE, 3, 90, "修正备注", "现场复核"), fixture.actorId());
    }

    private Fixture persistFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String planId = "S305-PLAN-" + suffix;
        User actor = new User();
        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setName("S305 lock contract factory");
        factoryRepository.saveAndFlush(factory);
        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setCode(PRODUCT);
        product.setName("S305 lock contract product");
        product.setUnit("kg");
        product.setIsActive(true);
        productTypeRepository.saveAndFlush(product);
        actor.setFactoryId(FACTORY);
        actor.setUsername("s305-lock-" + suffix);
        actor.setPasswordHash("integration-test-unused");
        actor.setFullName("S305 lock contract actor");
        actor.setIsActive(true);
        actor.setRoleCode("operator");
        actor = userRepository.saveAndFlush(actor);
        long actorId = actor.getId();
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("S305-" + suffix);
        plan.setProductTypeId(PRODUCT);
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(actorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        planRepository.saveAndFlush(plan);

        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("S305-BATCH-" + suffix);
        batch.setProductionPlanId(planId);
        batch.setProductTypeId(PRODUCT);
        batch.setQuantity(new BigDecimal("10"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch = batchRepository.saveAndFlush(batch);

        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY).batchId(batch.getId()).workerId(actorId)
                .reportType(ProductionReport.ReportType.PROGRESS).reportDate(LocalDate.now())
                .status(ProductionReport.Status.SUBMITTED).settled(false).processCategory("包装")
                .productTypeId(PRODUCT).outputQuantity(new BigDecimal("10"))
                .goodQuantity(new BigDecimal("9")).defectQuantity(BigDecimal.ONE)
                .totalWorkers(2).totalWorkMinutes(60).notes("原备注")
                .build();
        report = reportRepository.saveAndFlush(report);
        entityManager.clear();
        return new Fixture(planId, batch.getId(), report.getId(), actorId);
    }

    private ApprovalWorkflowInstance runningWorkflow() {
        ApprovalWorkflowInstance instance = org.mockito.Mockito.mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn("WF-S305-LOCK");
        when(instance.getStatus()).thenReturn(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        return instance;
    }

    private long countTasks() {
        return taskRepository.count();
    }

    private static void createSchema(String url, String user, String password) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to create owned PostgreSQL schema " + SCHEMA, error);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    private record Fixture(String planId, Long batchId, Long reportId, long actorId) {
    }
}

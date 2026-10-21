package com.cretas.aims.repository;

import com.cretas.aims.controller.SchedulingDiagnosticsController;
import com.cretas.aims.service.scheduling.SchedulingReadinessService;
import com.cretas.aims.service.scheduling.impl.SchedulingAIServiceImpl;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.service.FeatureEngineeringService;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import org.springframework.web.client.RestTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real Hibernate queries and service beans; fixtures are confined to this isolated test database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SchedulingReadinessService.class, SchedulingDiagnosticsController.class, SchedulingAIServiceImpl.class})
class SchedulingReadinessRepositoryQueryValidationTest {
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired SchedulingDiagnosticsController controller;
    @Autowired SchedulingReadinessService service;
    @Autowired SchedulingAIServiceImpl predictions;
    @MockBean RestTemplate restTemplate;
    @MockBean FeatureEngineeringService featureEngineeringService;
    @MockBean NotificationService notificationService;
    @MockBean PushNotificationService pushNotificationService;
    @MockBean SchedulingPlanCrudService planCrudService;
    @MockBean WorkerAssignmentService workerAssignmentService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/cretas_workflow_verify_test")) {
                throw new IllegalArgumentException("Real PG verification requires the owned loopback test database");
            }
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            properties.add("spring.datasource.username", () -> "cretas_ci");
            properties.add("spring.datasource.password", () -> "");
            properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:scheduling_readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE");
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("spring.flyway.enabled", () -> "false");
        properties.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Test
    void actualScopedReferencesNeverBecomePhysicalConfirmationAndDoNotWriteBusinessRows() {
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            var metadata = connection.getMetaData();
            System.out.printf("[SCHEDULING_READINESS_DB] product=%s version=%s driver=%s%n",
                    metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(), metadata.getDriverName());
            String expected = System.getenv("CRETAS_EXPECTED_PG_MAJOR");
            if (expected != null) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
                assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(Integer.parseInt(expected));
                assertThat(connection.getCatalog()).isEqualTo("cretas_workflow_verify_test");
            }
        });
        entityManager.createNativeQuery("INSERT INTO production_lines (id,factory_id,name,status,created_at,updated_at) "
                + "VALUES ('READINESS-LINE','READINESS-OWNER','Fixture line','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").executeUpdate();
        var plan = new SchedulingPlan();
        plan.setId("READINESS-PLAN"); plan.setFactoryId("READINESS-OWNER"); plan.setPlanDate(java.time.LocalDate.of(2030, 1, 1));
        entityManager.persist(plan);
        // Native scalar seed avoids binding optional JSON fields (custom_fields/photo_completed_stages)
        // through Hibernate's JSONB mapper; all scheduling references remain real database rows.
        entityManager.createNativeQuery("INSERT INTO production_batches "
                + "(factory_id,batch_number,product_type_id,quantity,unit,status,batch_type,created_at,updated_at) "
                + "VALUES ('READINESS-OWNER','READINESS-BATCH','READINESS-PRODUCT',10,'kg','PLANNED','REGULAR',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .executeUpdate();
        Long batchId = ((Number) entityManager.createNativeQuery(
                "SELECT id FROM production_batches WHERE factory_id='READINESS-OWNER' AND batch_number='READINESS-BATCH'")
                .getSingleResult()).longValue();
        var schedule = new LineSchedule();
        schedule.setId("READINESS-SCHEDULE"); schedule.setPlan(plan); schedule.setProductionLineId("READINESS-LINE");
        schedule.setBatchId(batchId); schedule.setPlannedQuantity(10); schedule.setCompletedQuantity(0); schedule.setAssignedWorkers(1);
        schedule.setPlannedEndTime(LocalDateTime.of(2030, 1, 1, 10, 0));
        entityManager.persist(schedule);
        entityManager.createNativeQuery("INSERT INTO production_lines(id,factory_id,name,status,created_at,updated_at) VALUES ('READINESS-FOREIGN-LINE','READINESS-OTHER','SECRET FOREIGN LINE','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").executeUpdate();
        entityManager.createNativeQuery("INSERT INTO production_batches(factory_id,batch_number,product_type_id,quantity,unit,status,batch_type,created_at,updated_at) VALUES ('READINESS-OTHER','SECRET FOREIGN BATCH','SECRET-FOREIGN-PRODUCT',10,'kg','PLANNED','REGULAR',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").executeUpdate();
        Long foreignBatchId = ((Number) entityManager.createNativeQuery("SELECT id FROM production_batches WHERE factory_id='READINESS-OTHER' AND batch_number='SECRET FOREIGN BATCH'").getSingleResult()).longValue();
        for (String reference : java.util.List.of("LINE", "BATCH", "BOTH")) {
            var damaged = new LineSchedule(); damaged.setId("READINESS-CROSS-" + reference); damaged.setPlan(plan);
            damaged.setProductionLineId(reference.equals("BATCH") ? "READINESS-LINE" : "READINESS-FOREIGN-LINE");
            damaged.setBatchId(reference.equals("LINE") ? batchId : foreignBatchId);
            damaged.setPlannedQuantity(10); damaged.setCompletedQuantity(0); damaged.setAssignedWorkers(1);
            damaged.setPlannedEndTime(LocalDateTime.of(2030, 1, 1, 10, 0)); entityManager.persist(damaged);
        }
        entityManager.flush(); entityManager.clear();
        Map<String, Long> before = counts();
        Map<String, java.util.List<String>> beforeRows = snapshot();
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        var missing = controller.readiness("READINESS-OWNER", null, null, null, null, null).getData();
        assertThat(missing.getStatus()).isEqualTo("INCOMPLETE");
        var own = service.check("READINESS-OWNER", "READINESS-LINE", null,
                LocalDateTime.of(2030, 1, 1, 9, 0), LocalDateTime.of(2030, 1, 1, 10, 0));
        assertThat(own.getProductionLine().isSelected()).isTrue();
        assertThat(own.getProductionLine().isConfirmed()).isFalse();
        assertThat(own.isReadyForAutomation()).isFalse();
        var eligible = service.check("READINESS-OWNER", "READINESS-LINE", batchId,
                LocalDateTime.of(2030, 1, 1, 9, 0), LocalDateTime.of(2030, 1, 1, 10, 0));
        assertThat(eligible.getStatus()).isEqualTo("INPUTS_VALID_UNCONFIRMED");
        assertThat(eligible.isReadyForAutomation()).isFalse();
        for (String reference : java.util.List.of("LINE", "BATCH", "BOTH")) {
            var blocked = predictions.calculateCompletionProbability("READINESS-OWNER", "READINESS-CROSS-" + reference);
            assertThat(blocked.getScheduleId()).isEqualTo("READINESS-CROSS-" + reference);
            assertThat(blocked.getPredictionOrigin()).isEqualTo("BUSINESS_INPUT");
            assertThat(blocked.getPredictionReason()).isEqualTo("TENANT_SCOPED_RESOURCE_MISSING");
            assertThat(blocked).hasAllNullFieldsOrPropertiesExcept("scheduleId", "predictionOrigin", "predictionReason");
        }
        org.springframework.test.util.ReflectionTestUtils.setField(predictions, "hybridPredictEnabled", false);
        try {
            var legacyOwn = predictions.calculateCompletionProbability("READINESS-OWNER", "READINESS-SCHEDULE");
            assertThat(legacyOwn.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
            assertThat(legacyOwn.getProductionLineName()).isEqualTo("Fixture line");
            assertThat(legacyOwn.getBatchNumber()).isEqualTo("READINESS-BATCH");
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(predictions, "hybridPredictEnabled", true); }
        var diagnostic = predictions.diagnoseCompletionProbability("READINESS-OWNER", "READINESS-SCHEDULE");
        assertThat(diagnostic.getPredictionOrigin()).isEqualTo("BUSINESS_INPUT");
        assertThat(diagnostic.getPredictionReason()).isEqualTo("VERIFIED_MODEL_FEATURES_UNAVAILABLE");
        var existing = service.check("READINESS-OWNER", null, null, null, null, "READINESS-SCHEDULE");
        assertThat(existing.getRequestMode()).isEqualTo("EXISTING_SCHEDULE");
        assertThat(existing.getScheduleId()).isEqualTo("READINESS-SCHEDULE");
        assertThat(existing.getProductionLine().isSelected()).isFalse();
        var mismatch = service.check("READINESS-OWNER", "READINESS-LINE", batchId,
                LocalDateTime.of(2030, 1, 1, 9, 0), LocalDateTime.of(2030, 1, 1, 10, 0), "READINESS-SCHEDULE");
        assertThat(mismatch.getRequestMode()).isEqualTo("EXISTING_SCHEDULE");
        assertThat(mismatch.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(mismatch.getMissingInputs()).contains("requestMode:mixed_schedule_and_explicit_inputs");
        var foreign = service.check("READINESS-OTHER", "READINESS-LINE", null, null, null);
        assertThat(foreign.getMissingInputs()).contains("productionLineId:tenant_mismatch_or_missing");
        assertThat(foreign.getProductionLine().isConfirmed()).isFalse();
        assertThat(counts()).isEqualTo(before);
        assertThat(snapshot()).isEqualTo(beforeRows);
        assertThat(stats.getEntityInsertCount()).isZero();
        assertThat(stats.getEntityUpdateCount()).isZero();
        assertThat(stats.getEntityDeleteCount()).isZero();
        verifyNoInteractions(restTemplate, featureEngineeringService);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[]{"production_lines", "production_batches", "scheduling_plans", "line_schedules", "scheduling_predictions"}) {
            counts.put(table, ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
        }
        return counts;
    }

    private Map<String, java.util.List<String>> snapshot() {
        Map<String, java.util.List<String>> rows = new LinkedHashMap<>();
        for (String table : new String[]{"production_lines", "production_batches", "scheduling_plans", "line_schedules", "scheduling_predictions"}) {
            var result = entityManager.createNativeQuery("SELECT * FROM " + table).getResultList();
            rows.put(table, result.stream().map(this::rowText).sorted().toList());
        }
        return rows;
    }

    private String rowText(Object row) {
        return row instanceof Object[] values ? java.util.Arrays.deepToString(values) : String.valueOf(row);
    }
}

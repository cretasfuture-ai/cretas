package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.impl.ProductProcessWorkflowServiceImpl;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.WorkflowBomSynchronizationService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Opt-in real PostgreSQL races for the merge's workflow/PO-header locks.
 * The test is deliberately isolated from shared databases by the URL guard.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import({MaterialAliasMergeService.class, MaterialMergeReferenceReplacer.class, MaterialReferenceWriteGuard.class,
        MaterialMergeWorkflowWriteGuard.class, ProcessSecretAccessService.class,
        ProcessSecretAuditRecorder.class, ProcessSecretRecipeGuard.class,
        MaterialMergeConcurrencyPostgresContractTest.JacksonTestConfiguration.class})
class MaterialMergeConcurrencyPostgresContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class JacksonTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final String SCHEMA = "artifact_merge_"
            + UUID.randomUUID().toString().replace("-", "");

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = java.sql.DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        String user = required("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (var connection = java.sql.DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        } catch (java.sql.SQLException alreadyCreated) {
            throw new IllegalStateException("cannot create isolated PostgreSQL schema", alreadyCreated);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RawMaterialTypeRepository materialRepository;
    @Autowired private ProductProcessWorkflowRepository workflowRepository;
    @Autowired private ProductProcessWorkflowRevisionRepository workflowRevisionRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private ProcessSecretPolicyRepository processSecretPolicyRepository;
    @Autowired private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private SupplierMaterialPurchaseSpecRepository supplierMaterialPurchaseSpecRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialMergeReferenceReplacer referenceReplacer;
    @Autowired private MaterialAliasMergeService mergeService;
    @Autowired private MaterialReferenceWriteGuard materialReferenceWriteGuard;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private UnitContractService unitContractService;
    @MockBean private PermissionService permissionService;
    @MockBean private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @SpyBean private ProcessSecretAuditRecorder processSecretAuditRecorder;
    private AuthenticatedActor.ActorScope actorScope;
    private volatile AuthenticatedActor activeActor;

    @BeforeEach
    void configureUnitContract() {
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString())).thenReturn(true);
        when(permissionService.hasPermission(any(User.class), org.mockito.ArgumentMatchers.eq("production:read_write")))
                .thenReturn(true);
    }

    @AfterEach
    void clearActor() {
        if (actorScope != null) actorScope.close();
        activeActor = null;
        reset(processSecretAuditRecorder);
    }

    @Test
    void publishedWorkflowWinsAndMergeLeavesEveryRecordUntouched() throws Exception {
        Fixture f = fixture(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch publishedLocked = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        try {
            Future<?> publisher = pool.submit(() -> transaction(() -> {
                workflowRepository.lockByIdAndFactoryId(f.workflowId, f.factoryFactory).orElseThrow();
                jdbc.update("UPDATE product_process_workflows SET status='PUBLISHED' WHERE id=?", f.workflowId);
                publishedLocked.countDown();
                await(releasePublish);
            }));
            assertThat(publishedLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> merge = pool.submit(() -> capture(() ->
                    transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()))));
            Thread.sleep(250L);
            assertThat(merge.isDone()).isFalse();
            releasePublish.countDown();
            publisher.get(10, TimeUnit.SECONDS);
            assertThat(merge.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("已发布工艺图"));

            assertThat(jdbc.queryForObject("SELECT status FROM product_process_workflows WHERE id=?", String.class,
                    f.workflowId)).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject("SELECT CAST(nodes_json AS text) FROM product_process_workflows WHERE id=?",
                    String.class, f.workflowId)).contains(f.duplicateId);
            assertThat(materialRepository.findById(f.duplicateId).orElseThrow().getMergedIntoId()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void receivingWinsAndMergeRejectsStuckOrderWithoutChangingReferences() throws Exception {
        Fixture f = fixture(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch receivingLocked = new CountDownLatch(1);
        CountDownLatch releaseReceiving = new CountDownLatch(1);
        try {
            Future<?> receiver = pool.submit(() -> transaction(() -> {
                purchaseOrderRepository.findByIdAndFactoryIdForUpdate(f.purchaseOrderId, f.factoryFactory)
                        .orElseThrow();
                jdbc.update("UPDATE purchase_order_items SET received_quantity=1 WHERE purchase_order_id=?",
                        f.purchaseOrderId);
                receivingLocked.countDown();
                await(releaseReceiving);
            }));
            assertThat(receivingLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> merge = pool.submit(() -> capture(() ->
                    transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()))));
            Thread.sleep(250L);
            assertThat(merge.isDone()).isFalse();
            releaseReceiving.countDown();
            receiver.get(10, TimeUnit.SECONDS);
            assertThat(merge.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("已开始收货"));
            assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                    String.class, f.purchaseOrderId)).isEqualTo(f.duplicateId);
            assertThat(materialRepository.findById(f.duplicateId).orElseThrow().getMergedIntoId()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void warehouseOnlyMergeIsRejectedBeforeAnyReferenceWrite() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        MergeSnapshot before = snapshot(f, f.workflowId);
        setActorRole(f, "warehouse_manager");

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId)).isEqualTo(before);
    }

    @Test
    void productionWriteWithoutOwnerEditIsRejectedBeforeAnyReferenceWrite() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        addPolicy(f, f.productTypeId, f.actorId + 1);
        MergeSnapshot before = snapshot(f, f.workflowId);

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId)).isEqualTo(before);
    }

    @Test
    void ordinaryOwnerStillCannotRewriteProtectedGraphOutput() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        String protectedOutput = "MM-SECRET-OUTPUT-" + UUID.randomUUID();
        addPolicy(f, f.productTypeId, f.actorId);
        addPolicy(f, protectedOutput, f.actorId + 1);
        setWorkflowNodes(f.workflowId, "[{\"data\":{\"skuId\":\"" + protectedOutput
                + "\"}},{\"text\":\"" + f.duplicateId + "\"}]");
        MergeSnapshot before = snapshot(f, f.workflowId);

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId)).isEqualTo(before);
    }

    @Test
    void validJsonWithUnparseableGraphFailsClosedAgainstAllProtectedProducts() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        addPolicy(f, "MM-SECRET-PARSE-" + UUID.randomUUID(), f.actorId + 1);
        // Valid jsonb object, but not the workflow-node list accepted by the graph parser;
        // retain the duplicate text so the merge plan still includes this draft.
        setWorkflowNodes(f.workflowId, "{\"text\":\"" + f.duplicateId + "\"}");
        MergeSnapshot before = snapshot(f, f.workflowId);

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId)).isEqualTo(before);
    }

    @Test
    void auditFailureRejectsAnOtherwiseAuthorizedMergeBeforeReferenceWrite() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        addPolicy(f, f.productTypeId, f.actorId);
        doThrow(new IllegalStateException("audit unavailable")).when(processSecretAuditRecorder).record(
                anyString(), anyString(), any(AuthenticatedActor.class),
                any(com.cretas.aims.entity.processsecret.ProcessSecretAction.class),
                anyString(), anyString(), anyString());
        MergeSnapshot before = snapshot(f, f.workflowId);

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId)).isEqualTo(before);
    }

    @Test
    void mixedDraftGraphsRejectTheWholeMergeWhenOneOutputIsProtected() {
        Fixture f = fixture(true);
        addReferenceSnapshotData(f);
        String protectedOutput = "MM-SECRET-MIXED-" + UUID.randomUUID();
        addPolicy(f, f.productTypeId, f.actorId);
        addPolicy(f, protectedOutput, f.actorId + 1);
        long secondWorkflow = insertDraftWorkflow(f, "[{\"data\":{\"skuId\":\""
                + protectedOutput + "\"}},{\"text\":\"" + f.duplicateId + "\"}]");
        MergeSnapshot before = snapshot(f, f.workflowId, secondWorkflow);

        assertMergeForbidden(f);
        assertThat(snapshot(f, f.workflowId, secondWorkflow)).isEqualTo(before);
    }

    @Test
    void mergeWinsAndAWaitingPublisherReadsTheMasterReference() throws Exception {
        Fixture f = fixture(true);
        CountDownLatch mergeApplied = new CountDownLatch(1);
        CountDownLatch releaseMerge = new CountDownLatch(1);
        // ApplicationEventPublisher is supplied by the ApplicationContext infrastructure, so
        // @MockBean does not replace the publisher injected into the replacer. Wire the
        // test-side publisher explicitly; the merge and repositories remain real.
        ReflectionTestUtils.setField(referenceReplacer, "eventPublisher", eventPublisher);
        doAnswer(invocation -> {
            mergeApplied.countDown();
            await(releaseMerge);
            return null;
        }).when(eventPublisher).publishEvent(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> merge = pool.submit(() -> capture(() ->
                    transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()))));
            assertThat(mergeApplied.await(10, TimeUnit.SECONDS))
                    .as("merge event was not intercepted; future=%s", futureState(merge))
                    .isTrue();

            Future<?> publisher = pool.submit(() -> transaction(() -> {
                workflowRepository.lockByIdAndFactoryId(f.workflowId, f.factoryFactory).orElseThrow();
                jdbc.update("UPDATE product_process_workflows SET status='PUBLISHED' WHERE id=?", f.workflowId);
            }));
            Thread.sleep(250L);
            assertThat(publisher.isDone()).isFalse();
            releaseMerge.countDown();
            assertThat(merge.get(10, TimeUnit.SECONDS)).isNull();
            publisher.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM product_process_workflows WHERE id=?", String.class,
                    f.workflowId)).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject("SELECT CAST(nodes_json AS text) FROM product_process_workflows WHERE id=?",
                    String.class, f.workflowId)).contains(f.masterId).doesNotContain(f.duplicateId);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void postgresSupplierTombstoneBlocksMergeWithoutTouchingDuplicate() {
        Fixture f = fixture(false);
        String supplier = "MMS-" + UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PG Supplier', TRUE, 1, 0, now(), now())",
                supplier, f.factoryFactory, supplier, supplier);
        // V20261028_90's whole-row identity is intentionally exercised: the
        // tombstone is for the master material, while the live row is for the
        // duplicate. Moving the live row to master would collide with it.
        jdbc.update("INSERT INTO supplier_materials "
                        + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, version, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 'CNY', 'kg', TRUE, FALSE, 0, now(), now(), now())",
                "MM-SM-TOMBSTONE-" + UUID.randomUUID(), f.factoryFactory, supplier, f.masterId);
        String duplicateRelation = "MM-SM-DUP-" + UUID.randomUUID();
        jdbc.update("INSERT INTO supplier_materials "
                        + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, version, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 'CNY', 'kg', TRUE, FALSE, 0, now(), now(), NULL)",
                duplicateRelation, f.factoryFactory, supplier, f.duplicateId);

        Throwable error = capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getMessage()).contains("供货记录已删除"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                f.duplicateId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?", String.class,
                f.duplicateId)).isNull();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM supplier_materials WHERE id=?", String.class,
                duplicateRelation)).isEqualTo(f.duplicateId);
        assertThat(jdbc.queryForObject("SELECT active FROM supplier_materials WHERE id=?", Boolean.class,
                duplicateRelation)).isTrue();
    }

    @Test
    void postgresSupplierWithoutTombstoneMovesRelation() {
        Fixture f = fixture(false);
        String supplier = "MMS-" + UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PG Positive Supplier', TRUE, 1, 0, now(), now())",
                supplier, f.factoryFactory, supplier, supplier);
        String relation = "MM-SM-POS-" + UUID.randomUUID();
        jdbc.update("INSERT INTO supplier_materials "
                        + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, version, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 'CNY', 'kg', TRUE, FALSE, 0, now(), now(), NULL)",
                relation, f.factoryFactory, supplier, f.duplicateId);

        assertThat(capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())))).isNull();
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                f.duplicateId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?", String.class,
                f.duplicateId)).isEqualTo(f.masterId);
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM supplier_materials WHERE id=?", String.class,
                relation)).isEqualTo(f.masterId);
    }

    @Test
    void openStandaloneReceiptBlocksMergeButConfirmedHistoryDoesNot() {
        Fixture f = fixture(false);
        String supplier = insertSupplier(f.factoryFactory, "PG Receipt Supplier");
        String receive = "MM-RCV-" + UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_receive_records "
                        + "(id, factory_id, receive_number, purchase_order_id, supplier_id, receive_date, status, "
                        + "received_by, total_amount, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, ?, CURRENT_DATE, 'DRAFT', 1, 0, now(), now(), NULL)",
                receive, f.factoryFactory, receive, supplier);
        jdbc.update("INSERT INTO purchase_receive_items "
                        + "(receive_record_id, material_type_id, material_name, received_quantity, unit, price_unit, "
                        + "created_at, updated_at, deleted_at) VALUES (?, ?, '重复物料', 1, 'kg', 'kg', now(), now(), NULL)",
                receive, f.duplicateId);

        Throwable blocked = capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())));
        assertThat(blocked).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getMessage()).contains("无采购单的收货单"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                f.duplicateId)).isTrue();

        jdbc.update("UPDATE purchase_receive_records SET status='CONFIRMED' WHERE id=?", receive);
        assertThat(capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())))).isNull();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_receive_items WHERE receive_record_id=?",
                String.class, receive)).isEqualTo(f.duplicateId);
    }

    @Test
    void supplierCollisionMapsOnlyEquivalentPurchaseSpecAndPreservesOrderIdentity() {
        Fixture f = fixture(false);
        String supplier = insertSupplier(f.factoryFactory, "PG Spec Supplier");
        String duplicateRelation = insertSupplierMaterial(f, supplier, f.duplicateId, "MM-SM-D-", true);
        String masterRelation = insertSupplierMaterial(f, supplier, f.masterId, "MM-SM-M-", true);
        String duplicateSpec = insertSpec(f, duplicateRelation, "D-SPEC", "10.00");
        String masterSpec = insertSpec(f, masterRelation, "M-SPEC", "10.00");
        String duplicateMaterialSpec = insertMaterialPackagingSpec(f, f.duplicateId, "D-MAT-SPEC", "1");
        String masterMaterialSpec = insertMaterialPackagingSpec(f, f.masterId, "M-MAT-SPEC", "1");
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, supplier_material_id, "
                        + "purchase_packaging_spec_id, material_packaging_spec_id, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, ?, ?, ?, now(), now())",
                po, f.duplicateId, duplicateRelation, duplicateSpec, duplicateMaterialSpec);

        assertThat(capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())))).isNull();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(f.masterId);
        assertThat(jdbc.queryForObject("SELECT supplier_material_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(masterRelation);
        assertThat(jdbc.queryForObject("SELECT purchase_packaging_spec_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(masterSpec);
        assertThat(jdbc.queryForObject("SELECT material_packaging_spec_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(masterMaterialSpec);
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM supplier_material_purchase_specs WHERE id=?",
                String.class, duplicateSpec)).isEqualTo(f.duplicateId);
    }

    @Test
    void supplierCollisionWithNoEquivalentSpecRollsBackEveryReference() {
        Fixture f = fixture(false);
        String supplier = insertSupplier(f.factoryFactory, "PG Spec Conflict Supplier");
        String duplicateRelation = insertSupplierMaterial(f, supplier, f.duplicateId, "MM-SM-D-", true);
        String masterRelation = insertSupplierMaterial(f, supplier, f.masterId, "MM-SM-M-", true);
        String duplicateSpec = insertSpec(f, duplicateRelation, "D-SPEC", "10.00");
        String masterSpec = insertSpec(f, masterRelation, "M-SPEC", "10.00");
        // Net quote matches, but the authoritative gross quote differs. This must
        // still reject mapping rather than treating the specs as equivalent.
        jdbc.update("UPDATE supplier_material_purchase_specs SET tax_included_quoted_price=11 WHERE id=?",
                masterSpec);
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, supplier_material_id, "
                        + "purchase_packaging_spec_id, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, ?, ?, now(), now())",
                po, f.duplicateId, duplicateRelation, duplicateSpec);

        Throwable error = capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getMessage()).contains("等价的采购包装规格"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                f.duplicateId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(f.duplicateId);
        assertThat(jdbc.queryForObject("SELECT active FROM supplier_materials WHERE id=?", Boolean.class,
                duplicateRelation)).isTrue();
    }

    @Test
    void inactiveMasterSupplierRelationIsReactivatedAndReceivesEquivalentSpec() {
        Fixture f = fixture(false);
        String supplier = insertSupplier(f.factoryFactory, "PG Inactive Master Supplier");
        String duplicateRelation = insertSupplierMaterial(f, supplier, f.duplicateId, "MM-SM-D-", true);
        String masterRelation = insertSupplierMaterial(f, supplier, f.masterId, "MM-SM-M-", false);
        String duplicateSpec = insertSpec(f, duplicateRelation, "D-SPEC", "10.00");
        String masterSpec = insertSpec(f, masterRelation, "M-SPEC", "10.00");
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, supplier_material_id, "
                        + "purchase_packaging_spec_id, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, ?, ?, now(), now())",
                po, f.duplicateId, duplicateRelation, duplicateSpec);

        assertThat(capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())))).isNull();
        assertThat(jdbc.queryForObject("SELECT active FROM supplier_materials WHERE id=?", Boolean.class,
                masterRelation)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM supplier_materials WHERE id=?", Boolean.class,
                duplicateRelation)).isFalse();
        assertThat(jdbc.queryForObject("SELECT supplier_material_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(masterRelation);
        assertThat(jdbc.queryForObject("SELECT purchase_packaging_spec_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(masterSpec);
    }

    @Test
    void materialPackagingSpecWithoutEquivalentRollsBackEveryReference() {
        Fixture f = fixture(false);
        String duplicateMaterialSpec = insertMaterialPackagingSpec(f, f.duplicateId, "D-MAT-SPEC", "1");
        insertMaterialPackagingSpec(f, f.masterId, "M-MAT-SPEC", "2");
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, material_packaging_spec_id, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, ?, now(), now())",
                po, f.duplicateId, duplicateMaterialSpec);

        Throwable error = capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getMessage()).contains("等价的原料包装规格"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                f.duplicateId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(f.duplicateId);
        assertThat(jdbc.queryForObject("SELECT material_packaging_spec_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(duplicateMaterialSpec);
    }

    @Test
    void realQuoteFrontdoorFirstThenMergeMovesTheSameReference() {
        Fixture f = fixture(false);
        String supplier = insertSupplier(f.factoryFactory, "PG Quote Supplier");
        String relation = insertSupplierMaterial(f, supplier, f.duplicateId, "MM-SM-Q-", true);
        String spec = insertSpec(f, relation, "Q-SPEC", "10.00");
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("UPDATE purchase_orders SET supplier_id=? WHERE id=?", supplier, po);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, now(), now())", po, f.duplicateId);

        PurchaseServiceImpl purchaseService = realPurchaseService();
        assertThat(capture(() -> transaction(() -> purchaseService.applyQuoteToDraftLine(f.factoryFactory, po,
                f.duplicateId, "kg", BigDecimal.ONE, supplier, new BigDecimal("10.00"), "kg", BigDecimal.ZERO,
                spec)))).isNull();
        assertThat(capture(() -> transaction(() ->
                mergeService.merge(f.factoryFactory, f.duplicateId, f.request())))).isNull();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(f.masterId);
        assertThat(jdbc.queryForObject("SELECT supplier_material_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(relation);
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM supplier_material_purchase_specs WHERE id=?",
                String.class, spec)).isEqualTo(f.masterId);
    }

    @Test
    void realQuoteFrontdoorAfterMergeRejectsDuplicateWithoutChangingDraft() {
        Fixture f = fixture(false);
        String po = insertOpenPurchaseOrder(f);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, now(), now())", po, f.duplicateId);
        transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()));

        PurchaseServiceImpl purchaseService = realPurchaseService();
        Throwable error = capture(() -> transaction(() -> purchaseService.applyQuoteToDraftLine(f.factoryFactory, po,
                f.duplicateId, "kg", BigDecimal.ONE, null, new BigDecimal("10.00"), "kg", BigDecimal.ZERO, null)));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, po)).isEqualTo(f.masterId);
    }

    @Test
    void mergeRejectsSupplierReferenceCommittedAfterInitialPlan() throws Exception {
        Fixture f = fixture(false);
        String supplier = "MMS-" + UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PG Race Supplier', TRUE, 1, 0, now(), now())",
                supplier, f.factoryFactory, supplier, supplier);
        String relation = "MM-SM-RACE-" + UUID.randomUUID();
        CountDownLatch rawLocked = new CountDownLatch(1);
        CountDownLatch releaseReference = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> transaction(() -> {
                materialReferenceWriteGuard.lockActive(f.factoryFactory, java.util.List.of(f.duplicateId));
                jdbc.update("INSERT INTO supplier_materials "
                                + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, version, created_at, updated_at, deleted_at) "
                                + "VALUES (?, ?, ?, ?, 'CNY', 'kg', TRUE, FALSE, 0, now(), now(), NULL)",
                        relation, f.factoryFactory, supplier, f.duplicateId);
                rawLocked.countDown();
                await(releaseReference);
            }));
            assertThat(rawLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> merge = pool.submit(() -> capture(() ->
                    transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()))));
            Thread.sleep(250L);
            assertThat(merge.isDone()).isFalse();
            releaseReference.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(merge.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_MERGE_CONFLICT"));
            assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                    f.duplicateId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT material_type_id FROM supplier_materials WHERE id=?", String.class,
                    relation)).isEqualTo(f.duplicateId);
        } finally {
            releaseReference.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void guardRejectsFreshInactiveStateWhenManagedEntityWasPreloaded() {
        Fixture f = fixture(false);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        transaction(() -> {
            // Simulate an earlier service read in the same persistence context.
            materialRepository.findById(f.duplicateId).orElseThrow();
            jdbc.update("UPDATE raw_material_types SET is_active=FALSE, merged_into_id=? WHERE id=?",
                    f.masterId, f.duplicateId);
            error.set(capture(() -> materialReferenceWriteGuard.lockActive(
                    f.factoryFactory, java.util.List.of(f.duplicateId))));
        });
        assertThat(error.get()).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
    }

    @Test
    void guardAcceptsFreshActiveStateWhenMaterialIsUnchanged() {
        Fixture f = fixture(false);
        transaction(() -> assertThat(materialReferenceWriteGuard.lockActive(
                f.factoryFactory, java.util.List.of(f.duplicateId))).hasSize(1));
    }

    @Test
    void realPurchaseCreateFrontdoorRejectsMergedDuplicateWithoutInsertingOrder() {
        Fixture f = fixture(false);
        transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()));
        PurchaseServiceImpl purchaseService = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                mock(com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository.class),
                supplierRepository,
                materialRepository,
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeItemRepository.class),
                mock(ArApService.class),
                eventPublisher,
                mock(MaterialBatchService.class),
                materialReferenceWriteGuard);
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item =
                new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(f.duplicateId);
        item.setMaterialName("旧重复料");
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("kg");
        item.setPriceUnit("kg");
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setPurchaseType("DIRECT");
        request.setOrderDate(LocalDate.now());
        request.setItems(java.util.List.of(item));
        Long before = jdbc.queryForObject("SELECT count(*) FROM purchase_orders WHERE factory_id=?", Long.class,
                f.factoryFactory);

        Throwable error = capture(() -> transaction(() ->
                purchaseService.createPurchaseOrder(f.factoryFactory, request, 1L)));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_orders WHERE factory_id=?", Long.class,
                f.factoryFactory)).isEqualTo(before);
    }

    @Test
    void realPurchaseCopyFrontdoorRejectsHistoricDuplicateWithoutInsertingOrder() {
        Fixture f = fixture(false);
        transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()));
        String sourceId = "MM-COPY-SOURCE-" + UUID.randomUUID();
        String historicalOrderNumber = "PO-HIST-" + f.factoryFactory.substring(f.factoryFactory.length() - 12);
        jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                        + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, 'COMPLETED', 1, 0, 'NOT_RECEIVED', 'UNCREATED', now(), now())",
                sourceId, f.factoryFactory, historicalOrderNumber);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, '历史重复物料', 1, 'kg', 'kg', 1, 0, now(), now())",
                sourceId, f.duplicateId);
        PurchaseServiceImpl purchaseService = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                mock(com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository.class),
                supplierRepository,
                materialRepository,
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeItemRepository.class),
                mock(ArApService.class),
                eventPublisher,
                mock(MaterialBatchService.class),
                materialReferenceWriteGuard);
        Long before = jdbc.queryForObject("SELECT count(*) FROM purchase_orders WHERE factory_id=?", Long.class,
                f.factoryFactory);

        Throwable error = capture(() -> transaction(() ->
                purchaseService.copyPurchaseOrder(f.factoryFactory, sourceId, 1L)));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_orders WHERE factory_id=?", Long.class,
                f.factoryFactory)).isEqualTo(before);
    }

    @Test
    void realWorkflowSaveFrontdoorRejectsMergedDuplicateDefinition() {
        Fixture f = fixture(false);
        transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()));
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        ProductType owner = new ProductType();
        owner.setId(f.productTypeId);
        owner.setFactoryId(f.factoryFactory);
        owner.setProductCategory("FINISHED_PRODUCT");
        when(productTypes.findByIdAndFactoryId(f.productTypeId, f.factoryFactory))
                .thenReturn(java.util.Optional.of(owner));
        when(productTypes.findByIdAndFactoryIdForUpdate(f.productTypeId, f.factoryFactory))
                .thenReturn(java.util.Optional.of(owner));
        ProductProcessWorkflowUnitValidator unitValidator = mock(ProductProcessWorkflowUnitValidator.class);
        when(unitValidator.validate(anyString(), any(ProductProcessWorkflowDTO.class)))
                .thenReturn(new com.cretas.aims.dto.workflow.WorkflowUnitValidationResult(
                        java.util.List.of(), java.util.List.of()));
        ProductProcessWorkflowServiceImpl workflowService = new ProductProcessWorkflowServiceImpl(
                workflowRepository,
                workflowRevisionRepository,
                mock(WorkflowRevisionSnapshotService.class),
                mock(BomWorkflowRevisionService.class),
                mock(WorkflowBomSynchronizationService.class),
                mock(ProductProcessWorkflowActivationService.class),
                mock(com.cretas.aims.repository.ProductProcessWorkflowActivationRepository.class),
                mock(ProductionPlanService.class),
                new ObjectMapper(),
                new ProductProcessWorkflowValidator(),
                mock(ProductProcessWorkflowCatalogValidator.class),
                unitValidator,
                productTypes,
                materialRepository,
                materialReferenceWriteGuard);
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setId(f.workflowId);
        definition.setFactoryId(f.factoryFactory);
        definition.setProductTypeId(f.productTypeId);
        definition.setSchemaVersion(1);
        definition.setVersion(1);
        definition.setLockVersion(0L);
        ProductProcessWorkflowDTO.Node raw = new ProductProcessWorkflowDTO.Node();
        raw.setId("raw-old");
        raw.setKind("RAW_MATERIAL");
        raw.setPosition(new ProductProcessWorkflowDTO.Position(0D, 0D));
        raw.setData(new java.util.LinkedHashMap<>(java.util.Map.of(
                "skuId", f.duplicateId, "name", "旧重复料")));
        definition.setNodes(java.util.List.of(raw));
        definition.setEdges(java.util.List.of());
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));

        Throwable error = capture(() -> transaction(() ->
                workflowService.saveDraft(f.factoryFactory, f.productTypeId, definition)));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT CAST(nodes_json AS text) FROM product_process_workflows WHERE id=?",
                String.class, f.workflowId)).contains(f.masterId).doesNotContain(f.duplicateId);
    }

    @Test
    void rawOwnerSaveWaitsForMergeAndThenRejectsWithoutDeadlock() throws Exception {
        Fixture f = fixture(true);
        String rawOwnerId = f.duplicateId;
        jdbc.update("UPDATE product_process_workflows SET product_type_id=? WHERE id=?",
                rawOwnerId, f.workflowId);

        ProductProcessWorkflowUnitValidator unitValidator = mock(ProductProcessWorkflowUnitValidator.class);
        when(unitValidator.validate(anyString(), any(ProductProcessWorkflowDTO.class)))
                .thenReturn(new com.cretas.aims.dto.workflow.WorkflowUnitValidationResult(
                        java.util.List.of(), java.util.List.of()));
        ProductProcessWorkflowServiceImpl workflowService = new ProductProcessWorkflowServiceImpl(
                workflowRepository,
                workflowRevisionRepository,
                mock(WorkflowRevisionSnapshotService.class),
                mock(BomWorkflowRevisionService.class),
                mock(WorkflowBomSynchronizationService.class),
                mock(ProductProcessWorkflowActivationService.class),
                mock(com.cretas.aims.repository.ProductProcessWorkflowActivationRepository.class),
                mock(ProductionPlanService.class),
                new ObjectMapper(),
                new ProductProcessWorkflowValidator(),
                mock(ProductProcessWorkflowCatalogValidator.class),
                unitValidator,
                productTypeRepository,
                materialRepository,
                materialReferenceWriteGuard);
        ProductProcessWorkflowDTO definition = oldDuplicateDefinition(f, rawOwnerId);

        CountDownLatch mergeApplied = new CountDownLatch(1);
        CountDownLatch releaseMerge = new CountDownLatch(1);
        ReflectionTestUtils.setField(referenceReplacer, "eventPublisher", eventPublisher);
        doAnswer(invocation -> {
            mergeApplied.countDown();
            await(releaseMerge);
            return null;
        }).when(eventPublisher).publishEvent(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> merge = pool.submit(() -> capture(() ->
                    transaction(() -> mergeService.merge(f.factoryFactory, f.duplicateId, f.request()))));
            assertThat(mergeApplied.await(10, TimeUnit.SECONDS))
                    .as("merge event was not intercepted; future=%s", futureState(merge))
                    .isTrue();

            Future<Throwable> save = pool.submit(() -> capture(() -> transaction(() ->
                    workflowService.saveDraft(f.factoryFactory, rawOwnerId, definition))));
            Thread.sleep(250L);
            assertThat(save.isDone()).isFalse();
            releaseMerge.countDown();
            assertThat(merge.get(10, TimeUnit.SECONDS)).isNull();
            Throwable saveError = save.get(10, TimeUnit.SECONDS);
            assertThat(saveError).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            ApiResponse<?> conflict = new GlobalExceptionHandler()
                    .handleOptimisticLockException((Exception) saveError);
            assertThat(conflict.getCode()).isEqualTo(409);
            assertThat(conflict.getSuccess()).isFalse();
            assertThat(conflict.getErrorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
            assertThat(conflict.getActionHint()).contains("刷新");
            assertThat(materialRepository.findById(f.duplicateId).orElseThrow().getMergedIntoId())
                    .isEqualTo(f.masterId);
            assertThat(jdbc.queryForObject("SELECT CAST(nodes_json AS text) FROM product_process_workflows WHERE id=?",
                    String.class, f.workflowId)).contains(f.masterId).doesNotContain(f.duplicateId);
        } finally {
            releaseMerge.countDown();
            pool.shutdownNow();
        }
    }

    private ProductProcessWorkflowDTO oldDuplicateDefinition(Fixture f, String ownerId) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setId(f.workflowId);
        definition.setFactoryId(f.factoryFactory);
        definition.setProductTypeId(ownerId);
        definition.setSchemaVersion(1);
        definition.setVersion(1);
        definition.setLockVersion(0L);
        ProductProcessWorkflowDTO.Node raw = new ProductProcessWorkflowDTO.Node();
        raw.setId("raw-old");
        raw.setKind("RAW_MATERIAL");
        raw.setPosition(new ProductProcessWorkflowDTO.Position(0D, 0D));
        raw.setData(new java.util.LinkedHashMap<>(java.util.Map.of(
                "skuId", f.duplicateId, "name", "旧重复料")));
        definition.setNodes(java.util.List.of(raw));
        definition.setEdges(java.util.List.of());
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return definition;
    }

    private Fixture fixture(boolean withPurchaseOrder) {
        String factory = "MM-PG-" + UUID.randomUUID();
        String master = "MM-MASTER-" + UUID.randomUUID();
        String duplicate = "MM-DUP-" + UUID.randomUUID();
        String factoryName = "Merge PG Factory " + factory.substring(factory.length() - 12);
        jdbc.update("INSERT INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, "
                        + "created_at, updated_at) VALUES (?, ?, 'FACTORY', 0, TRUE, FALSE, 20, now(), now())",
                factory, factoryName);
        long actorId = 100000L + Math.abs((long) factory.hashCode());
        String username = "merge-pg-" + factory.substring(factory.length() - 12);
        jdbc.update("INSERT INTO users (id, factory_id, username, password_hash, is_active, role_code, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'test', TRUE, 'factory_super_admin', now(), now())",
                actorId, factory, username);
        if (actorScope != null) actorScope.close();
        activeActor = new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, actorId, factory, "factory_super_admin");
        actorScope = AuthenticatedActor.trustedAsyncScope(activeActor);
        material(master, factory, "MASTER", "主物料");
        material(duplicate, factory, "DUP", "重复物料");
        String productType = "MM-PRODUCT-" + UUID.randomUUID();
        long workflow = jdbc.queryForObject("INSERT INTO product_process_workflows "
                        + "(factory_id, product_type_id, schema_version, status, definition_version, nodes_json, "
                        + "edges_json, viewport_json, unit_review_required, lock_version, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 'DRAFT', 1, ?::jsonb, '[]'::jsonb, '{}'::jsonb, FALSE, 0, now(), now()) "
                        + "RETURNING id",
                Long.class, factory, productType,
                "[{\"data\":{\"skuId\":\"" + duplicate + "\",\"name\":\"重复物料\"}}]");
        String po = null;
        if (withPurchaseOrder) {
            po = "MM-PO-" + UUID.randomUUID();
            jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                            + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, 'APPROVED', ?, 0, 'NOT_RECEIVED', 'UNCREATED', now(), now())",
                    po, factory, po, actorId);
            jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                            + "price_unit, quantity_to_price_factor, received_quantity, created_at, updated_at) "
                            + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, now(), now())", po, duplicate);
        }
        return new Fixture(factory, master, duplicate, productType, workflow, po, actorId);
    }

    private void material(String id, String factory, String code, String name) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factory);
        material.setCode(code);
        material.setName(name);
        material.setCategory("原料");
        material.setMainCategory("原料");
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setIsActive(true);
        materialRepository.saveAndFlush(material);
    }

    private String insertSupplier(String factory, String name) {
        String id = "MMS-" + UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE, 1, 0, now(), now())",
                id, factory, id, id, name);
        return id;
    }

    private String insertSupplierMaterial(Fixture f, String supplier, String materialId,
                                          String prefix, boolean active) {
        String id = prefix + UUID.randomUUID();
        jdbc.update("INSERT INTO supplier_materials "
                        + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, "
                        + "version, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, 'CNY', 'kg', ?, FALSE, 0, now(), now(), NULL)",
                id, f.factoryFactory, supplier, materialId, active);
        return id;
    }

    private String insertSpec(Fixture f, String supplierMaterialId, String name, String price) {
        String id = "MM-SPEC-" + UUID.randomUUID();
        jdbc.update("INSERT INTO supplier_material_purchase_specs "
                        + "(id, factory_id, supplier_material_id, material_type_id, name, purchase_package_unit, "
                        + "inventory_base_unit, conversion_factor, quoted_price, tax_included_quoted_price, currency, "
                        + "is_default, active, version, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'kg', 'kg', 1, ?, ?, 'CNY', FALSE, TRUE, 0, now(), now(), NULL)",
                id, f.factoryFactory, supplierMaterialId,
                supplierMaterialId.startsWith("MM-SM-M-") ? f.masterId : f.duplicateId,
                name, new BigDecimal(price), new BigDecimal(price));
        return id;
    }

    private String insertMaterialPackagingSpec(Fixture f, String materialId, String name, String factor) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO material_packaging_specs "
                        + "(id, factory_id, material_type_id, name, package_unit, base_unit, conversion_factor, "
                        + "is_default, is_active, sort_order, version, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 'kg', 'kg', ?, FALSE, TRUE, 0, 0, now(), now(), NULL)",
                id, f.factoryFactory, materialId, name, new BigDecimal(factor));
        return id;
    }

    private String insertOpenPurchaseOrder(Fixture f) {
        String po = "MM-PO-SPEC-" + UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                        + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, 'DRAFT', 1, 0, 'NOT_RECEIVED', 'UNCREATED', now(), now())",
                po, f.factoryFactory, po);
        return po;
    }

    private PurchaseServiceImpl realPurchaseService() {
        PurchaseServiceImpl purchaseService = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                mock(com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository.class),
                supplierRepository,
                materialRepository,
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeItemRepository.class),
                mock(ArApService.class),
                eventPublisher,
                mock(MaterialBatchService.class),
                materialReferenceWriteGuard);
        // Wire real relation/spec repositories for the contract path under test.
        ReflectionTestUtils.setField(purchaseService, "supplierMaterialRepository", supplierMaterialRepository);
        ReflectionTestUtils.setField(purchaseService, "supplierMaterialPurchaseSpecRepository",
                supplierMaterialPurchaseSpecRepository);
        return purchaseService;
    }

    private void assertMergeForbidden(Fixture fixture) {
        Throwable error = capture(() -> transaction(() ->
                mergeService.merge(fixture.factoryFactory, fixture.duplicateId, fixture.request())));
        assertThat(error).isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(403));
    }

    private void setActorRole(Fixture fixture, String role) {
        transaction(() -> jdbc.update("UPDATE users SET role_code=? WHERE id=?", role, fixture.actorId));
        if (actorScope != null) actorScope.close();
        activeActor = new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, fixture.actorId, fixture.factoryFactory, role);
        actorScope = AuthenticatedActor.trustedAsyncScope(activeActor);
    }

    private void addPolicy(Fixture fixture, String productTypeId, long ownerId) {
        transaction(() -> processSecretPolicyRepository.saveAndFlush(ProcessSecretPolicy.builder()
                .factoryId(fixture.factoryFactory)
                .productTypeId(productTypeId)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED)
                .ownerUserId(ownerId)
                .createdBy(fixture.actorId)
                .build()));
    }

    private void addReferenceSnapshotData(Fixture fixture) {
        String supplier = "MM-AUTH-SUP-" + UUID.randomUUID();
        String relation = "MM-AUTH-SM-" + UUID.randomUUID();
        transaction(() -> {
            jdbc.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, "
                            + "version, created_at, updated_at) VALUES (?, ?, ?, ?, 'Auth Snapshot Supplier', TRUE, ?, 0, now(), now())",
                    supplier, fixture.factoryFactory, supplier, supplier, fixture.actorId);
            jdbc.update("INSERT INTO supplier_materials "
                            + "(id, factory_id, supplier_id, material_type_id, currency, purchase_unit, active, preferred, "
                            + "version, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, 'CNY', 'kg', TRUE, FALSE, 0, now(), now(), NULL)",
                    relation, fixture.factoryFactory, supplier, fixture.duplicateId);
            jdbc.update("INSERT INTO raw_material_type_aliases "
                            + "(id, factory_id, material_type_id, alias, normalized_alias, source, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, now())",
                    "MM-AUTH-ALIAS-" + UUID.randomUUID(), fixture.factoryFactory, fixture.duplicateId,
                    "历史别名-" + fixture.duplicateId.substring(fixture.duplicateId.length() - 8),
                    "历史别名" + fixture.duplicateId.substring(fixture.duplicateId.length() - 8),
                    String.valueOf(fixture.actorId));
        });
    }

    private void setWorkflowNodes(long workflowId, String nodesJson) {
        transaction(() -> jdbc.update("UPDATE product_process_workflows SET nodes_json=?::jsonb WHERE id=?",
                nodesJson, workflowId));
    }

    private long insertDraftWorkflow(Fixture fixture, String nodesJson) {
        return transactionReturning(() -> jdbc.queryForObject("INSERT INTO product_process_workflows "
                        + "(factory_id, product_type_id, schema_version, status, definition_version, nodes_json, "
                        + "edges_json, viewport_json, unit_review_required, lock_version, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 'DRAFT', 1, ?::jsonb, '[]'::jsonb, '{}'::jsonb, FALSE, 0, now(), now()) "
                        + "RETURNING id", Long.class, fixture.factoryFactory, fixture.productTypeId, nodesJson));
    }

    private MergeSnapshot snapshot(Fixture fixture, long... workflowIds) {
        Map<Long, String> nodes = new java.util.LinkedHashMap<>();
        for (long workflowId : workflowIds) {
            nodes.put(workflowId, jdbc.queryForObject(
                    "SELECT CAST(nodes_json AS text) FROM product_process_workflows WHERE id=?",
                    String.class, workflowId));
        }
        String purchaseMaterial = fixture.purchaseOrderId == null ? null : jdbc.queryForObject(
                "SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, fixture.purchaseOrderId);
        return new MergeSnapshot(
                jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?", Boolean.class,
                        fixture.duplicateId),
                jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?", String.class,
                        fixture.duplicateId),
                nodes,
                purchaseMaterial,
                jdbc.queryForObject("SELECT COUNT(*) FROM supplier_materials WHERE factory_id=?", Integer.class,
                        fixture.factoryFactory),
                jdbc.queryForObject("SELECT COUNT(*) FROM raw_material_type_aliases WHERE factory_id=?",
                        Integer.class, fixture.factoryFactory));
    }

    private long transactionReturning(java.util.function.Supplier<Long> action) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(activeActor);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            return new TransactionTemplate(transactionManager).execute(status -> action.get());
        }
    }

    private record MergeSnapshot(Boolean duplicateActive, String mergedIntoId,
                                 Map<Long, String> workflowNodes, String purchaseMaterial,
                                 Integer supplierRows, Integer aliasRows) { }

    private void transaction(Runnable action) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(activeActor);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
        }
    }

    private static Throwable capture(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private static String futureState(Future<?> future) {
        if (!future.isDone()) {
            return "still running";
        }
        try {
            return "completed=" + future.get(0, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return "failed=" + cause;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    private record Fixture(String factoryFactory, String masterId, String duplicateId,
                           String productTypeId, long workflowId, String purchaseOrderId, long actorId) {
        private MaterialMergeDTO.MergeRequest request() {
            return new MaterialMergeDTO.MergeRequest(masterId, "PG concurrency");
        }
    }
}

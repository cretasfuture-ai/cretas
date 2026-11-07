package com.cretas.aims.service.material;

import com.cretas.aims.dto.purchase.CreateInquiryQuoteRequest;
import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.purchase.InquiryQuote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteSupplierPriceRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.purchase.impl.InquiryQuoteServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.entity.User;
import com.cretas.aims.service.config.impl.FactoryConfigServiceImpl;
import com.cretas.aims.service.impl.UserModuleAccessServiceImpl;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Real PostgreSQL contract for the inquiry material-reference front door.
 *
 * <p>This intentionally has its own disposable schema and calls the real
 * inquiry service and merge service.  The shared material-merge contract tests
 * cover the guard in isolation; this class proves that the inquiry transaction
 * cannot persist a stale reference or a second canonical duplicate.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import({MaterialAliasMergeService.class, MaterialMergeReferenceReplacer.class,
        MaterialReferenceWriteGuard.class, MaterialMergeWorkflowWriteGuard.class,
        ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretRecipeGuard.class, FactoryConfigServiceImpl.class,
        UserModuleAccessServiceImpl.class, UnitContractServiceImpl.class,
        InquiryQuoteMaterialReferencePostgresContractTest.JacksonTestConfiguration.class})
class InquiryQuoteMaterialReferencePostgresContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class JacksonTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final String SCHEMA = "artifact_inquiry_"
            + UUID.randomUUID().toString().replace("-", "");

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"),
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
        try (var connection = DriverManager.getConnection(url, user, password);
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
    @Autowired private EntityManager entityManager;
    @Autowired private RawMaterialTypeRepository materialRepository;
    @Autowired private InquiryQuoteRepository inquiryQuoteRepository;
    @SpyBean private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private MaterialAliasMergeService mergeService;
    @Autowired private MaterialMergeReferenceReplacer referenceReplacer;
    @Autowired private MaterialReferenceWriteGuard materialReferenceWriteGuard;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UnitContractService unitContractService;

    @MockBean private PermissionService permissionService;
    @MockBean private DDLExecutor ddlExecutor;
    @MockBean private DynamicFieldService dynamicFieldService;
    @MockBean private ApplicationEventPublisher eventPublisher;
    @SpyBean private ProcessSecretAuditRecorder processSecretAuditRecorder;
    private AuthenticatedActor.ActorScope actorScope;
    private volatile AuthenticatedActor activeActor;

    @BeforeEach
    void configureGuards() {
        when(permissionService.hasPermission(any(User.class), org.mockito.ArgumentMatchers.eq("production:read_write")))
                .thenReturn(true);
        when(permissionService.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
        when(dynamicFieldService.getActiveFields(anyString(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void clearActor() {
        if (actorScope != null) {
            actorScope.close();
        }
        activeActor = null;
        reset(processSecretAuditRecorder);
        reset(purchaseOrderRepository);
    }

    @Test
    void activeMaterialCreatePersistsCanonicalDraft() {
        Fixture f = fixture();

        InquiryQuote quote = transactionReturning(() -> inquiryService().create(
                f.factoryId, request("  " + f.duplicateId + "  "), f.actorId));

        assertThat(quote.getStatus()).isEqualTo(InquiryQuoteStatus.DRAFT);
        assertThat(quote.getMaterialTypeId()).isEqualTo(f.duplicateId);
        assertThat(inquiryQuoteRepository.findById(quote.getId())).get()
                .extracting(InquiryQuote::getMaterialTypeId)
                .isEqualTo(f.duplicateId);
        assertThat(countQuotes(f)).isEqualTo(1L);
    }

    @Test
    void inactiveMergedAndForeignMaterialsRejectBeforeInsert() {
        Fixture inactive = fixture();
        jdbc.update("UPDATE raw_material_types SET is_active=FALSE WHERE id=?", inactive.duplicateId);
        assertCreateRejectedWithoutInsert(inactive, inactive.duplicateId);

        Fixture merged = fixture();
        transaction(() -> mergeService.merge(merged.factoryId, merged.duplicateId, merged.request()));
        assertCreateRejectedWithoutInsert(merged, merged.duplicateId);

        Fixture foreign = fixture();
        assertCreateRejectedWithoutInsert(foreign, foreign.foreignMaterialId);
    }

    @Test
    void mergeFirstQuoteWaitsForMergeCommitThenRejectsWithoutHalfQuote() throws Exception {
        Fixture f = fixtureWithOpenPurchaseOrder();
        CountDownLatch materialLockHeld = new CountDownLatch(1);
        CountDownLatch mergeHeaderLocked = new CountDownLatch(1);
        CountDownLatch quoteReachedHeaderLock = new CountDownLatch(1);
        CountDownLatch releaseMaterialLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            String orderId = invocation.getArgument(0);
            String factoryId = invocation.getArgument(1);
            boolean quoteThread = Thread.currentThread().getName().equals("inquiry-quote-header");
            if (quoteThread) {
                quoteReachedHeaderLock.countDown();
            }
            PurchaseOrder order = entityManager.find(PurchaseOrder.class, orderId,
                    LockModeType.PESSIMISTIC_WRITE);
            if (Thread.currentThread().getName().equals("material-merge")) {
                mergeHeaderLocked.countDown();
            }
            return order != null && factoryId.equals(order.getFactoryId())
                    ? Optional.of(order) : Optional.empty();
        }).when(purchaseOrderRepository).findByIdAndFactoryIdForUpdate(anyString(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<?> materialHolder = pool.submit(() -> transaction(() -> {
            materialRepository.lockByFactoryIdAndIdIn(f.factoryId, List.of(f.duplicateId, f.masterId));
            materialLockHeld.countDown();
            await(releaseMaterialLock);
        }));
        try {
            assertThat(materialLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> merge = pool.submit(() -> {
                Thread.currentThread().setName("material-merge");
                return capture(() -> transaction(() -> mergeService.merge(
                        f.factoryId, f.duplicateId, f.request())));
            });
            assertThat(mergeHeaderLocked.await(10, TimeUnit.SECONDS)).isTrue();

            CreateInquiryQuoteRequest quoteRequest = request(f.duplicateId);
            quoteRequest.setSourcePurchaseOrderId(f.purchaseOrderId);
            Future<Throwable> quote = pool.submit(() -> {
                Thread.currentThread().setName("inquiry-quote-header");
                return capture(() -> transaction(() -> inquiryService().create(
                        f.factoryId, quoteRequest, f.actorId)));
            });
            assertThat(quoteReachedHeaderLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(quote.isDone()).as("quote must wait on merge's material lock").isFalse();
            releaseMaterialLock.countDown();

            assertThat(merge.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(quote.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
            assertThat(countQuotes(f)).isZero();
            assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?",
                    Boolean.class, f.duplicateId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?",
                    String.class, f.duplicateId)).isEqualTo(f.masterId);
        } finally {
            releaseMaterialLock.countDown();
            pool.shutdownNow();
            materialHolder.cancel(true);
        }
    }

    @Test
    void quoteFirstThenMergePreservesHistoricalQuoteAndMigratesPurchaseReference() {
        Fixture f = fixtureWithOpenPurchaseOrder();
        CreateInquiryQuoteRequest quoteRequest = request(f.duplicateId);
        quoteRequest.setSourcePurchaseOrderId(f.purchaseOrderId);
        InquiryQuote quote = transactionReturning(() -> inquiryService().create(
                f.factoryId, quoteRequest, f.actorId));

        Throwable blocked = capture(() -> transaction(() ->
                mergeService.merge(f.factoryId, f.duplicateId, f.request())));
        assertThat(blocked).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_MERGE_BLOCKED"));
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?",
                Boolean.class, f.duplicateId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?",
                String.class, f.duplicateId)).isNull();
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, f.purchaseOrderId)).isEqualTo(f.duplicateId);
        assertThat(inquiryQuoteRepository.findById(quote.getId())).get()
                .extracting(InquiryQuote::getStatus)
                .isEqualTo(InquiryQuoteStatus.DRAFT);

        transactionReturning(() -> inquiryService().cancel(f.factoryId, quote.getId()));
        transaction(() -> mergeService.merge(f.factoryId, f.duplicateId, f.request()));

        assertThat(inquiryQuoteRepository.findById(quote.getId())).get()
                .satisfies(saved -> {
                    assertThat(saved.getMaterialTypeId()).isEqualTo(f.duplicateId);
                    assertThat(saved.getStatus()).isEqualTo(InquiryQuoteStatus.CANCELLED);
                });
        assertThat(jdbc.queryForObject("SELECT material_type_id FROM purchase_order_items WHERE purchase_order_id=?",
                String.class, f.purchaseOrderId)).isEqualTo(f.masterId);
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?",
                Boolean.class, f.duplicateId)).isFalse();
    }

    @Test
    void sameTransactionUnflushedQuoteBlocksMergeAfterNativePlanAutoFlush() {
        Fixture f = fixture();
        AtomicReference<InquiryQuote> quoteRef = new AtomicReference<>();

        Throwable blocked = capture(() -> transaction(() -> {
            quoteRef.set(inquiryService().create(f.factoryId, request(f.duplicateId), f.actorId));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inquiry_quotes WHERE factory_id=? AND material_type_id=?",
                    Long.class, f.factoryId, f.duplicateId)).isZero();
            mergeService.merge(f.factoryId, f.duplicateId, f.request());
        }));

        assertThat(blocked).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_MERGE_BLOCKED"));
        assertThat(quoteRef.get()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inquiry_quotes WHERE factory_id=?",
                Long.class, f.factoryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_active FROM raw_material_types WHERE id=?",
                Boolean.class, f.duplicateId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM raw_material_types WHERE id=?",
                String.class, f.duplicateId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_material_type_aliases "
                        + "WHERE factory_id=?",
                Long.class, f.factoryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE material_type_id=?",
                Long.class, f.duplicateId)).isZero();
    }

    @Test
    void canonicalConcurrentCreatesProduceAtMostOneCommittedQuote() throws Exception {
        Fixture f = fixture();
        InquiryQuoteServiceImpl service = inquiryService();
        CreateInquiryQuoteRequest first = request("  " + f.duplicateId + "  ");
        CreateInquiryQuoteRequest second = request(f.duplicateId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = pool.submit(() -> concurrentCreate(service, f, first, ready, start));
            Future<Throwable> b = pool.submit(() -> concurrentCreate(service, f, second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstError = a.get(15, TimeUnit.SECONDS);
            Throwable secondError = b.get(15, TimeUnit.SECONDS);
            List<Throwable> errors = java.util.Arrays.asList(firstError, secondError);
            assertThat(errors.stream().filter(java.util.Objects::isNull)).hasSize(1);
            assertThat(errors.stream().filter(e -> e instanceof BusinessException)
                    .map(e -> ((BusinessException) e).getCode()).toList()).containsExactly(409);
            assertThat(countQuotes(f)).isEqualTo(1L);
            List<InquiryQuote> saved = inquiryQuoteRepository.findByFactoryIdOrderByCreatedAtDesc(
                    f.factoryId, org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getMaterialTypeId()).isEqualTo(f.duplicateId);
            assertThat(saved.get(0).getStatus()).isEqualTo(InquiryQuoteStatus.DRAFT);
        } finally {
            pool.shutdownNow();
        }
    }

    private Throwable concurrentCreate(InquiryQuoteServiceImpl service, Fixture f,
                                       CreateInquiryQuoteRequest request,
                                       CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return capture(() -> transaction(() -> service.create(f.factoryId, request, f.actorId)));
    }

    private void assertCreateRejectedWithoutInsert(Fixture f, String materialId) {
        long before = countQuotes(f);
        Throwable error = capture(() -> transaction(() ->
                inquiryService().create(f.factoryId, request(materialId), f.actorId)));
        assertThat(error).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(countQuotes(f)).isEqualTo(before);
    }

    private InquiryQuoteServiceImpl inquiryService() {
        PurchaseService purchaseService = mock(PurchaseService.class);
        when(purchaseService.getPurchaseOrderById(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String factoryId = invocation.getArgument(0);
                    PurchaseOrder order = entityManager.find(PurchaseOrder.class, invocation.getArgument(1));
                    if (order == null || !factoryId.equals(order.getFactoryId())) {
                        throw new IllegalStateException("purchase order not found");
                    }
                    return order;
                });
        return new InquiryQuoteServiceImpl(
                inquiryQuoteRepository,
                mock(InquiryQuoteSupplierPriceRepository.class),
                mock(SupplierRepository.class),
                purchaseOrderRepository,
                purchaseService,
                materialRepository,
                mock(SupplierMaterialRepository.class),
                mock(SupplierMaterialPurchaseSpecRepository.class),
                mock(BomRecipeItemRepository.class),
                mock(BomRecipeRepository.class),
                unitContractService,
                materialReferenceWriteGuard);
    }

    private CreateInquiryQuoteRequest request(String materialId) {
        CreateInquiryQuoteRequest request = new CreateInquiryQuoteRequest();
        request.setMaterialTypeId(materialId);
        request.setMaterialName("重复物料");
        request.setQuantity(BigDecimal.ONE);
        request.setUnit("kg");
        request.setInquiryDate(LocalDate.now());
        request.setRemark("PG inquiry material guard");
        return request;
    }

    private long countQuotes(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry_quotes WHERE factory_id=?", Long.class,
                f.factoryId);
    }

    private Fixture fixture() {
        String factory = "IQ-PG-" + UUID.randomUUID();
        String foreignFactory = "IQ-FOREIGN-" + UUID.randomUUID();
        String master = "IQ-MASTER-" + UUID.randomUUID();
        String duplicate = "IQ-DUP-" + UUID.randomUUID();
        String foreignMaterial = "IQ-FOREIGN-MAT-" + UUID.randomUUID();
        long actorId = 100000L + Math.abs((long) factory.hashCode());
        insertFactoryAndUser(factory, actorId);
        insertFactoryAndUser(foreignFactory, actorId + 1);
        material(master, factory, "MASTER", "主物料");
        material(duplicate, factory, "DUP", "重复物料");
        material(foreignMaterial, foreignFactory, "FOREIGN", "外厂物料");
        if (actorScope != null) {
            actorScope.close();
        }
        activeActor = new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, actorId, factory, "factory_super_admin");
        actorScope = AuthenticatedActor.trustedAsyncScope(activeActor);
        return new Fixture(factory, master, duplicate, foreignMaterial, null, actorId);
    }

    private Fixture fixtureWithOpenPurchaseOrder() {
        Fixture f = fixture();
        String po = "IQ-PO-" + UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                        + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, 'DRAFT', ?, 0, 'NOT_RECEIVED', 'UNCREATED', now(), now())",
                po, f.factoryId, po, f.actorId);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, material_name, quantity, unit, "
                        + "price_unit, quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, '重复物料', 1, 'kg', 'kg', 1, 0, now(), now())", po, f.duplicateId);
        return new Fixture(f.factoryId, f.masterId, f.duplicateId, f.foreignMaterialId, po, f.actorId);
    }

    private void insertFactoryAndUser(String factory, long actorId) {
        jdbc.update("INSERT INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, "
                        + "created_at, updated_at) VALUES (?, ?, 'FACTORY', 0, TRUE, FALSE, 20, now(), now())",
                factory, "Inquiry PG Factory " + factory.substring(factory.length() - 12));
        jdbc.update("INSERT INTO users (id, factory_id, username, password_hash, is_active, role_code, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'test', TRUE, 'factory_super_admin', now(), now())",
                actorId, factory, "inquiry-pg-" + factory.substring(factory.length() - 12));
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

    private InquiryQuote transactionReturning(java.util.function.Supplier<InquiryQuote> action) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(activeActor);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            return new TransactionTemplate(transactionManager).execute(status -> action.get());
        }
    }

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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
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

    private record Fixture(String factoryId, String masterId, String duplicateId,
                           String foreignMaterialId, String purchaseOrderId, long actorId) {
        private MaterialMergeDTO.MergeRequest request() {
            return new MaterialMergeDTO.MergeRequest(masterId, "PG inquiry reference guard");
        }
    }
}

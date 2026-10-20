package com.cretas.aims.service.intent;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Service contract on the real grant persistence boundary. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {AiPurchaseTaskGrant.class, PurchaseOrder.class, PurchaseOrderItem.class,
        Factory.class, Supplier.class, User.class, RawMaterialType.class, PurchaseReceiveRecord.class})
@EnableJpaRepositories(basePackageClasses = {AiPurchaseTaskGrantRepository.class,
        PurchaseOrderRepository.class, PurchaseOrderItemRepository.class})
@Import(PurchaseTaskGrantService.class)
class AiPurchaseTaskGrantServiceContractTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:grant_service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired PurchaseTaskGrantService service;
    @Autowired AiPurchaseTaskGrantRepository repository;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean PurchaseService purchaseService;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;

    /**
     * Failure-first source contract: the real implementation currently returns the order
     * before attaching the separately persisted item list. The grant boundary must therefore
     * read the item rows back by order id before it verifies or consumes a grant.
     */
    @Test
    void realPurchaseServiceCreateMustReturnOrReadBackPersistedItems() {
        seedPurchaseReferences();
        PurchaseServiceImpl realService = realPurchaseService();

        PurchaseOrder returned = realService.createPurchaseOrder("F-1",
                purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00"), 42L);

        assertThat(returned.getItems()).as("source contract: returned object does not hydrate item rows").isEmpty();
        assertThat(purchaseOrderItemRepository.findByPurchaseOrderId(returned.getId())).hasSize(1);
    }

    @Test
    void oneShotConsumeAndSameKeyReplayReturnTheRecordedDraft() {
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null, "2.00"));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "2.00", "10.00");
        seedPurchaseReferences();
        PurchaseServiceImpl realService = realPurchaseService();
        when(purchaseService.createPurchaseOrder(eq("F-1"), any(CreatePurchaseOrderRequest.class), eq(42L)))
                .thenAnswer(invocation -> realService.createPurchaseOrder(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        PurchaseTaskGrantService.ExecutionRequest execution = execution(grant, request, "request-1");
        PurchaseTaskGrantService.ExecutionResult first = service.executeGrant(execution);
        PurchaseTaskGrantService.ExecutionResult replay = service.executeGrant(execution);

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        assertThatThrownBy(() -> service.executeGrant(new PurchaseTaskGrantService.ExecutionRequest(
                grant.getId(), 2L, "F-1", 42L, grant.getDescriptorVersion(), "request-1", request)))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        assertThatThrownBy(() -> service.executeGrant(new PurchaseTaskGrantService.ExecutionRequest(
                grant.getId(), null, "F-1", 42L, grant.getDescriptorVersion(), "request-1", request)))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        assertThat(repository.findById(grant.getId())).get()
                .satisfies(saved -> {
                    assertThat(saved.isConsumed()).isTrue();
                    assertThat(saved.getResultPurchaseOrderId()).isEqualTo(first.purchaseOrderId());
                    assertThat(saved.getConsumedRequestKey()).isEqualTo("request-1");
                });
        verify(purchaseService, times(1)).createPurchaseOrder(eq("F-1"), any(CreatePurchaseOrderRequest.class), eq(42L));
    }

    @Test
    void actorScopeExpiryRevokeVersionAndCapsFailClosed() {
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "6.00", "10.00");

        assertThatThrownBy(() -> service.executeGrant(executionFor(
                grant, "OTHER-FACTORY", 42L, request, "request-x")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        assertThatThrownBy(() -> service.executeGrant(executionFor(
                grant, "F-1", 77L, request, "request-x")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        assertThatThrownBy(() -> service.executeGrant(execution(
                grant, request, "request-x")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);

        grant.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        repository.saveAndFlush(grant);
        assertThatThrownBy(() -> service.executeGrant(execution(
                grant, purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00"), "request-expired")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);

        AiPurchaseTaskGrant revocable = service.issueGrant(spec(null));
        service.revokeGrant(revocable.getId(), "F-1", 42L, revocable.getVersion(), 42L, "user cancelled");
        assertThatThrownBy(() -> service.executeGrant(execution(
                revocable, purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00"), "request-r")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
    }

    @Test
    void digestVersionAndExactPositivePriceAreBoundToTheGrant() {
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00");

        PurchaseTaskGrantService.ExecutionRequest wrongVersion = new PurchaseTaskGrantService.ExecutionRequest(
                grant.getId(), grant.getVersion() + 1, "F-1", 42L,
                grant.getDescriptorVersion(), "request-version", request);
        assertThatThrownBy(() -> service.executeGrant(wrongVersion))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);

        CreatePurchaseOrderRequest changedBody = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00");
        changedBody.setContractNumber("different-body");
        assertThatThrownBy(() -> service.executeGrant(execution(grant, changedBody, "request-digest")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        CreatePurchaseOrderRequest changedVersion = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00");
        changedVersion.setVersion(999L);
        assertThatThrownBy(() -> service.executeGrant(execution(grant, changedVersion, "request-version-body")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);

        CreatePurchaseOrderRequest overCap = purchaseRequest("SUP-1", "MAT-1", "5.01", "10.00");
        assertThatThrownBy(() -> service.executeGrant(execution(grant, overCap, "request-cap")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
        CreatePurchaseOrderRequest wrongPrice = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.01");
        assertThatThrownBy(() -> service.executeGrant(execution(grant, wrongPrice, "request-price")))
                .isInstanceOf(PurchaseTaskGrantService.GrantRejectedException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedPurchaseRollsBackGrantConsume() {
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00");
        when(purchaseService.createPurchaseOrder(anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("purchase failed"));

        assertThatThrownBy(() -> service.executeGrant(execution(grant, request, "request-fail")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(grant.getId())).get()
                .satisfies(saved -> assertThat(saved.isConsumed()).isFalse());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realPurchaseAndGrantRollbackTogetherWhenOuterTransactionFails() {
        seedPurchaseReferences();
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null));
        PurchaseServiceImpl realService = realPurchaseService();
        when(purchaseService.createPurchaseOrder(eq("F-1"), any(CreatePurchaseOrderRequest.class), eq(42L)))
                .thenAnswer(invocation -> realService.createPurchaseOrder(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "1.00", "10.00");
        PurchaseTaskGrantService.ExecutionRequest execution = execution(grant, request, "outer-ack-lost");
        long ordersBefore = purchaseOrderRepository.count();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            service.executeGrant(execution);
            throw new IllegalStateException("outer acknowledgement lost");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(purchaseOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(repository.findById(grant.getId())).get()
                .satisfies(saved -> assertThat(saved.isConsumed()).isFalse());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentExecuteSameGrantCreatesOneOrderAndReusesTheRecordedResult() throws Exception {
        seedPurchaseReferences();
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null, "3.00"));
        PurchaseServiceImpl realService = realPurchaseService();
        when(purchaseService.createPurchaseOrder(eq("F-1"), any(CreatePurchaseOrderRequest.class), eq(42L)))
                .thenAnswer(invocation -> realService.createPurchaseOrder(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "3.00", "10.00");
        PurchaseTaskGrantService.ExecutionRequest execution = execution(grant, request, "concurrent-execute");
        long ordersBefore = purchaseOrderRepository.count();
        long itemsBefore = purchaseOrderItemRepository.count();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentExecuteResult> first = workers.submit(() -> executeAfterBarrier(barrier, execution));
            Future<ConcurrentExecuteResult> second = workers.submit(() -> executeAfterBarrier(barrier, execution));
            List<ConcurrentExecuteResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
            assertThat(results).extracting(ConcurrentExecuteResult::result)
                    .allSatisfy(result -> assertThat(result).isNotNull());
            assertThat(results).extracting(result -> result.result().purchaseOrderId()).containsOnly(results.get(0).result().purchaseOrderId());
            assertThat(results).extracting(result -> result.result().replay()).containsExactlyInAnyOrder(false, true);

            assertThat(purchaseOrderRepository.count()).isEqualTo(ordersBefore + 1);
            assertThat(purchaseOrderItemRepository.count()).isEqualTo(itemsBefore + 1);
            AiPurchaseTaskGrant saved = repository.findById(grant.getId()).orElseThrow();
            assertThat(saved.isConsumed()).isTrue();
            PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryId(saved.getResultPurchaseOrderId(), "F-1").orElseThrow();
            List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
            assertThat(order.getFactoryId()).isEqualTo("F-1");
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getQuantity()).isEqualByComparingTo("3.0000");
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRevokeAndExecuteHasOneTerminalWinnerAndNoGhostOrder() throws Exception {
        seedPurchaseReferences();
        AiPurchaseTaskGrant grant = service.issueGrant(spec(null, "4.00"));
        PurchaseServiceImpl realService = realPurchaseService();
        when(purchaseService.createPurchaseOrder(eq("F-1"), any(CreatePurchaseOrderRequest.class), eq(42L)))
                .thenAnswer(invocation -> realService.createPurchaseOrder(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        CreatePurchaseOrderRequest request = purchaseRequest("SUP-1", "MAT-1", "4.00", "10.00");
        PurchaseTaskGrantService.ExecutionRequest execution = execution(grant, request, "concurrent-revoke-execute");
        long ordersBefore = purchaseOrderRepository.count();
        long itemsBefore = purchaseOrderItemRepository.count();
        long initialVersion = grant.getVersion();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentOutcome> execute = workers.submit(() -> executeOutcomeAfterBarrier(barrier, execution));
            Future<ConcurrentOutcome> revoke = workers.submit(() -> revokeOutcomeAfterBarrier(
                    barrier, grant.getId(), initialVersion));
            ConcurrentOutcome executeOutcome = execute.get(30, TimeUnit.SECONDS);
            ConcurrentOutcome revokeOutcome = revoke.get(30, TimeUnit.SECONDS);
            AiPurchaseTaskGrant saved = repository.findById(grant.getId()).orElseThrow();
            assertThat(saved.isRevoked() ^ saved.isConsumed()).isTrue();
            assertThat(executeOutcome.success() ^ revokeOutcome.success()).isTrue();
            if (saved.isRevoked()) {
                assertThat(revokeOutcome.success()).isTrue();
                assertThat(executeOutcome.success()).isFalse();
                assertThat(purchaseOrderRepository.count()).isEqualTo(ordersBefore);
                assertThat(purchaseOrderItemRepository.count()).isEqualTo(itemsBefore);
                assertThat(saved.getResultPurchaseOrderId()).isNull();
            } else {
                assertThat(executeOutcome.success()).isTrue();
                assertThat(revokeOutcome.success()).isFalse();
                assertThat(purchaseOrderRepository.count()).isEqualTo(ordersBefore + 1);
                assertThat(purchaseOrderItemRepository.count()).isEqualTo(itemsBefore + 1);
                PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryId(saved.getResultPurchaseOrderId(), "F-1").orElseThrow();
                assertThat(purchaseOrderItemRepository.findByPurchaseOrderId(order.getId())).hasSize(1);
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ConcurrentExecuteResult executeAfterBarrier(CyclicBarrier barrier,
                                                         PurchaseTaskGrantService.ExecutionRequest execution) {
        try {
            PurchaseTaskGrantService.ExecutionResult result = new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        awaitBarrier(barrier);
                        return service.executeGrant(execution);
                    });
            return new ConcurrentExecuteResult(result, null);
        } catch (RuntimeException failure) {
            return new ConcurrentExecuteResult(null, failure);
        }
    }

    private ConcurrentOutcome executeOutcomeAfterBarrier(CyclicBarrier barrier,
                                                         PurchaseTaskGrantService.ExecutionRequest execution) {
        try {
            PurchaseTaskGrantService.ExecutionResult result = new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        awaitBarrier(barrier);
                        return service.executeGrant(execution);
                    });
            return new ConcurrentOutcome(true, result, null);
        } catch (RuntimeException failure) {
            return new ConcurrentOutcome(false, null, failure);
        }
    }

    private ConcurrentOutcome revokeOutcomeAfterBarrier(CyclicBarrier barrier, String grantId, long expectedVersion) {
        try {
            new TransactionTemplate(transactionManager).execute(status -> {
                awaitBarrier(barrier);
                service.revokeGrant(grantId, "F-1", 42L, expectedVersion, 42L, "race revoke");
                return null;
            });
            return new ConcurrentOutcome(true, null, null);
        } catch (RuntimeException failure) {
            return new ConcurrentOutcome(false, null, failure);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("concurrency barrier failed", failure);
        }
    }

    private PurchaseTaskGrantService.GrantSpec spec(LocalDateTime expiresAt) {
        return spec(expiresAt, "1.00");
    }

    private PurchaseTaskGrantService.GrantSpec spec(LocalDateTime expiresAt, String approvedQuantity) {
        return new PurchaseTaskGrantService.GrantSpec(
                "F-1", 42L, "1.0.0", "SUP-1", "MAT-1",
                new BigDecimal("5.0000"), "kg", new BigDecimal("10.0000"),
                new BigDecimal("50.00"), "CNY", BigDecimal.ZERO, expiresAt, 99L,
                purchaseRequest("SUP-1", "MAT-1", approvedQuantity, "10.00"));
    }

    private PurchaseTaskGrantService.ExecutionRequest execution(
            AiPurchaseTaskGrant grant, CreatePurchaseOrderRequest request, String key) {
        return executionFor(grant, "F-1", 42L, request, key);
    }

    private PurchaseTaskGrantService.ExecutionRequest executionFor(
            AiPurchaseTaskGrant grant, String factoryId, Long userId,
            CreatePurchaseOrderRequest request, String key) {
        return new PurchaseTaskGrantService.ExecutionRequest(
                grant.getId(), grant.getVersion(), factoryId, userId,
                grant.getDescriptorVersion(), key, request);
    }

    private static CreatePurchaseOrderRequest purchaseRequest(
            String supplier, String material, String quantity, String price) {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item =
                new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(material);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("kg");
        item.setQuantityUnit("kg");
        item.setPriceUnit("kg");
        item.setUnitPrice(new BigDecimal(price));
        item.setTaxRate(BigDecimal.ZERO);
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(supplier);
        request.setPurchaseType("DIRECT");
        request.setOrderDate(LocalDate.now());
        request.setItems(List.of(item));
        return request;
    }

    private static PurchaseOrder persistedDraft(String id, String factory, Long user,
                                                String supplier, String material,
                                                String quantity, String price) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setFactoryId(factory);
        order.setCreatedBy(user);
        order.setSupplierId(supplier);
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setTotalAmount(new BigDecimal(quantity).multiply(new BigDecimal(price)).setScale(2));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setOrderNumber("PO-TEST-" + id);
        order.setPurchaseType(com.cretas.aims.entity.enums.PurchaseType.DIRECT);
        order.setOrderDate(LocalDate.now());
        order.setVflag(com.cretas.aims.entity.enums.VoucherFlag.UNCREATED);
        order.setInvoiceStatus(com.cretas.aims.entity.enums.PurchaseInvoiceStatus.NOT_RECEIVED);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(id);
        item.setMaterialTypeId(material);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal(price));
        item.setTaxRate(BigDecimal.ZERO);
        order.setItems(List.of(item));
        return order;
    }

    private void seedPurchaseReferences() {
        jdbcTemplate.update("INSERT INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, created_at, updated_at) SELECT 'F-1', 'grant-test-factory', 'FACTORY', 0, TRUE, FALSE, 20, NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM factories WHERE id = 'F-1')");
        jdbcTemplate.update("INSERT INTO users (id, factory_id, username, password_hash, is_active, created_at, updated_at) SELECT 42, 'F-1', 'grant-test-user', 'test', TRUE, NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 42)");
        jdbcTemplate.update("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, is_active, created_by, version, created_at, updated_at) SELECT 'SUP-1', 'F-1', 'SUP-1', 'SUP-1', 'grant-test-supplier', TRUE, 42, 0, NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE id = 'SUP-1')");
        jdbcTemplate.update("INSERT INTO raw_material_types (id, factory_id, code, name, unit, is_active, is_abaca_packaging, is_byproduct, self_supplied, tax_treatment, created_by, created_at, updated_at) SELECT 'MAT-1', 'F-1', 'MAT-1', 'grant-test-material', 'kg', TRUE, FALSE, FALSE, FALSE, 'TAXABLE', 42, NOW(), NOW() WHERE NOT EXISTS (SELECT 1 FROM raw_material_types WHERE id = 'MAT-1')");
    }

    private PurchaseServiceImpl realPurchaseService() {
        SupplierRepository suppliers = mock(SupplierRepository.class);
        RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
        SupplierMaterialRepository relations = mock(SupplierMaterialRepository.class);
        Supplier supplier = new Supplier(); supplier.setId("SUP-1"); supplier.setIsActive(true);
        when(suppliers.findByIdAndFactoryId("SUP-1", "F-1")).thenReturn(Optional.of(supplier));
        RawMaterialType material = new RawMaterialType(); material.setId("MAT-1"); material.setFactoryId("F-1"); material.setName("raw"); material.setUnit("kg");
        when(materials.findById("MAT-1")).thenReturn(Optional.of(material));
        SupplierMaterial relation = new SupplierMaterial(); relation.setId("REL-1"); relation.setFactoryId("F-1"); relation.setSupplierId("SUP-1"); relation.setMaterialTypeId("MAT-1"); relation.setPurchaseUnit("kg"); relation.setActive(true);
        when(relations.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue("F-1", "SUP-1", "MAT-1")).thenReturn(true);
        when(relations.findByFactoryIdAndSupplierIdAndMaterialTypeId("F-1", "SUP-1", "MAT-1")).thenReturn(Optional.of(relation));
        PurchaseServiceImpl service = new PurchaseServiceImpl(purchaseOrderRepository, purchaseOrderItemRepository,
                null, suppliers, materials, null, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplierMaterialRepository", relations);
        return service;
    }

    private record ConcurrentExecuteResult(PurchaseTaskGrantService.ExecutionResult result,
                                            RuntimeException failure) { }

    private record ConcurrentOutcome(boolean success,
                                     PurchaseTaskGrantService.ExecutionResult execution,
                                     RuntimeException failure) { }
}

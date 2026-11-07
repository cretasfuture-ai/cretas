package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionPlanBatchUsage;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.dto.production.PlanConcurrentReadinessDTO;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.production.PlanConcurrentReadinessService;
import com.cretas.aims.controller.PlanConcurrentReadinessController;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Opt-in real PostgreSQL contract for the concurrent readiness reader.
 *
 * <p>Fixtures are saved through the real repositories.  The disposable target
 * intentionally uses entity-generated DDL; dropping its FK constraints only
 * isolates this contract from unrelated production fixture tables and is not a
 * claim about production migrations or triggers.</p>
 */
@SpringBootTest(classes = PlanConcurrentReadinessPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PlanConcurrentReadinessPostgresContractTest {
    // Random disposable factories have no protected-product policy. Actor-aware denial is tested separately.
    @MockBean ProcessSecretRecipeGuard processSecretRecipeGuard;
    // This regression exercises real request/transaction wiring; authorization has separate MVC contracts.
    @MockBean BusinessRowScope businessRowScope;
    @MockBean FactoryAccessValidator factoryAccessValidator;

    private static final LocalDate TODAY = com.cretas.aims.util.InventoryExpiry.today();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({PlanConcurrentReadinessService.class, WarehouseResolver.class, PlanConcurrentReadinessController.class})
    static class Runtime {
        @Bean
        static BeanPostProcessor productionPlanRepositoryReadBarrier() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ProductionPlanRepository)) return bean;
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        Object value = invocation.proceed();
                        ReadBarrier barrier = ACTIVE_BARRIER.get();
                        if (barrier != null && "findByIdAndFactoryId".equals(invocation.getMethod().getName())
                                && invocation.getArguments().length == 2
                                && barrier.planId().equals(invocation.getArguments()[0])) {
                            barrier.afterRealQuery();
                        }
                        return value;
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    private static final AtomicReference<ReadBarrier> ACTIVE_BARRIER = new AtomicReference<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PlanConcurrentReadinessService readiness;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionPlanBatchUsageRepository planBatchUsages;
    @Autowired PlanStandardCostLineRepository standards;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository bomItems;
    @Autowired RawMaterialTypeRepository materialTypes;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired FactoryWarehouseRepository warehouses;
    @Autowired FinishedGoodsBatchRepository finishedGoods;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlanConcurrentReadinessController controller;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // Entity-DDL fixture isolation only; never represents production migration evidence.
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    @Test
    void requestBoundNonAutoCommitQueryBeforeControllerStillGetsFreshRepeatableReadSnapshot() throws Exception {
        Fixture f = fixture("kg", new BigDecimal("100"));
        when(businessRowScope.creatorIds(f.factory())).thenReturn(null);
        DbState before = state();
        var request = new ServletWebRequest(new MockHttpServletRequest());
        var osiv = new OpenEntityManagerInViewInterceptor();
        osiv.setEntityManagerFactory(entityManagerFactory);
        osiv.preHandle(request);
        try {
            var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(holder).isNotNull();
            var em = holder.getEntityManager();
            // Production uses non-autoCommit connections. Security/plan lookups can execute before
            // the snapshot service, with an OSIV-bound EM but no active Spring/JPA transaction.
            em.unwrap(Session.class).doWork(connection -> connection.setAutoCommit(false));
            assertThat(((Number) em.createNativeQuery("SELECT 1").getSingleResult()).intValue()).isEqualTo(1);
            assertThat(em.getTransaction().isActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            var response = controller.read(f.factory(), f.planId());

            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getData().material().status()).isEqualTo("VERIFIED");
            assertThat(response.getData().material().lowerPercent()).isEqualByComparingTo("100");
            assertThat(response.getData().progress().completionPercent()).isEqualByComparingTo("0");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(holder);
        } finally {
            osiv.afterCompletion(request, null);
        }
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void sameFactoryPendingDemandConstrainsLowerBoundButFutureTerminalAndOtherFactoryDoNotCompete() {
        Fixture f = fixture("kg", new BigDecimal("100"));
        savePlan(f.factory(), "PEER-" + f.token(), "PEER-N-" + f.token(), new BigDecimal("80"), TODAY,
                ProductionPlanStatus.PENDING, f.materialId(), f.productId(), f.recipeId());
        savePlan(f.factory(), "FUTURE-" + f.token(), "FUTURE-N-" + f.token(), new BigDecimal("500"), TODAY.plusDays(30),
                ProductionPlanStatus.PLANNED, f.materialId(), f.productId(), f.recipeId());
        savePlan(f.factory(), "DONE-" + f.token(), "DONE-N-" + f.token(), new BigDecimal("500"), TODAY,
                ProductionPlanStatus.COMPLETED, f.materialId(), f.productId(), f.recipeId());
        savePlan(f.factory(), "APPROVAL-" + f.token(), "APPROVAL-N-" + f.token(), new BigDecimal("900"), TODAY,
                ProductionPlanStatus.PENDING_APPROVAL, f.materialId(), f.productId(), f.recipeId());
        savePlan("OTHER-" + f.token(), "OTHER-" + f.token(), "OTHER-N-" + f.token(), new BigDecimal("500"), TODAY,
                ProductionPlanStatus.PENDING, f.materialId(), f.productId(), f.recipeId());

        DbState before = state();
        PlanConcurrentReadinessDTO result = readiness.read(f.factory(), f.planId());
        DbState after = state();

        assertThat(result.material().status()).isEqualTo("VERIFIED");
        assertThat(result.material().lowerPercent()).isEqualByComparingTo("25.000000");
        assertThat(result.material().upperPercent()).isEqualByComparingTo("100.000000");
        assertThat(result.material().items()).singleElement().satisfies(item -> {
            assertThat(item.required()).isEqualByComparingTo("80");
            assertThat(item.otherUnoccupied()).isEqualByComparingTo("80");
            assertThat(item.stockFree()).isEqualByComparingTo("100");
        });
        assertThat(after).isEqualTo(before);
    }

    @Test
    void pickingPoolIncludesLinesideExcludesInactiveRawAndFlagsHeldInactiveBatch() {
        Fixture f = fixture("kg", new BigDecimal("100"));
        String lineside = "WH-LINE-" + f.token();
        saveWarehouse(f.factory(), lineside, "LINE-" + f.token(), FactoryWarehouse.WarehouseType.LINESIDE, true);
        saveMaterialBatch(f.factory(), f.materialId(), lineside, "LINE-BATCH-" + f.token(), new BigDecimal("20"), "kg");
        PlanConcurrentReadinessDTO withLineside = readiness.read(f.factory(), f.planId());
        assertThat(withLineside.material().items()).singleElement().satisfies(item ->
                assertThat(item.stockFree()).isEqualByComparingTo("120"));

        String inactive = "WH-INACTIVE-" + f.token();
        saveWarehouse(f.factory(), inactive, "RAW-INACTIVE-" + f.token(), FactoryWarehouse.WarehouseType.RAW, false);
        saveMaterialBatch(f.factory(), f.materialId(), inactive, "INACTIVE-BATCH-" + f.token(), new BigDecimal("50"), "kg");
        PlanConcurrentReadinessDTO inactiveStock = readiness.read(f.factory(), f.planId());
        assertThat(inactiveStock.material().items()).singleElement().satisfies(item ->
                assertThat(item.stockFree()).isEqualByComparingTo("120"));

        String heldBatch = saveMaterialBatch(f.factory(), f.materialId(), inactive,
                "INACTIVE-HELD-" + f.token(), new BigDecimal("50"), "kg", new BigDecimal("10"), null);
        saveHeldUsage(f, heldBatch, new BigDecimal("10"));
        PlanConcurrentReadinessDTO heldInactive = readiness.read(f.factory(), f.planId());
        assertThat(heldInactive.material().status()).isEqualTo("UNVERIFIED");
        assertThat(heldInactive.unverifiedPlanCount()).isPositive();
    }

    @Test
    void dueDateAndEffectiveTodayExcludeExpiredStockAndFlagExpiredHeldReservation() {
        Fixture f = fixture("kg", BigDecimal.ZERO);
        saveMaterialBatch(f.factory(), f.materialId(), f.warehouseId(), "EXPIRED-" + f.token(),
                new BigDecimal("100"), "kg", BigDecimal.ZERO, TODAY.minusDays(1));
        PlanConcurrentReadinessDTO expired = readiness.read(f.factory(), f.planId());
        assertThat(expired.material().status()).isEqualTo("VERIFIED");
        assertThat(expired.material().items()).singleElement().satisfies(item -> {
            assertThat(item.stockFree()).isEqualByComparingTo("0");
            assertThat(item.lowerPercent()).isEqualByComparingTo("0.000000");
            assertThat(item.upperPercent()).isEqualByComparingTo("0.000000");
        });

        String heldBatch = saveMaterialBatch(f.factory(), f.materialId(), f.warehouseId(),
                "EXPIRED-HELD-" + f.token(), new BigDecimal("100"), "kg", new BigDecimal("10"), TODAY.minusDays(1));
        saveHeldUsage(f, heldBatch, new BigDecimal("10"));
        PlanConcurrentReadinessDTO heldExpired = readiness.read(f.factory(), f.planId());
        assertThat(heldExpired.material().status()).isEqualTo("UNVERIFIED");
        assertThat(heldExpired.unverifiedPlanCount()).isPositive();
    }

    @Test
    void pendingApprovalTargetHasNoDemandButStillReportsFinishedReceiptProgress() {
        Fixture f = fixture("kg", new BigDecimal("100"), ProductionPlanStatus.PENDING_APPROVAL);
        saveFinished(f, "FG-APPROVAL-" + f.token(), f.productId(), new BigDecimal("40"), "kg",
                FinishedGoodsBatch.Status.AVAILABLE, null);

        PlanConcurrentReadinessDTO result = readiness.read(f.factory(), f.planId());
        assertThat(result.material().status()).isEqualTo("UNVERIFIED");
        assertThat(result.material().reason()).isEqualTo("NO_TARGET_DEMAND");
        assertThat(result.progress().status()).isEqualTo("VERIFIED");
        assertThat(result.progress().receivedQuantity()).isEqualByComparingTo("40");
        assertThat(result.progress().completionPercent()).isEqualByComparingTo("50.000000");
    }

    @Test
    void normalizesGramToKilogramButMixedBoxAndCaseRemainUnknown() {
        Fixture f = fixture("g", new BigDecimal("100000"));
        DbState before = state();
        PlanConcurrentReadinessDTO grams = readiness.read(f.factory(), f.planId());
        assertThat(grams.material().status()).isEqualTo("VERIFIED");
        assertThat(grams.material().items()).singleElement().satisfies(item ->
                assertThat(item.stockFree()).isEqualByComparingTo("100"));
        assertThat(state()).isEqualTo(before);

        saveMaterialBatch(f.factory(), f.materialId(), f.warehouseId(), "BOX-" + f.token(), new BigDecimal("10"), "box");
        saveMaterialBatch(f.factory(), f.materialId(), f.warehouseId(), "CASE-" + f.token(), new BigDecimal("10"), "case");
        PlanConcurrentReadinessDTO mixed = readiness.read(f.factory(), f.planId());
        assertThat(mixed.material().status()).isEqualTo("UNVERIFIED");
        assertThat(mixed.unverifiedPlanCount()).isPositive();
        assertThat(state().materialCount()).isEqualTo(before.materialCount() + 2);
    }

    @Test
    void terminalPlanDoesNotAddDemandAndFinishedReceiptIsIndependentAndCanExceedPlan() {
        Fixture f = fixture("kg", new BigDecimal("100"));
        ProductionPlan terminal = savePlan(f.factory(), "DONE-" + f.token(), "DONE-N-" + f.token(), new BigDecimal("80"), TODAY,
                ProductionPlanStatus.COMPLETED, f.materialId(), f.productId(), f.recipeId());
        // The target has no finished receipt: terminal state alone must not imply 100%.
        PlanConcurrentReadinessDTO target = readiness.read(f.factory(), f.planId());
        assertThat(target.material().lowerPercent()).isEqualByComparingTo("100.000000");
        assertThat(target.progress().status()).isEqualTo("VERIFIED");
        assertThat(target.progress().completionPercent()).isEqualByComparingTo("0.000000");

        saveFinished(f, "FG-120-" + f.token(), f.productId(), new BigDecimal("120"), "kg", FinishedGoodsBatch.Status.AVAILABLE, null);
        saveFinished(f, "FG-RETURN-" + f.token(), f.productId(), new BigDecimal("900"), "kg", FinishedGoodsBatch.Status.AVAILABLE, 9L);
        saveFinished(f, "FG-REVERSED-" + f.token(), f.productId(), new BigDecimal("900"), "kg", FinishedGoodsBatch.Status.REVERSED, null);
        saveFinished(f, "FG-OTHER-SKU-" + f.token(), "SKU-OTHER-" + f.token(), new BigDecimal("900"), "kg", FinishedGoodsBatch.Status.AVAILABLE, null);

        PlanConcurrentReadinessDTO withReceipt = readiness.read(f.factory(), f.planId());
        assertThat(withReceipt.planStatus()).isEqualTo(ProductionPlanStatus.PLANNED.name());
        assertThat(withReceipt.progress().receivedQuantity()).isEqualByComparingTo("120");
        assertThat(withReceipt.progress().completionPercent()).isEqualByComparingTo("150.000000");
        assertThat(terminal.getId()).isNotEqualTo(f.planId());
    }

    @Test
    void repeatableReadKeepsReaderSnapshotWhileAnotherConnectionCommitsStockChange() throws Exception {
        Fixture f = fixture("kg", new BigDecimal("100"));
        CountDownLatch firstTargetRead = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ReadBarrier barrier = new ReadBarrier(f.planId(), firstTargetRead, releaseReader);
        assertThat(ACTIVE_BARRIER.compareAndSet(null, barrier)).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PlanConcurrentReadinessDTO> reader = pool.submit(() -> readiness.read(f.factory(), f.planId()));
            awaitReaderBarrier(firstTargetRead, reader);
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(status ->
                    jdbc.update("UPDATE material_batches SET receipt_quantity = ? WHERE id = ?", new BigDecimal("10"), f.materialBatchId())));
            writer.get(10, TimeUnit.SECONDS);
            releaseReader.countDown();
            PlanConcurrentReadinessDTO snapshot = reader.get(20, TimeUnit.SECONDS);
            assertThat(snapshot.material().items()).singleElement().satisfies(item ->
                    assertThat(item.stockFree()).isEqualByComparingTo("100"));
            assertThat(jdbc.queryForObject("SELECT receipt_quantity FROM material_batches WHERE id = ?", BigDecimal.class, f.materialBatchId()))
                    .isEqualByComparingTo("10");
        } finally {
            ACTIVE_BARRIER.compareAndSet(barrier, null);
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitReaderBarrier(CountDownLatch barrier, Future<?> reader) throws Exception {
        if (barrier.await(10, TimeUnit.SECONDS)) return;
        try {
            reader.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            throw new AssertionError("reader failed before the real repository barrier", error.getCause());
        }
        throw new AssertionError("reader did not reach the real repository barrier within 10 seconds");
    }

    private record ReadBarrier(String planId, CountDownLatch reached, CountDownLatch release) {
        private void afterRealQuery() throws InterruptedException {
            reached.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("reader release timeout");
        }
    }

    private Fixture fixture(String stockUnit, BigDecimal receipt) {
        return fixture(stockUnit, receipt, ProductionPlanStatus.PLANNED);
    }

    private Fixture fixture(String stockUnit, BigDecimal receipt, ProductionPlanStatus targetStatus) {
        String token = suffix();
        String factory = "READ-" + token;
        String warehouse = "WH-WKS-" + token;
        String logisticsWarehouse = "WH-LOG-" + token;
        String material = "MAT-READ-" + token;
        String product = "SKU-READ-" + token;
        String plan = "PLAN-READ-" + token;
        String recipe = "BOM-READ-" + token;
        tx.executeWithoutResult(status -> {
            saveWarehouse(factory, warehouse, "WH-WKS", FactoryWarehouse.WarehouseType.WORKSHOP, true);
            saveWarehouse(factory, logisticsWarehouse, "WH-LOG", FactoryWarehouse.WarehouseType.LOGISTICS, true);

            RawMaterialType type = new RawMaterialType();
            type.setId(material);
            type.setFactoryId(factory);
            type.setCode("MAT-" + token);
            type.setName("readiness material");
            type.setCategory("原料");
            type.setUnit("kg");
            type.setIsByproduct(false);
            type.setSelfSupplied(false);
            type.setIsActive(true);
            type.setCreatedBy(1L);
            materialTypes.saveAndFlush(type);

            saveMaterialBatch(factory, material, warehouse, "MB-" + token, receipt, stockUnit);
            BomRecipe bom = new BomRecipe();
            bom.setId(recipe);
            bom.setFactoryId(factory);
            bom.setRecipeCode("BOM-CODE-" + token);
            bom.setProductTypeId(product);
            bom.setProductName("readiness product");
            bom.setVersion(1);
            bom.setIsCurrent(true);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("kg");
            bom.setStatus(BomRecipe.Status.ACTIVE);
            recipes.saveAndFlush(bom);

            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(recipe);
            item.setFactoryId(factory);
            item.setMaterialTypeId(material);
            item.setMaterialName("readiness material");
            item.setStandardQuantity(BigDecimal.ONE);
            item.setYieldRate(new BigDecimal("100.00"));
            item.setUnit("kg");
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialCategory("RAW");
            item.setSortOrder(0);
            item.setIsOptional(false);
            item.setPerPortion(false);
            bomItems.saveAndFlush(item);

            savePlan(factory, plan, "PLAN-N-" + token, new BigDecimal("80"), TODAY,
                    targetStatus, material, product, recipe);
        });
        return new Fixture(token, factory, warehouse, material, product, plan, recipe, "MB-ID-MB-" + token);
    }

    private ProductionPlan savePlan(String factory, String id, String number, BigDecimal quantity, LocalDate date,
                                    ProductionPlanStatus status, String material, String product, String recipeId) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(factory);
        plan.setPlanNumber(number);
        plan.setProductTypeId(product);
        plan.setPlannedQuantity(quantity);
        plan.setPlannedUnit("kg");
        plan.setPlannedDate(date);
        plan.setBatchDate(date);
        plan.setStatus(status);
        plan.setCreatedBy(1L);
        plan.setTargetFinishedGoodIds(null);
        plan.setSelectedBomRecipeId(recipeId);
        plan.setSelectedBomVersion(1);
        tx.executeWithoutResult(state -> {
            plans.saveAndFlush(plan);
            standards.saveAndFlush(PlanStandardCostLine.builder()
                    .factoryId(factory).planId(id).lineType(PlanStandardCostLine.TYPE_MATERIAL)
                    .materialTypeId(material).materialName("readiness material").materialCategory("RAW")
                    .productTypeId(product).bomRecipeId(recipeId).bomVersion(1)
                    .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT).standardRate(BigDecimal.ONE)
                    .standardUnit("kg").capturedAt(LocalDateTime.now()).build());
        });
        return plan;
    }

    private void saveMaterialBatch(String factory, String material, String warehouse, String batchNumber,
                                   BigDecimal receipt, String unit) {
        saveMaterialBatch(factory, material, warehouse, batchNumber, receipt, unit, BigDecimal.ZERO, null);
    }

    private String saveMaterialBatch(String factory, String material, String warehouse, String batchNumber,
                                     BigDecimal receipt, String unit, BigDecimal reserved, LocalDate expireDate) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-ID-" + batchNumber);
        batch.setFactoryId(factory);
        batch.setBatchNumber(batchNumber);
        batch.setMaterialTypeId(material);
        batch.setReceiptDate(TODAY);
        batch.setExpireDate(expireDate);
        batch.setWarehouseId(warehouse);
        batch.setReceiptQuantity(receipt);
        batch.setQuantityUnit(unit);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(reserved);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        batch.setCreatedBy(1L);
        materialBatches.saveAndFlush(batch);
        return batch.getId();
    }

    private void saveWarehouse(String factory, String id, String code, FactoryWarehouse.WarehouseType type, boolean active) {
        tx.executeWithoutResult(state -> {
            FactoryWarehouse warehouse = new FactoryWarehouse();
            warehouse.setId(id);
            warehouse.setFactoryId(factory);
            warehouse.setCode(code);
            warehouse.setName("readiness " + code);
            warehouse.setType(type);
            warehouse.setIsActive(active);
            warehouses.saveAndFlush(warehouse);
        });
    }

    private void saveHeldUsage(Fixture fixture, String materialBatchId, BigDecimal quantity) {
        tx.executeWithoutResult(state -> {
            ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
            usage.setId("USAGE-" + fixture.token() + "-" + materialBatchId);
            usage.setProductionPlanId(fixture.planId());
            usage.setMaterialBatchId(materialBatchId);
            usage.setPlannedQuantity(quantity);
            usage.setActualQuantity(BigDecimal.ZERO);
            usage.setReservedQuantity(quantity);
            usage.setUsedQuantity(BigDecimal.ZERO);
            planBatchUsages.saveAndFlush(usage);
        });
    }

    private void saveFinished(Fixture f, String id, String product, BigDecimal quantity, String unit,
                              String status, Long returnItemId) {
        tx.executeWithoutResult(state -> {
            FinishedGoodsBatch row = new FinishedGoodsBatch();
            row.setId(id);
            row.setFactoryId(f.factory());
            row.setBatchNumber(id);
            row.setProductTypeId(product);
            row.setProducedQuantity(quantity);
            row.setShippedQuantity(BigDecimal.ZERO);
            row.setReservedQuantity(BigDecimal.ZERO);
            row.setUnit(unit);
            row.setProductionPlanId(f.planId());
            row.setWarehouseId(f.warehouseId());
            row.setStatus(status);
            row.setReturnOrderItemId(returnItemId);
            row.setOwnership(InventoryOwnership.COMPANY_OWNED);
            finishedGoods.saveAndFlush(row);
        });
    }

    private DbState state() {
        return new DbState(
                jdbc.queryForObject("SELECT count(*) FROM production_plans", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM material_batches", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM plan_standard_cost_lines", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM bom_recipes", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM bom_recipe_items", Long.class),
                jdbc.queryForObject("SELECT coalesce(sum(receipt_quantity),0) FROM material_batches", BigDecimal.class),
                jdbc.queryForObject("SELECT coalesce(sum(used_quantity),0) FROM material_batches", BigDecimal.class),
                jdbc.queryForObject("SELECT coalesce(sum(reserved_quantity),0) FROM material_batches", BigDecimal.class));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record Fixture(String token, String factory, String warehouseId, String materialId,
                           String productId, String planId, String recipeId, String materialBatchId) {
    }

    private record DbState(long planCount, long materialCount, long standardCount, long recipeCount,
                           long bomItemCount, BigDecimal receipt,
                           BigDecimal used, BigDecimal reserved) {
    }
}

package com.cretas.aims.repository;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.inventory.cost.MaterialMovingAverageCalculator;
import com.cretas.aims.service.*;
import com.cretas.aims.utils.ExcelUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.concurrent.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the real receipt service and JPA flush inside the real transaction manager. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catalog_gift_atomic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FreeGiftCostAtomicityRepositoryQueryValidationTest {
    @Autowired EntityManager em;
    @Autowired MaterialBatchRepository batches;
    @Autowired RawMaterialTypeRepository materials;
    @Autowired SupplierRepository suppliers;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach @AfterEach
    void clearOnlyThisIsolatedSyntheticDatabase() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (String entity : List.of("MaterialBatch", "RawMaterialType", "ProductType", "Customer", "Supplier", "User", "Factory"))
                em.createQuery("DELETE FROM " + entity).executeUpdate();
        });
    }

    @DynamicPropertySource
    static void optionalDisposablePostgres(DynamicPropertyRegistry properties) {
        String url = System.getenv("CRETAS_FACTORY_CATALOG_JPA_URL");
        if (url == null) return;
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/cretas_catalog_jpa_test(?:\\?stringtype=unspecified)?"))
            throw new IllegalArgumentException("Only the isolated catalog JPA test database is allowed");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "cretas_ci");
        properties.add("spring.datasource.password", () -> System.getenv("CRETAS_FACTORY_CATALOG_JPA_PASSWORD"));
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void twoRealReceiptTransactionsKeepBothGiftLayersInAverage() throws Exception {
        var tx = new TransactionTemplate(transactions);
        Long owner = tx.execute(status -> {
            var owners = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(em);
            var material = new RawMaterialType(); material.setId("CONCURRENT-MAT"); material.setFactoryId("F006");
            material.setCreatedBy(owners.first());
            material.setCode("CONCURRENT-MAT"); material.setName("Concurrent material"); material.setUnit("kg");
            material.setMovingAvgPrice(BigDecimal.TEN); em.persist(material);
            var batch = new MaterialBatch(); batch.setId("BASE-PURCHASE"); batch.setBatchNumber("BASE-PURCHASE");
            batch.setFactoryId("F006"); batch.setMaterialTypeId(material.getId()); batch.setSupplierId("SUP-F006");
            batch.setReceiptDate(LocalDate.now()); batch.setReceiptQuantity(BigDecimal.TEN); batch.setQuantityUnit("kg");
            batch.setUnitPrice(BigDecimal.TEN); batch.setUsedQuantity(BigDecimal.ZERO); batch.setReservedQuantity(BigDecimal.ZERO);
            batch.setCreatedBy(owners.first());
            batch.setStatus(MaterialBatchStatus.AVAILABLE); em.persist(batch); em.flush(); return owners.first();
        });
        var mapper = mock(MaterialBatchMapper.class);
        when(mapper.toEntity(any(), eq("F006"), eq(owner))).thenAnswer(invocation -> {
            CreateMaterialBatchRequest request = invocation.getArgument(0);
            var batch = new MaterialBatch(); batch.setFactoryId("F006"); batch.setMaterialTypeId(request.getMaterialTypeId());
            batch.setSupplierId(request.getSupplierId()); batch.setReceiptDate(request.getReceiptDate());
            batch.setReceiptQuantity(request.getReceiptQuantity()); batch.setQuantityUnit("kg");
            batch.setWeightPerUnit(request.getTotalWeight().divide(request.getReceiptQuantity())); batch.setUnitPrice(BigDecimal.ZERO);
            batch.setUsedQuantity(BigDecimal.ZERO); batch.setReservedQuantity(BigDecimal.ZERO);
            batch.setSourceDocType("FREE_GIFT"); batch.setSourceDocId(request.getSourceDocId());
            batch.setCreatedBy(owner);
            batch.setStatus(MaterialBatchStatus.AVAILABLE); return batch;
        });
        var service = new MaterialBatchServiceImpl(batches, mock(MaterialBatchAdjustmentRepository.class), materials,
                mapper, mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class), mock(FuturePlanMatchingService.class));
        var warehouse = mock(WarehouseResolver.class);
        when(warehouse.resolveLogisticsId("F006")).thenReturn("WH-TEST");
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouse);
        ReflectionTestUtils.setField(service, "supplierRepository", suppliers);
        var converter = new com.cretas.aims.service.uom.MaterialUomConverter(
                mock(MaterialPackagingHierarchyRepository.class),
                mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class), materials,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        ReflectionTestUtils.setField(service, "materialMovingAverageCalculator", new MaterialMovingAverageCalculator(converter));
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactionalService = (MaterialBatchService) proxy.getProxy();
        var ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (String line : List.of("1", "2")) tasks.add(pool.submit(() -> {
                ready.countDown();
                try { if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Second receipt did not start"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                var request = new CreateMaterialBatchRequest(); request.setMaterialTypeId("CONCURRENT-MAT");
                request.setSupplierId("SUP-F006"); request.setReceiptDate(LocalDate.now());
                request.setReceiptQuantity(BigDecimal.TEN); request.setQuantityUnit("kg"); request.setTotalWeight(BigDecimal.TEN);
                request.setTotalValue(BigDecimal.ZERO); request.setUnitPrice(BigDecimal.ZERO);
                request.setSourceDocType("FREE_GIFT"); request.setSourceDocId("CONCURRENT-DOC");
                request.setSourceLineNo(line); request.setNotes("synthetic concurrent gift");
                transactionalService.createMaterialBatch("F006", request, owner);
            }));
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        tx.execute(status -> {
            assertThat(batches.findByFactoryIdAndMaterialTypeId("F006", "CONCURRENT-MAT")).hasSize(3);
            assertThat(materials.findById("CONCURRENT-MAT").orElseThrow().getMovingAvgPrice()).isEqualByComparingTo("3.3333");
            return null;
        });
    }

    @Test
    void costRebuildFailureRollsBackAnAlreadyFlushedGiftReceipt() {
        var tx = new TransactionTemplate(transactions);
        for (boolean incomplete : List.of(true, false)) {
            assertThatThrownBy(() -> tx.execute(status -> {
                var owners = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(em);
                var material = new RawMaterialType(); material.setId("ATOMIC-MAT"); material.setFactoryId("F006");
                material.setCreatedBy(owners.first());
                material.setCode("ATOMIC-MAT"); material.setName("Atomic material"); material.setUnit("kg");
                material.setMovingAvgPrice(BigDecimal.TEN); em.persist(material); em.flush();
                var request = new CreateMaterialBatchRequest();
                request.setMaterialTypeId(material.getId()); request.setSupplierId("SUP-F006");
                request.setReceiptDate(LocalDate.now()); request.setReceiptQuantity(BigDecimal.TEN);
                request.setQuantityUnit("kg"); request.setTotalWeight(BigDecimal.TEN);
                request.setTotalValue(BigDecimal.ZERO); request.setUnitPrice(BigDecimal.ZERO);
                request.setSourceDocType("FREE_GIFT"); request.setSourceDocId("ATOMIC-DOC");
                request.setSourceLineNo("1"); request.setNotes("synthetic gift");
                var mapped = new MaterialBatch(); mapped.setFactoryId("F006");
                mapped.setMaterialTypeId(material.getId()); mapped.setSupplierId("SUP-F006");
                mapped.setReceiptDate(request.getReceiptDate()); mapped.setReceiptQuantity(BigDecimal.TEN);
                mapped.setQuantityUnit("kg"); mapped.setUnitPrice(BigDecimal.ZERO);
                mapped.setSourceDocType("FREE_GIFT"); mapped.setSourceDocId("ATOMIC-DOC");
                mapped.setCreatedBy(owners.first());
                mapped.setWeightPerUnit(BigDecimal.ONE);
                mapped.setStatus(MaterialBatchStatus.AVAILABLE); mapped.setUsedQuantity(BigDecimal.ZERO);
                mapped.setReservedQuantity(BigDecimal.ZERO);
                var mapper = mock(MaterialBatchMapper.class);
                when(mapper.toEntity(request, "F006", owners.first())).thenReturn(mapped);
                var service = new MaterialBatchServiceImpl(batches, mock(MaterialBatchAdjustmentRepository.class),
                        materials, mapper, mock(MaterialConsumptionRepository.class),
                        mock(ProductionPlanBatchUsageRepository.class), mock(ExcelUtil.class), mock(FuturePlanMatchingService.class));
                var warehouse = mock(WarehouseResolver.class);
                when(warehouse.resolveLogisticsId("F006")).thenReturn("WH-TEST");
                ReflectionTestUtils.setField(service, "warehouseResolver", warehouse);
                ReflectionTestUtils.setField(service, "supplierRepository", suppliers);
                var calculator = mock(MaterialMovingAverageCalculator.class);
                if (incomplete) when(calculator.calculate(anyString(), anyString(), anyList()))
                        .thenReturn(new MaterialMovingAverageCalculator.CalculationResult(null, BigDecimal.TEN,
                                BigDecimal.ZERO, List.of("unknown historic cost")));
                else when(calculator.calculate(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("cost unavailable"));
                ReflectionTestUtils.setField(service, "materialMovingAverageCalculator", calculator);
                service.createMaterialBatch("F006", request, owners.first());
                return null;
            })).isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                    .hasMessageContaining("成本重建失败");
            tx.execute(status -> {
                assertThat(batches.count()).isZero();
                assertThat(materials.findById("ATOMIC-MAT")).isEmpty();
                return null;
            });
        }
    }

    @Test
    void zeroCostReplayCannotMisreportAPreviouslyValuedGiftAsFree() {
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            var owners = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(em);
            var material = new RawMaterialType(); material.setId("REPLAY-MAT"); material.setFactoryId("F006");
            material.setCode("REPLAY-MAT"); material.setName("Replay material"); material.setUnit("kg");
            material.setCreatedBy(owners.first()); em.persist(material);
            var existing = new MaterialBatch(); existing.setId("VALUED-GIFT"); existing.setBatchNumber("VALUED-GIFT");
            existing.setFactoryId("F006"); existing.setMaterialTypeId(material.getId()); existing.setSupplierId("SUP-F006");
            existing.setReceiptDate(LocalDate.now()); existing.setReceiptQuantity(BigDecimal.TEN); existing.setQuantityUnit("kg");
            existing.setUnitPrice(new BigDecimal("3")); existing.setWeightPerUnit(BigDecimal.ONE);
            existing.setUsedQuantity(BigDecimal.ZERO); existing.setReservedQuantity(BigDecimal.ZERO);
            existing.setStatus(MaterialBatchStatus.AVAILABLE); existing.setWarehouseId("WH-TEST"); existing.setCreatedBy(owners.first());
            existing.setSourceDocType("FREE_GIFT"); existing.setSourceDocId("REPLAY-DOC"); existing.setSourceEventKey("REPLAY-DOC#line:1");
            em.persist(existing); em.flush();
            var mapper = mock(MaterialBatchMapper.class);
            var service = new MaterialBatchServiceImpl(batches, mock(MaterialBatchAdjustmentRepository.class), materials,
                    mapper, mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                    mock(ExcelUtil.class), mock(FuturePlanMatchingService.class));
            ReflectionTestUtils.setField(service, "supplierRepository", suppliers);
            var request = new CreateMaterialBatchRequest(); request.setMaterialTypeId(material.getId());
            request.setSupplierId("SUP-F006"); request.setReceiptDate(LocalDate.now()); request.setReceiptQuantity(BigDecimal.TEN);
            request.setQuantityUnit("kg"); request.setWarehouseId("WH-TEST"); request.setTotalWeight(BigDecimal.TEN);
            request.setTotalValue(BigDecimal.ZERO); request.setUnitPrice(BigDecimal.ZERO); request.setSourceDocType("FREE_GIFT");
            request.setSourceDocId("REPLAY-DOC"); request.setSourceLineNo("1"); request.setNotes("synthetic gift retry");
            service.createMaterialBatch("F006", request, owners.first()); return null;
        })).isInstanceOf(com.cretas.aims.exception.BusinessException.class).hasMessageContaining("不同入库内容");
    }
}

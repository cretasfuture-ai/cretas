package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.WageRecordTriggerService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real Spring transaction proxy plus a real isolated H2 transaction-bound
 * database. Repository doubles only expose the service seam; their save/read
 * implementations use the same JDBC connection so rollback is observable.
 */
@ExtendWith(MockitoExtension.class)
class ProductionBatchMaterialConsumptionTransactionContractTest {

    private static final String FACTORY = "F006";
    private static final long BATCH_ID = 11017L;
    private static final String MATERIAL_BATCH_ID = "MB-RAW-TX";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private WageRecordTriggerService wageRecordTriggerService;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @InjectMocks private ProcessingServiceImpl processingService;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ProcessingService transactionalService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(processingService, "inventoryLowStockEventPublisher",
                inventoryLowStockEventPublisher);
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE material_batches ("
                + "id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                + "receipt_quantity DECIMAL(18,6) NOT NULL, used_quantity DECIMAL(18,6) NOT NULL, "
                + "reserved_quantity DECIMAL(18,6) NOT NULL)");
        jdbc.execute("CREATE TABLE material_consumptions ("
                + "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                + "production_batch_id BIGINT, batch_id VARCHAR(191) NOT NULL, quantity DECIMAL(18,6) NOT NULL, "
                + "unit_price DECIMAL(10,2) NOT NULL, total_cost DECIMAL(10,2) NOT NULL, "
                + "consumption_time TIMESTAMP NOT NULL, consumed_at TIMESTAMP, recorded_by BIGINT NOT NULL, "
                + "source_type VARCHAR(20))");
        jdbc.execute("CREATE TABLE production_batches ("
                + "id BIGINT PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, material_cost DECIMAL(10,2))");

        jdbc.update("INSERT INTO material_batches(id,factory_id,receipt_quantity,used_quantity,reserved_quantity) "
                        + "VALUES (?,?,?,?,?)", MATERIAL_BATCH_ID, FACTORY, new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        jdbc.update("INSERT INTO production_batches(id,factory_id,material_cost) VALUES (?,?,?)",
                BATCH_ID, FACTORY, new BigDecimal("99.00"));

        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setCreatedBy(7L);
        MaterialBatch materialBatch = new MaterialBatch();
        materialBatch.setId(MATERIAL_BATCH_ID);
        materialBatch.setFactoryId(FACTORY);
        materialBatch.setReceiptQuantity(new BigDecimal("100"));
        materialBatch.setUsedQuantity(BigDecimal.ZERO);
        materialBatch.setReservedQuantity(BigDecimal.ZERO);
        materialBatch.setUnitPrice(new BigDecimal("10.00"));

        when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryId(MATERIAL_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(materialBatch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(invocation -> {
            MaterialBatch saved = invocation.getArgument(0);
            jdbc.update("UPDATE material_batches SET used_quantity=? WHERE id=? AND factory_id=?",
                    saved.getUsedQuantity(), saved.getId(), FACTORY);
            return saved;
        });
        when(materialConsumptionRepository.save(any(MaterialConsumption.class))).thenAnswer(invocation -> {
            MaterialConsumption saved = invocation.getArgument(0);
            jdbc.update("INSERT INTO material_consumptions(factory_id,production_batch_id,batch_id,quantity,"
                            + "unit_price,total_cost,consumption_time,consumed_at,recorded_by,source_type) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)", saved.getFactoryId(), saved.getProductionBatchId(),
                    saved.getBatchId(), saved.getQuantity(), saved.getUnitPrice(), saved.getTotalCost(),
                    saved.getConsumptionTime(), saved.getConsumedAt(), saved.getRecordedBy(), saved.getSourceType());
            return saved;
        });
        // Fault injection occurs only after stock and consumption writes.
        when(materialConsumptionRepository.findByProductionBatchIdAndFactoryId(anyLong(), eq(FACTORY)))
                .thenThrow(new IllegalStateException("canonical cost read failure"));

        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(database),
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(processingService);
        proxyFactory.setInterfaces(ProcessingService.class);
        proxyFactory.addAdvice(interceptor);
        transactionalService = (ProcessingService) proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    @DisplayName("manual writer rollback restores stock, consumption rows, and batch cost after canonical read failure")
    void rollbackCoversAllManualWriterWrites() {
        assertTrue(AopUtils.isAopProxy(transactionalService), "writer must run through Spring AOP proxy");

        assertThrows(IllegalStateException.class,
                () -> transactionalService.recordMaterialConsumption(
                        FACTORY, Long.toString(BATCH_ID),
                        List.of(Map.of("materialBatchId", MATERIAL_BATCH_ID, "quantity", "1")), 7L));

        assertEquals(0, jdbc.queryForObject("SELECT used_quantity FROM material_batches WHERE id=?",
                BigDecimal.class, MATERIAL_BATCH_ID).compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_consumptions WHERE production_batch_id=?",
                Integer.class, BATCH_ID));
        assertEquals(0, jdbc.queryForObject("SELECT material_cost FROM production_batches WHERE id=?",
                BigDecimal.class, BATCH_ID).compareTo(new BigDecimal("99.00")));
    }
}

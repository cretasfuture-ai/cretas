package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.quality.FactoryRecallService;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 PostgreSQL + 真 Hibernate 验证质量处置到成品库存的计划/SKU隔离边界。
 *
 * <p>这些场景不能用 Mockito 库存替代：成品筛选、放行记录排序、JPA 状态映射以及需检策略都必须在真实数据库中读回。
 */
@SpringBootTest(classes = QualityDispositionInventoryPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class QualityDispositionInventoryPostgresContractTest {

    private static final String FACTORY = "S3-QC-PG";
    private static final String OTHER_FACTORY = "S3-QC-OTHER";
    private static final Long USER_ID = 7001L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({QualityDispositionInventoryService.class, QcInspectionPolicy.class})
    static class Runtime {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /** 召回记录查询本身在 FactoryRecallServiceContractTest 里测; 这里桩它的答案, 验证放行按答案处理。其它仓储和策略均为真实 bean。 */
    @MockBean
    FactoryRecallService factoryRecallService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QualityDispositionInventoryService disposition;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired ProductionBatchRepository productionBatches;
    @Autowired FinishedGoodsBatchRepository finishedGoodsBatches;
    @Autowired QcReleaseRecordRepository releaseRecords;
    @Autowired ProductTypeRepository productTypes;
    @Autowired QualityInspectionRepository inspections;
    @Autowired ProcessTimeLimitEventRepository timeLimitEvents;
    @Autowired BatchLineageEdgeRepository lineageEdges;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // This disposable target intentionally uses entity-generated DDL without production FK fixtures.
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    private record Fixture(String factoryId, String planId, String productId, Long productionBatchId,
                           String materialId, String inspectionId, String firstFgId) {
    }

    @Test
    void samePlanAndSkuReleasesBothDefectiveFinishedGoods() {
        Fixture f = fixture(false);
        String secondFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(2);
        assertThat(result.getFinishedGoodsHeld()).isZero();
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(secondFg, FinishedGoodsBatch.Status.AVAILABLE);
        assertDbMaterial(f.materialId(), MaterialBatchStatus.AVAILABLE);
        assertInspectionRetained(f.inspectionId());
    }

    /**
     * Q2 改写 (Owner 2026-11-09 裁定「计划里所有批次都解除后, 一次放行这个计划的全部成品」): 原名
     * differentSkuAndDifferentFactoryRemainProtected, 钉的是「同计划其他 SKU 不放」—— 联产计划的副产品同样是
     * 这个计划的货, 现在一起放行。跨工厂保护不变: 别厂同计划号的成品一行不动 (阴性对照)。
     */
    @Test
    void otherSkuOfThePlanIsReleasedButOtherFactoryStaysProtected() {
        Fixture f = fixture(false);
        String otherSku = "PT-OTHER-" + suffix();
        String sameFactoryOtherSku = saveFinishedGoods(f.factoryId(), f.planId(), otherSku, "FG-OTHER-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        String otherFactory = saveFinishedGoods(OTHER_FACTORY, f.planId(), f.productId(), "FG-CROSS-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(2);
        assertThat(result.getFinishedGoodsHeld()).isZero();
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(sameFactoryOtherSku, FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(otherFactory, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbMaterial(f.materialId(), MaterialBatchStatus.AVAILABLE);
        assertInspectionRetained(f.inspectionId());
    }

    @Test
    void lockedSiblingKeepsSamePlanGoodsQuarantined() {
        Fixture f = fixture(false);
        Long siblingId = tx.execute(state -> productionBatches.saveAndFlush(productionBatch(
                f.factoryId(), f.planId(), f.productId(), QualityStatus.FAILED,
                "PB-SIBLING-" + suffix())).getId());
        assertThat(siblingId).isNotNull().isNotEqualTo(f.productionBatchId());
        assertDbQualityStatus(siblingId, QualityStatus.FAILED);
        String siblingFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-SIBLING-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isZero();
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(2);
        assertThat(result.getFinishedGoodsNotice()).contains("还有 1 个批次没有解除隔离");
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbFinishedGoods(siblingFg, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbQualityStatus(f.productionBatchId(), QualityStatus.PASSED);
        assertDbQualityStatus(siblingId, QualityStatus.FAILED);
        assertDbMaterial(f.materialId(), MaterialBatchStatus.AVAILABLE);
        assertInspectionRetained(f.inspectionId());
    }

    @Test
    void returnChildAndLatestRejectedQcReleaseStayDefective() {
        Fixture f = fixture(false);
        String returnFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "RTN-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        String rejectedFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-REJECTED-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        saveRejectedRelease(f.factoryId(), rejectedFg);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(1);
        // Return children are independent stock: neither released nor counted in this disposition.
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(1);
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(returnFg, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbFinishedGoods(rejectedFg, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbMaterial(f.materialId(), MaterialBatchStatus.AVAILABLE);
        assertInspectionRetained(f.inspectionId());
    }

    /** Q7 (2026-11-09): 工序时限复检判不合格写下的 DEFECTIVE 成品, 计划放行时读回 REJECTED 事件留在隔离。 */
    @Test
    void timeLimitRejectedFinishedGoodsStayDefective() {
        Fixture f = fixture(false);
        String expiredFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-EXPIRED-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        saveTimeLimitEvent(f.factoryId(), expiredFg, ProcessTimeLimitEvent.Status.REJECTED);
        // 阴性对照: 复检放行 (RELEASED) 的事件不是判废, 不挡放行
        String recheckedFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-RECHECKED-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        saveTimeLimitEvent(f.factoryId(), recheckedFg, ProcessTimeLimitEvent.Status.RELEASED);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(2);
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(1);
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(recheckedFg, FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(expiredFg, FinishedGoodsBatch.Status.DEFECTIVE);
    }

    private void saveTimeLimitEvent(String factoryId, String finishedGoodsId, String status) {
        tx.executeWithoutResult(state -> {
            LocalDateTime start = LocalDateTime.of(2026, 11, 9, 8, 0);
            timeLimitEvents.saveAndFlush(ProcessTimeLimitEvent.builder()
                    .factoryId(factoryId)
                    .targetType(ProcessTimeLimitService.TargetType.FINISHED_GOODS)
                    .targetId(finishedGoodsId)
                    .limitMinutes(30)
                    .startEvent("OUTPUT_POSTED")
                    .mixRule("EARLIEST")
                    .startAt(start)
                    .deadlineAt(start.plusMinutes(30))
                    .blockedAt(start.plusMinutes(45))
                    .coveredUntil(start.plusMinutes(45))
                    .status(status)
                    .conclusion("复检结论")
                    .build());
        });
    }

    /** Q4: 没有经过复核的放行不翻转任何成品 (批次状态照写)。 */
    @Test
    void unreviewedReleaseLeavesAllFinishedGoodsDefective() {
        Fixture f = fixture(false);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f, false);

        assertThat(result.getFinishedGoodsReleased()).isZero();
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(1);
        assertThat(result.getFinishedGoodsNotice()).contains("复核");
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbQualityStatus(f.productionBatchId(), QualityStatus.PASSED);
    }

    /** Q4: 同计划兄弟批有召回记录时, 被召回冻结的成品 (批号 = 兄弟批号) 读回仍 DEFECTIVE; FROZEN 成品不受影响。 */
    @Test
    void siblingRecallAndFrozenGoodsStayPut() {
        Fixture f = fixture(false);
        String siblingNumber = "PB-RECALLED-" + suffix();
        Long siblingId = tx.execute(state -> productionBatches.saveAndFlush(productionBatch(
                f.factoryId(), f.planId(), f.productId(), QualityStatus.PASSED, siblingNumber)).getId());
        String recalledFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), siblingNumber,
                FinishedGoodsBatch.Status.DEFECTIVE);
        String frozenFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-FROZEN-" + suffix(),
                FinishedGoodsBatch.Status.FROZEN);
        org.mockito.Mockito.when(factoryRecallService.hasRecallRecord(f.factoryId(), siblingId)).thenReturn(true);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isZero();
        assertThat(result.getFinishedGoodsNotice()).contains("召回");
        assertDbFinishedGoods(recalledFg, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbFinishedGoods(frozenFg, FinishedGoodsBatch.Status.FROZEN);
    }

    /** Q2: 报废兄弟批的成品 (真血缘边读回) 留在隔离, 其余放行; 报废不算「未解除」。 */
    @Test
    void scrappedSiblingGoodsStayButTheRestIsReleased() {
        Fixture f = fixture(false);
        Long scrappedId = tx.execute(state -> productionBatches.saveAndFlush(productionBatch(
                f.factoryId(), f.planId(), f.productId(), QualityStatus.SCRAPPED, "PB-SCRAPPED-" + suffix())).getId());
        String scrappedFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-SCRAPPED-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);
        saveEdge(f.factoryId(), f.productionBatchId(), f.firstFgId());
        saveEdge(f.factoryId(), scrappedId, scrappedFg);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(1);
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(1);
        assertThat(result.getFinishedGoodsNotice()).contains("报废批次的成品 1 个");
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.AVAILABLE);
        assertDbFinishedGoods(scrappedFg, FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbQualityStatus(scrappedId, QualityStatus.SCRAPPED);
    }

    /** Q2: 有报废兄弟批、没有血缘、批号对不上 —— 分不清归属, 整个计划不放 (读回仍 DEFECTIVE)。 */
    @Test
    void unattributableGoodsWithScrappedSiblingHoldTheWholePlan() {
        Fixture f = fixture(false);
        tx.execute(state -> productionBatches.saveAndFlush(productionBatch(
                f.factoryId(), f.planId(), f.productId(), QualityStatus.SCRAPPED, "PB-SCRAPPED-" + suffix())).getId());
        String otherFg = saveFinishedGoods(f.factoryId(), f.planId(), f.productId(), "FG-UNKNOWN-" + suffix(),
                FinishedGoodsBatch.Status.DEFECTIVE);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isZero();
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(2);
        assertThat(result.getFinishedGoodsNotice()).contains("分不清");
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.DEFECTIVE);
        assertDbFinishedGoods(otherFg, FinishedGoodsBatch.Status.DEFECTIVE);
    }

    /** Q2: 血缘指向别的计划的生产批次 —— 归属不明, 整个计划不放。 */
    @Test
    void lineagePointingOutsideThePlanHoldsEverything() {
        Fixture f = fixture(false);
        Long foreignBatch = tx.execute(state -> productionBatches.saveAndFlush(productionBatch(
                f.factoryId(), "PLAN-ELSEWHERE-" + suffix(), f.productId(), QualityStatus.FAILED,
                "PB-ELSEWHERE-" + suffix())).getId());
        saveEdge(f.factoryId(), foreignBatch, f.firstFgId());

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isZero();
        assertThat(result.getFinishedGoodsNotice()).contains("本计划以外");
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.DEFECTIVE);
    }

    private void saveEdge(String factoryId, Long productionBatchId, String finishedGoodsId) {
        tx.executeWithoutResult(state -> {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            edge.setEdgeType(LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED);
            edge.setSourceType(LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
            edge.setSourceId(String.valueOf(productionBatchId));
            edge.setTargetType(LineageEdgeRecorder.NODE_FINISHED_BATCH);
            edge.setTargetId(finishedGoodsId);
            edge.setQuantityUsed(BigDecimal.ONE);
            edge.setUnit("kg");
            edge.setEventTime(LocalDateTime.of(2026, 11, 9, 9, 0));
            lineageEdges.saveAndFlush(edge);
        });
    }

    @Test
    void inspectionRequiredProductReturnsToPendingInspection() {
        Fixture f = fixture(true);

        QualityDispositionInventoryService.InventoryDispositionResult result = release(f);

        assertThat(result.getFinishedGoodsReleased()).isEqualTo(1);
        assertThat(result.getFinishedGoodsHeld()).isZero();
        assertDbFinishedGoods(f.firstFgId(), FinishedGoodsBatch.Status.PENDING_INSPECTION);
        assertDbQualityStatus(f.productionBatchId(), QualityStatus.PASSED);
        assertDbMaterial(f.materialId(), MaterialBatchStatus.AVAILABLE);
        assertInspectionRetained(f.inspectionId());
    }

    private Fixture fixture(boolean inspectionRequired) {
        String token = suffix();
        String planId = "PLAN-QC-" + token;
        String productId = "PT-QC-" + token;
        String materialId = "MB-QC-" + token;
        String inspectionId = "QI-QC-" + token;

        return tx.execute(state -> {
            ProductType product = new ProductType();
            product.setId(productId);
            product.setFactoryId(FACTORY);
            product.setCode("SKU-QC-" + token);
            product.setName("质量处置测试品");
            product.setUnit("kg");
            product.setInspectionRequired(inspectionRequired);
            product.setIsActive(true);
            productTypes.saveAndFlush(product);

            com.cretas.aims.entity.MaterialBatch material = new com.cretas.aims.entity.MaterialBatch();
            material.setId(materialId);
            material.setFactoryId(FACTORY);
            material.setBatchNumber("MBN-QC-" + token);
            material.setReceiptDate(LocalDate.now());
            material.setWarehouseId("WH-QC");
            material.setReceiptQuantity(BigDecimal.ONE);
            material.setQuantityUnit("kg");
            material.setUsedQuantity(BigDecimal.ZERO);
            material.setReservedQuantity(BigDecimal.ZERO);
            material.setStatus(MaterialBatchStatus.DEFECTIVE);
            material.setCreatedBy(USER_ID);
            materialBatches.saveAndFlush(material);

            ProductionBatch batch = productionBatch(FACTORY, planId, productId, QualityStatus.FAILED,
                    "PB-QC-" + token);
            batch = productionBatches.saveAndFlush(batch);

            String fgId = saveFinishedGoods(FACTORY, planId, productId, "FG-QC-" + token,
                    FinishedGoodsBatch.Status.DEFECTIVE);

            QualityInspection inspection = new QualityInspection();
            inspection.setId(inspectionId);
            inspection.setFactoryId(FACTORY);
            inspection.setProductionBatchId(batch.getId());
            inspection.setMaterialBatchId(materialId);
            inspection.setInspectorId(USER_ID);
            inspection.setInspectionDate(LocalDate.now());
            inspection.setSampleSize(BigDecimal.ONE);
            inspection.setPassCount(BigDecimal.ZERO);
            inspection.setFailCount(BigDecimal.ONE);
            inspection.setPassRate(BigDecimal.ZERO);
            inspection.setResult("FAIL");
            inspections.saveAndFlush(inspection);
            return new Fixture(FACTORY, planId, productId, batch.getId(), materialId, inspectionId, fgId);
        });
    }

    private ProductionBatch productionBatch(String factoryId, String planId, String productId,
                                             QualityStatus qualityStatus, String batchNumber) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(batchNumber);
        batch.setProductionPlanId(planId);
        batch.setProductTypeId(productId);
        batch.setQuantity(BigDecimal.ONE);
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        batch.setQualityStatus(qualityStatus);
        batch.setCostIncomplete(false);
        batch.setBatchType("REGULAR");
        return batch;
    }

    private String saveFinishedGoods(String factoryId, String planId, String productId, String batchNumber,
                                     String status) {
        return tx.execute(state -> {
            FinishedGoodsBatch fg = new FinishedGoodsBatch();
            String id = "FG-ID-" + suffix();
            fg.setId(id);
            fg.setFactoryId(factoryId);
            fg.setBatchNumber(batchNumber);
            fg.setProductTypeId(productId);
            fg.setProducedQuantity(BigDecimal.ONE);
            fg.setShippedQuantity(BigDecimal.ZERO);
            fg.setReservedQuantity(BigDecimal.ZERO);
            fg.setUnit("kg");
            fg.setProductionPlanId(planId);
            fg.setWarehouseId("WH-QC");
            fg.setStatus(status);
            finishedGoodsBatches.saveAndFlush(fg);
            return id;
        });
    }

    private void saveRejectedRelease(String factoryId, String targetId) {
        tx.executeWithoutResult(state -> {
            QcReleaseRecord row = new QcReleaseRecord();
            row.setFactoryId(factoryId);
            row.setTargetType(QcReleaseRecord.TargetType.FINISHED_GOODS);
            row.setTargetId(targetId);
            row.setBatchNumber(targetId);
            row.setProductTypeId("unused");
            row.setQuantity(BigDecimal.ONE);
            row.setUnit("kg");
            row.setDecision(QcReleaseRecord.Decision.REJECTED);
            row.setConclusion("质检放行拒绝");
            row.setReleasedBy(USER_ID);
            row.setReleasedAt(LocalDateTime.now());
            releaseRecords.saveAndFlush(row);
        });
    }

    /** 复核通过后的放行 (executeApprovedDisposition 走的就是 reviewed=true)。 */
    private QualityDispositionInventoryService.InventoryDispositionResult release(Fixture fixture) {
        return release(fixture, true);
    }

    private QualityDispositionInventoryService.InventoryDispositionResult release(Fixture fixture, boolean reviewed) {
        return tx.execute(state -> disposition.applyDisposition(
                inspections.findById(fixture.inspectionId()).orElseThrow(), DispositionAction.RELEASE, reviewed));
    }

    private void assertDbFinishedGoods(String id, String expectedStatus) {
        assertThat(fgStatus(id)).isEqualTo(expectedStatus);
        assertThat(jdbc.queryForObject("select produced_quantity from finished_goods_batches where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("1");
    }

    private String fgStatus(String id) {
        return jdbc.queryForObject("select status from finished_goods_batches where id = ?", String.class, id);
    }

    private void assertDbQualityStatus(Long id, QualityStatus expected) {
        assertThat(jdbc.queryForObject("select quality_status from production_batches where id = ?",
                String.class, id)).isEqualTo(expected.name());
    }

    private void assertDbMaterial(String id, MaterialBatchStatus expected) {
        assertThat(jdbc.queryForObject("select status from material_batches where id = ?", String.class, id))
                .isEqualTo(expected.name());
    }

    private void assertInspectionRetained(String id) {
        assertThat(jdbc.queryForObject("select count(*) from quality_inspections where id = ? and factory_id = ?",
                Long.class, id, FACTORY))
                .isEqualTo(1L);
        var original = jdbc.queryForMap("select result,sample_size,pass_count,fail_count,pass_rate "
                + "from quality_inspections where id = ? and factory_id = ?", id, FACTORY);
        assertThat(original.get("result")).isEqualTo("FAIL");
        assertThat((BigDecimal) original.get("sample_size")).isEqualByComparingTo("1");
        assertThat((BigDecimal) original.get("pass_count")).isEqualByComparingTo("0");
        assertThat((BigDecimal) original.get("fail_count")).isEqualByComparingTo("1");
        assertThat((BigDecimal) original.get("pass_rate")).isEqualByComparingTo("0");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

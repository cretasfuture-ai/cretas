package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 {@link MaterialConsumptionRepository#findByFactoryIdAndPlanProductionBatchesIncludingWip}
 * (bug fix 2026-09-04, prod 只读探针实测 {@code PLAN-1788305552728-D94F7E22} 复现)。
 *
 * <p>见方法自身 javadoc 与 {@link com.cretas.aims.service.yield.OrderCostBreakdownService
 * #settlementConsumptionsFor} 类 javadoc「2026-09-04 缺陷订正」—— 本闸钉四条：
 * <ol>
 *   <li>T1: CLERK_WIP 批次自己的消耗行也命中 (这正是本方法存在的理由 —— 既有
 *       {@link ProductionBatchRepository#findByFactoryIdAndProductionPlanIdIn} 故意排除
 *       CLERK_WIP, 不能复用它)</li>
 *   <li>T2: {@code production_plan_id} 为 null (WIP 道消耗行的真实形态) 一样命中 —— 证明本查询
 *       没有按 plan_id 过滤 (那样会漏掉恰恰是要补上的那一半)</li>
 *   <li>T3: 跨工厂/跨计划隔离</li>
 *   <li>T4: 阴性 —— 无匹配批次时返回空, 不报错</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("MaterialConsumptionRepository#findByFactoryIdAndPlanProductionBatchesIncludingWip (缺陷订正 2026-09-04)")
class MaterialConsumptionPlanWideIncludingWipRepositoryQueryValidationTest {

    @Autowired private MaterialConsumptionRepository consumptionRepo;
    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String FACTORY_ID = "IT-PWWIP-F";
    private static final String OTHER_FACTORY_ID = "IT-PWWIP-OTHER-F";

    private String planId;

    @BeforeEach
    void setUp() {
        // production_plans.created_by 有 FK 指向 users(id); 测试不建真实用户,
        // 与 MaterialConsumptionStampSettledForPlanTest 同一条防御。
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        planId = seedPlan(FACTORY_ID, "IT-PWWIP-PLAN");
        long fgBatchId = seedBatch(FACTORY_ID, "REGULAR", planId);
        long wipBatchId = seedBatch(FACTORY_ID, "CLERK_WIP", planId);

        // FG 批次自己的消耗行 (isFinished=true → production_plan_id 非 null)。
        seedConsumption(FACTORY_ID, fgBatchId, planId, "MB-FG-CONSUMED", new BigDecimal("5.00"));
        // 🔴 WIP 批次自己的消耗行 —— production_plan_id 故意为 null
        // (ClerkProcessEntryServiceImpl#consumptionPlanId 对非末道), 但 production_batch_id 恒有值。
        seedConsumption(FACTORY_ID, wipBatchId, null, "MB-WIP-CONSUMED", new BigDecimal("20.00"));

        // 另一个工厂/计划的消耗行 —— 不该串进来。
        String otherPlanId = seedPlan(OTHER_FACTORY_ID, "IT-PWWIP-OTHER-PLAN");
        long otherBatchId = seedBatch(OTHER_FACTORY_ID, "CLERK_WIP", otherPlanId);
        seedConsumption(OTHER_FACTORY_ID, otherBatchId, otherPlanId, "MB-OTHER-FACTORY", new BigDecimal("99.00"));
    }

    @Test
    @DisplayName("T1: CLERK_WIP 批次自己的消耗行命中 (existing findByFactoryIdAndProductionPlanIdIn 故意排除它)")
    void includesClerkWipBatchConsumptions() {
        List<MaterialConsumption> got =
                consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(FACTORY_ID, planId);

        assertThat(got).extracting(MaterialConsumption::getBatchId)
                .containsExactlyInAnyOrder("MB-FG-CONSUMED", "MB-WIP-CONSUMED");
    }

    @Test
    @DisplayName("T2: production_plan_id 为 null (WIP 道真实形态) 一样命中 —— 不能按 plan_id 过滤")
    void matchesRowsWithNullProductionPlanId() {
        List<MaterialConsumption> got =
                consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(FACTORY_ID, planId);

        MaterialConsumption wipRow = got.stream()
                .filter(c -> "MB-WIP-CONSUMED".equals(c.getBatchId())).findFirst().orElseThrow();
        assertThat(wipRow.getProductionPlanId()).isNull();
    }

    @Test
    @DisplayName("T3: 跨工厂/跨计划隔离 —— 不会把别的工厂/计划的消耗行读进来")
    void isFactoryAndPlanScoped() {
        List<MaterialConsumption> got =
                consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(FACTORY_ID, planId);

        assertThat(got).extracting(MaterialConsumption::getBatchId)
                .doesNotContain("MB-OTHER-FACTORY");
    }

    @Test
    @DisplayName("T4: 阴性 —— 计划下无批次时返回空, 不报错")
    void noBatchesReturnsEmpty() {
        String emptyPlanId = seedPlan(FACTORY_ID, "IT-PWWIP-EMPTY-PLAN");
        List<MaterialConsumption> got =
                consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(FACTORY_ID, emptyPlanId);
        assertThat(got).isEmpty();
    }

    // ── helpers ──

    private String seedPlan(String factoryId, String planNumber) {
        String id = "IT-PWWIP-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(planNumber);
        plan.setProductTypeId("IT-PWWIP-PT");
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        plan.setCreatedBy(1L);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(plan);
        return id;
    }

    private long seedBatch(String factoryId, String batchType, String productionPlanId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("IT-PWWIP-B-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setProductTypeId("IT-PWWIP-PT");
        batch.setQuantity(new BigDecimal("100.00"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setBatchType(batchType);
        batch.setProductionPlanId(productionPlanId);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        return batchRepo.saveAndFlush(batch).getId();
    }

    private void seedConsumption(String factoryId, long productionBatchId, String productionPlanId,
                                 String materialBatchId, BigDecimal qty) {
        MaterialConsumption mc = new MaterialConsumption();
        mc.setFactoryId(factoryId);
        mc.setProductionPlanId(productionPlanId);
        mc.setProductionBatchId(productionBatchId);
        mc.setBatchId(materialBatchId);
        mc.setQuantity(qty);
        mc.setUnitPrice(new BigDecimal("10.00"));
        mc.setTotalCost(qty.multiply(new BigDecimal("10.00")));
        mc.setConsumptionTime(LocalDateTime.now());
        mc.setRecordedBy(1L);
        consumptionRepo.saveAndFlush(mc);
    }
}

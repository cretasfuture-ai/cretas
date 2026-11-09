package com.cretas.aims.integration;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 真 PostgreSQL: 计划成本差异单列回料 (食品 DAG 缺口 09 补齐, 2026-11-09)。
 *
 * <p>为什么要真库: 回料差读的是 jsonb —— 报工行 {@code row_payload.upstreamSources} 与追溯边
 * {@code batch_lineage_edges.meta} (rework / sourceWipNo) —— 外加回料池 {@code semi_finished_inventory.unit_cost}
 * 与锁定配方明细。H2 / Mockito 证明不了这些 jsonb 经 Hibernate 读回来的形状 (Boolean 还是字符串),
 * 也证明不了单价真的是从回料池那一行读到的。单价/折算走真实 {@link WipInventoryServiceImpl} (与成本核算同一个实现)。</p>
 *
 * <p>schema 由实体 create-drop 建, 外键全部摘掉 (一次性库, 只为不铺整棵依赖树), 不代表生产迁移。
 * 开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (见 {@link DisposablePostgresTargetGuard})。</p>
 */
@SpringBootTest(classes = PlanCostVarianceReworkPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PlanCostVarianceReworkPostgresContractTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({PlanCostVarianceService.class, WipInventoryServiceImpl.class})
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /** 产出 kg / 人工: 成本核算内核在这里不是被测对象 (回料走的是另一条读数路径)。 */
    @MockBean OrderCostBreakdownService orderCostBreakdownService;
    @MockBean UnitConversionService unitConversionService;
    /** WipInventoryServiceImpl 构造依赖, 本测试只用它的两个只读方法。 */
    @MockBean ProductFamilyResolver productFamilyResolver;
    @MockBean IntermediateOwnershipGuard intermediateOwnershipGuard;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PlanCostVarianceService service;
    @Autowired ProductionPlanRepository plans;
    @Autowired PlanStandardCostLineRepository standards;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository bomItems;

    private TransactionTemplate tx;
    private String factory;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        factory = "RW-" + UUID.randomUUID().toString().substring(0, 8);
        OrderCostBreakdownDTO breakdown = new OrderCostBreakdownDTO();
        breakdown.setConvertedOutputKg(new BigDecimal("80"));
        when(orderCostBreakdownService.computeByPlan(anyString(), anyString(), anyBoolean())).thenReturn(breakdown);
    }

    @Test
    void reworkFromAnotherPlanIsPricedFromThePoolRowAndTracedToTheProducerBatch() {
        String producerPlan = plan("A", false);
        long producerBatch = batch(producerPlan, "PB-A-" + factory);
        String pool = "CLK-SEMI-A-" + factory;
        reworkPool(pool, "12.9375", true);
        intakeRow(producerPlan, pool);

        String consumerPlan = plan("B", false);
        long consumerBatch = batch(consumerPlan, "PB-B-" + factory);
        // 阴性对照: 同一行里还有一条普通半成品投料 (非回料池), 不能算进回料
        String ordinary = "CLK-SEMI-ORD-" + factory;
        reworkPool(ordinary, "99", false);
        feedRow(consumerPlan, consumerBatch, "[{\"semiFinished\":true,\"sourceBatchNumber\":\"" + pool
                + "\",\"feedQuantityKg\":2.5},{\"semiFinished\":true,\"sourceBatchNumber\":\"" + ordinary
                + "\",\"feedQuantityKg\":7}]");
        reworkEdge(producerBatch, consumerBatch, pool, "2.5");

        PlanCostVarianceDTO v = service.computeForPlan(factory, consumerPlan, false);

        List<PlanCostVarianceDTO.Line> reworkLines = v.getLines().stream()
                .filter(l -> "REWORK".equals(l.getLineType())).toList();
        assertThat(reworkLines).as("只有回料池那一条, 普通半成品不算").hasSize(1);
        PlanCostVarianceDTO.Line line = reworkLines.getFirst();
        assertThat(line.getReworkPoolNo()).isEqualTo(pool);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_COMPARABLE);
        assertThat(line.getActualQuantity()).isEqualByComparingTo("2.5");
        assertThat(line.getActualUnitPrice()).as("单价就是回料池那一行的 unit_cost").isEqualByComparingTo("12.9375");
        assertThat(line.getActualAmount()).isEqualByComparingTo("32.34");
        assertThat(line.getReworkVariance()).isEqualByComparingTo("32.34");
        assertThat(line.getReworkSources()).hasSize(1);
        assertThat(line.getReworkSources().getFirst().getProducerBatchNumber()).isEqualTo("PB-A-" + factory);
        assertThat(line.getReworkSources().getFirst().getProducerPlanNumber()).isEqualTo("PN-A-" + factory);
        assertThat(line.getReworkSources().getFirst().getQuantity()).isEqualByComparingTo("2.5");

        assertThat(v.getReworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_COMPARABLE);
        assertThat(v.getReworkVariance()).isEqualByComparingTo("32.34");
        assertThat(v.getReworkQuantityText()).isEqualTo("2.5 kg");
        // F006 形态: 配方没有出成率 ⇒ 主料用量差算不出; 回料照样可比, 并提示回料差单看会偏高
        assertThat(v.isComparable()).isTrue();
        assertThat(v.getTotalVariance()).as("唯一可比的一项就是回料").isEqualByComparingTo("32.34");
        assertThat(v.getRecoveredMaterialNote()).contains("回投 2.5 kg").contains("+¥32.34").contains("回料差单看会偏高");
    }

    @Test
    void planWithoutReworkSaysNoneAndHasNoReworkLine() {
        String p = plan("C", false);
        long b = batch(p, "PB-C-" + factory);
        feedRow(p, b, "[]");

        PlanCostVarianceDTO v = service.computeForPlan(factory, p, false);

        assertThat(v.getReworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_NONE);
        assertThat(v.getReworkVariance()).as("⛔ 不报「回料差 ¥0」").isNull();
        assertThat(v.getRecoveredMaterialNote()).isEqualTo("本计划没有回投回料");
        assertThat(v.getLines()).noneMatch(l -> "REWORK".equals(l.getLineType()));
    }

    @Test
    void ownPlanReworkIsInternalAndUnknownPoolCostIsIncomparable() {
        String own = plan("D", false);
        long ownBatch = batch(own, "PB-D-" + factory);
        String ownPool = "CLK-SEMI-D-" + factory;
        reworkPool(ownPool, "20", true);
        intakeRow(own, ownPool);

        String other = plan("E", false);
        String unpricedPool = "CLK-SEMI-E-" + factory;
        reworkPool(unpricedPool, null, true);
        intakeRow(other, unpricedPool);

        feedRow(own, ownBatch, "[{\"semiFinished\":true,\"sourceBatchNumber\":\"" + ownPool
                + "\",\"feedQuantityKg\":3},{\"semiFinished\":true,\"sourceBatchNumber\":\"" + unpricedPool
                + "\",\"feedQuantityKg\":1}]");

        PlanCostVarianceDTO v = service.computeForPlan(factory, own, false);

        PlanCostVarianceDTO.Line internal = reworkLine(v, ownPool);
        assertThat(internal.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INTERNAL);
        assertThat(internal.getActualAmount()).isNull();
        PlanCostVarianceDTO.Line unpriced = reworkLine(v, unpricedPool);
        assertThat(unpriced.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(unpriced.getReasons()).anyMatch(r -> r.contains("回料池没有单位成本"));
        assertThat(unpriced.getActualAmount()).as("⛔ 不是 ¥0").isNull();
        assertThat(v.getReworkStatus()).isEqualTo(PlanCostVarianceDTO.REWORK_INCOMPARABLE);
        assertThat(v.getReworkVariance()).isNull();
    }

    @Test
    void recipeWithReworkRowMakesReworkIncomparable() {
        String p = plan("F", true);
        long b = batch(p, "PB-F-" + factory);
        String producer = plan("G", false);
        String pool = "CLK-SEMI-G-" + factory;
        reworkPool(pool, "10", true);
        intakeRow(producer, pool);
        feedRow(p, b, "[{\"semiFinished\":true,\"sourceBatchNumber\":\"" + pool + "\",\"feedQuantityKg\":2}]");

        PlanCostVarianceDTO v = service.computeForPlan(factory, p, false);

        PlanCostVarianceDTO.Line line = reworkLine(v, pool);
        assertThat(line.getStatus()).isEqualTo(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        assertThat(line.getReasons()).anyMatch(r -> r.contains("锁定配方登记了回料用量"));
        assertThat(line.getActualAmount()).isEqualByComparingTo("20");
        assertThat(v.getReworkVariance()).isNull();
    }

    private static PlanCostVarianceDTO.Line reworkLine(PlanCostVarianceDTO v, String pool) {
        return v.getLines().stream().filter(l -> pool.equals(l.getReworkPoolNo())).findFirst().orElseThrow();
    }

    // ---- fixtures ----

    /** 计划 + 锁定配方 (主料一行, 没有出成率 —— F006 形态) + 标准成本快照 (主料, 有单价)。 */
    private String plan(String tag, boolean recipeHasReworkRow) {
        String id = "PLAN-" + tag + "-" + factory;
        String recipe = "BOM-" + tag + "-" + factory;
        tx.executeWithoutResult(status -> {
            BomRecipe bom = new BomRecipe();
            bom.setId(recipe);
            bom.setFactoryId(factory);
            bom.setRecipeCode("BOM-CODE-" + tag + "-" + factory);
            bom.setProductTypeId("SKU-" + factory);
            bom.setProductName("rework product");
            bom.setVersion(1);
            bom.setIsCurrent(true);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("kg");
            bom.setStatus(BomRecipe.Status.ACTIVE);
            recipes.saveAndFlush(bom);
            bomItems.saveAndFlush(item(recipe, "MAT-" + factory, null, "RAW"));
            if (recipeHasReworkRow) {
                bomItems.saveAndFlush(item(recipe, null, "CLK-SEMI-REF-" + factory, "SEMI_FINISHED"));
            }

            ProductionPlan plan = new ProductionPlan();
            plan.setId(id);
            plan.setFactoryId(factory);
            plan.setPlanNumber("PN-" + tag + "-" + factory);
            plan.setProductTypeId("SKU-" + factory);
            plan.setPlannedQuantity(new BigDecimal("80"));
            plan.setActualQuantity(new BigDecimal("80"));
            plan.setPlannedUnit("kg");
            plan.setPlannedDate(LocalDate.now());
            plan.setBatchDate(LocalDate.now());
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(1L);
            plan.setSelectedBomRecipeId(recipe);
            plan.setSelectedBomVersion(1);
            plans.saveAndFlush(plan);
            standards.saveAndFlush(PlanStandardCostLine.builder()
                    .factoryId(factory).planId(id).lineType(PlanStandardCostLine.TYPE_MATERIAL)
                    .materialTypeId("MAT-" + factory).materialName("beef").materialCategory("RAW")
                    .productTypeId("SKU-" + factory).bomRecipeId(recipe).bomVersion(1)
                    .standardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE).standardUnit("kg")
                    .unitPrice(new BigDecimal("10")).priceUnit("kg")
                    .capturedAt(LocalDateTime.now()).build());
        });
        return id;
    }

    private BomRecipeItem item(String recipe, String material, String semiRef, String category) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipe);
        item.setFactoryId(factory);
        item.setMaterialTypeId(material);
        item.setSemiFinishedRefCode(semiRef);
        item.setMaterialName(material == null ? "回料" : "beef");
        item.setStandardQuantity(BigDecimal.ONE);
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setMaterialCategory(category);
        item.setSortOrder(semiRef == null ? 0 : 1);
        item.setIsOptional(false);
        item.setPerPortion(false);
        return item;
    }

    private long batch(String planId, String number) {
        Long id = jdbc.queryForObject("insert into production_batches (factory_id, batch_number, product_type_id, quantity, "
                        + "unit, status, cost_incomplete, batch_type, production_plan_id, created_at, updated_at) "
                        + "values (?, ?, ?, 80, 'kg', 'COMPLETED', false, 'NORMAL', ?, now(), now()) returning id",
                Long.class, factory, number, "SKU-" + factory, planId);
        return id;
    }

    private void reworkPool(String poolNo, String unitCost, boolean rework) {
        jdbc.update("insert into semi_finished_inventory (factory_id, intermediate_batch_no, product_type_id, is_rework, "
                        + "produced_quantity, consumed_quantity, available_quantity, adjustment_quantity, accumulated_cost, "
                        + "unit_cost, unit, status, ownership, version, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 10, 0, 10, 0, ?, ?, 'kg', 'AVAILABLE', 'COMPANY_OWNED', 0, now(), now())",
                factory, poolNo, "SKU-" + factory, rework,
                unitCost == null ? null : new BigDecimal(unitCost).multiply(BigDecimal.TEN),
                unitCost == null ? null : new BigDecimal(unitCost));
    }

    /** 回料池的入账报工行 (SAVED_SFI) —— 回料是哪张计划产的, 与先进先出归属读同一批行。 */
    private void intakeRow(String planId, String poolNo) {
        jdbc.update("insert into process_sheet_rows (factory_id, plan_id, process_code, client_row_id, batch_number, "
                        + "row_payload, row_status, submission_status, created_at, updated_at) "
                        + "values (?, ?, 'shuzhi', ?, ?, cast(? as jsonb), 'SAVED_SFI', 'SUBMITTED', now(), now())",
                factory, planId, "intake-" + poolNo + "#1", poolNo, "{\"outputQuantity\":10,\"upstreamSources\":[]}");
    }

    private void feedRow(String planId, long batchId, String upstreamJson) {
        jdbc.update("insert into process_sheet_rows (factory_id, plan_id, process_code, client_row_id, batch_id, "
                        + "row_payload, row_status, submission_status, created_at, updated_at) "
                        + "values (?, ?, 'xiuyou', ?, ?, cast(? as jsonb), 'SAVED', 'SUBMITTED', now(), now())",
                factory, planId, "feed-" + batchId, batchId,
                "{\"outputQuantity\":9,\"upstreamSources\":" + upstreamJson + "}");
    }

    /** 回料池先进先出归属写下的追溯边 (与 SemiFeedLineageRecorder#recordReworkPoolFeed 同形)。 */
    private void reworkEdge(long producerBatch, long targetBatch, String poolNo, String qty) {
        jdbc.update("insert into batch_lineage_edges (id, factory_id, edge_type, source_type, source_id, target_type, "
                        + "target_id, quantity_used, unit, event_time, meta, created_at, updated_at) values (?, ?, 'WIP_CONSUME', "
                        + "'PRODUCTION_BATCH', ?, 'PRODUCTION_BATCH', ?, ?, 'kg', now(), cast(? as jsonb), now(), now())",
                UUID.randomUUID().toString(), factory, String.valueOf(producerBatch), String.valueOf(targetBatch),
                new BigDecimal(qty), "{\"origin\":\"PROCESS_SHEET\",\"sourceWipNo\":\"" + poolNo
                        + "\",\"sourceResolvedBy\":\"REWORK_POOL_FIFO\",\"rework\":true}");
    }
}

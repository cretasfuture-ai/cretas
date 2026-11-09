package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.reversal.ReportReversalService;
import com.cretas.aims.service.reversal.ReversalConsumptionCleanupService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * N1 (2026-11-09) 真 PostgreSQL + 真 Hibernate + 真入口: 结单 → 撤回 → (改) → 再结单, 来源库存守恒。
 *
 * <p>结单按逐道录入行扣常驻半成品/成品投料; 撤回以前只还结单消耗行、被撤那一道提交时扣的料和入库明细,
 * 没撤的那几道的投料在重结时再扣一次, 被撤那一道结单时扣的量随行软删找不回。这里走真实的
 * {@code settleProduction} 与 {@code submitReversal/executeReversal}, 每一步之后核账:
 * <pre>
 *   半成品: 已消耗 + 余额 = 期初产出; 已消耗 = Σ出库流水 − Σ还回流水 = 当前有效投料之和
 *   成品:   在库产出 + 当前有效投料之和 = 期初产出
 * </pre>
 * 开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = SettlementReversalResettleLedgerPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SettlementReversalResettleLedgerPostgresContractTest {

    private static final String F = "N1-PG";
    private static final Long OPERATOR = 7L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class, SecondaryPlanFeedService.class,
            SettlementSemiStockingService.class, FinishedGoodsFeedServiceImpl.class,
            ReportReversalServiceImpl.class, ReversalConsumptionCleanupService.class})
    static class Runtime { }

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

    @MockBean ProductionPlanMapper planMapper;
    @MockBean SchedulingService schedulingService;
    @MockBean ExcelUtil excelUtil;
    @MockBean InventoryLowStockEventPublisher lowStockEvents;
    @MockBean ProductFamilyResolver familyResolver;
    @MockBean UnitContractService unitContractService;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;
    @MockBean NotificationService notificationService;
    @MockBean PermissionService permissionService;
    @MockBean UserRepository userRepository;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductionPlanService planService;
    @Autowired ReportReversalService reversalService;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired SemiFinishedInventoryRepository wips;
    @Autowired FinishedGoodsBatchRepository finishedGoods;
    @Autowired ProcessSheetRowRepository rows;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(ownershipGuard.ofClerkAnchor(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
        when(ownershipGuard.ofProductionBatch(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
        when(permissionService.hasPermission(any(), anyString())).thenReturn(true);
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
    }

    /** 一张计划: 第 1 道 (在制批次 W1) 投常驻半成品 10; 第 2 道 (正式批次 B, 出成品) 投半成品 30 + 成品 20。 */
    private record Fixture(String planId, Long stepOneBatchId, Long finalBatchId, Long semiId, String semiNo,
                           String finishedNo, Long stepOneRowId, Long finalRowId) {
    }

    private Fixture fixture(boolean withStepOne) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            SemiFinishedInventory semi = wips.save(SemiFinishedInventory.builder()
                    .factoryId(F).intermediateBatchNo("SRC-" + suffix).productTypeId("SEMI-1").unit("kg")
                    .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100")).processOrder(1)
                    .status(SemiFinishedInventory.Status.AVAILABLE).build());

            FinishedGoodsBatch fg = new FinishedGoodsBatch();
            fg.setFactoryId(F);
            fg.setBatchNumber("FGSRC-" + suffix);
            fg.setProductTypeId("FG-OLD");
            fg.setProducedQuantity(new BigDecimal("50"));
            fg.setUnit("kg");
            fg.setWarehouseId("WH");
            fg.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
            finishedGoods.save(fg);

            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-N1-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("N1-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("100"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            Long stepOneBatchId = null;
            Long stepOneRowId = null;
            if (withStepOne) {
                ProductionBatch w1 = batch(plan.getId(), "CLK-W-" + suffix, "CLERK_WIP");
                stepOneBatchId = w1.getId();
                stepOneRowId = rows.save(row(plan.getId(), w1, 1, "step1-" + suffix, false,
                        List.of(semiFeed(semi.getIntermediateBatchNo(), "10")))).getId();
            }
            ProductionBatch fin = batch(plan.getId(), "CLK-B-" + suffix, "REGULAR");
            Long finalRowId = rows.save(row(plan.getId(), fin, 2, "step2-" + suffix, true,
                    List.of(semiFeed(semi.getIntermediateBatchNo(), "30"),
                            finishedFeed(fg.getBatchNumber(), "20")))).getId();
            return new Fixture(plan.getId(), stepOneBatchId, fin.getId(), semi.getId(),
                    semi.getIntermediateBatchNo(), fg.getBatchNumber(), stepOneRowId, finalRowId);
        });
    }

    private ProductionBatch batch(String planId, String number, String type) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(F);
        batch.setBatchNumber(number);
        batch.setProductTypeId("FG-1");
        batch.setQuantity(new BigDecimal("90"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setProductionPlanId(planId);
        batch.setBatchType(type);
        return batches.save(batch);
    }

    private static ProcessSheetRowRequest.UpstreamRef semiFeed(String batchNo, String kg) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(batchNo);
        ref.setFeedQuantityKg(new BigDecimal(kg));
        ref.setSemiFinished(true);
        return ref;
    }

    private static ProcessSheetRowRequest.UpstreamRef finishedFeed(String batchNo, String kg) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(batchNo);
        ref.setFeedQuantityKg(new BigDecimal(kg));
        ref.setFinishedGoods(true);
        return ref;
    }

    private static ProcessSheetRow row(String planId, ProductionBatch batch, int order, String clientRowId,
                                       boolean finished, List<ProcessSheetRowRequest.UpstreamRef> feeds) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("step" + order);
        req.setProcessOrder(order);
        req.setProductTypeId("FG-1");
        req.setFinished(finished);
        req.setOutputQuantity(new BigDecimal("90"));
        req.setUnit("kg");
        req.setUpstreamSources(new ArrayList<>(feeds));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId(planId);
        row.setProcessCode("step" + order);
        row.setProcessOrder(order);
        row.setClientRowId(clientRowId);
        row.setBatchId(batch.getId());
        row.setBatchNumber(batch.getBatchNumber());
        row.setRowStatus("SAVED");
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        try {
            row.setRowPayload(JSON.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private static ProductionSettlementRequest settleRequest(String key) {
        return ProductionSettlementRequest.builder()
                .confirm(true)
                .manualOverride(true)
                .manualOverrideReason("N1 守恒测试")
                .idempotencyKey(key)
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>())
                .semiFinishedConsumptions(new ArrayList<>())
                .materialVarianceReason("无原料领用")
                .laborDeferredReason("工时稍后补录")
                .laborSegments(new ArrayList<>())
                .build();
    }

    private void settle(Fixture f, String key) {
        planService.settleProduction(F, f.planId(), settleRequest(key), OPERATOR);
    }

    private Long reverseFinalStep(Fixture f) {
        ReportReversalLog log = reversalService.submitReversal(F, f.finalBatchId(), OPERATOR, "改报工");
        if (log.getStatus() != ReportReversalLog.ReversalStatus.DONE) {
            reversalService.executeReversal(log.getId(), F);
        }
        return log.getId();
    }

    private BigDecimal semiConsumed(Fixture f) {
        return jdbc.queryForObject("select consumed_quantity from semi_finished_inventory where id = ?",
                BigDecimal.class, f.semiId());
    }

    private BigDecimal finishedProduced(Fixture f) {
        return jdbc.queryForObject("select produced_quantity from finished_goods_batches where batch_number = ? "
                + "and factory_id = ?", BigDecimal.class, f.finishedNo(), F);
    }

    /** 每一步之后的守恒: 期初 100 半成品 / 50 成品, 当前有效投料 semiOut / fgOut。 */
    private void assertConserved(Fixture f, String step, String semiOut, String fgOut) {
        BigDecimal produced = jdbc.queryForObject("select produced_quantity from semi_finished_inventory where id = ?",
                BigDecimal.class, f.semiId());
        BigDecimal consumed = semiConsumed(f);
        BigDecimal available = jdbc.queryForObject("select available_quantity from semi_finished_inventory where id = ?",
                BigDecimal.class, f.semiId());
        BigDecimal ledgerOut = jdbc.queryForObject("select coalesce(sum(-quantity), 0) from semi_finished_inventory_transactions "
                + "where semi_finished_id = ? and txn_type = 'OUT'", BigDecimal.class, f.semiId());
        BigDecimal ledgerBack = jdbc.queryForObject("select coalesce(sum(quantity), 0) from semi_finished_inventory_transactions "
                + "where semi_finished_id = ? and txn_type = 'REVERSE' and quantity > 0", BigDecimal.class, f.semiId());
        assertThat(produced).as(step + " 半成品产出不动").isEqualByComparingTo("100");
        assertThat(consumed.add(available)).as(step + " 半成品 已消耗 + 余额 = 期初").isEqualByComparingTo(produced);
        assertThat(ledgerOut.subtract(ledgerBack)).as(step + " 半成品 流水净出 = 已消耗").isEqualByComparingTo(consumed);
        assertThat(consumed).as(step + " 半成品 只扣当前有效投料").isEqualByComparingTo(semiOut);
        assertThat(finishedProduced(f).add(new BigDecimal(fgOut))).as(step + " 成品 在库 + 有效投料 = 期初")
                .isEqualByComparingTo("50");
    }

    private LocalDateTime stamp(Long rowId) {
        return jdbc.queryForObject("select interim_settled_at from process_sheet_rows where id = ?",
                LocalDateTime.class, rowId);
    }

    @Test
    void reverseOneStepEditAndResettleDeductsEachLiveFeedExactlyOnce() {
        Fixture f = fixture(true);
        assertConserved(f, "开始", "0", "0");

        settle(f, "k1-" + f.planId());
        assertConserved(f, "第一次结单", "40", "20");
        assertThat(jdbc.queryForObject("select stock_feed_detail from production_settlements where production_plan_id = ? "
                + "and deleted_at is null", String.class, f.planId()))
                .as("结单逐笔记下扣了什么").contains(f.semiNo()).contains(f.finishedNo());
        assertThat(stamp(f.stepOneRowId())).as("结单给没撤的那道盖戳").isNotNull();

        reverseFinalStep(f);
        assertConserved(f, "撤回第 2 道", "0", "0");
        assertThat(stamp(f.stepOneRowId())).as("没撤的那道重新待扣").isNull();
        assertThat(jdbc.queryForObject("select status from production_plans where id = ?", String.class, f.planId()))
                .isEqualTo("IN_PROGRESS");

        // 改: 第 2 道重新录入, 半成品投料从 30 改成 25, 成品照旧 20
        tx.executeWithoutResult(state -> {
            ProductionBatch fin = batches.findById(f.finalBatchId()).orElseThrow();
            rows.save(row(f.planId(), fin, 2, "step2-redo-" + f.planId(), true,
                    List.of(semiFeed(f.semiNo(), "25"), finishedFeed(f.finishedNo(), "20"))));
        });

        settle(f, "k2-" + f.planId());
        assertConserved(f, "改后再结单", "35", "20");
        assertThat(stamp(f.stepOneRowId())).as("再结单重新盖戳").isNotNull();
    }

    @Test
    void wholeOrderReversalAndResettleWithoutChangesLeavesStockWhereTheFirstSettlementPutIt() {
        Fixture f = fixture(false);

        settle(f, "k1-" + f.planId());
        assertConserved(f, "结单", "30", "20");

        reverseFinalStep(f);
        assertConserved(f, "整单撤回", "0", "0");

        tx.executeWithoutResult(state -> {
            ProductionBatch fin = batches.findById(f.finalBatchId()).orElseThrow();
            rows.save(row(f.planId(), fin, 2, "step2-again-" + f.planId(), true,
                    List.of(semiFeed(f.semiNo(), "30"), finishedFeed(f.finishedNo(), "20"))));
        });
        settle(f, "k2-" + f.planId());
        assertConserved(f, "原样再结单", "30", "20");
    }

    @Test
    void legacySettlementWithoutFeedDetailRefusesReversalAndChangesNothing() {
        Fixture f = fixture(true);
        settle(f, "k1-" + f.planId());
        // 模拟本列上线前的结单: 扣过投料, 却没有逐笔明细
        assertThat(jdbc.update("update production_settlements set stock_feed_detail = null where production_plan_id = ?",
                f.planId())).isEqualTo(1);

        assertThatThrownBy(() -> reverseFinalStep(f))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo("SETTLEMENT_STOCK_FEED_DETAIL_MISSING"));

        assertConserved(f, "拒绝撤回后", "40", "20");
        assertThat(jdbc.queryForObject("select count(*) from production_settlements where production_plan_id = ? "
                + "and deleted_at is null", Integer.class, f.planId())).as("结单还在").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from process_sheet_rows where plan_id = ? and deleted_at is null",
                Integer.class, f.planId())).as("逐道行一行没删").isEqualTo(2);
        assertThat(stamp(f.stepOneRowId())).as("戳没被清").isNotNull();
    }
}

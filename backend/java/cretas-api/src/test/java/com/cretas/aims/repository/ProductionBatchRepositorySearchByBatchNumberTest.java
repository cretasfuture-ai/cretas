package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺陷 50 第二步 (2026-09-02, LIUSHANMEN prod owner-hit) —— 生产批次按批次号搜索,
 * server-side, 分页在过滤之后。复现 owner 生产原样输入: 计划号
 * {@code PB-PLAN-1788008667414}, 批次号 {@code PB-PLAN-1788008667414-B141A9D8-8026}。
 *
 * <p>覆盖 {@link ProductionBatchRepository#searchByFactoryIdAndBatchNumber}:
 * <ul>
 *   <li>T1: 按批次自己的编号模糊 + 不区分大小写命中</li>
 *   <li>T2: CLERK_WIP 合成行按其归属计划号命中 (批次自己的编号里<b>没有</b>这个子串,
 *       只能靠 ProcessSheetRow ⋈ ProductionPlan 子查询) —— 这条覆盖不到就说明子查询没接上</li>
 *   <li>T3: includeClerkWip=false 时即使命中关键词也排除 CLERK_WIP, 与既有 6 个分支口径一致</li>
 *   <li>T4: status/supervisorId 与 batchNumber 是 AND 关系, 不是 OR</li>
 *   <li>T5: 阴性 —— 一个字都不命中的关键词返回空页 (total 0), 不是退回未过滤的全量</li>
 *   <li>T6: 分页在过滤<b>之后</b> —— totalElements 反映过滤后的命中数, 不是过滤前的整表行数</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ProductionBatchRepository#searchByFactoryIdAndBatchNumber (缺陷 50 第二步)")
class ProductionBatchRepositorySearchByBatchNumberTest {

    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String FACTORY_ID = "IT-BNS-F";
    private static final PageRequest PAGE = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

    @BeforeEach
    void setUp() {
        // production_plans.created_by 有 FK 指向 users(id); 测试不建真实用户, 与
        // MaterialConsumptionStampSettledForPlanTest 同一条防御。
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // T1 素材: 两个普通批次, 批次号各自不同, 都不是 CLERK_WIP。
        seedBatch("PB-PLAN-1111111111-AAAA0000", "REGULAR", ProductionBatchStatus.IN_PROGRESS, null);
        // owner 生产原样复现: 计划号 PB-PLAN-1788008667414, 批次号带同一段前缀 + 后缀。
        long ownerBatchId = seedBatch(
                "PB-PLAN-1788008667414-B141A9D8-8026", "CLERK_WIP", ProductionBatchStatus.IN_PROGRESS, null);
        String ownerPlanId = seedPlan("PB-PLAN-1788008667414");
        seedRow(ownerPlanId, ownerBatchId);

        // T2 素材: 批次自己的编号里完全没有计划号子串, 只能靠计划号子查询命中。
        long planOnlyBatchId = seedBatch("WIP-9999", "CLERK_WIP", ProductionBatchStatus.IN_PROGRESS, null);
        String planOnlyPlanId = seedPlan("PP-ONLY-777");
        seedRow(planOnlyPlanId, planOnlyBatchId);

        // T4 素材: 批次号命中关键词, 但 status 不同 —— 不该被 status=COMPLETED 的搜索命中。
        seedBatch("PB-STATUS-MISMATCH-001", "REGULAR", ProductionBatchStatus.PAUSED, null);
        // T4 素材: 批次号命中关键词, 但 supervisorId 不同。
        seedBatch("PB-SUPERVISOR-MISMATCH-001", "REGULAR", ProductionBatchStatus.IN_PROGRESS, 999L);
    }

    @Test
    @DisplayName("T1: 批次自己的编号模糊 + 不区分大小写命中")
    void matchesOwnBatchNumberCaseInsensitiveSubstring() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, true, "plan-1111111111-aaaa", PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getBatchNumber()).isEqualTo("PB-PLAN-1111111111-AAAA0000");
    }

    @Test
    @DisplayName("T2a: owner 原样复现 —— 搜计划号那一段前缀, 命中 CLERK_WIP 合成行 (子串命中)")
    void ownerReproSearchesPlanNumberPrefix() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, true, "PB-PLAN-1788008667414", PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getBatchNumber())
                .isEqualTo("PB-PLAN-1788008667414-B141A9D8-8026");
    }

    @Test
    @DisplayName("T2b: 批次自己的编号里没有这个子串, 只靠 ProcessSheetRow⋈ProductionPlan 子查询命中")
    void matchesClerkWipRowOnlyViaPlanNumberSubquery() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, true, "PP-ONLY-777", PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getBatchNumber()).isEqualTo("WIP-9999");
    }

    @Test
    @DisplayName("T3: includeClerkWip=false 时排除 CLERK_WIP, 即使批次号命中关键词")
    void excludesClerkWipWhenIncludeClerkWipFalse() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, false, "PB-PLAN-1788008667414", PAGE);

        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("T4a: status 与 batchNumber 是 AND —— 命中关键词但 status 不符不返回")
    void statusFilterAndsWithBatchNumber() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, ProductionBatchStatus.COMPLETED, null, true, "PB-STATUS-MISMATCH", PAGE);

        assertThat(page.getTotalElements()).isZero();

        Page<ProductionBatch> matched = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, ProductionBatchStatus.PAUSED, null, true, "PB-STATUS-MISMATCH", PAGE);
        assertThat(matched.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4b: supervisorId 与 batchNumber 是 AND —— 命中关键词但主管不符不返回")
    void supervisorFilterAndsWithBatchNumber() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, 1L, true, "PB-SUPERVISOR-MISMATCH", PAGE);

        assertThat(page.getTotalElements()).isZero();

        Page<ProductionBatch> matched = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, 999L, true, "PB-SUPERVISOR-MISMATCH", PAGE);
        assertThat(matched.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5: 阴性 —— 一个字都不命中的关键词返回空页, 不是退回未过滤的全量 (缺陷 50 的核心)")
    void noMatchReturnsEmptyPageNotTheFullUnfilteredSet() {
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, true, "NOBODY-HAS-THIS-BATCH-NUMBER-404", PAGE);

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("T6: 分页在过滤之后 —— totalElements 是过滤后的命中数, 不是过滤前的整表行数")
    void paginationAppliesAfterFiltering() {
        // setUp 已建 5 条批次; 关键词 "PB-" 命中其中 4 条 (WIP-9999 不含 "PB-")。
        Page<ProductionBatch> page = batchRepo.searchByFactoryIdAndBatchNumber(
                FACTORY_ID, null, null, true, "PB-", PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    // ── helpers ──

    private long seedBatch(String batchNumber, String batchType, ProductionBatchStatus status, Long supervisorId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId("IT-BNS-PT");
        batch.setQuantity(new BigDecimal("100.00"));
        batch.setUnit("kg");
        batch.setStatus(status);
        batch.setBatchType(batchType);
        batch.setSupervisorId(supervisorId);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        return batchRepo.saveAndFlush(batch).getId();
    }

    private String seedPlan(String planNumber) {
        String planId = "IT-BNS-PLAN-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber(planNumber);
        plan.setProductTypeId("IT-BNS-PT");
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
        return planId;
    }

    private void seedRow(String planId, long batchId) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("chaoshui");
        row.setProcessOrder(1);
        row.setClientRowId("row-" + batchId);
        row.setBatchId(batchId);
        row.setRowPayload("{}");
        row.setRowStatus("SAVED");
        rowRepo.saveAndFlush(row);
    }
}

package com.cretas.aims.integration;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.bom.impl.BomPriceAdjustmentServiceImpl;
import com.cretas.aims.service.bom.impl.BomRecipeServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 真 PostgreSQL: BOM 调价建议批准 (D-S5-10 归档版本不回写 / D-S5-11 批准即所见 / 审查 A–D)。
 *
 * <p>为什么要真库: 拒绝路径的承诺是「一行不改」—— 这取决于 {@code @Transactional} 代理在 409 时真的回滚;
 * 并发的承诺取决于 {@code SELECT ... FOR UPDATE} 的锁序与「加锁读时实体第一次进持久化上下文」。
 * Mockito 都证明不了。读回一律走 JdbcTemplate (绕过持久化上下文)。</p>
 *
 * <p>真实参与者: 调价服务、配方服务的成本重算 (审查 D, 与编辑 / 成本更正同一实现)、工艺保密检查 bean
 * ({@link ProcessSecretRecipeGuard}, 它会无锁 findById 配方 —— 审查 A 的触发点)。
 * 判定服务 {@link ProcessSecretAccessService} 是 mock: 一律放行, 并在这里挂并发插桩 (见 {@link #guardHook})。</p>
 *
 * <p>schema 由实体 create-drop 建, 外键全部摘掉 (一次性库), 不代表生产迁移。
 * 开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (见 {@link DisposablePostgresTargetGuard})。</p>
 */
@SpringBootTest(classes = BomPriceAdjustmentApprovalPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class BomPriceAdjustmentApprovalPostgresContractTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({BomPriceAdjustmentServiceImpl.class, BomRecipeServiceImpl.class, ProcessSecretRecipeGuard.class})
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

    /** 工艺保密判定: 放行; 挂插桩 —— 它在批准事务里、保密检查已无锁读过配方之后被调用。 */
    @MockBean ProcessSecretAccessService accessService;
    /** 配方服务的其余依赖: 成本重算只用到 nestedBomCostService.isNestedComponent (默认 false = 普通行)。 */
    @MockBean NestedBomCostService nestedBomCostService;
    @MockBean MaterialUomConverter materialUomConverter;
    @MockBean UnitContractService unitContractService;
    @MockBean ProductConfigurationReadinessService readinessService;
    @MockBean BomItemSubstituteService substituteService;
    @MockBean BomWorkflowRevisionService bomWorkflowRevisionService;
    @MockBean ProductWorkflowResolutionService workflowResolutionService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BomPriceAdjustmentService service;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository items;
    @Autowired BomPriceAdjustmentProposalRepository proposals;
    @Autowired RawMaterialTypeRepository materials;

    /** 一次性插桩: 保密检查 (配方已被无锁读过之后) / 行价写完、头成本重算时 (物料价已读、建议未提交)。 */
    private final AtomicReference<Runnable> guardHook = new AtomicReference<>();
    private final AtomicReference<Runnable> afterLineWriteHook = new AtomicReference<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private TransactionTemplate tx;
    private String factory;
    private String material;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        doAnswer(inv -> { runOnce(guardHook); return null; })
                .when(accessService).require(any(), any(), any(), any(), any());
        doAnswer(inv -> { runOnce(afterLineWriteHook); return false; })
                .when(nestedBomCostService).isNestedComponent(any());
        factory = "BPA-" + UUID.randomUUID().toString().substring(0, 8);
        material = "RMT-PKG-" + factory;
        // 线上形状: 收货 100 个 @0.8 之后包材移动均价 0.7501
        tx.executeWithoutResult(status -> {
            RawMaterialType type = new RawMaterialType();
            type.setId(material);
            type.setFactoryId(factory);
            type.setCode("PKG-" + factory);
            type.setName("QA-S3-1104-包材");
            type.setCategory("包材");
            type.setUnit("个");
            type.setIsByproduct(false);
            type.setSelfSupplied(false);
            type.setIsActive(true);
            type.setCreatedBy(1L);
            type.setUnitPrice(new BigDecimal("0.5"));
            type.setMovingAvgPrice(new BigDecimal("0.7501"));
            materials.saveAndFlush(type);
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- D-S5-10 / D-S5-11 (串行)

    @Test
    void archivedVersionProposalIsRejectedAndNothingIsWrittenInTheDatabase() {
        long line = line("RW-V1", 1, BomRecipe.Status.ARCHIVED, "2");
        long proposal = proposal("RW-V1", line, "0.7501");

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(factory, proposal, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_RECIPE_NOT_EDITABLE);
        Map<String, Object> row = itemRow(line);
        assertThat((BigDecimal) row.get("unit_price")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) row.get("item_cost")).as("2 个 × 0.5, 未被改成 2 × 0.7501").isEqualByComparingTo("1.0");
        assertThat((BigDecimal) headRow("RW-V1").get("total_material_cost")).isEqualByComparingTo("1.0");
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) p.get("proposed_unit_price")).isEqualByComparingTo("0.7501");
        assertThat(p.get("approved_by")).isNull();
        assertThat(auditCount()).isZero();
    }

    @Test
    void staleProposalIsRejectedAndNeitherTheLineNorTheProposalIsRewritten() {
        long line = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("SRC-V3", line, "0.8");

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(factory, proposal, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_STALE);
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.5");
        Map<String, Object> p = proposalRow(proposal);
        assertThat((BigDecimal) p.get("proposed_unit_price")).as("⛔ 不能被悄悄改成 0.7501").isEqualByComparingTo("0.8");
        assertThat(p.get("status")).isEqualTo("PENDING");
        assertThat(auditCount()).isZero();
    }

    @Test
    void activeVersionApprovalWritesTheProposalPriceAndRecomputesTheRecipeHeader() {
        long line = line("RW-V4", 4, BomRecipe.Status.ACTIVE, "3");
        long proposal = proposal("RW-V4", line, "0.7501");
        assertThat((BigDecimal) headRow("RW-V4").get("total_material_cost")).as("批准前: 3 × 0.5").isEqualByComparingTo("1.5");

        service.approve(factory, proposal, 9L, "ok", new BigDecimal("0.7501"));

        Map<String, Object> row = itemRow(line);
        assertThat((BigDecimal) row.get("unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) row.get("item_cost")).as("3 个 × 0.7501").isEqualByComparingTo("2.2503");
        // 审查 D: 标准成本 / 嵌套 BOM 读的是配方头, 它必须跟着行一起变
        Map<String, Object> head = headRow("RW-V4");
        assertThat((BigDecimal) head.get("total_material_cost")).isEqualByComparingTo("2.2503");
        assertThat((BigDecimal) head.get("total_cost")).isEqualByComparingTo("2.2503");
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("APPROVED");
        assertThat((BigDecimal) p.get("proposed_unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) p.get("current_unit_price")).isEqualByComparingTo("0.5");
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void generationWritesProposalsOnlyForEditableVersions() {
        long active = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long draft = line("NEW-V1", 1, BomRecipe.Status.DRAFT, "1");
        long archived = line("SRC-V1", 1, BomRecipe.Status.ARCHIVED, "1");

        service.generateForMaterial(factory, material, null, new BigDecimal("0.8"),
                BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE, "RCV-1", 1L);

        assertThat(jdbc.queryForList("select recipe_item_id from bom_price_adjustment_proposals "
                + "where factory_id = ? order by recipe_item_id", Long.class, factory))
                .containsExactlyInAnyOrder(active, draft)
                .doesNotContain(archived);
        assertThat(jdbc.queryForList("select proposed_unit_price from bom_price_adjustment_proposals "
                + "where factory_id = ?", BigDecimal.class, factory))
                .allSatisfy(price -> assertThat(price).isEqualByComparingTo("0.7501"));
    }

    // ---------------------------------------------------------------- 审查 A: 保密检查先无锁读了配方

    @Test
    void archiveCommittedWhileApprovalIsInsideTheSecretCheckIsNeverWrittenOver() throws Exception {
        long line = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("SRC-V3", line, "0.7501");
        String recipeId = recipeId("SRC-V3");
        AtomicReference<Boolean> archivedInsideCheck = new AtomicReference<>();
        AtomicReference<Future<Integer>> archiver = new AtomicReference<>();
        // 「激活新版本把它归档」: 另一个连接自动提交改状态。批准若已持有配方头锁, 它会被挡住。
        guardHook.set(() -> {
            Future<Integer> f = pool.submit(() -> jdbc.update(
                    "update bom_recipes set status = 'ARCHIVED', is_current = false where id = ?", recipeId));
            archiver.set(f);
            archivedInsideCheck.set(completesWithin(f, 3));
        });

        Throwable outcome = catchThrowableOfType(() -> service.approve(factory, proposal, 9L, "ok"), Throwable.class);
        archiver.get().get(30, TimeUnit.SECONDS);

        assertThat(archivedInsideCheck.get()).as("插桩跑过").isNotNull();
        if (archivedInsideCheck.get()) {
            // 归档在批准做决定之前已提交 ⇒ 批准必须看到 ARCHIVED 并拒绝, 行一分不动
            assertThat(outcome).as("归档先提交了, 批准却按旧的 ACTIVE 写入").isInstanceOf(BusinessException.class);
            assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.5");
        } else {
            // 归档被配方头锁挡在批准提交之后 ⇒ 批准写在当时仍生效的版本上, 之后才归档
            assertThat(outcome).isNull();
            assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.7501");
        }
        assertThat(headRow("SRC-V3").get("status")).isEqualTo("ARCHIVED");
    }

    // ---------------------------------------------------------------- 审查 B: 并发批准同一条建议

    @Test
    void twoConcurrentApprovalsOfOneProposalProduceExactlyOneApprovalAndOneAuditRow() throws Exception {
        long line = line("RW-V4", 4, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("RW-V4", line, "0.7501");
        CyclicBarrier bothInside = new CyclicBarrier(2);
        // 两个批准都走到保密检查才放行: 若 PENDING 判定读的不是加锁后的新值, 两个都会判成 PENDING
        Runnable rendezvous = () -> {
            try {
                bothInside.await(3, TimeUnit.SECONDS);
            } catch (Exception timedOut) {
                // 另一方被锁挡在外面进不来 —— 正是期望的串行; 继续
            }
        };
        doAnswer(inv -> { rendezvous.run(); return null; })
                .when(accessService).require(any(), any(), any(), any(), any());

        Future<Throwable> first = pool.submit(() -> attempt(() -> service.approve(factory, proposal, 9L, "first")));
        Future<Throwable> second = pool.submit(() -> attempt(() -> service.approve(factory, proposal, 10L, "second")));
        List<Throwable> outcomes = java.util.Arrays.asList(   // 成功 = null, ⛔ 不用 List.of (拒收 null)
                first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

        long succeeded = outcomes.stream().filter(t -> t == null).count();
        assertThat(succeeded).as("恰好一个批准成功; outcomes=" + outcomes).isEqualTo(1);
        Throwable loser = outcomes.stream().filter(t -> t != null).findFirst().orElseThrow();
        assertThat(loser).as("输的一方得到明确的 409「只有待审批的可以批准」, 不是乐观锁异常或别的")
                .isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) loser).getCode()).isEqualTo(409);
        assertThat(auditCount()).as("只有一条审计").isEqualTo(1);
        Map<String, Object> p = proposalRow(proposal);
        Long auditApprover = jdbc.queryForObject("select approved_by from bom_price_adjustment_audits where factory_id = ?",
                Long.class, factory);
        assertThat(((Number) p.get("approved_by")).longValue()).as("建议行上的批准人 = 审计里那一个, 没被覆盖")
                .isEqualTo(auditApprover);
    }

    // ---------------------------------------------------------------- 审查 C: 批准与确认入库并发

    @Test
    void receiptCommittingDuringAnApprovalDoesNotLoseItsNewPrice() throws Exception {
        long line = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("SRC-V3", line, "0.7501");
        AtomicReference<Future<Throwable>> receipt = new AtomicReference<>();
        AtomicReference<Boolean> receiptInsideApproval = new AtomicReference<>();
        // 批准已读过物料价、写了行、还没提交时, 另一批货确认入库: 与 PurchaseServiceImpl.confirmReceive 同序 ——
        // 先锁物料, 重算均价 (这里直接写成 0.9), 再按新均价生成 / 原地改写 PENDING 建议。
        afterLineWriteHook.set(() -> {
            Future<Throwable> f = pool.submit(() -> attempt(() -> tx.executeWithoutResult(status -> {
                RawMaterialType locked = materials.lockByFactoryIdAndIdIn(factory, List.of(material)).get(0);
                locked.setMovingAvgPrice(new BigDecimal("0.9"));
                materials.save(locked);
                service.generateForMaterial(factory, material, null, new BigDecimal("1.1"),
                        BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE, "RCV-2", 2L);
            })));
            receipt.set(f);
            receiptInsideApproval.set(completesWithin(f, 3));
        });

        Throwable approval = attempt(() -> service.approve(factory, proposal, 9L, "ok"));
        Throwable receiptOutcome = receipt.get().get(60, TimeUnit.SECONDS);

        assertThat(receiptInsideApproval.get()).as("插桩跑过").isNotNull();
        assertThat(receiptInsideApproval.get()).as("收货必须被批准持有的物料锁挡到批准提交之后").isFalse();
        assertThat(approval).as("批准成功").isNull();
        assertThat(receiptOutcome).as("收货成功").isNull();
        // 批准写的是它看到的 0.7501; 收货的新均价 0.9 没有丢: 留下一条新的 PENDING 建议把行带到 0.9
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.7501");
        Map<String, Object> approved = proposalRow(proposal);
        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat((BigDecimal) approved.get("proposed_unit_price")).isEqualByComparingTo("0.7501");
        assertThat(jdbc.queryForList("select proposed_unit_price from bom_price_adjustment_proposals "
                        + "where factory_id = ? and recipe_item_id = ? and status = 'PENDING'", BigDecimal.class, factory, line))
                .as("收货的新价以一条新的待审批建议留下").singleElement()
                .satisfies(price -> assertThat(price).isEqualByComparingTo("0.9"));
    }

    // ---------------------------------------------------------------- D-S5-09: 驳回

    @Test
    void rejectRecordsWhoWhenAndWhyAndLeavesTheRecipeLineUntouched() {
        long line = line("RJ-V4", 4, BomRecipe.Status.ACTIVE, "3");
        long proposal = proposal("RJ-V4", line, "0.7501");

        service.reject(factory, proposal, 11L, "  QA-供应商临时涨价, 下批回落  ");

        Map<String, Object> p = jdbc.queryForMap("select status, approved_by, approved_at, approval_comment "
                + "from bom_price_adjustment_proposals where id = ?", proposal);
        assertThat(p.get("status")).isEqualTo("REJECTED");
        assertThat(((Number) p.get("approved_by")).longValue()).isEqualTo(11L);
        assertThat(p.get("approved_at")).isNotNull();
        assertThat(p.get("approval_comment")).isEqualTo("QA-供应商临时涨价, 下批回落");
        // 阴性对照: 配方行、配方头、调价审计一个都没动
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) headRow("RJ-V4").get("total_material_cost")).isEqualByComparingTo("1.5");
        assertThat(auditCount()).isZero();
        // 驳回之后不能再批
        BusinessException approveAfter = catchThrowableOfType(
                () -> service.approve(factory, proposal, 9L, "late"), BusinessException.class);
        assertThat(approveAfter).isNotNull();
        assertThat(approveAfter.getCode()).isEqualTo(409);
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.5");
    }

    @Test
    void rejectWithoutAReasonIsRefusedAndTheProposalStaysPending() {
        long line = line("RJ-V5", 5, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("RJ-V5", line, "0.7501");

        for (String blank : java.util.Arrays.asList(null, "", "   ")) {
            BusinessException refused = catchThrowableOfType(
                    () -> service.reject(factory, proposal, 11L, blank), BusinessException.class);
            assertThat(refused).as("原因=%s", blank).isNotNull();
            assertThat(refused.getCode()).isEqualTo(400);
            assertThat(refused.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_REJECT_REASON_REQUIRED);
        }
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("PENDING");
        assertThat(p.get("approved_by")).isNull();
    }

    @Test
    void rejectOfAnApprovedProposalIsRefusedAndTheApprovalStands() {
        long line = line("RJ-V6", 6, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("RJ-V6", line, "0.7501");
        service.approve(factory, proposal, 9L, "ok");

        BusinessException refused = catchThrowableOfType(
                () -> service.reject(factory, proposal, 11L, "QA-晚了"), BusinessException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getCode()).isEqualTo(409);
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("APPROVED");
        assertThat(((Number) p.get("approved_by")).longValue()).isEqualTo(9L);
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.7501");
    }

    @Test
    void anotherFactoryCannotReadOrRejectTheProposal() {
        long line = line("RJ-V7", 7, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("RJ-V7", line, "0.7501");

        assertThat(catchThrowableOfType(() -> service.get("OTHER-" + factory, proposal),
                com.cretas.aims.exception.ResourceNotFoundException.class)).isNotNull();
        assertThat(catchThrowableOfType(() -> service.reject("OTHER-" + factory, proposal, 11L, "QA-越权"),
                com.cretas.aims.exception.ResourceNotFoundException.class)).isNotNull();
        assertThat(proposalRow(proposal).get("status")).isEqualTo("PENDING");
        assertThat(service.get(factory, proposal).getId()).isEqualTo(proposal);
    }

    // ---------------------------------------------------------------- N1: 同族两个成员同时调价

    /**
     * N1 (#1578 审查): 联产品共享投入按比例分摊, 改任一成员的行价都会重算并改写全族配方头。
     * 两个成员 (各自一条共享投入行、不同物料, 所以物料锁不互斥) 同时批准: 两个事务都停在保密检查
     * (配方头已加锁之后) 汇合, 再各自去重算全族。只锁自己那个头时, 甲持 A 等 B、乙持 B 等 A, PG 判死锁回滚一方;
     * 全族按 id 升序加锁后, 后到的一方在第一个头上排队, 两个都成功, 全族成本 = 两次调价都生效后的值。
     */
    @Test
    void twoMembersOfOneFamilyApprovedConcurrentlyBothSucceedWithoutDeadlock() throws Exception {
        String secondMaterial = material + "-B";
        tx.executeWithoutResult(status -> {
            RawMaterialType type = new RawMaterialType();
            type.setId(secondMaterial);
            type.setFactoryId(factory);
            type.setCode("RAW-B-" + factory);
            type.setName("QA-N1-原料B");
            type.setCategory("原料");
            type.setUnit("个");
            type.setIsByproduct(false);
            type.setSelfSupplied(false);
            type.setIsActive(true);
            type.setCreatedBy(1L);
            type.setUnitPrice(new BigDecimal("0.5"));
            type.setMovingAvgPrice(new BigDecimal("0.7501"));
            materials.saveAndFlush(type);
        });
        String familyId = "FAM-" + factory;
        long lineA = familyLine("FAM-A", familyId, "T-A", material);
        long lineB = familyLine("FAM-B", familyId, "T-B", secondMaterial);
        long proposalA = proposal("FAM-A", lineA, "0.7501");
        long proposalB = familyProposal("FAM-B", lineB, secondMaterial);
        assertThat((BigDecimal) headRow("FAM-A").get("total_material_cost"))
                .as("批准前: 共享池 (0.5 + 0.5) × 50%").isEqualByComparingTo("0.5");

        CyclicBarrier bothInside = new CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicInteger rendezvousMet = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            try {
                bothInside.await(3, TimeUnit.SECONDS);
                rendezvousMet.incrementAndGet();
            } catch (Exception timedOut) {
                // 另一方被族锁挡在外面进不来 —— 正是期望的串行; 继续
            }
            return null;
        }).when(accessService).require(any(), any(), any(), any(), any());

        Future<Throwable> first = pool.submit(() -> attempt(() -> service.approve(factory, proposalA, 9L, "A")));
        Future<Throwable> second = pool.submit(() -> attempt(() -> service.approve(factory, proposalB, 10L, "B")));
        List<Throwable> outcomes = java.util.Arrays.asList(
                first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

        assertThat(outcomes).as("两个批准都成功, 没有一方被 PG 判死锁回滚; outcomes=" + outcomes)
                .containsOnlyNulls();
        assertThat(rendezvousMet.get()).as("两个事务不可能同时持有各自的配方头进到保密检查").isZero();
        assertThat((BigDecimal) itemRow(lineA).get("unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) itemRow(lineB).get("unit_price")).isEqualByComparingTo("0.7501");
        // 后提交的那次重算看得到先提交的那一行: 共享池 0.7501 + 0.7501, 各分 50%
        assertThat((BigDecimal) headRow("FAM-A").get("total_material_cost")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) headRow("FAM-B").get("total_material_cost")).isEqualByComparingTo("0.7501");
        assertThat(proposalRow(proposalA).get("status")).isEqualTo("APPROVED");
        assertThat(proposalRow(proposalB).get("status")).isEqualTo("APPROVED");
        assertThat(auditCount()).isEqualTo(2);
    }

    // ---- helpers ----

    private static void runOnce(AtomicReference<Runnable> hook) {
        Runnable r = hook.getAndSet(null);
        if (r != null) r.run();
    }

    private static boolean completesWithin(Future<?> f, int seconds) {
        try {
            f.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException blocked) {
            return false;
        } catch (Exception failed) {
            return true;
        }
    }

    private static Throwable attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // ---- fixtures ----

    private String recipeId(String tag) {
        return "BOM-" + tag + "-" + factory;
    }

    private long line(String tag, int version, BomRecipe.Status status, String quantity) {
        String recipeId = recipeId(tag);
        BigDecimal cost = new BigDecimal(quantity).multiply(new BigDecimal("0.5"));
        return tx.execute(s -> {
            BomRecipe bom = new BomRecipe();
            bom.setId(recipeId);
            bom.setFactoryId(factory);
            bom.setRecipeCode("CODE-" + tag + "-" + factory);
            bom.setProductTypeId("SKU-" + tag + "-" + factory);
            bom.setProductName("product " + tag);
            bom.setVersion(version);
            bom.setIsCurrent(status == BomRecipe.Status.ACTIVE);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("个");
            bom.setStatus(status);
            bom.setTotalMaterialCost(cost);
            bom.setTotalCost(cost);
            recipes.saveAndFlush(bom);
            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(recipeId);
            item.setFactoryId(factory);
            item.setMaterialTypeId(material);
            item.setMaterialName("QA-S3-1104-包材");
            item.setStandardQuantity(new BigDecimal(quantity));
            item.setYieldRate(new BigDecimal("100.00"));
            item.setUnit("个");
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialCategory("PACKAGING");
            item.setSortOrder(0);
            item.setIsOptional(false);
            item.setPerPortion(false);
            item.setUnitPrice(new BigDecimal("0.5"));
            item.setItemCost(item.computeItemCost());
            return items.saveAndFlush(item).getId();
        });
    }

    private long proposal(String tag, long lineId, String proposed) {
        return tx.execute(s -> {
            BomPriceAdjustmentProposal p = new BomPriceAdjustmentProposal();
            p.setFactoryId(factory);
            p.setRecipeId(recipeId(tag));
            p.setRecipeItemId(lineId);
            p.setMaterialTypeId(material);
            p.setCurrentUnitPrice(new BigDecimal("0.5"));
            p.setProposedUnitPrice(new BigDecimal(proposed));
            p.setDeltaAmount(new BigDecimal(proposed).subtract(new BigDecimal("0.5")));
            p.setAffectedProductCount(1);
            p.setStatus(BomPriceAdjustmentProposal.Status.PENDING);
            p.setSourceType(BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE);
            return proposals.saveAndFlush(p).getId();
        });
    }

    /** 同一 BOM Family 的 ACTIVE 成员, 一条共享投入行 (非包材、无成本范围 = 全族分摊), 分摊比例 50%。 */
    private long familyLine(String tag, String familyId, String terminal, String materialId) {
        String recipeId = recipeId(tag);
        return tx.execute(s -> {
            BomRecipe bom = new BomRecipe();
            bom.setId(recipeId);
            bom.setFactoryId(factory);
            bom.setRecipeCode("CODE-" + tag + "-" + factory);
            bom.setProductTypeId("SKU-" + tag + "-" + factory);
            bom.setProductName("product " + tag);
            bom.setVersion(1);
            bom.setIsCurrent(true);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("个");
            bom.setStatus(BomRecipe.Status.ACTIVE);
            bom.setBomFamilyId(familyId);
            bom.setTargetTerminalNodeId(terminal);
            bom.setCostAllocationRatio(new BigDecimal("50"));
            bom.setTotalMaterialCost(new BigDecimal("0.5"));
            bom.setTotalCost(new BigDecimal("0.5"));
            recipes.saveAndFlush(bom);
            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(recipeId);
            item.setFactoryId(factory);
            item.setMaterialTypeId(materialId);
            item.setMaterialName("QA-N1-共享投入");
            item.setStandardQuantity(BigDecimal.ONE);
            item.setYieldRate(new BigDecimal("100.00"));
            item.setUnit("个");
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialCategory("RAW_MATERIAL");
            item.setSortOrder(0);
            item.setIsOptional(false);
            item.setPerPortion(false);
            item.setUnitPrice(new BigDecimal("0.5"));
            item.setItemCost(item.computeItemCost());
            return items.saveAndFlush(item).getId();
        });
    }

    private long familyProposal(String tag, long lineId, String materialId) {
        long id = proposal(tag, lineId, "0.7501");
        jdbc.update("update bom_price_adjustment_proposals set material_type_id = ? where id = ?", materialId, id);
        return id;
    }

    private Map<String, Object> itemRow(long id) {
        return jdbc.queryForMap("select unit_price, item_cost from bom_recipe_items where id = ?", id);
    }

    private Map<String, Object> headRow(String tag) {
        return jdbc.queryForMap("select status, total_material_cost, total_cost from bom_recipes where id = ?",
                recipeId(tag));
    }

    private Map<String, Object> proposalRow(long id) {
        return jdbc.queryForMap("select status, proposed_unit_price, current_unit_price, approved_by "
                + "from bom_price_adjustment_proposals where id = ?", id);
    }

    private int auditCount() {
        return jdbc.queryForObject("select count(*) from bom_price_adjustment_audits where factory_id = ?",
                Integer.class, factory);
    }
}

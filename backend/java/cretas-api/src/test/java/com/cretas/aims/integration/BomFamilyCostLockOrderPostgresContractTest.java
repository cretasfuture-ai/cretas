package com.cretas.aims.integration;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.bom.BomRecipeHeadLocks;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.bom.BomVersionService;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.bom.impl.BomItemSubstituteServiceImpl;
import com.cretas.aims.service.bom.impl.BomPriceAdjustmentServiceImpl;
import com.cretas.aims.service.bom.impl.BomRecipeServiceImpl;
import com.cretas.aims.service.bom.impl.BomSeasoningWorkspaceServiceImpl;
import com.cretas.aims.service.bom.impl.BomVersionServiceImpl;
import com.cretas.aims.service.bom.impl.BomYieldEstimateServiceImpl;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 真 PostgreSQL: 红线 3 —— 激活与调价批准落在同一 BOM Family 上时, 不死锁、成本不被旧值盖掉。
 *
 * <p>为什么要真库: 承诺的是 {@code SELECT ... FOR UPDATE} 的锁序 (整族配方头按 id 升序 → 明细行) 与
 * 「加锁读时实体第一次进持久化上下文」。配方头没有乐观锁、Hibernate 整行 UPDATE, 激活若用加锁前读到的行算成本,
 * 提交时会把调价批准刚写好的头成本盖回旧值 —— Mockito 与 H2 都证明不了。读回一律走 JdbcTemplate。</p>
 *
 * <p>真实参与者: 配方服务 (建配方 / 激活 / 成本重算)、调价服务、工艺保密检查 bean。
 * 工艺图解析 ({@link BomWorkflowRevisionService}) 是 mock, 给一个「原料 → 工序 → 单一主产出」的固定图;
 * 判定服务 {@link ProcessSecretAccessService} 是 mock, 一律放行并挂插桩。</p>
 *
 * <p>开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (见 {@link DisposablePostgresTargetGuard})。</p>
 */
@SpringBootTest(classes = BomFamilyCostLockOrderPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class BomFamilyCostLockOrderPostgresContractTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({BomPriceAdjustmentServiceImpl.class, BomRecipeServiceImpl.class, BomSeasoningWorkspaceServiceImpl.class,
            BomItemSubstituteServiceImpl.class, BomYieldEstimateServiceImpl.class, BomVersionServiceImpl.class,
            ProcessSecretRecipeGuard.class})
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

    @MockBean ProcessSecretAccessService accessService;
    @MockBean NestedBomCostService nestedBomCostService;
    @MockBean MaterialUomConverter materialUomConverter;
    @MockBean UnitContractService unitContractService;
    @MockBean ProductConfigurationReadinessService readinessService;
    /** 真实替代料服务 (克隆路径的「源头 + 新克隆」锁在它里面), 只给调料删除那一处挂插桩。 */
    @SpyBean BomItemSubstituteService substituteService;
    @MockBean YieldReportService yieldReportService;
    @MockBean BomWorkflowRevisionService bomWorkflowRevisionService;
    @MockBean ProductWorkflowResolutionService workflowResolutionService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BomPriceAdjustmentService priceAdjustments;
    @Autowired BomRecipeService recipes;
    @Autowired ProductTypeRepository products;
    @Autowired RawMaterialTypeRepository materials;
    @Autowired BomPriceAdjustmentProposalRepository proposals;
    @Autowired BomSeasoningWorkspaceService seasoning;
    @Autowired BomYieldEstimateService yields;
    @Autowired BomVersionService versions;
    @Autowired BomRecipeRepository recipeRepository;
    @Autowired BomRecipeItemRepository itemRepository;
    @Autowired BomSeasoningItemRepository seasoningItems;

    /** 一次性插桩: 成本重算读每一行时 (isNestedComponent) / 调价批准的保密检查 (族与行都已加锁之后)。 */
    private final AtomicReference<Runnable> costReadHook = new AtomicReference<>();
    private final AtomicReference<Runnable> secretCheckHook = new AtomicReference<>();
    /** 调料删除在 claimRevision 之后、calculateCost 之前调用替代料服务 (mock) 的那一刻。 */
    private final AtomicReference<Runnable> seasoningClaimedHook = new AtomicReference<>();
    /** 克隆每复制完一个成员的替代关系 (cloneRelations 真实执行之后) 的那一刻。 */
    private final AtomicReference<Runnable> cloneMemberHook = new AtomicReference<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private TransactionTemplate tx;
    private String factory;
    private String product;
    private String material;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        doAnswer(inv -> { runOnce(secretCheckHook); return null; })
                .when(accessService).require(any(), any(), any(), any(), any());
        doAnswer(inv -> { runOnce(costReadHook); return false; })
                .when(nestedBomCostService).isNestedComponent(any());
        doAnswer(inv -> { runOnce(seasoningClaimedHook); return List.of(); })
                .when(substituteService).replaceForSeasoningItem(any(), any(), any(), any());
        doAnswer(inv -> { Object copied = inv.callRealMethod(); runOnce(cloneMemberHook); return copied; })
                .when(substituteService).cloneRelations(any(), any(), any(), any(), any());
        stubUnitsAndWorkflow();

        factory = "QA-R3-" + UUID.randomUUID().toString().substring(0, 8);
        product = "SKU-R3-" + factory;
        material = "RAW-WF-" + product;
        tx.executeWithoutResult(status -> {
            ProductType sku = new ProductType();
            sku.setId(product);
            sku.setFactoryId(factory);
            sku.setCode("SKU-" + factory);
            sku.setName("QA-R3-成品");
            sku.setUnit("盒");
            sku.setGramsPerUnit(new BigDecimal("500"));
            sku.setIsActive(true);
            sku.setCreatedBy(1L);
            products.saveAndFlush(sku);
            RawMaterialType raw = new RawMaterialType();
            raw.setId(material);
            raw.setFactoryId(factory);
            raw.setCode("RAW-" + factory);
            raw.setName("QA-R3-原料");
            raw.setCategory("RAW");
            raw.setUnit("kg");
            raw.setIsByproduct(false);
            raw.setSelfSupplied(false);
            raw.setIsActive(true);
            raw.setIsAbacaPackaging(false);
            raw.setCreatedBy(1L);
            raw.setUnitPrice(new BigDecimal("0.5"));
            raw.setMovingAvgPrice(new BigDecimal("0.7501"));
            materials.saveAndFlush(raw);
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * 激活先进: 它在重算里读明细行 (头已按族加锁) 时, 调价批准到达。批准必须排在族锁后面等激活提交,
     * 然后在 ACTIVE 版本上照常写价、重算 —— 头成本 = 新价。没有族锁时, 批准会在激活读完行之后先提交,
     * 激活再用读到的旧行算出旧成本、整行写回, 盖掉批准的结果。
     */
    @Test
    void approvalArrivingWhileActivationRecomputesWaitsAndTheHeaderEndsAtTheNewPrice() throws Exception {
        String recipeId = draftWithOneRawLineAtOldPrice();
        long line = lineOf(recipeId);
        long proposal = proposal(recipeId, line);

        AtomicBoolean approvalFinishedInsideActivation = new AtomicBoolean();
        AtomicReference<Future<Throwable>> approval = new AtomicReference<>();
        costReadHook.set(() -> {
            approval.set(pool.submit(() -> attempt(() -> priceAdjustments.approve(factory, proposal, 9L, "R3"))));
            approvalFinishedInsideActivation.set(completesWithin(approval.get(), 3));
        });

        Throwable activation = pool.submit(() -> attempt(() -> recipes.activateRecipe(factory, recipeId, 1L)))
                .get(60, TimeUnit.SECONDS);
        assertThat(approval.get()).as("插桩触发了: 批准是在激活重算读行的那一刻发出的").isNotNull();
        Throwable approved = approval.get().get(60, TimeUnit.SECONDS);

        assertThat(activation).as("激活成功, 没有被 PG 判死锁").isNull();
        assertThat(approved).as("批准成功, 没有被 PG 判死锁").isNull();
        assertThat(approvalFinishedInsideActivation.get())
                .as("激活持有整族配方头期间, 批准拿不到族锁, 不可能先提交").isFalse();
        assertConsistentAtNewPrice(recipeId, line, proposal);
    }

    /**
     * 批准先进: 它在保密检查处 (整族头与明细行都已加锁) 停住时, 激活到达。激活必须在族锁上等批准提交,
     * 再读到新价去重算 —— 头成本 = 新价且状态 ACTIVE。没有族锁时, 激活会读到旧行、算出旧成本,
     * 等批准提交后整行写回旧成本。
     */
    @Test
    void activationArrivingWhileApprovalHoldsTheFamilyWaitsAndRecomputesFromTheNewPrice() throws Exception {
        String recipeId = draftWithOneRawLineAtOldPrice();
        long line = lineOf(recipeId);
        long proposal = proposal(recipeId, line);

        AtomicBoolean activationFinishedInsideApproval = new AtomicBoolean();
        AtomicReference<Future<Throwable>> activation = new AtomicReference<>();
        secretCheckHook.set(() -> {
            activation.set(pool.submit(() -> attempt(() -> recipes.activateRecipe(factory, recipeId, 1L))));
            activationFinishedInsideApproval.set(completesWithin(activation.get(), 3));
        });

        Throwable approved = pool.submit(() -> attempt(() -> priceAdjustments.approve(factory, proposal, 9L, "R3")))
                .get(60, TimeUnit.SECONDS);
        assertThat(activation.get()).as("插桩触发了: 激活是在批准持锁期间发出的").isNotNull();
        Throwable activated = activation.get().get(60, TimeUnit.SECONDS);

        assertThat(approved).as("批准成功, 没有被 PG 判死锁").isNull();
        assertThat(activated).as("激活成功, 没有被 PG 判死锁").isNull();
        assertThat(activationFinishedInsideApproval.get())
                .as("批准持有整族配方头期间, 激活拿不到族锁, 不可能先提交").isFalse();
        assertConsistentAtNewPrice(recipeId, line, proposal);
    }

    /**
     * 先读后锁 (发布收编: preflight 先无锁读草稿族头 / 行, 再进激活或重算): 读完之后别的事务提交了行新价与头备注,
     * 本事务再走真实重算入口。族锁第一次拿到时必须把那份旧的托管头 / 行刷新成已提交值 ——
     * 否则重算用旧行算出旧成本, 或用旧头整行写回, 把别人刚提交的备注盖掉 (丢失更新)。
     */
    @Test
    void entitiesReadBeforeTheFamilyLockAreRefreshedSoARecomputeDoesNotOverwriteAConcurrentCommit() {
        String recipeId = draftWithOneRawLineAtOldPrice();
        long line = lineOf(recipeId);

        Throwable outer = attempt(() -> tx.executeWithoutResult(status -> {
            BomRecipe staleHead = recipeRepository.findById(recipeId).orElseThrow();
            List<BomRecipeItem> staleLines = itemRepository.findByRecipeIdOrderBySortOrderAsc(recipeId);
            assertThat(staleHead.getNotes()).as("加锁前读到的是旧头").isNull();
            assertThat(staleLines).singleElement()
                    .satisfies(l -> assertThat(l.getUnitPrice()).isEqualByComparingTo("0.5"));
            try {
                pool.submit(() -> {
                    jdbc.update("update bom_recipe_items set unit_price = 0.7501, item_cost = 1.5002 where id = ?", line);
                    jdbc.update("update bom_recipes set notes = 'R3-并发已提交' where id = ?", recipeId);
                }).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            recipes.calculateCost(factory, recipeId);
        }));

        assertThat(outer).as("重算成功").isNull();
        Map<String, Object> head = jdbc.queryForMap(
                "select notes, total_material_cost from bom_recipes where id = ?", recipeId);
        assertThat(head.get("notes")).as("别人在加锁前提交的头备注没被旧头整行写回盖掉").isEqualTo("R3-并发已提交");
        assertThat((BigDecimal) head.get("total_material_cost"))
                .as("头成本按已提交的行新价算 (2 × 0.7501), 不是加锁前读到的旧行 1.0")
                .isEqualByComparingTo("1.5002");
    }

    /**
     * 调料删除 (claimRevision 改写目标头) 与同族另一成员的重算并发。目标成员 id 较大: 旧代码先由 claimRevision 的
     * UPDATE 单独锁住它, 再在 calculateCost 里按 id 升序锁整族 (先等 id 小的) —— 与按序加锁的重算方向相反,
     * PG 判死锁回滚一方。现在整族在入口一次按 id 升序锁住, 对方只是排队。
     */
    @Test
    void seasoningDeleteOnTheHigherIdMemberAndARecomputeOfTheLowerIdMemberDoNotDeadlock() throws Exception {
        String first = draftWithOneRawLineAtOldPrice();
        String second = draftWithOneRawLineAtOldPrice(secondProduct());
        String family = jdbc.queryForObject("select bom_family_id from bom_recipes where id = ?", String.class, first);
        jdbc.update("update bom_recipes set bom_family_id = ? where id = ?", family, second);
        String low = first.compareTo(second) < 0 ? first : second;
        String high = low.equals(first) ? second : first;
        jdbc.update("update bom_recipes set target_terminal_node_id = 't-low' where id = ?", low);
        jdbc.update("update bom_recipes set target_terminal_node_id = 't-high' where id = ?", high);
        // 两个成员各自的骨架行原本都指向固定图的同一终点 "terminal", 跟着改成各自的终点
        jdbc.update("update bom_recipe_items set cost_scope_key = 't-low' where recipe_id = ? and cost_scope_key is not null", low);
        jdbc.update("update bom_recipe_items set cost_scope_key = 't-high' where recipe_id = ? and cost_scope_key is not null", high);
        String highProduct = jdbc.queryForObject(
                "select product_type_id from bom_recipes where id = ?", String.class, high);
        BomWorkflowRevisionService.CostScopeProfile profile = new BomWorkflowRevisionService.CostScopeProfile(
                "OUTPUT_EXCLUSIVE", List.of("t-high"), List.of(highProduct));
        when(bomWorkflowRevisionService.resolveProcessCostProfiles(anyString(), any(BomRecipe.class)))
                .thenReturn(Map.of("process", profile));
        long binding = seasoningBinding(high, profile);
        long revision = jdbc.queryForObject(
                "select seasoning_revision from bom_recipes where id = ?", Long.class, high);

        AtomicBoolean recomputeFinishedWhileDeleteHeldTheFamily = new AtomicBoolean();
        AtomicReference<Future<Throwable>> recompute = new AtomicReference<>();
        seasoningClaimedHook.set(() -> {
            recompute.set(pool.submit(() -> attempt(() -> recipes.calculateCost(factory, low))));
            recomputeFinishedWhileDeleteHeldTheFamily.set(completesWithin(recompute.get(), 3));
        });

        Throwable deleted = pool.submit(() -> attempt(() -> seasoning.deleteBinding(factory, high, binding, revision)))
                .get(60, TimeUnit.SECONDS);
        assertThat(recompute.get()).as("插桩触发了: 重算是在调料删除 claimRevision 之后发出的").isNotNull();
        Throwable recomputed = recompute.get().get(60, TimeUnit.SECONDS);

        assertThat(deleted).as("调料删除成功, 没有被 PG 判死锁").isNull();
        assertThat(recomputed).as("同族重算成功, 没有被 PG 判死锁").isNull();
        assertThat(recomputeFinishedWhileDeleteHeldTheFamily.get())
                .as("调料删除持有整族配方头期间, 重算拿不到族锁, 不可能先提交").isFalse();
        assertThat(jdbc.queryForObject("select deleted_at is not null from bom_seasoning_items where id = ?",
                Boolean.class, binding)).isTrue();
        assertThat(jdbc.queryForObject("select seasoning_revision from bom_recipes where id = ?", Long.class, high))
                .isEqualTo(revision + 1);
    }

    /**
     * 克隆两成员族 (主产 id 较大, 按主产在前逐个复制) 与同族另一成员的重算并发。旧代码不锁源族, 由
     * cloneRelations 逐个成员锁「源头 + 新克隆」: 先锁 id 大的主产, 再锁 id 小的成员 —— 与按 id 升序锁整族的
     * 重算方向相反, PG 判死锁。现在克隆入口先按 id 升序锁住整个源族, 重算只是排队。
     */
    @Test
    void cloningAFamilyWhoseMainOutputHasTheHigherIdAndARecomputeOfTheLowerIdMemberDoNotDeadlock() throws Exception {
        String first = draftWithOneRawLineAtOldPrice();
        String second = draftWithOneRawLineAtOldPrice(secondProduct());
        String family = jdbc.queryForObject("select bom_family_id from bom_recipes where id = ?", String.class, first);
        jdbc.update("update bom_recipes set bom_family_id = ? where id = ?", family, second);
        String low = first.compareTo(second) < 0 ? first : second;
        String high = low.equals(first) ? second : first;
        jdbc.update("update bom_recipes set target_terminal_node_id = 't-low', output_role = 'CO_PRODUCT' where id = ?", low);
        jdbc.update("update bom_recipes set target_terminal_node_id = 't-high', output_role = 'MAIN' where id = ?", high);
        jdbc.update("update bom_recipe_items set cost_scope_key = 't-low' where recipe_id = ? and cost_scope_key is not null", low);
        jdbc.update("update bom_recipe_items set cost_scope_key = 't-high' where recipe_id = ? and cost_scope_key is not null", high);

        AtomicBoolean recomputeFinishedWhileCloneHeldTheSource = new AtomicBoolean();
        AtomicReference<Future<Throwable>> recompute = new AtomicReference<>();
        cloneMemberHook.set(() -> {
            recompute.set(pool.submit(() -> attempt(() -> recipes.calculateCost(factory, low))));
            recomputeFinishedWhileCloneHeldTheSource.set(completesWithin(recompute.get(), 3));
        });

        Throwable cloned = pool.submit(() -> attempt(() -> recipes.cloneRecipe(factory, high)))
                .get(60, TimeUnit.SECONDS);
        assertThat(recompute.get()).as("插桩触发了: 重算是在克隆复制完主产之后发出的").isNotNull();
        Throwable recomputed = recompute.get().get(60, TimeUnit.SECONDS);

        assertThat(cloned).as("克隆成功, 没有被 PG 判死锁").isNull();
        assertThat(recomputed).as("同族重算成功, 没有被 PG 判死锁").isNull();
        assertThat(recomputeFinishedWhileCloneHeldTheSource.get())
                .as("克隆持有整个源族期间, 重算拿不到族锁, 不可能先提交").isFalse();
        assertThat(jdbc.queryForObject("select count(*) from bom_recipes where bom_family_id <> ? and factory_id = ?",
                Integer.class, family, factory)).as("克隆出一个两成员的新族").isEqualTo(2);
    }

    /**
     * 标准出成率应用只改配方头自身, 但配方头是整行 UPDATE。应用读到配方之后、提交之前, 别的事务 (如调价重算)
     * 提交了 total_cost: 不锁的话, 应用提交时把旧 total_cost 写回。现在应用前先锁这些配方头, 对方排到应用提交之后。
     */
    @Test
    void applyingAYieldRateDoesNotWriteBackAStaleTotalCostCommittedMeanwhile() throws Exception {
        String recipeId = draftWithOneRawLineAtOldPrice();
        assertThat(attempt(() -> recipes.activateRecipe(factory, recipeId, 1L))).isNull();

        AtomicReference<Future<?>> concurrent = new AtomicReference<>();
        AtomicBoolean concurrentCommittedInsideApply = new AtomicBoolean();
        Throwable applied = attempt(() -> tx.executeWithoutResult(status -> {
            BomYieldApplyRequest request = new BomYieldApplyRequest();
            request.setRecipeId(recipeId);
            request.setYieldRate(new BigDecimal("95"));
            assertThat(yields.recalculateApply(factory, List.of(request)).getApplied()).isEqualTo(1);
            concurrent.set(pool.submit(() -> jdbc.update(
                    "update bom_recipes set total_cost = 9.9, total_material_cost = 9.9 where id = ?", recipeId)));
            concurrentCommittedInsideApply.set(completesWithin(concurrent.get(), 3));
        }));
        concurrent.get().get(60, TimeUnit.SECONDS);

        assertThat(applied).as("出成率应用成功").isNull();
        assertThat(concurrentCommittedInsideApply.get())
                .as("应用持有配方头期间, 并发写成本拿不到行锁, 不可能先提交").isFalse();
        Map<String, Object> head = jdbc.queryForMap(
                "select overall_yield_rate, total_cost from bom_recipes where id = ?", recipeId);
        assertThat((BigDecimal) head.get("overall_yield_rate")).isEqualByComparingTo("95");
        assertThat((BigDecimal) head.get("total_cost"))
                .as("并发提交的成本没被应用用旧值整行写回").isEqualByComparingTo("9.9");
    }

    /**
     * 版本审批同步配方头「当前版本」时, 原来是读实体再 save (整行 UPDATE)。审批读到配方之后别的事务提交了
     * total_cost, 审批提交时会把旧值写回。现在是定向 UPDATE 只写两列, 对方排到审批提交之后, 成本保留。
     */
    @Test
    void approvingABomVersionDoesNotWriteBackAStaleTotalCostCommittedMeanwhile() throws Exception {
        String recipeId = draftWithOneRawLineAtOldPrice();
        assertThat(attempt(() -> recipes.activateRecipe(factory, recipeId, 1L))).isNull();
        // 审批前先把两列和 updated_at 拨到与审批结果不同的值, 读回时才能证明「是审批写的」
        jdbc.update("update bom_recipes set version = 7, is_current = false, "
                + "updated_at = timestamp '2020-01-01 00:00:00' where id = ?", recipeId);
        String versionId = versions.createDraft(factory, recipeId, 1L).getId();

        AtomicReference<Future<?>> concurrent = new AtomicReference<>();
        AtomicBoolean concurrentCommittedInsideApproval = new AtomicBoolean();
        Throwable approved = attempt(() -> tx.executeWithoutResult(status -> {
            versions.approve(factory, versionId, 1L);
            concurrent.set(pool.submit(() -> jdbc.update(
                    "update bom_recipes set total_cost = 9.9, total_material_cost = 9.9 where id = ?", recipeId)));
            concurrentCommittedInsideApproval.set(completesWithin(concurrent.get(), 3));
        }));
        concurrent.get().get(60, TimeUnit.SECONDS);

        assertThat(approved).as("版本审批成功").isNull();
        assertThat(concurrentCommittedInsideApproval.get())
                .as("审批已写配方头, 并发写成本要等审批提交").isFalse();
        Map<String, Object> head = jdbc.queryForMap(
                "select is_current, version, total_cost, updated_at > timestamp '2020-01-02' as touched "
                        + "from bom_recipes where id = ?", recipeId);
        assertThat(head.get("is_current")).as("审批把 is_current 从 false 改成 true").isEqualTo(true);
        assertThat(head.get("version")).as("当前版本号同步成审批的版本").isEqualTo(1);
        assertThat(head.get("touched")).as("定向 UPDATE 自己写了 updated_at (native SQL 绕过 @PreUpdate)")
                .isEqualTo(true);
        assertThat((BigDecimal) head.get("total_cost"))
                .as("并发提交的成本没被审批用旧值整行写回").isEqualByComparingTo("9.9");
    }

    /** 定向 UPDATE 是 native SQL, 绕过实体的 @Where(deleted_at IS NULL): 已软删的配方不能被重新标成当前版本。 */
    @Test
    void markingTheCurrentVersionLeavesASoftDeletedRecipeUntouched() {
        String recipeId = draftWithOneRawLineAtOldPrice();
        jdbc.update("update bom_recipes set deleted_at = now(), is_current = false, version = 7 where id = ?", recipeId);

        Integer changed = tx.execute(status -> recipeRepository.markCurrentVersion(recipeId, factory, 3));

        assertThat(changed).as("软删的配方不在更新范围内").isZero();
        Map<String, Object> head = jdbc.queryForMap(
                "select is_current, version from bom_recipes where id = ?", recipeId);
        assertThat(head.get("is_current")).isEqualTo(false);
        assertThat(head.get("version")).isEqualTo(7);
    }

    /**
     * 加锁前读进来的明细行在加锁前被别的事务删掉: 第一次拿族锁时刷新它找不到行。Hibernate 此时已把事务标成只能回滚,
     * 吞掉继续只会在提交时变成 UnexpectedRollbackException (500); 必须当场按冲突报 409, 让用户刷新重试。
     */
    @Test
    void aLineReadBeforeTheLockAndDeletedMeanwhileIsA409NotARollbackOnCommit() throws Exception {
        String recipeId = draftWithOneRawLineAtOldPrice();
        long line = lineOf(recipeId);

        Throwable outcome = attempt(() -> tx.executeWithoutResult(status -> {
            assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipeId)).hasSize(1);
            try {
                pool.submit(() -> jdbc.update("delete from bom_recipe_items where id = ?", line))
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            recipes.calculateCost(factory, recipeId);
        }));

        assertThat(outcome).as("按冲突当场拒绝, 不是提交时的 UnexpectedRollbackException")
                .isInstanceOf(BusinessException.class);
        BusinessException conflict = (BusinessException) outcome;
        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict.getErrorCode()).isEqualTo(BomRecipeHeadLocks.FAMILY_LOCK_STALE);
    }

    /**
     * 加锁前就改脏了同族配方头: 第一次加锁时它会被 auto-flush 按加锁前的旧快照写出, 盖掉这之间别人提交的列,
     * 刷新也救不回来。必须拒绝 (程序错误), 而不是静默写出。阴性对照见上: 只读不改的同族对象照常刷新放行。
     */
    @Test
    void aFamilyHeadChangedBeforeTheLockIsRefusedInsteadOfFlushedFromAStaleSnapshot() {
        String recipeId = draftWithOneRawLineAtOldPrice();

        Throwable outcome = attempt(() -> tx.executeWithoutResult(status -> {
            BomRecipe head = recipeRepository.findById(recipeId).orElseThrow();
            head.setNotes("R3-加锁前改的");
            recipes.calculateCost(factory, recipeId);
        }));

        assertThat(outcome).as("加锁前改脏同族对象 → 拒绝").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("加锁前已被本事务改动");
        assertThat(jdbc.queryForObject("select notes from bom_recipes where id = ?", String.class, recipeId))
                .as("被拒绝的事务整体回滚, 什么都没写出").isNull();
    }

    private void assertConsistentAtNewPrice(String recipeId, long line, long proposal) {
        Map<String, Object> item = jdbc.queryForMap(
                "select unit_price, item_cost from bom_recipe_items where id = ?", line);
        Map<String, Object> head = jdbc.queryForMap(
                "select status, is_current, total_material_cost from bom_recipes where id = ?", recipeId);
        assertThat((BigDecimal) item.get("unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) item.get("item_cost")).as("行成本 = 2 × 0.7501").isEqualByComparingTo("1.5002");
        assertThat(head.get("status")).isEqualTo("ACTIVE");
        assertThat(head.get("is_current")).isEqualTo(true);
        assertThat((BigDecimal) head.get("total_material_cost"))
                .as("头成本必须等于已提交明细行之和, 不能是激活用旧行算出的 1.0")
                .isEqualByComparingTo("1.5002");
        assertThat(jdbc.queryForObject(
                "select status from bom_price_adjustment_proposals where id = ?", String.class, proposal))
                .isEqualTo("APPROVED");
    }

    // ---- fixtures ----

    /** 走真实建配方入口 (新族、工艺骨架行), 再把那一行压回旧价 0.5 × 用量 2, 头成本 1.0。 */
    private String draftWithOneRawLineAtOldPrice() {
        return draftWithOneRawLineAtOldPrice(product);
    }

    private String draftWithOneRawLineAtOldPrice(String productTypeId) {
        CreateBomRecipeRequest request = new CreateBomRecipeRequest();
        request.setProductTypeId(productTypeId);
        BomRecipeItemDTO raw = new BomRecipeItemDTO();
        raw.setMaterialTypeId("RAW-WF-" + productTypeId);
        raw.setStandardQuantity(new BigDecimal("2"));
        raw.setUnit("kg");
        raw.setMaterialCategory("RAW");
        request.setItems(List.of(raw));
        String recipeId = recipes.createRecipe(factory, request).getId();
        assertThat(jdbc.queryForObject("select bom_family_id from bom_recipes where id = ?", String.class, recipeId))
                .as("建配方产出的是一个 BOM Family (族锁的对象)").isNotBlank();
        jdbc.update("update bom_recipe_items set unit_price = 0.5, yield_rate = 100, quantity_to_price_factor = 1, "
                + "item_cost = 1.0 where recipe_id = ?", recipeId);
        jdbc.update("update bom_recipes set total_material_cost = 1.0, total_cost = 1.0 where id = ?", recipeId);
        return recipeId;
    }

    /** 同厂第二个 SKU 及其工艺原料 (RAW-WF-{产品}), 用来拼一个两成员的族。 */
    private String secondProduct() {
        String second = product + "-B";
        tx.executeWithoutResult(status -> {
            ProductType sku = new ProductType();
            sku.setId(second);
            sku.setFactoryId(factory);
            sku.setCode("SKU-B-" + factory);
            sku.setName("QA-R3-成品B");
            sku.setUnit("盒");
            sku.setGramsPerUnit(new BigDecimal("500"));
            sku.setIsActive(true);
            sku.setCreatedBy(1L);
            products.saveAndFlush(sku);
            RawMaterialType raw = new RawMaterialType();
            raw.setId("RAW-WF-" + second);
            raw.setFactoryId(factory);
            raw.setCode("RAW-B-" + factory);
            raw.setName("QA-R3-原料B");
            raw.setCategory("RAW");
            raw.setUnit("kg");
            raw.setIsByproduct(false);
            raw.setSelfSupplied(false);
            raw.setIsActive(true);
            raw.setIsAbacaPackaging(false);
            raw.setCreatedBy(1L);
            raw.setUnitPrice(new BigDecimal("0.5"));
            raw.setMovingAvgPrice(new BigDecimal("0.7501"));
            materials.saveAndFlush(raw);
        });
        return second;
    }

    private long seasoningBinding(String recipeId, BomWorkflowRevisionService.CostScopeProfile profile) {
        return tx.execute(status -> {
            BomSeasoningItem item = new BomSeasoningItem();
            item.setRecipeId(recipeId);
            item.setFactoryId(factory);
            item.setWorkProcessId("WP-TEST");
            item.setWorkflowProcessNodeId("process");
            item.setCostScope(profile.costScope());
            item.setCostScopeKey(profile.costScopeKey());
            item.setMaterialTypeId(material);
            item.setSection("COOKING");
            item.setSeq(0);
            item.setName("QA-R3-调料");
            item.setDosagePerKgG(new BigDecimal("10"));
            item.setCountInSeasoning(true);
            return seasoningItems.saveAndFlush(item).getId();
        });
    }

    private long lineOf(String recipeId) {
        List<Long> lines = jdbc.queryForList(
                "select id from bom_recipe_items where recipe_id = ? and deleted_at is null", Long.class, recipeId);
        assertThat(lines).as("建配方并进工艺骨架后只有一行").hasSize(1);
        return lines.getFirst();
    }

    private long proposal(String recipeId, long lineId) {
        return tx.execute(s -> {
            BomPriceAdjustmentProposal p = new BomPriceAdjustmentProposal();
            p.setFactoryId(factory);
            p.setRecipeId(recipeId);
            p.setRecipeItemId(lineId);
            p.setMaterialTypeId(material);
            p.setCurrentUnitPrice(new BigDecimal("0.5"));
            p.setProposedUnitPrice(new BigDecimal("0.7501"));
            p.setDeltaAmount(new BigDecimal("0.2501"));
            p.setAffectedProductCount(1);
            p.setStatus(BomPriceAdjustmentProposal.Status.PENDING);
            p.setSourceType(BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE);
            return proposals.saveAndFlush(p).getId();
        });
    }

    private void stubUnitsAndWorkflow() {
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String rawUnit = invocation.getArgument(1);
            String code = "盒".equals(rawUnit) ? "box" : rawUnit;
            UnitDimension dimension = "kg".equals(code) || "g".equals(code) ? UnitDimension.MASS : UnitDimension.PACKAGE;
            return new UnitNormalizationResult(rawUnit, code, new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 6));
        });
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String left = invocation.getArgument(1);
            String right = invocation.getArgument(2);
            return canonical(left).equals(canonical(right));
        });
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        when(bomWorkflowRevisionService.autoBindUniqueDraft(anyString(), any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.bindExactRevision(anyString(), any(BomRecipe.class), anyLong()))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.resolveExactBinding(anyString(), any(BomRecipe.class), anyLong()))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).graph());
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(anyString(), any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).terminalOutputs());
    }

    private static String canonical(String raw) {
        return "盒".equals(raw) ? "box" : raw;
    }

    /** 固定工艺图: 原料 RAW-WF-{产品} → 工序 → 单一主产出 (100%)。与 BomRecipeDraftLifecycleJpaTest 同形。 */
    private BomWorkflowRevisionService.WorkflowBinding workflowBinding(BomRecipe recipe) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(100L);
        revision.setFactoryId(recipe.getFactoryId());
        revision.setProductTypeId(recipe.getProductTypeId());
        revision.setWorkflowId(10L);
        revision.setDefinitionVersion(1);
        revision.setSchemaVersion(1);
        revision.setRevisionHash("r3-revision");
        revision.setNodesJson("[]");
        revision.setEdgesJson("[]");
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setStructurallyComplete(true);
        recipe.setWorkflowRevisionId(revision.getId());
        recipe.setWorkflowId(revision.getWorkflowId());
        recipe.setWorkflowDefinitionVersion(revision.getDefinitionVersion());
        recipe.setWorkflowRevisionHash(revision.getRevisionHash());
        recipe.setWorkflowSchemaVersion(revision.getSchemaVersion());
        recipe.setWorkflowNodesSnapshotJson(revision.getNodesJson());
        recipe.setWorkflowEdgesSnapshotJson(revision.getEdgesJson());
        if (recipe.getTargetTerminalNodeId() == null) {
            // 只在第一次绑定时给终点; 两成员族的用例会把成员改成各自的终点, 之后的解析不能把它压回去
            recipe.setTargetTerminalNodeId("terminal");
        }
        recipe.setOutputRole(BomRecipe.OutputRole.MAIN);
        recipe.setCostAllocationRatio(new BigDecimal("100"));

        String materialTypeId = "RAW-WF-" + recipe.getProductTypeId();
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("skuId", materialTypeId);
        rawData.put("name", "Workflow input");
        ProductProcessWorkflowDTO.Node rawNode = new ProductProcessWorkflowDTO.Node(
                "raw-input", "RAW_MATERIAL", new ProductProcessWorkflowDTO.Position(0D, 0D), rawData);
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-TEST");
        processData.put("ports", List.of(
                Map.of("id", "input", "direction", "INPUT", "materialNodeId", "raw-input",
                        "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 0),
                Map.of("id", "output", "direction", "OUTPUT", "materialNodeId", "terminal",
                        "materialKind", "FINISHED_GOOD", "unit", String.valueOf(recipe.getOutputUnit()),
                        "ordinal", 0, "outputRole", "MAIN",
                        "costAllocationRatio", new BigDecimal("100"))));
        ProductProcessWorkflowDTO.Node process = new ProductProcessWorkflowDTO.Node(
                "process", "PROCESS", new ProductProcessWorkflowDTO.Position(100D, 0D), processData);
        Map<String, Object> terminalData = new LinkedHashMap<>();
        terminalData.put("skuId", recipe.getProductTypeId());
        terminalData.put("baseUnit", String.valueOf(recipe.getOutputUnit()));
        ProductProcessWorkflowDTO.Node terminal = new ProductProcessWorkflowDTO.Node(
                "terminal", "FINISHED_GOOD", new ProductProcessWorkflowDTO.Position(200D, 0D), terminalData);
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(rawNode, process, terminal);
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge("edge-input", "raw-input", "output", "process", "input"),
                new ProductProcessWorkflowDTO.Edge("edge-output", "process", "output", "terminal", "input"));
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(nodes);
        definition.setEdges(edges);
        BomWorkflowRevisionService.TerminalOutput target = new BomWorkflowRevisionService.TerminalOutput(
                "terminal", recipe.getProductTypeId(), "process", "output",
                BomRecipe.OutputRole.MAIN, new BigDecimal("100"), recipe.getOutputUnit(),
                BomWorkflowRevisionService.CostAllocationBasis.DECLARED);
        PinnedWorkflowGraph graph = new PinnedWorkflowGraph(
                revision.getId(), revision.getWorkflowId(), revision.getDefinitionVersion(),
                revision.getRevisionHash(), recipe.getProductTypeId(), "terminal",
                List.of(materialTypeId),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-TEST", 1)),
                nodes, edges);
        return new BomWorkflowRevisionService.WorkflowBinding(revision, definition, graph, List.of(target), target);
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
}

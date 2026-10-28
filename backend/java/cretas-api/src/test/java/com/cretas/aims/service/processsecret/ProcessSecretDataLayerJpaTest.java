package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.SkuAssemblyService;
import com.cretas.aims.service.impl.ProductWorkProcessServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Hibernate 上的工艺保密数据层:
 * <ol>
 *   <li>PostLoad 打标真的挂上了持久化单元 —— 读配方 / 工序链会被记录, 不在请求或显式作用域里不记录;</li>
 *   <li>复制类出口 (组装 SKU、复制工序链) 走真实服务和真实库: 无授权被拒、审计落库、什么也没复制;
 *       保密负责人照常复制。</li>
 * </ol>
 * 与 SkuAssemblyServiceTest 使用同一组注解, 共享同一个测试上下文。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SkuAssemblyService.class, ProductWorkProcessServiceImpl.class, ProcessSecretRecipeGuard.class,
        ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretReadTrackerConfiguration.class})
class ProcessSecretDataLayerJpaTest {

    static final String F = "F-PS-DATA";
    static final String SECRET = "PT-PS-SECRET";
    static final String TARGET = "PT-PS-TARGET";
    static final long OWNER = 9001L;
    static final long OPERATOR = 9002L;

    @Autowired EntityManager entityManager;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository recipeItems;
    @Autowired ProductWorkProcessRepository workProcesses;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretAccessEventRepository events;
    @Autowired SkuAssemblyService skuAssembly;
    @Autowired ProductWorkProcessServiceImpl workProcessService;
    @Autowired ProcessSecretAccessService accessService;
    Long userId;

    @BeforeEach
    void seed() {
        Factory factory = new Factory();
        factory.setId(F);
        factory.setName("Process Secret Data Factory");
        entityManager.persist(factory);
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("ps-data-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        userId = user.getId();
        productTypes.saveAndFlush(product(SECRET, "SECRET-TPL", user.getId()));
        productTypes.saveAndFlush(product(TARGET, "TARGET", user.getId()));

        ProductWorkProcess process = new ProductWorkProcess();
        process.setFactoryId(F);
        process.setProductTypeId(SECRET);
        process.setWorkProcessId("WP-SECRET-1");
        process.setProcessOrder(1);
        process.setStandardYieldRate(new BigDecimal("0.8100"));
        process.setIsActive(true);
        workProcesses.saveAndFlush(process);

        BomRecipe recipe = new BomRecipe();
        recipe.setId("BOM-PS-SECRET");
        recipe.setFactoryId(F);
        recipe.setRecipeCode("BOM-PS-SECRET");
        recipe.setProductTypeId(SECRET);
        recipe.setProductName("秘制牛肉");
        recipe.setVersion(1);
        recipe.setIsCurrent(true);
        recipe.setOutputQuantityPerUnit(new BigDecimal("1000.0000"));
        recipe.setOutputUnit("g");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setSourceType(BomRecipe.SourceType.MANUAL);
        recipes.saveAndFlush(recipe);

        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipe.getId());
        item.setFactoryId(F);
        item.setMaterialTypeId("MAT-PS-1");
        item.setMaterialName("秘方香料");
        item.setStandardQuantity(new BigDecimal("3.2500"));
        item.setYieldRate(new BigDecimal("95.00"));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setMaterialCategory("RAW");
        item.setSortOrder(1);
        item.setIsOptional(false);
        item.setPerPortion(false);
        recipeItems.saveAndFlush(item);

        policies.saveAndFlush(ProcessSecretPolicy.builder().factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build());
        accessService.invalidate(F);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    static ProductType product(String id, String code, Long createdBy) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setCode(code);
        p.setName(code);
        p.setCategory("finished");
        p.setUnit("kg");
        p.setIsActive(true);
        p.setCreatedBy(createdBy);
        return p;
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    // ------------------------------------------------------------ 打标

    @Test
    void loadingRecipeContentDuringARequestIsRecordedByTheRealHibernateListener() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(ProcessSecretReadTracker.reads()).isEmpty();

        recipeItems.findByRecipeIdOrderBySortOrderAsc("BOM-PS-SECRET");
        workProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(F, SECRET);

        assertThat(ProcessSecretReadTracker.reads()).contains(
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.RECIPE, F, "BOM-PS-SECRET"),
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET));
    }

    @Test
    void loadsOutsideAnyRequestOrToolScopeAreNotRecordedButAnExplicitScopeIs() {
        recipes.findById("BOM-PS-SECRET").orElseThrow();
        assertThat(ProcessSecretReadTracker.reads()).isEmpty();

        entityManager.clear();
        try (ProcessSecretReadTracker.Scope ignored = ProcessSecretReadTracker.open()) {
            recipes.findById("BOM-PS-SECRET").orElseThrow();
            assertThat(ProcessSecretReadTracker.reads()).containsExactly(
                    new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET));
        }
        assertThat(ProcessSecretReadTracker.reads()).isEmpty();
    }

    @Test
    void anEntityFirstLoadedInsideAnUntrackedSectionIsRecordedWhenANormalQueryLoadsItAgain() {
        entityManager.clear();
        try (ProcessSecretReadTracker.Scope ignored = ProcessSecretReadTracker.open()) {
            BomRecipe untracked = ProcessSecretReadTracker.untracked(() -> recipes.findById("BOM-PS-SECRET").orElseThrow());
            assertThat(ProcessSecretReadTracker.reads()).isEmpty();
            // open-in-view 下若留在持久化上下文里, 下面的普通查询拿到的是缓存实例, 不触发 PostLoad, 打标网就漏了
            assertThat(entityManager.contains(untracked)).isFalse();

            recipes.findById("BOM-PS-SECRET").orElseThrow();

            assertThat(ProcessSecretReadTracker.reads()).containsExactly(
                    new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET));
        }
    }

    @Test
    void anEntityAlreadyManagedBeforeTheUntrackedSectionStaysManaged() {
        entityManager.clear();
        BomRecipe managed = recipes.findById("BOM-PS-SECRET").orElseThrow();   // 段外载入 (可能正被当前事务修改)

        BomRecipe again = ProcessSecretReadTracker.untracked(() -> recipes.findById("BOM-PS-SECRET").orElseThrow());

        assertThat(again).isSameAs(managed);
        assertThat(entityManager.contains(managed)).isTrue();
    }

    // ------------------------------------------------------------ 复制类出口

    @Test
    void assemblingASkuFromAProtectedTemplateWithoutGrantIsRefusedAuditedAndCopiesNothing() {
        long productsBefore = productTypes.count();
        long recipesBefore = recipes.count();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            assertThatThrownBy(() -> skuAssembly.assemblesku(F, SECRET, null, "v1", null, userId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        }
        assertThat(productTypes.count()).isEqualTo(productsBefore);
        assertThat(recipes.count()).isEqualTo(recipesBefore);
        assertThat(denials(SECRET)).anySatisfy(e -> {
            assertThat(e.getActorId()).isEqualTo(OPERATOR);
            assertThat(e.getResourceRef()).contains("assemble-sku");
        });
    }

    @Test
    void theOwnerCanStillAssembleASku() {
        ProductType sku;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            sku = skuAssembly.assemblesku(F, SECRET, null, "v1", null, userId);
        }
        entityManager.flush();
        assertThat(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, sku.getId())).isPresent();
        assertThat(events.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(F, SECRET, PageRequest.of(0, 10)))
                .extracting(ProcessSecretAccessEvent::getAction)
                .contains(ProcessSecretAction.VIEW_FULL, ProcessSecretAction.EXPORT);
    }

    @Test
    void copyingAProtectedProcessChainWithoutGrantIsRefusedAndTheTargetStaysEmpty() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            assertThatThrownBy(() -> workProcessService.copyChain(F, SECRET, TARGET))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        }
        assertThat(workProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(F, TARGET)).isEmpty();
        assertThat(denials(SECRET)).anySatisfy(e -> assertThat(e.getResourceRef()).contains("copy-chain"));
    }

    @Test
    void theOwnerCanCopyTheProcessChain() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            assertThat(workProcessService.copyChain(F, SECRET, TARGET)).isEqualTo(1);
        }
        assertThat(workProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(F, TARGET)).hasSize(1);
    }

    @Test
    void changingAProtectedStandardYieldNeedsEditButStaffingChangesDoNot() {
        // 独立审查 #13: 工序链的标准出成率 / 顺序是工艺; 排人不是
        Long stepId = workProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(F, SECRET).get(0).getId();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            assertThatThrownBy(() -> workProcessService.update(F, stepId, com.cretas.aims.dto.ProductWorkProcessDTO
                    .builder().standardYieldRate(new BigDecimal("0.5")).build()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            assertThatThrownBy(() -> workProcessService.delete(F, stepId)).isInstanceOf(BusinessException.class);
            // 第二轮 #7: 批量排序同样是改工序顺序
            com.cretas.aims.dto.ProductWorkProcessDTO.SortItem sort = new com.cretas.aims.dto.ProductWorkProcessDTO.SortItem();
            sort.setId(stepId);
            sort.setProcessOrder(9);
            assertThatThrownBy(() -> workProcessService.batchSort(F, List.of(sort)))
                    .isInstanceOf(BusinessException.class);
            // 第二轮 #7: 复制到受保护的目标也要编辑授权 (源是不受保护的 TARGET)
            assertThatThrownBy(() -> workProcessService.copyChain(F, TARGET, SECRET))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            // 只改预计工时 (不碰工艺字段) 照常
            workProcessService.update(F, stepId, com.cretas.aims.dto.ProductWorkProcessDTO.builder()
                    .estimatedMinutesOverride(30).build());
        }
        entityManager.flush();
        entityManager.clear();
        assertThat(workProcesses.findById(stepId).orElseThrow().getStandardYieldRate())
                .isEqualByComparingTo("0.8100");
    }

    @Autowired com.cretas.aims.repository.ProductionReportRepository productionReports;

    @Test
    void theByProcessAggregateQueryAcceptsTheExclusionListOnARealDatabase() {
        // 原生 SQL 新增 NOT IN (:excludedProductTypeIds): 真实 Hibernate 解析 + 集合参数绑定 (哨兵 / 实际列表两种)
        java.time.LocalDate from = java.time.LocalDate.of(1900, 1, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2999, 12, 31);
        assertThat(productionReports.aggregateYieldByProcess(F, from, to, "", List.of("__none__"), List.of("__none__"))).isEmpty();
        assertThat(productionReports.aggregateYieldByProcess(F, from, to, "", List.of(SECRET, TARGET), List.of("plan-x"))).isEmpty();
        assertThat(productionReports.aggregateYieldByProcess(F, from, to, SECRET, List.of("__none__"), List.of("__none__"))).isEmpty();
    }

    private List<ProcessSecretAccessEvent> denials(String product) {
        return events.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(F, product, PageRequest.of(0, 20))
                .stream().filter(e -> "DENY".equals(e.getDecision())).toList();
    }
}

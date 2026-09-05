package com.cretas.aims.service.production;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workflow.PlanInputShareResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 🔴 标准出成率的<b>唯一</b>来源是配方头 {@code bom_recipes.overall_yield_rate}
 * (任务 RECON-LEGAL-VALUE-SENTINEL-20260906)。
 *
 * <h2>本类守的是什么</h2>
 *
 * <p>{@code ProductionPlanServiceImpl#resolveEffectiveProductYieldRate} 曾经在读表头之前
 * 先数「行级 {@code yieldRate != 100.00} 的行有几个」, 不等于 1 就 {@code return null},
 * 而且 {@code log.warn} 只在 count &gt; 1 时开口 —— count = 0 是<b>一行日志都没有的静默返回</b>。
 *
 * <p>行级出成率<b>已废弃</b>: 实体默认 100.00, {@code BomRecipeServiceImpl#applyDtoToItem}
 * (add/update 共用) 与 {@code #createInputSkeleton} 都硬写 100.00。
 * 2026-09-06 直查 prod: {@code bom_recipe_items} <b>144/144 行</b>(两个租户, 含软删)
 * 全是 100.00 ⇒ 那个计数<b>恒为 0</b> ⇒ 表头出成率<b>从来没被读到过</b>。
 *
 * <p>级联后果: PR #599 的 {@code PlanInputShareResolver#spreadToPorts} 在
 * {@code yieldRatePercent == null} 时返回 {@code Map.of()}
 * ({@code PlanInputShareResolver.java:105-109}) ⇒ 「按端口份额摊主料投入量」
 * 这个功能<b>一次都没算过</b>。所以下面第一条不是「解析器返回了 93.75」这种内部读数,
 * 它一路跑到<b>缺料预警条目</b>上 —— 那才是 #599 真的开始工作的证据(形态 B)。
 *
 * <h2>三条判据, 三个不同的数</h2>
 * <ol>
 *   <li>行级全 100.00 + 表头 93.75 ⇒ 解析器给 93.75, 端口需求量 = 100 ÷ 0.9375 = 106.666667</li>
 *   <li>表头 NULL ⇒ 给 null, 且<b>留一条 INFO</b>(阴性对照: ⛔ 不许静默 return null)</li>
 *   <li>表头恰好 100.00 ⇒ 给 <b>100.00</b>(合法零损耗), 端口需求量 = 计划量本身</li>
 * </ol>
 *
 * <p>⚠️ 第三条的旧行为是 {@code compareTo(new BigDecimal("100.00")) != 0} 把 100.00
 * 当「未配置」—— 那时这条路径连一个预警条目都产不出来(份额表为空, 行落进 byReporting)。
 * 所以「有没有条目」本身就是那次变异的可观测量, ⛔ 不是只有数字会变。
 */
class MaterialAdvisoryHeadLevelYieldIsTheSingleSourceTest {

    private static final String F = "LIUSHANMEN";
    private static final String PRODUCT = "PT-HEAD-YIELD";
    private static final String MAIN_MATERIAL = "RMT-MAIN-PORK";
    private static final String PORT_ID = "PORT-INPUT-1";
    /** 计划产出量 (kg)。挑 100 是为了让 ÷出成率 的结果一眼能验算。 */
    private static final BigDecimal PLANNED_QTY = new BigDecimal("100");

    private ProductionPlanServiceImpl service;
    private BomRecipeRepository recipeRepo;
    private PlanInputShareResolver shareResolver;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        // prod 真实形状: 绑了 workflow 投料端口的主料行 —— standardQuantity 恒为 null
        // (配比在画布端口上, 见 scanMaterialAdvisory 注释), 行级 yieldRate 恒为 100.00。
        BomRecipeItem mainPort = new BomRecipeItem();
        mainPort.setMaterialTypeId(MAIN_MATERIAL);
        mainPort.setMaterialName("主料猪肉");
        mainPort.setMaterialCategory("RAW");
        mainPort.setStandardQuantity(null);
        mainPort.setYieldRate(new BigDecimal("100.00"));
        mainPort.setUnit("kg");
        mainPort.setWorkflowInputPortId(PORT_ID);

        BomRecipeItemRepository bomRepo = mock(BomRecipeItemRepository.class);
        when(bomRepo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(List.of(mainPort));

        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findRawStockUnitsByMaterialType(anyString(), anyString())).thenReturn(List.of("kg"));
        // 库存 0 ⇒ 必然落进缺口清单, 这样 requiredQuantity 能直接从 warnings 读出来
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(MAIN_MATERIAL)))
                .thenReturn(BigDecimal.ZERO);

        recipeRepo = mock(BomRecipeRepository.class);

        // spreadToPorts 跑**真的**那一份 —— 它才是 #599 的被测行为; 只把「份额从哪来」桩掉。
        shareResolver = spy(new PlanInputShareResolver());
        doReturn(Map.of(PORT_ID, new BigDecimal("100")))
                .when(shareResolver).resolve(eq(F), any(ProductionPlan.class));

        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", recipeRepo);
        ReflectionTestUtils.setField(service, "planInputShareResolver", shareResolver);

        // 硬约束 3: 跨用例读数前先清节流表, 并把清了什么打出来 ——
        // 不清的话第二个用例的 INFO 会被 10 分钟窗口吃掉, 读成「没打日志」。
        Map<String, Long> throttle = (Map<String, Long>) ReflectionTestUtils.getField(
                ProductionPlanServiceImpl.class, "MISSING_YIELD_LOG_AT");
        assertNotNull(throttle, "节流表拿不到 ⇒ 下面「打了几条 INFO」的读数不可判定");
        System.out.println("[setUp] cleared MISSING_YIELD_LOG_AT, size before=" + throttle.size());
        throttle.clear();

        serviceLogger = (Logger) LoggerFactory.getLogger(ProductionPlanServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logs != null) {
            serviceLogger.detachAppender(logs);
        }
    }

    private ProductionPlan plan() {
        ProductionPlan p = new ProductionPlan();
        p.setId("PLAN-HEAD-YIELD");
        p.setPlanNumber("PLAN-HEAD-YIELD");
        p.setFactoryId(F);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(PLANNED_QTY);
        // 建计划这一刻必然还没转批次 ⇒ 份额走「读计划钉住的修订」那条路(见 PlanInputShareResolver)
        p.setSelectedWorkflowRevisionId(1L);
        return p;
    }

    private void headLevelYield(BigDecimal rate) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("RECIPE-" + PRODUCT);
        recipe.setFactoryId(F);
        recipe.setProductTypeId(PRODUCT);
        recipe.setProductName("红烧肉-成品500g");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(rate);
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                eq(F), eq(PRODUCT), eq(BomRecipe.Status.ACTIVE))).thenReturn(Optional.of(recipe));
    }

    private BigDecimal resolvedYield() {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "resolveEffectiveProductYieldRate", F, PRODUCT);
    }

    @SuppressWarnings("unchecked")
    private List<ProductionPlanMaterialAdvisoryDTO.Item> advise() {
        return (List<ProductionPlanMaterialAdvisoryDTO.Item>) ReflectionTestUtils.invokeMethod(
                service, "buildMaterialAdvisoryItems", F, plan());
    }

    private long missingYieldInfos() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("标准出成率未配置"))
                .count();
    }

    // ------------------------------------------------------------------ 判据 1

    @Test
    @DisplayName("🔴 行级全 100.00 + 表头 93.75 ⇒ 解析器给 93.75 (旧代码在这里恒 null)")
    void headLevelYieldIsReadEvenWhenEveryItemYieldIs100() {
        headLevelYield(new BigDecimal("93.75"));

        BigDecimal resolved = resolvedYield();

        assertNotNull(resolved, "解析器返回 null ⇒ 那道 mainMaterialCount 闸又回来了 "
                + "(prod 上 144/144 行都是 100.00, 这个计数恒为 0)");
        assertEquals(0, resolved.compareTo(new BigDecimal("93.75")), "实际 " + resolved);
    }

    @Test
    @DisplayName("🔴 #599 的行为证据: 端口份额真的摊出了投入量 100 ÷ 0.9375 = 106.666667")
    void portRequirementsAreComputedFromHeadLevelYield() {
        headLevelYield(new BigDecimal("93.75"));

        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise();

        // 阳性对照: 旧代码在这里是**空表**(spreadToPorts 拿到 null 出成率 → Map.of() →
        // 这一行 standardQuantity 也是 null → 落进 byReporting, 一条预警都不产出)。
        assertEquals(1, items.size(), "缺料预警条目数不是 1 ⇒ 端口摊分没跑起来。实际 " + items);
        ProductionPlanMaterialAdvisoryDTO.Item item = items.get(0);
        assertEquals(MAIN_MATERIAL, item.getMaterialTypeId());
        assertEquals(0, item.getRequiredQuantity().compareTo(new BigDecimal("106.666667")),
                "需要 " + item.getRequiredQuantity() + " (期望 106.666667 = 100 ÷ 0.9375)");
        assertEquals(0, item.getShortageQuantity().compareTo(new BigDecimal("106.666667")));
    }

    // ------------------------------------------------------------------ 判据 2

    @Test
    @DisplayName("阴性对照: 表头 NULL ⇒ 返回 null, 但**必须**留一条 INFO 指名道姓说是哪个配方")
    void missingHeadLevelYieldReturnsNullAndSaysSo() {
        headLevelYield(null);

        assertNull(resolvedYield(), "表头没配却算出了一个出成率 ⇒ 兜底把「不知道」翻译成了一个读数");

        assertEquals(1, missingYieldInfos(),
                "「没配出成率」没有留痕(或留了多条) ⇒ 静默 return null 回来了。实际日志: "
                        + logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        String msg = logs.list.get(0).getFormattedMessage();
        assertTrue(msg.contains(PRODUCT), "日志里没有 productTypeId, 定位不到是哪个配方: " + msg);
        assertTrue(msg.contains(F), "日志里没有 factoryId: " + msg);
    }

    @Test
    @DisplayName("阴性对照: 表头 NULL ⇒ 端口摊不出来 ⇒ 一条预警都不产出(⛔ 不能拿 0 顶替)")
    void missingHeadLevelYieldProducesNoFabricatedRequirement() {
        headLevelYield(null);

        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise();

        assertTrue(items.isEmpty(),
                "算不出来却产出了预警条目 ——「需要 0kg 主料」是个看起来很确定的假答案。实际 " + items);
    }

    @Test
    @DisplayName("节流: 同一个配方连问三次, INFO 只打一条(⛔ 不刷屏, 但也不静默)")
    void missingYieldLogIsThrottledPerRecipe() {
        headLevelYield(null);

        resolvedYield();
        resolvedYield();
        resolvedYield();

        assertEquals(1, missingYieldInfos(),
                "节流失效 ⇒ 缺料预警每开一次界面就刷一条。实际条数 " + missingYieldInfos());
    }

    // ------------------------------------------------------------------ 判据 3

    @Test
    @DisplayName("🔴 表头恰好 100.00 ⇒ 返回 100.00 —— 它是合法的零损耗值, ⛔ 不是「未配置」哨兵")
    void headLevelYieldOf100IsALegalValueNotASentinel() {
        headLevelYield(new BigDecimal("100.00"));

        BigDecimal resolved = resolvedYield();

        assertNotNull(resolved, "100.00 又被当成「未配置」了 —— 那个哨兵已经在 2026-09-06 拆掉");
        assertEquals(0, resolved.compareTo(new BigDecimal("100.00")), "实际 " + resolved);
        assertEquals(0, missingYieldInfos(), "配了 100.00 却报「未配置」⇒ 判据和日志说的不是一件事");
    }

    @Test
    @DisplayName("🔴 表头 100.00 的行为证据: 零损耗 ⇒ 投入量 == 计划产出量 100 (⛔ 不是「没有条目」)")
    void headLevelYieldOf100SpreadsOneToOne() {
        headLevelYield(new BigDecimal("100.00"));

        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise();

        assertFalse(items.isEmpty(),
                "一条预警都没有 ⇒ 100.00 被当成哨兵后份额表为空, 这一行落进了 byReporting");
        assertEquals(0, items.get(0).getRequiredQuantity().compareTo(new BigDecimal("100")),
                "需要 " + items.get(0).getRequiredQuantity() + " (期望 100 = 100 ÷ 1.00)");
    }
}

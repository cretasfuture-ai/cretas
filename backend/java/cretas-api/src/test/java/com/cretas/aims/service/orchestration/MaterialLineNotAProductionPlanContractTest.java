package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.sales.SellableLineDomainResolver;
import com.cretas.aims.service.sales.SellableLineDomainResolver.SellableDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售订单卖物料时，<b>不能</b>拿物料的 id 去建生产计划。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code sales_order_items.product_type_id} <b>没有</b>外键（一列同时装成品 id 和物料 id），
 * 而 {@code production_plans.product_type_id} <b>有</b>
 * （{@code fkde8i961fabdlo5u4g6xiaqmyy → product_types(id)}）。
 * 财审联动原来把订单行的 id 原样透传去建计划 ⇒ 物料行必违约。
 *
 * <h2>prod 实证（2026-08-19，error 流全量；口径写在数字后面）</h2>
 *
 * <pre>
 *   提及约束名的行            40   全部落在 2026-08-13 一天
 *   带 Key 明细的 DETAIL 行   38   撞的 id 共 5 个:
 *       RMT_1785998666207            14  牛外脊西冷MB2+谷饲100天  原料
 *       RMT_1782891387679             9  1.75L李锦记薄盐生抽      调味料
 *       RMT_74b0b790-…                7  鸡油                    原料
 *       RMT_1784271051888             7  温氏黄油鸡              原料
 *       b4f16b12-003d-…               1  两张表都查不到           ← 不是物料
 *   production_plans 里 PP-AUTO% 的行数: 0
 * </pre>
 *
 * <h2>🔴 为什么只判「是不是物料」不够</h2>
 *
 * <p>5 个撞库 id 里有 1 个（{@code b4f16b12-…}「干式熟成鸡（半只）」）在
 * {@code product_types} 和 {@code raw_material_types} <b>两张表里都查不到</b>。
 * {@code isMaterialLine} 对它返回 {@code false} ⇒ 它会照旧走成品路径、照旧撞同一个外键。
 * 所以判据是三态（成品 / 物料 / 主数据缺失），只有第一态能进 {@code production_plans}。
 *
 * <h2>🔴 连坐：这不只是「这一行没建成计划」</h2>
 *
 * <p>建计划的异常一路抛到 {@code onSalesOrderFinanceApproved} 的 catch，
 * 而那个 catch 在 {@code for} 循环<b>外面</b> ⇒ 排在物料行后面的<b>成品行</b>连预留都不执行。
 * prod 上 {@code SO-20260813-0001}(LIUSHANMEN) 正是「温氏黄油鸡 + 吸塑盒」这种多物料行的单。
 * {@link #finishedGoodLineAfterAMaterialLineStillGetsReserved()} 钉的就是这条。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("物料行 / 主数据缺失行 不得进入 production_plans")
class MaterialLineNotAProductionPlanContractTest {

    private static final String FACTORY = "LIUSHANMEN";
    private static final String SO_ID = "SO-20260813-0001";
    /** prod 上真实撞过外键的物料 id（牛外脊西冷MB2+谷饲100天，14 次）。 */
    private static final String MATERIAL_ID = "RMT_1785998666207";
    /** prod 上真实撞过外键、且两张表都查不到的那一个（干式熟成鸡（半只），1 次）。 */
    private static final String ORPHAN_ID = "b4f16b12-003d-4b64-a464-d94d69e17292";
    private static final String PRODUCT_ID = "PT-REAL-FINISHED-GOOD";

    @Mock private InventoryMatchingService inventoryMatchingService;
    @Mock private BomExpansionService bomExpansionService;
    @Mock private ProcurementSuggestionService procurementSuggestionService;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private BatchConsumptionService batchConsumptionService;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SellableLineDomainResolver sellableLineDomainResolver;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    // ───────────────────────── 装配 ─────────────────────────

    private void givenApprovedOrderWithLines(LineItemMatch... lines) {
        SalesOrder order = new SalesOrder();
        order.setId(SO_ID);
        order.setFactoryId(FACTORY);
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(order));

        StockCheckResult result = new StockCheckResult();
        result.setSalesOrderId(SO_ID);
        result.setLineItems(List.of(lines));
        when(inventoryMatchingService.checkAvailability(FACTORY, SO_ID)).thenReturn(result);
    }

    /** 缺口为正 ⇒ isFullySatisfied() 为 false ⇒ 走「建生产计划」那条分支。 */
    private static LineItemMatch shortLine(String productTypeId, String name) {
        LineItemMatch m = new LineItemMatch();
        m.setSalesOrderItemId("ITEM-" + productTypeId);
        m.setProductTypeId(productTypeId);
        m.setProductTypeName(name);
        m.setRequiredQuantity(new BigDecimal("2"));
        m.setAvailableQuantity(BigDecimal.ZERO);
        m.setShortfallQuantity(new BigDecimal("2"));
        return m;
    }

    private static LineItemMatch satisfiedLine(String productTypeId, String name) {
        LineItemMatch m = shortLine(productTypeId, name);
        m.setAvailableQuantity(new BigDecimal("5"));
        m.setShortfallQuantity(new BigDecimal("-3"));
        return m;
    }

    /** 让「建计划」这条路能一路走通（本类的阳性对照要用）。 */
    private void givenProductTypeExists(String productTypeId) {
        ProductType pt = new ProductType();
        pt.setId(productTypeId);
        pt.setUnit("kg");
        when(productTypeRepository.findById(productTypeId)).thenReturn(Optional.of(pt));
        when(productionPlanRepository.saveAndFlush(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // ⚠️ BOM 展开跑在 TransactionTemplate(transactionManager) 里, 而本测试没有装配
        //    transactionManager ⇒ 它会 NPE, 并被 createProductionPlanFromSO 里既有的
        //    「BOM展开/采购建议生成失败(不影响PP创建)」catch 吞掉。
        //    本类只关心「计划建没建出来」, 那一步在 BOM 之前, 所以不受影响。
    }

    private void approve() {
        orchestrator.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, FACTORY, SO_ID, 1309L));
    }

    // ───────────────────────── 阳性对照 ─────────────────────────

    /**
     * 🔴 没有这一条，下面所有 {@code never()} 断言都可能是恒真式 ——
     * 「一张计划都没建」和「这条路根本没跑起来」长得一模一样。
     */
    @Test
    @DisplayName("阳性对照: 成品行缺货时确实会建出生产计划(否则 never() 全是恒真式)")
    void finishedGoodShortfallDoesCreateAProductionPlan() {
        when(sellableLineDomainResolver.resolve(FACTORY, PRODUCT_ID))
                .thenReturn(SellableDomain.FINISHED_GOOD);
        givenProductTypeExists(PRODUCT_ID);
        givenApprovedOrderWithLines(shortLine(PRODUCT_ID, "真·成品"));

        approve();

        ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getProductTypeId())
                .as("阳性对照: 成品行必须能建出计划, 否则本类其它断言没有意义")
                .isEqualTo(PRODUCT_ID);
    }

    // ───────────────────────── 主断言 ─────────────────────────

    @Test
    @DisplayName("🔴 物料行缺货 → 不建生产计划(生产计划造的是成品, 且那一列有外键)")
    void materialLineNeverBecomesAProductionPlan() {
        when(sellableLineDomainResolver.resolve(FACTORY, MATERIAL_ID))
                .thenReturn(SellableDomain.MATERIAL);
        givenApprovedOrderWithLines(shortLine(MATERIAL_ID, "牛外脊西冷MB2+谷饲100天"));

        approve();

        verify(productionPlanRepository, never()).saveAndFlush(any());
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 两张表都查不到的 id → 也不建(它不是物料, 只判 isMaterialLine 会漏掉它)")
    void orphanLineNeverBecomesAProductionPlan() {
        when(sellableLineDomainResolver.resolve(FACTORY, ORPHAN_ID))
                .thenReturn(SellableDomain.UNKNOWN);
        givenApprovedOrderWithLines(shortLine(ORPHAN_ID, "干式熟成鸡（半只）"));

        approve();

        verify(productionPlanRepository, never()).saveAndFlush(any());
        verify(productionPlanRepository, never()).save(any());
    }

    /**
     * 连坐那一条 —— 本类最重要的断言。
     *
     * <p>物料行排在<b>前面</b>。旧代码在它身上抛异常, 而 catch 在 for 循环外面,
     * 于是排在后面的成品行<b>连库存预留都不会执行</b>。
     */
    @Test
    @DisplayName("🔴 混单: 物料行在前也不能拖累后面成品行的库存预留")
    void finishedGoodLineAfterAMaterialLineStillGetsReserved() {
        when(sellableLineDomainResolver.resolve(FACTORY, MATERIAL_ID))
                .thenReturn(SellableDomain.MATERIAL);
        givenApprovedOrderWithLines(
                shortLine(MATERIAL_ID, "温氏黄油鸡"),
                satisfiedLine(PRODUCT_ID, "真·成品"));

        approve();

        verify(inventoryMatchingService, times(1)).reserveStock(
                eq(FACTORY), eq(SO_ID), eq("ITEM-" + PRODUCT_ID),
                eq(PRODUCT_ID), any(BigDecimal.class));
        verify(productionPlanRepository, never()).saveAndFlush(any());
    }

    /**
     * 兜底闸（choke point）—— 与上面的路由判据是<b>两层</b>，都要有。
     *
     * <p>路由判据（{@code canBecomeAProductionPlan}）挡的是「现在这个调用方」；
     * 这一条挡的是「将来任何新调用方」。它替换掉的是原来那个
     * {@code productTypeRepository.findById(id).orElse("kg")} —— 代码在崩溃前 8 行
     * 就已经问过库、拿到了「没有这个产品」，然后把答案丢掉了（形态 A¹⁰）。
     */
    @Test
    @DisplayName("🔴 兜底闸: 即使绕过路由判据, 建计划本身也必须拒绝不在 product_types 里的 id")
    void createPlanItselfRefusesAnIdThatIsNotAProductType() {
        // 让路由判据放行(谎称它是成品), 单独考验 createProductionPlanFromSO 自己
        when(sellableLineDomainResolver.resolve(FACTORY, MATERIAL_ID))
                .thenReturn(SellableDomain.FINISHED_GOOD);
        when(productTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.empty());
        givenApprovedOrderWithLines(shortLine(MATERIAL_ID, "牛外脊西冷MB2+谷饲100天"));

        approve();

        verify(productionPlanRepository, never()).saveAndFlush(any());
    }

    /**
     * 阴性对照：证明「产品存在但没填单位」<b>不</b>被兜底闸拦下。
     *
     * <p>没有这一条，兜底闸可能是「只要 unit 为空就拒」——那会误伤一批合法产品。
     * 原代码把「查不到产品」和「查到了但 unit 为空」折叠成同一个 {@code .orElse("kg")}，
     * 修复的要点正是把这两件事拆开，所以要各钉一条。
     */
    @Test
    @DisplayName("阴性对照: 产品存在但 unit 为空 → 仍然建计划, 单位兜底 kg")
    void productWithBlankUnitStillGetsAPlanWithKgFallback() {
        when(sellableLineDomainResolver.resolve(FACTORY, PRODUCT_ID))
                .thenReturn(SellableDomain.FINISHED_GOOD);
        ProductType noUnit = new ProductType();
        noUnit.setId(PRODUCT_ID);
        noUnit.setUnit("   ");
        when(productTypeRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(noUnit));
        when(productionPlanRepository.saveAndFlush(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        givenApprovedOrderWithLines(shortLine(PRODUCT_ID, "单位没填的成品"));

        approve();

        ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPlannedUnit())
                .as("「查不到产品」要炸, 「查到了但没填单位」才轮到 kg 兜底 —— 两件事不能再折叠")
                .isEqualTo("kg");
    }

    // ───────────── 判据本身: 三态 ↔ isMaterialLine 布尔视图 等价 ─────────────

    /**
     * 形态 D：判定原来有两份（{@code SalesServiceImpl} 一份私有的、联动侧没有）。
     * 收敛成一份之后，必须钉住薄壳的语义没变 —— 否则发货扣库存那条路会跟着一起改。
     */
    @Test
    @DisplayName("判据收敛: isMaterialLine 恰好等价于「三态 == MATERIAL」")
    void booleanViewIsExactlyTheMaterialState() {
        ProductTypeRepository productTypes = org.mockito.Mockito.mock(ProductTypeRepository.class);
        RawMaterialTypeRepository materials = org.mockito.Mockito.mock(RawMaterialTypeRepository.class);
        SellableLineDomainResolver real = new SellableLineDomainResolver(productTypes, materials);

        com.cretas.aims.entity.RawMaterialType mat = new com.cretas.aims.entity.RawMaterialType();
        ProductType inactiveProduct = new ProductType();
        inactiveProduct.setIsActive(false);
        ProductType activeProduct = new ProductType();
        activeProduct.setIsActive(true);

        // ① 纯物料 → MATERIAL / true
        when(materials.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.of(mat));
        when(productTypes.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.empty());
        assertThat(real.resolve(FACTORY, MATERIAL_ID)).isEqualTo(SellableDomain.MATERIAL);
        assertThat(real.isMaterialLine(FACTORY, MATERIAL_ID)).isTrue();

        // ② 既是物料又是【停用】商品(prod 实测 5 个 __RAW_WORKFLOW_OWNER__ 全是这一档) → MATERIAL
        String bothInactive = "RMT_1784220443823";
        when(materials.findByIdAndFactoryId(bothInactive, FACTORY)).thenReturn(Optional.of(mat));
        when(productTypes.findByIdAndFactoryId(bothInactive, FACTORY)).thenReturn(Optional.of(inactiveProduct));
        assertThat(real.resolve(FACTORY, bothInactive)).isEqualTo(SellableDomain.MATERIAL);

        // ③ 既是物料又是【启用】商品 → 按成品处理(前提破裂, 保持既有行为不静默改道)
        String bothActive = "RMT_BOTH_ACTIVE";
        when(materials.findByIdAndFactoryId(bothActive, FACTORY)).thenReturn(Optional.of(mat));
        when(productTypes.findByIdAndFactoryId(bothActive, FACTORY)).thenReturn(Optional.of(activeProduct));
        assertThat(real.resolve(FACTORY, bothActive)).isEqualTo(SellableDomain.FINISHED_GOOD);
        assertThat(real.isMaterialLine(FACTORY, bothActive)).isFalse();

        // ④ 只在 product_types 里 → FINISHED_GOOD
        when(materials.findByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(Optional.empty());
        when(productTypes.findByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(Optional.of(activeProduct));
        when(productTypes.findById(PRODUCT_ID)).thenReturn(Optional.of(activeProduct));
        assertThat(real.resolve(FACTORY, PRODUCT_ID)).isEqualTo(SellableDomain.FINISHED_GOOD);
        assertThat(real.isMaterialLine(FACTORY, PRODUCT_ID)).isFalse();

        // ⑤ 两张表都查不到 → UNKNOWN(而 isMaterialLine 对它是 false —— 正是漏网的那一档)
        when(materials.findByIdAndFactoryId(ORPHAN_ID, FACTORY)).thenReturn(Optional.empty());
        when(productTypes.findByIdAndFactoryId(ORPHAN_ID, FACTORY)).thenReturn(Optional.empty());
        when(productTypes.findById(ORPHAN_ID)).thenReturn(Optional.empty());
        assertThat(real.resolve(FACTORY, ORPHAN_ID)).isEqualTo(SellableDomain.UNKNOWN);
        assertThat(real.isMaterialLine(FACTORY, ORPHAN_ID))
                .as("UNKNOWN 在布尔视图里是 false —— 这正是为什么路由必须看三态而不是布尔")
                .isFalse();
    }
}

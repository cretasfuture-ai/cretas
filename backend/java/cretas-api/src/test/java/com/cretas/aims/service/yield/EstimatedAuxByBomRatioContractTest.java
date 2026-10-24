package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.service.bom.SeasoningRatioContract;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 闸 —— <b>辅料先估</b>（owner 裁定 2026-09-06）：已结单计划的辅料/调料在<b>完全没有</b>实际耗用
 * 记录时，按「BOM 配比 × <b>实际</b>主料投入」折算成本，并把每一条标上「按配比估算」。
 *
 * <h2>为什么有这道闸（2026-09-06 生产库只读实测）</h2>
 * <pre>
 * bom_seasoning_items 配了辅料的【已结单计划】   F006 29 个 / LIUSHANMEN 7 个
 * material_consumptions 里有 SEASONING 行的计划   F006  2 个 / LIUSHANMEN 0 个
 * </pre>
 * ⇒ 三十几个计划的辅料成本此前一律是 <b>¥0 且一声不吭</b>。那不是「辅料不要钱」，是没人录 ——
 * 按 0 计会系统性低估 COGS、虚增毛利。
 *
 * <h2>这道闸钉住的东西</h2>
 * <ol>
 *   <li>估出来了、金额对、并且带「按配比估算」标签（阳性）</li>
 *   <li><b>有</b>实际耗用记录的辅料<b>不</b>估（阴性对照，三种形态：有量的记录 / 量=0 的记录 /
 *       报工侧已归集的调料成本）—— 少一条这三者里的任何一条都会造成双计。
 *       ⚠️ 这两层<b>不是串联的双保险</b>: 有量的记录先把调料桶顶成非 0, 批级门就返回了,
 *       物料级过滤根本够不着 —— 它独有的作用面只有「量=0 的记录」和「记在别的 sourceType 下」</li>
 *   <li>批级门触发但 BOM 里还有<b>一条记录都没有</b>的辅料 ⇒ 写
 *       {@code AUX_PARTIALLY_RECORDED} marker（非阻断），⛔ 否则就是「静默少算」</li>
 *   <li>缺配比 / 缺单价 ⇒ 进 {@code missingCostItems} 并<b>阻断</b>，⛔ 不按 0 计（阴性对照）</li>
 *   <li>主料投入量折不成 kg ⇒ 整批不估并报缺，⛔ 不拿「能折的那部分」当基数</li>
 *   <li>#580 客供主料逻辑<b>零回归</b>：客供料仍然计 ¥0，但它的<b>物理投入量照样进估算基数</b></li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimatedAuxByBomRatioContractTest {

    private static final String F = "F006";
    private static final String ORDER = "SO-AUX-EST-001";
    private static final String PLAN = "PLAN-AUX-EST";
    private static final String RECIPE = "BOM-AUX-EST";
    private static final Integer VERSION = 3;
    private static final Long BATCH_ID = 9201L;
    private static final String BATCH_NO = "PB-AUX-EST-0001";

    /** 主料 80kg —— 估算基数。BOM 盐 10 g/kg ⇒ 0.8kg × ¥2/kg = ¥1.6000。 */
    private static final String RAW_KG = "80";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private YieldReportService yieldReportService;
    @Mock private BomVersionRepository bomVersionRepository;
    @Mock private BomSeasoningItemRepository bomSeasoningItemRepository;
    @InjectMocks private OrderCostBreakdownService service;

    // ──────────────────────────── 装配 ────────────────────────────

    /**
     * 一个「已结单、有 pinned BOM、主料 80kg、包材走实际领用」的批次。
     *
     * <p>⚠️ 必须给一行<b>实际包材领用</b>：否则 {@code resolvePinnedPackaging} 会因为
     * {@code bom_versions} 里没有快照而报 {@code PINNED_BOM_SNAPSHOT_MISSING}，
     * 把每个用例的 {@code costComplete} 都拉黑 —— 那样「缺配比会阻断」这条断言就成了恒真式
     * （本仓形态 B′：断言可能是恒真的，要先让它有机会绿）。
     */
    private void stubBatch(List<MaterialConsumption> extraConsumptions, BatchYieldDTO yield) {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN);
        p.setFactoryId(F);
        p.setSelectedBomRecipeId(RECIPE);
        p.setSelectedBomVersion(VERSION);
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of(p));
        when(planRepository.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(p));

        ProductionBatch b = new ProductionBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(F);
        b.setBatchNumber(BATCH_NO);
        b.setProductionPlanId(PLAN);
        b.setQuantity(new BigDecimal("100"));
        b.setEquipmentCost(BigDecimal.ZERO);
        b.setOtherCost(BigDecimal.ZERO);
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any())).thenReturn(List.of(b));
        when(yieldReportService.getYield(F, BATCH_ID)).thenReturn(yield);

        List<MaterialConsumption> rows = new ArrayList<>();
        rows.add(consumption("MB-RAW", "MT-RAW", "RAW_MATERIAL", RAW_KG, "10.00"));
        rows.add(consumption("MB-BOX", "MT-BOX", "PACKAGING", "100", "0.50"));
        rows.addAll(extraConsumptions);
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, F)).thenReturn(rows);

        stubMaterialBatch("MB-RAW", "原料-0001", "kg", "10.00", InventoryOwnership.COMPANY_OWNED);
        stubMaterialBatch("MB-BOX", "外箱-0001", "pcs", "0.50", InventoryOwnership.COMPANY_OWNED);
    }

    private void stubBatch(List<MaterialConsumption> extraConsumptions) {
        stubBatch(extraConsumptions, emptyYield());
    }

    private static BatchYieldDTO emptyYield() {
        return BatchYieldDTO.builder().totalLaborCost(BigDecimal.ZERO).steps(List.of()).build();
    }

    private MaterialConsumption consumption(String batchId, String materialTypeId, String sourceType,
                                            String qty, String unitPrice) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F);
        c.setBatchId(batchId);
        c.setMaterialTypeId(materialTypeId);
        c.setSourceType(sourceType);
        c.setQuantity(new BigDecimal(qty));
        if (unitPrice != null) {
            c.setUnitPrice(new BigDecimal(unitPrice));
            c.setTotalCost(new BigDecimal(qty).multiply(new BigDecimal(unitPrice)));
        }
        return c;
    }

    private void stubMaterialBatch(String id, String batchNumber, String unit, String unitPrice,
                                   InventoryOwnership ownership) {
        MaterialBatch m = new MaterialBatch();
        m.setId(id);
        m.setFactoryId(F);
        m.setBatchNumber(batchNumber);
        m.setQuantityUnit(unit);
        m.setOwnership(ownership);
        m.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        when(materialBatchRepository.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(m));
    }

    /** 一条 BOM 调料行。{@code dosage}/{@code price1} 传 null 就是「没填」。 */
    private BomSeasoningItem seasoning(String materialTypeId, String name, String dosage,
                                       String price1, String price2) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId(RECIPE);
        s.setFactoryId(F);
        s.setMaterialTypeId(materialTypeId);
        s.setName(name);
        s.setSection(BomSeasoningItem.SECTION_COOKING);
        s.setCountInSeasoning(true);
        s.setDosagePerKgG(dosage == null ? null : new BigDecimal(dosage));
        s.setPriceSource1(price1 == null ? null : new BigDecimal(price1));
        s.setPriceSource2(price2 == null ? null : new BigDecimal(price2));
        return s;
    }

    private void stubBom(BomSeasoningItem... items) {
        when(bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of(items));
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", bomSeasoningItemRepository);
    }

    private OrderCostBreakdownDTO compute() {
        return service.compute(F, ORDER, false);
    }

    // ──────────────────────────── 阳性 ────────────────────────────

    @Test
    @DisplayName("🔴 阳性: 辅料零耗用记录 ⇒ 按 BOM 配比×实际主料投入估算, 金额 0.8kg×¥2=¥1.6, "
            + "标「按配比估算」并计入 seasoningCost/totalCost")
    void estimatesAuxByBomRatioWhenNothingWasRecorded() {
        stubBatch(List.of());
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItemCount()).isEqualTo(1);
        OrderCostBreakdownDTO.EstimatedAuxItem line = dto.getEstimatedAuxItems().get(0);
        assertThat(line.getMaterialName()).isEqualTo("食用盐");
        assertThat(line.getDosagePerKgG()).isEqualByComparingTo("10");
        assertThat(line.getRawInputKg()).isEqualByComparingTo(RAW_KG);
        // 10 g/kg × 80kg = 800 g = 0.8 kg
        assertThat(line.getQuantity()).isEqualByComparingTo("0.8");
        assertThat(line.getUnit()).isEqualTo("kg");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("2.00");
        assertThat(line.getAmount()).isEqualByComparingTo("1.6000");
        // 标签是这条裁定的全部可见性所在 —— 少了它, 估的和实际领用的就分不开了。
        assertThat(line.getBasis()).isEqualTo(OrderCostBreakdownService.ESTIMATED_AUX_LABEL);
        assertThat(line.getBasis()).isEqualTo("按配比估算");
        assertThat(line.getCollectionStatus()).isEqualTo("ESTIMATED_BY_BOM_RATIO");
        assertThat(line.getPriceSource()).isEqualTo("BOM_SEASONING_PRICE_SOURCE1");
        assertThat(line.getRatioSource()).isEqualTo("BOM_SEASONING_ITEMS_LIVE");
        assertThat(line.getBatchNumber()).isEqualTo(BATCH_NO);
        assertThat(line.getBomRecipeId()).isEqualTo(RECIPE);
        assertThat(line.getBomVersion()).isEqualTo(VERSION);

        // 估算值就是调料成本 —— 它进总成本, 不是一条只给人看的备注。
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("1.6000");
        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getTotalCost()).isNotNull();
        // 主料 80×10 + 包材 100×0.5 + 估算辅料 1.6 = 851.6
        assertThat(dto.getTotalCost()).isEqualByComparingTo("851.6000");
        assertThat(dto.getMissingCostItems())
                .noneMatch(item -> item.startsWith("ESTIMATED_AUX_"));
    }

    @Test
    @DisplayName("🔴 逐道录入: 调料实耗在卤制批, 前处理批有主料无调料 ⇒ 前处理批【不】再估整张 BOM "
            + "(F006【演示L3】猪舌/猪大肠批把 10 味调料各估一遍, 无价的阻断成本)")
    void auxConsumedOnAnotherBatchOfTheSamePlanIsNotReEstimated() {
        stubBatch(List.of());
        ProductionBatch braise = new ProductionBatch();
        braise.setId(9202L);
        braise.setFactoryId(F);
        braise.setBatchNumber("CLK-AUX-BRAISE");
        braise.setProductionPlanId(PLAN);
        braise.setEquipmentCost(BigDecimal.ZERO);
        braise.setOtherCost(BigDecimal.ZERO);
        ProductionBatch pre = batchRepository.findByFactoryIdAndProductionPlanIdIn(F, List.of(PLAN)).get(0);
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any())).thenReturn(List.of(pre, braise));
        when(yieldReportService.getYield(F, 9202L)).thenReturn(emptyYield());
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(9202L, F))
                .thenReturn(List.of(consumption("MB-SALT", "MT-SALT", "SEASONING", "0.5", "3.00"),
                        consumption("MB-BOX", "MT-BOX", "PACKAGING", "10", "0.50")));
        stubMaterialBatch("MB-SALT", "食用盐-0001", "kg", "3.00", InventoryOwnership.COMPANY_OWNED);
        // BOM 价没填 —— 若前处理批去估它, 会落 ESTIMATED_AUX_PRICE_MISSING 并阻断
        stubBom(seasoning("MT-SALT", "食用盐", "10", null, null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("ESTIMATED_AUX_"));
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("1.50");
        assertThat(dto.isCostComplete()).as("missing=%s", dto.getMissingCostItems()).isTrue();
    }

    @Test
    @DisplayName("priceSource1 缺失时退 priceSource2 (与 BomRecipeServiceImpl#seasoningCost 同一条规则)")
    void fallsBackToPriceSource2() {
        stubBatch(List.of());
        stubBom(seasoning("MT-GINGER", "味好美生姜粉", "25", null, "122.53"));

        OrderCostBreakdownDTO dto = compute();

        OrderCostBreakdownDTO.EstimatedAuxItem line = dto.getEstimatedAuxItems().get(0);
        assertThat(line.getPriceSource()).isEqualTo("BOM_SEASONING_PRICE_SOURCE2");
        // 25 g/kg × 80kg = 2kg × 122.53 = 245.06
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
        assertThat(line.getAmount()).isEqualByComparingTo("245.0600");
    }

    // ──────────────────────── 阴性对照 (不该估的) ────────────────────────

    @Test
    @DisplayName("🔴 阴性对照①: 该辅料【有】实际耗用记录 ⇒ 不估, 用实际值 —— 估了就是双计")
    void doesNotEstimateWhenActualConsumptionExists() {
        stubBatch(List.of(consumption("MB-SALT", "MT-SALT", "SEASONING", "0.5", "3.00")));
        stubMaterialBatch("MB-SALT", "食用盐-0001", "kg", "3.00", InventoryOwnership.COMPANY_OWNED);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getEstimatedAuxItemCount()).isZero();
        // 只有实际耗用的 0.5×3.00, ⛔ 不是 1.50+1.60
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("1.50");
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("ESTIMATED_AUX_"));
        // BOM 里就这一条辅料, 而它有记录 ⇒ 没有缺口 ⇒ ⛔ 不该有「部分记录」marker
        assertThat(dto.getPartiallyRecordedAuxItems()).isEmpty();
    }

    @Test
    @DisplayName("🔴 部分记录留痕: BOM 两种调料只记了一种 ⇒ 成本仍按记录算(不补差), "
            + "但未记录的那种要写 AUX_PARTIALLY_RECORDED marker —— 否则成本看起来完整、实际短了它")
    void partiallyRecordedAuxIsMarkedInsteadOfSilentlyDropped() {
        stubBatch(List.of(consumption("MB-SALT", "MT-SALT", "SEASONING", "0.5", "3.00")));
        stubMaterialBatch("MB-SALT", "食用盐-0001", "kg", "3.00", InventoryOwnership.COMPANY_OWNED);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null),
                seasoning("MT-PEPPER", "黑胡椒粉", "5", "40.00", null));

        OrderCostBreakdownDTO dto = compute();

        // 策略没变: 不补差 —— 成本仍然只含已记录的那 0.5×3.00
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("1.50");
        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        // ...但缺口要看得见, 且【不阻断】
        assertThat(dto.getPartiallyRecordedAuxItems())
                .containsExactly("AUX_PARTIALLY_RECORDED:黑胡椒粉");
        assertThat(dto.getPartiallyRecordedAuxItemCount()).isEqualTo(1);
        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getTotalCost()).isNotNull();
        // ⛔ marker 绝不能混进 missingCostItems —— 那会把它变成阻断项
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("AUX_PARTIALLY_RECORDED"));

        // 人话版要说出来, 并且说清它是「按已记录算的下限」而不是「算不出」
        String note = ProductionPlanServiceImpl.describeEstimatedAuxNote(dto);
        assertThat(note).isNotNull();
        assertThat(note).contains("黑胡椒粉").contains("没有任何耗用记录").contains("下限");
        assertThat(note).doesNotContain("算不出");
    }

    @Test
    @DisplayName("阴性对照⑨: 调料成本整个来自报工侧一笔总额(没有任何逐物料记录) ⇒ ⛔ 不写 marker —— "
            + "那是「按批次粒度记录」, 对逐物料覆盖度无法断言, 逐条挂 marker 就是噪声")
    void reportedLumpSeasoningDoesNotProducePartialRecordMarkers() {
        BatchYieldDTO reported = BatchYieldDTO.builder()
                .totalLaborCost(BigDecimal.ZERO)
                .steps(List.of(
                        StepYieldDTO.builder().processOrder(1).costCategory("RAW_MATERIAL")
                                .materialCost(BigDecimal.ZERO).build(),
                        StepYieldDTO.builder().processOrder(2).costCategory("SEASONING")
                                .materialCost(new BigDecimal("42.00")).build()))
                .build();
        stubBatch(List.of(), reported);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null),
                seasoning("MT-PEPPER", "黑胡椒粉", "5", "40.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("42.00");
        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getPartiallyRecordedAuxItems()).isEmpty();
        assertThat(ProductionPlanServiceImpl.describeEstimatedAuxNote(dto)).isNull();
    }

    @Test
    @DisplayName("🔴 阴性对照②: 耗用记录的 quantity=0 也算记录 ⇒ 不估。0 是操作员说的「这次没用」, "
            + "拿估算覆盖它等于替他改口供")
    void aZeroQuantityRecordStillCountsAsRecorded() {
        stubBatch(List.of(consumption("MB-SALT", "MT-SALT", "SEASONING", "0", "3.00")));
        stubMaterialBatch("MB-SALT", "食用盐-0001", "kg", "3.00", InventoryOwnership.COMPANY_OWNED);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("🔴 阴性对照③: 报工侧已归集调料成本 ⇒ 整批不估 (报工不按物料拆分, 只能批级判)")
    void doesNotEstimateWhenReportedSeasoningCostExists() {
        BatchYieldDTO reported = BatchYieldDTO.builder()
                .totalLaborCost(BigDecimal.ZERO)
                .steps(List.of(
                        StepYieldDTO.builder().processOrder(1).costCategory("RAW_MATERIAL")
                                .materialCost(BigDecimal.ZERO).build(),
                        StepYieldDTO.builder().processOrder(2).costCategory("SEASONING")
                                .materialCost(new BigDecimal("42.00")).build()))
                .build();
        stubBatch(List.of(), reported);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("42.00");
    }

    @Test
    @DisplayName("阴性对照④: COOKING 段且 count_in_seasoning=false (老汤类) 不计入调料 ⇒ 既不估也不报缺")
    void skipsRowsThatDoNotCountTowardSeasoning() {
        stubBatch(List.of());
        BomSeasoningItem broth = seasoning("MT-BROTH", "老汤", "500", "5.00", null);
        broth.setCountInSeasoning(false);
        stubBom(broth);

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("ESTIMATED_AUX_"));
        assertThat(dto.isCostComplete()).isTrue();
    }

    @Test
    @DisplayName("阴性对照⑤: 别的工厂的 BOM 调料行不参与估算 (findByRecipeIdOrderBySeqAsc 不带 factoryId)")
    void ignoresRowsFromAnotherTenant() {
        stubBatch(List.of());
        BomSeasoningItem foreign = seasoning("MT-SALT", "食用盐", "10", "2.00", null);
        foreign.setFactoryId("LIUSHANMEN");
        stubBom(foreign);

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("0");
    }

    // ──────────────────── 缺配比/缺单价: 报缺, ⛔ 不按 0 计 ────────────────────

    @Test
    @DisplayName("🔴 阴性对照⑥: 缺配比 ⇒ ESTIMATED_AUX_RATIO_MISSING 并阻断, ⛔ 不按 ¥0 计")
    void missingRatioBlocksInsteadOfCostingZero() {
        stubBatch(List.of());
        stubBom(seasoning("MT-SALT", "食用盐", null, "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getMissingCostItems()).contains("ESTIMATED_AUX_RATIO_MISSING:食用盐");
        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getTotalCost()).isNull();   // ⛔ 不是 850.00 —— 那是把辅料白送掉
        assertThat(ProductionPlanServiceImpl.missingCostItemLabel("ESTIMATED_AUX_RATIO_MISSING:食用盐"))
                .contains("食用盐").contains("配比");
    }

    @Test
    @DisplayName("🔴 阴性对照⑦: 缺单价 ⇒ ESTIMATED_AUX_PRICE_MISSING 并阻断, ⛔ 不按 ¥0 计")
    void missingPriceBlocksInsteadOfCostingZero() {
        stubBatch(List.of());
        stubBom(seasoning("MT-SALT", "食用盐", "10", null, null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getMissingCostItems()).contains("ESTIMATED_AUX_PRICE_MISSING:食用盐");
        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("0");
        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getTotalCost()).isNull();
        assertThat(ProductionPlanServiceImpl.missingCostItemLabel("ESTIMATED_AUX_PRICE_MISSING:食用盐"))
                .contains("食用盐").contains("单价");
    }

    @Test
    @DisplayName("🔴 阴性对照⑧: 主料是计数单位折不成 kg ⇒ 整批不估并报缺, "
            + "⛔ 不拿「能折的那部分」当基数(那会给出偏低却看起来精确的估值)")
    void refusesToEstimateWhenRawBasisCannotBeConvertedToKilograms() {
        stubBatch(List.of(consumption("MB-CHICKEN", "MT-CHICKEN", "RAW_MATERIAL", "501", "20.00")));
        stubMaterialBatch("MB-CHICKEN", "白条鸡-0001", "只", "20.00", InventoryOwnership.COMPANY_OWNED);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getMissingCostItems())
                .contains("ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE:" + BATCH_NO);
        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.isCostComplete()).isFalse();
        assertThat(ProductionPlanServiceImpl.missingCostItemLabel(
                "ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE:" + BATCH_NO)).contains("kg");
    }

    // ──────────────────── #580 客供主料: 零回归 ────────────────────

    @Test
    @DisplayName("🔴 #580 回归: 客供主料仍然计 ¥0 (不进 missingCostItems), 但它的【物理投入量】"
            + "照样进估算基数 —— 配比说的是投了多少料, 与这料是谁的钱无关")
    void customerSuppliedRawStaysZeroCostButStillFeedsTheEstimationBasis() {
        stubBatch(List.of(consumption("MB-CS", "MT-CS", "RAW_MATERIAL", "20", null)));
        stubMaterialBatch("MB-CS", "客供1399上脑-0001", "kg", null, InventoryOwnership.CUSTOMER_OWNED);
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();

        // #580 那半: 客供批显式记 0, 不报缺价, 不阻断
        assertThat(dto.getCustomerSuppliedItems()).containsExactly("CUSTOMER_SUPPLIED:客供1399上脑-0001");
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("RAW_MATERIAL_PRICE:"));
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("800.00");   // 只有自有料 80×10

        // 本次那半: 基数是 80 + 20 = 100kg ⇒ 10g/kg × 100kg = 1kg × ¥2 = ¥2.0000
        OrderCostBreakdownDTO.EstimatedAuxItem line = dto.getEstimatedAuxItems().get(0);
        assertThat(line.getRawInputKg()).isEqualByComparingTo("100");
        assertThat(line.getAmount()).isEqualByComparingTo("2.0000");
        assertThat(dto.isCostComplete()).isTrue();
    }

    // ──────────────────── 脱敏 / 人话版 ────────────────────

    @Test
    @DisplayName("脱敏: 估算行金额/单价置 null, 保留配比/基数/量/「按配比估算」标签 (物理量与来源标记)")
    void maskedEstimatesKeepPhysicalQuantitiesButDropMoney() {
        stubBatch(List.of());
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, true);

        OrderCostBreakdownDTO.EstimatedAuxItem line = dto.getEstimatedAuxItems().get(0);
        assertThat(line.getAmount()).isNull();
        assertThat(line.getUnitPrice()).isNull();
        assertThat(line.getQuantity()).isEqualByComparingTo("0.8");
        assertThat(line.getDosagePerKgG()).isEqualByComparingTo("10");
        assertThat(line.getBasis()).isEqualTo(OrderCostBreakdownService.ESTIMATED_AUX_LABEL);
    }

    @Test
    @DisplayName("人话版: 有估算行时说清「按配比估算」+ 量 + 金额; 没有时返回 null (不多嘴)")
    void humanReadableNoteNamesTheEstimate() {
        stubBatch(List.of());
        stubBom(seasoning("MT-SALT", "食用盐", "10", "2.00", null));

        OrderCostBreakdownDTO dto = compute();
        String note = ProductionPlanServiceImpl.describeEstimatedAuxNote(dto);

        assertThat(note).isNotNull();
        assertThat(note).contains(OrderCostBreakdownService.ESTIMATED_AUX_LABEL);
        assertThat(note).contains("食用盐");
        assertThat(note).contains("0.8kg");
        assertThat(note).contains("¥1.6");
        assertThat(note).contains("不是实际领用");

        // 阴性对照: 没有估算行就不该多这一句 (否则每次重算都挂一句无意义的话)
        OrderCostBreakdownDTO none = OrderCostBreakdownDTO.builder()
                .estimatedAuxItems(List.of()).partiallyRecordedAuxItems(List.of()).build();
        assertThat(ProductionPlanServiceImpl.describeEstimatedAuxNote(none)).isNull();
        assertThat(ProductionPlanServiceImpl.describeEstimatedAuxNote(null)).isNull();
        // 两份清单都空才闭嘴; 只有 marker 也要说话 (⛔ 否则静默少算又回来了)
        OrderCostBreakdownDTO onlyMarker = OrderCostBreakdownDTO.builder()
                .estimatedAuxItems(List.of())
                .partiallyRecordedAuxItems(List.of("AUX_PARTIALLY_RECORDED:黑胡椒粉")).build();
        assertThat(ProductionPlanServiceImpl.describeEstimatedAuxNote(onlyMarker))
                .isNotNull()
                .contains("黑胡椒粉");
    }

    // ──────────────────── 共享口径 (形态 D) ────────────────────

    @Test
    @DisplayName("形态 D: 配比/计价/质量折算三条口径只有一份 —— SeasoningRatioContract 就是那一份")
    void sharedContractIsTheSingleDefinition() {
        BomSeasoningItem s = seasoning("MT-SALT", "食用盐", "10", "2.00", "9.99");

        // 配比: g/kg × kg = g (⛔ 不含 subsequentPotRatio 那层放大, 单锅基准)
        assertThat(SeasoningRatioContract.requiredGrams(s, new BigDecimal("80")))
                .isEqualByComparingTo("800");
        // 计价: source1 优先
        assertThat(SeasoningRatioContract.unitPricePerKg(s)).isEqualByComparingTo("2.00");
        assertThat(SeasoningRatioContract.unitPriceSource(s)).isEqualTo("BOM_SEASONING_PRICE_SOURCE1");

        // 质量折算: 逐字保留领料单原有的字面量集合
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "kg")).isEqualByComparingTo("2");
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "公斤")).isEqualByComparingTo("2");
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2000"), "g")).isEqualByComparingTo("2");
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "吨")).isEqualByComparingTo("2000");
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("4"), "斤")).isEqualByComparingTo("2");
        // ⛔ 计数单位不硬折 —— 返回 null 而不是 0 (0 会被读成「一粒都没有」)
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "只")).isNull();
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "个")).isNull();
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "件")).isNull();
        assertThat(SeasoningRatioContract.massToKg(new BigDecimal("2"), "pcs")).isNull();

        // 没有价就是没有价, ⛔ 不兜底成 0
        assertThat(SeasoningRatioContract.unitPricePerKg(
                seasoning("MT-X", "X", "10", null, null))).isNull();
        assertThat(SeasoningRatioContract.unitPricePerKg(
                seasoning("MT-X", "X", "10", "0", "0"))).isNull();
    }

    @Test
    @DisplayName("依赖缺失(bom_seasoning_items 仓储不可用) ⇒ 整块跳过, 零回归且不制造新噪声")
    void skipsEntirelyWhenRepositoryIsUnavailable() {
        stubBatch(List.of());
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", null);

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.getMissingCostItems()).noneMatch(i -> i.startsWith("ESTIMATED_AUX_"));
        assertThat(dto.isCostComplete()).isTrue();
    }

    @Test
    @DisplayName("BOM 调料明细读挂了 ⇒ 不让整个成本分解崩, 整块跳过")
    void survivesRepositoryFailure() {
        stubBatch(List.of());
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", bomSeasoningItemRepository);
        when(bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(anyString()))
                .thenThrow(new IllegalStateException("db down"));

        OrderCostBreakdownDTO dto = compute();

        assertThat(dto.getEstimatedAuxItems()).isEmpty();
        assertThat(dto.isCostComplete()).isTrue();
    }
}

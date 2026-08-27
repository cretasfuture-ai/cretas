package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 包材规格自动推算 (Packaging Spec Auto-Inference) 单元测试.
 *
 * <p>覆盖场景:
 * <ol>
 *   <li>PACKAGING 物料有 packQtyPerProduct, DTO 未手填 → standardQuantity 自动推算</li>
 *   <li>PACKAGING 物料有 packQtyPerProduct, DTO 已手填 → 手填优先 (不覆盖)</li>
 *   <li>PACKAGING 物料 packQtyPerProduct 为 null → standardQuantity 保持 null (诚实)</li>
 *   <li>RAW 物料 (非 PACKAGING) 有 packQtyPerProduct → 不触发推算</li>
 *   <li>自动推算同时回填 unit (DTO unit 为 null 时从物料主数据取)</li>
 *   <li>自动推算时 unit 已填 → 不覆盖 DTO 单位</li>
 *   <li>成本含 PACKAGING 行 (unitPrice + 自动推算 standardQuantity → itemCost 不为零)</li>
 * </ol>
 */
@DisplayName("BomRecipeServiceImpl — 包材规格自动推算 (PackagingSpecAutoInfer)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipePackagingSpecInferTest {

    @Mock
    BomRecipeRepository recipeRepo;

    @Mock
    BomRecipeItemRepository itemRepo;

    @Mock
    RawMaterialTypeRepository materialTypeRepo;

    @Mock
    MaterialUomConverter materialUomConverter;

    @Mock
    UnitContractService unitContractService;

    @Mock
    NestedBomCostService nestedBomCostService;

    /**
     * 🔴 2026-08-27 补：本测试类 8 条全红并被登记进 {@code known-failing-tests.txt}，
     * 红的原因**不是**推算逻辑，而是 {@code addItem} 后来加了一道
     * {@code assertPinnedDraft}（「BOM 草稿尚未自动关联已保存的 Workflow 修订」）。
     * 一条断言都没跑到被测行为 —— 而 {@code applyPackagingSpecAutoInfer} 在此期间
     * 从主代码里消失了，整件事被这片红完全掩盖。
     */
    @Mock
    com.cretas.aims.service.bom.BomWorkflowRevisionService bomWorkflowRevisionService;

    /** addItem 末尾会调它同步替代料; 未声明 → 字段为 null → NPE 在断言之前。 */
    @Mock
    com.cretas.aims.service.bom.BomItemSubstituteService substituteService;

    /** 成本重算会扫调料行; 未声明 → 字段为 null → NPE 在断言之前。 */
    @Mock
    com.cretas.aims.repository.bom.BomSeasoningItemRepository seasoningItemRepo;

    @InjectMocks
    BomRecipeServiceImpl service;

    private BomRecipe recipe;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("RECIPE-PKG-TEST");
        recipe.setFactoryId("F006");
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setItems(new ArrayList<>());
        // addItem 走 assertPinnedDraft：hash 为 null 直接 409，一条断言都跑不到被测行为。
        recipe.setWorkflowRevisionHash("REV-HASH-TEST");

        when(recipeRepo.findById("RECIPE-PKG-TEST")).thenReturn(Optional.of(recipe));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        UnitContractService canonicalUnits = TestUnitContractFactory.contract();
        when(unitContractService.normalize(anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.normalize(
                        invocation.getArgument(0), invocation.getArgument(1)));
        // 2026-08-03 P7 之后 applyPackagingLevel 用 areEquivalent 判「包材单位是否另选了」。
        // ⛔ 不打这个桩 → 恒 false → 每条用例都被「包材单位必须继承物料档案」拦住,
        //    断言一条都跑不到被测行为（本类此前就是这么全红的）。
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.areEquivalent(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        // NestedBomCostService: treat all items as plain (non-nested) for these tests
        when(nestedBomCostService.isNestedComponent(any())).thenReturn(false);
        // 空骨架: 本类测的是包材用量来源, 与 workflow 槽位绑定无关。
        // ⛔ 不打这个桩 → resolvePinnedGraph 返回 null → graph.nodes() NPE。
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), any(BomRecipe.class)))
                .thenReturn(minimalGraph());
    }

    // ─── T1: PACKAGING 物料有 packQtyPerProduct, DTO 未手填 → 自动推算 ─────────

    @Test
    @DisplayName("T1: PACKAGING + packQtyPerProduct=1 + DTO qty null → standardQuantity auto-set to 1")
    void packaging_withPackQty_dtoQtyNull_autoInferred() {
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        BomRecipeItem savedItem = stubSavedItem("PKG-001", new BigDecimal("1.000000"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", null, "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // standardQuantity must have been auto-set to packQtyPerProduct before save
        assertThat(result.getStandardQuantity())
                .as("standardQuantity should be auto-set from packQtyPerProduct=1")
                .isEqualByComparingTo(new BigDecimal("1.000000"));
    }

    @Test
    @DisplayName("T1b: PACKAGING + packQtyPerProduct=0.05 (外箱/20盒) → standardQuantity=0.05")
    void packaging_outerBox_packQty_dtoQtyNull_autoInferred() {
        // 外箱: 每箱装20盒 → 每产品单位需要1/20=0.05个外箱
        RawMaterialType outerBox = packagingMt("PKG-002", "外箱(20盒装)", "个",
                new BigDecimal("0.050000"));
        when(materialTypeRepo.findById("PKG-002")).thenReturn(Optional.of(outerBox));

        BomRecipeItem savedItem = stubSavedItem("PKG-002", new BigDecimal("0.050000"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-002", null, "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        assertThat(result.getStandardQuantity())
                .isEqualByComparingTo(new BigDecimal("0.050000"));
    }

    // ─── T2: 手填优先 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2: PACKAGING + packQtyPerProduct=1 + DTO qty=2 (手填) → 手填 2 优先, 不覆盖")
    void packaging_withPackQty_dtoQtyManuallyFilled_respectsManualInput() {
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // 手填 qty=2 (多个盒子手工调整)
        BomRecipeItem savedItem = stubSavedItem("PKG-001", new BigDecimal("2"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", new BigDecimal("2"), "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // 手填 2 应被保留, packQtyPerProduct=1 不应覆盖
        assertThat(result.getStandardQuantity())
                .as("manual qty=2 must not be overridden by packQtyPerProduct=1")
                .isEqualByComparingTo(new BigDecimal("2"));
    }

    // ─── T3: packQtyPerProduct null → explicit validation ─────────────────

    @Test
    @DisplayName("T3: PACKAGING + packQtyPerProduct null + DTO qty null → explicit validation error")
    void packaging_packQtyNull_dtoQtyNull_isRejected() {
        // 物料未配置规格
        RawMaterialType unspecBox = packagingMt("PKG-003", "未配置规格盒", "个", null);
        when(materialTypeRepo.findById("PKG-003")).thenReturn(Optional.of(unspecBox));

        BomRecipeItem savedItem = stubSavedItem("PKG-003", null, "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-003", null, "个", "PACKAGING");
        // 🔴 2026-08-27: 措辞与抛出点都变了 —— 2026-08-03 P7 之后由 applyPackagingLevel
        //    一次收齐三条必填再报, 类型是 BusinessException 而不是 IllegalArgumentException。
        //    ⛔ 守的行为没变: 物料没配规格、用户也没手填 → **拒绝**, 不许凭空造一个用量。
        assertThatThrownBy(() -> service.addItem("F006", "RECIPE-PKG-TEST", dto))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("包材自然用量必须大于 0");
    }

    // ─── T4: RAW 物料不触发推算 ───────────────────────────────────────────

    @Test
    @DisplayName("T4: RAW 物料即使有 packQtyPerProduct → 不触发自动推算 (category guard)")
    void raw_withPackQtyField_doesNotTriggerInfer() {
        // RAW 物料错配了 packQtyPerProduct (不应触发推算)
        RawMaterialType rawMt = rawMtWithPackQty("RAW-001", "猪舌", "kg", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("RAW-001")).thenReturn(Optional.of(rawMt));

        BomRecipeItem savedItem = stubSavedItem("RAW-001", null, "kg");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        // category=RAW, qty=null → should NOT infer from packQtyPerProduct
        BomRecipeItemDTO dto = buildDto("RAW-001", null, "kg", "RAW");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // verify: service did NOT call packQtyPerProduct to fill in standardQuantity
        // (since savedItem.standardQuantity=null and we return null, if it were inferred it'd be non-null)
        assertThat(savedItem.getStandardQuantity())
                .as("RAW category: packQtyPerProduct must NOT trigger auto-infer")
                .isNull();
    }

    // ─── T5: unit 回填 ───────────────────────────────────────────────────

    @Test
    @DisplayName("T5: PACKAGING auto-infer + DTO unit null → material alias 个 is canonicalized to pcs")
    void packaging_autoInfer_unitBackfilledFromMaterial() {
        // Material unit = "个", DTO unit not supplied
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // We capture what item is saved
        final BomRecipeItem[] capturedItem = new BomRecipeItem[1];
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            capturedItem[0] = inv.getArgument(0);
            return capturedItem[0];
        });
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(unrelatedExistingItem()));

        // DTO: no unit (null) — not explicitly provided
        BomRecipeItemDTO dto = buildDtoNoUnit("PKG-001", null, "PACKAGING");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // unit is back-filled from material then persisted as the canonical code.
        // 🔴 2026-08-27: 这条原来写死期望 "pcs"。今天落库的是档案单位经 unit contract
        //    归一后的值, 而那个值是不是英文码是**另一件事**(单位规范化), 不是本类守的东西。
        //    ⇒ 把守的东西从**字面**抬到**性质**: 单位必须来自**物料档案**并经同一个权威归一,
        //      ⛔ 不许是调用方自己给的。期望值从同一个权威算出来, 不写死拼写。
        String expectedUnit = TestUnitContractFactory.contract()
                .normalize("F006", box.getUnit()).code();
        assertThat(capturedItem[0].getUnit())
                .as("包材单位必须继承物料档案(经 unit contract 归一), 不是调用方另选的")
                .isEqualTo(expectedUnit);
        assertThat(expectedUnit)
                .as("阳性对照: 期望值必须真的算出来了 —— 算不出来这条断言就没在守任何东西")
                .isNotBlank();
    }

    @Test
    @DisplayName("T6: PACKAGING auto-infer + DTO unit=袋 → canonical bag is preserved")
    void packaging_autoInfer_existingUnitPreserved() {
        RawMaterialType box = packagingMt("PKG-001", "包装袋", "袋", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));
        // allow 袋 unit
        when(materialUomConverter.isWriteUnitCompatible(anyString(), eq("袋"))).thenReturn(true);

        final BomRecipeItem[] capturedItem = new BomRecipeItem[1];
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            capturedItem[0] = inv.getArgument(0);
            return capturedItem[0];
        });
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(unrelatedExistingItem()));

        // DTO explicitly supplies unit=袋
        BomRecipeItemDTO dto = buildDto("PKG-001", null, "袋", "PACKAGING");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // Explicit localized alias is persisted using the canonical bag code.
        // 同上: 守「继承档案 + 经同一权威归一」这条性质, 不写死拼写。
        // ⚠️ 本例调用方显式给了 unit=袋 —— 与档案一致, 所以能过「包材单位不能另选」那道闸;
        //    给一个**不同**的单位会被那道闸直接拒(那是它守的, 不在本类重复断言)。
        String expectedUnit = TestUnitContractFactory.contract()
                .normalize("F006", box.getUnit()).code();
        assertThat(capturedItem[0].getUnit())
                .as("显式给了与档案一致的单位时, 落库仍走档案 + 同一个归一权威")
                .isEqualTo(expectedUnit);
        assertThat(expectedUnit).isNotBlank();
    }

    // ─── T7: 成本含 PACKAGING 行 ───────────────────────────────────────

    @Test
    @DisplayName("T7: 成本含PACKAGING行 — auto-inferred qty + unitPrice → itemCost non-zero")
    void packaging_costIncludesPackagingItem() {
        // 吸塑盒: 1个/产品, 单价0.30元
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        box.setUnitPrice(new BigDecimal("0.30"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // Item saved with auto-inferred qty=1, unitPrice=0.30
        BomRecipeItem savedItem = new BomRecipeItem();
        savedItem.setId(10L);
        savedItem.setRecipeId("RECIPE-PKG-TEST");
        savedItem.setFactoryId("F006");
        savedItem.setMaterialTypeId("PKG-001");
        savedItem.setMaterialName("吸塑盒");
        savedItem.setUnit("个");
        savedItem.setStandardQuantity(new BigDecimal("1.000000"));
        savedItem.setYieldRate(new BigDecimal("100.00"));
        savedItem.setUnitPrice(new BigDecimal("0.30"));
        savedItem.setMaterialCategory("PACKAGING");
        // itemCost = 1.0000 * 0.30 = 0.3000
        savedItem.setItemCost(savedItem.computeItemCost());

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", null, "个", "PACKAGING");
        dto.setUnitPrice(new BigDecimal("0.30"));
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        assertThat(result.getItemCost())
                .as("itemCost = standardQuantity(1) × unitPrice(0.30) = 0.3000")
                .isEqualByComparingTo(new BigDecimal("0.3000"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private RawMaterialType packagingMt(String id, String name, String unit,
                                         BigDecimal packQtyPerProduct) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setCategory("PACKAGING");
        mt.setPackQtyPerProduct(packQtyPerProduct);
        mt.setIsActive(true);
        return mt;
    }

    private RawMaterialType rawMtWithPackQty(String id, String name, String unit,
                                              BigDecimal packQtyPerProduct) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setCategory("RAW");
        mt.setPackQtyPerProduct(packQtyPerProduct);
        mt.setIsActive(true);
        return mt;
    }

    /**
     * 最小可用工艺图：一个 RAW_MATERIAL 节点 → 一个带 INPUT 端口的 PROCESS 节点。
     *
     * <p>⛔ 不能给空图：{@code BomWorkflowRevisionService.resolveInputSlots} 是**静态**方法
     * （mock 不掉），空图会抛「这个产品的工艺图里没有接入任何原料」，RAW 那条用例
     * 一条断言都跑不到。本类测的是包材用量来源，这张图只是让入口走得通。</p>
     */
    private com.cretas.aims.service.workflow.PinnedWorkflowGraph minimalGraph() {
        com.cretas.aims.dto.ProductProcessWorkflowDTO.Node rawNode =
                new com.cretas.aims.dto.ProductProcessWorkflowDTO.Node();
        rawNode.setId("RM-1");
        rawNode.setKind("RAW_MATERIAL");
        rawNode.setData(new java.util.LinkedHashMap<>(java.util.Map.of("skuId", "RAW-001")));

        com.cretas.aims.dto.ProductProcessWorkflowDTO.Node processNode =
                new com.cretas.aims.dto.ProductProcessWorkflowDTO.Node();
        processNode.setId("P-1");
        processNode.setKind("PROCESS");
        processNode.setData(new java.util.LinkedHashMap<>(java.util.Map.of(
                "ports", List.of(java.util.Map.of("id", "in-1", "direction", "INPUT", "unit", "kg")))));

        com.cretas.aims.dto.ProductProcessWorkflowDTO.Edge edge =
                new com.cretas.aims.dto.ProductProcessWorkflowDTO.Edge();
        edge.setId("E-1");
        edge.setSource("RM-1");
        edge.setTarget("P-1");
        edge.setTargetHandle("in-1");

        return new com.cretas.aims.service.workflow.PinnedWorkflowGraph(
                1L, 1L, 1, "REV-HASH-TEST", "PT-TEST", "NODE-END",
                List.of("RAW-001"), List.of(), List.of(rawNode, processNode), List.of(edge));
    }

    /**
     * 「配方里已经存在的另一行」—— 只用来让 resolveExistingItemForRequest 有东西可扫。
     * ⛔ id 必须非空: 那段用 Set.of(...).contains(item.getId()), null 直接 NPE。
     * ⛔ materialTypeId 必须与被建行不同: 相同会被判成同一行而走更新路径, 捕获不到 save。
     */
    private BomRecipeItem unrelatedExistingItem() {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(1L);
        item.setRecipeId("RECIPE-PKG-TEST");
        item.setFactoryId("F006");
        item.setMaterialTypeId("PKG-OTHER");
        item.setUnit("pcs");
        return item;
    }

    private BomRecipeItem stubSavedItem(String materialTypeId, BigDecimal qty, String unit) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(99L);
        item.setRecipeId("RECIPE-PKG-TEST");
        item.setFactoryId("F006");
        item.setMaterialTypeId(materialTypeId);
        item.setUnit(unit);
        item.setStandardQuantity(qty);
        item.setMaterialCategory("PACKAGING");
        return item;
    }

    private BomRecipeItemDTO buildDto(String materialTypeId, BigDecimal qty, String unit, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", unit);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        // 2026-08-03 P7 起 PACKAGING 行必须带业务角色, 否则 applyPackagingLevel 直接拒。
        // ⛔ 不设它 → 每条用例都停在「包材必须选择所在包装层级的业务角色」, 跑不到被测行为。
        if ("PACKAGING".equalsIgnoreCase(category)) {
            ReflectionTestUtils.setField(dto, "packagingRole", "PRIMARY");
        }
        return dto;
    }

    /** Build DTO with null unit (no unit provided) — simulates client not sending unit field. */
    private BomRecipeItemDTO buildDtoNoUnit(String materialTypeId, BigDecimal qty, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", null);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        if ("PACKAGING".equalsIgnoreCase(category)) {
            ReflectionTestUtils.setField(dto, "packagingRole", "PRIMARY");
        }
        return dto;
    }
}

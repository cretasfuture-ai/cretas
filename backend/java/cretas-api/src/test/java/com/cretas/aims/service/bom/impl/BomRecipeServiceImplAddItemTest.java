package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
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
 * Regression test: BomRecipeServiceImpl.addItem / updateItem / deleteItem must NOT replace
 * the Hibernate-managed BomRecipe.items collection reference.
 *
 * <p>Root cause: {@code BomRecipe.items} is annotated with
 * {@code cascade = CascadeType.ALL, orphanRemoval = true}. Calling
 * {@code recipe.setItems(newList)} after a Hibernate session load replaces the
 * PersistentBag with a plain ArrayList, causing:
 * {@code HibernateException: A collection with cascade="all-delete-orphan" was no longer
 * referenced by the owning entity instance}.
 *
 * <p>Fix: {@link BomRecipeServiceImpl#refreshItemsInPlace(BomRecipe)} uses
 * {@code recipe.getItems().clear(); recipe.getItems().addAll(fresh)} to keep the same
 * collection instance. This test asserts that the original list instance is preserved
 * (i.e., {@code setItems} is never called with a different object reference after load).
 */
@DisplayName("BomRecipeServiceImpl.addItem — orphanRemoval collection identity preserved")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeServiceImplAddItemTest {

    @Mock
    BomRecipeRepository recipeRepo;

    @Mock
    BomRecipeItemRepository itemRepo;

    @Mock
    ProductTypeRepository productTypeRepo;

    @Mock
    BomSeasoningItemRepository seasoningItemRepo;

    @Mock
    BomProcessInjectionConfigRepository processInjectionConfigRepo;

    @Mock
    RawMaterialTypeRepository materialTypeRepo;

    @Mock
    MaterialUomConverter materialUomConverter;

    @Mock
    UnitContractService unitContractService;

    /** SP1: NestedBomCostService must be mocked so @InjectMocks can inject it. */
    @Mock
    NestedBomCostService nestedBomCostService;

    /** Every DRAFT write path runs assertPinnedDraft, which resolves the pinned graph. */
    @Mock
    BomWorkflowRevisionService bomWorkflowRevisionService;

    /** addItem/updateItem replace substitute relations; cloneRecipe copies them. */
    @Mock
    BomItemSubstituteService substituteService;

    @InjectMocks
    BomRecipeServiceImpl service;

    private BomRecipe recipe;
    private BomRecipeItem existingItem;
    /** The Hibernate-managed list instance that must NOT be replaced. */
    private List<BomRecipeItem> managedList;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("RECIPE-ORPHAN-TEST");
        recipe.setFactoryId("F006");
        recipe.setStatus(BomRecipe.Status.DRAFT);
        // A BOM is writable / activatable / clonable only once it is pinned to a saved
        // Workflow revision (BomRecipeServiceImpl#assertPinnedDraft).
        recipe.setWorkflowRevisionId(9301L);
        recipe.setWorkflowRevisionHash("revision-additem-hash");
        // A pinned BOM always carries its role in the family; activation reconciles the
        // family and requires a MAIN output.
        recipe.setOutputRole(BomRecipe.OutputRole.MAIN);
        recipe.setCostAllocationRatio(new BigDecimal("100"));
        recipe.setTargetTerminalNodeId("terminal");
        // A single-output BOM is its own family anchor: the shared recipe is itself.
        recipe.setSharedRecipeId(recipe.getId());

        existingItem = new BomRecipeItem();
        existingItem.setId(1L);
        existingItem.setRecipeId("RECIPE-ORPHAN-TEST");
        existingItem.setFactoryId("F006");
        existingItem.setMaterialTypeId("MT-001");
        existingItem.setMaterialName("猪舌原料");
        existingItem.setUnit("kg");
        existingItem.setStandardQuantity(BigDecimal.valueOf(800));

        // Simulate Hibernate-managed PersistentBag by using a specific ArrayList instance
        managedList = new ArrayList<>();
        managedList.add(existingItem);
        recipe.setItems(managedList);

        // Mock repo lookups
        when(recipeRepo.findById("RECIPE-ORPHAN-TEST")).thenReturn(Optional.of(recipe));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(managedList);

        ProductType product = new ProductType();
        product.setId("SKU-001");
        product.setFactoryId("F006");
        product.setName("测试产品");
        product.setUnit("袋");
        product.setGramsPerUnit(new BigDecimal("500"));
        when(productTypeRepo.findByIdAndFactoryIdForUpdate("SKU-001", "F006"))
                .thenReturn(Optional.of(product));

        RawMaterialType mt = new RawMaterialType();
        mt.setId("MT-001");
        mt.setName("猪舌原料");
        mt.setUnit("kg");
        mt.setFactoryId("F006");
        mt.setPrimaryCode("001");
        mt.setTaxRate(TaxRate.TAX_9);
        mt.setUnitPrice(new BigDecimal("10.0000"));
        mt.setMovingAvgPrice(new BigDecimal("12.0000"));
        when(materialTypeRepo.findById("MT-001")).thenReturn(Optional.of(mt));
        when(materialTypeRepo.findById("MT-002")).thenReturn(Optional.of(newMt("MT-002", "包材A", "g", "002")));

        // uom converter: allow write unit (service calls isWriteUnitCompatible)
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        when(materialUomConverter.toComparableQuantity(
                anyString(), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(invocation -> MaterialUomConverter.ConversionResult.converted(
                        invocation.getArgument(1), invocation.getArgument(3)));
        UnitContractService canonicalUnits = TestUnitContractFactory.contract();
        when(unitContractService.normalize(anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.normalize(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.areEquivalent(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        // resolvePinnedGraph throws or returns a graph — it never returns null.
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), any(BomRecipe.class)))
                .thenReturn(pinnedGraph());
        // Likewise the terminal outputs: a pinned revision always has at least the MAIN one,
        // and activation reconciles the family against them.
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(
                        anyString(), any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe member = invocation.getArgument(1);
                    return List.of(new BomWorkflowRevisionService.TerminalOutput(
                            "terminal", member.getProductTypeId(), "process", "output",
                            BomRecipe.OutputRole.MAIN, new BigDecimal("100"),
                            member.getOutputUnit(),
                            BomWorkflowRevisionService.CostAllocationBasis.DECLARED));
                });
    }

    /** Minimal pinned Workflow graph owning one RAW input slot per fixture material. */
    private PinnedWorkflowGraph pinnedGraph() {
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        List<java.util.Map<String, Object>> ports = new ArrayList<>();
        // Only RAW materials occupy Workflow input slots, and every slot material must
        // resolve against materialTypeRepo when the skeleton is rebuilt on activation.
        // MT-002/MT-BOX/MT-CASE are packaging and MT-MASS is auxiliary — none of them
        // are workflow inputs.
        java.util.Map<String, String> slotUnits = new java.util.LinkedHashMap<>();
        slotUnits.put("MT-001", "kg");
        List<String> materials = List.copyOf(slotUnits.keySet());
        double x = 0D;
        for (String materialTypeId : materials) {
            java.util.Map<String, Object> rawData = new java.util.LinkedHashMap<>();
            rawData.put("skuId", materialTypeId);
            nodes.add(new ProductProcessWorkflowDTO.Node(
                    "raw-" + materialTypeId, "RAW_MATERIAL",
                    new ProductProcessWorkflowDTO.Position(x, 0D), rawData));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge-" + materialTypeId, "raw-" + materialTypeId, "output",
                    "process", "in-" + materialTypeId));
            ports.add(java.util.Map.of("id", "in-" + materialTypeId, "direction", "INPUT",
                    "materialNodeId", "raw-" + materialTypeId,
                    "materialKind", "RAW_MATERIAL", "ordinal", ports.size(),
                    "unit", slotUnits.get(materialTypeId)));
            x += 100D;
        }
        List<java.util.Map<String, Object>> allPorts = new ArrayList<>(ports);
        allPorts.add(java.util.Map.of("id", "output", "direction", "OUTPUT",
                "materialNodeId", "terminal", "materialKind", "FINISHED_GOOD",
                "ordinal", 0, "outputRole", "MAIN",
                "costAllocationRatio", new BigDecimal("100")));
        java.util.Map<String, Object> processData = new java.util.LinkedHashMap<>();
        processData.put("workProcessId", "WP-ADDITEM");
        processData.put("ports", allPorts);
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "process", "PROCESS", new ProductProcessWorkflowDTO.Position(600D, 0D), processData));
        java.util.Map<String, Object> terminalData = new java.util.LinkedHashMap<>();
        terminalData.put("skuId", "SKU-001");
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "terminal", "FINISHED_GOOD",
                new ProductProcessWorkflowDTO.Position(700D, 0D), terminalData));
        edges.add(new ProductProcessWorkflowDTO.Edge(
                "edge-output", "process", "output", "terminal", "input"));
        return new PinnedWorkflowGraph(
                9301L, 10L, 1, "revision-additem-hash", "SKU-001", "terminal",
                materials,
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-ADDITEM", 1)),
                List.copyOf(nodes), List.copyOf(edges));
    }

    @Test
    @DisplayName("历史 BOM 可删除，当前生效 BOM 不可删除")
    void deleteAllowsHistoryButRejectsActive() {
        recipe.setStatus(BomRecipe.Status.ARCHIVED);
        service.deleteRecipe("F006", recipe.getId());
        assertThat(recipe.getDeletedAt()).isNotNull();

        BomRecipe active = new BomRecipe();
        active.setId("ACTIVE-1");
        active.setFactoryId("F006");
        active.setStatus(BomRecipe.Status.ACTIVE);
        when(recipeRepo.findById("ACTIVE-1")).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.deleteRecipe("F006", "ACTIVE-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能删除");
    }

    @Test
    @DisplayName("每个 SKU 最多保留十个未删除 BOM 版本")
    void cloneRejectsEleventhVersion() {
        recipe.setProductTypeId("SKU-001");
        when(recipeRepo.countByFactoryIdAndProductTypeId("F006", "SKU-001")).thenReturn(10L);

        assertThatThrownBy(() -> service.cloneRecipe("F006", recipe.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getErrorCode()).isEqualTo("BOM_VERSION_LIMIT_REACHED");
                });
        verify(recipeRepo, never()).save(argThat(candidate -> candidate != recipe));
    }

    @Test
    @DisplayName("ensureDraft clones the one current ACTIVE version completely")
    void ensureDraftClonesCurrentActiveVersion() {
        recipe.setProductTypeId("SKU-001");
        recipe.setProductName("测试产品");
        recipe.setVersion(1);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOutputQuantityPerUnit(new BigDecimal("500"));
        recipe.setOutputUnit("袋");
        existingItem.setSubProductTypeId("SUB-SKU-1");
        existingItem.setActualQuantity(new BigDecimal("808"));
        existingItem.setItemCost(new BigDecimal("9.99"));

        BomSeasoningItem seasoning = new BomSeasoningItem();
        seasoning.setId(11L);
        seasoning.setRecipeId(recipe.getId());
        seasoning.setFactoryId("F006");
        seasoning.setName("盐");
        seasoning.setWorkProcessId("WP-1");

        BomProcessInjectionConfig config = new BomProcessInjectionConfig();
        config.setId(12L);
        config.setRecipeId(recipe.getId());
        config.setFactoryId("F006");
        config.setWorkProcessId("WP-1");
        config.setInjectionAmountKg(new BigDecimal("2.5"));

        when(recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc("F006", "SKU-001"))
                .thenReturn(List.of(recipe));
        when(recipeRepo.countByFactoryIdAndProductTypeId("F006", "SKU-001")).thenReturn(1L);
        when(recipeRepo.findMaxVersion("F006", "SKU-001")).thenReturn(1);
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(invocation -> {
            BomRecipe saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId("RECIPE-V2-DRAFT");
            return saved;
        });
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipe.getId())).thenReturn(List.of(seasoning));
        when(processInjectionConfigRepo.findByRecipeIdAndDeletedAtIsNull(recipe.getId()))
                .thenReturn(List.of(config));

        BomRecipe draft = service.ensureDraft("F006", "SKU-001");

        assertThat(draft.getVersion()).isEqualTo(2);
        assertThat(draft.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(recipe.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(recipe.getIsCurrent()).isTrue();
        assertThat(draft.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getSubProductTypeId()).isEqualTo("SUB-SKU-1");
            assertThat(item.getActualQuantity()).isEqualByComparingTo("808");
            assertThat(item.getItemCost()).isEqualByComparingTo("9.99");
        });
        verify(seasoningItemRepo).saveAll(argThat(rows -> rows.iterator().hasNext()));
        verify(processInjectionConfigRepo).saveAll(argThat(rows -> rows.iterator().hasNext()));
    }

    /*
     * 🔴 2026-08-12: 这条原来断言 `BOM_CURRENT_ACTIVE_REQUIRED`(有历史版本但 0 个 ACTIVE ⇒ 拒绝)。
     * 用例名写着「instead of guessing a source」—— 作者担心的是**从哪一版克隆**要靠猜, 那是对的;
     * 但结论下错了: 正确做法不是拒绝, 而是**根本不克隆** —— 照当前画布 revision 投一份新草稿
     * (画布是权威、BOM 是投影)。真机上那句「请先修复版本状态」指向一个**没有界面**的动作。
     *
     * 新语义连同「绝不从归档版克隆」的守卫, 移到了
     * {@link BomDraftWithoutActiveBaselineTest} —— 那里能把所需依赖 mock 干净;
     * 本类的夹具缺 bomWorkflowRevisionService / unitContractService, 走不到投影路径。
     */

    @Test
    @DisplayName("ensureDraft rejects ambiguous multiple drafts")
    void ensureDraftRejectsMultipleDrafts() {
        recipe.setProductTypeId("SKU-001");
        BomRecipe secondDraft = new BomRecipe();
        secondDraft.setId("RECIPE-DRAFT-2");
        secondDraft.setFactoryId("F006");
        secondDraft.setProductTypeId("SKU-001");
        secondDraft.setStatus(BomRecipe.Status.DRAFT);
        when(recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc("F006", "SKU-001"))
                .thenReturn(List.of(recipe, secondDraft));

        assertThatThrownBy(() -> service.ensureDraft("F006", "SKU-001"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOM_MULTIPLE_DRAFTS"));
    }

    @Test
    @DisplayName("ensureDraft rejects invalid SKU grams before creating a version")
    void ensureDraftRejectsInvalidSkuGrams() {
        ProductType invalid = new ProductType();
        invalid.setId("SKU-BAD");
        invalid.setFactoryId("F006");
        invalid.setName("错误 SKU");
        invalid.setUnit("袋");
        invalid.setGramsPerUnit(BigDecimal.ZERO);
        when(productTypeRepo.findByIdAndFactoryIdForUpdate("SKU-BAD", "F006"))
                .thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> service.ensureDraft("F006", "SKU-BAD"))
                .isInstanceOfSatisfying(BusinessException.class,
                        // gramsPerUnit generalised into netContentQuantity + netContentUnit;
                        // same guard, new code.
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOM_SKU_NET_CONTENT_REQUIRED"));
        verify(recipeRepo, never()).save(argThat(candidate -> "SKU-BAD".equals(candidate.getProductTypeId())));
    }

    // ⛔ 激活相关的四条断言不在本类 —— 它们在
    //    BomRecipeDraftLifecycleJpaTest (真 H2 + 真画布夹具):
    //      · activationArchivesEveryCompetingActiveVersion
    //      · archivedVersionIsReactivatedByCloningItIntoADraft  (原 archivedVersionCanBeReactivated)
    //      · emptyDraftIsRebuiltFromTheCanvasInsteadOfRefused   (原 activationRejectsEmptyDraft)
    //      · canvasWithoutAnyRawInputTellsTheUserWhatToDo
    //    以及原有的 activationRejectsPackagingWithoutFixedQuantity
    //      (原 activationRejectsPackagingWithoutQuantity, 那边一直是绿的)。
    //
    //    本类是**纯 Mockito**, 而 activateRecipe 要 bomWorkflowRevisionService /
    //    readinessService / workflowActivationRepo 三个协作者。在这里补 mock 等于把
    //    那份画布夹具再抄一份, 同一个东西两份、必漂 —— 所以搬走而不是就地修。

    @Test
    @DisplayName("addItem: collection reference stays the same instance after call (no setItems replacement)")
    void addItem_keepsOriginalCollectionInstance() {
        BomRecipeItem newItem = new BomRecipeItem();
        newItem.setId(2L);
        newItem.setRecipeId("RECIPE-ORPHAN-TEST");
        newItem.setFactoryId("F006");
        newItem.setMaterialTypeId("MT-002");
        newItem.setMaterialName("包材A");
        newItem.setUnit("g");
        newItem.setStandardQuantity(BigDecimal.valueOf(50));

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(newItem);
        // findByRecipeIdOrderBySortOrderAsc returns the updated list (including new item)
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem, newItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        // The collection instance on the entity must be the SAME object as managedList
        // (not a brand-new ArrayList). If setItems() was called with a new list, this fails.
        assertThat(recipe.getItems())
                .as("Hibernate-managed collection instance must be preserved after addItem")
                .isSameAs(managedList);

        // Content should be updated in-place
        assertThat(recipe.getItems()).hasSize(2);
        assertThat(recipe.getItems()).contains(existingItem, newItem);
    }

    // ── F6: taxRate 未传 → 保持 null (不默认 0%), 成本侧诚实标缺 ──────────────
    @Test
    @DisplayName("addItem 未传价格税率时从物料档案继承")
    void addItem_pricingNotProvided_inheritsMaterialMaster() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        // buildDto 不设 taxRate → dto.getTaxRate() == null
        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getTaxRate()).isEqualByComparingTo("9.00");
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo("12.0000");
    }

    @Test
    @DisplayName("addItem 忽略页面价格税率，以物料档案为真值")
    void addItem_payloadPricingIgnored_materialMasterWins() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        ReflectionTestUtils.setField(dto, "taxRate", new BigDecimal("13.00"));
        ReflectionTestUtils.setField(dto, "unitPrice", new BigDecimal("999.00"));
        ReflectionTestUtils.setField(dto, "yieldRate", new BigDecimal("55.00"));
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getTaxRate()).isEqualByComparingTo("9.00");
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo("12.0000");
        assertThat(captor.getValue().getYieldRate()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("RAW BOM 可只建立物料关联，不填每成品用量")
    void addRawItem_standardQuantityMayBeNull() {
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-001", null, "kg", "RAW");
        BomRecipeItem saved = service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(saved.getStandardQuantity()).isNull();
        assertThat(saved.getActualQuantity()).isNull();
        // 「没填用量」的成本是 unknown, ⛔ 不是 0 —— 报 0 会把「我不知道」当成一个真实读数
        // 往成本汇总里传。
        assertThat(saved.getItemCost()).isNull();
    }

    @Test
    @DisplayName("包材 BOM 仍必须填每成品用量")
    void addPackagingItem_standardQuantityRequired() {
        BomRecipeItemDTO dto = buildDto("MT-002", null, "g", "PACKAGING");

        // Still required — P7 moved the check into applyPackagingLevel, so it now surfaces
        // as a coded BusinessException instead of a bare IllegalArgumentException.
        assertThatThrownBy(() -> service.addItem("F006", "RECIPE-ORPHAN-TEST", dto))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("BOM_PACKAGING_QUANTITY_REQUIRED");
                    assertThat(ex.getMessage()).contains("用量");
                });
    }

    @Test
    @DisplayName("包材主数据 box 与中文显示值盒统一持久化为 canonical box")
    void addPackagingItem_boxAliasPersistsCanonicalCode() {
        RawMaterialType box = newMt("MT-BOX", "内盒", "box", "003");
        when(materialTypeRepo.findById("MT-BOX")).thenReturn(Optional.of(box));
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId())).thenReturn(List.of(existingItem));

        BomRecipeItem saved = service.addItem("F006", recipe.getId(),
                buildDto("MT-BOX", new BigDecimal("1"), "盒", "PACKAGING"));

        // V20261029_32__unit_codes_to_chinese.sql moved the canonical literal to Chinese.
        // The property is unchanged: master value "box" and UI label 「盒」 converge on one unit.
        assertThat(saved.getUnit()).isEqualTo("盒");
        assertThat(saved.getPriceUnit()).isEqualTo("盒");
        assertThat(saved.getStandardQuantity()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("包材主数据 case 与中文显示值箱统一持久化为 canonical case")
    void addPackagingItem_caseAliasPersistsCanonicalCode() {
        RawMaterialType outerCase = newMt("MT-CASE", "外箱", "case", "004");
        when(materialTypeRepo.findById("MT-CASE")).thenReturn(Optional.of(outerCase));
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId())).thenReturn(List.of(existingItem));

        BomRecipeItem saved = service.addItem("F006", recipe.getId(),
                buildDto("MT-CASE", new BigDecimal("0.125"), "箱", "PACKAGING"));

        assertThat(saved.getUnit()).isEqualTo("箱");
        assertThat(saved.getPriceUnit()).isEqualTo("箱");
        assertThat(saved.getStandardQuantity()).isEqualByComparingTo("0.125");
    }

    @Test
    @DisplayName("g/kg 继续通过统一单位契约并保持 canonical code")
    void addMassItems_keepCanonicalGramAndKilogramCodes() {
        RawMaterialType material = newMt("MT-MASS", "粉料", "kg", "005");
        when(materialTypeRepo.findById("MT-MASS")).thenReturn(Optional.of(material));
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId())).thenReturn(List.of(existingItem));

        BomRecipeItem grams = service.addItem("F006", recipe.getId(),
                buildDto("MT-MASS", new BigDecimal("125"), "g", "AUXILIARY"));
        BomRecipeItem kilograms = service.addItem("F006", recipe.getId(),
                buildDto("MT-MASS", new BigDecimal("1"), "kg", "AUXILIARY"));

        assertThat(grams.getUnit()).isEqualTo("g");
        assertThat(kilograms.getUnit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("未知单位返回明确 400 业务校验而不是进入数据库")
    void addItem_unknownUnitReturnsBusinessValidation() {
        // ⚠️ Not PACKAGING: since P7 a packaging line inherits its unit from the material
        // master, so an unrecognised unit is reported as BOM_PACKAGING_UNIT_MISMATCH and
        // never reaches the unit-catalogue guard. That guard lives on this path.
        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.ONE, "unknown-unit", "AUXILIARY");

        assertThatThrownBy(() -> service.addItem("F006", recipe.getId(), dto))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getErrorCode()).isEqualTo("BOM_ITEM_UNIT_UNKNOWN");
                    assertThat(ex.getActionHint()).contains("单位目录");
                });
        verify(itemRepo, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("SP8: addItem 未传 primaryCode 时从物料主数据回填")
    void addItem_primaryCodeBackfilledFromMaterial() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getPrimaryCode()).isEqualTo("002");
    }

    @Test
    @DisplayName("SP8: addItem 传入 primaryCode 与物料主编码不一致 -> 400")
    void addItem_primaryCodeMismatchRejected() {
        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        ReflectionTestUtils.setField(dto, "primaryCode", "001");

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.addItem("F006", "RECIPE-ORPHAN-TEST", dto));

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("MT-002").contains("001").contains("002");
        verify(itemRepo, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("addItem: AUXILIARY item accepted — B-41 AUXILIARY category supported")
    void addItem_auxiliaryCategory_accepted() {
        BomRecipeItem auxItem = new BomRecipeItem();
        auxItem.setId(3L);
        auxItem.setRecipeId("RECIPE-ORPHAN-TEST");
        auxItem.setFactoryId("F006");
        auxItem.setMaterialTypeId("MT-001");
        auxItem.setMaterialName("辅料A");
        auxItem.setUnit("g");

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(auxItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem, auxItem));

        BomRecipeItemDTO dto = buildDto("MT-001", BigDecimal.valueOf(10), "g", "AUXILIARY");
        BomRecipeItem result = service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(result).isEqualTo(auxItem);
    }

    @Test
    @DisplayName("deleteItem: collection reference stays the same instance after soft-delete")
    void deleteItem_keepsOriginalCollectionInstance() {
        // A line that is the sole occupant of a required Workflow input slot cannot be
        // deleted at all. This test is about collection identity, so it deletes a
        // PACKAGING line — assertNotRequiredWorkflowInputByMaterial exempts those.
        existingItem.setMaterialCategory("PACKAGING");
        when(itemRepo.findById(1L)).thenReturn(Optional.of(existingItem));
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(existingItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of());  // after soft-delete, returns empty

        service.deleteItem("F006", 1L);

        assertThat(recipe.getItems())
                .as("Hibernate-managed collection instance must be preserved after deleteItem")
                .isSameAs(managedList);
        assertThat(recipe.getItems()).isEmpty();
    }

    // ─── 回料(半成品)行: material_type_id 为空, 身份在 semi_finished_ref_code 上 ───

    /**
     * 新路径: 加一条回料行成功, 且落库的是「没有原料档案 / 有 WIP 引用 / 类别 SEMI_FINISHED」。
     *
     * <p>回料在半成品库, 不在原料仓 —— 所以这条路径<b>一次都不能去查 raw_material_types</b>,
     * 否则就等于回到了被否掉的那个方案 (给回料建一份假原料档案)。
     */
    @Test
    @DisplayName("addItem: 回料行 (semiFinishedRefCode) 落库, 且不碰原料档案")
    void addItem_semiFinishedRow_persistsWithoutMaterialMaster() {
        clearInvocations(materialTypeRepo);
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            BomRecipeItem saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "semiFinishedRefCode", "WIP-F006-REWORK-20260829");
        ReflectionTestUtils.setField(dto, "standardQuantity", new BigDecimal("0.1951"));
        ReflectionTestUtils.setField(dto, "unit", "kg");
        ReflectionTestUtils.setField(dto, "materialCategory", "SEMI_FINISHED");

        BomRecipeItem saved = service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(saved.getMaterialTypeId())
                .as("回料行不指向原料档案 —— 非空就说明它又被塞回原料仓了")
                .isNull();
        assertThat(saved.getSemiFinishedRefCode()).isEqualTo("WIP-F006-REWORK-20260829");
        assertThat(saved.getMaterialCategory())
                .isEqualTo(BomRecipeItem.CATEGORY_SEMI_FINISHED);
        assertThat(saved.getStandardQuantity()).isEqualByComparingTo("0.1951");
        assertThat(saved.getUnit()).isEqualTo("kg");
        verify(materialTypeRepo, never()).findById(anyString());
    }

    /**
     * 存量兼容的<b>阴性对照</b>: 同一个入口, 普通原料行仍然要查档案并写 materialTypeId。
     * 少了它, 上面那条「不碰原料档案」可能只是因为这条路整个坏掉了。
     */
    @Test
    @DisplayName("阴性对照: 普通原料行仍然查档案、仍然写 materialTypeId")
    void addItem_plainMaterialRow_stillResolvesMaterialMaster() {
        clearInvocations(materialTypeRepo);
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            BomRecipeItem saved = inv.getArgument(0);
            saved.setId(43L);
            return saved;
        });

        BomRecipeItem saved = service.addItem("F006", "RECIPE-ORPHAN-TEST",
                buildDto("MT-001", new BigDecimal("800"), "kg", "RAW"));

        assertThat(saved.getMaterialTypeId()).isEqualTo("MT-001");
        assertThat(saved.getSemiFinishedRefCode()).isNull();
        verify(materialTypeRepo, atLeastOnce()).findById("MT-001");
    }

    /**
     * 接线断言 (形态 B): 在<b>产品真实入口</b> {@code addItem} 上, 用
     * {@link NestedBomCostService#isNestedComponent} 的<b>真实实现</b>做路由判定,
     * 证明回料行确实走到了 {@code resolveItemCost}。
     *
     * <p>⚠️ 这一条不能用「isNestedComponent 返 true」代替 —— 那测的是谓词, 不是接线。
     * 缺陷正是「谓词判对了、而生产那两个调用点没有把它接上」这种形状。
     */
    @Test
    @DisplayName("接线: addItem 之后的成本重算把回料行交给 NestedBomCostService.resolveItemCost")
    void addItem_semiFinishedRow_reachesNestedCostResolver() {
        NestedBomCostService realPredicate = new NestedBomCostService(null, null);
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class)))
                .thenAnswer(inv -> realPredicate.isNestedComponent(inv.getArgument(0)));
        when(nestedBomCostService.resolveItemCost(anyString(), any(BomRecipeItem.class)))
                .thenReturn(new BigDecimal("46.0000"));

        BomRecipeItem semiRow = new BomRecipeItem();
        semiRow.setId(44L);
        semiRow.setRecipeId("RECIPE-ORPHAN-TEST");
        semiRow.setFactoryId("F006");
        semiRow.setMaterialTypeId(null);
        semiRow.setSemiFinishedRefCode("WIP-F006-REWORK-20260829");
        semiRow.setMaterialCategory(BomRecipeItem.CATEGORY_SEMI_FINISHED);
        semiRow.setStandardQuantity(new BigDecimal("2.5"));
        semiRow.setUnit("kg");

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(semiRow);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(semiRow));

        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "semiFinishedRefCode", "WIP-F006-REWORK-20260829");
        ReflectionTestUtils.setField(dto, "standardQuantity", new BigDecimal("2.5"));
        ReflectionTestUtils.setField(dto, "unit", "kg");
        ReflectionTestUtils.setField(dto, "materialCategory", "SEMI_FINISHED");

        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        verify(nestedBomCostService, atLeastOnce()).resolveItemCost(eq("F006"), same(semiRow));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private BomRecipeItemDTO buildDto(String materialTypeId, BigDecimal qty, String unit, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", unit);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        if ("PACKAGING".equalsIgnoreCase(category)) {
            // P7 (2026-08-03): a packaging line must say which packaging level it plays.
            // This is what the web client sends; without it every PACKAGING case dies on
            // BOM_PACKAGING_ROLE_REQUIRED before reaching its own subject.
            ReflectionTestUtils.setField(dto, "packagingRole", "PRIMARY_CONTAINER");
        }
        return dto;
    }

    private RawMaterialType newMt(String id, String name, String unit, String primaryCode) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setPrimaryCode(primaryCode);
        mt.setTaxRate(TaxRate.TAX_9);
        mt.setUnitPrice(new BigDecimal("10.0000"));
        mt.setMovingAvgPrice(new BigDecimal("12.0000"));
        return mt;
    }
}

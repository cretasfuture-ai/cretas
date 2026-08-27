package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T159-B-anchor: BomRecipeServiceImpl UoM guard on write.
 *
 * <p>Tests the hard-block (R3) at BOM write time:
 * <ul>
 *   <li>Cross-dimension unit (个 vs kg-canonical) → throws BusinessException with 防呆 message</li>
 *   <li>Same-dimension unit (g vs kg-canonical) → allowed</li>
 *   <li>Same unit (kg vs kg-canonical) → allowed</li>
 *   <li>Same count dimension (pcs vs 件-canonical) → allowed</li>
 *   <li>Abaca material → allowed (ABACA_SKIP fail-open)</li>
 *   <li>Null/blank unit in DTO → no block (backwards compat)</li>
 * </ul>
 */
@DisplayName("T159-B R3: BomRecipeServiceImpl — UoM guard at BOM write time")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeServiceImplUomGuardTest {

    @Mock
    private BomRecipeRepository recipeRepo;
    @Mock
    private BomRecipeItemRepository itemRepo;
    @Mock
    private RawMaterialTypeRepository materialTypeRepo;
    /** Placeholder mock so @InjectMocks constructor can inject; replaced in setUp with real converter. */
    @Mock
    private MaterialUomConverter mockUomConverter;
    @Mock
    private UnitContractService unitContractService;

    /** SP1: NestedBomCostService must be mocked so @InjectMocks can inject it. */
    @Mock
    private NestedBomCostService nestedBomCostService;

    /**
     * Every DRAFT write path runs {@code assertPinnedDraft}, which resolves the pinned
     * graph through this collaborator; without the mock @InjectMocks leaves it null.
     */
    @Mock
    private BomWorkflowRevisionService bomWorkflowRevisionService;

    /** addItem/updateItem replace substitutes and recompute family cost after the guard. */
    @Mock
    private BomItemSubstituteService substituteService;
    @Mock
    private BomSeasoningItemRepository seasoningItemRepo;
    @Mock
    private BomProcessInjectionConfigRepository processInjectionConfigRepo;

    private MaterialUomConverter realUomConverter;

    @InjectMocks
    private BomRecipeServiceImpl service;

    private static final String FACTORY = "F001";
    private static final String RECIPE_ID = "recipe-001";
    private static final String MAT_PORK = "mt-pork";     // canonical=kg, not abaca
    private static final String MAT_BOX = "mt-box";       // canonical=件, not abaca
    private static final String MAT_ABACA = "mt-abaca";   // abaca=true

    @BeforeEach
    void setUp() {
        // Use real UomConverter wired with real UnitConversionService
        // (no DB calls needed for write-compat check — just dimension logic)
        com.cretas.aims.repository.MaterialPackagingHierarchyRepository packagingRepo =
                mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class);
        when(packagingRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());
        realUomConverter = new MaterialUomConverter(
                packagingRepo, mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class),
                materialTypeRepo,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        // Replace @InjectMocks placeholder mock with real converter
        ReflectionTestUtils.setField(service, "materialUomConverter", realUomConverter);
        UnitContractService canonicalUnits = TestUnitContractFactory.contract();
        when(unitContractService.normalize(anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.normalize(
                        invocation.getArgument(0), invocation.getArgument(1)));

        // BOM recipe exists in DRAFT status
        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE_ID);
        recipe.setFactoryId(FACTORY);
        recipe.setStatus(BomRecipe.Status.DRAFT);
        // A DRAFT BOM is writable only once it is pinned to a saved Workflow revision
        // (BomRecipeServiceImpl#assertPinnedDraft). Unpinned, none of the UoM checks
        // below are ever reached.
        recipe.setWorkflowRevisionId(9101L);
        recipe.setWorkflowRevisionHash("revision-uom-hash");
        // resolvePinnedGraph either throws or returns a graph — it never returns null.
        // Stubbing null would be a shape production cannot produce.
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), any(BomRecipe.class)))
                .thenReturn(pinnedGraph());
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        // Default material repo: not found
        when(materialTypeRepo.findById(anyString())).thenReturn(Optional.empty());

        // Pork material: canonical=kg
        RawMaterialType pork = new RawMaterialType();
        pork.setId(MAT_PORK);
        pork.setName("冷冻猪舌");
        pork.setUnit("kg");
        pork.setFactoryId(FACTORY);
        pork.setIsAbacaPackaging(false);
        when(materialTypeRepo.findById(MAT_PORK)).thenReturn(Optional.of(pork));

        // Box material: canonical=件
        RawMaterialType box = new RawMaterialType();
        box.setId(MAT_BOX);
        box.setName("吸塑盒2014-3.5");
        box.setUnit("件");
        box.setFactoryId(FACTORY);
        box.setIsAbacaPackaging(false);
        when(materialTypeRepo.findById(MAT_BOX)).thenReturn(Optional.of(box));

        // Abaca material: canonical=箱, abaca=true
        RawMaterialType abaca = new RawMaterialType();
        abaca.setId(MAT_ABACA);
        abaca.setName("牛肉");
        abaca.setUnit("箱");
        abaca.setFactoryId(FACTORY);
        abaca.setIsAbacaPackaging(true);
        when(materialTypeRepo.findById(MAT_ABACA)).thenReturn(Optional.of(abaca));

        // itemRepo default behavior
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(anyString())).thenReturn(Collections.emptyList());
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ============================================================
    // (c) Cross-dimension BLOCKED cases
    // ============================================================

    @Test
    @DisplayName("(c) addItem: 个 BOM unit vs kg-canonical material → BusinessException with 防呆 message")
    void addItem_crossDimension_个vsKg_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "个", new BigDecimal("100"));

        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getMessage())
                            .contains("冷冻猪舌")    // real material name
                            .contains("个")          // echoes the unit the user typed
                            .contains("kg")          // material master unit
                            .contains("BOM单位")
                            // 阴性对照: the canonical code is internal vocabulary and must not
                            // reach a user-facing message (it renders 「只」 as "pcs").
                            .doesNotContain("pcs");
                    assertThat(be.getCode()).isIn(409, 400);
                    // 4-位一体: must have actionHint
                    assertThat(be.getActionHint()).isNotBlank();
                });
    }

    @Test
    @DisplayName("(c) addItem: pcs BOM unit vs kg-canonical material → BlockedException")
    void addItem_crossDimension_pcsVsKg_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "pcs", new BigDecimal("100"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冷冻猪舌");
    }

    @Test
    @DisplayName("(c) addItem: kg BOM unit vs 件-canonical material → BlockedException")
    void addItem_crossDimension_kgVsJian_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "kg", new BigDecimal("50"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("吸塑盒");
    }

    // ============================================================
    // (a) Same-dimension ALLOWED cases
    // ============================================================

    @Test
    @DisplayName("(a) addItem: g BOM unit vs kg-canonical material → ALLOWED (g↔kg same dimension)")
    void addItem_sameDimension_gVsKg_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "g", new BigDecimal("500"));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID)).thenReturn(Collections.emptyList());
        // Should not throw
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("g");
    }

    @Test
    @DisplayName("(a) addItem: kg BOM unit vs kg-canonical material → ALLOWED (same unit)")
    void addItem_sameUnit_kgVsKg_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "kg", new BigDecimal("2"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("kg");
    }

    // ============================================================
    // (b) Same count dimension ALLOWED
    // ============================================================

    @Test
    @DisplayName("(b) addItem: 个 BOM unit vs 件-canonical material → ALLOWED (同计数维度)")
    void addItem_countDimension_geVsJian_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "个", new BigDecimal("100"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        // 个 is its own canonical count unit (DISTINCT_COUNT_LABELS, #1976): it is written
        // through as the user typed it, not folded into 件/pcs.
        assertThat(item.getUnit()).isEqualTo("个");
        // 阴性对照: folding 件/个/只 together is exactly the LIUSHANMEN 2026-07-30 defect.
        assertThat(item.getUnit()).isNotIn("pcs", "件");
    }

    @Test
    @DisplayName("(b) addItem: pcs BOM unit vs 件-canonical material → ALLOWED")
    void addItem_countDimension_pcsVsJian_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "pcs", new BigDecimal("200"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        // pcs is an alias of 件, so it resolves to that canonical unit — and it is the
        // Chinese name that gets persisted, not the English code.
        assertThat(item.getUnit()).isEqualTo("件");
    }

    // ============================================================
    // (d) Abaca material → ALLOWED
    // ============================================================

    @Test
    @DisplayName("(d) addItem: localized abaca package alias → canonical case allowed")
    void addItem_abacaCaseAlias_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_ABACA, "箱", new BigDecimal("100"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getMaterialTypeId()).isEqualTo(MAT_ABACA);
    }

    // ============================================================
    // Null/blank unit → no block
    // ============================================================

    @Test
    @DisplayName("null BOM unit → explicit 400 service validation")
    void addItem_nullUnit_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, null, new BigDecimal("500"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOM_ITEM_UNIT_REQUIRED"));
    }

    @Test
    @DisplayName("blank BOM unit → explicit 400 service validation")
    void addItem_blankUnit_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "  ", new BigDecimal("500"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOM_ITEM_UNIT_REQUIRED"));
    }

    // ============================================================
    // updateItem path also guarded
    // ============================================================

    @Test
    @DisplayName("updateItem: cross-dimension 个 vs kg-canonical → BlockedException")
    void updateItem_crossDimension_blocked() {
        BomRecipeItem existingItem = buildExistingItem(1L, MAT_PORK);
        when(itemRepo.findById(1L)).thenReturn(Optional.of(existingItem));

        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "个", new BigDecimal("100"));
        assertThatThrownBy(() -> service.updateItem(FACTORY, 1L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冷冻猪舌");
    }

    @Test
    @DisplayName("updateItem: same dimension g vs kg-canonical → ALLOWED")
    void updateItem_sameDimension_allowed() {
        BomRecipeItem existingItem = buildExistingItem(2L, MAT_PORK);
        when(itemRepo.findById(2L)).thenReturn(Optional.of(existingItem));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "g", new BigDecimal("500"));
        BomRecipeItem updated = service.updateItem(FACTORY, 2L, dto);
        assertThat(updated.getUnit()).isEqualTo("g");
    }

    // ============================================================
    // Helper
    // ============================================================

    /**
     * Minimal pinned Workflow graph owning one RAW input slot per fixture material.
     *
     * <p>Slot ids mirror {@link #bomItemDto}: {@code raw-<matId> / in-<matId> / edge-<matId>}.
     */
    private PinnedWorkflowGraph pinnedGraph() {
        List<ProductProcessWorkflowDTO.Node> nodes = new java.util.ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new java.util.ArrayList<>();
        List<Map<String, Object>> ports = new java.util.ArrayList<>();
        double x = 0D;
        for (String materialTypeId : List.of(MAT_PORK, MAT_BOX, MAT_ABACA)) {
            Map<String, Object> rawData = new LinkedHashMap<>();
            rawData.put("skuId", materialTypeId);
            rawData.put("name", materialTypeId);
            nodes.add(new ProductProcessWorkflowDTO.Node(
                    "raw-" + materialTypeId, "RAW_MATERIAL",
                    new ProductProcessWorkflowDTO.Position(x, 0D), rawData));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge-" + materialTypeId, "raw-" + materialTypeId, "output",
                    "process", "in-" + materialTypeId));
            ports.add(Map.of("id", "in-" + materialTypeId, "direction", "INPUT",
                    "materialNodeId", "raw-" + materialTypeId,
                    "materialKind", "RAW_MATERIAL", "ordinal", ports.size()));
            x += 100D;
        }
        List<Map<String, Object>> allPorts = new java.util.ArrayList<>(ports);
        allPorts.add(Map.of("id", "output", "direction", "OUTPUT",
                "materialNodeId", "terminal", "materialKind", "FINISHED_GOOD",
                "ordinal", 0, "outputRole", "MAIN",
                "costAllocationRatio", new BigDecimal("100")));
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-UOM");
        processData.put("ports", allPorts);
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "process", "PROCESS", new ProductProcessWorkflowDTO.Position(400D, 0D), processData));
        Map<String, Object> terminalData = new LinkedHashMap<>();
        terminalData.put("skuId", "SKU-UOM");
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "terminal", "FINISHED_GOOD",
                new ProductProcessWorkflowDTO.Position(500D, 0D), terminalData));
        edges.add(new ProductProcessWorkflowDTO.Edge(
                "edge-output", "process", "output", "terminal", "input"));
        return new PinnedWorkflowGraph(
                9101L, 10L, 1, "revision-uom-hash", "SKU-UOM", "terminal",
                List.of(MAT_PORK, MAT_BOX, MAT_ABACA),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-UOM", 1)),
                List.copyOf(nodes), List.copyOf(edges));
    }

    private BomRecipeItemDTO bomItemDto(String materialTypeId, String unit, BigDecimal qty) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setMaterialTypeId(materialTypeId);
        dto.setUnit(unit);
        dto.setStandardQuantity(qty);
        dto.setYieldRate(new BigDecimal("100.00"));
        // A line on a pinned draft carries the Workflow slot the user picked on the
        // canvas. Without it the service auto-binds against the pinned graph, which is
        // a different code path than the UoM dimension guard this class exists for.
        dto.setWorkflowMaterialNodeId("raw-" + materialTypeId);
        dto.setWorkflowInputPortId("in-" + materialTypeId);
        dto.setWorkflowEdgeId("edge-" + materialTypeId);
        return dto;
    }

    private BomRecipeItem buildExistingItem(Long id, String materialTypeId) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setFactoryId(FACTORY);
        item.setRecipeId(RECIPE_ID);   // must match the mocked recipe in setUp
        item.setMaterialTypeId(materialTypeId);
        item.setStandardQuantity(new BigDecimal("100"));
        return item;
    }
}

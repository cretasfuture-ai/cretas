package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductConfigurationCompletenessReport;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductConfigurationReadinessServiceTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "FG-001";

    @Mock ProductProcessWorkflowRepository workflowRepository;
    @Mock ProductProcessWorkflowActivationRepository activationRepository;
    @Mock ProductProcessWorkflowValidator workflowValidator;
    @Mock ProductProcessWorkflowCatalogValidator catalogValidator;
    @Mock ProductProcessWorkflowUnitValidator unitValidator;
    @Mock BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock BomRecipeRepository recipeRepository;
    @Mock BomRecipeItemRepository itemRepository;
    @Mock BomSeasoningItemRepository seasoningRepository;
    @Mock ProductPackagingSpecRepository packagingSpecRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock UnitContractService unitContractService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductConfigurationReadinessService service;

    @BeforeEach
    void setUp() {
        service = new ProductConfigurationReadinessService(
                workflowRepository,
                activationRepository,
                workflowValidator,
                catalogValidator,
                unitValidator,
                bomWorkflowRevisionService,
                recipeRepository,
                itemRepository,
                seasoningRepository,
                packagingSpecRepository,
                productTypeRepository,
                unitContractService,
                objectMapper);
    }

    @Test
    void activePinnedBomWithoutMutableDraftUsesMultiEntryTargetGraphOnce() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.ACTIVE, true);
        ProductProcessWorkflowDTO.Node process = processNode("process-shared", "WP-SHARED", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A", "RAW-B"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep("process-shared", "WP-SHARED", 1)));
        stubCommon(recipe, List.of(binding("process-shared", "WP-SHARED")));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, null);

        assertTrue(report.isWorkflowDraftComplete());
        assertTrue(report.isBomComplete());
        assertTrue(report.isBomActive());
        assertEquals("BOM_ACTIVE", report.getStage());
        assertEquals(1, report.getProcessAuxiliaryStatuses().size());
        assertEquals("process-shared",
                report.getProcessAuxiliaryStatuses().getFirst().getWorkflowProcessNodeId());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        assertTrue(report.getIssues().stream()
                .noneMatch(issue -> "WORKFLOW_DRAFT_MISSING".equals(issue.getCode())));
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void mainOutputReadinessIncludesItsOwnOutputExclusiveAuxiliary() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        recipe.setSharedRecipeId(recipe.getId());
        BomSeasoningItem exclusive = binding("process-package-main", "WP-PACK-MAIN");
        exclusive.setCostScope("OUTPUT_EXCLUSIVE");
        ProductProcessWorkflowDTO.Node process =
                processNode("process-package-main", "WP-PACK-MAIN", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep(
                        "process-package-main", "WP-PACK-MAIN", 1)));
        stubCommon(recipe, List.of(exclusive));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        assertTrue(report.getProcessAuxiliaryStatuses().getFirst().isComplete());
    }

    @Test
    void pinnedRepeatedMasterProcessNodesMayOmitAuxiliaryBindings() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        ProductProcessWorkflowDTO.Node first = processNode("process-first", "WP-SAME", "REQUIRED");
        ProductProcessWorkflowDTO.Node second = processNode("process-second", "WP-SAME", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A", "RAW-B"),
                List.of(first, second),
                List.of(
                        new PinnedWorkflowGraph.ProcessStep("process-first", "WP-SAME", 1),
                        new PinnedWorkflowGraph.ProcessStep("process-second", "WP-SAME", 2)));
        stubCommon(recipe, List.of(binding("process-first", "WP-SAME")));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(2, report.getProcessAuxiliaryStatuses().size());
        ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus firstStatus =
                report.getProcessAuxiliaryStatuses().get(0);
        ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus secondStatus =
                report.getProcessAuxiliaryStatuses().get(1);
        assertEquals("process-first", firstStatus.getWorkflowProcessNodeId());
        assertEquals(1, firstStatus.getBindingCount());
        assertTrue(firstStatus.isComplete());
        assertEquals("process-second", secondStatus.getWorkflowProcessNodeId());
        assertEquals(0, secondStatus.getBindingCount());
        assertTrue(secondStatus.isComplete());
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_AUXILIARY")));
    }

    @Test
    void legacyUnpinnedRecipeStillMatchesSeasoningByMasterProcessId() throws Exception {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, false);
        ProductProcessWorkflowDTO.Node process = processNode("legacy-node", "WP-LEGACY", "REQUIRED");
        ProductProcessWorkflow draft = new ProductProcessWorkflow();
        draft.setId(41L);
        draft.setFactoryId(FACTORY);
        draft.setProductTypeId(PRODUCT);
        draft.setDefinitionVersion(2);
        draft.setSchemaVersion(1);
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        draft.setNodesJson(objectMapper.writeValueAsString(List.of(process)));
        draft.setEdgesJson("[]");
        draft.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        BomSeasoningItem legacy = binding(null, "WP-LEGACY");
        stubCommon(recipe, List.of(legacy));
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY, PRODUCT, ProductProcessWorkflow.Status.DRAFT)).thenReturn(Optional.of(draft));

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals("legacy-node",
                report.getProcessAuxiliaryStatuses().getFirst().getWorkflowProcessNodeId());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        verify(bomWorkflowRevisionService, never()).resolvePinnedGraph(FACTORY, recipe);
    }

    @Test
    void activePinnedBomWithMissingAuxiliaryPolicyRemainsUsableWhenRawConfigured() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.ACTIVE, true);
        ProductProcessWorkflowDTO.Node process = processNode("legacy-process", "WP-LEGACY", null);
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep("legacy-process", "WP-LEGACY", 1)));
        stubCommon(recipe, List.of());
        when(recipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport activeReport =
                service.requireActiveBomComplete(FACTORY, PRODUCT);
        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());
        assertTrue(activeReport.isBomActive());
        assertTrue(report.isBomComplete());
        assertTrue(report.getProcessAuxiliaryStatuses().getFirst().isComplete());
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_AUXILIARY")));
    }

    @Test
    void missingRawMaterialStillBlocksBomReadiness() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of(), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId())).thenReturn(List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertFalse(report.isBomComplete());
        assertTrue(report.getIssues().stream().anyMatch(issue ->
                "BOM_RAW_REQUIRED".equals(issue.getCode())));
    }

    @Test
    void packagingLevelsAreOptionalWhenRawMaterialIsConfigured() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setUnit("袋");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.of(product));
        when(unitContractService.describe(FACTORY, "袋")).thenReturn(Optional.of(new CanonicalUnit(
                "bag", UnitDimension.PACKAGE, "bag", BigDecimal.ONE, "袋", 0)));

        ProductPackagingSpec outerCase = new ProductPackagingSpec();
        outerCase.setId("PACK-CASE");
        outerCase.setFactoryId(FACTORY);
        outerCase.setProductTypeId(PRODUCT);
        outerCase.setName("外箱");
        outerCase.setPackageUnit("箱");
        outerCase.setBaseUnit("袋");
        when(packagingSpecRepository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(outerCase));

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(2, report.getPackagingLevels().size());
        assertTrue(report.getPackagingLevels().stream()
                .allMatch(ProductConfigurationCompletenessReport.PackagingLevelStatus::isComplete));
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_PACKAGING")
                        || "BOM_BASE_PACKAGING_REQUIRED".equals(issue.getCode())));
    }

    /**
     * 🔴 2026-08-25 owner 裁定: <b>成品必须有包材</b>。
     *
     * <p>改之前包材那一档的 {@code complete} 是硬编码 {@code true}，
     * 完整性只 {@code return rawCount > 0} —— 包材从来没参与过判定。
     * 实测 F006 13 个 ACTIVE BOM 全是 {@code FINISHED_PRODUCT}，只有 3 个配了包材。
     */
    @Test
    void finishedGoodsWithoutPackagingIsIncomplete() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(productOfCategory("FINISHED_PRODUCT", "kg")));
        when(unitContractService.describe(FACTORY, "kg")).thenReturn(Optional.empty());

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertFalse(report.isBomComplete(),
                "成品没配包材却算完整 —— 那正是改之前的行为");
        assertTrue(report.getIssues().stream()
                        .anyMatch(issue -> "BOM_PACKAGING_REQUIRED".equals(issue.getCode())),
                "没有告诉用户差的是包材: " + report.getIssues());
    }

    /**
     * 🔴 阴性对照: 只对<b>成品</b>强制 —— 半成品/原料/来料加工不受影响。
     *
     * <p>owner 原话: 「除非它生产的是辅料种东西, 它可以不选择包材没问题」。
     * 没有这条对照, 上面那条断言无法区分「按成品收窄」和「对所有产品一刀切」。
     */
    @Test
    void semiFinishedWithoutPackagingStaysComplete() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(productOfCategory("SEMI_FINISHED", "kg")));
        when(unitContractService.describe(FACTORY, "kg")).thenReturn(Optional.empty());

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete(),
                "半成品被包材规则误伤了 —— 裁定里明确说这类可以不配包材");
        assertTrue(report.getIssues().stream()
                        .noneMatch(issue -> "BOM_PACKAGING_REQUIRED".equals(issue.getCode())),
                "半成品不该报缺包材: " + report.getIssues());
    }

    /** 成品配了包材就完整 —— 阳性对照, 否则上面那条 assertFalse 可能只是恒假。 */
    @Test
    void finishedGoodsWithPackagingIsComplete() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of(rawItem(recipe.getId()), packagingItem(recipe.getId())));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(productOfCategory("FINISHED_PRODUCT", "kg")));
        when(unitContractService.describe(FACTORY, "kg")).thenReturn(Optional.empty());

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete(), "成品配了包材仍被判不完整");
        assertTrue(report.getIssues().stream()
                .noneMatch(issue -> "BOM_PACKAGING_REQUIRED".equals(issue.getCode())));
    }

    private ProductType productOfCategory(String productCategory, String unit) {
        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setUnit(unit);
        product.setProductCategory(productCategory);
        return product;
    }

    private BomRecipeItem packagingItem(String recipeId) {
        return BomRecipeItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY)
                .materialTypeId("PKG-A")
                .unit("\u76d2")
                .materialCategory("PACKAGING")
                .build();
    }

    /**
     * 🔴 2026-08-25 owner 裁定: 辅料<b>不分类、一律选填</b>, 但没配要提示一声。
     *
     * <p>原话:「辅料它不是必须的, 也是 optional 的, 但是要做一个提示 ——
     * 如果他没有配置辅料的话, 提示当前没有配置辅料, 请确定是否要配置,
     * 如果不配置, 让他继续保存就好了。」
     *
     * <p>所以这一条钉的是<b>「提示了」和「没拦人」同时成立</b> ——
     * 只钉前者会让它变成一道拦人的闸, 只钉后者等于没提示。
     */
    @Test
    void finishedGoodsWithoutSeasoningWarnsButDoesNotBlock() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());   // 没有任何辅料
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of(rawItem(recipe.getId()), packagingItem(recipe.getId())));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(productOfCategory("FINISHED_PRODUCT", "kg")));
        when(unitContractService.describe(FACTORY, "kg")).thenReturn(Optional.empty());

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        ProductConfigurationCompletenessReport.Issue seasoning = report.getIssues().stream()
                .filter(i -> "BOM_SEASONING_ABSENT".equals(i.getCode()))
                .findFirst().orElse(null);
        assertTrue(seasoning != null, "没配辅料却一声不吭: " + report.getIssues());
        assertEquals("WARNING", seasoning.getSeverity(),
                "辅料提示被标成了阻断项 —— 那就不是「可以继续保存」了");

        // 🔴 另一半: 它【不】影响完整性, 发布/激活照常放行
        assertTrue(report.isBomComplete(),
                "辅料只是提示, 不该把 BOM 判成不完整");
    }

    /** 阳性对照: 配了辅料就不提示 (否则「总是提示」和「该提示才提示」分不开)。 */
    @Test
    void finishedGoodsWithSeasoningIsNotWarned() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of(binding(null, "WP-LEGACY")));
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of(rawItem(recipe.getId()), packagingItem(recipe.getId())));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(productOfCategory("FINISHED_PRODUCT", "kg")));
        when(unitContractService.describe(FACTORY, "kg")).thenReturn(Optional.empty());

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.getIssues().stream()
                        .noneMatch(i -> "BOM_SEASONING_ABSENT".equals(i.getCode())),
                "配了辅料还在提示没配: " + report.getIssues());
    }

    /** 阴性对照: 缺主原料仍然是 BLOCKING —— 别把整套 issue 都降级成提示。 */
    @Test
    void missingRawMaterialStaysBlocking() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of());   // 连主原料都没有
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        ProductConfigurationCompletenessReport.Issue raw = report.getIssues().stream()
                .filter(i -> "BOM_RAW_REQUIRED".equals(i.getCode()))
                .findFirst().orElse(null);
        assertTrue(raw != null, "缺主原料没报: " + report.getIssues());
        assertEquals("BLOCKING", raw.getSeverity(), "缺主原料被降级成了提示");
        assertFalse(report.isBomComplete());
    }

    private void stubCommon(BomRecipe recipe, List<BomSeasoningItem> bindings) {
        when(recipeRepository.findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(recipe));
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of(rawItem(recipe.getId())));
        when(seasoningRepository.findByRecipeIdOrderBySeqAsc(recipe.getId())).thenReturn(bindings);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.empty());
        when(packagingSpecRepository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(FACTORY, PRODUCT))
                .thenReturn(List.of());
        when(activationRepository.findByFactoryIdAndProductTypeId(FACTORY, PRODUCT))
                .thenReturn(Optional.empty());
    }

    private BomRecipe pinnedRecipe(BomRecipe.Status status, boolean current) {
        BomRecipe recipe = recipe(status, current);
        recipe.setWorkflowRevisionId(71L);
        recipe.setWorkflowId(41L);
        recipe.setWorkflowDefinitionVersion(2);
        recipe.setWorkflowRevisionHash("revision-hash");
        recipe.setWorkflowSchemaVersion(1);
        recipe.setWorkflowNodesSnapshotJson("[]");
        recipe.setWorkflowEdgesSnapshotJson("[]");
        return recipe;
    }

    private BomRecipe recipe(BomRecipe.Status status, boolean current) {
        return BomRecipe.builder()
                .id("BOM-1")
                .factoryId(FACTORY)
                .recipeCode("BOM-1")
                .productTypeId(PRODUCT)
                .productName("Finished")
                .version(1)
                .outputQuantityPerUnit(BigDecimal.ONE)
                .status(status)
                .isCurrent(current)
                .build();
    }

    private BomRecipeItem rawItem(String recipeId) {
        return BomRecipeItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY)
                .materialTypeId("RAW-A")
                .unit("kg")
                .materialCategory("RAW")
                .build();
    }

    private BomSeasoningItem binding(String processNodeId, String workProcessId) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setRecipeId("BOM-1");
        item.setFactoryId(FACTORY);
        item.setWorkflowProcessNodeId(processNodeId);
        item.setWorkProcessId(workProcessId);
        return item;
    }

    private PinnedWorkflowGraph graph(
            List<String> roots,
            List<ProductProcessWorkflowDTO.Node> nodes,
            List<PinnedWorkflowGraph.ProcessStep> processes) {
        return new PinnedWorkflowGraph(
                71L,
                41L,
                2,
                "revision-hash",
                PRODUCT,
                "finished",
                roots,
                processes,
                nodes,
                List.of());
    }

    private ProductProcessWorkflowDTO.Node processNode(
            String id,
            String workProcessId,
            String auxiliaryPolicy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", id);
        data.put("auxiliaryPolicy", auxiliaryPolicy);
        data.put("ports", new ArrayList<>());
        return new ProductProcessWorkflowDTO.Node(
                id,
                "PROCESS",
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                data);
    }
}

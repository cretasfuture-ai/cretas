package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(BomRecipeServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("BOM draft lifecycle JPA integration")
class BomRecipeDraftLifecycleJpaTest {

    @Autowired
    private BomRecipeService service;

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private BomRecipeRepository recipeRepository;

    @Autowired
    private BomRecipeItemRepository itemRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ProductWorkflowResolutionService workflowResolutionService;

    @MockBean
    private MaterialUomConverter materialUomConverter;

    @MockBean
    private NestedBomCostService nestedBomCostService;

    @MockBean
    private UnitContractService unitContractService;

    @MockBean
    private ProductConfigurationReadinessService productConfigurationReadinessService;

    @MockBean
    private BomItemSubstituteService bomItemSubstituteService;

    @MockBean
    private BomWorkflowRevisionService bomWorkflowRevisionService;

    @BeforeEach
    void stubUnitContract() {
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = switch (raw) {
                case "盒" -> "box";
                case "袋" -> "bag";
                case "包" -> "pack";
                case "瓶" -> "bottle";
                case "克" -> "g";
                case "千克" -> "kg";
                case "毫升" -> "ml";
                case "升" -> "L";
                default -> raw;
            };
            UnitDimension dimension = switch (code) {
                case "g", "kg" -> UnitDimension.MASS;
                case "ml", "L" -> UnitDimension.VOLUME;
                default -> UnitDimension.PACKAGE;
            };
            return new UnitNormalizationResult(raw, code,
                    new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 6));
        });
        when(unitContractService.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String left = invocation.getArgument(1);
                    String right = invocation.getArgument(2);
                    return canonicalUnit(left).equals(canonicalUnit(right));
                });
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        when(bomWorkflowRevisionService.autoBindUniqueDraft(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.bindExactRevision(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class), anyLong()))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.resolveExactBinding(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class), anyLong()))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)));
        when(bomWorkflowRevisionService.resolvePinnedGraph(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).graph());
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> workflowBinding(invocation.getArgument(1)).terminalOutputs());
    }

    private BomWorkflowRevisionService.WorkflowBinding workflowBinding(BomRecipe recipe) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(100L);
        revision.setFactoryId(recipe.getFactoryId());
        revision.setProductTypeId(recipe.getProductTypeId());
        revision.setWorkflowId(10L);
        revision.setDefinitionVersion(1);
        revision.setSchemaVersion(1);
        revision.setRevisionHash("test-revision");
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
        recipe.setTargetTerminalNodeId("terminal");
        recipe.setOutputRole(BomRecipe.OutputRole.MAIN);
        recipe.setCostAllocationRatio(new BigDecimal("100"));

        String materialTypeId = "RAW-WF-" + recipe.getProductTypeId();
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("skuId", materialTypeId);
        rawData.put("name", "Workflow input");
        ProductProcessWorkflowDTO.Node raw = new ProductProcessWorkflowDTO.Node(
                "raw-input", "RAW_MATERIAL", new ProductProcessWorkflowDTO.Position(0D, 0D), rawData);
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-TEST");
        processData.put("ports", List.of(
                Map.of("id", "input", "direction", "INPUT", "materialNodeId", "raw-input",
                        "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 0),
                Map.of("id", "output", "direction", "OUTPUT", "materialNodeId", "terminal",
                        "materialKind", "FINISHED_GOOD", "unit", recipe.getOutputUnit(),
                        "ordinal", 0, "outputRole", "MAIN",
                        "costAllocationRatio", new BigDecimal("100"))));
        ProductProcessWorkflowDTO.Node process = new ProductProcessWorkflowDTO.Node(
                "process", "PROCESS", new ProductProcessWorkflowDTO.Position(100D, 0D), processData);
        Map<String, Object> terminalData = new LinkedHashMap<>();
        terminalData.put("skuId", recipe.getProductTypeId());
        terminalData.put("baseUnit", recipe.getOutputUnit());
        ProductProcessWorkflowDTO.Node terminal = new ProductProcessWorkflowDTO.Node(
                "terminal", "FINISHED_GOOD", new ProductProcessWorkflowDTO.Position(200D, 0D), terminalData);
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(raw, process, terminal);
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge("edge-input", "raw-input", "output", "process", "input"),
                new ProductProcessWorkflowDTO.Edge("edge-output", "process", "output", "terminal", "input"));
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(nodes);
        definition.setEdges(edges);
        BomWorkflowRevisionService.TerminalOutput target =
                new BomWorkflowRevisionService.TerminalOutput(
                        "terminal", recipe.getProductTypeId(), "process", "output",
                        BomRecipe.OutputRole.MAIN, new BigDecimal("100"), recipe.getOutputUnit(),
                        BomWorkflowRevisionService.CostAllocationBasis.DECLARED);
        PinnedWorkflowGraph graph = new PinnedWorkflowGraph(
                revision.getId(), revision.getWorkflowId(), revision.getDefinitionVersion(),
                revision.getRevisionHash(), recipe.getProductTypeId(), "terminal",
                List.of(materialTypeId),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-TEST", 1)),
                nodes, edges);
        return new BomWorkflowRevisionService.WorkflowBinding(
                revision, definition, graph, List.of(target), target);
    }

    @Test
    @DisplayName("zero versions creates a v1 DRAFT with an auto-generated Workflow input skeleton")
    void createsEmptyFirstDraft() {
        ProductType product = saveProduct("FIRST", "盒", new BigDecimal("500"));

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(draft.getProductName()).isEqualTo(product.getName());
        assertThat(draft.getOutputUnit()).isEqualTo("box");
        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("500");
        assertThat(draft.getNetContentUnit()).isEqualTo("g");
        assertThat(draft.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(item.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(item.getWorkflowEdgeId()).isEqualTo("edge-input");
                    assertThat(item.getCostScope()).isEqualTo("SHARED");
                    assertThat(item.getCostScopeKey()).isEqualTo("terminal");
                });
    }

    @Test
    void publicationPreflightReusesRealDraftActivationGuardsWithoutWriting() {
        ProductType product = saveProduct("PUBLISH-READONLY", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        ProductProcessWorkflowRevision target = workflowBinding(draft).revision();
        BomRecipeItem input = itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        String originalPort = input.getWorkflowInputPortId();
        input.setWorkflowInputPortId("missing-slot");
        itemRepository.saveAndFlush(input);
        assertThatThrownBy(() -> service.validateDraftForPublish(product.getFactoryId(), draft.getId(), target))
                .isInstanceOf(BusinessException.class);
        input.setWorkflowInputPortId(originalPort);
        itemRepository.saveAndFlush(input);
        completeWorkflowSkeleton(draft);
        BomRecipe badOutput = recipeRepository.findById(draft.getId()).orElseThrow();
        badOutput.setOutputQuantityPerUnit(new BigDecimal("2"));
        recipeRepository.saveAndFlush(badOutput);
        assertThatThrownBy(() -> service.validateDraftForPublish(product.getFactoryId(), draft.getId(), target))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("BOM_OUTPUT_QUANTITY_MISMATCH"));
        badOutput.setOutputQuantityPerUnit(BigDecimal.ONE);
        recipeRepository.saveAndFlush(badOutput);
        BomRecipe before = recipeRepository.findById(draft.getId()).orElseThrow();
        var updated = before.getUpdatedAt();
        assertThatCode(() -> service.validateDraftForPublish(product.getFactoryId(), draft.getId(), target)).doesNotThrowAnyException();
        assertThat(recipeRepository.findById(draft.getId())).hasValueSatisfying(after -> {
            assertThat(after.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
            assertThat(after.getIsCurrent()).isFalse();
            assertThat(after.getUpdatedAt()).isEqualTo(updated);
        });
    }

    @Test
    @DisplayName("volume SKU snapshots one bottle output and 500 ml net content")
    void snapshotsVolumeSkuWithoutTurningNetContentIntoOutputQuantity() {
        ProductType product = saveStructuredProduct(
                "VOLUME", "瓶", new BigDecimal("500"), "ml");

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getOutputUnit()).isEqualTo("bottle");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("500");
        assertThat(draft.getNetContentUnit()).isEqualTo("ml");
    }

    @Test
    @DisplayName("editing a generated input cannot disguise another material with the old Workflow slot")
    void rejectsChangingWorkflowSlotMainMaterial() {
        ProductType product = saveProduct("EDIT-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        RawMaterialType replacement = saveRawMaterial(
                product.getFactoryId(), "RAW-REPLACEMENT-" + product.getId(), "替代主料");

        BomRecipeItemDTO request = new BomRecipeItemDTO();
        request.setMaterialTypeId(replacement.getId());
        request.setMaterialCategory("RAW");
        request.setUnit("kg");
        request.setSortOrder(skeleton.getSortOrder());
        request.setIsOptional(false);
        request.setWorkflowMaterialNodeId(skeleton.getWorkflowMaterialNodeId());
        request.setWorkflowInputPortId(skeleton.getWorkflowInputPortId());
        request.setWorkflowEdgeId(skeleton.getWorkflowEdgeId());
        request.setCostScope(skeleton.getCostScope());

        assertThatThrownBy(() ->
                service.updateItem(product.getFactoryId(), skeleton.getId(), request))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("BOM_WORKFLOW_INPUT_SLOT_MATERIAL_MISMATCH"));
        assertThat(itemRepository.findById(skeleton.getId()))
                .hasValueSatisfying(saved ->
                        assertThat(saved.getMaterialTypeId())
                                .isEqualTo("RAW-WF-" + product.getId()));
    }

    @Test
    @DisplayName("full payload update merges the Workflow skeleton instead of deleting and recreating it")
    void fullPayloadUpdateMergesWorkflowSkeleton() {
        ProductType product = saveProduct("MERGE-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();

        BomRecipeItemDTO row = new BomRecipeItemDTO();
        row.setMaterialTypeId(skeleton.getMaterialTypeId());
        row.setMaterialCategory("RAW");
        row.setUnit("kg");
        row.setStandardQuantity(new BigDecimal("2.5"));
        UpdateBomRecipeRequest request = new UpdateBomRecipeRequest();
        request.setItems(List.of(row));

        service.updateRecipe(product.getFactoryId(), draft.getId(), request);

        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getId()).isEqualTo(skeleton.getId());
                    assertThat(saved.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(saved.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(saved.getWorkflowEdgeId()).isEqualTo("edge-input");
                    assertThat(saved.getStandardQuantity()).isEqualByComparingTo("2.5");
                });
    }

    @Test
    @DisplayName("required Workflow input cannot be deleted from a BOM draft")
    void requiredWorkflowInputCannotBeDeleted() {
        ProductType product = saveProduct("DELETE-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();

        assertThatThrownBy(() ->
                service.deleteItem(product.getFactoryId(), skeleton.getId()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("BOM_WORKFLOW_REQUIRED_INPUT_DELETE_FORBIDDEN"));
        assertThat(itemRepository.findById(skeleton.getId())).isPresent();
    }

    @Test
    @DisplayName("partial Workflow slot identity cannot bypass required input deletion protection")
    void partialWorkflowIdentityCannotBeDeleted() {
        ProductType product = saveProduct("DELETE-PARTIAL-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        skeleton.setWorkflowInputPortId(null);
        skeleton.setWorkflowEdgeId(null);
        itemRepository.saveAndFlush(skeleton);

        assertThatThrownBy(() ->
                service.deleteItem(product.getFactoryId(), skeleton.getId()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("BOM_WORKFLOW_INPUT_SLOT_IDENTITY_INCOMPLETE"));
        assertThat(itemRepository.findById(skeleton.getId())).isPresent();
    }

    @Test
    @DisplayName("zero-tuple RAW row that uniquely matches a pinned slot cannot be deleted")
    void zeroTupleRequiredWorkflowInputCannotBeDeleted() {
        ProductType product = saveProduct("DELETE-ZERO-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        skeleton.setWorkflowMaterialNodeId(null);
        skeleton.setWorkflowInputPortId(null);
        skeleton.setWorkflowEdgeId(null);
        itemRepository.saveAndFlush(skeleton);

        assertThatThrownBy(() ->
                service.deleteItem(product.getFactoryId(), skeleton.getId()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("BOM_WORKFLOW_REQUIRED_INPUT_DELETE_FORBIDDEN"));
        assertThat(itemRepository.findById(skeleton.getId())).isPresent();
    }

    @Test
    @DisplayName("same-revision ensure repairs a replacement row from its unique deleted predecessor")
    void sameRevisionEnsureRepairsUniqueDeletedPredecessor() {
        ProductType product = saveProduct("ENSURE-RECOVER-SLOT", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem predecessor =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        predecessor.softDelete();
        itemRepository.saveAndFlush(predecessor);

        BomRecipeItem replacement = BomRecipeItem.builder()
                .recipeId(draft.getId())
                .factoryId(product.getFactoryId())
                .materialTypeId(predecessor.getMaterialTypeId())
                .materialName(predecessor.getMaterialName())
                .unit(predecessor.getUnit())
                .standardQuantity(BigDecimal.ONE)
                .yieldRate(new BigDecimal("100"))
                .actualQuantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .priceUnit(predecessor.getUnit())
                .quantityToPriceFactor(BigDecimal.ONE)
                .materialCategory("RAW")
                .sortOrder(0)
                .isOptional(false)
                .perPortion(false)
                .build();
        replacement = itemRepository.saveAndFlush(replacement);

        BomRecipe reopened =
                service.ensureDraft(product.getFactoryId(), product.getId(), draft.getWorkflowRevisionId());

        assertThat(reopened.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(itemRepository.findById(replacement.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(saved.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(saved.getWorkflowEdgeId()).isEqualTo("edge-input");
                });
    }

    @Test
    @DisplayName("same-revision activation repairs a replacement row from its unique deleted predecessor")
    void activationRepairsUniqueDeletedPredecessor() {
        ProductType product = saveProduct("RECOVER-SLOT", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem predecessor =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        predecessor.softDelete();
        itemRepository.saveAndFlush(predecessor);

        BomRecipeItem replacement = BomRecipeItem.builder()
                .recipeId(draft.getId())
                .factoryId(product.getFactoryId())
                .materialTypeId(predecessor.getMaterialTypeId())
                .materialName(predecessor.getMaterialName())
                .unit(predecessor.getUnit())
                .standardQuantity(BigDecimal.ONE)
                .yieldRate(new BigDecimal("100"))
                .actualQuantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .priceUnit(predecessor.getUnit())
                .quantityToPriceFactor(BigDecimal.ONE)
                .materialCategory("RAW")
                .sortOrder(0)
                .isOptional(false)
                .perPortion(false)
                .build();
        replacement = itemRepository.saveAndFlush(replacement);

        BomRecipe activated =
                service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(itemRepository.findById(replacement.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(saved.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(saved.getWorkflowEdgeId()).isEqualTo("edge-input");
                });
    }

    @Test
    @DisplayName("Workflow canvas exact revision is authoritative when creating the first draft")
    void exactWorkflowRevisionIsAuthoritative() {
        ProductType product = saveProduct("EXACT-REV", "盒", new BigDecimal("500"));

        BomRecipe draft =
                service.ensureDraft(product.getFactoryId(), product.getId(), 100L);

        assertThat(draft.getWorkflowRevisionId()).isEqualTo(100L);
        verify(bomWorkflowRevisionService).bindExactRevision(
                product.getFactoryId(), draft, 100L);
        verify(bomWorkflowRevisionService, org.mockito.Mockito.never())
                .autoBindUniqueDraft(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class));
    }

    @Test
    @DisplayName("explicit Workflow upgrade keeps mapped rows and appends a skeleton for a new stable input")
    void workflowUpgradeAppendsNewInputSkeleton() {
        ProductType product = saveProduct("UPGRADE-SLOT", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        String newMaterialId = "RAW-NEW-" + product.getId();
        saveRawMaterial(product.getFactoryId(), newMaterialId, "新增工艺投入");

        // 升级是否已发生 —— <b>不能</b>用 recipe.getWorkflowDefinitionVersion() 来判。
        // twoInputGraph 内部会调 workflowBinding, 而后者把 definitionVersion 写回 1;
        // upgradeWorkflowRevision 在一次调用里要 resolvePinnedGraph <b>两次</b>
        // (先 reconcileUpgradedInputSkeletons 建骨架, 再 validateFamilyContracts 校验),
        // 于是第二次拿到的是<b>升级前</b>的单投入图 —— 骨架行按双投入图刚建好, 校验却按单投入图看,
        // 报一句假的「投入槽已不存在」。真实实现按钉住的修订解析, 两次必然一致; 这里是假件不忠实。
        java.util.concurrent.atomic.AtomicBoolean upgraded =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        when(bomWorkflowRevisionService.upgradeToLatestCompatibleDraft(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    BomWorkflowRevisionService.WorkflowBinding binding = workflowBinding(recipe);
                    recipe.setWorkflowDefinitionVersion(2);
                    upgraded.set(true);
                    return binding;
                });
        when(bomWorkflowRevisionService.resolvePinnedGraph(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return upgraded.get()
                            ? twoInputGraph(recipe, newMaterialId)
                            : workflowBinding(recipe).graph();
                });

        service.upgradeWorkflowRevision(product.getFactoryId(), draft.getId());

        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .extracting(BomRecipeItem::getWorkflowEdgeId)
                .containsExactly("edge-input", "edge-extra");
    }

    @Test
    @DisplayName("Workflow regenerated identities take over existing unbound materials and preserve packaging")
    void workflowUpgradeTakesOverExistingRowsByUniqueMaterialIdentity() {
        ProductType product = saveProduct("REKEY-SLOTS", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        String materialA = "RAW-WF-" + product.getId();
        List<String> addedMaterials = List.of(
                "RAW-B-" + product.getId(),
                "RAW-C-" + product.getId(),
                "RAW-D-" + product.getId());
        for (String materialId : addedMaterials) {
            saveRawMaterial(product.getFactoryId(), materialId, materialId);
            itemRepository.saveAndFlush(BomRecipeItem.builder()
                    .recipeId(draft.getId())
                    .factoryId(product.getFactoryId())
                    .materialTypeId(materialId)
                    .materialName(materialId)
                    .unit("kg")
                    .materialCategory("RAW")
                    .sortOrder(10)
                    .isOptional(false)
                    .build());
        }
        RawMaterialType packaging = saveRawMaterial(
                product.getFactoryId(), "PKG-" + product.getId(), "外箱");
        BomRecipeItem packagingRow = itemRepository.saveAndFlush(BomRecipeItem.builder()
                .recipeId(draft.getId())
                .factoryId(product.getFactoryId())
                .materialTypeId(packaging.getId())
                .materialName(packaging.getName())
                .standardQuantity(BigDecimal.ONE)
                .unit("box")
                .materialCategory("PACKAGING")
                .sortOrder(20)
                .isOptional(false)
                .build());
        List<String> targetMaterials = new ArrayList<>();
        targetMaterials.add(materialA);
        targetMaterials.addAll(addedMaterials);

        when(bomWorkflowRevisionService.upgradeToLatestCompatibleDraft(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    BomWorkflowRevisionService.WorkflowBinding binding = workflowBinding(recipe);
                    recipe.setWorkflowDefinitionVersion(2);
                    recipe.setWorkflowRevisionId(222L);
                    recipe.setWorkflowRevisionHash("revision-222");
                    recipe.setTargetTerminalNodeId("terminal:new");
                    return binding;
                });
        when(bomWorkflowRevisionService.resolvePinnedGraph(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return Integer.valueOf(2).equals(recipe.getWorkflowDefinitionVersion())
                             ? regeneratedInputGraph(recipe, targetMaterials)
                             : workflowBinding(recipe).graph();
                });
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return List.of(new BomWorkflowRevisionService.TerminalOutput(
                            "terminal:new",
                            recipe.getProductTypeId(),
                            "process:new",
                            "output",
                            BomRecipe.OutputRole.MAIN,
                            new BigDecimal("100"),
                            recipe.getOutputUnit(),
                            BomWorkflowRevisionService.CostAllocationBasis.DECLARED));
                });

        service.upgradeWorkflowRevision(product.getFactoryId(), draft.getId());

        List<BomRecipeItem> upgraded =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId());
        assertThat(upgraded).hasSize(5);
        assertThat(upgraded.stream()
                .filter(item -> !"PACKAGING".equals(item.getMaterialCategory()))
                .map(BomRecipeItem::getMaterialTypeId).toList())
                .containsExactlyInAnyOrderElementsOf(targetMaterials);
        for (String materialId : targetMaterials) {
            assertThat(upgraded.stream()
                    .filter(item -> materialId.equals(item.getMaterialTypeId()))
                    .findFirst().orElseThrow().getWorkflowMaterialNodeId())
                    .isEqualTo("material:" + materialId);
        }
        BomRecipeItem preservedPackaging = upgraded.stream()
                .filter(item -> item.getId().equals(packagingRow.getId()))
                .findFirst().orElseThrow();
        assertThat(preservedPackaging.getMaterialCategory()).isEqualTo("PACKAGING");
        assertThat(preservedPackaging.getWorkflowMaterialNodeId()).isNull();
    }

    @Test
    @DisplayName("Workflow regenerated identities preserve canonical BOM count units against localized ports")
    void workflowUpgradeRekeysCanonicalCountUnitAgainstLocalizedPort() {
        ProductType product = saveProduct("REKEY-COUNT-UNIT", "袋", new BigDecimal("400"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem existing =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        existing.setUnit("pcs");
        existing = itemRepository.saveAndFlush(existing);
        Long existingItemId = existing.getId();
        String materialTypeId = existing.getMaterialTypeId();

        when(bomWorkflowRevisionService.upgradeToLatestCompatibleDraft(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    BomWorkflowRevisionService.WorkflowBinding binding = workflowBinding(recipe);
                    recipe.setWorkflowDefinitionVersion(2);
                    recipe.setWorkflowRevisionId(222L);
                    recipe.setWorkflowRevisionHash("revision-222");
                    recipe.setTargetTerminalNodeId("terminal:new");
                    return binding;
                });
        when(bomWorkflowRevisionService.resolvePinnedGraph(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return Integer.valueOf(2).equals(recipe.getWorkflowDefinitionVersion())
                            ? regeneratedInputGraph(recipe, List.of(materialTypeId), "只")
                            : workflowBinding(recipe).graph();
                });
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(
                anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    BomRecipe recipe = invocation.getArgument(1);
                    return List.of(new BomWorkflowRevisionService.TerminalOutput(
                            "terminal:new",
                            recipe.getProductTypeId(),
                            "process:new",
                            "output",
                            BomRecipe.OutputRole.MAIN,
                            new BigDecimal("100"),
                            recipe.getOutputUnit(),
                            BomWorkflowRevisionService.CostAllocationBasis.DECLARED));
                });

        service.upgradeWorkflowRevision(product.getFactoryId(), draft.getId());

        assertThat(itemRepository.findById(existingItemId))
                .hasValueSatisfying(upgraded -> {
                    assertThat(upgraded.getMaterialTypeId()).isEqualTo(materialTypeId);
                    assertThat(upgraded.getUnit()).isEqualTo("pcs");
                    assertThat(upgraded.getWorkflowMaterialNodeId())
                            .isEqualTo("material:" + materialTypeId);
                    assertThat(upgraded.getWorkflowInputPortId()).isEqualTo("input:0");
                    assertThat(upgraded.getWorkflowEdgeId()).isEqualTo("edge:" + materialTypeId);
                });
    }

    @Test
    @DisplayName("kg-base SKU still snapshots one base unit instead of its net weight")
    void snapshotsKgBaseSkuAsOneKgUnit() {
        ProductType product = saveStructuredProduct(
                "KG-BASE", "kg", BigDecimal.ONE, "kg");

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("1");
        assertThat(draft.getOutputUnit()).isEqualTo("kg");
        assertThat(draft.getNetContentQuantity()).isEqualByComparingTo("1");
        assertThat(draft.getNetContentUnit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("repeated ensure returns the same draft without incrementing version")
    void repeatedEnsureReusesDraft() {
        ProductType product = saveProduct("REPEAT", "盒", new BigDecimal("250"));

        BomRecipe first = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipe second = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("ensure archives a pre-active orphan draft and clones the exact current ACTIVE version")
    void ensureArchivesSupersededDraftBeforeCloningCurrentActive() {
        ProductType product = saveProduct("SUPERSEDED-DRAFT", "袋", new BigDecimal("400"));
        BomRecipe obsoleteV1 = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(obsoleteV1);
        BomRecipe candidateV2 = service.cloneRecipe(product.getFactoryId(), obsoleteV1.getId());
        BomRecipe activeV2 =
                service.activateRecipe(product.getFactoryId(), candidateV2.getId(), 1309L);

        BomRecipe editableV3 = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(editableV3.getVersion()).isEqualTo(3);
        assertThat(editableV3.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(editableV3.getWorkflowRevisionId()).isEqualTo(activeV2.getWorkflowRevisionId());
        assertThat(editableV3.getWorkflowRevisionHash()).isEqualTo(activeV2.getWorkflowRevisionHash());
        assertThat(editableV3.getNotes()).contains("v2");
        assertThat(recipeRepository.findById(obsoleteV1.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
                    assertThat(saved.getIsCurrent()).isFalse();
                });
        assertThat(recipeRepository.findById(activeV2.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
                    assertThat(saved.getIsCurrent()).isTrue();
                });
    }

    @Test
    @DisplayName("BOM-only clone inherits the exact Workflow revision and immutable snapshot")
    void bomOnlyCloneKeepsExactWorkflowSnapshotWithoutRequestingANewDraft() {
        ProductType product = saveProduct("CLONE-PIN", "盒", new BigDecimal("500"));
        BomRecipe original = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(original);
        BomRecipe active = service.activateRecipe(product.getFactoryId(), original.getId(), 1309L);
        clearInvocations(bomWorkflowRevisionService);

        BomRecipe cloned = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(cloned.getId()).isNotEqualTo(active.getId());
        assertThat(cloned.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(cloned.getWorkflowRevisionId()).isEqualTo(active.getWorkflowRevisionId());
        assertThat(cloned.getWorkflowId()).isEqualTo(active.getWorkflowId());
        assertThat(cloned.getWorkflowDefinitionVersion()).isEqualTo(active.getWorkflowDefinitionVersion());
        assertThat(cloned.getWorkflowRevisionHash()).isEqualTo(active.getWorkflowRevisionHash());
        assertThat(cloned.getWorkflowNodesSnapshotJson()).isEqualTo(active.getWorkflowNodesSnapshotJson());
        assertThat(cloned.getWorkflowEdgesSnapshotJson()).isEqualTo(active.getWorkflowEdgesSnapshotJson());
        assertThat(cloned.getTargetTerminalNodeId()).isEqualTo(active.getTargetTerminalNodeId());
        assertThat(cloned.getBomFamilyId()).isNotEqualTo(active.getBomFamilyId());
        verify(bomWorkflowRevisionService, never())
                .autoBindUniqueDraft(anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class));
        assertThat(recipeRepository.findById(active.getId()))
                .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE));
    }

    @Test
    @DisplayName("concurrent ensure calls serialize on the SKU and create one draft")
    void concurrentEnsureCreatesOneDraft() throws Exception {
        ProductType product = saveProduct("CONCURRENT", "包", new BigDecimal("180"));
        int callers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.ensureDraft(product.getFactoryId(), product.getId()).getId();
                }));
            }
            ready.await();
            start.countDown();

            Set<String> ids = new java.util.HashSet<>();
            for (Future<String> future : futures) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                    product.getFactoryId(), product.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("factory scoped lookup does not expose another factory SKU")
    void rejectsCrossFactoryProduct() {
        ProductType product = saveProduct("ISOLATION", "袋", new BigDecimal("300"));

        assertThatThrownBy(() -> service.ensureDraft("OTHER-FACTORY", product.getId()))
                .hasMessageContaining("产品不存在");
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isZero();
    }

    @Test
    @DisplayName("activation commits without replacing the orphan-removal item collection")
    void activationCommitsWithPersistedRawItems() {
        ProductType product = saveProduct("ACTIVATE", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        BomRecipeItem item = completeWorkflowSkeleton(draft);

        BomRecipe activated = service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activated.getIsCurrent()).isTrue();
        assertThat(recipeRepository.findById(draft.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
                    assertThat(saved.getIsCurrent()).isTrue();
                });
        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .extracting(BomRecipeItem::getMaterialTypeId)
                .containsExactly(item.getMaterialTypeId());
    }

    @Test
    @DisplayName("packaging item without a positive fixed quantity cannot activate")
    void activationRejectsPackagingWithoutFixedQuantity() {
        ProductType product = saveProduct("PACKAGING-GATE", "盒", new BigDecimal("800"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        RawMaterialType packaging = new RawMaterialType();
        packaging.setId("PACKAGING-BOM-GATE");
        packaging.setFactoryId(product.getFactoryId());
        packaging.setCode("PACKAGING-GATE");
        packaging.setName("成品盒");
        packaging.setCategory("PACKAGING");
        packaging.setUnit("box");
        packaging.setIsActive(true);
        packaging.setIsAbacaPackaging(false);
        packaging.setCreatedBy(1L);
        rawMaterialTypeRepository.saveAndFlush(packaging);

        BomRecipeItem workflowInput =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        workflowInput.setStandardQuantity(BigDecimal.ONE);
        workflowInput.setUnitPrice(new BigDecimal("10"));
        workflowInput.setQuantityToPriceFactor(BigDecimal.ONE);
        itemRepository.saveAndFlush(workflowInput);

        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(draft.getId());
        item.setFactoryId(product.getFactoryId());
        item.setMaterialTypeId(packaging.getId());
        item.setMaterialName(packaging.getName());
        item.setUnit("box");
        item.setMaterialCategory("PACKAGING");
        item.setYieldRate(new BigDecimal("100.00"));
        item.setSortOrder(0);
        item.setIsOptional(false);
        item.setPerPortion(false);
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        itemRepository.saveAndFlush(item);

        assertThatThrownBy(() -> service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L))
                .hasMessageContaining("成品盒")
                .hasMessageContaining("缺少有效数量");
        assertThat(recipeRepository.findById(draft.getId()))
                .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.DRAFT));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 激活闸 —— 从 BomRecipeServiceImplAddItemTest 迁来的四条。
    //
    // 那四条挂在一个**纯 Mockito** 类上, 而 activateRecipe 需要
    // bomWorkflowRevisionService / readinessService / workflowActivationRepo
    // 三个协作者, 那个类一个都没 mock ⇒ 四条全都在
    //   NullPointerException: ...BomWorkflowRevisionService.findNewerCompatibleDraft(...)
    //                        because "this.bomWorkflowRevisionService" is null
    // 上当场炸掉, **一次都没走到它们各自要断言的那道闸**。
    //
    // ⛔ 不在原地补 mock: 那要把本类这份画布夹具(workflowBinding)再抄一份进去,
    //    同一个东西两份、必漂(本仓形态 D)。本类已经有真画布 + 真 H2, 断言搬过来
    //    就跑在**产品真实入口**上, 而不是跑在一份手工模拟的图上。
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("激活新版本时每个仍挂着 ACTIVE 的旧版本都要归档并取消 current")
    void activationArchivesEveryCompetingActiveVersion() {
        ProductType product = saveProduct("COMPETING", "袋", new BigDecimal("500"));
        BomRecipe v1 = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(v1);
        BomRecipe activeV1 = service.activateRecipe(product.getFactoryId(), v1.getId(), 1309L);

        // 历史脏数据: status=ACTIVE 而 isCurrent=false —— 闸要收口的正是这一种,
        // 只清 current 标记会在库里留下两个「已生效」版本 (见 findCompetingVersionsForActivation)。
        BomRecipe stale = recipeRepository.findById(activeV1.getId()).orElseThrow();
        stale.setIsCurrent(false);
        recipeRepository.saveAndFlush(stale);

        BomRecipe v2 = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(v2);
        BomRecipe activeV2 = service.activateRecipe(product.getFactoryId(), v2.getId(), 1309L);

        assertThat(activeV2.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activeV2.getIsCurrent()).isTrue();
        assertThat(recipeRepository.findById(activeV1.getId()))
                .as("status=ACTIVE 但 isCurrent=false 的旧版本同样要被收口, 否则库里留下两个「已生效」")
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
                    assertThat(saved.getIsCurrent()).isFalse();
                });
        assertThat(recipeRepository.findByFactoryIdAndProductTypeIdOrderByVersionDesc(
                        product.getFactoryId(), product.getId()))
                .as("同一 SKU 任何时刻最多一个 ACTIVE")
                .filteredOn(saved -> saved.getStatus() == BomRecipe.Status.ACTIVE)
                .extracting(BomRecipe::getId)
                .containsExactly(activeV2.getId());
    }

    /**
     * 原断言是 {@code archivedVersionCanBeReactivated}: 直接 {@code activateRecipe(归档版)}
     * 就能把它切回生效, 且不新建版本。
     *
     * <p><b>产品已经不是这个行为了。</b>{@code activationFamily} 现在要求被激活的这一份
     * 必须是它所属 Family 的 <b>DRAFT</b> 成员, 否则 409 {@code BOM_FAMILY_DRAFT_REQUIRED}。
     * 这是对的: 联合生产下一个 Family 有多个终端产出, 单独把其中一个成员切回旧版会留下
     * 一个自相矛盾的 Family。
     *
     * <p>⚠️ 原断言之所以一直「通过」, 是因为它跑在一个 <b>{@code bomFamilyId == null}</b> 的
     * 假配方上 —— 而 {@code ensureDraft} <b>给每一份 BOM 都发 Family id</b>(本类
     * {@code bomOnlyCloneKeepsExactWorkflowSnapshot...} 就在断言两次克隆的 familyId 不同)。
     * 也就是说那个桩的形状<b>真实产品从来不会产出</b>, 断言守的是一条到不了的分支。
     *
     * <p>⇒ 抬到守性质:「回到历史版本」这件事仍然做得到, 只是路径变成
     * <b>克隆归档版 → 得到草稿 → 激活草稿</b>; 直接激活归档版必须被拒, 且那句拒绝
     * 要能让用户找到上面那条路。
     */
    @Test
    @DisplayName("归档版不能直接重新激活，要先克隆成草稿；两条路都要成立")
    void archivedVersionIsReactivatedByCloningItIntoADraft() {
        ProductType product = saveProduct("REACTIVATE", "袋", new BigDecimal("500"));
        BomRecipe v1 = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(v1);
        service.activateRecipe(product.getFactoryId(), v1.getId(), 1309L);
        BomRecipe v2 = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(v2);
        service.activateRecipe(product.getFactoryId(), v2.getId(), 1309L);
        assertThat(recipeRepository.findById(v1.getId()))
                .as("阳性对照: v1 必须真的是 ARCHIVED, 否则下面两条断言什么都没证明")
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
                    assertThat(saved.getBomFamilyId())
                            .as("⛔ 真实 BOM 一定有 Family id —— 没有 id 的桩会绕开整道闸")
                            .isNotNull();
                });

        // ① 直接激活归档版: 拒, 并且那句拒绝要指向可走的下一步
        assertThatThrownBy(() -> service.activateRecipe(product.getFactoryId(), v1.getId(), 1310L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("BOM_FAMILY_DRAFT_REQUIRED");
                    assertThat(error.getActionHint())
                            .as("actionHint 为空的 409 会被 web-admin 当乐观锁冲突整条吞掉")
                            .isNotBlank()
                            .contains("克隆");
                });
        assertThat(recipeRepository.findById(v2.getId()))
                .as("被拒之后当前生效版本一个字都不许动")
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
                    assertThat(saved.getIsCurrent()).isTrue();
                });

        // ② 克隆归档版 → 草稿 → 激活: 通
        BomRecipe restored = service.cloneRecipe(product.getFactoryId(), v1.getId());
        assertThat(restored.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        BomRecipe activated = service.activateRecipe(
                product.getFactoryId(), restored.getId(), 1310L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activated.getIsCurrent()).isTrue();
        assertThat(recipeRepository.findById(v2.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
                    assertThat(saved.getIsCurrent()).isFalse();
                });
        assertThat(recipeRepository.findByFactoryIdAndProductTypeIdOrderByVersionDesc(
                        product.getFactoryId(), product.getId()))
                .filteredOn(saved -> saved.getStatus() == BomRecipe.Status.ACTIVE)
                .extracting(BomRecipe::getId)
                .containsExactly(activated.getId());
    }

    /**
     * 原断言是 {@code activationRejectsEmptyDraft}: 空草稿 → {@code BOM_ACTIVATION_ITEMS_REQUIRED}
     * 「BOM 还没有任何原辅料或包材，不能激活」。
     *
     * <p><b>产品已经不是这个行为了, 而且不是因为「哪条报错先抛」。</b>实测(本类探针,
     * 把草稿的明细行全删干净再激活): <b>没有任何报错, 直接激活成功</b>。
     * 成因是 {@code activateRecipe} 在所有校验之前先跑
     * {@code reconcileUpgradedInputSkeletons} —— 它照画布把每个缺行的投入槽<b>补一条骨架行</b>,
     * 于是「空」这个状态在任何一道闸看见它之前就已经不存在了。
     *
     * <p>这条路是对的, 不该改回去: Canvas-is-BOM 之后<b>画布才是投入的真值</b>,
     * 明细行少一条是可修复的不一致, 不是用户填错。另一头也堵死了 —— 实测用户根本删不掉
     * 这些行: {@code deleteItem} 直接 409 {@code BOM_WORKFLOW_REQUIRED_INPUT_DELETE_FORBIDDEN}
     * 「Workflow 必需投入槽不能从 BOM 中删除」。
     *
     * <p>⇒ 断言从<b>守字面</b>(某个 errorCode)抬到<b>守性质</b>: 空草稿要按画布补齐、
     * 补出来的行必须真的绑在画布槽上, 并且 ⛔ 不许拿一个编出来的用量冒充用户意图。
     */
    @Test
    @DisplayName("空草稿按画布补齐后激活，⛔ 不是当作用户填错拒掉")
    void emptyDraftIsRebuiltFromTheCanvasInsteadOfRefused() {
        ProductType product = saveProduct("EMPTY-REBUILD", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        itemRepository.deleteAll(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()));
        itemRepository.flush();
        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .as("阳性对照: 激活之前它必须真的是空的, 否则下面「补齐」什么都没证明")
                .isEmpty();

        BomRecipe activated = service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getMaterialTypeId())
                            .as("补出来的行必须是画布上那个原料, ⛔ 不是随便找一个顶上")
                            .isEqualTo("RAW-WF-" + product.getId());
                    assertThat(row.getWorkflowMaterialNodeId()).isEqualTo("raw-input");
                    assertThat(row.getWorkflowInputPortId()).isEqualTo("input");
                    assertThat(row.getWorkflowEdgeId()).isEqualTo("edge-input");
                    assertThat(row.getStandardQuantity())
                            .as("⛔ 用量留空 —— 编一个数出来就是把「我不知道」当成用户意图往成本里传")
                            .isNull();
                });
    }

    /**
     * 「BOM 里什么都没有」这件事<b>唯一</b>还会被拒的形态: 画布上一根原料都没接。
     *
     * <p>此前这条报错<b>用户根本看不见</b> —— 它是 409 且 {@code actionHint} 为空,
     * 而 web-admin 的拦截器把「409 且 actionHint/hintTarget 双空」判成乐观锁冲突整条吞掉
     * ({@code request.ts} 的 {@code isVanillaOptimisticLock});
     * 发布链路那边 {@code isWorkflowBomSyncRace} 按 {@code BOM_WORKFLOW_} 前缀匹配后
     * 也不补 toast。两头同时静默。
     *
     * <p>所以这条测试守两件事, 缺一不可:
     * <ol>
     *   <li><b>看得见</b> —— actionHint 非空(这正是拦截器的判据本身);</li>
     *   <li><b>看得懂</b> —— 阴性对照钉住黑话不许回来。</li>
     * </ol>
     */
    @Test
    @DisplayName("画布上没接原料时，报错要说清缺什么、去哪补，而且必须带 actionHint 才看得见")
    void canvasWithoutAnyRawInputTellsTheUserWhatToDo() {
        ProductType product = saveProduct("NO-RAW-INPUT", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        completeWorkflowSkeleton(draft);
        when(bomWorkflowRevisionService.resolvePinnedGraph(
                        anyString(), org.mockito.ArgumentMatchers.any(BomRecipe.class)))
                .thenAnswer(invocation -> {
                    PinnedWorkflowGraph base = workflowBinding(invocation.getArgument(1)).graph();
                    return new PinnedWorkflowGraph(
                            base.workflowRevisionId(), base.workflowId(), base.definitionVersion(),
                            base.revisionHash(), base.targetProductTypeId(), base.terminalNodeId(),
                            List.of(), base.processes(),
                            base.nodes().stream()
                                    .filter(node -> !"RAW_MATERIAL".equals(node.getKind())).toList(),
                            base.edges().stream()
                                    .filter(edge -> !"raw-input".equals(edge.getSource())).toList());
                });

        assertThatThrownBy(() -> service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("BOM_WORKFLOW_INPUT_SLOT_REQUIRED");
                    // ① 看得见 —— 空 actionHint 会被前端拦截器当乐观锁冲突整条吞掉
                    assertThat(error.getActionHint())
                            .as("actionHint 为空 = 这条报错在 web-admin 上一个字都不显示")
                            .isNotBlank();
                    // ② 看得懂 —— 缺什么 / 去哪补
                    assertThat(error.getMessage()).contains("原料");
                    assertThat(error.getActionHint()).contains("工艺图");
                    // ③ 阴性对照: 黑话不许回来。给厂长的话里出现这些词等于没说。
                    assertThat(error.getMessage() + error.getActionHint())
                            .as("面向用户的报错不许带黑话")
                            .doesNotContain("投入槽")
                            .doesNotContain("目标 SKU 路径")
                            .doesNotContain("SKU 路径");
                });
        assertThat(recipeRepository.findById(draft.getId()))
                .as("拒绝之后草稿仍然是草稿 —— ⛔ 不许半激活")
                .hasValueSatisfying(saved ->
                        assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.DRAFT));
    }

    private ProductType saveProduct(String suffix, String unit, BigDecimal gramsPerUnit) {
        ProductType product = newProduct(suffix, unit);
        product.setGramsPerUnit(gramsPerUnit);
        ProductType saved = productTypeRepository.saveAndFlush(product);
        saveWorkflowInputMaterial(saved);
        return saved;
    }

    private ProductType saveStructuredProduct(
            String suffix,
            String unit,
            BigDecimal netContentQuantity,
            String netContentUnit) {
        ProductType product = newProduct(suffix, unit);
        product.setNetContentQuantity(netContentQuantity);
        product.setNetContentUnit(netContentUnit);
        ProductType saved = productTypeRepository.saveAndFlush(product);
        saveWorkflowInputMaterial(saved);
        return saved;
    }

    private void saveWorkflowInputMaterial(ProductType product) {
        saveRawMaterial(product.getFactoryId(), "RAW-WF-" + product.getId(), "Workflow 固定投入");
    }

    private RawMaterialType saveRawMaterial(String factoryId, String id, String name) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setCode(id);
        material.setName(name);
        material.setCategory("RAW");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setIsAbacaPackaging(false);
        material.setCreatedBy(1L);
        return rawMaterialTypeRepository.saveAndFlush(material);
    }

    private PinnedWorkflowGraph twoInputGraph(BomRecipe recipe, String extraMaterialTypeId) {
        BomWorkflowRevisionService.WorkflowBinding base = workflowBinding(recipe);
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>(base.graph().nodes());
        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("skuId", extraMaterialTypeId);
        extraData.put("name", "新增工艺投入");
        nodes.add(1, new ProductProcessWorkflowDTO.Node(
                "raw-extra", "RAW_MATERIAL",
                new ProductProcessWorkflowDTO.Position(0D, 80D), extraData));

        ProductProcessWorkflowDTO.Node process = nodes.stream()
                .filter(node -> "process".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> ports = new ArrayList<>();
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "input", "direction", "INPUT", "materialNodeId", "raw-input",
                "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 0)));
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "input-extra", "direction", "INPUT", "materialNodeId", "raw-extra",
                "materialKind", "RAW_MATERIAL", "unit", "kg", "ordinal", 1)));
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "output", "direction", "OUTPUT", "materialNodeId", "terminal",
                "materialKind", "FINISHED_GOOD", "unit", recipe.getOutputUnit(),
                "ordinal", 0, "outputRole", "MAIN",
                "costAllocationRatio", new BigDecimal("100"))));
        process.getData().put("ports", ports);

        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>(base.graph().edges());
        edges.add(1, new ProductProcessWorkflowDTO.Edge(
                "edge-extra", "raw-extra", "output", "process", "input-extra"));
        return new PinnedWorkflowGraph(
                recipe.getWorkflowRevisionId(), recipe.getWorkflowId(),
                recipe.getWorkflowDefinitionVersion(), recipe.getWorkflowRevisionHash(),
                recipe.getProductTypeId(), "terminal",
                List.of("RAW-WF-" + recipe.getProductTypeId(), extraMaterialTypeId),
                List.of(new PinnedWorkflowGraph.ProcessStep("process", "WP-TEST", 1)),
                nodes, edges);
    }

    private PinnedWorkflowGraph regeneratedInputGraph(
            BomRecipe recipe, List<String> materialTypeIds) {
        return regeneratedInputGraph(recipe, materialTypeIds, "kg");
    }

    private PinnedWorkflowGraph regeneratedInputGraph(
            BomRecipe recipe, List<String> materialTypeIds, String inputUnit) {
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        List<Map<String, Object>> ports = new ArrayList<>();
        for (int index = 0; index < materialTypeIds.size(); index++) {
            String materialTypeId = materialTypeIds.get(index);
            String materialNodeId = "material:" + materialTypeId;
            String portId = "input:" + index;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("skuId", materialTypeId);
            data.put("name", materialTypeId);
            nodes.add(new ProductProcessWorkflowDTO.Node(
                    materialNodeId, "RAW_MATERIAL",
                    new ProductProcessWorkflowDTO.Position(0D, index * 60D), data));
            ports.add(new LinkedHashMap<>(Map.of(
                    "id", portId,
                    "direction", "INPUT",
                    "materialNodeId", materialNodeId,
                    "materialKind", "RAW_MATERIAL",
                    "unit", inputUnit,
                    "ordinal", index)));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge:" + materialTypeId,
                    materialNodeId,
                    "output",
                    "process:new",
                    portId));
        }
        ports.add(new LinkedHashMap<>(Map.of(
                "id", "output",
                "direction", "OUTPUT",
                "materialNodeId", "terminal:new",
                "materialKind", "FINISHED_GOOD",
                "unit", recipe.getOutputUnit(),
                "ordinal", 0,
                "outputRole", "MAIN",
                "costAllocationRatio", new BigDecimal("100"))));
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-TEST");
        processData.put("ports", ports);
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "process:new", "PROCESS",
                new ProductProcessWorkflowDTO.Position(100D, 0D), processData));
        Map<String, Object> terminalData = new LinkedHashMap<>();
        terminalData.put("skuId", recipe.getProductTypeId());
        terminalData.put("baseUnit", recipe.getOutputUnit());
        nodes.add(new ProductProcessWorkflowDTO.Node(
                "terminal:new", "FINISHED_GOOD",
                new ProductProcessWorkflowDTO.Position(200D, 0D), terminalData));
        edges.add(new ProductProcessWorkflowDTO.Edge(
                "edge:output", "process:new", "output", "terminal:new", "input"));
        return new PinnedWorkflowGraph(
                222L,
                recipe.getWorkflowId(),
                2,
                "revision-222",
                recipe.getProductTypeId(),
                "terminal:new",
                materialTypeIds,
                List.of(new PinnedWorkflowGraph.ProcessStep("process:new", "WP-TEST", 1)),
                nodes,
                edges);
    }

    private BomRecipeItem completeWorkflowSkeleton(BomRecipe draft) {
        BomRecipeItem item = itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        item.setStandardQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("10"));
        item.setYieldRate(new BigDecimal("100.00"));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        return itemRepository.saveAndFlush(item);
    }

    private ProductType newProduct(String suffix, String unit) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        String factoryId = "F-BOM-" + suffix;
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("BOM lifecycle " + suffix);
        factoryRepository.saveAndFlush(factory);

        ProductType product = new ProductType();
        product.setId("SKU-BOM-" + suffix);
        product.setFactoryId(factoryId);
        product.setCode("SKU-" + suffix);
        product.setName("测试 SKU " + suffix);
        product.setUnit(unit);
        product.setIsActive(true);
        product.setCreatedBy(1L);
        return product;
    }

    private String canonicalUnit(String raw) {
        return switch (raw) {
            case "盒" -> "box";
            case "袋" -> "bag";
            case "包" -> "pack";
            case "瓶" -> "bottle";
            case "克" -> "g";
            case "千克" -> "kg";
            case "毫升" -> "ml";
            case "升" -> "L";
            default -> raw;
        };
    }

    /**
     * 一次 createRecipe 不许为同一个物料写出两行。
     *
     * <p><b>事故形态</b>: createRecipe 先按 Workflow 投入端口建"骨架行", 再把调用方提交的行
     * merge 进去。但骨架匹配只在两种情况下发生 —— 调用方带齐 3 个 workflow 身份字段, 或者
     * <b>一个都没带且 materialCategory 是 RAW</b>。其余一律新建一行, 而骨架行因为带 workflow
     * 身份会被保留 → <b>同一物料两行并存</b>, 骨架那行 standard_quantity 为空。
     *
     * <p>🔴 <b>根因是两套分类被当成了同一个东西</b>: Workflow 里的 {@code materialKind}
     * (RAW_MATERIAL / SEMI_FINISHED …) 说的是"这个格子里放的是什么料", BOM 里的
     * {@code materialCategory} (RAW / AUXILIARY / PACKAGING / BYPRODUCT) 说的是"这一行在
     * 配方里算哪类成本"。调料在 Workflow 里就是 RAW_MATERIAL, 在 BOM 里却该记 AUXILIARY ——
     * 拿业务分类当骨架身份判据, 于是所有非 RAW 的行全部重复。
     *
     * <p><b>用户看到的是什么</b>: 保存成功、界面正常, 直到<b>领料</b>那一步才被拦
     * 「原料未配置 BOM 用量，请先补充配方用量」—— 而他明明填过。界面上那行是好的,
     * 坏的是另一行影子记录, 他会在那儿反复填、反复失败。
     *
     * <p>prod 实测 (六膳门酱鸭腿 BOM-20260801-001): 提交 4 行落库 7 行,
     * 三个 AUXILIARY 各多一行空用量, 时间戳显示 7 行全在同一次调用的 53ms 内写完。
     */
    @Test
    @DisplayName("AUXILIARY 行也要并进 Workflow 骨架 —— 否则一次创建写出两行, 其中一行用量为空")
    void mergesNonRawCategoryRowIntoWorkflowSkeleton() {
        ProductType product = saveProduct("AUX-MERGE", "盒", new BigDecimal("500"));
        String skeletonMaterialId = "RAW-WF-" + product.getId();

        CreateBomRecipeRequest request = new CreateBomRecipeRequest();
        request.setProductTypeId(product.getId());
        BomRecipeItemDTO auxiliary = new BomRecipeItemDTO();
        auxiliary.setMaterialTypeId(skeletonMaterialId);
        auxiliary.setStandardQuantity(new BigDecimal("0.06"));
        auxiliary.setUnit("kg");
        auxiliary.setMaterialCategory("AUXILIARY");
        request.setItems(List.of(auxiliary));

        service.createRecipe(product.getFactoryId(), request);
        String recipeId = recipeRepository.findAll().stream()
                .filter(r -> product.getId().equals(r.getProductTypeId()))
                .map(BomRecipe::getId)
                .findFirst()
                .orElseThrow();

        List<BomRecipeItem> alive = itemRepository
                .findByRecipeIdOrderBySortOrderAsc(recipeId).stream()
                .filter(item -> item.getDeletedAt() == null)
                .toList();

        assertThat(alive.size())
                .as("同一物料写出两行 = 领料时会撞上用量为空的那行, 而用户界面上看到自己填过了")
                .isEqualTo(1);
        assertThat(alive.get(0).getStandardQuantity())
                .as("并进骨架后必须带上调用方填的用量")
                .isEqualByComparingTo("0.06");
        assertThat(alive.get(0).getWorkflowInputPortId())
                .as("并进的是骨架行, 所以 workflow 身份要保留")
                .isEqualTo("input");
    }

    /** 阳性对照: RAW 分类本来就能并 —— 证明上面那条红的原因是分类, 不是别的。 */
    @Test
    @DisplayName("对照: RAW 分类一直都能并进骨架 (差别只在 materialCategory)")
    void rawCategoryAlreadyMergesIntoSkeleton() {
        ProductType product = saveProduct("RAW-MERGE", "盒", new BigDecimal("500"));
        String skeletonMaterialId = "RAW-WF-" + product.getId();

        CreateBomRecipeRequest request = new CreateBomRecipeRequest();
        request.setProductTypeId(product.getId());
        BomRecipeItemDTO raw = new BomRecipeItemDTO();
        raw.setMaterialTypeId(skeletonMaterialId);
        raw.setStandardQuantity(new BigDecimal("1.25"));
        raw.setUnit("kg");
        raw.setMaterialCategory("RAW");
        request.setItems(List.of(raw));

        service.createRecipe(product.getFactoryId(), request);
        String recipeId = recipeRepository.findAll().stream()
                .filter(r -> product.getId().equals(r.getProductTypeId()))
                .map(BomRecipe::getId)
                .findFirst()
                .orElseThrow();

        List<BomRecipeItem> alive = itemRepository
                .findByRecipeIdOrderBySortOrderAsc(recipeId).stream()
                .filter(item -> item.getDeletedAt() == null)
                .toList();
        assertThat(alive.size()).isEqualTo(1);
        assertThat(alive.get(0).getStandardQuantity()).isEqualByComparingTo("1.25");
    }


    private BomRecipeItem insertLegacyDuplicate(BomRecipe draft, BomRecipeItem skeleton, String factoryId) {
        BomRecipeItem duplicate = new BomRecipeItem();
        duplicate.setRecipeId(draft.getId());
        duplicate.setFactoryId(factoryId);
        duplicate.setMaterialTypeId(skeleton.getMaterialTypeId());
        duplicate.setMaterialName(skeleton.getMaterialName());
        duplicate.setUnit(skeleton.getUnit());
        duplicate.setStandardQuantity(new BigDecimal("1.25"));
        duplicate.setMaterialCategory("AUXILIARY");
        duplicate.setSortOrder(99);
        return itemRepository.saveAndFlush(duplicate);
    }

    /**
     * 槽已被别的行占着时, 同物料的重复行必须删得掉。
     *
     * <p>删除闸的本意(见 {@code assertNotRequiredWorkflowInputByMaterial} 的 Javadoc)是
     * 「这一行还<b>唯一代表</b>着一个必需投入时不许删」, 但它从来没检查过那个槽是不是
     * <b>已经被另一行带身份地占着</b>。于是历史遗留的重复行永远删不掉 ——
     * <b>系统自己造出来的行, 它又不让你删</b>, 用户在界面上没有任何出路。
     *
     * <p>2026-08-01 六膳门实测: BOM 里三个辅料各有两行, 带端口那行用量为空卡住领料,
     * 而没端口的那行删不了, 整条生产链断在这里。
     */
    @Test
    @DisplayName("槽已被占时重复行可删 —— 否则历史脏数据永远清不掉")
    void deletesDuplicateRowWhenSlotAlreadyOccupied() {
        ProductType product = saveProduct("DUP-DEL", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();
        // 阳性对照: 骨架行确实带着完整槽身份, 否则本用例测的不是要测的东西
        assertThat(skeleton.getWorkflowInputPortId()).isNotBlank();

        Long duplicateId = insertLegacyDuplicate(draft, skeleton, product.getFactoryId()).getId();

        assertThatCode(() -> service.deleteItem(product.getFactoryId(), duplicateId))
                .as("槽已被骨架行占着, 这一行只是重复行, 删掉不会让必需投入消失")
                .doesNotThrowAnyException();

        List<BomRecipeItem> alive = itemRepository
                .findByRecipeIdOrderBySortOrderAsc(draft.getId()).stream()
                .filter(i -> i.getDeletedAt() == null)
                .toList();
        assertThat(alive.size()).isEqualTo(1);
        assertThat(alive.get(0).getWorkflowInputPortId())
                .as("留下的必须是占着槽的那行, 不能把骨架删了留重复行")
                .isNotBlank();
    }

    /** 反向断言: 槽没人占时仍然要拦 —— 那一行是该投入在 BOM 里的唯一代表, 删了投入就凭空消失。 */
    @Test
    @DisplayName("反向: 槽无人占据时仍拦住删除 —— 不能把必需投入删没")
    void stillRefusesDeleteWhenSlotIsNotOccupied() {
        ProductType product = saveProduct("SOLE-DEL", "盒", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipeItem skeleton =
                itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()).getFirst();

        // 抹掉骨架行的槽身份 —— 模拟「旧界面把生成的骨架换成了一条无绑定行」, 此时槽无人占据
        skeleton.setWorkflowMaterialNodeId(null);
        skeleton.setWorkflowInputPortId(null);
        skeleton.setWorkflowEdgeId(null);
        Long soleId = itemRepository.saveAndFlush(skeleton).getId();

        assertThatThrownBy(() -> service.deleteItem(product.getFactoryId(), soleId))
                .as("它是该必需投入在 BOM 里的唯一代表, 删了投入就没了 —— 这道闸必须继续拦")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必需投入槽");
    }

}

package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.BomSubstituteInput;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.bom.SeasoningBindingUpdateRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.impl.BomSeasoningWorkspaceServiceImpl;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BomSeasoningWorkspaceServiceTest {
    private static final String FACTORY = "F006";
    private static final String RECIPE = "recipe-1";

    @Mock BomRecipeRepository recipeRepository;
    @Mock BomSeasoningItemRepository seasoningItemRepository;
    @Mock ProductWorkProcessRepository productWorkProcessRepository;
    @Mock WorkProcessRepository workProcessRepository;
    @Mock RawMaterialTypeRepository materialTypeRepository;
    @Mock ProductWorkflowResolutionService workflowResolutionService;
    @Mock BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock BomItemSubstituteService substituteService;
    @Mock ProductProcessWorkflowRevisionRepository workflowRevisionRepository;
    @Mock BomRecipeService bomRecipeService;
    @InjectMocks BomSeasoningWorkspaceServiceImpl service;

    @Test
    void workspaceKeepsEmptyProcessesAndDeduplicatesMaterialWithoutSummingDosage() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p2", "p3");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "滚揉"), workProcess("p2", "焯水"), workProcess("p3", "熟制")));
        BomSeasoningItem first = binding(1L, "node-p1", "p1", "salt", "5.0000");
        BomSeasoningItem second = binding(2L, "node-p2", "p2", "salt", "1.5000");
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of(first, second));
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of(material("salt")));

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertTrue(response.isEditable());
        assertEquals(3L, response.getSeasoningRevision());
        assertEquals(101L, response.getWorkflowRevisionId());
        assertEquals(41L, response.getWorkflowId());
        assertEquals(1, response.getWorkflowDefinitionVersion());
        assertEquals("hash-101", response.getWorkflowRevisionHash());
        assertEquals("DRAFT", response.getWorkflowRevisionStatus());
        assertEquals(LocalDateTime.of(2026, 7, 20, 12, 0), response.getWorkflowRevisionSavedAt());
        assertEquals(1, response.getWorkflowRootCount());
        assertEquals(3, response.getWorkflowProcessCount());
        assertEquals(1, response.getWorkflowTargetCount());
        assertEquals("product-1", response.getWorkflowTargetProductTypeId());
        assertEquals(3, response.getProcesses().size());
        assertEquals(BigDecimal.ONE, response.getProcesses().get(0).getStandardBasisQuantity());
        assertEquals("kg", response.getProcesses().get(0).getStandardBasisUnit());
        assertTrue(response.getProcesses().get(0).isStandardUsageSupported());
        assertTrue(response.getProcesses().get(2).getBindings().isEmpty());
        assertEquals(1, response.getMaterialSummaries().size());
        assertEquals(2, response.getMaterialSummaries().get(0).getProcessUsages().size());
        assertEquals(new BigDecimal("5.0000"),
                response.getMaterialSummaries().get(0).getProcessUsages().get(0).getDosagePerKgG());
        assertEquals(new BigDecimal("1.5000"),
                response.getMaterialSummaries().get(0).getProcessUsages().get(1).getDosagePerKgG());
    }

    @Test
    void coProductWorkspaceCombinesSharedAndOwnExclusiveBindingsWithoutLeakingSiblingBranch() {
        BomRecipe child = recipe(BomRecipe.Status.DRAFT, 2L);
        child.setId("CHILD");
        child.setSharedRecipeId("MAIN");
        child.setBomFamilyId("FAMILY-1");
        child.setTargetTerminalNodeId("finished");
        child.setOutputRole(BomRecipe.OutputRole.CO_PRODUCT);
        BomRecipe main = recipe(BomRecipe.Status.DRAFT, 2L);
        main.setId("MAIN");
        main.setProductTypeId("product-main");
        main.setSharedRecipeId("MAIN");
        main.setBomFamilyId("FAMILY-1");
        main.setTargetTerminalNodeId("term-main");
        main.setOutputRole(BomRecipe.OutputRole.MAIN);
        BomSeasoningItem shared = binding(1L, "node-p1", "p1", "salt", "5");
        shared.setRecipeId("MAIN");
        shared.setCostScope("SHARED");
        BomSeasoningItem siblingExclusive = binding(2L, "node-p2", "p2", "salt", "9");
        siblingExclusive.setRecipeId("MAIN");
        siblingExclusive.setCostScope("OUTPUT_EXCLUSIVE");
        BomSeasoningItem childExclusive = binding(3L, "node-p2", "p2", "pepper", "2");
        childExclusive.setRecipeId("CHILD");
        childExclusive.setCostScope("OUTPUT_EXCLUSIVE");
        when(recipeRepository.findById("CHILD")).thenReturn(Optional.of(child));
        when(recipeRepository.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                FACTORY, "FAMILY-1", BomRecipe.Status.DRAFT)).thenReturn(List.of(main, child));
        pin(child, "p1", "p2");
        when(bomWorkflowRevisionService.resolveProcessCostProfiles(FACTORY, child))
                .thenReturn(Map.of(
                        "node-p1", new BomWorkflowRevisionService.CostScopeProfile(
                                "SHARED",
                                List.of("term-main", "finished"),
                                List.of("product-main", "product-1")),
                        "node-p2", new BomWorkflowRevisionService.CostScopeProfile(
                                "OUTPUT_EXCLUSIVE",
                                List.of("finished"),
                                List.of("product-1"))));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc("MAIN"))
                .thenReturn(List.of(shared, siblingExclusive));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc("CHILD"))
                .thenReturn(List.of(childExclusive));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "共享处理"), workProcess("p2", "联产品包装")));
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of(material("salt"), material("pepper")));

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, "CHILD");

        assertTrue(response.isEditable());
        assertEquals(List.of(1L), response.getProcesses().get(0).getBindings().stream()
                .map(BomSeasoningItem::getId).toList());
        assertTrue(response.getProcesses().get(0).isEditable());
        assertEquals(List.of("product-main", "product-1"),
                response.getProcesses().get(0).getCostTargetProductTypeIds());
        assertEquals(List.of(3L), response.getProcesses().get(1).getBindings().stream()
                .map(BomSeasoningItem::getId).toList());
        assertTrue(response.getProcesses().get(1).isEditable());
    }

    @Test
    void workspaceUsesPinnedDraftRevisionBeforeRuntimeAndLegacyPaths() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p3");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "前处理"), workProcess("p3", "熟制")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertEquals(List.of("p1", "p3"), response.getProcesses().stream()
                .map(BomSeasoningWorkspaceResponse.ProcessView::getWorkProcessId).toList());
        verify(productWorkProcessRepository, never())
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(anyString(), anyString());
        verifyNoInteractions(workflowResolutionService);
    }

    @Test
    void workspaceKeepsActiveBomOnItsPinnedRevisionWhenCurrentWorkflowHasMoved() {
        BomRecipe recipe = recipe(BomRecipe.Status.ACTIVE, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "pinned-v1");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("pinned-v1", "历史固定工序")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());
        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertEquals(List.of("pinned-v1"), response.getProcesses().stream()
                .map(BomSeasoningWorkspaceResponse.ProcessView::getWorkProcessId).toList());
        verifyNoInteractions(workflowResolutionService);
    }

    @Test
    void createAllowsSameMaterialInAnotherProcessAndUsesAuthoritativeSnapshot() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1", "p2");
        RawMaterialType material = material("salt");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p2", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p2"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(inv -> {
            BomSeasoningItem saved = inv.getArgument(0);
            saved.setId(22L);
            return saved;
        });
        SeasoningBindingCreateRequest request = createRequest(4L, "node-p2");

        var response = service.createBinding(FACTORY, RECIPE, "p2", request);

        assertEquals(5L, response.getSeasoningRevision());
        ArgumentCaptor<BomSeasoningItem> captor = ArgumentCaptor.forClass(BomSeasoningItem.class);
        verify(seasoningItemRepository).save(captor.capture());
        assertEquals("档案食盐", captor.getValue().getName());
        assertEquals(new BigDecimal("2.3000"), captor.getValue().getPriceSource1());
        assertNull(captor.getValue().getPriceSource2());
        assertEquals("p2", captor.getValue().getWorkProcessId());
        assertEquals("node-p2", captor.getValue().getWorkflowProcessNodeId());
        verify(substituteService).replaceForSeasoningItem(FACTORY, RECIPE, 22L, List.of());
        verify(bomRecipeService).calculateCost(FACTORY, RECIPE);
    }

    @Test
    void createFallsBackToUntaxedPurchaseReferenceWithoutPretendingItIsMovingAverage() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        RawMaterialType material = material("salt");
        material.setMovingAvgPrice(null);
        material.setUnitPrice(new BigDecimal("17.7000"));
        material.setTaxIncludedUnitPrice(new BigDecimal("20.0000"));
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p1"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(invocation -> {
            BomSeasoningItem saved = invocation.getArgument(0);
            saved.setId(24L);
            return saved;
        });

        service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1"));

        ArgumentCaptor<BomSeasoningItem> captor = ArgumentCaptor.forClass(BomSeasoningItem.class);
        verify(seasoningItemRepository).save(captor.capture());
        assertNull(captor.getValue().getPriceSource1());
        assertEquals(new BigDecimal("17.7000"), captor.getValue().getPriceSource2());
    }

    @Test
    void createPrefersPositiveMovingAverageWhenBothPricesExist() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        RawMaterialType material = material("salt");
        material.setUnitPrice(new BigDecimal("17.7000"));
        material.setTaxIncludedUnitPrice(new BigDecimal("20.0000"));
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p1"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1"));

        ArgumentCaptor<BomSeasoningItem> captor = ArgumentCaptor.forClass(BomSeasoningItem.class);
        verify(seasoningItemRepository).save(captor.capture());
        assertEquals(new BigDecimal("2.3000"), captor.getValue().getPriceSource1());
        assertNull(captor.getValue().getPriceSource2());
    }

    @Test
    void createRejectsNullZeroOrNegativePricesBeforeClaimingRevision() {
        for (BigDecimal movingAverage : List.of(BigDecimal.ZERO, new BigDecimal("-1"))) {
            reset(materialTypeRepository, seasoningItemRepository, recipeRepository,
                    bomWorkflowRevisionService, substituteService);
            BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
            when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
            pin(recipe, "p1");
            RawMaterialType material = material("salt");
            material.setMovingAvgPrice(movingAverage);
            material.setUnitPrice(BigDecimal.ZERO);
            material.setTaxIncludedUnitPrice(BigDecimal.ZERO);
            when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1")));

            assertEquals("SEASONING_PRICE_REQUIRED", exception.getErrorCode());
            verify(recipeRepository, never()).claimSeasoningRevision(anyString(), anyString(), anyLong());
            verify(seasoningItemRepository, never()).save(any());
        }
    }

    @Test
    void workspaceResolvesChineseKgFromPinnedOutgoingSemiFinishedMaterialNode() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithLinkedOutput(recipe, "p1", "公斤", "SEMI_FINISHED");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "原料处理")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        var process = response.getProcesses().get(0);
        assertEquals(BigDecimal.ONE, process.getStandardBasisQuantity());
        assertEquals("kg", process.getStandardBasisUnit());
        assertEquals("SEMI_FINISHED", process.getStandardBasisMaterialKind());
        assertTrue(process.isStandardUsageSupported());
    }

    @Test
    void workflowOutputPortUnitOverridesStaleProcessAndAdjacentMaterialUnits() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithAuthoritativeOutputPort(recipe, "p1");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "定量包装")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        var process = response.getProcesses().get(0);
        // 2026-08-14 定案 (UnitContractServiceImpl#systemUnits): 计数/包装单位的规范码
        // 就是中文字本身, 英文单词 (box/bag/pcs...) 降级为别名。归一之后一律得到中文码。
        // 期望值曾是 "box" (英文码年代的规范表), 现在的规范码是 "盒"。
        assertEquals("盒", process.getStandardBasisUnit());
        assertTrue(process.isStandardUsageSupported());
    }

    @Test
    void customSkuOutputUnitIsAValidOpaquePerOutputBasis() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithLinkedOutput(recipe, "p1", "袋", "SEMI_FINISHED");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "装袋")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        var process = response.getProcesses().getFirst();
        assertEquals(BigDecimal.ONE, process.getStandardBasisQuantity());
        // 2026-07-31: 期望值从「袋」改为规范码 "bag"。
        //
        // 旧期望**编码的是缺陷本身**: 当时这里用一张只有 7 组的私有别名表, 「盒」在表里 → 归一成
        // "box" (上一个用例断言的正是 "box"), 而「袋」不在表里 → 原样留着。同一个字段一会儿是
        // 英文码一会儿是中文, 取决于那张表恰好收没收 —— 而这正是客户 2026-07-31 撞到的那类错。
        // 改成走权威表后全部是规范码, 与上一个用例的 "box" 一致。
        //
        // 2026-08-14 定案 (第二次迁移, UnitContractServiceImpl#systemUnits): 计数/包装单位
        // 的规范码改成中文字本身 (见该方法注释: 英文码让 件/个/只、框/筐 这类"一个码对应多个
        // 中文单位"的场景只能靠 storageUnit 规则打补丁保字面)。"bag" 因此降级为别名,
        // 归一后的规范码是「袋」——与本用例的输入字面重合只是巧合 (输入恰好就是中文)。
        //
        // ⚠️ 不是把中文单位换成英文给用户看: 前端 businessUnitLabel → displayUnit →
        // UNIT_LABELS['袋'] = 「袋」, 界面显示不变。这个字段同时还喂端口单位冲突检测,
        // 那里必须是规范码, 否则「袋」和「bag」会被数成两种单位。
        assertEquals("袋", process.getStandardBasisUnit());
        assertTrue(process.isStandardUsageSupported());
    }

    @Test
    void workspaceFailsClosedWhenPinnedOutputUnitsConflict() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithConflictingOutputs(recipe, "p1");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "原料处理")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        assertFalse(response.getProcesses().get(0).isStandardUsageSupported());
        assertNull(response.getProcesses().get(0).getStandardBasisUnit());
    }

    @Test
    void createRejectsUnsupportedPinnedOutputBasisBeforeClaimingRevision() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithConflictingOutputs(recipe, "p1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(3L, "node-p1")));

        assertEquals(400, exception.getCode());
        assertEquals("SEASONING_STANDARD_BASIS_UNSUPPORTED", exception.getErrorCode());
        verify(recipeRepository, never()).claimSeasoningRevision(anyString(), anyString(), anyLong());
        verify(seasoningItemRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnsupportedPinnedOutputBasisBeforeClaimingRevision() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        BomSeasoningItem existing = binding(11L, "node-p1", "p1", "salt", "5");
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        when(seasoningItemRepository.findByIdAndRecipeId(11L, RECIPE)).thenReturn(Optional.of(existing));
        pinWithConflictingOutputs(recipe, "p1");
        SeasoningBindingUpdateRequest request = new SeasoningBindingUpdateRequest();
        request.setExpectedRevision(3L);
        request.setMaterialTypeId("salt");
        request.setDosagePerKgG(new BigDecimal("12"));
        request.setSubsequentPotRatio(new BigDecimal("0.5"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateBinding(FACTORY, RECIPE, 11L, request));

        assertEquals(400, exception.getCode());
        assertEquals("SEASONING_STANDARD_BASIS_UNSUPPORTED", exception.getErrorCode());
        verify(recipeRepository, never()).claimSeasoningRevision(anyString(), anyString(), anyLong());
        verify(seasoningItemRepository, never()).save(any());
    }

    @Test
    void createPersistsSeasoningAndItsNodeScopedSubstitutesAsOneServiceOperation() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p2");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material("salt")));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p2", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p2"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(inv -> {
            BomSeasoningItem saved = inv.getArgument(0);
            saved.setId(23L);
            return saved;
        });
        BomSubstituteInput substitute = new BomSubstituteInput();
        substitute.setMaterialTypeId("pepper");
        substitute.setConversionFactor(new BigDecimal("1.25"));
        SeasoningBindingCreateRequest request = createRequest(4L, "node-p2");
        request.setSubstitutes(List.of(substitute));

        service.createBinding(FACTORY, RECIPE, "p2", request);

        verify(substituteService).replaceForSeasoningItem(
                FACTORY, RECIPE, 23L, request.getSubstitutes());
    }

    @Test
    void duplicateInSameProcessReturnsConflictWithExistingBinding() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material("salt")));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.of(
                        binding(9L, "node-p1", "p1", "salt", "5")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("bindingId=9"));
        verify(recipeRepository, never()).claimSeasoningRevision(anyString(), anyString(), anyLong());
    }

    @Test
    void staleRevisionReturnsReloadConflictAndDoesNotWrite() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material("salt")));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 3L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBinding(FACTORY, RECIPE, "p1", createRequest(3L, "node-p1")));

        assertEquals(409, ex.getCode());
        assertEquals("SEASONING_REVISION_CONFLICT", ex.getErrorCode());
        verify(seasoningItemRepository, never()).save(any());
    }

    // ==================== 物料大类闸 (2026-08-28) ====================

    /**
     * 只改物料的类目, 其余路径全部走真实实现 —— 断言跑在产品真实入口
     * {@code createBinding} 上, 不是直接戳 validateMaterial。
     */
    private RawMaterialType materialOfCategory(String category) {
        RawMaterialType material = material("salt");
        material.setPrimaryCode(null);   // 把 "003" 那条旁路让开, 否则大类判据量不到
        material.setCategory(category);  // setCategory 顺带推出 mainCategory
        return material;
    }

    private BusinessException bindWithCategory(String category) {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        when(materialTypeRepository.findById("salt"))
                .thenReturn(Optional.of(materialOfCategory(category)));
        // lenient: 拒绝路径上这四个桩根本不会被用到 —— 而那正是「大类闸在 save 之前就拦住了」
        // 的侧面证据 (第一版写成 when(...) 时 Mockito 就是用 UnnecessaryStubbing 报出来的)。
        lenient().when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        lenient().when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p1"))
                .thenReturn(List.of());
        lenient().when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        lenient().when(seasoningItemRepository.save(any())).thenAnswer(inv -> {
            BomSeasoningItem saved = inv.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        try {
            service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1"));
            return null;   // 放行
        } catch (BusinessException e) {
            return e;      // 拒绝
        }
    }

    @Test
    void additiveCategoryCanBeBoundBecauseItsMainCategoryIsAuxiliary() {
        // 六膳门 9 个「添加剂」物料 (保水剂T22 / 复合磷酸盐 / 小苏打 / 无磷保水剂 …)
        // 以前全部被「只能选辅料或调料物料」挡住 —— 而它们是肉制品核心辅料。
        reset(materialTypeRepository, seasoningItemRepository, recipeRepository,
                bomWorkflowRevisionService, substituteService);
        assertNull(bindWithCategory("添加剂"), "category=添加剂 应该能绑成调料");
        verify(seasoningItemRepository).save(any());
    }

    @Test
    void auxiliarySubCategoriesAreAllAcceptedThroughTheMainCategory() {
        // 辅料大类下的每个小类都该放行 —— 以后再加小类不用改代码。
        for (String category : List.of("辅料", "调料", "调味料", "添加剂")) {
            reset(materialTypeRepository, seasoningItemRepository, recipeRepository,
                    bomWorkflowRevisionService, substituteService);
            assertNull(bindWithCategory(category), "category=" + category + " 应该放行");
        }
    }

    @Test
    void packagingAndRawCategoriesAreStillRejected() {
        // 阴性对照: 不是「什么都放行」。包材 = 包材大类; 主材/蔬菜/肉类 = 原料大类。
        for (String category : List.of("包材", "主材", "原料", "蔬菜", "肉类")) {
            reset(materialTypeRepository, seasoningItemRepository, recipeRepository,
                    bomWorkflowRevisionService, substituteService);
            BusinessException ex = bindWithCategory(category);
            assertNotNull(ex, "category=" + category + " 应该被拒");
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("只能选择辅料物料"), ex.getMessage());
            verify(seasoningItemRepository, never()).save(any());
        }
    }

    @Test
    void primaryCode003BypassIsUntouched() {
        // 这条旁路本次不动: 料号前缀 003 认辅料, 与大类是两条独立判据。
        reset(materialTypeRepository, seasoningItemRepository, recipeRepository,
                bomWorkflowRevisionService, substituteService);
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 4L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pin(recipe, "p1");
        RawMaterialType material = materialOfCategory("包材");   // 大类是包材, 本该被拒
        material.setPrimaryCode("003");                          // 但料号前缀说它是辅料
        when(materialTypeRepository.findById("salt")).thenReturn(Optional.of(material));
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                RECIPE, "node-p1", "salt")).thenReturn(Optional.empty());
        when(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(RECIPE, "node-p1"))
                .thenReturn(List.of());
        when(recipeRepository.claimSeasoningRevision(RECIPE, FACTORY, 4L)).thenReturn(1);
        when(seasoningItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createBinding(FACTORY, RECIPE, "p1", createRequest(4L, "node-p1"));

        verify(seasoningItemRepository).save(any());
    }

    private BomRecipe recipe(BomRecipe.Status status, long revision) {
        return BomRecipe.builder().id(RECIPE).factoryId(FACTORY).recipeCode("BOM-1")
                .productTypeId("product-1").productName("产品").outputQuantityPerUnit(BigDecimal.ONE)
                .status(status).seasoningRevision(revision)
                .workflowRevisionId(101L).workflowRevisionHash("hash-101").build();
    }

    private ProductWorkProcess process(String id, int order) {
        return ProductWorkProcess.builder().factoryId(FACTORY).productTypeId("product-1")
                .workProcessId(id).processOrder(order).build();
    }

    private WorkProcess workProcess(String id, String name) {
        WorkProcess process = new WorkProcess();
        process.setId(id);
        process.setFactoryId(FACTORY);
        process.setProcessName(name);
        return process;
    }

    private RawMaterialType material(String id) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(FACTORY);
        material.setCode("003001");
        material.setName("档案食盐");
        material.setCategory("辅料");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setMovingAvgPrice(new BigDecimal("2.3000"));
        return material;
    }

    private BomSeasoningItem binding(Long id, String node, String process, String material, String dosage) {
        BomSeasoningItem binding = new BomSeasoningItem();
        binding.setId(id);
        binding.setRecipeId(RECIPE);
        binding.setFactoryId(FACTORY);
        binding.setWorkProcessId(process);
        binding.setWorkflowProcessNodeId(node);
        binding.setMaterialTypeId(material);
        binding.setSection("COOKING");
        binding.setSeq(0);
        binding.setName("档案食盐");
        binding.setDosagePerKgG(new BigDecimal(dosage));
        binding.setPriceSource1(new BigDecimal("2.3000"));
        binding.setCountInSeasoning(true);
        return binding;
    }

    private SeasoningBindingCreateRequest createRequest(long revision, String processNodeId) {
        SeasoningBindingCreateRequest request = new SeasoningBindingCreateRequest();
        request.setExpectedRevision(revision);
        request.setMaterialTypeId("salt");
        request.setWorkflowProcessNodeId(processNodeId);
        request.setDosagePerKgG(new BigDecimal("5.0000"));
        request.setSubsequentPotRatio(new BigDecimal("0.5000"));
        return request;
    }

    private void pin(BomRecipe recipe, String... processIds) {
        List<PinnedWorkflowGraph.ProcessStep> steps = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        for (int i = 0; i < processIds.length; i++) {
            steps.add(new PinnedWorkflowGraph.ProcessStep("node-" + processIds[i], processIds[i], i + 1));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("workProcessId", processIds[i]);
            data.put("outputUnit", "kg");
            data.put("ports", List.of(Map.of("direction", "OUTPUT", "unit", "kg")));
            nodes.add(new ProductProcessWorkflowDTO.Node(
                    "node-" + processIds[i], "PROCESS",
                    new ProductProcessWorkflowDTO.Position((double) i, 0D), data));
        }
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"), steps, nodes, List.of()));
        lenient().when(bomWorkflowRevisionService.resolveProcessCostProfiles(FACTORY, recipe))
                .thenReturn(java.util.Arrays.stream(processIds).collect(java.util.stream.Collectors.toMap(
                        processId -> "node-" + processId,
                        processId -> new BomWorkflowRevisionService.CostScopeProfile(
                                "SHARED", List.of("finished"), List.of("product-1")))));
        lenient().when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs(FACTORY, recipe)).thenReturn(List.of(
                new BomWorkflowRevisionService.TerminalOutput(
                        "finished",
                        "product-1",
                        "node-" + processIds[processIds.length - 1],
                        "output",
                        BomRecipe.OutputRole.MAIN,
                        new BigDecimal("100"),
                        "kg",
                        BomWorkflowRevisionService.CostAllocationBasis.DECLARED)));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of(
                WorkflowRevisionCandidateDTO.builder()
                        .revisionId(101L)
                        .workflowId(41L)
                        .definitionVersion(1)
                        .revisionHash("hash-101")
                        .status("DRAFT")
                        .savedAt(LocalDateTime.of(2026, 7, 20, 12, 0))
                        .processCount(processIds.length)
                        .compatible(true)
                        .build()));
    }

    /**
     * 🔴 2026-08-09 真机: 一道工序加了副产之后, 它的辅料就再也改不了了。
     *
     * <p>副产的单位与主产出天然可以不同(真机: 成品 box、副产肥油 kg)。若副产也算进
     * outputUnits, size 就 != 1 → 整道工序判「缺少可用的产出基准」→ 辅料弹窗
     * 「当前不能保存」(而按钮不禁用, 点了没反应)。即: 加一个副产 = 永久丧失辅料可配置性,
     * 而这两件事在业务上毫无关系。基准回答的是「每产出多少主/联产配多少辅料」, 副产不参与。
     */
    @Test
    void byproductOutputDoesNotDestroyTheSeasoningBasis() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithByproductAlongsideFinished(recipe, "p1");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "分切")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        var process = response.getProcesses().get(0);
        assertTrue(process.isStandardUsageSupported(), "副产不该让整道工序失去辅料基准");
        // 输入 port/baseUnit 字面是英文别名 "box" (模拟存量/历史数据), 归一后的规范码
        // 是「盒」(2026-08-14 定案, 见上一个用例的详细注释)。
        assertEquals("盒", process.getStandardBasisUnit(), "基准应取主产出单位, 不受副产 kg 影响");
    }

    /**
     * 🔴 复现 prod: 副产节点**不在目标切片里**。
     *
     * <p>PinnedWorkflowGraph 是按目标产品切出来的子图(ancestors/terminals), 兄弟终端(副产、
     * 联产)通常不在 graph.nodes() 里。于是「按 materialNodeId 去 nodesById 查 isByproduct」
     * 查不到 → 判不出是副产 → 它的 kg 照样混进 outputUnits → size!=1 → 整道工序仍然
     * 「标准用量不可用」。真机实证: 修订 272 端口数据完全正常, 但 workspace 返回
     * basisUnit=null / supported=false。
     *
     * <p>判据: 端口的 materialNode **不在切片里**就不参与本目标的基准 —— 不能依赖节点存在。
     */
    @Test
    void byproductPortOutsideTheTargetSliceStillMustNotBreakTheBasis() {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, 3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));
        pinWithByproductPortButNodeOutsideSlice(recipe, "p1");
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(
                workProcess("p1", "分切")));
        when(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of());
        when(materialTypeRepository.findAllById(any())).thenReturn(List.of());

        BomSeasoningWorkspaceResponse response = service.getWorkspace(FACTORY, RECIPE);

        var process = response.getProcesses().get(0);
        assertTrue(process.isStandardUsageSupported(),
                "副产节点不在切片里时也不该毁掉基准(prod 就是这个形态)");
        // 同上: 输入是英文别名 "box", 规范码是「盒」。
        assertEquals("盒", process.getStandardBasisUnit());
    }

    /** 端口指向副产, 但副产节点**不在** graph.nodes() 里 —— 与 prod 的目标切片一致。 */
    private void pinWithByproductPortButNodeOutsideSlice(BomRecipe recipe, String processId) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", processId);
        processData.put("ports", List.of(
                Map.of("direction", "OUTPUT", "unit", "box",
                        "materialNodeId", "fg-1", "materialKind", "FINISHED_GOOD"),
                Map.of("direction", "OUTPUT", "unit", "kg",
                        "materialNodeId", "byp-outside", "materialKind", "SEMI_FINISHED")));
        Map<String, Object> fgData = new LinkedHashMap<>();
        fgData.put("baseUnit", "box");
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                new ProductProcessWorkflowDTO.Node("node-" + processId, "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node("fg-1", "FINISHED_GOOD",
                        new ProductProcessWorkflowDTO.Position(1D, 0D), fgData));
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge("e-fg", "node-" + processId, "output", "fg-1", "input"));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"),
                        List.of(new PinnedWorkflowGraph.ProcessStep("node-" + processId, processId, 1)),
                        nodes, edges));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of());
    }

    /** 一道工序: 主产出 box + 副产 kg —— 与画布「+ 副产」建出来的形状一致。 */
    private void pinWithByproductAlongsideFinished(BomRecipe recipe, String processId) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", processId);
        processData.put("ports", List.of(
                Map.of("direction", "OUTPUT", "unit", "box",
                        "materialNodeId", "fg-1", "materialKind", "FINISHED_GOOD"),
                Map.of("direction", "OUTPUT", "unit", "kg",
                        "materialNodeId", "byp-1", "materialKind", "SEMI_FINISHED")));
        Map<String, Object> fgData = new LinkedHashMap<>();
        fgData.put("baseUnit", "box");
        Map<String, Object> bypData = new LinkedHashMap<>();
        bypData.put("baseUnit", "kg");
        bypData.put("isByproduct", Boolean.TRUE);
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                new ProductProcessWorkflowDTO.Node("node-" + processId, "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node("fg-1", "FINISHED_GOOD",
                        new ProductProcessWorkflowDTO.Position(1D, 0D), fgData),
                new ProductProcessWorkflowDTO.Node("byp-1", "SEMI_FINISHED",
                        new ProductProcessWorkflowDTO.Position(1D, 1D), bypData));
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge("e-fg", "node-" + processId, "output", "fg-1", "input"),
                new ProductProcessWorkflowDTO.Edge("e-byp", "node-" + processId, "output2", "byp-1", "input"));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"),
                        List.of(new PinnedWorkflowGraph.ProcessStep("node-" + processId, processId, 1)),
                        nodes, edges));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of());
    }

    private void pinWithLinkedOutput(BomRecipe recipe, String processId, String baseUnit, String materialKind) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", processId);
        processData.put("ports", List.of(Map.of(
                "direction", "OUTPUT",
                "materialNodeId", "semi-1",
                "materialKind", materialKind)));
        Map<String, Object> materialData = new LinkedHashMap<>();
        materialData.put("baseUnit", baseUnit);
        materialData.put("materialKind", materialKind);
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                new ProductProcessWorkflowDTO.Node("node-" + processId, "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node("semi-1", materialKind,
                        new ProductProcessWorkflowDTO.Position(1D, 0D), materialData));
        List<ProductProcessWorkflowDTO.Edge> edges = List.of(
                new ProductProcessWorkflowDTO.Edge(
                        "edge-1", "node-" + processId, "output", "semi-1", "input"));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"),
                        List.of(new PinnedWorkflowGraph.ProcessStep("node-" + processId, processId, 1)),
                        nodes, edges));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of());
    }

    private void pinWithConflictingOutputs(BomRecipe recipe, String processId) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", processId);
        processData.put("ports", List.of(
                Map.of("direction", "OUTPUT", "materialNodeId", "semi-kg"),
                Map.of("direction", "OUTPUT", "materialNodeId", "semi-g")));
        Map<String, Object> kgData = Map.of("baseUnit", "kg", "materialKind", "SEMI_FINISHED");
        Map<String, Object> gData = Map.of("baseUnit", "g", "materialKind", "SEMI_FINISHED");
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                new ProductProcessWorkflowDTO.Node("node-" + processId, "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node("semi-kg", "SEMI_FINISHED",
                        new ProductProcessWorkflowDTO.Position(1D, 0D), kgData),
                new ProductProcessWorkflowDTO.Node("semi-g", "SEMI_FINISHED",
                        new ProductProcessWorkflowDTO.Position(1D, 1D), gData));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"),
                        List.of(new PinnedWorkflowGraph.ProcessStep("node-" + processId, processId, 1)),
                        nodes, List.of()));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE)).thenReturn(List.of());
    }

    private void pinWithAuthoritativeOutputPort(BomRecipe recipe, String processId) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", processId);
        processData.put("outputUnit", "kg");
        processData.put("ports", List.of(Map.of(
                "direction", "OUTPUT",
                "unit", "box",
                "materialNodeId", "finished-1")));
        Map<String, Object> materialData = Map.of(
                "baseUnit", "kg",
                "materialKind", "FINISHED_PRODUCT");
        List<ProductProcessWorkflowDTO.Node> nodes = List.of(
                new ProductProcessWorkflowDTO.Node("node-" + processId, "PROCESS",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), processData),
                new ProductProcessWorkflowDTO.Node("finished-1", "FINISHED_PRODUCT",
                        new ProductProcessWorkflowDTO.Position(1D, 0D), materialData));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(
                new PinnedWorkflowGraph(101L, 41L, 1, "hash-101", "product-1", "finished",
                        List.of("raw-1"),
                        List.of(new PinnedWorkflowGraph.ProcessStep(
                                "node-" + processId, processId, 1)),
                        nodes,
                        List.of(new ProductProcessWorkflowDTO.Edge(
                                "edge-port-authority",
                                "node-" + processId,
                                "output",
                                "finished-1",
                                "input"))));
        lenient().when(bomWorkflowRevisionService.listCompatible(FACTORY, RECIPE))
                .thenReturn(List.of());
    }
}

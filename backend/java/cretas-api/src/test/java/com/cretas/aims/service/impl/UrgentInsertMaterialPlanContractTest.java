package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.scheduling.AffectedPlanDTO;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.InsertSlotPreview;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.Assessment;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.Status;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 17 第 1、3 项: 原料按生效配方版本 + 库存 + 在途评估, 缺什么缺多少说清; 预览之后配方 / 库存 /
 * 被挤计划任何一项变了, 确认必须被拒并且什么都不写。
 *
 * <p>评估器是真实实现, 只把 BOM 服务、配方仓库、在途查询换成桩; 确认走真实的
 * {@link UrgentInsertServiceImpl#confirmInsert}。
 */
class UrgentInsertMaterialPlanContractTest {

    private static final String F = "F-MAT-PLAN";
    private static final String PT = "PT-1";
    private final LocalDateTime start = LocalDateTime.of(2027, 1, 5, 9, 0);

    private BomExpansionService bom;
    private BomRecipeRepository recipes;
    private ShortageSupplyQueryRepository supply;
    private ProcessSecretRecipeGuard secrets;
    private BomRecipe recipe;
    private UrgentInsertMaterialAssessor assessor;

    private InsertSlotRepository slots;
    private ProductionPlanRepository plans;
    private InsertSlotPreviewRepository previews;
    private ImpactAnalysisService impact;
    private UrgentInsertServiceImpl service;
    private ChainImpactResult chain;
    private UrgentInsertScheduleService scheduler;
    private com.cretas.aims.repository.UserRepository users;
    private WorkerShiftAvailabilityCheck workers;

    @BeforeEach
    void setUp() {
        bom = mock(BomExpansionService.class);
        recipes = mock(BomRecipeRepository.class);
        supply = mock(ShortageSupplyQueryRepository.class);
        secrets = mock(ProcessSecretRecipeGuard.class);
        when(secrets.permitsProduct(anyString(), anyString(), any(), anyString())).thenReturn(true);
        recipe = new BomRecipe();
        recipe.setId("RECIPE-1");
        recipe.setVersion(3);
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(F, PT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        UnitContractService units = mock(UnitContractService.class);
        when(units.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(1).equals(i.getArgument(2)));
        when(supply.findOpenOrderRemaining(anyString(), anyString())).thenReturn(List.of());
        assessor = UrgentInsertTestSupport.construct(UrgentInsertMaterialAssessor.class, Map.of(
                BomExpansionService.class, bom,
                BomRecipeRepository.class, recipes,
                ShortageSupplyQueryRepository.class, supply,
                UnitContractService.class, units,
                ProcessSecretRecipeGuard.class, secrets,
                PlatformTransactionManager.class, mock(PlatformTransactionManager.class)));

        slots = mock(InsertSlotRepository.class);
        plans = mock(ProductionPlanRepository.class);
        previews = mock(InsertSlotPreviewRepository.class);
        impact = mock(ImpactAnalysisService.class);
        InsertSlot slot = InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(start).endTime(start.plusHours(2)).requiredWorkers(0).availableWorkers(0)
                .availableCapacity(BigDecimal.TEN).impactLevel("none").status("available").build();
        slot.setUpdatedAt(start.minusMinutes(5));
        when(slots.findByIdAndFactoryId("S1", F)).thenReturn(Optional.of(slot));
        when(slots.claimAvailableSlot(eq("S1"), eq(F), any())).thenReturn(1);
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        chain = completeImpact(List.of());
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any())).thenAnswer(i -> chain);
        when(impact.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        when(previews.findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(F), any())).thenReturn(List.of());
        when(previews.countByFactoryIdAndSlotId(F, "S1")).thenReturn(0L);
        when(previews.save(any())).thenAnswer(i -> i.getArgument(0));
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductType product = new ProductType();
        product.setId(PT);
        product.setFactoryId(F);
        product.setUnit("kg");
        when(products.findByIdAndFactoryId(PT, F)).thenReturn(Optional.of(product));
        when(products.findByIdAndFactoryIdForUpdate(PT, F)).thenReturn(Optional.of(product));
        ResourceCheckResult ready = ResourceCheckResult.available("TEST", BigDecimal.TEN, BigDecimal.ONE);
        LineCapacityEquipmentCheck lines = mock(LineCapacityEquipmentCheck.class);
        when(lines.checkEquipment(anyString(), anyString())).thenReturn(ready);
        when(lines.checkCapacity(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(ready);
        workers = mock(WorkerShiftAvailabilityCheck.class);
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, 0, 0, Set.of(), "ok"));
        scheduler = UrgentInsertTestSupport.okScheduler();
        users = mock(com.cretas.aims.repository.UserRepository.class);
        com.cretas.aims.entity.User operator = new com.cretas.aims.entity.User();
        operator.setFullName("测试人");
        operator.setRoleCode("scheduler");
        when(users.findById(anyLong())).thenReturn(Optional.of(operator));
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(UrgentInsertScheduleService.class, scheduler),
                Map.entry(com.cretas.aims.repository.UserRepository.class, users),
                Map.entry(InsertSlotRepository.class, slots),
                Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(InsertSlotPreviewRepository.class, previews),
                Map.entry(ProductTypeRepository.class, products),
                Map.entry(ImpactAnalysisService.class, impact),
                Map.entry(LineCapacityEquipmentCheck.class, lines),
                Map.entry(WorkerShiftAvailabilityCheck.class, workers),
                Map.entry(SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class)),
                Map.entry(UrgentInsertMaterialAssessor.class, assessor),
                Map.entry(ObjectMapper.class, new ObjectMapper())));
    }

    // ------------------------------------------------------------------ fixtures

    private static MaterialRequirement need(String id, String name, String qty) {
        MaterialRequirement r = new MaterialRequirement();
        r.setMaterialTypeId(id);
        r.setMaterialTypeName(name);
        r.setRequiredQuantity(new BigDecimal(qty));
        return r;
    }

    /** 配方要 60kg 带鱼; 可领库存 {@code available} kg。 */
    private void stock(String available) {
        when(bom.expandBOM(eq(F), eq(PT), any())).thenReturn(List.of(need("RM-1", "带鱼", "60")));
        BigDecimal avail = new BigDecimal(available);
        if (avail.compareTo(new BigDecimal("60")) >= 0) {
            MaterialAllocation a = new MaterialAllocation();
            a.setMaterialTypeId("RM-1");
            a.setAllocatedQuantity(new BigDecimal("60"));
            when(bom.checkPickableAvailability(eq(F), anyList()))
                    .thenReturn(new MaterialCheckResult(true, List.of(), List.of(a)));
        } else {
            MaterialShortfall sf = new MaterialShortfall("RM-1", "带鱼", new BigDecimal("60"), avail,
                    new BigDecimal("60").subtract(avail));
            sf.setQuantityUnit("kg");
            when(bom.checkPickableAvailability(eq(F), anyList()))
                    .thenReturn(new MaterialCheckResult(false, List.of(sf), List.of()));
        }
    }

    private static ChainImpactResult completeImpact(List<AffectedPlanDTO> affected) {
        return ChainImpactResult.builder().impactLevel("none").requiresApproval(false)
                .affectedPlans(affected).totalAffectedPlans(affected.size()).directConflicts(0).cascadeDelays(0)
                .maxDelayMinutes(0).totalDelayMinutes(0).affectedVipCustomers(0)
                .criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build();
    }

    private static AffectedPlanDTO affected(String planId, int delayMinutes) {
        return AffectedPlanDTO.builder().planId(planId).delayMinutes(delayMinutes)
                .originalEndTime(LocalDateTime.of(2027, 1, 6, 12, 0))
                .delayedEndTime(LocalDateTime.of(2027, 1, 6, 12, 0).plusMinutes(delayMinutes)).build();
    }

    private GetInsertSlotsRequest analyzeRequest() {
        GetInsertSlotsRequest r = new GetInsertSlotsRequest();
        r.setProductTypeId(PT);
        r.setRequiredQuantity(new BigDecimal("50"));
        r.setQuantityUnit("KG");
        return r;
    }

    private ConfirmUrgentInsertRequest confirmRequest(String previewId) {
        return ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId(PT).previewId(previewId)
                .quantityUnit("KG").plannedQuantity(new BigDecimal("50")).build();
    }

    /** 走真实预览入口发一份预览, 并让确认读到它。 */
    private InsertSlotPreview issuePreview() {
        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());
        assertThat(out.get("previewId")).as("预览应已签发").isNotNull();
        org.mockito.ArgumentCaptor<InsertSlotPreview> saved = org.mockito.ArgumentCaptor.forClass(InsertSlotPreview.class);
        verify(previews).save(saved.capture());
        InsertSlotPreview preview = saved.getValue();
        preview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId(preview.getId(), F)).thenReturn(Optional.of(preview));
        return preview;
    }

    // ------------------------------------------------------------------ 第 1 项: 缺什么缺多少

    @Test
    void shortStockNamesTheMaterialAndHowMuchIsMissing() {
        stock("20");

        Assessment a = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(a.status()).isEqualTo(Status.SHORT);
        assertThat(a.bomRecipeId()).isEqualTo("RECIPE-1");
        assertThat(a.bomVersion()).isEqualTo(3);
        assertThat(a.message()).contains("带鱼").contains("缺 40kg").contains("需要 60kg").contains("可领库存 20kg")
                .contains("没有在途采购");
        assertThat(a.lines()).singleElement().satisfies(l -> {
            assertThat(l.shortfall()).isEqualByComparingTo("40");
            assertThat(l.gapAfterOnOrder()).isEqualByComparingTo("40");
        });
    }

    @Test
    void inTransitIsListedButNeverCountedAsAvailable() {
        stock("20");
        when(supply.findOpenOrderRemaining(F, "RM-1"))
                .thenReturn(java.util.Collections.singletonList(new Object[] {"kg", new BigDecimal("100")}));

        Assessment a = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(a.status()).as("货没到, 仍是缺料").isEqualTo(Status.SHORT);
        assertThat(a.message()).contains("在途未到 100kg").contains("到货前不能插");
        assertThat(a.lines().get(0).gapAfterOnOrder()).isEqualByComparingTo("0");
    }

    @Test
    void secretProtectedProductOnlyNamesWhichMaterialIsShort() {
        stock("20");
        when(secrets.permitsProduct(anyString(), anyString(), any(), anyString())).thenReturn(false);

        Assessment a = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(a.status()).isEqualTo(Status.SHORT);
        assertThat(a.message()).isEqualTo("带鱼 不足");
        assertThat(a.lines().get(0).required()).isNull();
        assertThat(a.lines().get(0).shortfall()).isNull();
    }

    @Test
    void noActiveRecipeVersionIsUnverifiedNotEnough() {
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(F, PT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.empty());

        Assessment a = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(a.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(a.fingerprint()).isNull();
        verify(bom, never()).checkPickableAvailability(anyString(), anyList());
    }

    @Test
    void fingerprintMovesWhenRecipeVersionOrStockMoves() {
        stock("80");
        String base = assessor.assess(F, PT, new BigDecimal("50")).fingerprint();
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).fingerprint()).as("同样数据同样指纹").isEqualTo(base);

        recipe.setVersion(4);
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).fingerprint()).as("配方改版").isNotEqualTo(base);
        recipe.setVersion(3);

        stock("20");
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).fingerprint()).as("库存变成不够").isNotEqualTo(base);
    }

    @Test
    void stockChangeThatKeepsTheVerdictDoesNotMoveTheFingerprint() {
        stock("80");
        String base = assessor.assess(F, PT, new BigDecimal("50")).fingerprint();
        stock("500");
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).fingerprint())
                .as("仍然够: 不应误拒确认").isEqualTo(base);
    }

    @Test
    void requiredQuantityIsPartOfTheFingerprint() {
        stock("80");
        String base = assessor.assess(F, PT, new BigDecimal("50")).fingerprint();
        when(bom.expandBOM(eq(F), eq(PT), any())).thenReturn(List.of(need("RM-1", "带鱼", "61")));
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).fingerprint()).isNotEqualTo(base);
    }

    @Test
    void recipeChangedWhileAssessingIsUnverifiedNotAMixOfVersions() {
        stock("80");
        BomRecipe newer = new BomRecipe();
        newer.setId("RECIPE-1");
        newer.setVersion(4);
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(F, PT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe), Optional.of(newer));

        Assessment a = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(a.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(a.message()).contains("配方被修改");
    }

    @Test
    void responseNeverExposesTheFingerprint() {
        stock("20");
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).toMap()).doesNotContainKey("fingerprint");
        stock("80");
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).toMap()).doesNotContainKey("fingerprint");
    }

    @Test
    void configErrorMessageDoesNotNameMaterialsForCallersWithoutFullView() {
        when(bom.expandBOM(eq(F), eq(PT), any())).thenThrow(new BusinessException(409,
                "原料「带鱼」BOM单位(g)与库存单位(个)无法换算"));
        assertThat(assessor.assess(F, PT, new BigDecimal("50")).message()).contains("带鱼");

        when(secrets.permitsProduct(anyString(), anyString(), any(), anyString())).thenReturn(false);
        Assessment hidden = assessor.assess(F, PT, new BigDecimal("50"));

        assertThat(hidden.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(hidden.message()).doesNotContain("带鱼");
    }

    @Test
    void unverifiedMaterialDoesNotIssueAPreviewThatWouldFillTheFactoryQuota() {
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(F, PT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.empty());

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        assertThat(out.get("previewId")).isNull();
        assertThat(out).containsEntry("feasibilityStatus", "UNVERIFIED");
        verify(previews, never()).save(any());
    }

    // ------------------------------------------------------------------ 预览: 所有读数都落进快照

    @Test
    void previewCarriesMaterialPlanAndStoresFingerprintsAndLockedRecipe() {
        stock("80");

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) out.get("materialPlan");
        assertThat(plan).containsEntry("status", "OK").containsEntry("bomRecipeId", "RECIPE-1")
                .containsEntry("bomVersion", 3);
        org.mockito.ArgumentCaptor<InsertSlotPreview> saved = org.mockito.ArgumentCaptor.forClass(InsertSlotPreview.class);
        verify(previews).save(saved.capture());
        assertThat(plan).doesNotContainKey("fingerprint");
        assertThat(saved.getValue().getMaterialFingerprint())
                .isEqualTo(assessor.assess(F, PT, new BigDecimal("50")).fingerprint());
        assertThat(saved.getValue().getBomRecipeId()).isEqualTo("RECIPE-1");
        assertThat(saved.getValue().getBomVersion()).isEqualTo(3);
        assertThat(saved.getValue().getImpactFingerprint()).isEqualTo(UrgentInsertServiceImpl.impactFingerprint(chain));
    }

    @Test
    void shortStockPreviewSaysInfeasibleWithTheGap() {
        stock("20");

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        assertThat(out).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "INFEASIBLE");
        assertThat((String) out.get("infeasibleReason")).contains("带鱼").contains("缺 40kg");
    }

    // ------------------------------------------------------------------ 第 3 项: 确认与预览必须一致

    @Test
    void confirmWithMatchingSnapshotCreatesPlanLockedToThePreviewedRecipeVersion() {
        stock("80");
        InsertSlotPreview preview = issuePreview();

        service.confirmInsert(F, 1L, confirmRequest(preview.getId()));

        verify(plans).save(org.mockito.ArgumentMatchers.argThat(p ->
                "RECIPE-1".equals(p.getSelectedBomRecipeId()) && Integer.valueOf(3).equals(p.getSelectedBomVersion())));
    }

    @Test
    void confirmIsRefusedWhenStockWentShortAfterPreview() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        stock("20");

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).contains("原料不足").contains("带鱼").contains("缺 40kg");
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_MATERIAL_SHORT");
                });
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(plans, never()).save(any());
    }

    @Test
    void confirmIsRefusedWhenRecipeVersionChangedAfterPreview() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        recipe.setVersion(4);

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).contains("请重新预览");
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_MATERIAL_CHANGED");
                });
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(plans, never()).save(any());
    }

    @Test
    void confirmIsRefusedWhenTheSetOfBumpedPlansChangedEvenIfTheCountIsTheSame() {
        stock("80");
        chain = completeImpact(List.of(affected("PLAN-A", 120)));
        InsertSlotPreview preview = issuePreview();
        chain = completeImpact(List.of(affected("PLAN-B", 120)));

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_IMPACT_CHANGED"));
        verify(plans, never()).save(any());
    }

    @Test
    void confirmIsRefusedWhenTheDelayOfABumpedPlanChanged() {
        stock("80");
        chain = completeImpact(List.of(affected("PLAN-A", 120)));
        InsertSlotPreview preview = issuePreview();
        chain = completeImpact(List.of(affected("PLAN-A", 240)));

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_IMPACT_CHANGED"));
        verify(plans, never()).save(any());
    }

    @Test
    void previewIssuedBeforeThisChangeHasNoFingerprintAndMustBeRedone() {
        stock("80");
        InsertSlotPreview legacy = InsertSlotPreview.builder().id("PV-OLD").factoryId(F).slotId("S1")
                .productTypeId(PT).previewQuantity(new BigDecimal("50")).quantityUnit("KG").plannedUnit("kg")
                .productionLineId("L1").windowStart(start).windowEnd(start.plusHours(2))
                .slotUpdatedAt(start.minusMinutes(5)).impactLevel("none").requiresApproval(false)
                .affectedPlanCount(0).candidateNumber(1).build();
        legacy.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId("PV-OLD", F)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest("PV-OLD")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_IMPACT_CHANGED"));
        verify(plans, never()).save(any());
    }

    @Test
    void forceInsertIsAlsoBlockedByShortMaterial() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        stock("20");

        assertThatThrownBy(() -> service.forceInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_MATERIAL_SHORT"));
        verify(plans, never()).save(any());
    }

    // ------------------------------------------------------------------ 第 2 项: 确认后真正排入

    @Test
    void confirmAppliesTheScheduleWithThePreviewedFingerprintAndReturnsTheOutcome() {
        stock("80");
        InsertSlotPreview preview = issuePreview();

        com.cretas.aims.dto.production.ProductionPlanDTO dto = service.confirmInsert(F, 1L, confirmRequest(preview.getId()));

        verify(scheduler).apply(eq(F), any(), any(ProductionPlan.class), eq(UrgentInsertTestSupport.SCHEDULE_FP), eq(1L));
        assertThat(dto.getScheduleOutcome()).containsEntry("status", "OK");
        assertThat(preview.getScheduleFingerprint()).isEqualTo(UrgentInsertTestSupport.SCHEDULE_FP);
    }

    @Test
    void confirmFailsWhenTheScheduleChangedAfterPreviewAndTheErrorReachesTheCaller() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        when(scheduler.apply(any(), any(), any(), any(), any())).thenThrow(new BusinessException(409, "排程变了")
                .withCode("INSERT_PREVIEW_SCHEDULE_CHANGED"));

        assertThatThrownBy(() -> service.confirmInsert(F, 1L, confirmRequest(preview.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_SCHEDULE_CHANGED"));
    }

    @Test
    void blockedScheduleMakesThePreviewInfeasibleWithTheReasonAndIssuesNoPreview() {
        stock("80");
        when(scheduler.dryRun(any(), any(), any())).thenReturn(new UrgentInsertScheduleService.Proposal(
                UrgentInsertScheduleService.BLOCKED, "设备已报废", null, null, List.of(), List.of(), null));

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        assertThat(out.get("previewId")).isNull();
        assertThat(out).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "INFEASIBLE");
        assertThat((String) out.get("infeasibleReason")).contains("排程排不进去").contains("设备已报废");
        @SuppressWarnings("unchecked")
        Map<String, Object> sp = (Map<String, Object>) out.get("schedulePlan");
        assertThat(sp).containsEntry("status", "BLOCKED");
        verify(previews, never()).save(any());
    }

    @Test
    void readOnlyImpactAnalysisNeverRunsTheEngineBecauseItNeedsAWriteTransaction() {
        stock("80");

        Map<String, Object> out = service.analyzeInsertImpact(F, "S1", analyzeRequest());

        verify(scheduler, never()).dryRun(any(), any(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> sp = (Map<String, Object>) out.get("schedulePlan");
        assertThat(sp).containsEntry("status", "NOT_EVALUATED");
        assertThat(out.get("previewId")).isNull();
    }

    @Test
    void theTemporaryPlanForTheDryRunCarriesAnOwnerBecauseCreatedByIsNotNull() {
        stock("80");

        service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        org.mockito.ArgumentCaptor<ProductionPlan> temp = org.mockito.ArgumentCaptor.forClass(ProductionPlan.class);
        verify(scheduler).dryRun(eq(F), any(), temp.capture());
        assertThat(temp.getValue().getCreatedBy()).isNotNull();
        assertThat(temp.getValue().getSelectedBomRecipeId()).isEqualTo("RECIPE-1");
    }

    @Test
    void shortMaterialDoesNotEvenAskTheEngine() {
        stock("20");

        service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        verify(scheduler, never()).dryRun(any(), any(), any());
    }

    @Test
    void previewShowsEachBumpedPlansNewCompletionDateAndWhetherTheDueDateIsBroken() {
        stock("80");
        when(scheduler.dryRun(any(), any(), any())).thenReturn(new UrgentInsertScheduleService.Proposal(
                UrgentInsertScheduleService.OK, null, start.toLocalDate(), start.toLocalDate(),
                List.of(new UrgentInsertScheduleService.Moved("PLAN-B", "PB-1", start.toLocalDate().plusDays(8),
                        start.toLocalDate().plusDays(11), 3, start.toLocalDate().plusDays(8), true, List.of("SO-B"), List.of())),
                List.of(), "FP-X"));

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> sp = (Map<String, Object>) out.get("schedulePlan");
        assertThat(sp).containsEntry("exceedsDueCount", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moved = (List<Map<String, Object>>) sp.get("movedPlans");
        assertThat(moved).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("planNumber", "PB-1").containsEntry("exceedsDue", true)
                    .containsEntry("delayDays", 3L);
            assertThat(m.get("newEnd")).isEqualTo(start.toLocalDate().plusDays(11));
        });
    }

    @Test
    void forceInsertWithoutApprovalAppliesTheScheduleImmediately() {
        stock("80");
        InsertSlotPreview preview = issuePreview();

        service.forceInsert(F, 1L, confirmRequest(preview.getId()));

        verify(scheduler).apply(eq(F), any(), any(ProductionPlan.class), eq(UrgentInsertTestSupport.SCHEDULE_FP), eq(1L));
    }

    @Test
    void forceInsertThatNeedsApprovalDoesNotScheduleUntilApproved() {
        stock("80");
        chain = ChainImpactResult.builder().impactLevel("high").requiresApproval(true)
                .affectedPlans(List.of()).totalAffectedPlans(0).directConflicts(0).cascadeDelays(0)
                .maxDelayMinutes(0).totalDelayMinutes(0).affectedVipCustomers(0)
                .criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build();
        InsertSlotPreview preview = issuePreview();

        service.forceInsert(F, 1L, confirmRequest(preview.getId()));

        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void approvalSchedulesThePlanAtApprovalTimeAndFailsTheApprovalIfItDoesNotFit() {
        ProductionPlan pending = new ProductionPlan();
        pending.setId("PLAN-FORCED");
        pending.setFactoryId(F);
        pending.setPlanNumber("URGENT-F");
        pending.setStartTime(start);
        pending.setSuggestedProductionLineId("L1");
        pending.markAsForceInsert(1L, "客户急单", true);
        when(plans.findById("PLAN-FORCED")).thenReturn(Optional.of(pending));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        InsertSlot selected = InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(start).endTime(start.plusHours(2)).status("selected").build();
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of(selected));

        service.approveForceInsert(F, "PLAN-FORCED", 9L, true, "同意");

        verify(scheduler).apply(eq(F), eq(selected), eq(pending), org.mockito.ArgumentMatchers.isNull(), eq(9L));

        // 排不进去: 批准整体失败 (事务回滚), 抛给调用方
        ProductionPlan pending2 = new ProductionPlan();
        pending2.setId("PLAN-FORCED-2");
        pending2.setFactoryId(F);
        pending2.setStartTime(start);
        pending2.setSuggestedProductionLineId("L1");
        pending2.markAsForceInsert(1L, "x", true);
        when(plans.findById("PLAN-FORCED-2")).thenReturn(Optional.of(pending2));
        when(scheduler.apply(any(), any(), eq(pending2), any(), any())).thenThrow(new BusinessException(409, "排不进去")
                .withCode("INSERT_SCHEDULE_BLOCKED"));
        assertThatThrownBy(() -> service.approveForceInsert(F, "PLAN-FORCED-2", 9L, true, "同意"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
    }

    // ------------------------------------------------------------------ 没有排班数据: 未核对, 不阻断 (Steve 2026-11-10)

    private static final String NO_DATA = WorkerShiftAvailabilityCheck.NO_SCHEDULE_DATA;

    @Test
    @SuppressWarnings("unchecked")
    void noScheduleDataIsUncheckedNotShortAndStillIssuesAPreview() {
        stock("80");
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(WorkerShiftAvailabilityCheck.Result.noScheduleData(0));

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        assertThat(out.get("previewId")).as("缺排班数据不拦预览").isNotNull();
        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs).containsEntry("workerStatus", "UNVERIFIED").containsEntry("workerNoScheduleData", true)
                .containsEntry("workerVerified", false);
        assertThat(rs.get("availableWorkers")).as("不知道不等于 0 人").isNull();
        assertThat((List<String>) out.get("uncheckedWarnings")).containsExactly(NO_DATA);
        assertThat((List<String>) out.get("unverifiedReasons")).contains(NO_DATA);
        assertThat(out).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "UNVERIFIED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dataShowingTooFewWorkersStillMakesTheSlotInfeasibleAndHasNoUncheckedWarning() {
        stock("80");
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, 2, 1, Set.of(11L), "人员不足: 需要2人，可用1人"));

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs).containsEntry("workerStatus", "SHORTAGE").containsEntry("workerNoScheduleData", false)
                .containsEntry("availableWorkers", 1);
        assertThat(out).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "INFEASIBLE")
                .containsEntry("riskLevel", "critical");
        assertThat((List<String>) out.get("uncheckedWarnings")).isEmpty();
    }

    @Test
    void otherUnverifiedWorkerReadsKeepTheirOwnStatusAndAreNotLabelledNoData() {
        stock("80");
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(WorkerShiftAvailabilityCheck.Result.unverified(2, "人员未验证：无法确认人员排班数据"));

        Map<String, Object> out = service.createInsertPreview(F, 1L, "S1", analyzeRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs).containsEntry("workerStatus", "MISSING").containsEntry("workerNoScheduleData", false);
        assertThat((List<?>) out.get("uncheckedWarnings")).isEmpty();
    }

    @Test
    void confirmCarriesTheUncheckedWarningAndAWorkerListIsNotRefusedWhenThereIsNoRosterToCheckItAgainst() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(WorkerShiftAvailabilityCheck.Result.noScheduleData(0));
        ConfirmUrgentInsertRequest withList = ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId(PT)
                .previewId(preview.getId()).quantityUnit("KG").plannedQuantity(new BigDecimal("50"))
                .assignedWorkerIds(List.of(11L, 12L)).build();

        ProductionPlanDTO dto = service.confirmInsert(F, 1L, withList);

        assertThat(dto.getUncheckedWarnings()).containsExactly(NO_DATA);
        verify(plans, org.mockito.Mockito.atLeastOnce()).save(any(ProductionPlan.class));
    }

    @Test
    void confirmWithVerifiedRosterCarriesNoUncheckedWarning() {
        stock("80");
        InsertSlotPreview preview = issuePreview();

        ProductionPlanDTO dto = service.confirmInsert(F, 1L, confirmRequest(preview.getId()));

        assertThat(dto.getUncheckedWarnings()).isEmpty();
    }

    @Test
    void forceInsertWithoutApprovalCarriesTheUncheckedWarning() {
        stock("80");
        InsertSlotPreview preview = issuePreview();
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(WorkerShiftAvailabilityCheck.Result.noScheduleData(0));

        ProductionPlanDTO dto = service.forceInsert(F, 1L, confirmRequest(preview.getId()));

        assertThat(dto.getUncheckedWarnings()).containsExactly(NO_DATA);
    }

    @Test
    void approvalCarriesTheUncheckedWarning() {
        ProductionPlan pending = new ProductionPlan();
        pending.setId("PLAN-FORCED-W");
        pending.setFactoryId(F);
        pending.setPlanNumber("URGENT-W");
        pending.setStartTime(start);
        pending.setSuggestedProductionLineId("L1");
        pending.markAsForceInsert(1L, "客户急单", true);
        when(plans.findById("PLAN-FORCED-W")).thenReturn(Optional.of(pending));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        InsertSlot selected = InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(start).endTime(start.plusHours(2)).requiredWorkers(0).status("selected").build();
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of(selected));
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(WorkerShiftAvailabilityCheck.Result.noScheduleData(0));

        ProductionPlanDTO dto = service.approveForceInsert(F, "PLAN-FORCED-W", 9L, true, "同意");

        assertThat(dto.getUncheckedWarnings()).containsExactly(NO_DATA);
    }

    @Test
    void rejectingTheApprovalNeverSchedules() {
        ProductionPlan pending = new ProductionPlan();
        pending.setId("PLAN-FORCED-3");
        pending.setFactoryId(F);
        pending.setStartTime(start);
        pending.markAsForceInsert(1L, "x", true);
        when(plans.findById("PLAN-FORCED-3")).thenReturn(Optional.of(pending));
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(slots.findByFactoryIdAndStatus(F, "selected")).thenReturn(List.of());

        service.approveForceInsert(F, "PLAN-FORCED-3", 9L, false, "不同意");

        verify(scheduler, never()).apply(any(), any(), any(), any(), any());
    }
}

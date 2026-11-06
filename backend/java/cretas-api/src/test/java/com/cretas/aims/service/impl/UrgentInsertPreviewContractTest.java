package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.InsertSlotPreview;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.cretas.aims.exception.BusinessException;

class UrgentInsertPreviewContractTest {

    private static final String FACTORY = "F-PREVIEW";
    private static final String SLOT = "S-PREVIEW";
    private static final String PRODUCT = "P-PREVIEW";

    private InsertSlotPreviewRepository previews;
    private ProductTypeRepository products;
    private ProductionLineCapacityConfigRepository capacityConfigs;
    private InsertSlotRepository slots;
    private SchedulingResourceWriteLock factoryLock;
    private ImpactAnalysisService impact;
    private InsertSlot slot;
    private InsertSlotPreview currentPreview;
    private UrgentInsertServiceImpl service;

    @BeforeEach
    void setUp() {
        previews = mock(InsertSlotPreviewRepository.class);
        products = mock(ProductTypeRepository.class);
        capacityConfigs = mock(ProductionLineCapacityConfigRepository.class);
        slots = mock(InsertSlotRepository.class);
        factoryLock = mock(SchedulingResourceWriteLock.class);

        slot = InsertSlot.builder()
                .id(SLOT).factoryId(FACTORY).productionLineId("LINE-1")
                .productionLineName("实际产线")
                .startTime(LocalDateTime.now().plusHours(2))
                .endTime(LocalDateTime.now().plusHours(4))
                .requiredWorkers(0).availableWorkers(0).status("available")
                .build();
        slot.setUpdatedAt(slot.getStartTime().minusMinutes(5));
        when(slots.findByIdAndFactoryId(SLOT, FACTORY)).thenReturn(Optional.of(slot));
        when(previews.findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(List.of());
        when(previews.countByFactoryIdAndSlotId(FACTORY, SLOT)).thenReturn(0L);
        when(previews.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        impact = mock(ImpactAnalysisService.class);
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(completeImpact());
        when(impact.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        when(impact.checkMaterialAvailability(anyString(), anyString(), any()))
                .thenReturn(ResourceCheckResult.available("MATERIAL", BigDecimal.TEN, BigDecimal.ONE));

        LineCapacityEquipmentCheck lineChecks = mock(LineCapacityEquipmentCheck.class);
        when(lineChecks.checkEquipment(anyString(), anyString()))
                .thenReturn(ResourceCheckResult.available("EQUIPMENT", BigDecimal.ONE, BigDecimal.ONE));
        when(lineChecks.checkCapacity(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.available("CAPACITY", BigDecimal.TEN, BigDecimal.ONE));

        WorkerShiftAvailabilityCheck workers = mock(WorkerShiftAvailabilityCheck.class);
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, 0, 0, Set.of(), "人员排班可用: 0人"));

        currentPreview = InsertSlotPreview.builder().id("PV1").factoryId(FACTORY).slotId(SLOT)
                .productTypeId(PRODUCT).previewQuantity(new BigDecimal("5.00")).quantityUnit("PIECE")
                .plannedUnit("件").productionLineId("LINE-1").windowStart(slot.getStartTime())
                .windowEnd(slot.getEndTime()).slotUpdatedAt(slot.getUpdatedAt())
                .impactLevel("none").requiresApproval(false).affectedPlanCount(0).candidateNumber(1).build();
        currentPreview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId("PV1", FACTORY)).thenReturn(Optional.of(currentPreview));

        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slots,
                InsertSlotPreviewRepository.class, previews,
                ProductTypeRepository.class, products,
                ProductionLineCapacityConfigRepository.class, capacityConfigs,
                ImpactAnalysisService.class, impact,
                LineCapacityEquipmentCheck.class, lineChecks,
                WorkerShiftAvailabilityCheck.class, workers,
                SchedulingResourceWriteLock.class, factoryLock,
                ObjectMapper.class, new ObjectMapper()));
    }

    private GetInsertSlotsRequest request(String unit) {
        GetInsertSlotsRequest request = new GetInsertSlotsRequest();
        request.setProductTypeId(PRODUCT);
        request.setRequiredQuantity(new BigDecimal("5.00"));
        request.setQuantityUnit(unit);
        request.setDeadline(LocalDateTime.now().plusDays(1));
        return request;
    }

    private void product(String unit) {
        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setUnit(unit);
        when(products.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.of(product));
        when(products.findByIdAndFactoryIdForUpdate(PRODUCT, FACTORY)).thenReturn(Optional.of(product));
    }

    @Test
    void getImpactRemainsReadOnlyAndDoesNotIssuePreview() {
        product("件");

        Map<String, Object> result = service.analyzeInsertImpact(FACTORY, SLOT, request("PIECE"));

        assertThat(result).containsEntry("previewId", null);
        verify(factoryLock, never()).lockFactory(anyString());
        verify(previews, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"袋", "盒", "g", "box", "case", "箱", "箱(6袋)"})
    void unknownPackageUnitsCannotBePretendedToBeKg(String productUnit) {
        product(productUnit);

        Map<String, Object> result = service.analyzeInsertImpact(FACTORY, SLOT, request("KG"));

        assertThat(result).containsEntry("previewId", null)
                .containsEntry("feasibilityStatus", "UNVERIFIED");
        verify(previews, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"kg", "公斤", "千克"})
    void kilogramUnitsRemainKgWithoutConversion(String productUnit) {
        product(productUnit);

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("KG"));

        assertThat(result.get("previewId")).isNotNull();
        assertThat(result).containsEntry("quantityUnit", "KG")
                .containsEntry("plannedUnit", productUnit)
                .containsEntry("previewQuantity", new BigDecimal("5.00"));
    }

    @Test
    void mismatchedPieceAndKgCannotIssuePreview() {
        product("件");

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("KG"));

        assertThat(result).containsEntry("previewId", null)
                .containsEntry("feasibilityStatus", "UNVERIFIED");
        verify(previews, never()).save(any());
    }

    @Test
    void mismatchedKgAndPieceCannotIssuePreview() {
        product("kg");

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("PIECE"));

        assertThat(result).containsEntry("previewId", null)
                .containsEntry("feasibilityStatus", "UNVERIFIED");
        verify(previews, never()).save(any());
    }

    @Test
    void batchUsesBatchWithoutConversion() {
        product("批");

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("BATCH"));

        assertThat(result.get("previewId")).isNotNull();
        assertThat(result).containsEntry("quantityUnit", "BATCH")
                .containsEntry("plannedUnit", "批")
                .containsEntry("previewQuantity", new BigDecimal("5.00"));
    }

    @Test
    void individualUnitRequiresProductSpecificPieceCapacity() {
        product("个");
        ProductionLineCapacityConfig generic = new ProductionLineCapacityConfig();
        generic.setProductionLineId("LINE-1");
        generic.setProductTypeId(null);
        generic.setCapacityUnit(ProductionLineCapacityConfig.CapacityUnit.PIECE);
        when(capacityConfigs.findEffectiveForProduct(eq(FACTORY), eq(PRODUCT), any()))
                .thenReturn(List.of(generic));

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("PIECE"));

        assertThat(result).containsEntry("previewId", null)
                .containsEntry("feasibilityStatus", "UNVERIFIED");
        verify(previews, never()).save(any());
    }

    @Test
    void individualUnitUsesProductSpecificPieceCapacityWithoutConversion() {
        product("个");
        ProductionLineCapacityConfig specific = new ProductionLineCapacityConfig();
        specific.setProductionLineId("LINE-1");
        specific.setProductTypeId(PRODUCT);
        specific.setCapacityUnit(ProductionLineCapacityConfig.CapacityUnit.PIECE);
        when(capacityConfigs.findEffectiveForProduct(eq(FACTORY), eq(PRODUCT), any()))
                .thenReturn(List.of(specific));

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("PIECE"));

        assertThat(result.get("previewId")).isNotNull();
        assertThat(result).containsEntry("quantityUnit", "PIECE")
                .containsEntry("plannedUnit", "个");
    }

    private ConfirmUrgentInsertRequest confirmRequest() {
        return ConfirmUrgentInsertRequest.builder()
                .slotId(SLOT).previewId("PV1").productTypeId(PRODUCT)
                .plannedQuantity(new BigDecimal("5.00")).quantityUnit("PIECE")
                .urgentReason("测试").build();
    }

    private static ChainImpactResult completeImpact() {
        return completeImpact("none");
    }

    private static ChainImpactResult completeImpact(String level) {
        return completeImpact(level, "high".equals(level) || "critical".equals(level));
    }

    private static ChainImpactResult completeImpact(String level, boolean requiresApproval) {
        return ChainImpactResult.builder().impactLevel(level).requiresApproval(requiresApproval)
                .totalAffectedPlans(0).directConflicts(0).cascadeDelays(0)
                .maxDelayMinutes(0).totalDelayMinutes(0).affectedVipCustomers(0)
                .criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build();
    }

    @Test
    void confirmationRejectsChangedQuantityBeforeClaim() {
        product("件");
        ConfirmUrgentInsertRequest request = confirmRequest();
        request.setPlannedQuantity(new BigDecimal("6.00"));

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"kg,公斤,KG", "kg,千克,KG", "件,个,PIECE"})
    void productSnapshotIdentityRejectsChangedStoredLabelsEvenWithinSameCapacityDimension(
            String cachedLabel, String changedLabel, String dimension) {
        product(changedLabel);
        ReflectionTestUtils.setField(currentPreview, "plannedUnit", cachedLabel);
        ReflectionTestUtils.setField(currentPreview, "quantityUnit", dimension);
        ConfirmUrgentInsertRequest request = confirmRequest();
        request.setQuantityUnit(dimension);

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("产品实际单位已变化");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void expiredPreviewCannotBeReplayed() {
        product("件");
        currentPreview.setCreatedAt(LocalDateTime.now().minusMinutes(16));

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, confirmRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void changedSlotTimestampCannotAuthorizeOldPreview() {
        product("件");
        slot.setUpdatedAt(slot.getUpdatedAt().plusMinutes(1));

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, confirmRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("产线或时间窗已变化");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void repeatedPreviewRequestReusesMatchingSnapshot() {
        product("件");
        when(previews.findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(List.of(currentPreview));

        Map<String, Object> result = service.createInsertPreview(FACTORY, SLOT, request("PIECE"));

        assertThat(result).containsEntry("previewId", "PV1")
                .containsEntry("plannedUnit", "件");
        verify(previews, never()).save(any());
    }

    @Test
    void incompleteImpactCannotIssueConfirmationPreview() {
        product("件");
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(ChainImpactResult.builder().impactLevel("none").build());

        assertThatThrownBy(() -> service.createInsertPreview(FACTORY, SLOT, request("PIECE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("影响分析未完成");
        verify(previews, never()).save(any());
    }

    @Test
    void forceCannotBypassFreshResourceFailure() {
        product("件");
        LineCapacityEquipmentCheck lineChecks = mock(LineCapacityEquipmentCheck.class);
        when(lineChecks.checkEquipment(anyString(), anyString()))
                .thenReturn(ResourceCheckResult.unverified("EQUIPMENT", "设备未验证", BigDecimal.ONE));
        when(lineChecks.checkCapacity(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.available("CAPACITY", BigDecimal.TEN, BigDecimal.ONE));
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slots, InsertSlotPreviewRepository.class, previews,
                ProductTypeRepository.class, products, ProductionLineCapacityConfigRepository.class, capacityConfigs,
                LineCapacityEquipmentCheck.class, lineChecks, SchedulingResourceWriteLock.class, factoryLock,
                ObjectMapper.class, new ObjectMapper()));
        ConfirmUrgentInsertRequest request = confirmRequest();
        request.setForceInsert(true);

        assertThatThrownBy(() -> service.forceInsert(FACTORY, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("复核未通过");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void confirmationUsesPreviewImpactInsteadOfGenericSlotImpact() {
        product("件");
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(completeImpact("high"));
        currentPreview = InsertSlotPreview.builder().id("PV1").factoryId(FACTORY).slotId(SLOT)
                .productTypeId(PRODUCT).previewQuantity(new BigDecimal("5.00")).quantityUnit("PIECE")
                .plannedUnit("件").productionLineId("LINE-1").windowStart(slot.getStartTime())
                .windowEnd(slot.getEndTime()).slotUpdatedAt(slot.getUpdatedAt())
                .impactLevel("high").requiresApproval(true).affectedPlanCount(0).candidateNumber(1)
                .build();
        currentPreview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId("PV1", FACTORY)).thenReturn(Optional.of(currentPreview));
        ConfirmUrgentInsertRequest request = confirmRequest();
        request.setForceInsert(true);

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("强制插入审批");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void normalConfirmCannotUseForceFlagToBypassApprovalRequirement() {
        product("件");
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(completeImpact("low", true));
        currentPreview = InsertSlotPreview.builder().id("PV1").factoryId(FACTORY).slotId(SLOT)
                .productTypeId(PRODUCT).previewQuantity(new BigDecimal("5.00")).quantityUnit("PIECE")
                .plannedUnit("件").productionLineId("LINE-1").windowStart(slot.getStartTime())
                .windowEnd(slot.getEndTime()).slotUpdatedAt(slot.getUpdatedAt())
                .impactLevel("low").requiresApproval(true).affectedPlanCount(0).candidateNumber(1)
                .build();
        currentPreview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId("PV1", FACTORY)).thenReturn(Optional.of(currentPreview));
        ConfirmUrgentInsertRequest request = confirmRequest();
        request.setForceInsert(true);

        assertThatThrownBy(() -> service.confirmInsert(FACTORY, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("强制插入审批");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }
}

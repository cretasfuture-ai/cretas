package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.RecommendScoreResult;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 紧急插单止血 (2026-10-24): 资源判断不得默认通过 / 他厂时段不得被确认 / 占用失败不得建计划。
 *
 * <p>原料核对走真实的 {@link ImpactAnalysisServiceImpl} (只把 BOM 服务换成桩),
 * 所以「写死 10000」这类回退会让这里变红。
 */
class UrgentInsertResourceGuardContractTest {

    private static final String F = "F-UI-1";

    private BomExpansionService bom;
    private ImpactAnalysisServiceImpl impact;
    private ProductionLineCapacityConfigRepository capacityConfigs;
    private ProductEquipmentRequirementRepository equipmentRequirements;
    private ProductionLineRepository productionLines;
    private EquipmentRepository equipment;
    private LineCapacityEquipmentCheck lineChecks;

    @BeforeEach
    void setUp() {
        bom = mock(BomExpansionService.class);
        // 有完整查看权的调用方 (#1217 工艺保密之后, 无权时只说哪些料不足、不报数量)
        ProcessSecretRecipeGuard secrets = mock(ProcessSecretRecipeGuard.class);
        when(secrets.permitsProduct(anyString(), anyString(), any(), anyString())).thenReturn(true);
        impact = UrgentInsertTestSupport.construct(ImpactAnalysisServiceImpl.class, Map.of(
                BomExpansionService.class, bom,
                ProcessSecretRecipeGuard.class, secrets,
                PlatformTransactionManager.class, mock(PlatformTransactionManager.class)));
        // 产能 / 设备走真实判定, 只把仓库换成桩; 默认什么都没配 ⇒ MISSING
        capacityConfigs = mock(ProductionLineCapacityConfigRepository.class);
        equipmentRequirements = mock(ProductEquipmentRequirementRepository.class);
        productionLines = mock(ProductionLineRepository.class);
        equipment = mock(EquipmentRepository.class);
        lineChecks = new LineCapacityEquipmentCheck(capacityConfigs, equipmentRequirements, productionLines, equipment);
    }

    /** 一条在运行的线, 该产品每小时 {@code perHour} 件; 一台空闲设备。时段 2 小时。 */
    private void configuredInPieces(String perHour) {
        ProductionLine line = new ProductionLine();
        line.setId("L1");
        line.setFactoryId(F);
        line.setName("包装线");
        when(productionLines.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(List.of(line));
        ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
        c.setFactoryId(F);
        c.setProductionLineId("L1");
        c.setProductTypeId("PT-1");
        c.setCapacityPerHour(new BigDecimal(perHour));
        c.setCapacityUnit(ProductionLineCapacityConfig.CapacityUnit.PIECE);
        when(capacityConfigs.findEffectiveForProduct(eq(F), eq("PT-1"), any())).thenReturn(List.of(c));
        ProductEquipmentRequirement req = new ProductEquipmentRequirement();
        req.setEquipmentId(7L);
        when(equipmentRequirements.findByFactoryIdAndProductTypeId(F, "PT-1")).thenReturn(List.of(req));
        FactoryEquipment device = new FactoryEquipment();
        device.setId(7L);
        device.setFactoryId(F);
        device.setEquipmentName("封口机");
        device.setEquipmentCode("EQ-7");
        device.setStatus("idle");
        when(equipment.findByIdIn(anyCollection())).thenReturn(List.of(device));
    }

    private static MaterialRequirement req() {
        MaterialRequirement r = new MaterialRequirement();
        r.setMaterialTypeId("RM-1");
        r.setMaterialTypeName("带鱼");
        r.setRequiredQuantity(new BigDecimal("60"));
        return r;
    }

    private void stockIsZero() {
        when(bom.expandBOM(F, "PT-1", new BigDecimal("50"))).thenReturn(List.of(req()));
        MaterialShortfall sf = new MaterialShortfall("RM-1", "带鱼", new BigDecimal("60"), BigDecimal.ZERO, new BigDecimal("60"));
        when(bom.checkMaterialAvailability(eq(F), anyList()))
                .thenReturn(new MaterialCheckResult(false, List.of(sf), List.of()));
    }

    // ---------------------------------------------------------------- material

    @Test
    void zeroStockIsVerifiedShortNotAvailable() {
        stockIsZero();

        ResourceCheckResult r = impact.checkMaterialAvailability(F, "PT-1", new BigDecimal("50"));

        System.out.println("ZERO_STOCK available=" + r.isAvailable() + " verified=" + r.isVerified() + " msg=" + r.getMessage());
        assertThat(r.isAvailable()).isFalse();
        assertThat(r.isVerified()).isTrue();
        assertThat(r.getMessage()).contains("带鱼").contains("可用 0");
    }

    @Test
    void noActiveBomIsUnverifiedNotAvailable() {
        when(bom.expandBOM(anyString(), anyString(), any()))
                .thenThrow(new BusinessException(409, "产品尚无已激活的新版 BOM 配方: PT-1"));

        ResourceCheckResult r = impact.checkMaterialAvailability(F, "PT-1", new BigDecimal("50"));

        assertThat(r.isAvailable()).isFalse();
        assertThat(r.isVerified()).isFalse();
        assertThat(r.getMessage()).startsWith("原料未验证").contains("BOM");
    }

    @Test
    void dataSourceExceptionIsUnverifiedNotAvailable() {
        when(bom.expandBOM(anyString(), anyString(), any())).thenThrow(new IllegalStateException("db down"));

        ResourceCheckResult r = impact.checkMaterialAvailability(F, "PT-1", new BigDecimal("50"));

        assertThat(r.isAvailable()).isFalse();
        assertThat(r.isVerified()).isFalse();
    }

    @Test
    void bomWithNoInputRowsIsUnverified() {
        when(bom.expandBOM(anyString(), anyString(), any())).thenReturn(List.of());

        ResourceCheckResult r = impact.checkMaterialAvailability(F, "PT-1", new BigDecimal("50"));

        assertThat(r.isVerified()).isFalse();
        assertThat(r.isAvailable()).isFalse();
    }

    @Test
    void missingProductOrQuantityIsUnverifiedWithoutTouchingStock() {
        assertThat(impact.checkMaterialAvailability(F, null, new BigDecimal("50")).isVerified()).isFalse();
        assertThat(impact.checkMaterialAvailability(F, "PT-1", null).isVerified()).isFalse();
        assertThat(impact.checkMaterialAvailability(F, "PT-1", null).isAvailable()).isFalse();
        verifyNoInteractions(bom);
    }

    @Test
    void sufficientStockIsVerifiedAvailable() {
        when(bom.expandBOM(F, "PT-1", new BigDecimal("50"))).thenReturn(List.of(req()));
        when(bom.checkMaterialAvailability(eq(F), anyList()))
                .thenReturn(new MaterialCheckResult(true, List.of(), List.of()));

        ResourceCheckResult r = impact.checkMaterialAvailability(F, "PT-1", new BigDecimal("50"));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.isVerified()).isTrue();
    }

    // --------------------------------------------------------------- equipment

    @Test
    void equipmentHasNoRealDataSoItIsUnverified() {
        ResourceCheckResult r = impact.checkEquipmentAvailability(F, LocalDateTime.now(), LocalDateTime.now().plusHours(2), null);

        System.out.println("EQUIPMENT available=" + r.isAvailable() + " verified=" + r.isVerified());
        assertThat(r.isAvailable()).isFalse();
        assertThat(r.isVerified()).isFalse();
        assertThat(r.getMessage()).startsWith("设备未验证");
    }

    // ------------------------------------------------- analyzeInsertImpact entry

    private InsertSlot slot() {
        LocalDateTime start = LocalDateTime.now().plusHours(2);
        return InsertSlot.builder().id("S-1").factoryId(F).productionLineId("default")
                .startTime(start).endTime(start.plusHours(2))
                .availableCapacity(new BigDecimal("200")).impactLevel("none")
                .requiredWorkers(4).availableWorkers(4).status("available").build();
    }

    private static GetInsertSlotsRequest analyzeReq() {
        GetInsertSlotsRequest r = new GetInsertSlotsRequest();
        r.setProductTypeId("PT-1");
        r.setRequiredQuantity(new BigDecimal("50"));
        return r;
    }

    /** ImpactAnalysisService whose resource checks are the REAL impl and the rest are inert stubs. */
    private ImpactAnalysisService impactWithRealResourceChecks() {
        ImpactAnalysisService svc = mock(ImpactAnalysisService.class);
        when(svc.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(ChainImpactResult.builder().impactLevel("none").build());
        when(svc.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new RecommendScoreResult());
        when(svc.checkMaterialAvailability(anyString(), any(), any()))
                .thenAnswer(inv -> impact.checkMaterialAvailability(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(svc.checkEquipmentAvailability(anyString(), any(), any(), any()))
                .thenAnswer(inv -> impact.checkEquipmentAvailability(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        return svc;
    }

    private UrgentInsertServiceImpl urgent(InsertSlotRepository slots, ImpactAnalysisService svc, ProductionPlanRepository plans) {
        return UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slots,
                ImpactAnalysisService.class, svc,
                ProductionPlanRepository.class, plans,
                LineCapacityEquipmentCheck.class, lineChecks,
                ObjectMapper.class, new ObjectMapper()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeWithNoActiveBomIsNotFeasibleAndSaysUnverified() {
        when(bom.expandBOM(anyString(), anyString(), any()))
                .thenThrow(new BusinessException(409, "产品尚无已激活的新版 BOM 配方: PT-1"));
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        when(slots.findByIdAndFactoryId("S-1", F)).thenReturn(Optional.of(slot()));

        Map<String, Object> out = urgent(slots, impactWithRealResourceChecks(), mock(ProductionPlanRepository.class))
                .analyzeInsertImpact(F, "S-1", analyzeReq());

        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        System.out.println("ANALYZE_NO_BOM isFeasible=" + out.get("isFeasible") + " status=" + out.get("feasibilityStatus")
                + " materialVerified=" + rs.get("materialVerified"));
        assertThat(out.get("isFeasible")).isEqualTo(false);
        assertThat(out.get("feasibilityStatus")).isEqualTo("UNVERIFIED");
        assertThat(out.get("resourcesVerified")).isEqualTo(false);
        assertThat(rs.get("materialVerified")).isEqualTo(false);
        assertThat((List<String>) out.get("unverifiedReasons")).anyMatch(s -> s.startsWith("原料未验证"));
    }

    @Test
    void analyzeWithZeroStockIsInfeasible() {
        stockIsZero();
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        when(slots.findByIdAndFactoryId("S-1", F)).thenReturn(Optional.of(slot()));

        Map<String, Object> out = urgent(slots, impactWithRealResourceChecks(), mock(ProductionPlanRepository.class))
                .analyzeInsertImpact(F, "S-1", analyzeReq());

        assertThat(out.get("isFeasible")).isEqualTo(false);
        assertThat(out.get("feasibilityStatus")).isEqualTo("INFEASIBLE");
        assertThat((String) out.get("infeasibleReason")).contains("原料不足");
    }

    private ImpactAnalysisService impactWithEnoughMaterial() {
        ImpactAnalysisService svc = mock(ImpactAnalysisService.class);
        when(svc.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(ChainImpactResult.builder().impactLevel("none").build());
        when(svc.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new RecommendScoreResult());
        when(svc.checkMaterialAvailability(anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.available("MATERIAL", new BigDecimal("100"), new BigDecimal("50")));
        return svc;
    }

    private Map<String, Object> analyzeConfigured(String unit) {
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        when(slots.findByIdAndFactoryId("S-1", F)).thenReturn(Optional.of(slot()));
        GetInsertSlotsRequest req = analyzeReq();
        req.setQuantityUnit(unit);
        return urgent(slots, impactWithEnoughMaterial(), mock(ProductionPlanRepository.class))
                .analyzeInsertImpact(F, "S-1", req);
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeNeverSaysFeasibleWhileAnyResourceIsUnverified() {
        // 原料 / 产能 / 设备都「核对过且够」, 人员仍是配置估算 ⇒ 不得为 true
        configuredInPieces("30");   // 2 小时 60 件 >= 50 件

        Map<String, Object> out = analyzeConfigured("PIECE");

        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        System.out.println("ANALYZE_CONFIGURED capacity=" + rs.get("capacityStatus") + " equipment=" + rs.get("equipmentStatus")
                + " status=" + out.get("feasibilityStatus") + " unverified=" + out.get("unverifiedReasons"));
        assertThat(rs.get("capacityStatus")).isEqualTo("READY");
        assertThat(rs.get("equipmentStatus")).isEqualTo("READY");
        assertThat(out.get("isFeasible")).isEqualTo(false);
        assertThat(out.get("feasibilityStatus")).isEqualTo("UNVERIFIED");
        assertThat((List<String>) out.get("unverifiedReasons")).containsExactly(UrgentInsertServiceImpl.WORKER_UNVERIFIED_REASON);
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeWithoutConfigsSaysCapacityAndEquipmentAreMissingNotTheSlotsDefaultKg() {
        // 时段上挂着按默认 kg 估的 200, 订单 50 —— 旧逻辑会算「产能够」; 现在没配置就是未验证
        Map<String, Object> out = analyzeConfigured("KG");

        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs.get("capacityStatus")).isEqualTo("MISSING");
        assertThat(rs.get("equipmentStatus")).isEqualTo("MISSING");
        assertThat(out.get("feasibilityStatus")).isEqualTo("UNVERIFIED");
        assertThat((List<String>) out.get("unverifiedReasons"))
                .anyMatch(s -> s.startsWith("产能未验证"))
                .anyMatch(s -> s.startsWith("设备未验证"));
    }

    @Test
    void analyzeWithTooLittleConfiguredCapacityIsInfeasibleInTheConfiguredUnit() {
        configuredInPieces("10");   // 2 小时 20 件 < 50 件

        Map<String, Object> out = analyzeConfigured("PIECE");

        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs.get("capacityStatus")).isEqualTo("CONFLICT");
        assertThat(out.get("feasibilityStatus")).isEqualTo("INFEASIBLE");
        assertThat(out.get("riskLevel")).isEqualTo("critical");
        assertThat((String) out.get("infeasibleReason")).contains("产能不足: 需要 50 件").doesNotContain("kg");
    }

    @Test
    void analyzeInAnotherUnitThanConfiguredIsMissingNotConverted() {
        configuredInPieces("1000");

        Map<String, Object> out = analyzeConfigured("KG");

        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) out.get("resourceStatus");
        assertThat(rs.get("capacityStatus")).isEqualTo("MISSING");
        assertThat((String) rs.get("capacityMessage")).contains("包装线 配的是 件/小时");
    }

    @Test
    void analyzeOtherFactorysSlotIs404() {
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        when(slots.findByIdAndFactoryId("S-1", "F-OTHER")).thenReturn(Optional.empty());
        when(slots.findById("S-1")).thenReturn(Optional.of(slot()));

        assertThatThrownBy(() -> urgent(slots, impactWithRealResourceChecks(), mock(ProductionPlanRepository.class))
                .analyzeInsertImpact("F-OTHER", "S-1", analyzeReq()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ------------------------------------------------------------ confirm/force

    private static ConfirmUrgentInsertRequest confirmReq(boolean force) {
        ConfirmUrgentInsertRequest r = new ConfirmUrgentInsertRequest();
        r.setSlotId("S-1");
        r.setProductTypeId("PT-1");
        r.setPlannedQuantity(new BigDecimal("50"));
        r.setForceInsert(force);
        return r;
    }

    @Test
    void confirmAndForceOnOtherFactorysSlotAre404AndWriteNothing() {
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        // findById would happily return it — the service must not use it on write paths
        when(slots.findById("S-1")).thenReturn(Optional.of(slot()));
        when(slots.findByIdAndFactoryId("S-1", "F-OTHER")).thenReturn(Optional.empty());
        ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
        UrgentInsertServiceImpl svc = urgent(slots, mock(ImpactAnalysisService.class), plans);

        assertThatThrownBy(() -> svc.confirmInsert("F-OTHER", 1L, confirmReq(false)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> svc.forceInsert("F-OTHER", 1L, confirmReq(true)))
                .isInstanceOf(EntityNotFoundException.class);

        verify(slots, never()).save(any(InsertSlot.class));
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(plans, never()).save(any(ProductionPlan.class));
    }

    @Test
    void lostClaimIs409AndNoPlanIsSaved() {
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        when(slots.findByIdAndFactoryId("S-1", F)).thenReturn(Optional.of(slot()));
        when(slots.claimAvailableSlot(eq("S-1"), eq(F), any())).thenReturn(0);
        ProductionPlanRepository plans = mock(ProductionPlanRepository.class);

        assertThatThrownBy(() -> urgent(slots, mock(ImpactAnalysisService.class), plans)
                .confirmInsert(F, 1L, confirmReq(false)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        verify(plans, never()).save(any(ProductionPlan.class));
    }
}

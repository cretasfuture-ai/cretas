package com.cretas.aims.service.production;

import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1 (2026-06-12 客户走查) — 开工无条件化: 原料库存预检只记录预警, 不阻塞开工.
 *
 * <p>测试覆盖:
 * <ul>
 *   <li>库存充足 → 开始成功</li>
 *   <li>单原料/多原料库存不足 → 仍开始成功, 只记录预警</li>
 *   <li>库存恰好等于需求 → 开始成功</li>
 *   <li>无 BOM 配置 → skip 校验, 允许开始</li>
 *   <li>plan.plannedQuantity 为空 → skip 校验, 允许开始</li>
 *   <li>已 IN_PROGRESS 状态 → 状态校验先报错 (库存校验未被触发)</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-10
 */
@DisplayName("S4-08 缺料开工闸 × 转批次 create-batch (PR 检查必跑: *ContractTest)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShortageCreateBatchGateContractTest {

    private static final String FACTORY_ID = "F001";
    private static final String PLAN_ID = "PP-2026-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        setField(service, "bomRecipeItemRepository", bomRecipeItemRepository);
        setField(service, "bomRecipeRepository", bomRecipeRepository);
        // 2026-08-02: 开工现在会把批次一起建出来 (两个「开工」入口收敛), 所以这条路径
        // 需要工序任务服务。此前 startProduction 只翻状态, 不碰它。
        setField(service, "workProcessTaskService", workProcessTaskService);
        setField(service, "wipInventoryService", wipInventoryService);

        // toDTOWithConversionInfo 的依赖默认 stub — 成功 path 才会用上
        ProductionPlanDTO emptyDto = new ProductionPlanDTO();
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class))).thenReturn(emptyDto);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        // 批次物化的默认 stub: 本类关心的是「库存不足会不会拦住开工」, 不是批次内容
        lenient().when(productionBatchRepository.save(any(com.cretas.aims.entity.ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(workProcessTaskService.spawnTasks(
                        anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
    }

    /**
     * 构造一个最简 PENDING 计划。
     *
     * <p>⚠️ 2026-08-02 起必须带 {@code plannedUnit}: 开工会一并物化批次, 而批次没有单位
     * 就生不出来 ({@code requireProductionUnit} 422)。此前 fixture 不设单位也能「开工成功」——
     * 但那种计划<b>永远转不成批次</b> (createBatchFromPlan 一直要求单位), 开完就是死的。
     * prod 实测 36 个 PENDING 计划全部有单位, 这个 fixture 之前描述的是一个现实中不存在、
     * 且一旦出现就走不下去的状态。
     */
    private ProductionPlan pendingPlan(BigDecimal plannedQuantity) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PP-2026-001");
        plan.setPlannedQuantity(plannedQuantity);
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        return plan;
    }

    /** 构造一个 BOM item (yieldRate=100% → actualQuantity == standardQuantity). */
    private BomRecipeItem bomItem(String materialTypeId, String materialName,
                            BigDecimal standardQuantity, String unit) {
        return bomItem(materialTypeId, materialName, standardQuantity, new BigDecimal("100.00"), unit);
    }

    private BomRecipeItem bomItem(String materialTypeId, String materialName,
                            BigDecimal standardQuantity, BigDecimal yieldRate, String unit) {
        return BomRecipeItem.builder()
                .factoryId(FACTORY_ID)
                .recipeId("RECIPE-" + PRODUCT_TYPE_ID)
                .materialTypeId(materialTypeId)
                .materialName(materialName)
                .standardQuantity(standardQuantity)
                .yieldRate(yieldRate)
                .unit(unit)
                .build();
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ==================== S4-08 真实的缺料闸 + 真实的开工路径 ====================

    /** 一个有未解决缺料实例的销售订单计划, 接上真实的 ShortageStartGate (只有仓库是桩)。 */
    private final java.util.List<com.cretas.aims.entity.shortage.ShortageCaseEvent> caseEvents = new java.util.ArrayList<>();

    private com.cretas.aims.repository.FactorySettingsRepository realGate(Boolean switchOn) {
        com.cretas.aims.repository.FactorySettingsRepository settings =
                org.mockito.Mockito.mock(com.cretas.aims.repository.FactorySettingsRepository.class);
        com.cretas.aims.repository.shortage.ShortageCaseRepository cases =
                org.mockito.Mockito.mock(com.cretas.aims.repository.shortage.ShortageCaseRepository.class);
        com.cretas.aims.repository.shortage.ShortageCaseLineRepository lines =
                org.mockito.Mockito.mock(com.cretas.aims.repository.shortage.ShortageCaseLineRepository.class);
        com.cretas.aims.repository.shortage.ShortageCaseEventRepository events =
                org.mockito.Mockito.mock(com.cretas.aims.repository.shortage.ShortageCaseEventRepository.class);
        when(settings.findBlockStartOnOpenShortageByFactoryId(FACTORY_ID)).thenReturn(switchOn);

        com.cretas.aims.entity.shortage.ShortageCase c = new com.cretas.aims.entity.shortage.ShortageCase();
        c.setId("CASE-1");
        c.setFactoryId(FACTORY_ID);
        c.setStage(com.cretas.aims.entity.shortage.ShortageCase.Stage.EXECUTING);
        when(cases.findOpenForUpdate(FACTORY_ID, com.cretas.aims.entity.shortage.ShortageCase.SourceType.SALES_ORDER, "SO-1"))
                .thenReturn(Optional.of(c));
        com.cretas.aims.entity.shortage.ShortageCaseLine gap = new com.cretas.aims.entity.shortage.ShortageCaseLine();
        gap.setMaterialTypeId("MT-A");
        gap.setMaterialName("面粉");
        gap.setUnit("kg");
        gap.setGapQty(new BigDecimal("12"));
        when(lines.findByCaseIdOrderByIdAsc("CASE-1")).thenReturn(List.of(gap));
        when(events.save(any(com.cretas.aims.entity.shortage.ShortageCaseEvent.class))).thenAnswer(inv -> {
            caseEvents.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(events.findByCaseIdOrderByIdAsc("CASE-1")).thenAnswer(inv -> List.copyOf(caseEvents));
        setField(service, "shortageStartGate",
                new com.cretas.aims.service.shortage.ShortageStartGate(settings, cases, lines, events));
        lastEvents = events;
        return settings;
    }

    private com.cretas.aims.repository.shortage.ShortageCaseEventRepository lastEvents;

    private ProductionPlan orderPlanWithOpenShortage() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        plan.setSourceOrderId("SO-1");
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        return plan;
    }

    // ==================== create-batch 也是开工 (2026-11-03 验收 D-S410-01) ====================
    // 网页「逐道录入」「下发 APP 报工」都先调 create-batch, 它把待处理计划翻成生产中并建批次 ——
    // 曾经不过缺料闸, 开关开着、缺料没解决也照样开工。

    @Test
    @DisplayName("开关开 + 缺料未解决: create-batch 409 SHORTAGE_CASE_OPEN, 计划保持待处理, 一个批次都不建")
    void createBatch_switchOn_refusedWithNoBatch() {
        realGate(true);
        ProductionPlan plan = orderPlanWithOpenShortage();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createBatchFromPlan(FACTORY_ID, PLAN_ID, 9L, null));

        assertEquals(409, e.getCode());
        assertEquals(com.cretas.aims.service.shortage.ShortageStartGate.CODE, e.getErrorCode());
        assertEquals("CASE-1", e.getHintTarget());
        assertEquals(ProductionPlanStatus.PENDING, plan.getStatus());
        org.mockito.Mockito.verify(productionBatchRepository, org.mockito.Mockito.never())
                .save(any(com.cretas.aims.entity.ProductionBatch.class));
        org.mockito.Mockito.verify(productionPlanRepository, org.mockito.Mockito.never()).save(any(ProductionPlan.class));
    }

    @Test
    @DisplayName("对照: 开关关 (N1 默认) 时 create-batch 照常建批次并开工 —— 两参老调用也一样")
    void createBatch_switchOff_createsTheBatchAndStarts() {
        realGate(false);
        ProductionPlan plan = orderPlanWithOpenShortage();

        assertNotNull(service.createBatchFromPlan(FACTORY_ID, PLAN_ID));

        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        org.mockito.Mockito.verify(productionBatchRepository, org.mockito.Mockito.atLeastOnce())
                .save(any(com.cretas.aims.entity.ProductionBatch.class));
        assertTrue(caseEvents.isEmpty(), "开关关着时不写任何处理记录");
    }

    @Test
    @DisplayName("开关开 + 原因: create-batch 照常建批次, 原因记进缺料实例的处理记录且读得回来")
    void createBatch_switchOnWithReason_startsAndTheReasonIsReadBack() {
        realGate(true);
        ProductionPlan plan = orderPlanWithOpenShortage();

        assertNotNull(service.createBatchFromPlan(FACTORY_ID, PLAN_ID, 9L, "客户加急, 先下发 APP 报工"));

        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        List<com.cretas.aims.entity.shortage.ShortageCaseEvent> trail = lastEvents.findByCaseIdOrderByIdAsc("CASE-1");
        assertEquals(1, trail.size(), "一次开工只记一次 (startProduction 不经过 create-batch, 不会重复记)");
        assertEquals("FORCED_START", trail.get(0).getTriggerReason());
        assertTrue(trail.get(0).getDetail().contains("客户加急, 先下发 APP 报工"), trail.get(0).getDetail());
        assertEquals(9L, trail.get(0).getActorId());
    }
}

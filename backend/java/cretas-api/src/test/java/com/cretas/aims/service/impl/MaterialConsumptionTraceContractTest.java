package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MaterialConsumption 的 notes 落库, 以及 productionPlanId 的前置存在性校验。
 *
 * 背景: notes 从来没被 set 过 —— 生产库 39 条消耗记录该列全为空, 而两个请求 DTO
 * 一直公布着这个字段。另外 production_plan_id 有外键指向 production_plans,
 * 接通 /consume 的计划ID 透传之后, 传一个不存在的 ID 会在落库时炸成通用 500;
 * 现存唯一调用方 (RN BatchOperationsTestScreen) 传的正是硬编码的 "1"。
 * 所以「接通」和「校验」必须同时做, 否则只是把静默丢弃换成 FK 500。
 */
@DisplayName("消耗记录的 notes 落库与计划引用校验")
class MaterialConsumptionTraceContractTest {

    private MaterialBatchRepository batchRepository;
    private MaterialConsumptionRepository consumptionRepository;
    private ProductionPlanRepository planRepository;
    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(MaterialBatchRepository.class);
        consumptionRepository = mock(MaterialConsumptionRepository.class);
        planRepository = mock(ProductionPlanRepository.class);
        service = new MaterialBatchServiceImpl(
                batchRepository,
                mock(MaterialBatchAdjustmentRepository.class),
                mock(RawMaterialTypeRepository.class),
                mock(MaterialBatchMapper.class),
                consumptionRepository,
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher",
                mock(InventoryLowStockEventPublisher.class));
        ReflectionTestUtils.setField(service, "productionPlanRepository", planRepository);
    }

    private MaterialBatch batch() {
        MaterialBatch b = new MaterialBatch();
        b.setId("B-1");
        b.setFactoryId("F006");
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(new BigDecimal("50"));
        b.setUnitPrice(new BigDecimal("8.50"));
        when(batchRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.of(b));
        when(batchRepository.findById(anyString())).thenReturn(Optional.of(b));
        return b;
    }

    private MaterialConsumption savedConsumption() {
        ArgumentCaptor<MaterialConsumption> c = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepository).save(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("useBatchMaterial 把 notes 写进消耗记录")
    void useWritesNotes() {
        batch();
        when(planRepository.existsById("PLAN-1")).thenReturn(true);

        service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), "PLAN-1", 42L, "用途: 试制 — 第二批");

        assertEquals("用途: 试制 — 第二批", savedConsumption().getNotes());
    }

    @Test
    @DisplayName("consumeBatchMaterial 把 notes 写进消耗记录")
    void consumeWritesNotes() {
        batch();
        when(planRepository.existsById("PLAN-1")).thenReturn(true);

        service.consumeBatchMaterial("F006", "B-1", new BigDecimal("10"), "PLAN-1", 42L, "产线甲班消耗");

        assertEquals("产线甲班消耗", savedConsumption().getNotes());
    }

    @Test
    @DisplayName("5 参旧签名仍可用, notes 为 null（不写空串）")
    void legacyFiveArgOverloadStillWorks() {
        batch();
        when(planRepository.existsById("PLAN-1")).thenReturn(true);

        service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), "PLAN-1", 42L);

        assertNull(savedConsumption().getNotes());
    }

    @Test
    @DisplayName("计划ID 不存在 → 404, 且在任何写入之前")
    void nonExistentPlanIsRejectedBeforeAnyWrite() {
        batch();
        when(planRepository.existsById("1")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.consumeBatchMaterial("F006", "B-1", new BigDecimal("10"), "1", 42L, null));

        assertEquals(404, e.getCode());
        assertEquals("productionPlanId", e.getHintTarget());
        verify(consumptionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(batchRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("不传计划ID 是合法的, 不去查计划表 —— 领料出库那条路就不传")
    void nullPlanIsAllowedAndSkipsTheLookup() {
        MaterialBatch b = batch();

        service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), null, 42L, "无计划领料");

        verify(planRepository, never()).existsById(anyString());
        assertEquals(0, new BigDecimal("10").compareTo(b.getUsedQuantity()), "扣减照常发生");
    }

    @Test
    @DisplayName("reserve 传不存在的计划 → 404, 而不是让 usage 行的外键炸成 500")
    void reserveRejectsNonExistentPlan() {
        batch();
        when(planRepository.existsById("1")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.reserveBatchMaterial("F006", "B-1", new BigDecimal("10"), "1"));

        assertEquals(404, e.getCode());
        verify(batchRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ══════════════════════════════════════════════════════════════════
    // 🔴 2026-08-27: 这一组替换了原来那条「现状钉住: 不带计划就不写消耗记录」。
    //
    // 原断言是 `verify(consumptionRepository, never()).save(any())`, 它守的理由是
    // 「不带计划的领料很可能已由 FactoryMaterialRequisition 记过账, 补写会重复计成本」。
    // 那条理由**查证后不成立**: 领料链路写的那行 sourceType 是 MATERIAL_RETURN(退料),
    // 与手工出库不是同一个事件。
    //
    // 而现状已被判定为缺陷并修掉了 —— 生产实测(批次 a5e3740e…, 出库 0.01kg):
    // material_consumptions 零新增、operation_logs 零行, 变的只有 usedQuantity 和
    // lastUsedAt(单个可变字段, 下次出库就被覆盖) ⇒ **连是谁出的都没记**。
    //
    // ⚠️ 这是规则里的形态 C‴: 断言好好的, 是**需求变了**。症状是「红得理直气壮」,
    //    最容易被读成「我改错了」然后把改动回退掉。
    // ⛔ 改断言不许滑成删断言 ⇒ 把守的东西从「不写行」抬到「留痕的性质」, 并配阴性对照。
    //
    // ⚠️ 而且这条断言**红了很久没人看见** —— 它当时不在任何 CI 选择器里
    //    (类名不以 *ContractTest / *StartupGuardTest 结尾)。本次连同改名一起解决。
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("🔴 不带计划的手工出库也必须留痕 —— 而且标成 MANUAL_ISSUE")
    void manualIssueWithoutPlanStillLeavesATrace() {
        batch();

        service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), null, 42L, "无计划领料");

        MaterialConsumption saved = savedConsumption();
        assertNull(saved.getProductionPlanId(), "不带计划就是不带, 不许凭空塞一个");
        // 单独标来源, 别和报工领用混在一起 —— 下游要分得清哪些是手工出库
        assertEquals("MANUAL_ISSUE", saved.getSourceType());
        assertEquals(42L, saved.getRecordedBy(), "记不下「是谁」的出库不许静默通过");
        assertEquals("无计划领料", saved.getNotes());
    }

    @Test
    @DisplayName("阴性对照: 带计划时来源是 PRODUCTION_ISSUE, 不是 MANUAL_ISSUE")
    void plannedIssueIsTaggedDifferently() {
        batch();
        when(planRepository.existsById("P-1")).thenReturn(true);

        service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), "P-1", 42L, "报工领用");

        MaterialConsumption saved = savedConsumption();
        // 没有这一条, 上面那句 MANUAL_ISSUE 可能只是「来源字段恒为 MANUAL_ISSUE」
        assertEquals("PRODUCTION_ISSUE", saved.getSourceType());
        assertEquals("P-1", saved.getProductionPlanId());
    }

    @Test
    @DisplayName("🔴 认不出操作人的出库必须被拒 —— 留痕的前提是知道是谁")
    void issueWithoutOperatorIsRejected() {
        batch();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.useBatchMaterial("F006", "B-1", new BigDecimal("10"), null, null, "匿名领料"));

        assertEquals(401, e.getCode());
        // ⛔ 拒了就不许留下半条记录
        verify(consumptionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("对照: consumeBatchMaterial 是无条件写的 —— 两条路径在这点上并不一致")
    void consumeWritesTheRowEvenWithoutAPlan() {
        batch();

        service.consumeBatchMaterial("F006", "B-1", new BigDecimal("10"), null, 42L, "无计划消耗");

        assertEquals("无计划消耗", savedConsumption().getNotes());
    }
}

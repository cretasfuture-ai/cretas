package com.cretas.aims.service.quality.release;

import com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool;
import com.cretas.aims.controller.MaterialBatchController;
import com.cretas.aims.dto.material.UpdateBatchStatusRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.cretas.aims.service.quality.FactoryRecallService;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交接单 2026-11-11 第二节第 1 条: 在制批次 (MaterialBatch, sourceDocType = PRODUCTION_BATCH) 判不合格之后,
 * 别的入口不能把它改回能用。Steve 2026-11-04 质量裁定: 不合格批次只能走处置和复核改状态。
 *
 * <p>#1571 的闸只查 {@code RAW_MATERIAL_BATCH} 类型的放行记录; 在制批次在质检放行判不合格写的是
 * {@code WIP_MATERIAL_BATCH} 记录, 一条都查不到 ⇒ 仓库页 / 放行决策工具 / 质检处置放行都能把它改回可用。
 * 另一种判不合格来源 —— 工序时限超时、复检判废 ({@code ProcessTimeLimitService.writeOff} 写 DEFECTIVE) ——
 * 原料和在制批次都没被闸认出来。
 *
 * <p>第一条用例走产品真实入口: {@link MaterialBatchController#updateBatchStatus} (PUT /material-batches/{id}/status)
 * → 真实的 {@link MaterialBatchServiceImpl}。
 */
@DisplayName("在制批次判不合格: 别的入口不能改回可用 (交接单 11-11 二.1)")
class WipBatchQcRejectionGuardContractTest {

    private static final String F = "F-WIP";

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final QcReleaseRecordRepository records = mock(QcReleaseRecordRepository.class);
    private final ProcessTimeLimitEventRepository timeLimits = mock(ProcessTimeLimitEventRepository.class);
    /** 这批的放行记录 (按时间倒序, 与仓储查询同序)。只挂在 WIP 类型上: 闸若还按原料类型查, 就一条都拿不到。 */
    private final List<QcReleaseRecord> history = new ArrayList<>();
    /** 这批的超时判废事件。 */
    private final List<ProcessTimeLimitEvent> timeLimitRejections = new ArrayList<>();

    private static final com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange MANUAL =
            new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                    com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE,
                    "复检合格", 7L, "quality_manager");

    private MaterialBatchServiceImpl warehouse;
    private MaterialBatchController controller;
    private ReleaseDecisionTool tool;
    private QualityDispositionInventoryService disposition;
    private MaterialBatch batch;

    @BeforeEach
    void setUp() {
        batch = new MaterialBatch();
        batch.setId("WIP-1");
        batch.setFactoryId(F);
        batch.setMaterialTypeId("RMT-SEMI");
        batch.setBatchNumber("WIP-PB-1");
        batch.setSourceDocType(QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        batch.setSourceDocId("101");
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        when(batches.findById("WIP-1")).thenReturn(Optional.of(batch));
        when(batches.findByIdAndFactoryId("WIP-1", F)).thenReturn(Optional.of(batch));
        when(batches.findByFactoryIdAndBatchNumber(F, "WIP-PB-1")).thenReturn(Optional.of(batch));
        when(batches.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(records.findByTarget(F, QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "WIP-1"))
                .thenAnswer(i -> new ArrayList<>(history));
        when(records.findByTarget(eq(F), eq(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH), anyString()))
                .thenReturn(List.of());
        when(timeLimits.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(eq(F), anyString(), eq("WIP-1"),
                eq(ProcessTimeLimitEvent.Status.REJECTED))).thenAnswer(i ->
                ProcessTimeLimitService.TargetType.WIP_MATERIAL_BATCH.equals(i.getArgument(1))
                        ? new ArrayList<>(timeLimitRejections) : List.of());

        warehouse = new MaterialBatchServiceImpl(batches, null, null, mock(MaterialBatchMapper.class),
                null, null, null, null);
        ReflectionTestUtils.setField(warehouse, "qcReleaseRecordRepository", records);
        ReflectionTestUtils.setField(warehouse, "processTimeLimitEventRepository", timeLimits);
        // 纪律排查 A3 放行闸叠加在本闸之后: 这里测「判不合格不能改回可用」, 放行规则配成允许手动放行,
        // 让失败只可能来自本闸
        ReflectionTestUtils.setField(warehouse, "materialBatchReleaseGuard",
                new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard(
                        com.cretas.aims.service.discipline.DisciplineFixtures.policy(Map.of(
                                com.cretas.aims.service.canvas.ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL")),
                        mock(com.cretas.aims.repository.material.MaterialBatchReleaseRepository.class)));
        controller = new MaterialBatchController(warehouse, mock(MobileService.class),
                mock(PriceMaskResolver.class), mock(OpeningInventoryService.class));

        tool = new ReleaseDecisionTool();
        ReflectionTestUtils.setField(tool, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(tool, "qcReleaseRecordRepository", records);
        ReflectionTestUtils.setField(tool, "processTimeLimitEventRepository", timeLimits);
        com.cretas.aims.ai.tool.ToolRbacGuard qcRbac = mock(com.cretas.aims.ai.tool.ToolRbacGuard.class);
        when(qcRbac.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
        ReflectionTestUtils.setField(tool, "rbacGuard", qcRbac);
        ReflectionTestUtils.setField(tool, "releaseGuard",
                mock(com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.class));

        disposition = new QualityDispositionInventoryService();
        ReflectionTestUtils.setField(disposition, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(disposition, "productionBatchRepository", mock(ProductionBatchRepository.class));
        ReflectionTestUtils.setField(disposition, "finishedGoodsBatchRepository", mock(FinishedGoodsBatchRepository.class));
        ReflectionTestUtils.setField(disposition, "factoryRecallService", mock(FactoryRecallService.class));
        ReflectionTestUtils.setField(disposition, "qcReleaseRecordRepository", records);
        ReflectionTestUtils.setField(disposition, "processTimeLimitEventRepository", timeLimits);
        ReflectionTestUtils.setField(disposition, "materialBatchReleaseGuard",
                mock(com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.class));
    }

    private QcReleaseRecord record(String decision, LocalDateTime at) {
        return QcReleaseRecord.builder().factoryId(F).targetType(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH)
                .targetId("WIP-1").decision(decision).conclusion("x").releasedBy(9L).releasedAt(at).build();
    }

    private void qcRejected() {
        history.add(0, record(QcReleaseRecord.Decision.REJECTED, LocalDateTime.now().minusMinutes(5)));
    }

    private void timeLimitRejected(LocalDateTime decidedAt) {
        ProcessTimeLimitEvent e = new ProcessTimeLimitEvent();
        e.setFactoryId(F);
        e.setTargetType(ProcessTimeLimitService.TargetType.WIP_MATERIAL_BATCH);
        e.setTargetId("WIP-1");
        e.setStatus(ProcessTimeLimitEvent.Status.REJECTED);
        e.setDecidedAt(decidedAt);
        timeLimitRejections.add(e);
    }

    private static void assertRejectedConflict(Throwable t) {
        assertThat(t).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getErrorCode()).isEqualTo(MaterialQcRejectionGuard.CODE);
            assertThat(e.getMessage()).contains("WIP-PB-1").contains("在制批次");
        });
    }

    /** 产品真实入口: PUT /material-batches/{id}/status。 */
    private void putStatus(MaterialBatchStatus target) {
        UpdateBatchStatusRequest body = new UpdateBatchStatusRequest();
        body.setStatus(target.name());
        body.setReason("仓管改状态");
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("userId", 7L);
        http.setAttribute("role", "warehouse_manager");
        controller.updateBatchStatus(F, "WIP-1", body, http);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeTool(String method) throws Exception {
        Method m = null;
        for (Class<?> c = tool.getClass(); c != null && m == null; c = c.getSuperclass()) {
            for (Method x : c.getDeclaredMethods()) {
                if (x.getName().equals(method) && x.getParameterCount() == 3) {
                    m = x;
                }
            }
        }
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", F);
        ctx.put("userId", 1L);
        try {
            return (Map<String, Object>) m.invoke(tool, F,
                    Map.of("batchNumber", "WIP-PB-1", "decision", "RELEASED", "notes", "复检合格"), ctx);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private QualityInspection inspection() {
        QualityInspection qi = new QualityInspection();
        qi.setId("qi-wip");
        qi.setFactoryId(F);
        qi.setMaterialBatchId("WIP-1");
        return qi;
    }

    @Test
    @DisplayName("真实入口 PUT /material-batches/{id}/status: 质检判不合格的在制批次改成可用 → 409, 状态不变, 不落库")
    void controllerCannotMakeQcRejectedWipUsable() {
        qcRejected();
        assertThatThrownBy(() -> putStatus(MaterialBatchStatus.AVAILABLE))
                .satisfies(WipBatchQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("仓库改状态: 鲜品 / 冻品 / 生产预留 / 库存中 / 预留 也都拦 (都是能领用投产的状态)")
    void warehouseCannotMakeItUsableUnderAnyName() {
        qcRejected();
        for (MaterialBatchStatus target : List.of(MaterialBatchStatus.FRESH, MaterialBatchStatus.FROZEN,
                MaterialBatchStatus.PRODUCING_RESERVED, MaterialBatchStatus.IN_STOCK, MaterialBatchStatus.RESERVED)) {
            assertThatThrownBy(() -> warehouse.updateBatchStatus(F, "WIP-1", target, MANUAL))
                    .as("%s", target).satisfies(WipBatchQcRejectionGuardContractTest::assertRejectedConflict);
        }
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("阴性对照: 送回质检中 / 报废照常; 复检后重新放行 (最近一条 RELEASED) 可改成可用; 从没判过的照常")
    void negativeControls() {
        qcRejected();
        putStatus(MaterialBatchStatus.INSPECTING);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        putStatus(MaterialBatchStatus.SCRAPPED);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.SCRAPPED);

        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        history.add(0, record(QcReleaseRecord.Decision.RELEASED, LocalDateTime.now()));
        putStatus(MaterialBatchStatus.AVAILABLE);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        history.clear();
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        putStatus(MaterialBatchStatus.AVAILABLE);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("放行决策工具: 预览 canDo=false; 执行 (绕过预览) 409, 状态不变, 不落库")
    void releaseDecisionToolCannotReleaseIt() throws Exception {
        qcRejected();
        Map<String, Object> preview = invokeTool("doPreview");
        assertThat(preview.get("canDo")).isEqualTo(false);
        assertThat(preview.get("status")).isEqualTo(MaterialQcRejectionGuard.CODE);

        assertThatThrownBy(() -> invokeTool("doExecute"))
                .satisfies(WipBatchQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("质检处置放行: 409, 状态不变; 阴性对照: 没判过的照常翻成可用")
    void dispositionReleaseCannotFlipIt() {
        qcRejected();
        assertThatThrownBy(() -> disposition.applyDisposition(inspection(), DispositionAction.RELEASE, true))
                .satisfies(WipBatchQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));

        history.clear();
        disposition.applyDisposition(inspection(), DispositionAction.RELEASE, true);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("工序超时复检判废的在制批次: 真实入口改成可用 → 409; 判废之后在质检放行复检放行 (更新的 RELEASED) 才放")
    void timeLimitRejectionAlsoBlocks() {
        LocalDateTime rejectedAt = LocalDateTime.now().minusHours(1);
        timeLimitRejected(rejectedAt);
        // 判废之前的一条旧放行记录不能盖掉判废
        history.add(record(QcReleaseRecord.Decision.RELEASED, rejectedAt.minusHours(2)));
        assertThatThrownBy(() -> putStatus(MaterialBatchStatus.AVAILABLE))
                .satisfies(WipBatchQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);

        history.add(0, record(QcReleaseRecord.Decision.RELEASED, rejectedAt.plusMinutes(30)));
        putStatus(MaterialBatchStatus.AVAILABLE);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("超时判废同样管原料收货批次 (#1571 只看质检放行记录, 没看时限事件)")
    void timeLimitRejectionBlocksRawBatchToo() {
        batch.setSourceDocType("PURCHASE_RECEIVE");
        timeLimitRejected(LocalDateTime.now().minusMinutes(10));
        assertThatThrownBy(() -> putStatus(MaterialBatchStatus.AVAILABLE))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(MaterialQcRejectionGuard.CODE);
                    assertThat(e.getMessage()).contains("原料批次");
                });
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
    }

    @Test
    @DisplayName("时限事件仓储没注入: 直接报错, 不当「没判过」放回可用")
    void missingTimeLimitRepositoryFailsLoud() {
        ReflectionTestUtils.setField(warehouse, "processTimeLimitEventRepository", null);
        assertThatThrownBy(() -> putStatus(MaterialBatchStatus.AVAILABLE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
    }

    @Test
    @DisplayName("两步洗白 第 1 步: 不良品不能被预留 (预留满 → DEPLETED 会盖掉结论, 之后 consume 不挡) → 409, 预留量不变")
    void defectiveBatchCannotBeReserved() {
        qcRejected();
        batch.setReceiptQuantity(new java.math.BigDecimal("10"));
        assertThatThrownBy(() -> warehouse.reserveBatchMaterial(F, "WIP-1", new java.math.BigDecimal("10"), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains(MaterialBatchStatus.DEFECTIVE.getDisplayName());
                });
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo("0");
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("两步洗白 第 2 步: 判过不合格、已被数量状态盖成 DEPLETED 的批次释放预留 → 恢复成不良品, 不是可用; 阴性对照: 没判过的恢复可用")
    void releasingReservationOfRejectedBatchRestoresDefective() {
        ReflectionTestUtils.setField(warehouse, "productionPlanBatchUsageRepository",
                mock(com.cretas.aims.repository.ProductionPlanBatchUsageRepository.class));
        qcRejected();
        batch.setStatus(MaterialBatchStatus.DEPLETED);
        batch.setReceiptQuantity(new java.math.BigDecimal("10"));
        batch.setReservedQuantity(new java.math.BigDecimal("10"));
        warehouse.releaseBatchReservation(F, "WIP-1", new java.math.BigDecimal("4"), null);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);

        history.clear();
        batch.setStatus(MaterialBatchStatus.DEPLETED);
        batch.setReservedQuantity(new java.math.BigDecimal("10"));
        warehouse.releaseBatchReservation(F, "WIP-1", new java.math.BigDecimal("4"), null);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("数量恢复的共用判定: 质检判不合格 / 超时判废 → DEFECTIVE; 复检放行后 / 没判过 → AVAILABLE; 仓储缺失报错")
    void statusWhenRestoredFollowsLatestVerdict() {
        assertThat(MaterialQcRejectionGuard.statusWhenRestored(records, timeLimits, batch))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        timeLimitRejected(LocalDateTime.now().minusHours(1));
        assertThat(MaterialQcRejectionGuard.statusWhenRestored(records, timeLimits, batch))
                .isEqualTo(MaterialBatchStatus.DEFECTIVE);
        history.add(0, record(QcReleaseRecord.Decision.RELEASED, LocalDateTime.now()));
        assertThat(MaterialQcRejectionGuard.statusWhenRestored(records, timeLimits, batch))
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        qcRejected();
        assertThat(MaterialQcRejectionGuard.statusWhenRestored(records, timeLimits, batch))
                .isEqualTo(MaterialBatchStatus.DEFECTIVE);
        assertThatThrownBy(() -> MaterialQcRejectionGuard.statusWhenRestored(null, timeLimits, batch))
                .isInstanceOf(IllegalStateException.class);
    }
}

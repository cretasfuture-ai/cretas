package com.cretas.aims.service.quality.release;

import com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.quality.FactoryRecallService;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S411-04 审查 M2: 质检放行判了「不合格」的原料 (DEFECTIVE), 其它三个入口 —— 仓库页改批次状态、放行决策工具、
 * 质检处置放行 —— 都不能把它改回能用; 都走同一个判定 {@link MaterialQcRejectionGuard}。
 *
 * <p>阴性对照: 没有放行记录 / 最近一条是 RELEASED (复检后重新放行) 的不良品照常能改回可用;
 * 判了不合格的批次送回质检中 / 报废照常允许。放行记录仓储没注入时报错, 不当「没判过」。
 */
@DisplayName("质检放行判不合格的原料: 别的入口不能改回可用 (D-S411-04 M2)")
class MaterialQcRejectionGuardContractTest {

    private static final String F = "F-M2";

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final QcReleaseRecordRepository records = mock(QcReleaseRecordRepository.class);
    /** 这批原料的放行记录 (按时间倒序, 与仓储查询同序)。 */
    private final List<QcReleaseRecord> history = new ArrayList<>();

    private MaterialBatchServiceImpl warehouse;
    private static final com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange MANUAL =
            new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                    com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE,
                    "复检合格", 7L, "quality_manager");
    private ReleaseDecisionTool tool;
    private QualityDispositionInventoryService disposition;
    private MaterialBatch batch;

    @BeforeEach
    void setUp() {
        batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(F);
        batch.setMaterialTypeId("RMT-1");
        batch.setBatchNumber("MT-1");
        batch.setSourceDocType("PURCHASE_RECEIVE");
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        when(batches.findById("MB-1")).thenReturn(Optional.of(batch));
        when(batches.findByIdAndFactoryId("MB-1", F)).thenReturn(Optional.of(batch));
        when(batches.findByFactoryIdAndBatchNumber(F, "MT-1")).thenReturn(Optional.of(batch));
        when(batches.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(records.findByTarget(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "MB-1"))
                .thenAnswer(i -> new ArrayList<>(history));

        warehouse = new MaterialBatchServiceImpl(batches, null, null, mock(MaterialBatchMapper.class),
                null, null, null, null);
        ReflectionTestUtils.setField(warehouse, "qcReleaseRecordRepository", records);
        // 纪律排查 A3 放行闸叠加在本闸之后: 这里测的是「质检判不合格不能改回可用」, 所以放行规则配成允许手动放行
        ReflectionTestUtils.setField(warehouse, "materialBatchReleaseGuard",
                new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard(
                        com.cretas.aims.service.discipline.DisciplineFixtures.policy(Map.of(
                                com.cretas.aims.service.canvas.ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL")),
                        mock(com.cretas.aims.repository.material.MaterialBatchReleaseRepository.class)));

        tool = new ReleaseDecisionTool();
        ReflectionTestUtils.setField(tool, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(tool, "qcReleaseRecordRepository", records);
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
    }

    private void decided(String decision, int minutesAgo) {
        history.add(QcReleaseRecord.builder().factoryId(F).targetType(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH)
                .targetId("MB-1").decision(decision).conclusion("x").releasedBy(9L)
                .releasedAt(LocalDateTime.now().minusMinutes(minutesAgo)).build());
    }

    private static void assertRejectedConflict(Throwable t) {
        assertThat(t).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getErrorCode()).isEqualTo(MaterialQcRejectionGuard.CODE);
            assertThat(e.getMessage()).contains("MT-1").contains("质检放行");
        });
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
                    Map.of("batchNumber", "MT-1", "decision", "RELEASED", "notes", "复检合格"), ctx);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private QualityInspection inspection() {
        QualityInspection qi = new QualityInspection();
        qi.setId("qi-1");
        qi.setFactoryId(F);
        qi.setMaterialBatchId("MB-1");
        return qi;
    }

    @Test
    @DisplayName("仓库页: 质检判不合格的原料改成可用 / 鲜品 → 409, 状态不变, 不落库")
    void warehouseCannotMakeItUsable() {
        decided(QcReleaseRecord.Decision.REJECTED, 5);
        for (MaterialBatchStatus target : List.of(MaterialBatchStatus.AVAILABLE, MaterialBatchStatus.FRESH)) {
            assertThatThrownBy(() -> warehouse.updateBatchStatus(F, "MB-1", target, MANUAL))
                    .as("%s", target).satisfies(MaterialQcRejectionGuardContractTest::assertRejectedConflict);
        }
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("仓库页阴性对照: 送回质检中 / 报废照常; 复检后重新放行 (最近一条 RELEASED) 可以改成可用; 从没判过的不良品照常")
    void warehouseNegativeControls() {
        decided(QcReleaseRecord.Decision.REJECTED, 5);
        warehouse.updateBatchStatus(F, "MB-1", MaterialBatchStatus.INSPECTING, MANUAL);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        warehouse.updateBatchStatus(F, "MB-1", MaterialBatchStatus.SCRAPPED, MANUAL);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.SCRAPPED);

        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        history.add(0, QcReleaseRecord.builder().factoryId(F).targetType(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH)
                .targetId("MB-1").decision(QcReleaseRecord.Decision.RELEASED).conclusion("复检合格").releasedBy(9L)
                .releasedAt(LocalDateTime.now()).build());
        warehouse.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE, MANUAL);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        history.clear();
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        warehouse.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE, MANUAL);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("放行决策工具: 预览 canDo=false 写明原因; 执行 (绕过预览) 409, 状态不变, 不落库")
    void releaseDecisionToolCannotReleaseIt() throws Exception {
        decided(QcReleaseRecord.Decision.REJECTED, 5);
        Map<String, Object> preview = invokeTool("doPreview");
        assertThat(preview.get("canDo")).isEqualTo(false);
        assertThat(preview.get("status")).isEqualTo(MaterialQcRejectionGuard.CODE);
        assertThat((String) preview.get("message")).contains("质检放行");

        assertThatThrownBy(() -> invokeTool("doExecute"))
                .satisfies(MaterialQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("放行决策工具阴性对照: 没被质检判过的不良品照常能放行")
    void releaseDecisionToolNegativeControl() throws Exception {
        assertThat(invokeTool("doPreview").get("canDo")).isEqualTo(true);
        invokeTool("doExecute");
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("质检处置放行: 409 (不静默跳过), 状态不变; 阴性对照: 没被质检判过的照常翻成可用")
    void dispositionReleaseCannotFlipIt() {
        decided(QcReleaseRecord.Decision.REJECTED, 5);
        assertThatThrownBy(() -> disposition.applyDisposition(inspection(), DispositionAction.RELEASE, true))
                .satisfies(MaterialQcRejectionGuardContractTest::assertRejectedConflict);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches, never()).save(any(MaterialBatch.class));

        history.clear();
        disposition.applyDisposition(inspection(), DispositionAction.RELEASE, true);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("放行记录仓储没注入: 直接报错, 不当「没判过」放回可用")
    void missingRepositoryFailsLoud() {
        ReflectionTestUtils.setField(warehouse, "qcReleaseRecordRepository", null);
        assertThatThrownBy(() -> warehouse.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE, MANUAL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
    }
}

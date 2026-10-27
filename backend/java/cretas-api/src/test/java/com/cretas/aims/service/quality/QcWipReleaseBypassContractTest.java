package com.cretas.aims.service.quality;

import com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 审查低 10 —— 生产过程中的在制批次 (sourceDocType=PRODUCTION_BATCH) 处于待检, 只能经
 * 「质检放行」解除 (要查 CCP 偏离并留放行记录)。两条旁路必须拒绝把它直接放成可用:
 * AI 工具 {@code release_decision} 与质检处置桥接 {@link QualityDispositionInventoryService}。
 *
 * <p>阴性对照: 同样是 INSPECTING 的<b>原料</b>批次 (采购收货质检) 照旧可以由这两条路放行 —— 拦的只是在制批次。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("在制批次待检不许绕过质检放行")
class QcWipReleaseBypassContractTest {

    private static final String F = "F006";

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private FactoryRecallService factoryRecallService;

    private ReleaseDecisionTool tool;
    private QualityDispositionInventoryService disposition;

    @BeforeEach
    void setUp() {
        tool = new ReleaseDecisionTool();
        ReflectionTestUtils.setField(tool, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        disposition = new QualityDispositionInventoryService();
        ReflectionTestUtils.setField(disposition, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(disposition, "productionBatchRepository", productionBatchRepository);
        ReflectionTestUtils.setField(disposition, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(disposition, "factoryRecallService", factoryRecallService);
    }

    private MaterialBatch batch(String id, String sourceDocType) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber("BN-" + id);
        b.setStatus(MaterialBatchStatus.INSPECTING);
        b.setSourceDocType(sourceDocType);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(F, "BN-" + id)).thenReturn(Optional.of(b));
        when(materialBatchRepository.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(b));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeTool(String method, String batchNumber) throws Exception {
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
                    Map.of("batchNumber", batchNumber, "decision", "RELEASED", "notes", "复检合格"), ctx);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private QualityInspection inspectionOf(String materialBatchId) {
        QualityInspection qi = new QualityInspection();
        qi.setId("qi-1");
        qi.setFactoryId(F);
        qi.setMaterialBatchId(materialBatchId);
        return qi;
    }

    @Test
    @DisplayName("AI 工具: 在制批次待检 → 预览 canDo=false (QC_RELEASE_REQUIRED), 执行抛错且不保存")
    void toolRefusesWip() throws Exception {
        MaterialBatch wip = batch("W1", QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        Map<String, Object> preview = invokeTool("doPreview", "BN-W1");
        assertThat(preview.get("canDo")).isEqualTo(false);
        assertThat(preview.get("status")).isEqualTo("QC_RELEASE_REQUIRED");
        assertThatThrownBy(() -> invokeTool("doExecute", "BN-W1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("质检放行");
        assertThat(wip.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("阴性对照 · AI 工具: 原料批次质检中 → 照旧放行成可用")
    void toolStillReleasesRawMaterial() throws Exception {
        MaterialBatch raw = batch("R1", "PURCHASE_RECEIVE");
        assertThat(invokeTool("doPreview", "BN-R1").get("canDo")).isEqualTo(true);
        invokeTool("doExecute", "BN-R1");
        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("质检处置放行: 在制批次待检不翻成可用 (返回 QC_RELEASE_REQUIRED)")
    void dispositionRefusesWip() {
        MaterialBatch wip = batch("W2", QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        QualityDispositionInventoryService.InventoryDispositionResult r =
                disposition.applyDisposition(inspectionOf("W2"), DispositionAction.RELEASE);
        assertThat(r.getMaterialFlip()).isEqualTo(QualityDispositionInventoryService.FlipDecision.QC_RELEASE_REQUIRED);
        assertThat(wip.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("阴性对照 · 质检处置放行: 原料批次照旧翻成可用")
    void dispositionStillReleasesRawMaterial() {
        MaterialBatch raw = batch("R2", "PURCHASE_RECEIVE");
        QualityDispositionInventoryService.InventoryDispositionResult r =
                disposition.applyDisposition(inspectionOf("R2"), DispositionAction.RELEASE);
        assertThat(r.getMaterialFlip()).isEqualTo(QualityDispositionInventoryService.FlipDecision.FLIP);
        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }
}

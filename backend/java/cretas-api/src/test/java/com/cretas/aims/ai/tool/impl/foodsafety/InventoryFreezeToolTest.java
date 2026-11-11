package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InventoryFreezeTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class InventoryFreezeToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";

    @InjectMocks
    private InventoryFreezeTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-IF-01: metadata + supportsPreview + WRITE")
    void metadata() {
        assertEquals("inventory_freeze", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.HIGH, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-IF-02: doPreview — AVAILABLE batch → canDo=true + severity=BLOCKING")
    @SuppressWarnings("unchecked")
    void previewAllowsFreeze() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setMaterialTypeId("MT-001");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "reason", "客户投诉拉肚子");
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("PREVIEW", result.get("status"));
        assertEquals("BLOCKING", result.get("severity"));
        assertEquals(true, result.get("canDo"));
        assertEquals("AVAILABLE", result.get("currentStatus"));
    }

    private MaterialBatch batchIn(MaterialBatchStatus status) {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setStatus(status);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));
        return mb;
    }

    @Test
    @DisplayName("UT-IF-03: doPreview — 不合格 / 质检中 / 过期 / 报废 → ALREADY_BLOCKED, canDo=false (不覆盖原结论)")
    void previewRefusesToOverwriteBlockedStatus() throws Exception {
        for (MaterialBatchStatus st : new MaterialBatchStatus[]{MaterialBatchStatus.DEFECTIVE,
                MaterialBatchStatus.INSPECTING, MaterialBatchStatus.EXPIRED, MaterialBatchStatus.SCRAPPED}) {
            batchIn(st);
            Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                    Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
            assertEquals("ALREADY_BLOCKED", result.get("status"), st.name());
            assertEquals(false, result.get("canDo"), st.name());
            assertTrue(((String) result.get("message")).contains(st.getDisplayName()), st.name());
        }
    }

    @Test
    @DisplayName("UT-IF-04: doExecute — AVAILABLE → 扣住: 新状态挡投产 (blocksProduction), save 1 次, notes 留痕")
    @SuppressWarnings("unchecked")
    void executeFreezesIntoAStatusThatBlocksProduction() throws Exception {
        MaterialBatch mb = batchIn(MaterialBatchStatus.AVAILABLE);
        assertFalse(mb.getStatus().blocksProduction(), "阳性对照: 冻结前能投产");

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        // 性质: 冻结之后这批不能投产 —— 原实现写 FROZEN, 而 FROZEN (冻品) 不挡投产
        assertTrue(mb.getStatus().blocksProduction(), "冻结后必须挡投产, 实际状态 " + mb.getStatus());
        assertTrue(com.cretas.aims.service.gate.MaterialBatchStateGate.diagnose(mb).isPresent(),
                "写路径共用的状态闸必须认出这批不可投产");
        assertEquals(mb.getStatus().name(), data.get("currentStatus"));
        assertEquals("AVAILABLE", data.get("previousStatus"));
        verify(materialBatchRepository, times(1)).save(any(MaterialBatch.class));
        assertNotNull(mb.getNotes());
        assertTrue(mb.getNotes().contains(InventoryFreezeTool.AUDIT_MARK));
        // 提示与实际一致: 说了「不能领用投产」, 并指向能解除的地方
        String message = String.valueOf(result.get("message"));
        assertTrue(message.contains("不能领用投产"), message);
        assertTrue(message.contains("质检放行"), message);
    }

    @Test
    @DisplayName("UT-IF-05: doExecute 绕过 preview — 不合格 / 质检中的批次状态不变, NO save")
    void executeDoesNotLaunderDefectiveOrInspecting() throws Exception {
        for (MaterialBatchStatus st : new MaterialBatchStatus[]{MaterialBatchStatus.DEFECTIVE,
                MaterialBatchStatus.INSPECTING}) {
            MaterialBatch mb = batchIn(st);
            Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                    Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
            assertEquals("ALREADY_BLOCKED", result.get("status"), st.name());
            assertEquals(st, mb.getStatus(), "冻结不能覆盖「" + st.getDisplayName() + "」");
        }
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-IF-05b: 冻品 (FROZEN, 鲜品转冻品后的正常库存) 能投产; 冻结后不能投产")
    void frozenGoodsCanBeFrozenAndThenBlockProduction() throws Exception {
        assertFalse(MaterialBatchStatus.FROZEN.blocksProduction(), "冻品是正常库存, 不能因为本次修复被挡投产");
        MaterialBatch mb = batchIn(MaterialBatchStatus.FROZEN);
        invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
        assertTrue(mb.getStatus().blocksProduction(), "实际状态 " + mb.getStatus());
        verify(materialBatchRepository, times(1)).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("UT-IF-05c: 已用完 (货架实物 0) → 没东西可扣, 不改状态")
    void usedUpBatchIsNotTouched() throws Exception {
        MaterialBatch mb = batchIn(MaterialBatchStatus.USED_UP);
        mb.setUsedQuantity(new BigDecimal("50"));
        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
        assertEquals("ALREADY_BLOCKED", result.get("status"));
        assertEquals(MaterialBatchStatus.USED_UP, mb.getStatus());
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-IF-05e: 已耗尽 (全被预留, 货还在架上, 预留转消耗照样能投产) → 照样冻结, 冻结后挡投产 (审查 H1)")
    void depletedButPhysicallyPresentBatchIsFrozen() throws Exception {
        MaterialBatch mb = batchIn(MaterialBatchStatus.DEPLETED);
        mb.setReservedQuantity(new BigDecimal("50"));
        assertEquals(0, mb.getCurrentQuantity().signum(), "阳性对照: 可用量 0");
        assertTrue(mb.getPhysicalQuantity().signum() > 0, "阳性对照: 实物还在");
        assertFalse(mb.getStatus().blocksProduction(), "阳性对照: 已耗尽不挡投产");

        Map<String, Object> preview = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
        assertEquals(true, preview.get("canDo"));
        invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH, "reason", "召回"), ctx());
        assertTrue(mb.getStatus().blocksProduction(), "实际状态 " + mb.getStatus());
        verify(materialBatchRepository, times(1)).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("UT-IF-05d: 召回损失认得出被冻结扣住的批次; 不带审计标记的冻品不算")
    void recallHeldRecognition() {
        MaterialBatch held = new MaterialBatch();
        held.setStatus(InventoryFreezeTool.HOLD_STATUS);
        assertTrue(InventoryFreezeTool.isHeldForRecall(held, true));
        assertFalse(InventoryFreezeTool.isHeldForRecall(held, false), "没点名、无标记的质检中批次不算召回冻结");
        held.setNotes("[x] " + InventoryFreezeTool.AUDIT_MARK + "1] 召回 (prev=AVAILABLE)");
        assertTrue(InventoryFreezeTool.isHeldForRecall(held, false));

        MaterialBatch frozenGoods = new MaterialBatch();
        frozenGoods.setStatus(MaterialBatchStatus.FROZEN);
        assertFalse(InventoryFreezeTool.isHeldForRecall(frozenGoods, true), "冻品不是召回冻结");
        frozenGoods.setNotes(InventoryFreezeTool.AUDIT_MARK + "1] 老数据 (prev=AVAILABLE)");
        assertTrue(InventoryFreezeTool.isHeldForRecall(frozenGoods, false), "修复前冻结写的 FROZEN 老数据仍认得");
    }

    @Test
    @DisplayName("UT-IF-06: doExecute — batch not found → BusinessException 404")
    void executeNotFoundThrows() {
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("batchNumber", "B-MISSING", "reason", "X");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}

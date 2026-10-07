package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ReleaseDecisionTool} (Sprint 8 P4c). */
@ExtendWith(MockitoExtension.class)
class ReleaseDecisionToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ReleaseDecisionTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RD-01: metadata — name + required + WRITE + supportsPreview")
    void metadata() {
        assertEquals("release_decision", tool.getToolName());
        assertEquals(List.of("batchNumber", "decision"), tool.getRequiredParameters());
        assertTrue(tool.supportsPreview());
        assertTrue(tool.getDescription().contains("放行"));
    }

    @Test
    @DisplayName("UT-RD-02: preview — invalid decision returns INVALID_DECISION")
    @SuppressWarnings("unchecked")
    void previewInvalidDecision() throws Exception {
        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-X", "decision", "MAYBE"), ctx());
        assertEquals("INVALID_DECISION", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RD-03: preview — REJECTED without notes returns MISSING_REJECT_REASON")
    void previewRejectedNoNotes() throws Exception {
        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-X", "decision", "REJECTED"), ctx());
        assertEquals("MISSING_REJECT_REASON", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RD-04: preview — INSPECTING + RELEASED → canDo=true, new=AVAILABLE")
    void previewReleasedHappy() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-A");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        batch.setReceiptQuantity(new BigDecimal("28"));
        batch.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-A"))
                .thenReturn(Optional.of(batch));

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-A", "decision", "RELEASED"), ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals("INSPECTING", result.get("currentStatus"));
        assertEquals("AVAILABLE", result.get("newStatus"));
        assertEquals("BLOCKING", result.get("severity"));
    }

    @Test
    @DisplayName("UT-RD-05: preview — already AVAILABLE + RELEASED → ALREADY_RELEASED (R4)")
    void previewAlreadyReleased() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-2");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-B");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-B"))
                .thenReturn(Optional.of(batch));

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-B", "decision", "RELEASED"), ctx());
        assertEquals("ALREADY_RELEASED", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RD-06: execute — INSPECTING + RELEASED happy path saves AVAILABLE")
    @SuppressWarnings("unchecked")
    void executeReleaseHappy() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-3");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-C");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-C"))
                .thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenReturn(batch);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-C", "decision", "RELEASED", "notes", "全部通过"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("INSPECTING", data.get("previousStatus"));
        assertEquals("AVAILABLE", data.get("newStatus"));
        assertEquals("RELEASED", data.get("decision"));
        assertEquals(MaterialBatchStatus.AVAILABLE, batch.getStatus());
    }

    @Test
    @DisplayName("UT-RD-07: execute — invalid decision throws IllegalArgumentException")
    void executeInvalidDecision() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoke("doExecute", FACTORY_ID,
                        Map.of("batchNumber", "B-X", "decision", "MAYBE"), ctx()));
        assertTrue(ex.getMessage().contains("decision"));
    }

    @Test
    @DisplayName("UT-RD-08: execute — REJECTED without notes throws IllegalArgumentException")
    void executeRejectedNoNotes() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoke("doExecute", FACTORY_ID,
                        Map.of("batchNumber", "B-X", "decision", "REJECTED"), ctx()));
        assertTrue(ex.getMessage().contains("notes"));
    }

    // ── 给人看的话里没有状态枚举码 (2026-10-07 质检员真测: 同类工具里也有拼英文状态码的) ──

    private MaterialBatch batchWith(String number, MaterialBatchStatus status) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-" + number);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber(number);
        batch.setStatus(status);
        batch.setReceiptQuantity(new BigDecimal("28"));
        batch.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, number))
                .thenReturn(Optional.of(batch));
        return batch;
    }

    /** 文案里一个状态枚举码都不许有 (循环全部取值), 也不许有 status= 这种开发者写法。 */
    private static void assertNoStatusCode(String message) {
        assertNotNull(message);
        for (MaterialBatchStatus s : MaterialBatchStatus.values()) {
            assertFalse(message.contains(s.name()), "文案里漏出状态码 " + s.name() + ": " + message);
        }
        assertFalse(message.contains("status="), "文案里漏出 status=: " + message);
    }

    @Test
    @DisplayName("UT-RD-09: preview 文案 — 放行 / 已放行 / 终态拒绝 三条都用中文状态名, 无枚举码; 状态码字段仍保留给机器")
    void previewMessagesUseChineseStatusNames() throws Exception {
        batchWith("B-P1", MaterialBatchStatus.INSPECTING);
        batchWith("B-P2", MaterialBatchStatus.AVAILABLE);
        batchWith("B-P3", MaterialBatchStatus.EXPIRED);

        Map<String, Object> ok = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-P1", "decision", "RELEASED"), ctx());
        Map<String, Object> already = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-P2", "decision", "RELEASED"), ctx());
        Map<String, Object> terminal = invoke("doPreview", FACTORY_ID,
                Map.of("batchNumber", "B-P3", "decision", "RELEASED"), ctx());

        // 必发生的对照: 三条分支真的走到了各自的出口 (状态各不相同), 不是都落在同一条
        assertEquals("PREVIEW", ok.get("status"));
        assertEquals("ALREADY_RELEASED", already.get("status"));
        assertEquals("INVALID_TRANSITION", terminal.get("status"));

        String okMsg = ok.get("message").toString();
        assertNoStatusCode(okMsg);
        assertTrue(okMsg.contains("「质检中」") && okMsg.contains("「可用」"), okMsg);
        String alreadyMsg = already.get("message").toString();
        assertNoStatusCode(alreadyMsg);
        assertTrue(alreadyMsg.contains("「可用」"), alreadyMsg);
        String terminalMsg = terminal.get("message").toString();
        assertNoStatusCode(terminalMsg);
        assertTrue(terminalMsg.contains("「已过期」"), terminalMsg);
        assertFalse(terminalMsg.contains("DEPLETED/USED_UP"), terminalMsg);

        // 机器读的字段不动
        assertEquals("INSPECTING", ok.get("currentStatus"));
        assertEquals("AVAILABLE", ok.get("newStatus"));
    }

    @Test
    @DisplayName("UT-RD-10: execute 文案 — 成功 / 已在目标状态 / 终态拒绝 三条都用中文状态名, 无枚举码")
    @SuppressWarnings("unchecked")
    void executeMessagesUseChineseStatusNames() throws Exception {
        MaterialBatch fresh = batchWith("B-E1", MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenReturn(fresh);
        batchWith("B-E2", MaterialBatchStatus.AVAILABLE);
        batchWith("B-E3", MaterialBatchStatus.SCRAPPED);

        Map<String, Object> done = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-E1", "decision", "RELEASED", "notes", "全部通过"), ctx());
        Map<String, Object> again = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-E2", "decision", "RELEASED"), ctx());
        IllegalStateException terminal = assertThrows(IllegalStateException.class, () ->
                invoke("doExecute", FACTORY_ID, Map.of("batchNumber", "B-E3", "decision", "RELEASED"), ctx()));

        String doneMsg = done.get("message").toString();
        assertNoStatusCode(doneMsg);
        assertTrue(doneMsg.contains("「质检中」→「可用」"), doneMsg);
        // 已在目标状态: 走幂等出口, 状态码仍在 data 里给机器 (这里直接是 data 本身)
        String againMsg = again.get("message").toString();
        assertNoStatusCode(againMsg);
        assertTrue(againMsg.contains("「可用」"), againMsg);
        assertEquals("ALREADY_RELEASED", again.get("status"));
        assertNoStatusCode(terminal.getMessage());
        assertTrue(terminal.getMessage().contains("「已报废」"), terminal.getMessage());
        assertEquals(MaterialBatchStatus.AVAILABLE, fresh.getStatus());
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

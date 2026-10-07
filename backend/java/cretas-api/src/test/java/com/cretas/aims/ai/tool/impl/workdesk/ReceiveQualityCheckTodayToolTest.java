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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ReceiveQualityCheckTodayTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class ReceiveQualityCheckTodayToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ReceiveQualityCheckTodayTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RQC-01: metadata — READ no required params")
    void metadata() {
        assertEquals("receive_quality_check_today", tool.getToolName());
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-RQC-02: INSPECTING batch returned with daysWaiting")
    @SuppressWarnings("unchecked")
    void inspectingBatchesReturned() throws Exception {
        MaterialBatch b = batch("b1", MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository
                .findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of(b));
        when(materialBatchRepository
                .countByFactoryIdAndReceiptDateAfter(anyString(), any()))
                .thenReturn(3L);

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("inspectingCount"));
        assertEquals(3L, data.get("todayInboundCount"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inspectingBatches");
        assertEquals("b1", rows.get(0).get("batchId"));
        assertEquals("INSPECTING", rows.get(0).get("status"));
    }

    @Test
    @DisplayName("UT-RQC-03: no batches — friendly empty message")
    @SuppressWarnings("unchecked")
    void noBatchesEmptyMessage() throws Exception {
        when(materialBatchRepository
                .findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of());
        when(materialBatchRepository
                .countByFactoryIdAndReceiptDateAfter(anyString(), any()))
                .thenReturn(0L);

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        assertTrue(result.get("message").toString().contains("无"));
    }

    /**
     * 2026-10-07 质检员真测: 回复里漏出「INSPECTING 状态」(英文状态码)。三条分支各自拼文案, 逐条钉:
     * 给人看的话里一个枚举码都不许有 (循环 MaterialBatchStatus 全部取值, 不只是 INSPECTING),
     * 且必须有中文状态名「质检中」(必发生的对照: 文案不是空的 / 不是被整句删掉了)。
     */
    @Test
    @DisplayName("UT-RQC-04: 三条分支的文案都用中文状态名「质检中」, 不出现任何状态枚举码")
    @SuppressWarnings("unchecked")
    void messagesNeverLeakStatusCodes() throws Exception {
        // 分支 1: 今日无入库、无质检中
        when(materialBatchRepository.findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of());
        when(materialBatchRepository.countByFactoryIdAndReceiptDateAfter(anyString(), any())).thenReturn(0L);
        String none = invokeDoExecute(Map.of(), ctx()).get("message").toString();

        // 分支 2: 今日有入库、无质检中
        when(materialBatchRepository.countByFactoryIdAndReceiptDateAfter(anyString(), any())).thenReturn(3L);
        String inboundOnly = invokeDoExecute(Map.of(), ctx()).get("message").toString();

        // 分支 3: 有质检中的批次
        when(materialBatchRepository.findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of(batch("b1", MaterialBatchStatus.INSPECTING)));
        Map<String, Object> waiting = invokeDoExecute(Map.of(), ctx());
        String waitingMsg = waiting.get("message").toString();

        java.util.Set<String> distinct = new java.util.HashSet<>(List.of(none, inboundOnly, waitingMsg));
        assertEquals(3, distinct.size(), "三条分支的文案必须各不相同 (参数化的那一维真的在变)");
        for (String msg : List.of(none, inboundOnly, waitingMsg)) {
            assertTrue(msg.contains("质检中"), "缺中文状态名「质检中」: " + msg);
            for (MaterialBatchStatus s : MaterialBatchStatus.values()) {
                assertFalse(msg.contains(s.name()), "文案里漏出状态码 " + s.name() + ": " + msg);
            }
            assertFalse(msg.contains("QC"), "文案里漏出英文缩写 QC: " + msg);
        }

        // 结构化数据: 机器读的 status 码保留, 给人看的 statusDisplay 是中文
        Map<String, Object> data = (Map<String, Object>) waiting.get("data");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inspectingBatches");
        assertEquals("INSPECTING", rows.get(0).get("status"));
        assertEquals("质检中", rows.get(0).get("statusDisplay"));
    }

    // ── helpers ──
    private MaterialBatch batch(String id, MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY_ID);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId("m1");
        b.setReceiptDate(LocalDate.now());
        b.setReceiptQuantity(new BigDecimal("50"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setWarehouseId("WH-LOG");
        b.setStatus(status);
        b.setCreatedBy(1L);
        b.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));
        return b;
    }

    private Map<String, Object> ctx() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY_ID);
        c.put("userId", 1L);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        var m = findMethod(tool.getClass(), "doExecute");
        m.setAccessible(true);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> c, String n) {
        while (c != null) {
            for (var m : c.getDeclaredMethods()) if (m.getName().equals(n)) return m;
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException("not found: " + n);
    }

    private void injectField(Object t, String n, Object v) throws Exception {
        Field f = findField(t.getClass(), n);
        f.setAccessible(true);
        f.set(t, v);
    }

    private Field findField(Class<?> c, String n) {
        while (c != null) {
            try { return c.getDeclaredField(n); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new IllegalArgumentException("field not found: " + n);
    }
}

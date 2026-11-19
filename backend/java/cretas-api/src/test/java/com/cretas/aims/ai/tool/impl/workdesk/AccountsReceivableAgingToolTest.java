package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.service.finance.ArApService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AccountsReceivableAgingTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class AccountsReceivableAgingToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AccountsReceivableAgingTool tool;

    @Mock
    private ArApService arApService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ARA-01: metadata")
    void metadata() {
        assertEquals("accounts_receivable_aging", tool.getToolName());
    }

    @Test
    @DisplayName("UT-ARA-02: view=aging — sums + extracts highRisk (60+ days), 每客户一行的真实行形状")
    @SuppressWarnings("unchecked")
    void detectsHighRisk() throws Exception {
        // getAgingAnalysis 真实形状: 每个客户一行, total + 各账龄列 (旧用例按「每桶一行」造数, 与实现脱节)
        Map<String, Object> c1 = Map.of("counterpartyName", "甲", "total", new BigDecimal("10000"),
                "days0_30", new BigDecimal("10000"));
        Map<String, Object> c2 = Map.of("counterpartyName", "乙", "total", new BigDecimal("3000"),
                "days61_90", new BigDecimal("3000"));
        Map<String, Object> c3 = Map.of("counterpartyName", "丙", "total", new BigDecimal("5000"),
                "days120plus", new BigDecimal("5000"));
        when(arApService.getAgingAnalysis(anyString(), eq(CounterpartyType.CUSTOMER)))
                .thenReturn(List.of(c1, c2, c3));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of("view", "aging"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        // 甲只有 0-30 不算高风险; 乙 61-90、丙 120+ 是高风险
        assertEquals(2L, data.get("highRiskCount"));
        assertEquals(0, ((BigDecimal) data.get("highRiskAmount")).compareTo(new BigDecimal("8000")));
        assertEquals(3L, data.get("totalCount"));
        assertEquals(0, ((BigDecimal) data.get("totalAmount")).compareTo(new BigDecimal("18000")));
    }

    @Test
    @DisplayName("UT-ARA-03: 不带 view 走默认欠款排名分支, 不走账龄分析 (逾期块在前的内容断言见 AccountsReceivableCollectFirstContractTest)")
    @SuppressWarnings("unchecked")
    void defaultViewIsOwedRankingNotAging() throws Exception {
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("OWED_RANKING", data.get("mode"));
        assertFalse(data.containsKey("highRiskCount"), "默认视图不应带账龄高风险字段");
        org.mockito.Mockito.verifyNoInteractions(arApService);
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

package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.execution.IntentPermissionGate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SYSTEM_HELP 列的是「这个人现在真的能用的」功能 (2026-10-03, 意图识别 90% 方案第 1 步)。
 * 每条「被排除」都配一条「放行时确实出现」的对照。
 */
class SystemHelpToolTest {

    private final AIIntentService intents = mock(AIIntentService.class);
    private final IntentPermissionGate gate = mock(IntentPermissionGate.class);
    private final SystemHelpTool tool = new SystemHelpTool();

    SystemHelpToolTest() {
        ReflectionTestUtils.setField(tool, "aiIntentService", intents);
        ReflectionTestUtils.setField(tool, "intentPermissionGate", gate);
        when(gate.check(any(), any(), any())).thenReturn(IntentPermissionGate.PermissionCheck.legacy());
        when(intents.hasPermission(eq("F006"), anyString(), eq("operator"))).thenReturn(true);
    }

    private static AIIntentConfig intent(String code, String name, String category, String business,
                                         String sensitivity, int priority, String keywords) {
        AIIntentConfig i = AIIntentConfig.builder()
                .intentCode(code).intentName(name).intentCategory(category).businessType(business)
                .sensitivityLevel(sensitivity).priority(priority).keywords(keywords).isActive(true).build();
        return i;
    }

    private Map<String, Object> run(List<AIIntentConfig> all) throws Exception {
        when(intents.getAllIntents("F006")).thenReturn(all);
        return tool.doExecute("F006", Map.of(), Map.of("userId", 7L, "userRole", "operator"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Object entries) {
        return ((List<Map<String, Object>>) entries).stream().map(m -> (String) m.get("intentCode")).toList();
    }

    @Test
    void listsPermittedReadsFirstThenWritesWithConfirmationNote() throws Exception {
        Map<String, Object> r = run(List.of(
                intent("ORDER_TODAY", "今日订单", "QUERY", "FACTORY", "LOW", 92, "[\"今天订单\"]"),
                intent("MATERIAL_BATCH_CREATE", "原料入库", "DATA_OP", "FACTORY", "HIGH", 95, "[\"到货了\"]"),
                intent("TRACE_FULL", "完整溯源", "QUERY", "FACTORY", "LOW", 90, null)));

        assertThat(codes(r.get("queries"))).containsExactly("ORDER_TODAY", "TRACE_FULL");
        assertThat(codes(r.get("operations"))).containsExactly("MATERIAL_BATCH_CREATE");
        String msg = (String) r.get("message");
        assertThat(msg).contains("「今天订单」 —— 今日订单").contains("「完整溯源」")
                .contains("会先请您确认").contains("「到货了」 —— 原料入库");
        assertThat(msg.indexOf("今天订单")).isLessThan(msg.indexOf("到货了"));
        assertThat(msg).doesNotContain("设备");
    }

    @Test
    void roleWithoutPermissionDoesNotSeeTheIntent() throws Exception {
        when(intents.hasPermission("F006", "SHIPMENT_CREATE", "operator")).thenReturn(false);
        Map<String, Object> r = run(List.of(
                intent("SHIPMENT_CREATE", "创建发货单", "DATA_OP", "FACTORY", "HIGH", 95, "[\"发货\"]"),
                intent("ORDER_TODAY", "今日订单", "QUERY", "FACTORY", "LOW", 92, "[\"今天订单\"]")));

        assertThat(codes(r.get("operations"))).isEmpty();
        assertThat((String) r.get("message")).doesNotContain("发货");
        assertThat(codes(r.get("queries"))).containsExactly("ORDER_TODAY");   // 对照: 有权限的照常出现
    }

    @Test
    void permissionCodeDenialHidesTheIntent() throws Exception {
        AIIntentConfig rule = intent("RULE_CONFIG", "规则配置", "CONFIG", "FACTORY", "HIGH", 80, "[\"改规则\"]");
        when(gate.check(rule, 7L, "operator")).thenReturn(IntentPermissionGate.PermissionCheck.denied("system:read_write"));
        assertThat(codes(run(List.of(rule)).get("operations"))).isEmpty();

        when(gate.check(rule, 7L, "operator")).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        assertThat(codes(run(List.of(rule)).get("operations"))).containsExactly("RULE_CONFIG");
    }

    @Test
    void helpItselfPaginationSystemAndRestaurantAreLeftOut() throws Exception {
        AIIntentConfig inactive = intent("OLD_QUERY", "旧查询", "QUERY", "FACTORY", "LOW", 99, null);
        inactive.setIsActive(false);
        Map<String, Object> r = run(List.of(
                intent("SYSTEM_HELP", "使用帮助", "SYSTEM", "COMMON", "LOW", 100, null),
                intent("PAGINATION_NEXT", "下一页", "QUERY", "COMMON", "LOW", 100, null),
                intent("SYSTEM_SETTINGS", "系统设置", "SYSTEM", "COMMON", "LOW", 100, null),
                intent("RESTAURANT_DISH_LIST", "菜品列表", "QUERY", "RESTAURANT", "LOW", 100, null),
                inactive,
                intent("ORDER_TODAY", "今日订单", "QUERY", "FACTORY", "LOW", 92, null)));

        assertThat(codes(r.get("queries"))).containsExactly("ORDER_TODAY");
    }

    @Test
    void listsAreCapped() throws Exception {
        List<AIIntentConfig> all = new ArrayList<>();
        for (int n = 0; n < 20; n++) {
            all.add(intent("Q" + n, "查询" + n, "QUERY", "FACTORY", "LOW", 100 - n, null));
            all.add(intent("W" + n, "操作" + n, "DATA_OP", "FACTORY", "HIGH", 100 - n, null));
        }
        Map<String, Object> r = run(all);
        assertThat(codes(r.get("queries"))).hasSize(SystemHelpTool.MAX_READ).startsWith("Q0", "Q1");
        assertThat(codes(r.get("operations"))).hasSize(SystemHelpTool.MAX_WRITE);
        assertThat(r.get("availableCount")).isEqualTo(40);
    }

    @Test
    void nothingUsableSaysSoInsteadOfInventingExamples() throws Exception {
        Map<String, Object> r = run(List.of(intent("SYSTEM_HELP", "使用帮助", "SYSTEM", "COMMON", "LOW", 100, null)));
        assertThat((String) r.get("message")).contains("还没有可以在对话里使用的功能").doesNotContain("「");
    }
}

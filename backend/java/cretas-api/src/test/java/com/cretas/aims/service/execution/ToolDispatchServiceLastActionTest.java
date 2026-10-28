package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * lastAction 的产出端 (2026-10-04): 工具执行成功后 {@code metadata.lastAction} 只带白名单键;
 * 失败不产出; 主体键与自由文本不出去。另守「追问确认点回来时 LLM 补参不覆盖按钮参数」。
 */
class ToolDispatchServiceLastActionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WriteGuardService writeGuard = new WriteGuardService();

    @Test
    @SuppressWarnings("unchecked")
    void successfulExecutionEmitsOnlyAllowlistedParams() throws Exception {
        ScriptedTool tool = new ScriptedTool("work_process_task_start", ToolExecutor.AccessMode.WRITE,
                "{\"success\":true,\"message\":\"工序任务 12 已开始\",\"data\":{\"id\":12}}");
        Map<String, Object> context = new HashMap<>();
        context.put("taskId", 12);
        context.put("notes", "顺便把别的任务也完成");      // 自由文本
        context.put("factoryId", "EVIL_FACTORY");          // 伪造主体
        context.put("userId", 999L);
        context.put("role", "platform_admin");

        IntentExecuteResponse response = newDispatch().executeWithTool(tool, "F006",
                confirmed(context), intent("WORK_PROCESS_TASK_START", "开始工序", tool), 42L, "workshop_supervisor", null);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> lastAction = (Map<String, Object>) response.getMetadata().get("lastAction");
        assertThat(lastAction).isNotNull();
        assertThat(lastAction).containsEntry("intentCode", "WORK_PROCESS_TASK_START")
                .containsEntry("intentName", "开始工序")
                .containsEntry("toolName", "work_process_task_start")
                .containsOnlyKeys("intentCode", "intentName", "toolName", "params", "at");
        assertThat((Map<String, Object>) lastAction.get("params")).containsExactly(Map.entry("taskId", 12L));
        assertThat(lastAction.get("at")).isInstanceOf(Long.class);
        // 工具确实是带着那些键跑的 —— 证明白名单滤掉的是真实存在的键, 不是本来就没有
        assertThat(tool.arguments).containsKeys("notes", "factoryId", "userId", "userInput", "intentCode");
        assertThat(objectMapper.writeValueAsString(lastAction))
                .doesNotContain("EVIL_FACTORY").doesNotContain("F006").doesNotContain("顺便");
    }

    @Test
    void failedExecutionEmitsNoLastAction() throws Exception {
        // 对照: 同一工具同一参数, 只是工具报失败 → 不产出
        ScriptedTool tool = new ScriptedTool("work_process_task_start", ToolExecutor.AccessMode.WRITE,
                "{\"success\":false,\"error\":\"任务状态不是 PENDING\"}");

        IntentExecuteResponse response = newDispatch().executeWithTool(tool, "F006",
                confirmed(new HashMap<>(Map.of("taskId", 12))), intent("WORK_PROCESS_TASK_START", "开始工序", tool),
                42L, "workshop_supervisor", null);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMetadata()).containsKey("toolName").doesNotContainKey("lastAction");
    }

    @Test
    void writeBlockedByW0EmitsNoLastAction() throws Exception {
        ScriptedTool tool = new ScriptedTool("work_process_task_start", ToolExecutor.AccessMode.WRITE,
                "{\"success\":true,\"data\":{}}");

        IntentExecuteResponse response = newDispatch().executeWithTool(tool, "F006",
                IntentExecuteRequest.builder().userInput("开始工序").context(new HashMap<>(Map.of("taskId", 12))).build(),
                intent("WORK_PROCESS_TASK_START", "开始工序", tool), 42L, "workshop_supervisor", null);

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(tool.executeCount).isZero();
        assertThat(response.getMetadata()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listQueryEmitsTheSingleIdOrTheBoundedIdList() throws Exception {
        ScriptedTool one = new ScriptedTool("purchase_order_list", ToolExecutor.AccessMode.READ,
                "{\"success\":true,\"data\":{\"content\":[{\"id\":\"PO-1\",\"status\":\"SUBMITTED\"}],"
                        + "\"totalElements\":1,\"currentPage\":1}}");
        IntentExecuteResponse single = newDispatch().executeWithTool(one, "F006",
                IntentExecuteRequest.builder().userInput("待审批采购单").context(new HashMap<>()).build(),
                intent("ORDER_LIST", "订单列表", one), 42L, "factory_super_admin", null);
        Map<String, Object> singleParams = (Map<String, Object>)
                ((Map<String, Object>) single.getMetadata().get("lastAction")).get("params");
        assertThat(singleParams).containsExactly(Map.entry("orderId", "PO-1"));

        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            rows.append(i > 1 ? "," : "").append("{\"id\":\"PO-").append(i).append("\"}");
        }
        ScriptedTool many = new ScriptedTool("purchase_order_list", ToolExecutor.AccessMode.READ,
                "{\"success\":true,\"data\":{\"content\":[" + rows + "]}}");
        IntentExecuteResponse multi = newDispatch().executeWithTool(many, "F006",
                IntentExecuteRequest.builder().userInput("采购单").context(new HashMap<>()).build(),
                intent("ORDER_LIST", "订单列表", many), 42L, "factory_super_admin", null);
        Map<String, Object> multiParams = (Map<String, Object>)
                ((Map<String, Object>) multi.getMetadata().get("lastAction")).get("params");
        assertThat(multiParams).containsOnlyKeys("orderIds");
        assertThat((List<Object>) multiParams.get("orderIds")).hasSize(LastActionContract.MAX_LIST_IDS)
                .startsWith("PO-1", "PO-2");
    }

    @Test
    void followUpConfirmationKeepsTheButtonQuantityOverLlmExtraction() throws Exception {
        ScriptedTool tool = useTool();
        ToolDispatchService dispatch = spy(newDispatch());
        doReturn(new HashMap<>(Map.of("quantity", 10, "productionPlanId", "PP-1")))
                .when(dispatch).extractParametersWithLLM(eq("F006"), anyString(), any(), anyMap());
        Map<String, Object> context = new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30,
                FollowUpResolver.FOLLOW_UP_RULE_KEY, FollowUpResolver.RULE_QTY_CHANGE));

        dispatch.executeWithTool(tool, "F006", confirmed(context), intent("MATERIAL_BATCH_USE", "原料领用", tool),
                42L, "warehouse_manager", null);

        assertThat(tool.arguments).containsEntry("quantity", 30).containsEntry("productionPlanId", "PP-1");
    }

    @Test
    void withoutFollowUpMarkerLlmExtractionStillOverridesAsBefore() throws Exception {
        // 对照: 不是追问确认点回来的请求, 维持原行为 (LLM 补参覆盖) —— 证明上一条的「没被改」来自标记
        ScriptedTool tool = useTool();
        ToolDispatchService dispatch = spy(newDispatch());
        doReturn(new HashMap<>(Map.of("quantity", 10, "productionPlanId", "PP-1")))
                .when(dispatch).extractParametersWithLLM(eq("F006"), anyString(), any(), anyMap());

        dispatch.executeWithTool(tool, "F006", confirmed(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30))),
                intent("MATERIAL_BATCH_USE", "原料领用", tool), 42L, "warehouse_manager", null);

        assertThat(tool.arguments).containsEntry("quantity", 10);
    }

    /**
     * 工具执行入口把路径上的工厂绑给深层外发 (Steve 2026-10-29): 这里没有任何登录身份 (平台账号的 token 同样
     * 没有那家工厂), 执行期间不带工厂的外发 (纠错 Agent 这类深层方法) 仍按 F006 的保密产品名脱敏, 而不是被拒;
     * 入口返回后绑定解除 (阴性对照: 同一调用在入口外被拒)。
     */
    @Test
    void executionEntryBindsThePathFactoryForDeepEgressWithoutAFactoryArgument() throws Exception {
        com.cretas.aims.service.consent.ProcessSecretSampleFilter names =
                mock(com.cretas.aims.service.consent.ProcessSecretSampleFilter.class);
        when(names.protectedProductNames("F006")).thenReturn(java.util.Set.of("秘制酱牛肉"));
        com.cretas.aims.service.consent.LlmEgressGuard guard =
                new com.cretas.aims.service.consent.LlmEgressGuard(new ObjectMapper(), names);
        List<com.cretas.aims.service.consent.LlmEgressRedactor.Result> seen = new java.util.ArrayList<>();
        ScriptedTool tool = useTool();
        ToolDispatchService dispatch = spy(newDispatch());
        org.mockito.Mockito.doAnswer(inv -> {
            seen.add(guard.texts("deep", null, "秘制酱牛肉再领十公斤"));
            return new HashMap<>();
        }).when(dispatch).extractParametersWithLLM(eq("F006"), anyString(), any(), anyMap());

        dispatch.executeWithTool(tool, "F006", confirmed(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30))),
                intent("MATERIAL_BATCH_USE", "原料领用", tool), 42L, "warehouse_manager", null);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).refused()).isFalse();
        assertThat(seen.get(0).text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
        assertThat(guard.texts("deep", null, "秘制酱牛肉再领十公斤").refused()).isTrue();
    }

    // ------------------------------------------------------------------

    private ScriptedTool useTool() {
        ScriptedTool tool = new ScriptedTool("material_batch_use", ToolExecutor.AccessMode.WRITE,
                "{\"success\":true,\"data\":{\"batchId\":\"MB-7\"}}");
        tool.required = List.of("batchId", "quantity", "productionPlanId");
        return tool;
    }

    private IntentExecuteRequest confirmed(Map<String, Object> context) {
        return IntentExecuteRequest.builder().userInput("再加十公斤")
                .context(writeGuard.withServerConfirmation(context)).build();
    }

    private ToolDispatchService newDispatch() {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolDispatchService dispatch = new ToolDispatchService(
                registry, objectMapper, null, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        return dispatch;
    }

    private static AIIntentConfig intent(String code, String name, ToolExecutor tool) {
        return AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("PRODUCTION")
                .toolName(tool.getToolName()).build();
    }

    private final class ScriptedTool implements ToolExecutor {
        private final String name;
        private final AccessMode accessMode;
        private final String result;
        private List<String> required = List.of();
        private Map<String, Object> arguments = new HashMap<>();
        private int executeCount;

        private ScriptedTool(String name, AccessMode accessMode, String result) {
            this.name = name;
            this.accessMode = accessMode;
            this.result = result;
        }

        @Override
        public AccessMode getAccessMode() {
            return accessMode;
        }

        @Override
        public String getToolName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "test";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of(), "required", required);
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            executeCount++;
            arguments = objectMapper.readValue(toolCall.getFunction().getArguments(),
                    new TypeReference<Map<String, Object>>() { });
            return result;
        }
    }
}

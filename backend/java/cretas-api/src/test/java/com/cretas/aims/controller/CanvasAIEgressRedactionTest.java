package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布 AI 的三条外发: 审批流草稿 (审批人名单 + 管理员原话)、工艺 Workflow 草稿 (工艺图 + 原话)、
 * 方案 / 影响分析 (只有原话)。走真实入口 {@link CanvasAIController#chat}, 截获实际发给模型的内容。
 */
class CanvasAIEgressRedactionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;
    private ToolRegistry toolRegistry;
    private CanvasAIController controller;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        toolRegistry = mock(ToolRegistry.class);
        controller = new CanvasAIController(toolRegistry, mapper, llm, mock(MobileService.class),
                mock(ToolExecutionGateway.class));
        ReflectionTestUtils.setField(controller, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
    }

    private static CanvasAIController.AIRequest approvalRequest(String message) {
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("approval_workflow_config");
        request.setMode("plan");
        request.setMessage(message);
        request.setParams(Map.of("context", Map.of(
                "decisionType", "PURCHASE_ORDER_APPROVAL",
                "workflow", Map.of("name", "采购订单审批", "startNodeId", "start", "nodes", List.of(), "edges", List.of()),
                "roles", List.of(Map.of("code", "factory_super_admin", "label", "工厂总管理员")),
                "users", List.of(
                        Map.of("id", "42", "username", "zhaotiezhu", "name", "赵铁柱"),
                        Map.of("id", "43", "username", "qiandahai", "name", "钱大海")))));
        return request;
    }

    @Test
    void approvalDirectoryNamesAndPhonesNeverReachTheModelButTheDraftShowsRealNames() throws Exception {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), user.capture())).thenAnswer(inv -> {
            String said = inv.getArgument(1);
            String person = said.replaceAll("(?s).*让(\\[人员[A-Z]+]).*", "$1");
            return """
                    {"name":"采购审批","startNodeId":"start",
                     "nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                              {"id":"ai_a","type":"approval","label":"%s审批","config":{"approverUserIds":["42"]}},
                              {"id":"end","type":"end","label":"通过","config":{"outcome":"APPROVED"}}],
                     "edges":[{"id":"e1","source":"start","target":"ai_a"},{"id":"e2","source":"ai_a","target":"end"}]}
                    """.formatted(person);
        });

        CanvasAIController.AIResponse response = controller
                .chat("F006", null, approvalRequest("超过五万让赵铁柱审批, 有事打 13912345678")).getData();

        String sent = system.getValue() + "\n" + user.getValue();
        assertThat(sent).doesNotContain("赵铁柱", "钱大海", "zhaotiezhu", "qiandahai", "13912345678");
        assertThat(sent).contains("\"42\"", "\"43\"", "factory_super_admin");
        assertThat(mapper.writeValueAsString(response.getDiffs())).contains("赵铁柱审批").doesNotContain("[人员");
    }

    @Test
    void processWorkflowDefinitionHidesSecretProductNames() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("canvas_product_process_workflow_config")).thenReturn(Optional.of(tool));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), user.capture()))
                .thenReturn("{\"steps\":[{\"processName\":\"卤制\",\"outputName\":\"[保密产品A]\"}]}");
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("product_process_workflow_config");
        request.setMode("plan");
        request.setMessage("秘制酱牛肉 加一道卤制, 找李经理确认");
        request.setParams(Map.of("definition", Map.of("productName", "秘制酱牛肉", "steps", List.of())));

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        String sent = system.getValue() + "\n" + user.getValue();
        assertThat(sent).doesNotContain("秘制酱牛肉", "李经理").contains("[保密产品A]", "卤制");
        assertThat(mapper.writeValueAsString(response.getDiffs())).contains("秘制酱牛肉").doesNotContain("[保密产品A]");
    }

    @Test
    void secretLookupFailureMeansTheProcessWorkflowIsNotSent() throws Exception {
        when(processSecret.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));
        when(toolRegistry.getExecutor("canvas_product_process_workflow_config"))
                .thenReturn(Optional.of(mock(ToolExecutor.class)));
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("product_process_workflow_config");
        request.setMessage("加一道卤制");
        request.setParams(Map.of("definition", Map.of("steps", List.of())));

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        verify(llm, never()).chatLowTemp(anyString(), anyString());
        assertThat(response.getReply()).contains("未发给 AI");
    }

    @Test
    void planAndActionModesMaskPhonesInTheUtterance() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(system.capture(), user.capture())).thenReturn("通知 [电话A] 即可");
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setMode("action");
        request.setMessage("改完通知 13912345678");

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertThat(system.getValue() + user.getValue()).doesNotContain("13912345678").contains("[电话A]");
        assertThat(response.getReply()).isEqualTo("通知 13912345678 即可");
    }
}

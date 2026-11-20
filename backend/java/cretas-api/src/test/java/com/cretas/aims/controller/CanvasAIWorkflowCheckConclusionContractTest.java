package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.service.MobileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasAIWorkflowCheckConclusionContractTest {

    @Test
    void workflowCheckConclusionLocalizesModelFieldsEnumsAndNodeIds() {
        ToolRegistry registry = mock(ToolRegistry.class);
        DashScopeClient client = mock(DashScopeClient.class);
        CanvasAIController controller = new CanvasAIController(registry, new ObjectMapper(), client,
                mock(MobileService.class), mock(ToolExecutionGateway.class));
        when(registry.getExecutor("canvas_product_process_workflow_config"))
                .thenReturn(Optional.of(mock(ToolExecutor.class)));

        String question = "检查投入与产出单位、数量换算是否完整";
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setMessage(question);
        request.setMode("plan");
        request.setModuleCode("product_process_workflow_config");
        List<Map<String, Object>> nodes = List.of(
                Map.of("id", "p:L3:pa", "data", Map.of("processName", "拼装分装")),
                Map.of("id", "p:L3:pc1", "data", Map.of("processName", "清洗去毛")),
                Map.of("id", "m:L3:out", "data", Map.of("name", "拼装成品")));
        request.setParams(Map.of("intent", "check", "context", Map.of("definition", Map.of("nodes", nodes))));
        when(client.chatLowTemp(anyString(), eq(question))).thenReturn("""
                **结论:发现 2 个问题.**
                1. 「`p:L3:pa`」的`conversionRule` 为 `ACTUAL_WEIGHT`，请核对「`m:L3:out`」的每盒净重。
                2. 「p:L3:pc1」的字段「工艺类别」未填写。请在右侧选「加工」。
                """);

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertEquals("""
                结论：发现 2 个问题。
                1、「拼装分装」按实际称重，请核对「拼装成品」的每盒净重。
                2、「清洗去毛」还没选工序类型。请在右侧选「加工」。""",
                response.getReply());
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).chatLowTemp(prompt.capture(), eq(question));
        assertTrue(prompt.getValue().contains("每项最多两行、两句短话"));
        assertTrue(prompt.getValue().contains("再说明在哪儿改、改什么"));
        assertFalse(prompt.getValue().contains("the field"));
        assertTrue(response.getDiffs().isEmpty());
        assertFalse(response.isApplied());
    }
}

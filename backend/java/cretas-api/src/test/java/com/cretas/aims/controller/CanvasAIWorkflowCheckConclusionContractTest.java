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
                Map.of("id", "p:L3:pc1", "data", Map.of("processName", "清洗去毛")));
        request.setParams(Map.of("intent", "check", "context", Map.of("definition", Map.of("nodes", nodes))));
        when(client.chatLowTemp(anyString(), eq(question))).thenReturn("""
                结论：发现 2 个问题。
                1. `p:L3:pa` 的 `conversionRule` 为 `ACTUAL_WEIGHT`，请核对每盒净重。
                2. p:L3:pc1：processCategory:null。
                """);

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertEquals("""
                结论：发现 2 个问题。
                1. 拼装分装 的 换算规则 为 按实际重量，请核对每盒净重。
                2. 清洗去毛：工艺类别:未填写。""", response.getReply());
        assertTrue(response.getDiffs().isEmpty());
        assertFalse(response.isApplied());
    }
}

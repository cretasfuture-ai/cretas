package com.cretas.aims.ai.tool.impl.sop;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SOP 文档: 正文里的负责人、公司名、电话、本厂工艺保密产品名不发给模型, 结构化结果里换回真名。
 * 走真实入口 {@code tool.execute} (PDF 解析只替掉下载那一步)。
 */
class SopEgressRedactionTest {

    private static final String FACTORY = "F006";
    private static final String SOP = "秘制酱牛肉 卤制 SOP\n负责人：赵铁柱 13912345678\n供货单位 华东冷链商贸有限公司\n"
            + "第一步 解冻 2 小时; 第二步 卤制 40 分钟";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(FACTORY)).thenReturn(Set.of("秘制酱牛肉"));
    }

    private void wire(Object tool) {
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "dashScopeClient", llm);
        ReflectionTestUtils.setField(tool, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
    }

    private static ToolCall call(String name, Map<String, Object> args) throws Exception {
        return ToolCall.builder().id("c1").function(ToolCall.FunctionCall.builder().name(name)
                .arguments(new ObjectMapper().writeValueAsString(args)).build()).build();
    }

    @Test
    void pdfTextIsRedactedBeforeStructuringAndTheStepsShowRealNames() throws Exception {
        SopParseDocumentTool tool = spy(new SopParseDocumentTool());
        wire(tool);
        doReturn(SOP).when(tool).extractPdfText("https://oss.example/sop.pdf");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), sent.capture())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            String product = p.replaceAll("(?s).*?(\\[保密产品[A-Z]+]).*", "$1");
            return "{\"steps\":[{\"name\":\"卤制\",\"description\":\"" + product + " 卤制 40 分钟\"}],"
                    + "\"metadata\":{\"productName\":\"" + product + "\"}}";
        });

        String out = tool.execute(call(tool.getToolName(), Map.of("fileUrl", "https://oss.example/sop.pdf",
                "fileType", "PDF")), Map.of("factoryId", FACTORY, "userId", 1L));

        assertThat(sent.getValue()).doesNotContain("秘制酱牛肉", "赵铁柱", "13912345678", "华东冷链").contains("卤制 40 分钟");
        assertThat(out).contains("秘制酱牛肉 卤制 40 分钟").doesNotContain("[保密产品");
    }

    @Test
    void complexityAnalysisIsRedactedAndFallsBackToRulesWhenLookupFails() throws Exception {
        SopAnalyzeComplexityTool tool = new SopAnalyzeComplexityTool();
        wire(tool);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), sent.capture())).thenReturn("{\"level\":2,\"reason\":\"步骤少\",\"stepCount\":2}");

        tool.execute(call(tool.getToolName(), Map.of("sopContent", SOP)), Map.of("factoryId", FACTORY, "userId", 1L));

        assertThat(sent.getValue()).doesNotContain("秘制酱牛肉", "赵铁柱", "13912345678", "华东冷链");

        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        llm = mock(DashScopeClient.class);
        ReflectionTestUtils.setField(tool, "dashScopeClient", llm);
        String out = tool.execute(call(tool.getToolName(), Map.of("sopContent", SOP)),
                Map.of("factoryId", FACTORY, "userId", 1L));

        verify(llm, never()).chat(anyString(), anyString());
        assertThat(out).contains("\"success\":true");
    }
}

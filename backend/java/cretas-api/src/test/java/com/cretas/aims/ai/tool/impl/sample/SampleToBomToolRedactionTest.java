package com.cretas.aims.ai.tool.impl.sample;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.rd.ProductSampleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 样品转 BOM: 客户名、客户要求里的人名与电话、工艺保密产品名不发给模型; 脱敏不了就不发。
 * 走真实入口 {@link SampleToBomTool#execute}, 截获实际发给模型的两段提示词。
 */
class SampleToBomToolRedactionTest {

    private static final String FACTORY = "F006";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;
    private SampleToBomTool tool;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(FACTORY)).thenReturn(Set.of("秘制酱牛肉"));

        ProductSample sample = new ProductSample();
        sample.setSampleCode("SP-2026-001");
        sample.setName("秘制酱牛肉 试制款");
        sample.setMainMaterial("牛腱子");
        sample.setCustomerName("老街坊餐饮");
        sample.setSalesperson("周晓明");
        sample.setCustomerLatestRequirement("老街坊餐饮要求少盐, 联系人：赵铁柱 13912345678 或 021-58881234");
        ProductSampleService samples = mock(ProductSampleService.class);
        when(samples.getSample(FACTORY, "S1")).thenReturn(sample);

        RawMaterialType beef = new RawMaterialType();
        beef.setId("M1");
        beef.setName("牛腱子");
        RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
        when(materials.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(beef));

        tool = new SampleToBomTool();
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "productSampleService", samples);
        ReflectionTestUtils.setField(tool, "materialTypeRepository", materials);
        ReflectionTestUtils.setField(tool, "dashScopeClient", llm);
        ReflectionTestUtils.setField(tool, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
    }

    private JsonNode run(String adjustments) throws Exception {
        String args = mapper.writeValueAsString(Map.of("sampleId", "S1", "adjustments", adjustments));
        ToolCall call = ToolCall.builder().id("c1")
                .function(ToolCall.FunctionCall.builder().name("sample_to_bom").arguments(args).build()).build();
        return mapper.readTree(tool.execute(call, Map.of("factoryId", FACTORY, "userId", 1L)));
    }

    @Test
    void modelSeesNoCustomerPersonPhoneOrSecretProductAndAnswerIsRestored() throws Exception {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), user.capture())).thenAnswer(inv -> {
            String prompt = inv.getArgument(1);
            // 模型照抄它看到的保密产品代号; 回答回到用户时要换回真名
            String token = prompt.replaceAll("(?s).*样品名称: (\\[[^]]+]).*", "$1");
            return "{\"items\":[{\"materialName\":\"牛腱子\",\"quantity\":1,\"unit\":\"kg\",\"percentage\":100}],"
                    + "\"totalWeight\":1,\"explanation\":\"" + token + " 按少盐配\",\"warnings\":[]}";
        });

        JsonNode out = run("参照周晓明上次给的配方, 主料换成牛腱子");

        String sent = system.getValue() + "\n" + user.getValue();
        assertThat(sent).doesNotContain("老街坊餐饮", "赵铁柱", "周晓明", "13912345678", "58881234", "秘制酱牛肉");
        assertThat(sent).contains("少盐", "牛腱子", "SP-2026-001", "[保密产品A]");
        assertThat(out.path("success").asBoolean()).isTrue();
        assertThat(out.toString()).contains("秘制酱牛肉 按少盐配").doesNotContain("[保密产品A]");
    }

    @Test
    void lookupFailureMeansNothingIsSent() throws Exception {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));

        JsonNode out = run("减 10% 包材");

        verify(llm, never()).chatLowTemp(anyString(), anyString());
        assertThat(out.path("success").asBoolean()).isFalse();
        assertThat(out.toString()).contains("未发给 AI");
    }
}

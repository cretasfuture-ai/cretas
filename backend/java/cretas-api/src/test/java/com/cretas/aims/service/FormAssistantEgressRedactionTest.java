package com.cretas.aims.service;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
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
 * 表单助手: 用户说的客户名 / 联系人 / 电话、提交值里的地址 / 备注不发给模型; 填回表单的是真值。
 * 走真实入口 {@link FormAssistantService#parseFormInput} / {@link FormAssistantService#submitValidationFeedback}。
 */
class FormAssistantEgressRedactionTest {

    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;
    private FormAssistantService service;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        FormPromptRegistry prompts = mock(FormPromptRegistry.class);
        when(prompts.promptFor(anyString(), anyString())).thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper();
        service = new FormAssistantService(llm, mock(DashScopeVisionClient.class), mock(DashScopeConfig.class),
                mapper, prompts);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        ReflectionTestUtils.setField(service, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
    }

    private static List<Map<String, Object>> customerFields() {
        return List.of(Map.of("name", "customerName", "title", "客户名称", "type", "string"),
                Map.of("name", "contactPhone", "title", "联系电话", "type", "string"));
    }

    @Test
    void parseFormSendsTokensAndFillsRealValues() {
        ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), said.capture())).thenAnswer(inv -> {
            String s = inv.getArgument(1);
            String org = s.replaceAll("(?s).*?(\\[单位[A-Z]+]).*", "$1");
            String phone = s.replaceAll("(?s).*?(\\[电话[A-Z]+]).*", "$1");
            return "{\"field_values\":{\"customerName\":\"" + org + "\",\"contactPhone\":\"" + phone + "\"}}";
        });

        FormAssistantService.FormParseResult r = service.parseFormInput(
                "新建客户张记食品有限公司, 电话 13812345678, 订秘制酱牛肉", "CUSTOMER", customerFields(), "F006");

        assertThat(said.getValue()).doesNotContain("张记食品", "13812345678", "秘制酱牛肉");
        assertThat(r.getFieldValues()).containsEntry("customerName", "张记食品有限公司")
                .containsEntry("contactPhone", "13812345678");
    }

    /** 线上 2026-10-27 01:47 复现句: 模型把代号去掉方括号填进字段, 预览卡片显示了「单位A」。 */
    private static final String PROD_SENTENCE = "给华东冷链商贸有限公司下单，联系人赵铁柱 13912345678，牛腱 100 公斤";
    private static final java.util.regex.Pattern ANY_PLACEHOLDER = java.util.regex.Pattern.compile(
            "(客户|供应商|单位|人员|名称|保密产品|电话|证件号|卡号|隐藏内容)\\s*[A-Z]+(?![A-Za-z])");

    private static List<Map<String, Object>> salesOrderFields() {
        return List.of(Map.of("name", "customerName", "title", "客户", "type", "string"),
                Map.of("name", "contactPerson", "title", "联系人", "type", "string"),
                Map.of("name", "contactPhone", "title", "联系电话", "type", "string"),
                Map.of("name", "remark", "title", "备注", "type", "string"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "模型写成 {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"bare", "fullwidth", "spaced"})
    void parseFormRestoresTokensTheModelRewrote(String style) throws Exception {
        ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), said.capture())).thenAnswer(inv -> {
            String s = inv.getArgument(1);
            java.util.function.Function<String, String> rewrite = tok -> {
                String core = tok.substring(1, tok.length() - 1);
                return switch (style) {
                    case "bare" -> core;
                    case "fullwidth" -> "【" + core + "】";
                    default -> "[ " + core.replaceAll("([A-Z]+)$", " $1") + " ]";
                };
            };
            String org = rewrite.apply(s.replaceAll("(?s).*?(\\[单位[A-Z]+]).*", "$1"));
            String person = rewrite.apply(s.replaceAll("(?s).*?(\\[人员[A-Z]+]).*", "$1"));
            String phone = rewrite.apply(s.replaceAll("(?s).*?(\\[电话[A-Z]+]).*", "$1"));
            return "{\"field_values\":{\"customerName\":\"" + org + "\",\"contactPerson\":\"" + person
                    + "\",\"contactPhone\":\"" + phone + "\",\"remark\":\"联系人 " + person + "，电话 " + phone
                    + "\"},\"confidence\":0.95}";
        });

        FormAssistantService.FormParseResult r = service.parseFormInput(
                PROD_SENTENCE, "SALES_ORDER", salesOrderFields(), "F006");

        assertThat(said.getValue()).doesNotContain("华东冷链", "赵铁柱", "13912345678").contains("牛腱");
        assertThat(r.getFieldValues()).containsEntry("customerName", "华东冷链商贸有限公司")
                .containsEntry("contactPerson", "赵铁柱").containsEntry("contactPhone", "13912345678")
                .containsEntry("remark", "联系人 赵铁柱，电话 13912345678");
        String body = new ObjectMapper().writeValueAsString(r);
        assertThat(ANY_PLACEHOLDER.matcher(body).find()).as(body).isFalse();
    }

    @Test
    void validationFeedbackHidesSubmittedNamesAddressesAndRemarks() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), prompt.capture()))
                .thenReturn("{\"corrected_values\":{},\"explanation\":\"地址缺少门牌\",\"confidence\":0.8}");
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("customerName", "张记食品有限公司");
        submitted.put("contactPerson", "王建国");
        submitted.put("shippingAddress", "上海市松江区某路 88 号");
        submitted.put("remark", "王总说下周付款");

        FormAssistantService.ValidationFeedbackResult r = service.submitValidationFeedback("CUSTOMER", customerFields(),
                submitted, List.of(Map.of("field", "shippingAddress", "message", "地址格式不正确")), "帮我改一下", "F006");

        assertThat(prompt.getValue()).doesNotContain("张记食品", "王建国", "松江", "下周付款")
                .contains("地址格式不正确");
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void schemaGenerationHidesNamesInTheDescriptionAndJustification() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), said.capture())).thenReturn("{\"relevant\":true,\"fields\":[]}");

        service.generateSchemaWithJustification("给客户张记食品有限公司加一个对接人字段, 默认填联系人：王建国",
                "CUSTOMER", List.of("customerName"), "李经理要求, 电话 13812345678", "F006", false, 20);

        assertThat(system.getValue() + said.getValue())
                .doesNotContain("张记食品", "王建国", "李经理", "13812345678").contains("对接人");
    }

    @Test
    void secretLookupFailureMeansTheFormIsNotSent() {
        when(processSecret.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));

        FormAssistantService.FormParseResult r = service.parseFormInput(
                "新建客户张记食品有限公司", "CUSTOMER", customerFields(), "F006");

        verify(llm, never()).chatLowTemp(anyString(), anyString());
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).contains("未发给 AI");
    }
}

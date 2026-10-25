package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
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
 * 官网「数据安全」: 工厂 AI 助手把查询结果发给模型之前脱敏, 脱敏不了就不发。
 * 走真实入口 {@link WorkdeskOutputSummarizer#apply(IntentExecuteResponse, String)}, 截获实际发给模型的内容。
 */
class WorkdeskOutputSummarizerRedactionTest {

    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;
    private WorkdeskOutputSummarizer summarizer;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
        summarizer = new WorkdeskOutputSummarizer(llm, new ObjectMapper());
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
        ReflectionTestUtils.setField(summarizer, "processSecretSampleFilter", processSecret);
    }

    private static IntentExecuteResponse followupResponse() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerName", "张记食品有限公司");
        customer.put("contactPerson", "王建国");
        customer.put("phone", "13812345678");
        customer.put("receivableAmount", 12800);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("productName", "五香虎皮猪肘");
        batch.put("quantity", 300);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("_toolCount", 2);
        data.put("customers", List.of(customer));
        data.put("batches", List.of(batch));
        return IntentExecuteResponse.builder()
                .intentName("每日客户跟进")
                .formattedText("{\"_toolCount\":2,\"customers\":[{\"customerName\":\"张记食品有限公司\"}]}")
                .resultData(data)
                .build();
    }

    @Test
    void modelNeverSeesNamesOrPhoneAndUserSeesRealNames() {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(anyString(), sent.capture()))
                .thenReturn("今日跟进: [客户A] 应收 12800 元, 联系人 [人员A]; 在制 五香虎皮猪肘 300。");
        IntentExecuteResponse response = followupResponse();

        summarizer.apply(response, "F006");

        String payload = sent.getValue();
        assertThat(payload).doesNotContain("张记食品有限公司", "王建国", "13812345678", "\"phone\"");
        assertThat(payload).contains("[客户A]", "[人员A]", "12800", "五香虎皮猪肘");
        assertThat(response.getFormattedText())
                .contains("张记食品有限公司 应收 12800 元, 联系人 王建国")
                .doesNotContain("[客户A]", "[人员A]");
    }

    @Test
    void processSecretProductIsTokenisedForTheModelAndRestoredForTheUser() {
        when(processSecret.protectedProductNames("F006")).thenReturn(Set.of("五香虎皮猪肘"));
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(anyString(), sent.capture()))
                .thenReturn("今日跟进: [客户A] 应收 12800 元; 在制 [保密产品A] 300, 进度正常, 无风险。");
        IntentExecuteResponse response = followupResponse();

        summarizer.apply(response, "F006");

        assertThat(sent.getValue()).doesNotContain("五香虎皮猪肘").contains("[保密产品A]");
        assertThat(response.getFormattedText()).contains("在制 五香虎皮猪肘 300");
    }

    @Test
    void whenProtectedNamesCannotBeLoadedNothingIsSent() {
        when(processSecret.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));
        IntentExecuteResponse response = followupResponse();

        summarizer.apply(response, "F006");

        verify(llm, never()).chatFast(anyString(), anyString());
        assertThat(response.getFormattedText()).contains(WorkdeskOutputSummarizer.PRIVACY_FALLBACK_HEADER)
                .doesNotContain("_toolCount");
    }

    @Test
    void whenRedactionRefusesNothingIsSent() {
        // 手机号做了字段名, 字段名不改写 → 脱敏器拒绝 → 不得发给模型
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("_toolCount", 1);
        data.put("13812345678", Map.of("orders", 2));
        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentName("每日客户跟进")
                .formattedText("{\"_toolCount\":1}")
                .resultData(data)
                .build();

        summarizer.apply(response, "F006");

        verify(llm, never()).chatFast(anyString(), anyString());
        assertThat(response.getFormattedText()).doesNotContain("_toolCount");
    }
}

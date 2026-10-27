package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.entity.config.AIQuotaConfig;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import com.cretas.aims.repository.config.AIQuotaConfigRepository;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流式时间范围成本分析: 批次成本数据里的供应商名 / 联系人、原话里的电话, 发给 Python 分析 (再到大模型) 前换代号;
 * 回答换回真名后再过数字出处闸; 脱敏不了就不发、不扣配额。走真实入口 {@code analyzeTimeRangeCostStream}。
 */
class AIEnterpriseServiceStreamEgressTest {

    private static final String FACTORY = "F006";
    private static final String QUESTION = "华东冷链商贸有限公司那批原料为什么贵, 结论发给 13912345678";
    private static final Pattern ORG_TOKEN = Pattern.compile("\\[(?:供应商|单位|客户)[A-Z]+]");

    /** 与 Spring Boot 注入的 ObjectMapper 一致: 时间写成 ISO 字符串。 */
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private ProcessSecretSampleFilter processSecret;
    private AIEnterpriseService service;
    private PythonSmartBIClient python;
    private AIQuotaUsageRepository quota;
    private MockHttpServletRequest http;
    private java.util.concurrent.ExecutorService sse;

    @BeforeEach
    void setUp() {
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
        service = new AIEnterpriseService();
        python = mock(PythonSmartBIClient.class);
        ProcessingService processing = mock(ProcessingService.class);
        quota = mock(AIQuotaUsageRepository.class);
        AIQuotaConfigRepository quotaConfig = mock(AIQuotaConfigRepository.class);
        ReflectionTestUtils.setField(service, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(service, "processingService", processing);
        ReflectionTestUtils.setField(service, "quotaUsageRepository", quota);
        ReflectionTestUtils.setField(service, "quotaConfigRepository", quotaConfig);
        ReflectionTestUtils.setField(service, "auditLogRepository", mock(AIAuditLogRepository.class));
        ReflectionTestUtils.setField(service, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
        // 流式分析在 sseExecutor 上跑: 换成本测试自己的线程池, run() 之后等它跑完再断言 (不靠固定等待时长)
        sse = java.util.concurrent.Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(service, "sseExecutor", sse);
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(Map.of(
                        "batchNumber", "B-1",
                        "productName", "卤牛肉",
                        "costSummary", Map.of("totalCost", new BigDecimal("12.50")),
                        "materialConsumptions", List.of(Map.of(
                                "materialName", "牛腱",
                                "cost", new BigDecimal("8.00"),
                                "supplier", Map.of("id", 3, "name", "华东冷链商贸有限公司",
                                        "contactPerson", "赵铁柱"))),
                        "createdAt", LocalDateTime.of(2026, 7, 2, 10, 0))));
        when(quota.findByFactoryIdAndWeekStart(eq(FACTORY), any(LocalDate.class)))
                .thenReturn(Optional.of(AIQuotaUsage.builder().factoryId(FACTORY).usedCount(0).quotaLimit(100).build()));
        when(quotaConfig.findByFactoryIdAndQuestionType(anyString(), eq("time_range")))
                .thenReturn(List.of(AIQuotaConfig.builder().factoryId(FACTORY).questionType("time_range").quotaCost(1).build()));
        http = new MockHttpServletRequest();
        http.setAttribute("role", "finance_manager");
    }

    private SseEmitter run() throws InterruptedException {
        SseEmitter emitter = service.analyzeTimeRangeCostStream(FACTORY, 77L, LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 19, 23, 59), "overall", QUESTION, false, 0, http);
        sse.shutdown();
        assertThat(sse.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return emitter;
    }

    @Test
    void batchDataAndQuestionAreSentWithTokensAndTheAnswerNamesTheRealSupplier() throws Exception {
        doAnswer(inv -> {
            PythonSmartBIClient.GeneralAnalysisCall call = inv.getArgument(3);
            Matcher m = ORG_TOKEN.matcher(mapper.writeValueAsString(call.data()));
            assertThat(m.find()).isTrue();
            String answer = m.group() + " 的牛腱单价偏高, 建议比价。";
            PythonSmartBIClient.GeneralAnalysisEventConsumer consumer = inv.getArgument(4);
            consumer.onEvent(new PythonSmartBIClient.GeneralAnalysisStreamEvent("chunk",
                    mapper.readTree(mapper.writeValueAsString(answer))));
            consumer.onEvent(new PythonSmartBIClient.GeneralAnalysisStreamEvent("done",
                    mapper.readTree("{\"success\":true,\"answer\":" + mapper.writeValueAsString(answer) + "}")));
            return null;
        }).when(python).streamGeneralAnalysis(anyString(), nullable(String.class), nullable(String.class),
                any(PythonSmartBIClient.GeneralAnalysisCall.class), any());

        SseEmitter emitter = run();

        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> sent =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(python).streamGeneralAnalysis(eq(FACTORY), eq("77"), eq("finance_manager"),
                sent.capture(), any());
        verify(quota).incrementUsedCount(eq(FACTORY), any(LocalDate.class), eq(1));
        String data = mapper.writeValueAsString(sent.getValue().data());
        assertThat(data).doesNotContain("华东冷链", "赵铁柱").contains("牛腱", "12.5");
        assertThat(sent.getValue().query()).doesNotContain("华东冷链", "13912345678").contains("为什么贵");
        List<Map<String, Object>> payloads = payloads(emitter);
        Map<String, Object> answer = payloads.stream().filter(p -> "answer".equals(p.get("type"))).findFirst().orElseThrow();
        assertThat(String.valueOf(answer.get("content"))).startsWith("华东冷链商贸有限公司 的牛腱单价偏高");
    }

    @Test
    void nothingIsSentAndNoQuotaIsSpentWhenRedactionFails() throws Exception {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));

        SseEmitter emitter = run();

        verify(python, never()).streamGeneralAnalysis(anyString(), any(), any(), any(), any());
        verify(quota, never()).incrementUsedCount(anyString(), any(LocalDate.class), anyInt());
        // 明确告诉用户是脱敏这一步拦下的, 不是笼统的「服务不可用」
        assertThat(payloads(emitter)).anySatisfy(p -> {
            assertThat(p.get("type")).isEqualTo("error");
            assertThat(String.valueOf(p.get("message"))).contains("脱敏");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> payloads(SseEmitter emitter) throws Exception {
        Field attemptsField = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        attemptsField.setAccessible(true);
        var attempts = (Iterable<ResponseBodyEmitter.DataWithMediaType>) attemptsField.get(emitter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResponseBodyEmitter.DataWithMediaType attempt : attempts) {
            if (attempt.getData() instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }
}

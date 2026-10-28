package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.entity.config.AIQuotaConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import com.cretas.aims.repository.config.AIQuotaConfigRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.reports.OperatingReportGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出口 2 (批次成本: 单批 / 周报 / 时间范围流式 / 对比) 与出口 6 (共享分析缓存)。
 * 非流式走真实 {@link AIAnalysisService} 到桩掉的 Python 分析服务, 断言打在发出去的正文上;
 * 流式断言打在发给 Python 的正文和随附的结构化数据上。
 */
class AIEnterpriseServiceProcessSecretPromptTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 27, 23, 59);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final ProcessingService processing = mock(ProcessingService.class);
    private final AIAnalysisResultRepository results = mock(AIAnalysisResultRepository.class);
    private final AIQuotaUsageRepository quota = mock(AIQuotaUsageRepository.class);
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private ExecutorService sse;
    private final Map<Long, AIAnalysisResult> stored = new HashMap<>();
    private long nextId = 100L;

    private AIEnterpriseService service(ProcessSecretLlmCostScope scope) throws Exception {
        AIAnalysisService basic = new AIAnalysisService();
        DashScopeConfig dashScope = mock(DashScopeConfig.class);
        when(dashScope.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(basic, "dashScopeConfig", dashScope);
        ReflectionTestUtils.setField(basic, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(basic, "llmCostScope", scope);
        when(python.analyzeGeneral(anyString(), nullable(String.class), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true).answer("新分析").build());

        AIEnterpriseService service = new AIEnterpriseService();
        OperatingReportGenerator operating = mock(OperatingReportGenerator.class);
        when(operating.isFactory(anyString())).thenReturn(false);
        when(operating.usesLegacyReportContract(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "operatingReports", operating);
        ReflectionTestUtils.setField(service, "basicAIService", basic);
        ReflectionTestUtils.setField(service, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(service, "processingService", processing);
        ReflectionTestUtils.setField(service, "analysisResultRepository", results);
        ReflectionTestUtils.setField(service, "quotaUsageRepository", quota);
        AIQuotaConfigRepository quotaConfig = mock(AIQuotaConfigRepository.class);
        ReflectionTestUtils.setField(service, "quotaConfigRepository", quotaConfig);
        ReflectionTestUtils.setField(service, "auditLogRepository", mock(AIAuditLogRepository.class));
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        sse = Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(service, "sseExecutor", sse);

        when(quota.findByFactoryIdAndWeekStart(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(AIQuotaUsage.builder().factoryId(FACTORY).usedCount(0).quotaLimit(100).build()));
        when(quotaConfig.findByFactoryIdAndQuestionType(anyString(), anyString()))
                .thenReturn(List.of(AIQuotaConfig.builder().factoryId(FACTORY).questionType("x").quotaCost(1).build()));
        // 保存即分配 id, findById 读回同一行 (分档缓存索引只记 id)
        when(results.save(any(AIAnalysisResult.class))).thenAnswer(inv -> {
            AIAnalysisResult saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(nextId++);
            stored.put(saved.getId(), saved);
            return saved;
        });
        when(results.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<Long>getArgument(0))));
        ReflectionTestUtils.setField(service, "cacheService", new CacheService());   // 无 Redis: 内存缓存
        return service;
    }

    private List<String> sentToModel(int times) throws Exception {
        ArgumentCaptor<PythonGeneralAnalysisRequest> request = ArgumentCaptor.forClass(PythonGeneralAnalysisRequest.class);
        verify(python, times(times)).analyzeGeneral(eq(FACTORY), nullable(String.class), request.capture());
        return request.getAllValues().stream().map(PythonGeneralAnalysisRequest::getMessage).toList();
    }

    /** 时间范围 / 对比的批次行 (getEnhancedBatchCostAnalysis 形状里格式化要用到 batchInfo)。 */
    private static List<Map<String, Object>> enhancedRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : weeklyRows()) {
            Map<String, Object> copy = new HashMap<>(row);
            copy.put("batchInfo", Map.of("yieldRate", new BigDecimal("95")));
            rows.add(copy);
        }
        return rows;
    }

    // ---------------- 周报 (日/月/历史同一写法) ----------------

    @Test
    void weeklyReportPromptDropsProtectedBatchesAndTotalsOnlyTheRest() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getWeeklyBatchesCost(eq(FACTORY), any(), any())).thenReturn(weeklyRows());

        service.generateWeeklyReport(FACTORY, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25));

        String sent = sentToModel(1).get(0);
        BigDecimal expected = sumOfNonProtectedRows(weeklyRows());
        assertThat(sent)
                .contains("总成本: ¥" + expected.toPlainString())
                .contains("不含 1 个保密产品")
                .contains("批次总数: 2")
                .contains(NORMAL_COST.toPlainString(), NORMAL2_COST.toPlainString())
                .doesNotContain(SECRET_COST.toPlainString(), SECRET_NAME);
    }

    @Test
    void weeklyReportNegativeControlCarriesEveryBatchWhenNothingIsProtected() throws Exception {
        AIEnterpriseService service = service(unprotectedScope());
        when(processing.getWeeklyBatchesCost(eq(FACTORY), any(), any())).thenReturn(weeklyRows());

        service.generateWeeklyReport(FACTORY, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25));

        assertThat(sentToModel(1).get(0)).contains(SECRET_COST.toPlainString(), "批次总数: 3")
                .doesNotContain("保密产品");
    }

    // ---------------- 单批 ----------------

    @Test
    void singleBatchOfAProtectedProductIsRefusedBeforeCacheAndModel() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getBatchById(FACTORY, "1")).thenReturn(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN));
        MobileDTO.AICostAnalysisRequest request = new MobileDTO.AICostAnalysisRequest();
        request.setBatchId("1");
        request.setReportType("default");

        MobileDTO.AICostAnalysisResponse response = service.analyzeCost(FACTORY, 7L, request, http);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).isEqualTo(ProcessSecretLlmCostScope.WITHHELD_REASON);
        verify(python, never()).analyzeGeneral(anyString(), any(), any());
        verify(processing, never()).getBatchCostAnalysis(anyString(), anyString());
        verify(results, never()).findFirstByFactoryIdAndBatchIdAndReportTypeOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void singleBatchOfANormalProductInTheSameFactoryIsStillAnalysed() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getBatchById(FACTORY, "2")).thenReturn(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, BigDecimal.TEN));
        Map<String, Object> costData = new HashMap<>();
        costData.put("totalCost", NORMAL_COST);
        when(processing.getBatchCostAnalysis(FACTORY, "2")).thenReturn(costData);
        MobileDTO.AICostAnalysisRequest request = new MobileDTO.AICostAnalysisRequest();
        request.setBatchId("2");
        request.setReportType("default");

        MobileDTO.AICostAnalysisResponse response = service.analyzeCost(FACTORY, 7L, request, http);

        assertThat(response.getSuccess()).isTrue();
        assertThat(sentToModel(1).get(0)).contains(NORMAL_COST.toPlainString());
    }

    // ---------------- 时间范围 (流式: 正文 + 随附结构化数据) ----------------

    @Test
    void streamedTimeRangeSendsNeitherTextNorStructuredCostOfProtectedBatches() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(), any())).thenReturn(enhancedRows());
        http.setAttribute("role", "finance_manager");

        service.analyzeTimeRangeCostStream(FACTORY, 7L, START, END, "overall", null, false, 0, http);
        sse.shutdown();
        assertThat(sse.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> call =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(python).streamGeneralAnalysis(eq(FACTORY), eq("7"), eq("finance_manager"), call.capture(), any());
        String data = mapper.writeValueAsString(call.getValue().data());
        long expected = sumOfNonProtectedRows(weeklyRows()).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        assertThat(call.getValue().query())
                .contains("批次:2", "总成本:¥" + expected, "不含 1 个保密产品")
                .doesNotContain(SECRET_COST.toPlainString(), "7778");
        assertThat(data).contains(NORMAL_COST.toPlainString()).doesNotContain(SECRET_COST.toPlainString(), SECRET);
    }

    @Test
    void streamedTimeRangeForTheOwnerCarriesTheProtectedCostAndTheHarness() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(), any())).thenReturn(enhancedRows());
        http.setAttribute("role", "factory_super_admin");

        try (var ignored = as(owner())) {   // 请求线程上的调用者, 由服务带进流式工作线程
            service.analyzeTimeRangeCostStream(FACTORY, OWNER, START, END, "overall", null, false, 0, http);
        }
        sse.shutdown();
        assertThat(sse.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> call =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(python).streamGeneralAnalysis(eq(FACTORY), anyString(), anyString(), call.capture(), any());
        assertThat(call.getValue().query()).contains("批次:3", ProcessSecretLlmCostScope.HARNESS_INSTRUCTION)
                .doesNotContain("不含 1 个保密产品");
        assertThat(mapper.writeValueAsString(call.getValue().data())).contains(SECRET_COST.toPlainString());
    }

    @Test
    void streamBackstopRefusesWhenFilteringWasBypassed() throws Exception {
        // 过滤被绕过 (行原样返回) 时, 发送前的中央兜底仍拦下, 不发也不扣配额
        ProcessSecretLlmCostScope real = protectedScope();
        ProcessSecretLlmCostScope bypassed = org.mockito.Mockito.spy(real);
        org.mockito.Mockito.doAnswer(inv -> new ProcessSecretLlmCostScope.Rows(inv.getArgument(1), 0, 0))
                .when(bypassed).withoutProtectedBatches(anyString(), any());
        AIEnterpriseService service = service(bypassed);
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(), any())).thenReturn(enhancedRows());

        service.analyzeTimeRangeCostStream(FACTORY, 7L, START, END, "overall", null, false, 0, http);
        sse.shutdown();
        assertThat(sse.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        verify(bypassed).withoutProtectedBatches(eq(FACTORY), any());   // 一定会发生: 确实走到了过滤这一步
        verify(python, never()).streamGeneralAnalysis(anyString(), any(), any(), any(), any());
        verify(quota, never()).incrementUsedCount(anyString(), any(LocalDate.class), anyInt());
    }

    @Test
    void theOwnerMayAnalyseTheProtectedBatchDirectly() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getBatchById(FACTORY, "1")).thenReturn(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN));
        Map<String, Object> costData = new HashMap<>();
        costData.put("totalCost", SECRET_COST);
        when(processing.getBatchCostAnalysis(FACTORY, "1")).thenReturn(costData);
        MobileDTO.AICostAnalysisRequest request = new MobileDTO.AICostAnalysisRequest();
        request.setBatchId("1");
        request.setReportType("default");

        MobileDTO.AICostAnalysisResponse response;
        try (var ignored = as(owner())) {
            response = service.analyzeCost(FACTORY, OWNER, request, http);
        }

        assertThat(response.getSuccess()).isTrue();
        assertThat(sentToModel(1).get(0)).contains(SECRET_COST.toPlainString());
    }

    // ---------------- 对比 ----------------

    @Test
    void comparisonDropsTheProtectedBatchAndRefusesWhenFewerThanTwoRemain() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getComparativeBatchesCostAnalysis(FACTORY, List.of("1", "2", "3"))).thenReturn(enhancedRows());
        when(processing.getComparativeBatchesCostAnalysis(FACTORY, List.of("1", "2"))).thenReturn(enhancedRows().subList(0, 2));

        MobileDTO.AICostAnalysisResponse three = service.compareBatchCosts(FACTORY, 7L, List.of("1", "2", "3"), null, http);
        assertThat(three.getSuccess()).isTrue();
        assertThat(sentToModel(1).get(0)).contains("以下是2个批次", "不含 1 个保密产品")
                .doesNotContain(SECRET_COST.toPlainString(), SECRET_NAME);

        MobileDTO.AICostAnalysisResponse two = service.compareBatchCosts(FACTORY, 7L, List.of("1", "2"), null, http);
        assertThat(two.getSuccess()).isFalse();
        verify(python, times(1)).analyzeGeneral(anyString(), any(), any());
    }

    // ---------------- 共享分析缓存 ----------------

    private AIAnalysisResult cachedTimeRange() {
        AIAnalysisResult cached = AIAnalysisResult.builder().factoryId(FACTORY).reportType("time_range")
                .periodStart(START).periodEnd(END).dimension("overall")
                .analysisText("旧分析: " + SECRET_NAME + " 成本 " + SECRET_COST)
                .expiresAt(LocalDateTime.now().plusDays(3)).build();
        return cached;
    }

    private MobileDTO.AICostAnalysisResponse timeRangeAs(AIEnterpriseService service, String factory,
                                                         com.cretas.aims.security.AuthenticatedActor actor) {
        try (var ignored = as(actor)) {
            return service.analyzeTimeRangeCost(factory, 1L, START, END, "overall", null, http);
        }
    }

    @Test
    void oldCachedRowsWithoutATierAreServedToNobodyInAProtectedFactory() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(results.findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(List.of(cachedTimeRange()));
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(), any())).thenReturn(enhancedRows());

        MobileDTO.AICostAnalysisResponse owner = timeRangeAs(service, FACTORY, owner());
        MobileDTO.AICostAnalysisResponse other = timeRangeAs(service, FACTORY, sameFactoryUser());

        assertThat(owner.getAnalysis()).isEqualTo("新分析");
        assertThat(other.getAnalysis()).isEqualTo("新分析");
        sentToModel(2);   // 两次都现算 —— 一定会发生的读数
        verify(results, never()).findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(anyString(), any());
    }

    @Test
    void sameTierReusesTheCachedAnalysisDifferentTierDoesNot() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getTimeRangeBatchesCostAnalysis(eq(FACTORY), any(), any())).thenReturn(enhancedRows());

        MobileDTO.AICostAnalysisResponse first = timeRangeAs(service, FACTORY, owner());
        MobileDTO.AICostAnalysisResponse ownerAgain = timeRangeAs(service, FACTORY, owner());
        MobileDTO.AICostAnalysisResponse grantee = timeRangeAs(service, FACTORY, grantee());      // 同样完整查看 = 同档
        MobileDTO.AICostAnalysisResponse colleague = timeRangeAs(service, FACTORY, sameFactoryUser());
        MobileDTO.AICostAnalysisResponse platform = timeRangeAs(service, FACTORY, platform());     // 与同厂无权同档

        assertThat(first.getCacheHit()).isFalse();
        assertThat(ownerAgain.getCacheHit()).isTrue();
        assertThat(grantee.getCacheHit()).isTrue();
        assertThat(colleague.getCacheHit()).isFalse();
        assertThat(platform.getCacheHit()).isTrue();
        List<String> sent = sentToModel(2);   // 负责人档一次 + 无权档一次
        assertThat(sent.get(0)).contains(SECRET_COST.toPlainString());
        assertThat(sent.get(1)).doesNotContain(SECRET_COST.toPlainString()).contains("不含 1 个保密产品");
    }

    @Test
    void anotherFactoryNeverGetsThisFactorysCachedAnalysis() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getTimeRangeBatchesCostAnalysis(anyString(), any(), any())).thenReturn(enhancedRows());

        // 两边都是无权调用者 (档位哈希相同), 两个工厂都有保密产品: 只能靠缓存键里的工厂分开
        MobileDTO.AICostAnalysisResponse here = timeRangeAs(service, FACTORY, sameFactoryUser());
        MobileDTO.AICostAnalysisResponse elsewhere = timeRangeAs(service, OTHER_FACTORY, otherFactoryUser());
        MobileDTO.AICostAnalysisResponse hereAgain = timeRangeAs(service, FACTORY, platform());   // 阳性: 同厂同档确实复用

        assertThat(here.getCacheHit()).isFalse();
        assertThat(elsewhere.getCacheHit()).isFalse();
        assertThat(hereAgain.getCacheHit()).isTrue();
        verify(python).analyzeGeneral(eq(OTHER_FACTORY), nullable(String.class), any());
    }

    @Test
    void cacheNegativeControlAFactoryWithoutProtectedProductsStillServesTheCache() throws Exception {
        AIEnterpriseService service = service(unprotectedScope());
        when(results.findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(eq(FACTORY), any()))
                .thenReturn(List.of(cachedTimeRange()));

        MobileDTO.AICostAnalysisResponse response = service.analyzeTimeRangeCost(FACTORY, 2L, START, END, "overall", null, http);

        assertThat(response.getCacheHit()).isTrue();
        assertThat(response.getAnalysis()).startsWith("旧分析");
        verify(python, never()).analyzeGeneral(anyString(), any(), any());
    }

    @Test
    void comparisonCacheIsNotReadInAFactoryWithProtectedProducts() throws Exception {
        AIEnterpriseService service = service(protectedScope());
        when(processing.getComparativeBatchesCostAnalysis(FACTORY, List.of("2", "3"))).thenReturn(enhancedRows().subList(1, 3));

        service.compareBatchCosts(FACTORY, 2L, List.of("2", "3"), null, http);

        verify(results, never()).findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(anyString(), any());
        sentToModel(1);
    }
}

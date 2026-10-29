package com.cretas.aims.service;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.reports.OperatingReportGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 数据访问审计在 {@link AIEnterpriseService} 上的入口: 定时 / 手动的日周月报告 (复审 M1: 以前没开入口,
 * 排除保密批次时的「牵涉」标记落空, 报告分析读到的已是裁剪后的数据 ⇒ 记成 NONE), 以及单批分析缓存命中的口径 (L3)。
 */
class AIEnterpriseServiceAiAuditContractTest {

    private final ProcessSecretLlmCostScope scope = protectedScope();
    private final ProcessSecretAccessService access = (ProcessSecretAccessService) ReflectionTestUtils.getField(scope, "accessService");
    private final ProcessSecretAuditRecorder recorder = (ProcessSecretAuditRecorder) ReflectionTestUtils.getField(access, "auditRecorder");
    private final ProcessSecretAiAccessAudit audit = new ProcessSecretAiAccessAudit(access, scope);
    private final AIAnalysisService basic = mock(AIAnalysisService.class);

    private static Map<String, Object> batchRow(long id, String productTypeId, String planId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", id);
        row.put("batchNumber", "B-" + id);
        row.put("productTypeId", productTypeId);
        row.put("productionPlanId", planId);
        row.put("productName", SECRET.equals(productTypeId) ? SECRET_NAME : "普通" + id);
        row.put("costSummary", Map.of("totalCost", NORMAL_COST));
        return row;
    }

    private AIEnterpriseService reports(List<Map<String, Object>> batches) {
        AIEnterpriseService service = new AIEnterpriseService();
        OperatingReportGenerator operating = mock(OperatingReportGenerator.class);
        when(operating.isFactory(FACTORY)).thenReturn(false);
        ProcessingService processing = mock(ProcessingService.class);
        when(processing.getWeeklyBatchesCost(eq(FACTORY), any(), any())).thenReturn(batches);
        AIAnalysisResultRepository results = mock(AIAnalysisResultRepository.class);
        when(results.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("aiAnalysis", "报告");
        when(basic.analyzeCost(eq(FACTORY), isNull(), anyMap(), isNull(), anyString())).thenReturn(ok);
        ReflectionTestUtils.setField(service, "operatingReports", operating);
        ReflectionTestUtils.setField(service, "processingService", processing);
        ReflectionTestUtils.setField(service, "promptConfigRepository", mock(AIReportPromptConfigRepository.class));
        ReflectionTestUtils.setField(service, "analysisResultRepository", results);
        ReflectionTestUtils.setField(service, "basicAIService", basic);
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        ReflectionTestUtils.setField(service, "aiAccessAudit", audit);
        return service;
    }

    private record Row(String decision, String reason, String ref, AuthenticatedActor actor) { }

    private List<Row> aiRows() {
        ArgumentCaptor<AuthenticatedActor> actors = ArgumentCaptor.forClass(AuthenticatedActor.class);
        ArgumentCaptor<String> decisions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasons = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refs = ArgumentCaptor.forClass(String.class);
        verify(recorder, atLeast(0)).record(eq(FACTORY), isNull(), actors.capture(), eq(ProcessSecretAction.AI_ACCESS),
                decisions.capture(), reasons.capture(), refs.capture());
        List<Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < decisions.getAllValues().size(); i++) {
            rows.add(new Row(decisions.getAllValues().get(i), reasons.getAllValues().get(i), refs.getAllValues().get(i),
                    actors.getAllValues().get(i)));
        }
        return rows;
    }

    @Test
    void aScheduledDailyReportThatTrimmedAProtectedBatchIsRecordedAsProtected() {
        reports(List.of(batchRow(1, SECRET, SECRET_PLAN), batchRow(2, NORMAL, NORMAL_PLAN)))
                .generateDailyReport(FACTORY, LocalDate.of(2026, 10, 20));

        // 一定发生: 报告真的交给了模型, 而且交出去的只剩普通批次 (保密批次在这一步被排除)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(basic).analyzeCost(eq(FACTORY), isNull(), data.capture(), isNull(), anyString());
        assertThat((List<?>) data.getValue().get("batches")).hasSize(1);
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("PROTECTED");
            assertThat(row.ref()).isEqualTo("ai:report-generation");
            assertThat(row.actor()).isNull();                         // 定时任务没有调用者
            assertThat(row.reason()).startsWith("tier=NONE(ps-");     // 没有调用者 = 无权档
        });
    }

    @Test
    void negativeControlADailyReportWithoutProtectedBatchesIsRecordedAsNotProtected() {
        reports(List.of(batchRow(2, NORMAL, NORMAL_PLAN))).generateDailyReport(FACTORY, LocalDate.of(2026, 10, 20));

        verify(basic).analyzeCost(eq(FACTORY), isNull(), anyMap(), isNull(), anyString());
        assertThat(aiRows()).singleElement().satisfies(row -> assertThat(row.decision()).isEqualTo("NONE"));
    }

    @Test
    void weeklyAndMonthlyReportsAreRecordedTheSameWay() {
        AIEnterpriseService service = reports(List.of(batchRow(1, SECRET, SECRET_PLAN), batchRow(2, NORMAL, NORMAL_PLAN)));
        service.generateWeeklyReport(FACTORY, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25));
        service.generateMonthlyReport(FACTORY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        verify(basic, times(2)).analyzeCost(eq(FACTORY), isNull(), anyMap(), isNull(), anyString());
        assertThat(aiRows()).extracting(Row::decision, Row::ref).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PROTECTED", "ai:report-generation"),
                org.assertj.core.groups.Tuple.tuple("PROTECTED", "ai:report-generation"));
    }

    @Test
    void aBatchAnalysisCacheHitWithoutABatchIdFollowsTheTimeRangeRule() {
        AIEnterpriseService service = reports(List.of());
        MobileDTO.AICostAnalysisResponse cached = MobileDTO.AICostAnalysisResponse.builder().success(true).cacheHit(true).build();

        try (ProcessSecretAiAccessAudit.Access ai = audit.begin(FACTORY, ProcessSecretAiAccessAudit.Entry.ENTERPRISE_ANALYSIS)) {
            service.auditAnalyzeCostOutcome(ai, FACTORY, null, cached);
        }
        ReflectionTestUtils.setField(service, "llmCostScope", unprotectedScope());
        try (ProcessSecretAiAccessAudit.Access ai = audit.begin(FACTORY, ProcessSecretAiAccessAudit.Entry.ENTERPRISE_ANALYSIS)) {
            service.auditAnalyzeCostOutcome(ai, FACTORY, null, cached);
        }

        // 有保密产品的工厂: 缓存条目的数据看不到, 按牵涉记; 没有保密产品的工厂 (阴性对照): 不牵涉
        assertThat(aiRows()).extracting(Row::decision).containsExactly("PROTECTED", "NONE");
    }
}

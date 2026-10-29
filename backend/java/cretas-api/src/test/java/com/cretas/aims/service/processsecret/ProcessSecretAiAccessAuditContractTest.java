package com.cretas.aims.service.processsecret;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 数据访问审计 (Steve 2026-10-29 选 A): 每个 AI 请求一条 —— 调用者、档位、是否牵涉保密产品、工厂、入口。
 * 走真实入口 {@link AIAnalysisService#analyzeCost} (全部非流式成本分析的汇合点) + 真实判定链 + 真实
 * {@link ProcessSecretAccessService#audit}; 断言打在审计写入器收到的那一行上。
 */
class ProcessSecretAiAccessAuditContractTest {

    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private ProcessSecretLlmCostScope scope;
    private ProcessSecretAuditRecorder recorder;
    private ProcessSecretAiAccessAudit audit;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        scope = protectedScope();
        ProcessSecretAccessService access = (ProcessSecretAccessService) ReflectionTestUtils.getField(scope, "accessService");
        recorder = (ProcessSecretAuditRecorder) ReflectionTestUtils.getField(access, "auditRecorder");
        audit = new ProcessSecretAiAccessAudit(access, scope);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ProcessSecretAiAccessAudit.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ProcessSecretAiAccessAudit.class)).detachAppender(logs);
        RequestContextHolder.resetRequestAttributes();
    }

    private static ProductionByProductDTO stat(String id, String name) {
        return ProductionByProductDTO.builder().productTypeId(id).productName(name)
                .totalQuantity(new BigDecimal("100")).unit("kg").build();
    }

    /** 预计算上下文只有普通产品 (阴性对照要的是「这次数据确实不牵涉保密产品」)。 */
    private AIAnalysisService service() throws Exception {
        ReportService reports = mock(ReportService.class);
        when(reports.getProductionByProduct(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(stat(NORMAL, "普通甲")));
        BomService bom = mock(BomService.class);
        when(bom.getProductTypesWithBom(FACTORY)).thenReturn(List.of());
        ProductionBatchRepository contextBatches = mock(ProductionBatchRepository.class);
        when(contextBatches.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any()))
                .thenReturn(List.of(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100"))));
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        when(productTypes.findByFactoryId(FACTORY)).thenReturn(List.of());
        AIContextServiceImpl context = new AIContextServiceImpl(bom, reports, contextBatches, productTypes, scope);
        ReflectionTestUtils.setField(context, "aiAccessAudit", audit);

        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        when(batches.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN)));
        when(batches.findByIdAndFactoryId(2L, FACTORY))
                .thenReturn(Optional.of(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, BigDecimal.TEN)));

        AIAnalysisService service = new AIAnalysisService();
        DashScopeConfig dashScope = mock(DashScopeConfig.class);
        when(dashScope.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(service, "dashScopeConfig", dashScope);
        ReflectionTestUtils.setField(service, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(service, "aiContextService", context);
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        ReflectionTestUtils.setField(service, "productionBatchRepository", batches);
        ReflectionTestUtils.setField(service, "aiAccessAudit", audit);
        lenient().when(python.analyzeGeneral(anyString(), nullable(String.class), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true).answer("好的").build());
        return service;
    }

    private Map<String, Object> analyse(AuthenticatedActor actor, String batchId) throws Exception {
        AIAnalysisService service = service();
        try (var ignored = as(actor)) {
            return service.analyzeCost(FACTORY, actor.id(), batchId, new HashMap<>(), null, "分析这个批次", true, 50);
        }
    }

    /** 审计写入器收到的 AI 访问记录: {decision, reason, resourceRef, actor}。 */
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
    void theOwnerAnalysingAProtectedBatchIsRecordedOnceAsProtectedWithTheFullTier() throws Exception {
        Map<String, Object> result = analyse(owner(), "1");

        assertThat(result.get("success")).isEqualTo(true);   // 一定发生: 真的发给了模型
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("PROTECTED");
            assertThat(row.reason()).startsWith("tier=FULL(ps-").doesNotContain("withheld");
            assertThat(row.ref()).isEqualTo("ai:cost-analysis");
            assertThat(row.actor().id()).isEqualTo(OWNER);
        });
    }

    @Test
    void negativeControlAnOrdinaryRequestOnANonProtectedProductIsRecordedAsNotProtected() throws Exception {
        Map<String, Object> result = analyse(owner(), "2");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("NONE");
            assertThat(row.reason()).startsWith("tier=FULL(ps-");
        });
    }

    @Test
    void anUnauthorizedCallerIsRecordedWithTheirOwnTierAndTheWithheldOutcome() throws Exception {
        Map<String, Object> refused = analyse(sameFactoryUser(), "1");
        Map<String, Object> ordinary = analyse(sameFactoryUser(), "2");

        assertThat(refused.get("processSecret")).isEqualTo(true);
        assertThat(ordinary.get("success")).isEqualTo(true);
        List<Row> rows = aiRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).decision()).isEqualTo("PROTECTED");
        assertThat(rows.get(0).reason()).startsWith("tier=NONE(ps-").endsWith("; withheld");
        assertThat(rows.get(1).decision()).isEqualTo("NONE");
        assertThat(rows.get(1).reason()).startsWith("tier=NONE(ps-").doesNotContain("withheld");
        assertThat(rows).allSatisfy(row -> assertThat(row.actor().id()).isEqualTo(sameFactoryUser().id()));
    }

    @Test
    void aWriteFailureDoesNotBlockTheAiRequestAndIsLoggedAndCounted() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(recorder).record(anyString(), any(), any(), eq(ProcessSecretAction.AI_ACCESS), any(), any(), any());

        Map<String, Object> result = analyse(owner(), "1");

        assertThat(result.get("success")).isEqualTo(true);
        verify(python).analyzeGeneral(eq(FACTORY), nullable(String.class), any());   // 业务请求照常发出
        assertThat(audit.writeFailures()).isEqualTo(1);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("AI access audit NOT written", "entry=cost-analysis", "factory=" + FACTORY, "containsProtected=true"));
    }

    @Test
    void oneRecordPerHttpRequestWrittenWhenTheRequestCompletesWithTheFlagsMerged() throws Exception {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, owner());   // 请求线程上调用者取自请求
        ServletRequestAttributes request = new ServletRequestAttributes(http);
        RequestContextHolder.setRequestAttributes(request);
        AIAnalysisService service = service();
        try (var ignored = as(owner())) {
            // 先保密后普通: 合并后仍是「牵涉」; 若每个入口各记一条 (哪怕只剩最后一条), 读到的就是普通那条的 NONE
            service.analyzeCost(FACTORY, OWNER, "1", new HashMap<>(), null, "先看保密批次", true, 50);
            service.analyzeCost(FACTORY, OWNER, "2", new HashMap<>(), null, "再看普通批次", true, 50);
        }
        verify(python, times(2)).analyzeGeneral(eq(FACTORY), nullable(String.class), any());   // 一定发生: 两次都发了
        assertThat(aiRows()).isEmpty();   // 请求还没结束: 不写

        request.requestCompleted();

        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("PROTECTED");
            assertThat(row.ref()).isEqualTo("ai:cost-analysis");   // 第一个入口, 不是嵌套的上下文接口
        });
    }

    @Test
    void nestedEntriesOffTheRequestThreadShareTheOutermostRecord() throws Exception {
        AIAnalysisService service = service();
        try (var ignored = as(owner());
             var outer = audit.begin(FACTORY, ProcessSecretAiAccessAudit.Entry.INTENT_EXECUTION)) {
            service.analyzeCost(FACTORY, OWNER, "1", new HashMap<>(), null, "工具里调的分析", true, 50);
            assertThat(aiRows()).isEmpty();
        }

        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.ref()).isEqualTo("ai:intent-execution");
            assertThat(row.decision()).isEqualTo("PROTECTED");
        });
    }

    @Test
    void toolReadsOfAProtectedProductMarkTheRecordAndOrdinaryReadsDoNot() throws Exception {
        try (var ignored = as(owner())) {
            try (var ai = audit.begin(FACTORY, ProcessSecretAiAccessAudit.Entry.AI_TOOL)) {
                ai.involvesReads(List.of(new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, FACTORY, NORMAL)));
            }
            try (var ai = audit.begin(FACTORY, ProcessSecretAiAccessAudit.Entry.AI_TOOL)) {
                ai.involvesReads(List.of(new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, FACTORY, SECRET_PLAN)));
            }
        }
        assertThat(aiRows()).extracting(Row::decision).containsExactly("NONE", "PROTECTED");
    }
}

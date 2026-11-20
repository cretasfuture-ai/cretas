package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderListTool;
import com.cretas.aims.ai.tool.impl.quality.QualityCheckQueryTool;
import com.cretas.aims.ai.tool.impl.report.ReportBatchYieldRankTool;
import com.cretas.aims.ai.tool.impl.shipment.ShipmentQueryTool;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.ShipmentRecordService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.yield.YieldAnalysisService;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import com.cretas.aims.service.yield.balance.MaterialBalanceThresholdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对话里说的时间要真的用在查询上 (S6 验收 2026-11-03, F006):
 * 「查询上个月的销售订单」返回全部 538 条, 「查询昨天的质检记录」「查询上个月的出货记录」queryConditions 都是空的。
 *
 * <p>走对话真实使用的 {@link ToolDispatchService#executeWithTool} + <b>真的规则预处理</b>
 * ({@link QueryPreprocessorServiceImpl#preprocessRulesOnly}, 把「上个月」「昨天」归一成日期) + 真工具 + 桩服务。
 * 两条分支都走: 识别分支 (matchResult 带预处理结果) 与短语直达分支 (matchResult == null)。
 * 重复调用缓存 (ToolCallRedundancyService) 是桩, 每个样本都真跑工具 —— 线上同一会话 5 分钟内的同一句会被
 * 重放 (cached:true), 那是重放不是筛选结果。
 */
@DisplayName("对话里说的时间 → 工具按时间过滤 (S6 验收 2026-11-03)")
class DateFilterChatEntryContractTest {

    private static final String F = "F-DATE-1";
    private static final String ROLE = "factory_super_admin";
    private static final LocalDate LOSS_TODAY = LocalDate.of(2026, 11, 19);

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatchService dispatch;
    private ToolRegistry registry;
    private QueryPreprocessorServiceImpl preprocessor;

    private final LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
    private final LocalDate lastMonthEnd = lastMonthStart.withDayOfMonth(lastMonthStart.lengthOfMonth());
    private final LocalDate yesterday = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        preprocessor = new QueryPreprocessorServiceImpl(new TimeNormalizationRules(), new ColloquialMappings(),
                null, null, mapper, null, null);
        ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", preprocessor);
    }

    // ------------------------------------------------------------------ 销售订单

    @ParameterizedTest(name = "识别分支={0}")
    @ValueSource(booleans = {true, false})
    void lastMonthSalesOrdersAreFilteredByOrderDate(boolean recognitionBranch) throws Exception {
        SalesService sales = salesReturning();
        IntentExecuteResponse response = run(withMapper(new OrderListTool(sales)), "ORDER_LIST", "CRM",
                "查询上个月的销售订单", recognitionBranch, "factory_super_admin");

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        verify(sales).getSalesOrdersFiltered(eq(F), isNull(), eq(lastMonthStart), eq(lastMonthEnd), anyInt(), anyInt());
        verify(sales, never()).getSalesOrders(anyString(), anyInt(), anyInt());
    }

    @Test
    void theSameSentenceWithoutATimeUsesTheStatedSevenDayDefault() throws Exception {
        SalesService sales = salesReturning();
        IntentExecuteResponse response = run(withMapper(new OrderListTool(sales)), "ORDER_LIST", "CRM", "查询销售订单",
                true, "factory_super_admin");

        // Steve 2026-11-12 口径 1: 没说时间 = 最近 7 天, 回复写明 (以前是不限时间、返回全部订单)
        verify(sales).getSalesOrdersFiltered(eq(F), isNull(), eq(LocalDate.now().minusDays(6)), eq(LocalDate.now()),
                anyInt(), anyInt());
        // 性质: 主查询按日期过滤、不拿不限时间的整页 (size=20) 列表当结果; 默认窗口 0 条时只会另取最近 100 单找最近订单日期 (放宽逻辑)
        verify(sales, never()).getSalesOrders(anyString(), anyInt(), eq(20));
        assertThat(String.valueOf(response.getMessage())).startsWith("没说时间，按最近 7 天");
    }

    @Test
    void aRangeTheUserNeverSaidIsNotAppliedNorReportedAsSaid() throws Exception {
        // 工作台实测 (C): 句子里没有时间, 识别分支带来的预处理结果却有一段日期 ("你说的是 2026-11-18 至 2026-11-18"),
        // 被当成用户说的时间用在查询上。原话没说时间 => 走默认最近 7 天, 不用那段日期。
        SalesService sales = salesReturning();
        OrderListTool tool = withMapper(new OrderListTool(sales));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("ORDER_LIST").intentCategory("CRM")
                .intentName("ORDER_LIST").toolName(tool.getToolName()).build();
        IntentMatchResult match = IntentMatchResult.builder()
                .preprocessedQuery(preprocessor.preprocessRulesOnly("查询今天的销售订单")).build();

        dispatch.executeWithTool(tool, F, IntentExecuteRequest.builder().userInput("查询销售订单").build(),
                intent, 1L, "factory_super_admin", match);

        verify(sales).getSalesOrdersFiltered(eq(F), isNull(), eq(LocalDate.now().minusDays(6)), eq(LocalDate.now()),
                anyInt(), anyInt());
        // 阴性对照: 原话真说了「今天」, 就按今天查
        SalesService sales2 = salesReturning();
        OrderListTool tool2 = withMapper(new OrderListTool(sales2));
        dispatch.executeWithTool(tool2, F, IntentExecuteRequest.builder().userInput("查询今天的销售订单").build(),
                intent, 1L, "factory_super_admin", match);
        verify(sales2).getSalesOrdersFiltered(eq(F), isNull(), eq(LocalDate.now()), eq(LocalDate.now()), anyInt(), anyInt());
    }

    @Test
    void financeReviewOrdersAreRefusedOnTheChatPathToRolesTheAppRefuses() throws Exception {
        SalesService sales = salesReturning();
        OrderListTool tool = withMapper(new OrderListTool(sales));

        IntentExecuteResponse refused = run(tool, "ORDER_LIST", "CRM", "查询待财务审核的销售订单", true, "viewer");
        // 工具里的 403 与 RBAC 拒绝同一个状态 (以前降成 FAILED, 对话里是业务码 400)
        assertThat(refused.getStatus()).isEqualTo("PERMISSION_DENIED");
        assertThat(String.valueOf(refused.getMessage())).contains("无权查看待财审销售单");
        verifyNoInteractions(sales);

        IntentExecuteResponse allowed = run(tool, "ORDER_LIST", "CRM", "查询待财务审核的销售订单", true, "finance_manager");
        assertThat(allowed.getStatus()).as(allowed.getMessage()).isEqualTo("SUCCESS");
        verify(sales).getSalesOrdersFiltered(eq(F), eq(SalesOrderStatus.PENDING_FINANCE_REVIEW),
                eq(LocalDate.now().minusDays(6)), eq(LocalDate.now()), anyInt(), anyInt());
    }

    // ------------------------------------------------------------------ 质检记录 / 出货记录

    @ParameterizedTest(name = "识别分支={0}")
    @ValueSource(booleans = {true, false})
    void yesterdaysInspectionsAreFilteredByInspectionDate(boolean recognitionBranch) throws Exception {
        QualityInspectionService inspections = mock(QualityInspectionService.class);
        PageResponse<QualityInspection> empty = new PageResponse<>();
        empty.setContent(List.of()); empty.setTotalElements(0L); empty.setTotalPages(0);
        when(inspections.getInspectionsFiltered(anyString(), any(), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(empty);
        QualityCheckQueryTool tool = withMapper(new QualityCheckQueryTool());
        ReflectionTestUtils.setField(tool, "qualityInspectionService", inspections);

        IntentExecuteResponse response = run(tool, "QUALITY_CHECK_QUERY", "QUALITY", "查询昨天的质检记录",
                recognitionBranch, "factory_super_admin");

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        verify(inspections).getInspectionsFiltered(eq(F), any(), isNull(), eq(yesterday), eq(yesterday), isNull(),
                any(PageRequest.class));
        // 0 条: 回复点名时间条件, 不是「暂无质检记录」
        String reply = new ResultFormatterServiceImpl().format(response);
        assertThat(reply).contains(yesterday.toString()).contains("按条件筛选");
    }

    @ParameterizedTest(name = "识别分支={0}")
    @ValueSource(booleans = {true, false})
    void lastMonthShipmentsAreFilteredByShipmentDate(boolean recognitionBranch) throws Exception {
        ShipmentRecordService shipments = mock(ShipmentRecordService.class);
        when(shipments.searchFiltered(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        ShipmentQueryTool tool = withMapper(new ShipmentQueryTool());
        ReflectionTestUtils.setField(tool, "shipmentRecordService", shipments);

        IntentExecuteResponse response = run(tool, "SHIPMENT_QUERY", "SHIPMENT", "查询上个月的出货记录",
                recognitionBranch, "factory_super_admin");

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        verify(shipments).searchFiltered(eq(F), isNull(), isNull(), eq(lastMonthStart), eq(lastMonthEnd), anyInt(), anyInt());
    }

    // ------------------------------------------------------------------ 不能按时间筛的工具: 说出来

    @Test
    void aToolThatCannotFilterByTimeSaysSoInsteadOfPretending() throws Exception {
        PurchaseService purchases = mock(PurchaseService.class);
        when(purchases.getPurchaseOrders(anyString(), anyInt(), anyInt())).thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
        PurchaseOrderListTool tool = withMapper(new PurchaseOrderListTool());
        ReflectionTestUtils.setField(tool, "purchaseService", purchases);

        IntentExecuteResponse response = run(tool, "PURCHASE_ORDER_LIST", "PROCUREMENT", "查询上个月的采购订单", true,
                "factory_super_admin");
        String reply = new ResultFormatterServiceImpl().format(response);

        assertThat(reply).startsWith("注意：这个查询不能按时间筛选").contains(lastMonthStart.toString());
        // 对照: 不说时间就没有这句提示
        String plain = new ResultFormatterServiceImpl().format(
                run(tool, "PURCHASE_ORDER_LIST", "PROCUREMENT", "查询采购订单", true, "factory_super_admin"));
        assertThat(String.valueOf(plain)).doesNotContain("不能按时间筛选");
    }

    @ParameterizedTest
    @CsvSource({
            "最近一个月有哪些损耗异常, 2026-10-19, false",
            "近30天有哪些损耗异常, 2026-10-21, false",
            "近 30 天，有哪些损耗异常, 2026-10-21, false",
            "最近有哪些损耗异常, 2026-10-21, true",
            "本周有哪些损耗异常, 2026-11-16, false"
    })
    void lossAnomalySseShortcutQueriesAndReportsTheSameWindow(String sentence, String from, boolean widened)
            throws Exception {
        ProductionReportRepository reports = mock(ProductionReportRepository.class);
        when(reports.findYieldReportsForBalance(eq(F), any(), any(), eq(""), any(), any())).thenReturn(List.of(Map.of(
                "batch_number", "PB-POSITIVE", "report_date", LOSS_TODAY, "product_name", "酱牛肉",
                "process_name", "熟制杀菌", "input_quantity", BigDecimal.valueOf(100),
                "output_quantity", BigDecimal.valueOf(80), "input_unit", "kg", "output_unit", "kg")));
        AIIntentService recognizer = configureLossAnomalySse(reports);
        IntentExecuteResponse response = runSse(recognizer, sentence);

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("REPORT_BATCH_YIELD_RANK");
        verify(recognizer, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any(), any(), any(), any());
        verify(reports).findYieldReportsForBalance(eq(F), eq(LocalDate.parse(from)), eq(LOSS_TODAY),
                eq(""), any(), any());
        var result = mapper.valueToTree(response.getResultData());
        var data = result.path("data");
        var period = data.path("period");
        var applied = result.path("appliedTimeRange");
        assertThat(period.path("startDate").asText()).isEqualTo(from);
        assertThat(period.path("endDate").asText()).isEqualTo(LOSS_TODAY.toString());
        assertThat(applied.path("from").asText()).isEqualTo(period.path("startDate").asText());
        assertThat(applied.path("to").asText()).isEqualTo(period.path("endDate").asText());
        assertThat(data.path("windowWidened").asBoolean()).isEqualTo(widened);
        assertThat(data.path("abnormalCount").asInt()).isEqualTo(1);
        assertThat(response.getMessage()).contains(from + " 至 " + LOSS_TODAY, "PB-POSITIVE");
    }

    @Test
    void lossAnomalySseDistinguishesUnbatchedReportsFromNoReports() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 16);
        ProductionReportRepository reports = mock(ProductionReportRepository.class);
        when(reports.findYieldReportsForBalance(F, start, LOSS_TODAY, "",
                ProcessSecretAggregateExclusions.Scope.EMPTY.productTypeIdsOrSentinel(),
                ProcessSecretAggregateExclusions.Scope.EMPTY.planIdsOrSentinel()))
                .thenReturn(List.of(Map.of("report_date", LOSS_TODAY.minusDays(1)),
                        Map.of("report_date", LOSS_TODAY, "batch_number", " ")));
        when(reports.findYieldReportsForBalance(eq(F), any(), eq(start.minusDays(1)), eq(""), any(), any()))
                .thenReturn(List.of(Map.of("report_date", start.minusDays(1))));
        AIIntentService recognizer = configureLossAnomalySse(reports);
        IntentExecuteResponse response = runSse(recognizer, "本周有哪些损耗异常");

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("REPORT_BATCH_YIELD_RANK");
        assertThat(response.getMessage()).contains("有 2 条报工", "缺少批次号", "近一年有 1 条报工", "没法判断")
                .doesNotContain("没有任何报工记录", "近一年也没有报工记录");
        var data = mapper.valueToTree(response.getResultData()).path("data");
        assertThat(data.path("reportCountInWindow").asInt(-1)).isZero();
        assertThat(data.path("unbatchedReportCountInWindow").asInt(-1)).isEqualTo(2);
        assertThat(data.path("noReportsInWindow").isBoolean()).isTrue();
        assertThat(data.path("noReportsInWindow").asBoolean()).isFalse();
        assertThat(data.path("latestReportDate").isNull()).isTrue();
        assertThat(data.path("abnormalNearLatestReport").isNull()).isTrue();
    }

    private AIIntentService configureLossAnomalySse(ProductionReportRepository reports) {
        ProcessSecretAggregateExclusions exclusions = mock(ProcessSecretAggregateExclusions.class);
        when(exclusions.forCurrentCaller(eq(F), anyString())).thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        MaterialBalanceThresholdProvider thresholds = mock(MaterialBalanceThresholdProvider.class);
        when(thresholds.forFactory(F)).thenReturn(MaterialBalanceCalculator.Thresholds.DEFAULT);
        ReportBatchYieldRankTool tool = withMapper(new ReportBatchYieldRankTool() {
            @Override
            protected LocalDate today() {
                return LOSS_TODAY;
            }
        });
        ReflectionTestUtils.setField(tool, "yieldAnalysisService", mock(YieldAnalysisService.class));
        ReflectionTestUtils.setField(tool, "materialBalanceService",
                new MaterialBalanceService(reports, exclusions, thresholds, mapper));
        ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", new QueryPreprocessorServiceImpl(
                new TimeNormalizationRules() {
                    @Override
                    public NormalizationResult extractAndReplace(String text, LocalDateTime now) {
                        return super.extractAndReplace(text, LOSS_TODAY.atTime(12, 0));
                    }
                }, new ColloquialMappings(), null, null, mapper, null, null));

        AIIntentService recognizer = mock(AIIntentService.class);
        configureSseIntent(recognizer, "REPORT_BATCH_YIELD_RANK", tool);
        return recognizer;
    }

    @ParameterizedTest
    @ValueSource(strings = {"近两年销售额多少", "近两日销售额多少", "最近两年销售额多少", "近 30 天销售额多少"})
    void salesWithNewPrefixFormsReachesRecognitionWithoutShortcut(String sentence) throws Exception {
        AIIntentService recognizer = mock(AIIntentService.class);
        ToolExecutor sales = mock(ToolExecutor.class);
        when(sales.getToolName()).thenReturn("report_sales_margin_summary");
        configureSseIntent(recognizer, "REPORT_SALES_MARGIN_SUMMARY", sales);

        IntentExecuteResponse response = runSse(recognizer, sentence);

        verify(recognizer).recognizeIntentWithConfidence(eq(sentence), eq(F), eq(3), any(), eq(ROLE),
                any(), any(), any(), eq(List.of()));
        verify(recognizer, never()).getIntentByCode(F, "REPORT_SALES_MARGIN_SUMMARY");
        verify(registry, never()).getExecutor("report_sales_margin_summary");
        assertThat(response.getIntentRecognized()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private void configureSseIntent(AIIntentService recognizer, String code, ToolExecutor tool) {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode(code).intentName(code).intentCategory("QUERY")
                .businessType("FACTORY").sensitivityLevel("LOW").toolName(tool.getToolName()).build();
        when(recognizer.getIntentByCode(F, code)).thenReturn(Optional.of(intent));
        when(recognizer.hasPermission(code, ROLE)).thenReturn(true);
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
    }

    private IntentExecuteResponse runSse(AIIntentService recognizer, String sentence) throws Exception {
        when(recognizer.recognizeIntentWithConfidence(eq(sentence), eq(F), eq(3), any(), eq(ROLE),
                any(), any(), any(), eq(List.of()))).thenReturn(IntentMatchResult.builder()
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .build());
        IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        SemanticCacheService cache = mock(SemanticCacheService.class);
        when(cache.queryCache(F, sentence)).thenReturn(SemanticCacheHit.miss(0L));
        SseStreamingService sse = spy(new SseStreamingService(recognizer, cache, knowledge,
                mock(AnalysisRouterService.class), mapper, registry, dispatch, mock(DynamicToolSelectionService.class),
                mock(DashScopeClient.class), mock(DashScopeConfig.class)));
        BusinessTypeGate businessType = mock(BusinessTypeGate.class);
        when(businessType.resolveDomain(F)).thenReturn("FACTORY");
        when(businessType.check(anyString(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(sse, "businessTypeGate", businessType);
        ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
        IntentPermissionGate permissions = mock(IntentPermissionGate.class);
        when(permissions.check(any(), any(), anyString())).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        ReflectionTestUtils.setField(sse, "intentPermissionGate", permissions);
        doNothing().when(sse).sendSseEvent(any(), anyString(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        try {
            sse.executeStreamAsync(emitter, F, IntentExecuteRequest.builder().userInput(sentence).build(),
                    1L, ROLE, new F006BossQuestionClient.Cancellation());
            var event = ArgumentCaptor.forClass(Object.class);
            verify(sse).sendSseEvent(eq(emitter), eq("result"), event.capture());
            return (IntentExecuteResponse) event.getValue();
        } finally {
            sse.destroy();
        }
    }

    private SalesService salesReturning() {
        SalesService sales = mock(SalesService.class);
        when(sales.getSalesOrders(anyString(), anyInt(), anyInt())).thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        when(sales.getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        return sales;
    }

    private <T extends ToolExecutor> T withMapper(T tool) {
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        return tool;
    }

    private IntentExecuteResponse run(ToolExecutor tool, String intentCode, String category, String sentence,
                                      boolean recognitionBranch, String role) throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode(intentCode).intentCategory(category)
                .intentName(intentCode).toolName(tool.getToolName()).build();
        IntentMatchResult match = recognitionBranch
                ? IntentMatchResult.builder().preprocessedQuery(preprocessor.preprocessRulesOnly(sentence)).build()
                : null;
        return dispatch.executeWithTool(tool, F, IntentExecuteRequest.builder().userInput(sentence).build(),
                intent, 1L, role, match);
    }
}

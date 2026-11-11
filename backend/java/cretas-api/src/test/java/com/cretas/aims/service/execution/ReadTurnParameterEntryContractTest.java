package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.factorycatalog.ProductSalesRankingTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.material.MaterialBatchListFilter;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.ConversationMemoryServiceImpl;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INTENT-DISTILL D1: 只读续句的参数来源优先级与分页, 跑在两个产品真实入口上:
 * 普通入口 {@link IntentExecutionOrchestrator#execute} 与 SSE {@code SseStreamingService#executeStreamAsync}。
 *
 * <p>入口之下全是真的: {@link ToolDispatchService}、规则时间归一、{@link ConversationMemoryServiceImpl}
 * (仓储换成内存表)、{@link OrderListTool} / {@link MaterialBatchQueryTool} / {@link ProductSalesRankingTool};
 * 只有识别 (模型) 和数据库服务是桩。识别桩按句子返回意图 —— 本测试守的是「识别之后参数怎么合并」。
 */
@DisplayName("只读续句: 本轮 > 同一目标已确认 > 默认; 页码只由程序给出 (两个入口)")
class ReadTurnParameterEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1309L;
    private static final String ROLE = "factory_super_admin";
    private static final String SESSION = "d1-session";

    private final LocalDate today = LocalDate.now();
    private final LocalDate lastMonthStart = today.minusMonths(1).withDayOfMonth(1);
    private final LocalDate lastMonthEnd = lastMonthStart.withDayOfMonth(lastMonthStart.lengthOfMonth());
    private final LocalDate thisMonthStart = today.withDayOfMonth(1);
    private final LocalDate thisMonthEnd = today.withDayOfMonth(today.lengthOfMonth());
    private final LocalDate lastWeekStart = today.minusDays(6);

    // ------------------------------------------------------------------ 只改时间, 状态沿用

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「查上个月已确认的销售订单」→「时间改成本月」: 只改时间, 状态沿用, 页码回到默认")
    void correctingTheTimeKeepsTheConfirmedStatus(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查上个月已确认的销售订单", h.orderList);
        h.recognize("时间改成本月", h.orderList);

        IntentExecuteResponse first = h.say("查上个月已确认的销售订单");
        IntentExecuteResponse second = h.say("时间改成本月");

        assertThat(first.getStatus()).as(first.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(second.getStatus()).as(second.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), eq(SalesOrderStatus.CONFIRMED),
                eq(lastMonthStart), eq(lastMonthEnd), eq(1), eq(20));
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), eq(SalesOrderStatus.CONFIRMED),
                eq(thisMonthStart), eq(thisMonthEnd), eq(1), eq(20));
        Map<String, Object> sources = sources(second);
        assertThat(sources).containsEntry("status", "INHERITED")
                .containsEntry("startDate", "CURRENT_TURN").containsEntry("endDate", "CURRENT_TURN")
                .containsEntry("page", "DEFAULT").containsEntry("size", "DEFAULT");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("本轮明确改了状态: 本轮的状态盖过上一轮的, 时间沿用")
    void anExplicitCorrectionWinsOverTheConfirmedValue(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查上个月已确认的销售订单", h.orderList);
        h.recognize("改成已完成的", h.orderList);

        h.say("查上个月已确认的销售订单");
        IntentExecuteResponse second = h.say("改成已完成的");

        assertThat(second.getStatus()).as(second.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), eq(SalesOrderStatus.COMPLETED),
                eq(lastMonthStart), eq(lastMonthEnd), eq(1), eq(20));
        assertThat(sources(second)).containsEntry("status", "CURRENT_TURN").containsEntry("startDate", "INHERITED");
    }

    // ------------------------------------------------------------------ 换目标不继承

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「查上个月已确认的销售订单」→「查今天入库的原料」: 原料查询不继承订单的状态与时间")
    void switchingTargetInheritsNothing(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查上个月已确认的销售订单", h.orderList);
        h.recognize("查今天入库的原料", h.materialBatches);

        h.say("查上个月已确认的销售订单");
        IntentExecuteResponse second = h.say("查今天入库的原料");

        assertThat(second.getStatus()).as(second.getMessage()).isIn("SUCCESS", "COMPLETED");
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<MaterialBatchListFilter> filter = ArgumentCaptor.forClass(MaterialBatchListFilter.class);
        verify(h.materials).getMaterialBatchList(eq(FACTORY), page.capture(), filter.capture());
        assertThat(page.getValue().getStatus()).isNull();
        assertThat(filter.getValue().receiptFrom()).isEqualTo(today);
        assertThat(filter.getValue().receiptTo()).isEqualTo(today);
        assertThat(sources(second)).doesNotContainValue("INHERITED");
    }

    // ------------------------------------------------------------------ 分页

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「每页 3 条」→「下一页」: 两页窗口各 3 条、不重叠, 第二页页码由服务端给出")
    void pageSizeThreeThenNextPageGivesTwoAdjacentWindows(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查销售订单，每页3条", h.orderList);
        h.recognize("下一页", h.paginationNext);

        IntentExecuteResponse first = h.say("查销售订单，每页3条");
        IntentExecuteResponse second = h.say("下一页");

        assertThat(first.getStatus()).as(first.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(second.getStatus()).as(second.getMessage()).isIn("SUCCESS", "COMPLETED");
        // 没说时间 = 默认最近 7 天 (口径 1); 第二页沿用同一窗口
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), isNull(), eq(lastWeekStart), eq(today), eq(1), eq(3));
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), isNull(), eq(lastWeekStart), eq(today), eq(2), eq(3));
        verify(h.sales, never()).getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), eq(20));
        verify(h.sales, never()).getSalesOrders(anyString(), anyInt(), anyInt());
        List<String> pageOne = orderNumbers(first);
        List<String> pageTwo = orderNumbers(second);
        assertThat(pageOne).containsExactly("SO-1", "SO-2", "SO-3");
        assertThat(pageTwo).containsExactly("SO-4", "SO-5", "SO-6");
        assertThat(sources(first)).containsEntry("size", "CURRENT_TURN");
        assertThat(sources(second)).containsEntry("page", "SERVER_PAGE").containsEntry("size", "INHERITED");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("工具不支持按页查看: 明确拒绝, 不按默认分页悄悄执行")
    void pagingATooThatCannotPageIsRefused(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("上个月产品销售排行，看第2页", h.salesRanking);

        IntentExecuteResponse response = h.say("上个月产品销售排行，看第2页");

        assertThat(response.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(response.getMessage()).contains("不支持按页码查看");
        verify(h.ranking, never()).rank(anyString(), any(), any());
    }

    // ------------------------------------------------------------------ 不支持的口径

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("口径 6「不是金额，是数量」: 开头说明暂时只能按金额排, 同时给出按金额的排行 (时间沿用上一轮)")
    void unsupportedQuantityMetricStatesTheLimitAndStillRanksByAmount(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("上个月产品销售排行", h.salesRanking);
        h.recognize("不是金额，是数量", h.salesRanking);
        h.recognize("上个月产品销售数量和金额排行", h.salesRanking);

        IntentExecuteResponse first = h.say("上个月产品销售排行");
        IntentExecuteResponse corrected = h.say("不是金额，是数量");
        // 阴性对照: 同时点名金额 (按金额排、顺带看数量) 照常执行, 不加限制说明
        IntentExecuteResponse both = h.say("上个月产品销售数量和金额排行");

        assertThat(first.getStatus()).as(first.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(corrected.getStatus()).as(corrected.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(corrected.getMessage()).startsWith("「产品销售排行」暂时只能按金额排，不能按销售数量排");
        assertThat(corrected.getFormattedText()).startsWith("「产品销售排行」暂时只能按金额排，不能按销售数量排");
        assertThat(String.valueOf(corrected.getResultData())).contains("冻品A");
        assertThat(notices(corrected)).singleElement().satisfies(notice -> assertThat(notice)
                .containsEntry("kind", "UNSUPPORTED_OPTION").containsEntry("parameter", "metric")
                .containsEntry("requested", "quantity").containsEntry("applied", "amount"));
        assertThat(sources(corrected)).containsEntry("startDate", "INHERITED").containsEntry("endDate", "INHERITED");
        assertThat(both.getStatus()).as(both.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(both.getMetadata()).doesNotContainKey("readNotices");
        assertThat(both.getMessage()).doesNotContain("暂时只能");
        verify(h.ranking, times(3)).rank(eq(FACTORY), eq(lastMonthStart), eq(lastMonthEnd));
    }

    // ------------------------------------------------------------------ 口径 1 / 7: 默认时间与默认条数

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("口径 1+7「看看已确认的销售订单」: 没说时间按最近 7 天、每页 20 条, 回复写明时间与总条数")
    void unsaidTimeDefaultsToTheLastSevenDaysAndTwentyRowsWithTheTotal(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("看看已确认的销售订单", h.orderList);

        IntentExecuteResponse response = h.say("看看已确认的销售订单");

        assertThat(response.getStatus()).as(response.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), eq(SalesOrderStatus.CONFIRMED),
                eq(lastWeekStart), eq(today), eq(1), eq(20));
        assertThat(sources(response)).containsEntry("startDate", "DEFAULT").containsEntry("endDate", "DEFAULT")
                .containsEntry("size", "DEFAULT").containsEntry("status", "CURRENT_TURN");
        String window = lastWeekStart + " 至 " + today;
        String expectedHead = "没说时间，按最近 7 天（" + window + "）查询。\n共 45 条，这里是第 1–20 条（每页 20 条）";
        assertThat(response.getMessage()).startsWith(expectedHead);
        assertThat(response.getFormattedText()).startsWith(expectedHead);
        assertThat(notices(response)).extracting(n -> n.get("kind"))
                .containsExactly("DEFAULT_TIME_RANGE", "PAGED_RESULT");
        assertThat(notices(response).get(1)).containsEntry("total", 45L).containsEntry("size", 20);
        assertThat(orderNumbers(response)).hasSize(20);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("口径 1 阴性对照: 说了时间就按说的时间, 不加默认时间说明")
    void saidTimeIsNotReplacedByTheDefault(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查上个月的销售订单", h.orderList);

        IntentExecuteResponse response = h.say("查上个月的销售订单");

        assertThat(response.getStatus()).as(response.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.sales).getSalesOrdersFiltered(eq(FACTORY), isNull(), eq(lastMonthStart), eq(lastMonthEnd),
                eq(1), eq(20));
        assertThat(sources(response)).containsEntry("startDate", "CURRENT_TURN");
        assertThat(notices(response)).extracting(n -> n.get("kind")).containsExactly("PAGED_RESULT");
        assertThat(response.getMessage()).doesNotContain("没说时间");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("口径 7: 工具默认 10 条的列表 (原料批次) 在对话里也按 20 条一页; 不声明默认时间的工具不补时间")
    void listToolsWithASmallerDefaultPageAlsoShowTwenty(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.recognize("查原料批次", h.materialBatches);

        IntentExecuteResponse response = h.say("查原料批次");

        assertThat(response.getStatus()).as(response.getMessage()).isIn("SUCCESS", "COMPLETED");
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<MaterialBatchListFilter> filter = ArgumentCaptor.forClass(MaterialBatchListFilter.class);
        verify(h.materials).getMaterialBatchList(eq(FACTORY), page.capture(), filter.capture());
        assertThat(page.getValue().getSize()).isEqualTo(20);
        assertThat(filter.getValue().receiptFrom()).isNull();
        assertThat(sources(response)).containsEntry("size", "DEFAULT");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sources(IntentExecuteResponse response) {
        assertThat(response.getMetadata()).as("metadata of %s", response.getStatus()).isNotNull();
        Object readQuery = response.getMetadata().get(ReadTurnParameters.METADATA_KEY);
        assertThat(readQuery).as("metadata.readQuery").isInstanceOf(Map.class);
        return (Map<String, Object>) ((Map<String, Object>) readQuery).get("sources");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> notices(IntentExecuteResponse response) {
        assertThat(response.getMetadata()).as("metadata of %s", response.getStatus()).isNotNull();
        Object notices = response.getMetadata().get("readNotices");
        assertThat(notices).as("metadata.readNotices").isInstanceOf(List.class);
        return (List<Map<String, Object>>) notices;
    }

    @SuppressWarnings("unchecked")
    private static List<String> orderNumbers(IntentExecuteResponse response) {
        Object data = response.getResultData();
        assertThat(data).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) data;
        if (!map.containsKey("orders") && map.get("data") instanceof Map<?, ?> nested) {
            map = (Map<String, Object>) nested;
        }
        List<String> numbers = new ArrayList<>();
        for (Object row : (List<?>) map.get("orders")) {
            numbers.add(String.valueOf(((Map<?, ?>) row).get("orderNumber")));
        }
        return numbers;
    }

    private static final class Harness {
        private final Entry entry;
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final List<Object[]> events = new ArrayList<>();
        private final AIIntentService recognizer = mock(AIIntentService.class);
        private final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        private final ToolRegistry registry = mock(ToolRegistry.class);
        private final SalesService sales = mock(SalesService.class);
        private final MaterialBatchService materials = mock(MaterialBatchService.class);
        private final ProductSalesRankingService ranking = mock(ProductSalesRankingService.class);
        private final Map<String, AIIntentConfig> byUtterance = new HashMap<>();
        private final Map<String, ConversationMemory> rows = new HashMap<>();
        private final ConversationMemoryServiceImpl memory;
        private final ToolDispatchService dispatch;
        private final AIIntentConfig orderList;
        private final AIIntentConfig materialBatches;
        private final AIIntentConfig salesRanking;
        private final AIIntentConfig paginationNext;
        private IntentExecutionOrchestrator orchestrator;
        private SseStreamingService sse;
        private final SseEmitter emitter = mock(SseEmitter.class);
        private int sseResults;

        Harness(Entry entry) throws Exception {
            this.entry = entry;
            ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
            when(repository.findBySessionId(anyString())).thenAnswer(call ->
                    Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
            when(repository.save(any(ConversationMemory.class))).thenAnswer(call -> {
                ConversationMemory row = call.getArgument(0, ConversationMemory.class);
                rows.put(row.getSessionId(), row);
                return row;
            });
            memory = new ConversationMemoryServiceImpl(repository, null);
            ReflectionTestUtils.setField(memory, "windowSize", 10);
            ReflectionTestUtils.setField(memory, "summaryThreshold", 1000);
            ReflectionTestUtils.setField(memory, "expireMinutes", 60);
            memory.getOrCreateContext(FACTORY, USER, SESSION);

            OrderListTool orderTool = new OrderListTool(sales);
            ReflectionTestUtils.setField(orderTool, "objectMapper", mapper);
            MaterialBatchQueryTool materialTool = new MaterialBatchQueryTool();
            ReflectionTestUtils.setField(materialTool, "objectMapper", mapper);
            ReflectionTestUtils.setField(materialTool, "materialBatchService", materials);
            ProductSalesRankingTool rankingTool = new ProductSalesRankingTool(ranking);
            ReflectionTestUtils.setField(rankingTool, "objectMapper", mapper);
            for (ToolExecutor tool : List.<ToolExecutor>of(orderTool, materialTool, rankingTool)) {
                when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
            }
            when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);

            when(semanticCache.queryCache(anyString(), anyString()))
                    .thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
            stubSales();
            when(materials.getMaterialBatchList(anyString(), any(PageRequest.class), any(MaterialBatchListFilter.class)))
                    .thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
            SalesOrderItemRepository.SalesRankingRow row = mock(SalesOrderItemRepository.SalesRankingRow.class);
            when(row.getProductName()).thenReturn("冻品A");
            when(row.getQuantity()).thenReturn(BigDecimal.TEN);
            when(row.getAmount()).thenReturn(BigDecimal.valueOf(100));
            when(ranking.rank(anyString(), any(), any())).thenReturn(List.of(row));

            orderList = intent("ORDER_LIST", "销售订单查询", "order_list");
            materialBatches = intent("MATERIAL_BATCH_QUERY", "原料批次查询", "material_batch_query");
            salesRanking = intent("PRODUCT_SALES_RANKING", "产品销售排行", "product_sales_ranking");
            paginationNext = intent("PAGINATION_NEXT", "下一页", "pagination_next");
            for (AIIntentConfig intent : List.of(orderList, materialBatches, salesRanking, paginationNext)) {
                when(recognizer.getIntentByCode(FACTORY, intent.getIntentCode())).thenReturn(Optional.of(intent));
                when(recognizer.getIntentByCode(intent.getIntentCode())).thenReturn(Optional.of(intent));
                when(recognizer.hasPermission(intent.getIntentCode(), ROLE)).thenReturn(true);
                when(recognizer.hasPermission(FACTORY, intent.getIntentCode(), ROLE)).thenReturn(true);
            }
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));

            ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
            when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
            dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class), redundancy,
                    mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                    mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                    mock(ParameterExtractionLearningService.class));
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
            when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
            ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", new QueryPreprocessorServiceImpl(
                    new TimeNormalizationRules(), new ColloquialMappings(), null, null, mapper, null, null));
            ReflectionTestUtils.setField(dispatch, "conversationMemoryService", memory);
            com.cretas.aims.service.impl.ResultFormatterServiceImpl formatter =
                    new com.cretas.aims.service.impl.ResultFormatterServiceImpl();
            com.cretas.aims.security.PriceFieldResponseAdvice priceView =
                    mock(com.cretas.aims.security.PriceFieldResponseAdvice.class);
            when(priceView.maskedViewFor(any(), any())).thenAnswer(call -> call.getArgument(0));
            ReflectionTestUtils.setField(formatter, "priceView", priceView);

            IntentPermissionGate gate = mock(IntentPermissionGate.class);
            when(gate.check(any(), any(), anyString())).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            when(businessTypeGate.resolveDomain(FACTORY)).thenReturn("FACTORY");
            if (entry == Entry.ORDINARY) {
                orchestrator = new IntentExecutionOrchestrator(
                        recognizer, mock(IntentSemanticsParser.class), semanticCache,
                        mock(RuleEngineService.class), mock(ConversationService.class), memory,
                        mapper, mock(DashScopeClient.class), mock(DashScopeConfig.class),
                        mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), registry,
                        mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                        mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), dispatch,
                        mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
                ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
                ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
                IntentConfigManagementService config = mock(IntentConfigManagementService.class);
                when(config.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
                ReflectionTestUtils.setField(orchestrator, "configService", config);
                ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
                ReflectionTestUtils.setField(orchestrator, "resultFormatterService", formatter);
                ReflectionTestUtils.setField(orchestrator, "recognitionHistoryResolver",
                        new RecognitionHistoryResolver(memory));
                OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
                FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
                for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
                    ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
                }
            } else {
                FactoryRepository factories = mock(FactoryRepository.class);
                Factory factory = new Factory();
                factory.setId(FACTORY);
                factory.setType(FactoryType.FACTORY);
                factory.setIsActive(true);
                when(factories.findById(anyString())).thenReturn(Optional.of(factory));
                sse = org.mockito.Mockito.spy(new SseStreamingService(
                        recognizer, semanticCache, mock(IntentKnowledgeBase.class),
                        mock(AnalysisRouterService.class), mapper, registry, dispatch,
                        mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
                ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                        factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
                ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
                ReflectionTestUtils.setField(sse, "businessTypeGate", businessTypeGate);
                ReflectionTestUtils.setField(sse, "intentPermissionGate", gate);
                ReflectionTestUtils.setField(sse, "conversationMemoryService", memory);
                ReflectionTestUtils.setField(sse, "resultFormatterService", formatter);
                org.mockito.Mockito.doAnswer(call -> {
                    events.add(new Object[]{call.getArgument(1), call.getArgument(2)});
                    return null;
                }).when(sse).sendSseEvent(any(), anyString(), any());
            }
        }

        private void stubSales() {
            when(sales.getSalesOrders(anyString(), anyInt(), anyInt())).thenAnswer(call ->
                    page(call.getArgument(1, Integer.class), call.getArgument(2, Integer.class)));
            when(sales.getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), anyInt())).thenAnswer(call ->
                    page(call.getArgument(4, Integer.class), call.getArgument(5, Integer.class)));
        }

        /** 45 张订单 SO-1..SO-45, 按 (页码, 每页条数) 切出真实窗口。 */
        private static PageResponse<SalesOrder> page(int page, int size) {
            List<SalesOrder> rows = IntStream.rangeClosed((page - 1) * size + 1, Math.min(45, page * size))
                    .mapToObj(n -> {
                        SalesOrder order = new SalesOrder();
                        order.setId("id-" + n);
                        order.setOrderNumber("SO-" + n);
                        return order;
                    }).toList();
            return PageResponse.of(rows, page, size, 45L);
        }

        private static AIIntentConfig intent(String code, String name, String tool) {
            return AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("QUERY")
                    .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                    .toolName(tool).isActive(true).build();
        }

        void recognize(String utterance, AIIntentConfig intent) {
            byUtterance.put(utterance, intent);
        }

        private IntentMatchResult matched(String utterance) {
            AIIntentConfig intent = byUtterance.get(utterance);
            if (intent == null) return IntentMatchResult.empty(utterance);
            return IntentMatchResult.builder().bestMatch(intent).confidence(0.99d)
                    .matchMethod(IntentMatchResult.MatchMethod.KEYWORD).userInput(utterance)
                    .requiresConfirmation(false).build();
        }

        IntentExecuteResponse say(String utterance) throws Exception {
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(utterance).sessionId(SESSION).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    return orchestrator.execute(FACTORY, request, USER, ROLE);
                }
                ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", emitter, FACTORY, request, USER, ROLE);
            }
            Object result = null;
            for (int i = sseResults; i < events.size(); i++) {
                if ("result".equals(events.get(i)[0])) result = events.get(i)[1];
            }
            List<String> seen = events.subList(sseResults, events.size()).stream()
                    .map(e -> e[0] + "=" + String.valueOf(e[1]).substring(0, Math.min(300, String.valueOf(e[1]).length())))
                    .toList();
            sseResults = events.size();
            assertThat(result).as("SSE result event for %s; events=%s", utterance, seen)
                    .isInstanceOf(IntentExecuteResponse.class);
            return (IntentExecuteResponse) result;
        }
    }
}

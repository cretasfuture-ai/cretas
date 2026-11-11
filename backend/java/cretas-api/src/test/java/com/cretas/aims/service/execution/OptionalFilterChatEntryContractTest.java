package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.alert.AlertListTool;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderListTool;
import com.cretas.aims.ai.tool.impl.shipment.ShipmentQueryTool;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.material.MaterialBatchListFilter;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.EquipmentAlertsService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ShipmentRecordService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-10 同因扫描: 对话里说的可选筛选到不了工具。
 *
 * <p>走对话真实使用的那一段 {@link ToolDispatchService#executeWithTool} (识别分支与短语直达分支在此汇合),
 * 真工具 + 桩服务, 请求里只有用户原话, 大模型桩不给任何参数 —— 断言工具调用服务时带上了句子里说的状态,
 * 以及短语直达分支 (matchResult == null) 也补上了「这个批次」「上个月」。
 */
@DisplayName("对话里说的可选筛选 → 工具参数 (ERP-S6-10 同因)")
class OptionalFilterChatEntryContractTest {

    private static final String F = "F-OPT-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ToolDispatchService dispatch;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, llm, mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
    }

    // ------------------------------------------------------------------ 状态

    @Test
    void salesOrdersByTheStatusSaidInChinese() throws Exception {
        SalesService sales = mock(SalesService.class);
        when(sales.getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        OrderListTool tool = withMapper(new OrderListTool(sales));

        assertThat(run(tool, "ORDER_LIST", "查询已确认的销售订单", null).getStatus()).isEqualTo("SUCCESS");

        // 没说时间: 默认最近 7 天 (Steve 2026-11-12 口径 1)
        verify(sales).getSalesOrdersFiltered(eq(F), eq(SalesOrderStatus.CONFIRMED),
                eq(java.time.LocalDate.now().minusDays(6)), eq(java.time.LocalDate.now()), anyInt(), anyInt());
        verify(sales, never()).getSalesOrders(anyString(), anyInt(), anyInt());
        verify(llm, never()).chatWithTools(anyString(), anyString(), anyList());
    }

    @Test
    void noStatusSaidMeansNoStatusFilter() throws Exception {
        SalesService sales = mock(SalesService.class);
        when(sales.getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));
        OrderListTool tool = withMapper(new OrderListTool(sales));

        assertThat(run(tool, "ORDER_LIST", "查询销售订单", null).getStatus()).isEqualTo("SUCCESS");

        // 没说状态 = 不按状态筛; 没说时间 = 默认最近 7 天 (Steve 2026-11-12 口径 1)
        verify(sales).getSalesOrdersFiltered(eq(F), isNull(), eq(java.time.LocalDate.now().minusDays(6)),
                eq(java.time.LocalDate.now()), anyInt(), anyInt());
        verify(sales, never()).getSalesOrders(anyString(), anyInt(), anyInt());
    }

    @Test
    void aStatusSaidButNotRecognisedIsSaidBackAndNothingIsQueried() throws Exception {
        SalesService sales = mock(SalesService.class);
        OrderListTool tool = withMapper(new OrderListTool(sales));

        IntentExecuteResponse response = run(tool, "ORDER_LIST", "查询冻结状态的销售订单", null);

        assertThat(response.getStatus()).isNotEqualTo("SUCCESS");
        assertThat(String.valueOf(response.getMessage())).contains("冻结").contains("没有按状态查询")
                .contains("能查的状态").contains("已确认(CONFIRMED)").contains("已取消(CANCELLED)");
        verifyNoInteractions(sales);
    }

    @Test
    void shipmentsByTheStatusSaid() throws Exception {
        ShipmentRecordService shipments = mock(ShipmentRecordService.class);
        when(shipments.searchFiltered(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        ShipmentQueryTool tool = withMapper(new ShipmentQueryTool());
        ReflectionTestUtils.setField(tool, "shipmentRecordService", shipments);

        assertThat(run(tool, "SHIPMENT_QUERY", "看看已发货的出货单", null).getStatus()).isEqualTo("SUCCESS");

        verify(shipments).searchFiltered(eq(F), isNull(), eq("shipped"), isNull(), isNull(), anyInt(), anyInt());
        verify(shipments, never()).getByFactoryId(anyString(), anyInt(), anyInt());
    }

    @Test
    void anEmptyFilteredResultNamesTheFilterForAnyToolOnTheSharedMechanism() throws Exception {
        ShipmentRecordService shipments = mock(ShipmentRecordService.class);
        when(shipments.searchFiltered(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        ShipmentQueryTool tool = withMapper(new ShipmentQueryTool());
        ReflectionTestUtils.setField(tool, "shipmentRecordService", shipments);

        IntentExecuteResponse response = run(tool, "SHIPMENT_QUERY", "看看已发货的出货单", null);
        String reply = new com.cretas.aims.service.impl.ResultFormatterServiceImpl().format(response);

        assertThat(reply).contains("没有状态为「已发货」的记录").contains("按状态筛选").doesNotContain("当前暂无出货记录");
    }

    @Test
    void purchaseOrdersByTheStatusSaid() throws Exception {
        PurchaseService purchases = mock(PurchaseService.class);
        when(purchases.getPurchaseOrdersByStatus(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
        PurchaseOrderListTool tool = withMapper(new PurchaseOrderListTool());
        ReflectionTestUtils.setField(tool, "purchaseService", purchases);

        assertThat(run(tool, "PURCHASE_ORDER_LIST", "收货中的采购订单有哪些", null).getStatus()).isEqualTo("SUCCESS");

        verify(purchases).getPurchaseOrdersByStatus(eq(F), eq(PurchaseOrderStatus.PARTIAL_RECEIVED), anyInt(), anyInt());
    }

    @Test
    void alertsByTheStatusSaid() throws Exception {
        EquipmentAlertsService alerts = mock(EquipmentAlertsService.class);
        when(alerts.getAlertList(anyString(), any(), any(), any(), any()))
                .thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
        AlertListTool tool = withMapper(new AlertListTool());
        ReflectionTestUtils.setField(tool, "equipmentAlertsService", alerts);

        assertThat(run(tool, "ALERT_LIST", "未处理的告警有哪些", null).getStatus()).isEqualTo("SUCCESS");

        verify(alerts).getAlertList(eq(F), any(), any(), any(), eq("ACTIVE"));
    }

    // ------------------------------------------------------------------ 短语直达分支: 指代 + 时间

    @Test
    void theShortcutBranchFillsTheBatchReferenceAndTheTimeRange() throws Exception {
        MaterialBatchService batches = mock(MaterialBatchService.class);
        when(batches.getMaterialBatchList(anyString(), any(), any(MaterialBatchListFilter.class)))
                .thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
        MaterialBatchQueryTool tool = withMapper(new MaterialBatchQueryTool());
        ReflectionTestUtils.setField(tool, "materialBatchService", batches);

        QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        PreprocessedQuery rules = PreprocessedQuery.builder()
                .originalInput("上个月这个批次的入库情况")
                .finalQuery("上个月这个批次的入库情况")
                .primaryTimeRange(TimeNormalizationRules.TimeRange.of(
                        LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 31, 23, 59)))
                .build();
        when(preprocessor.preprocessRulesOnly("上个月这个批次的入库情况")).thenReturn(rules);
        ConversationContext session = new ConversationContext();
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.getContext("S-1")).thenReturn(session);
        when(preprocessor.resolveReferences("上个月这个批次的入库情况", session)).thenReturn(
                QueryPreprocessorService.ReferenceResolutionResult.builder()
                        .hasResolutions(true)
                        .resolvedEntities(Map.of("这个批次", QueryPreprocessorService.ResolvedEntity.builder()
                                .entityType("BATCH").entityId("MB-9").entityName("B20261005-01").build()))
                        .build());
        ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", preprocessor);
        ReflectionTestUtils.setField(dispatch, "conversationMemoryService", memory);

        // matchResult == null: 短语直达 / 显式意图分支
        assertThat(run(tool, "MATERIAL_BATCH_QUERY", "上个月这个批次的入库情况", "S-1").getStatus())
                .isEqualTo("SUCCESS");

        ArgumentCaptor<PageRequest> request = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<MaterialBatchListFilter> filter = ArgumentCaptor.forClass(MaterialBatchListFilter.class);
        verify(batches).getMaterialBatchList(eq(F), request.capture(), filter.capture());
        assertThat(request.getValue().getKeyword()).isEqualTo("B20261005-01");
        assertThat(filter.getValue().receiptFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(filter.getValue().receiptTo()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void withoutASessionTheShortcutBranchStillFillsTheTimeRange() throws Exception {
        MaterialBatchService batches = mock(MaterialBatchService.class);
        when(batches.getMaterialBatchList(anyString(), any(), any(MaterialBatchListFilter.class)))
                .thenReturn(PageResponse.of(List.of(), 1, 10, 0L));
        MaterialBatchQueryTool tool = withMapper(new MaterialBatchQueryTool());
        ReflectionTestUtils.setField(tool, "materialBatchService", batches);
        QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        when(preprocessor.preprocessRulesOnly("上个月的原料批次")).thenReturn(PreprocessedQuery.builder()
                .originalInput("上个月的原料批次").finalQuery("上个月的原料批次")
                .primaryTimeRange(TimeNormalizationRules.TimeRange.of(
                        LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 31, 23, 59)))
                .build());
        ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", preprocessor);

        assertThat(run(tool, "MATERIAL_BATCH_QUERY", "上个月的原料批次", null).getStatus()).isEqualTo("SUCCESS");

        ArgumentCaptor<MaterialBatchListFilter> filter = ArgumentCaptor.forClass(MaterialBatchListFilter.class);
        verify(batches).getMaterialBatchList(eq(F), any(), filter.capture());
        assertThat(filter.getValue().receiptFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(preprocessor, never()).resolveReferences(anyString(), any());
    }

    private <T extends ToolExecutor> T withMapper(T tool) {
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        return tool;
    }

    private IntentExecuteResponse run(ToolExecutor tool, String intentCode, String sentence, String sessionId)
            throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode(intentCode).intentCategory("QUERY")
                .intentName(intentCode).toolName(tool.getToolName()).build();
        return dispatch.executeWithTool(tool, F,
                IntentExecuteRequest.builder().userInput(sentence).sessionId(sessionId).build(),
                intent, 1L, "factory_super_admin", null);
    }
}

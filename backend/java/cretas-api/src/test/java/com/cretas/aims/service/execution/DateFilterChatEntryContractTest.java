package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderListTool;
import com.cretas.aims.ai.tool.impl.quality.QualityCheckQueryTool;
import com.cretas.aims.ai.tool.impl.shipment.ShipmentQueryTool;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QualityInspectionService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatchService dispatch;
    private QueryPreprocessorServiceImpl preprocessor;

    private final LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
    private final LocalDate lastMonthEnd = lastMonthStart.withDayOfMonth(lastMonthStart.lengthOfMonth());
    private final LocalDate yesterday = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
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
        verify(sales, never()).getSalesOrders(anyString(), anyInt(), anyInt());
        assertThat(String.valueOf(response.getMessage())).startsWith("没说时间，按最近 7 天");
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

    // ------------------------------------------------------------------ helpers

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

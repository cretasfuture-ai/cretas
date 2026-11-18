package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.foodsafety.BatchFullTraceTool;
import com.cretas.aims.ai.tool.impl.workdesk.AccountsReceivableAgingTool;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-18 LSMDEMO 演示前复测: AI 回复把内部东西原样露给用户。
 * 1 应收应付 / 2 毛利: REPORT/ANALYSIS 意图没有专用格式化器时, 通用兜底丢掉工具自己写好的 message,
 *   把 data 的字段名拼成「owingCustomerCount: 7 | totalAmount: 453,066.4」。
 * 3 销售订单状态列外露 FINANCE_REJECTED 等英文枚举。
 * 4 缺批次号时把参数描述里写给模型的提示语原样当成回复。
 * 断言: 回复文本不含驼峰英文字段名 / 英文状态枚举 / 提示语原文, 且含该有的中文要素。
 */
class ReplyRenderingLeakContractTest {

    private static final Pattern CAMEL_KEY = Pattern.compile("\\b[a-z]+[A-Z][A-Za-z0-9]*\\b");
    private static final Pattern ENUM_CODE = Pattern.compile("\\b[A-Z]+(_[A-Z]+)+\\b");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultFormatterServiceImpl formatter = new ResultFormatterServiceImpl();

    private AccountsReceivableAgingTool arTool;
    private CustomerRepository customers;
    private ArApService arAp;
    private PriceFieldResponseAdvice view;

    @BeforeEach
    void setUp() {
        arTool = new AccountsReceivableAgingTool();
        customers = mock(CustomerRepository.class);
        arAp = mock(ArApService.class);
        FinanceRowScopeQuery scope = mock(FinanceRowScopeQuery.class);
        view = mock(PriceFieldResponseAdvice.class);
        when(view.showsKey(any(), any())).thenReturn(true);
        when(scope.creators("F1")).thenReturn(null);
        ReflectionTestUtils.setField(arTool, "customerRepository", customers);
        ReflectionTestUtils.setField(arTool, "arApService", arAp);
        ReflectionTestUtils.setField(arTool, "financeRowScopeQuery", scope);
        ReflectionTestUtils.setField(arTool, "priceView", view);
        ReflectionTestUtils.setField(arTool, "objectMapper", mapper);
    }

    private static Customer customer(String name, String balance) {
        Customer c = new Customer();
        c.setName(name);
        c.setCurrentBalance(new BigDecimal(balance));
        return c;
    }

    private static Map<String, Object> agingRow(String name, String total, String days1to30, String days61to90) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("counterpartyName", name);
        row.put("current", BigDecimal.ZERO);
        row.put("days1_30", new BigDecimal(days1to30));
        row.put("days31_60", BigDecimal.ZERO);
        row.put("days61_90", new BigDecimal(days61to90));
        row.put("days91_120", BigDecimal.ZERO);
        row.put("days120plus", BigDecimal.ZERO);
        row.put("total", new BigDecimal(total));
        return row;
    }

    /** 真工具执行 → 取出派发层交给格式化器的那层 {message, data}。 */
    private Map<String, Object> runArToolPayload() throws Exception {
        String json = arTool.execute(ToolCall.of("t1", "accounts_receivable_aging", "{}"),
                Map.of("factoryId", "F1", "userId", 1L));
        Map<String, Object> outer = mapper.readValue(json, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) outer.get("data");
        return payload;
    }

    private String format(String intentCode, String category, Object resultData) {
        return formatter.format(IntentExecuteResponse.builder()
                .intentCode(intentCode).intentCategory(category).status("COMPLETED").resultData(resultData).build());
    }

    private static void assertNoInternalNames(String text) {
        assertThat(CAMEL_KEY.matcher(text).find()).as("回复不许含驼峰英文字段名: %s", text).isFalse();
        assertThat(ENUM_CODE.matcher(text).find()).as("回复不许含英文状态枚举: %s", text).isFalse();
    }

    @Test
    void receivableAndPayableReply_hasNoFieldNames_andAnswersPayableAndOverdue() throws Exception {
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of(
                customer("鲜湘缘", "12000"), customer("味美", "8000")));
        when(customers.findByFactoryId("F1")).thenReturn(List.of(customer("鲜湘缘", "12000"), customer("味美", "8000")));
        when(arAp.getAgingAnalysis("F1", CounterpartyType.CUSTOMER)).thenReturn(List.of(
                agingRow("鲜湘缘", "12000", "5000", "0"), agingRow("味美", "8000", "0", "0")));
        when(arAp.getAgingAnalysis("F1", CounterpartyType.SUPPLIER)).thenReturn(List.of(
                agingRow("供应商甲", "3000", "0", "1000")));

        String text = format("ACCOUNTS_RECEIVABLE_AGING", "ANALYSIS", runArToolPayload());

        assertNoInternalNames(text);
        assertThat(text).contains("欠款最多的是 鲜湘缘", "合计 ¥20000.00");
        assertThat(text).contains("逾期应收").contains("1 家客户").contains("¥5000.00");
        assertThat(text).contains("应付未结清 1 家供应商").contains("¥3000.00").contains("其中已逾期 1 家");
    }

    @Test
    void receivableAndPayableReply_withoutAmountView_staysWithoutMoney() throws Exception {
        when(view.showsKey(any(), any())).thenReturn(false);
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of(customer("鲜湘缘", "12000")));
        when(customers.findByFactoryId("F1")).thenReturn(List.of(customer("鲜湘缘", "12000")));
        when(arAp.getAgingAnalysis("F1", CounterpartyType.CUSTOMER)).thenReturn(List.of(agingRow("鲜湘缘", "12000", "5000", "0")));
        when(arAp.getAgingAnalysis("F1", CounterpartyType.SUPPLIER)).thenReturn(List.of(agingRow("供应商甲", "3000", "0", "1000")));

        String text = format("ACCOUNTS_RECEIVABLE_AGING", "ANALYSIS", runArToolPayload());

        assertNoInternalNames(text);
        assertThat(text).doesNotContain("¥").doesNotContain("12000").doesNotContain("5000").doesNotContain("3000");
        assertThat(text).contains("逾期应收").contains("应付未结清 1 家供应商");
    }

    @Test
    void grossMarginReply_usesToolSentence_notFieldDump() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCount", 3);
        data.put("revenue", new BigDecimal("280553.60"));
        data.put("costedRevenue", new BigDecimal("200000.00"));
        data.put("marginRate", new BigDecimal("47.43"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "2026-11-01 至 2026-11-18 销售额 ¥280553.60（共 3 个产品）。\n毛利 ¥94859.12，毛利率 47.43%。");
        payload.put("data", data);

        String text = format("REPORT_SALES_MARGIN_SUMMARY", "REPORT", payload);

        assertNoInternalNames(text);
        assertThat(text).contains("销售额 ¥280553.60", "3 个产品", "毛利率 47.43%");
    }

    @Test
    void reportFallbackWithoutMessage_usesChineseLabels_andDropsUnknownKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCount", 3);
        data.put("marginRate", new BigDecimal("47.43"));
        data.put("someInternalCounter", 9);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", data);

        String text = format("REPORT_SALES_MARGIN_SUMMARY", "REPORT", payload);

        assertNoInternalNames(text);
        assertThat(text).contains("产品数: 3", "毛利率(%): 47.43");
    }

    @Test
    void salesOrderList_showsChineseStatus_notEnglishEnum() {
        List<Map<String, Object>> orders = List.of(
                order("SO-1", "FINANCE_REJECTED"), order("SO-2", "PENDING_FINANCE_REVIEW"),
                order("SO-3", "PARTIAL_DELIVERED"), order("SO-4", "SHORT_CLOSED"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", orders);
        data.put("totalElements", 4);

        String text = format("ORDER_LIST", "CRM", data);

        assertNoInternalNames(text);
        assertThat(text).contains("财务已驳回", "待财务审核", "部分发货", "短交结案");
    }

    private static Map<String, Object> order(String no, String status) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("orderNumber", no);
        o.put("customerName", "味美");
        o.put("status", status);
        return o;
    }

    @Test
    void batchTraceWithoutBatchNumber_asksUserInPlainWords_notPromptText() throws Exception {
        BatchFullTraceTool trace = new BatchFullTraceTool();
        ReflectionTestUtils.setField(trace, "objectMapper", mapper);

        String json = trace.execute(ToolCall.of("t1", "batch_full_trace", "{}"), Map.of("factoryId", "F1", "userId", 1L));

        assertThat(json).contains("请告诉我成品批次号", "原料批次和供应商");
        assertThat(json).doesNotContain("不要猜").doesNotContain("追问");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) trace.getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        String label = (String) ((Map<String, Object>) props.get("batchNumber")).get("description");
        assertThat(label).doesNotContain("不要猜").doesNotContain("追问");
    }
}

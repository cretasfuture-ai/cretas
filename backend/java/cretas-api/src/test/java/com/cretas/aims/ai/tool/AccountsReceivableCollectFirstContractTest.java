package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.workdesk.AccountsReceivableAgingTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 48: 问「应收逾期怎么催」, 以前先列 4 个客户欠款 (客户余额口径 ¥22,276), 再附逾期 3 家 ¥49,086 (账龄口径),
 * 两种口径混在一起, 也没说先催哪几张单。现在: 逾期总数在前 (账龄口径), 紧跟「建议先催」单据 (单号/客户/金额/逾期天数/理由),
 * 客户余额排名另起一段并标明口径名称。真实工具入口 execute()。
 */
class AccountsReceivableCollectFirstContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AccountsReceivableAgingTool tool;
    private CustomerRepository customers;
    private ArApService arAp;
    private PriceFieldResponseAdvice view;

    @BeforeEach
    void setUp() {
        tool = new AccountsReceivableAgingTool();
        customers = mock(CustomerRepository.class);
        arAp = mock(ArApService.class);
        FinanceRowScopeQuery scope = mock(FinanceRowScopeQuery.class);
        view = mock(PriceFieldResponseAdvice.class);
        when(view.showsKey(any(), any())).thenReturn(true);
        when(scope.creators("F1")).thenReturn(null);
        ReflectionTestUtils.setField(tool, "customerRepository", customers);
        ReflectionTestUtils.setField(tool, "arApService", arAp);
        ReflectionTestUtils.setField(tool, "financeRowScopeQuery", scope);
        ReflectionTestUtils.setField(tool, "priceView", view);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of(
                customer("鲜湘缘", "12000"), customer("味美", "8000"), customer("老张", "2276")));
        when(customers.findByFactoryId("F1")).thenReturn(List.of(customer("鲜湘缘", "12000")));
        when(arAp.getAgingAnalysis("F1", CounterpartyType.SUPPLIER)).thenReturn(List.of());
        // 三张逾期单, 来自三家客户: AR-3 逾期最久且金额最大 (天数x金额最高), AR-1 次之, AR-2 最小
        when(arAp.getOverdueReceivableDocuments("F1")).thenReturn(List.of(
                doc("AR-1", "味美", "9000", 40), doc("AR-2", "老张", "1000", 5), doc("AR-3", "鲜湘缘", "39086", 90)));
    }

    private static Customer customer(String name, String balance) {
        Customer c = new Customer();
        c.setName(name);
        c.setCurrentBalance(new BigDecimal(balance));
        return c;
    }

    private static Map<String, Object> doc(String no, String customer, String amount, long days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionNumber", no);
        m.put("counterpartyName", customer);
        m.put("outstanding", new BigDecimal(amount));
        m.put("daysOverdue", days);
        return m;
    }

    private String run(String args) throws Exception {
        return runPayload(args).get("message").asText();
    }

    private JsonNode runPayload(String args) throws Exception {
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", "accounts_receivable_aging", args),
                Map.of("factoryId", "F1", "userId", 1L)));
        return r.get("data");
    }

    @Test
    void owedRankingLeadsWithOverdueCollectFirstAndLabelsTheOtherBasis() throws Exception {
        String m = run("{}");
        // 先给逾期总数 (账龄口径): 3 家客户、3 张、合计 49086
        assertTrue(m.contains("逾期应收共 3 家客户、3 张单据，合计 ¥49086.00"), m);
        // 建议先催: 单号 / 客户 / 金额 / 逾期天数 / 理由, 按 天数x金额 排序
        assertTrue(m.contains("建议先催"), m);
        assertTrue(m.indexOf("AR-3") < m.indexOf("AR-1") && m.indexOf("AR-1") < m.indexOf("AR-2"), m);
        assertTrue(m.contains("单号 AR-3，客户 鲜湘缘，金额 ¥39086.00，逾期 90 天，理由：逾期最久且金额最大"), m);
        // 另一口径 (客户应收余额 ¥22276) 必须出现在自己的口径标题之后, 且逾期块先于它
        int label = m.indexOf("客户应收余额口径");
        assertTrue(label > 0 && m.indexOf("建议先催") < label, m);
        assertTrue(m.indexOf("22276") > label, m);
        assertFalse(m.substring(0, label).contains("22276"), "逾期块里不许出现客户余额口径的数: " + m);
    }

    @Test
    void overdueViewGivesOnlyOverdueBasis() throws Exception {
        String m = run("{\"view\":\"overdue\"}");
        assertTrue(m.contains("建议先催") && m.contains("AR-3"), m);
        assertFalse(m.contains("22276") || m.contains("客户应收余额口径"), m);
    }

    @Test
    void withoutAmountView_documentsStillListedButNoMoney() throws Exception {
        when(view.showsKey(any(), any())).thenReturn(false);
        JsonNode payload = runPayload("{\"view\":\"overdue\"}");
        String m = payload.path("message").asText();
        assertTrue(m.contains("单号 AR-3") && m.contains("逾期 90 天"), m);
        assertFalse(m.contains("¥") || m.contains("39086") || m.contains("49086"), m);
        JsonNode customers = payload.path("data").path("overdueCustomers");
        assertEquals(3, customers.size());
        customers.forEach(row -> assertFalse(row.has("outstanding")));
    }

    @Test
    void moreThanTwentyCustomersStatesExactlyHowManyWereOmitted() throws Exception {
        when(arAp.getOverdueReceivableDocuments("F1")).thenReturn(IntStream.rangeClosed(1, 22)
                .mapToObj(i -> doc("AR-" + i, "客户-%02d".formatted(i), String.valueOf(i * 100), 15))
                .toList());
        JsonNode payload = runPayload("{\"view\":\"overdue\"}");
        String m = payload.path("message").asText();
        JsonNode data = payload.path("data");
        assertTrue(m.contains("22 家客户、22 张单据，合计 ¥25300.00"), m);
        assertTrue(m.contains("客户-22：¥2200.00") && m.contains("客户-03：¥300.00"), m);
        assertTrue(m.contains("另有 2 家逾期客户"), m);
        assertFalse(m.contains("客户-02") || m.contains("客户-01"), m);
        assertEquals(20, data.path("overdueCustomers").size());
        assertEquals(2, data.path("omittedOverdueCustomerCount").asInt());
        assertEquals(3, data.path("collectFirst").size());
    }

    @Test
    void defaultViewStillListsOverdueCustomersWhenCustomerBalancesAreZero() throws Exception {
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of());
        String m = run("{}");
        assertTrue(m.contains("鲜湘缘：¥39086.00") && m.contains("建议先催"), m);
        assertTrue(m.contains("客户应收余额口径"), m);
    }

    @Test
    void noOverdueDocuments_noCollectList_andSaysSo() throws Exception {
        when(arAp.getOverdueReceivableDocuments("F1")).thenReturn(List.of());
        String ranking = run("{}");
        assertFalse(ranking.contains("建议先催"), ranking);
        assertTrue(ranking.contains("应收流水里没有已逾期的记录"), ranking);
        String overdue = run("{\"view\":\"overdue\"}");
        assertFalse(overdue.contains("建议先催"), overdue);
        assertTrue(overdue.contains("没有已逾期") || overdue.contains("没有需要催收"), overdue);
    }
}

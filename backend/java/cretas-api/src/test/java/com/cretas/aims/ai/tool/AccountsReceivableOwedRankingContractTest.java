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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2026-11-18 LSMDEMO「哪个客户欠款最多」答 0 客户 / ¥0 (#1853): 客户余额排名与经营分析页同源,
 * 账龄分支改按每客户行读; 受限财务范围不得拿到全厂排名 (审查 H1)。真实工具入口 execute()。
 */
class AccountsReceivableOwedRankingContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AccountsReceivableAgingTool tool;
    private CustomerRepository customers;
    private ArApService arAp;
    private FinanceRowScopeQuery scope;
    private PriceFieldResponseAdvice view;

    @BeforeEach
    void setUp() {
        tool = new AccountsReceivableAgingTool();
        customers = mock(CustomerRepository.class);
        arAp = mock(ArApService.class);
        scope = mock(FinanceRowScopeQuery.class);
        view = mock(PriceFieldResponseAdvice.class);
        when(view.showsKey(any(), any())).thenReturn(true);
        when(scope.creators("F1")).thenReturn(null);   // null = 全厂范围
        ReflectionTestUtils.setField(tool, "customerRepository", customers);
        ReflectionTestUtils.setField(tool, "arApService", arAp);
        ReflectionTestUtils.setField(tool, "financeRowScopeQuery", scope);
        ReflectionTestUtils.setField(tool, "priceView", view);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
    }

    private static Customer customer(String name, String balance) {
        Customer c = new Customer();
        c.setName(name);
        c.setCurrentBalance(new BigDecimal(balance));
        return c;
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t1", "accounts_receivable_aging", args),
                Map.of("factoryId", "F1", "userId", 1L)));
    }

    private static String msg(JsonNode r) {
        return r.get("data").get("message").asText();
    }

    @Test
    void ranksCustomersByBalanceDescending_prepaymentsListedSeparately() throws Exception {
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of(
                customer("鲜湘缘", "12000"), customer("味美", "8000"), customer("老张", "2276")));
        when(customers.findByFactoryId("F1")).thenReturn(List.of(
                customer("鲜湘缘", "12000"), customer("味美", "8000"), customer("老张", "2276"), customer("预付客户", "-11433.62")));
        String m = msg(run("{}"));
        assertTrue(m.contains("欠款最多的是 鲜湘缘") && m.contains("合计 ¥22276"), m);
        assertTrue(m.indexOf("鲜湘缘") < m.indexOf("味美"), m);
        assertTrue(m.contains("1 个客户预收款 ¥11433.62") && m.contains("不计入欠款"), m);
    }

    @Test
    void withoutAmountView_namesOnly_noMoney() throws Exception {
        when(view.showsKey(any(), any())).thenReturn(false);
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of(customer("鲜湘缘", "12000")));
        when(customers.findByFactoryId("F1")).thenReturn(List.of(customer("鲜湘缘", "12000")));
        JsonNode r = run("{}");
        assertTrue(msg(r).contains("鲜湘缘") && msg(r).contains("金额按权限不显示"), msg(r));
        assertFalse(msg(r).contains("12000") || msg(r).contains("¥"), msg(r));
        assertFalse(r.toString().contains("12000"), r.toString());
    }

    @Test
    void nobodyOwes_saysSo_notZeroCustomersZeroYuan() throws Exception {
        when(customers.findCustomersWithOutstandingBalance("F1")).thenReturn(List.of());
        when(customers.findByFactoryId("F1")).thenReturn(List.of(customer("预付客户", "-500")));
        String m = msg(run("{}"));
        assertTrue(m.contains("当前没有客户欠款") && m.contains("1 个客户预收款"), m);
        assertFalse(m.contains("共 0 客户"), m);
    }

    @Test
    void restrictedFinanceScope_getsNoFactoryWideRanking() throws Exception {
        when(scope.creators("F1")).thenReturn(List.of(7L));   // 仅本人范围
        JsonNode r = run("{}");
        assertTrue(msg(r).contains("财务范围受限"), msg(r));
        assertFalse(msg(r).contains("鲜湘缘"), msg(r));
        verifyNoInteractions(customers);   // 阴性对照: 受限范围一次客户表都不读
    }

    @Test
    void agingView_readsPerCustomerRows_andSaysWhenLedgerIsEmpty() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total", new BigDecimal("500"));
        row.put("days61_90", new BigDecimal("300"));
        row.put("days91_120", BigDecimal.ZERO);
        row.put("days120plus", BigDecimal.ZERO);
        when(arAp.getAgingAnalysis("F1", CounterpartyType.CUSTOMER)).thenReturn(List.of(row));
        String m = msg(run("{\"view\":\"aging\"}"));
        assertTrue(m.contains("共 1 客户 / ¥500") && m.contains("60+ 天高风险 1 客户"), m);

        when(arAp.getAgingAnalysis("F1", CounterpartyType.CUSTOMER)).thenReturn(List.of());
        String empty = msg(run("{\"view\":\"aging\"}"));
        assertTrue(empty.contains("流水里没有未结清的应收记录"), empty);
        assertFalse(empty.contains("¥0"), empty);
    }
}

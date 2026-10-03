package com.cretas.aims.controller;

import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.controller.finance.ArApController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC response advice/serialization against the service's existing wire keys. */
class ArApQueryMoneyContractTest {
    private final ArApService service = mock(ArApService.class);

    @AfterEach
    void cleanup() { PriceSensitiveContext.clear(); }

    private MockMvc mvc(boolean canViewPrice, boolean canReadWriteFinance) {
        PriceSensitiveContext.clear();
        PermissionService permissions = mock(PermissionService.class);
        UserRepository users = mock(UserRepository.class);
        User actor = new User();
        actor.setId(9L);
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(permissions.hasPermission(actor, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(canViewPrice);
        when(permissions.hasPermission(actor, PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)).thenReturn(canReadWriteFinance);
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        ArApController controller = new ArApController(service, mapper, mock(MobileService.class),
                mock(FactoryConfigService.class), mock(OpeningInventoryService.class));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @ParameterizedTest(name = "overview price={0}, finance write={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void overviewMasksAllMoneyButPreservesCountsAndWireKeys(boolean allowed, boolean financeWrite) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("totalReceivable", "totalPayable", "netPosition", "overdueReceivable", "overduePayable")) {
            row.put(key, new BigDecimal("123.45"));
        }
        for (String key : List.of("overdueARCount", "overdueAPCount", "payableCount", "receivableCount",
                "monthlyTransactionCount", "customerCount", "supplierCount")) row.put(key, 2L);
        row.put("transactionsByType", List.of(Map.of("type", "客户回款", "count", 1L, "amount", new BigDecimal("9.5"))));
        when(service.getFinanceOverview("F006")).thenReturn(row);
        var result = mvc(allowed, financeWrite).perform(get("/api/mobile/F006/finance/overview").requestAttr("userId", 9L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.receivableCount").value(2))
                .andExpect(jsonPath("$.data.transactionsByType[0].count").value(1));
        for (String key : List.of("totalReceivable", "totalPayable", "netPosition", "overdueReceivable", "overduePayable")) {
            if (allowed) result.andExpect(jsonPath("$.data." + key).value(123.45));
            else result.andExpect(jsonPath("$.data." + key).value(nullValue()));
        }
        if (allowed) result.andExpect(jsonPath("$.data.transactionsByType[0].amount").value(9.5));
        else result.andExpect(jsonPath("$.data.transactionsByType[0].amount").value(nullValue()));
        assertThat(row.get("totalReceivable")).isEqualTo(new BigDecimal("123.45"));
        verify(service).getFinanceOverview("F006");
    }

    @ParameterizedTest(name = "aging price={0}, finance write={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void agingPreservesExactSixBucketBoundariesAndMasksEveryBucket(boolean allowed, boolean financeWrite) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("counterpartyId", "audit-customer");
        row.put("counterpartyName", "测试客户");
        for (String key : List.of("current", "days1_30", "days31_60", "days61_90", "days91_120", "days120plus", "total")) {
            row.put(key, BigDecimal.ZERO);
        }
        row.put("days120plus", new BigDecimal("201"));
        row.put("total", new BigDecimal("201"));
        when(service.getAgingAnalysis("F006", CounterpartyType.CUSTOMER)).thenReturn(List.of(row));
        var result = mvc(allowed, financeWrite).perform(get("/api/mobile/F006/finance/aging")
                        .param("counterpartyType", "CUSTOMER").requestAttr("userId", 9L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].counterpartyId").value("audit-customer"));
        for (String key : List.of("current", "days1_30", "days31_60", "days61_90", "days91_120", "days120plus", "total")) {
            if (allowed) result.andExpect(jsonPath("$.data[0]." + key).value(key.equals("total") || key.equals("days120plus") ? 201 : 0));
            else result.andExpect(jsonPath("$.data[0]." + key).value(nullValue()));
        }
        assertThat(row.get("total")).isEqualTo(new BigDecimal("201"));
        verify(service).getAgingAnalysis("F006", CounterpartyType.CUSTOMER);
    }

    @ParameterizedTest(name = "statement price={0}, finance write={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void statementMasksBalancesAndNestedTransactionsWithoutDeserializingEntities(boolean allowed, boolean financeWrite) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("counterpartyId", "audit-customer");
        row.put("counterpartyType", "CUSTOMER");
        row.put("startDate", "2026-10-01");
        row.put("endDate", "2026-10-03");
        row.put("transactionCount", 1);
        for (String key : List.of("openingBalance", "periodDebit", "periodCredit", "closingBalance")) row.put(key, new BigDecimal("12.3"));
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId("audit-tx");
        transaction.setAmount(new BigDecimal("12.3"));
        transaction.setBalanceAfter(BigDecimal.ZERO);
        transaction.setSettledAmount(new BigDecimal("2.3"));
        transaction.setOutstandingAmount(new BigDecimal("10"));
        row.put("transactions", List.of(transaction));
        when(service.getStatement("F006", CounterpartyType.CUSTOMER, "audit-customer",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3))).thenReturn(row);
        var result = mvc(allowed, financeWrite).perform(get("/api/mobile/F006/finance/statement").requestAttr("userId", 9L)
                        .param("counterpartyType", "CUSTOMER").param("counterpartyId", "audit-customer")
                        .param("startDate", "2026-10-01").param("endDate", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactionCount").value(1))
                .andExpect(jsonPath("$.data.transactions[0].id").value("audit-tx"));
        for (String key : List.of("openingBalance", "periodDebit", "periodCredit", "closingBalance")) {
            if (allowed) result.andExpect(jsonPath("$.data." + key).value(12.3));
            else result.andExpect(jsonPath("$.data." + key).value(nullValue()));
        }
        Map<String, Number> amounts = Map.of("amount", 12.3, "balanceAfter", 0, "settledAmount", 2.3, "outstandingAmount", 10);
        for (var entry : amounts.entrySet()) {
            if (allowed) result.andExpect(jsonPath("$.data.transactions[0]." + entry.getKey()).value(entry.getValue()));
            else result.andExpect(jsonPath("$.data.transactions[0]." + entry.getKey()).value(nullValue()));
        }
    }
}

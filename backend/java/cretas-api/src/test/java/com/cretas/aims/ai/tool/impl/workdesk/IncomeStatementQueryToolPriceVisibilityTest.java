package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利润表 AI 工具的毛利 / 毛利率 / 金额可见性与 web 利润表同一判定 (#1788 漏网: 只读号拿到 grossMarginPercent / netMarginPercent)。
 *
 * <p>走工具真实入口 {@code execute(ToolCall, context)}, 判定用真实 {@link PriceFieldResponseAdvice}, 权限只来自一组配置的权限码
 * (不看角色名); 结论在原始结果和经 {@code maskedViewFor} (生产出口) 遮挡后两处都取。
 */
@DisplayName("利润表 AI 工具: 毛利率 / 金额按价格查看权限配置给, 看不到就不出现, 也不变成 0")
class IncomeStatementQueryToolPriceVisibilityTest {

    private static final long USER_ID = 1L;
    private static final Set<String> VIEWER = Set.of("finance:read", "production:read");
    private static final Set<String> PRICE_VIEW_FINANCE_READ = Set.of("finance:read", "procurement:price:view");
    private static final Set<String> FINANCE_RW_NO_PRICE_VIEW = Set.of("finance:read_write");
    private static final Set<String> FINANCE_MANAGER = Set.of("finance:read_write", "procurement:price:view");

    /** 金额 / 毛利 / 利润及由它们算出的比率: 无权限时一个都不许出现在结果里。 */
    private static final List<String> MONEY_AND_RATE_KEYS = List.of(
            "revenues", "costs", "expenses", "totalRevenue", "totalCost", "grossProfit", "totalExpense",
            "operatingProfit", "incomeTax", "netProfit", "grossMarginPercent", "netMarginPercent");
    private static final List<String> MONEY_KEYS = MONEY_AND_RATE_KEYS.subList(0, 10);

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void priceContextMustNotLeak() {
        com.cretas.aims.security.PriceSensitiveContext.clear();
    }

    private static PriceFieldResponseAdvice priceViewFor(Set<String> held) {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(User.class), anyString()))
                .thenAnswer(call -> held.contains(call.<String>getArgument(1)));
        when(permissions.hasConfiguredPermission(any(User.class), anyString(), any()))
                .thenAnswer(call -> held.contains(call.<String>getArgument(1)));
        UserRepository users = mock(UserRepository.class);
        User u = new User();
        u.setId(USER_ID);
        u.setFactoryId("F1");
        u.setRoleCode("whatever_role_name_is_not_the_criterion");
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule()));
        return advice;
    }

    /** 收入 100000 / 成本 60000 / 毛利 40000 (毛利率 40.00%) / 净利润 25000 (净利率 25.00%)。 */
    private static IncomeStatementService service() {
        IncomeStatementDTO dto = IncomeStatementDTO.builder()
                .factoryId("F1").startYear(2026).startMonth(11).endYear(2026).endMonth(11)
                .costDataAvailable(true)
                .grossMarginStatus(IncomeStatementDTO.GrossMarginStatus.CALCULABLE)
                .revenues(List.of()).costs(List.of(BalanceSheetDTO.LineItem.builder()
                        .accountCode("5401").accountName("主营业务成本").amount(new BigDecimal("60000")).build()))
                .expenses(List.of())
                .totalRevenue(new BigDecimal("100000")).totalCost(new BigDecimal("60000"))
                .grossProfit(new BigDecimal("40000")).totalExpense(new BigDecimal("10000"))
                .operatingProfit(new BigDecimal("30000")).incomeTax(new BigDecimal("5000"))
                .netProfit(new BigDecimal("25000")).generatedAt("2026-11-17T10:00:00").build();
        IncomeStatementService s = mock(IncomeStatementService.class);
        when(s.generate(anyString(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(dto);
        return s;
    }

    private record Exit(String message, Map<String, Object> raw, Map<String, Object> masked) { }

    @SuppressWarnings("unchecked")
    private Exit run(Set<String> held, IncomeStatementService service) throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(held);
        IncomeStatementQueryTool tool = new IncomeStatementQueryTool();
        ReflectionTestUtils.setField(tool, "incomeStatementService", service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceView);
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(), "{\"startYear\":2026,\"startMonth\":11}"),
                Map.of("factoryId", "F1", "userId", USER_ID)));
        assertTrue(r.get("success").asBoolean(), r.toString());
        Map<String, Object> raw = mapper.convertValue(r.get("data").get("data"), Map.class);
        return new Exit(r.get("data").get("message").asText(), raw,
                (Map<String, Object>) priceView.maskedViewFor(raw, USER_ID));
    }

    private static void assertNoFigures(String message) {
        for (String figure : List.of("100000", "60000", "40000", "30000", "25000", "40.00", "25.00", "¥", "%")) {
            assertFalse(message.contains(figure), "文字里出现了 " + figure + ": " + message);
        }
    }

    @Test
    @DisplayName("只读号 (财务查看, 无价格查看): 毛利率 / 净利率 / 金额都不出现 (不是 0), 文字也不写, 不查库")
    void viewerWithoutPriceViewGetsNoMarginNoAmounts() throws Exception {
        IncomeStatementService service = service();
        Exit exit = run(VIEWER, service);

        assertTrue(exit.message().contains("按当前账号的权限配置不显示"), exit.message());
        assertNoFigures(exit.message());
        for (Map<String, Object> data : List.of(exit.raw(), exit.masked())) {
            for (String key : MONEY_AND_RATE_KEYS) {
                assertFalse(data.containsKey(key), key + " 不该出现: " + data);
            }
            assertEquals(Boolean.TRUE, data.get("marginHidden"), data.toString());
            assertEquals(Boolean.TRUE, data.get("amountsHidden"), data.toString());
        }
        verify(service, never()).generate(anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("财务读写但不在价格查看配置里: 与 web 利润表 (@PriceSensitive 口径) 一致, 整份不给")
    void financeReadWriteWithoutPriceViewFollowsWebSpec() throws Exception {
        Exit exit = run(FINANCE_RW_NO_PRICE_VIEW, service());
        assertNoFigures(exit.message());
        for (String key : MONEY_AND_RATE_KEYS) {
            assertFalse(exit.raw().containsKey(key), key + ": " + exit.raw());
        }
    }

    @Test
    @DisplayName("有价格查看、只有财务查看: 毛利率 / 净利率照给 (文字与字段同进同出), 金额字段与文字都不出")
    void priceViewFinanceReadGetsRatesButNoAmounts() throws Exception {
        Exit exit = run(PRICE_VIEW_FINANCE_READ, service());

        assertTrue(exit.message().contains("毛利率 40.00%"), exit.message());
        assertTrue(exit.message().contains("净利率 25.00%"), exit.message());
        assertFalse(exit.message().contains("¥") || exit.message().contains("100000"), exit.message());
        for (Map<String, Object> data : List.of(exit.raw(), exit.masked())) {
            assertEquals(0, new BigDecimal(String.valueOf(data.get("grossMarginPercent"))).compareTo(new BigDecimal("40.00")), data.toString());
            assertEquals(0, new BigDecimal(String.valueOf(data.get("netMarginPercent"))).compareTo(new BigDecimal("25.00")), data.toString());
            for (String key : MONEY_KEYS) {
                assertFalse(data.containsKey(key), key + " 不该出现: " + data);
            }
        }
    }

    @Test
    @DisplayName("阳性对照 —— 财务管理 + 价格查看: 金额、毛利率、净利率都在, 遮挡后也在")
    void financeManagerSeesEverything() throws Exception {
        Exit exit = run(FINANCE_MANAGER, service());

        assertTrue(exit.message().contains("毛利 ¥40000"), exit.message());
        assertTrue(exit.message().contains("净利率 25.00%"), exit.message());
        for (Map<String, Object> data : List.of(exit.raw(), exit.masked())) {
            assertEquals(0, new BigDecimal(String.valueOf(data.get("grossProfit"))).compareTo(new BigDecimal("40000")), data.toString());
            assertEquals(0, new BigDecimal(String.valueOf(data.get("grossMarginPercent"))).compareTo(new BigDecimal("40.00")), data.toString());
            assertFalse(data.containsKey("marginHidden") || data.containsKey("amountsHidden"), data.toString());
        }
    }

    @Test
    @DisplayName("口径就是 web 利润表 DTO 上的注解值 (价格查看), 不写死角色")
    void specIsTheWebIncomeStatementAnnotation() throws Exception {
        String web = IncomeStatementDTO.class.getDeclaredField("grossProfit").getAnnotation(PriceSensitive.class).permission();
        assertEquals(web, IncomeStatementQueryTool.PRICE_SPEC);
        assertEquals(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION, IncomeStatementQueryTool.PRICE_SPEC);
    }
}

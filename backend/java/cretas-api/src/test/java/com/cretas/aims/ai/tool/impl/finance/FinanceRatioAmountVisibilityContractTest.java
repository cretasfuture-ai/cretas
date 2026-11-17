package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ROE / ROA / 杜邦 三个工具: 回复文字放在结果的 message 键, 以前无条件写出净利润、股东权益、总资产和由它们算出的比率,
 * 出口 maskedViewFor 不遮 message; 缺数时把收入 / 成本当 0、资产 / 权益当 1, 算出假的比率
 * (getFinanceReport 根本不给 totalAssets / totalEquity ⇒ 线上 ROE = 净利润 / 1 × 100%)。
 *
 * <p>现在: 金额与比率按 web GET /reports/finance 上同名字段的同一个遮挡判定 (真实 {@link PriceFieldResponseAdvice},
 * 权限只来自一组权限码, 不看角色名) 同进同出; 缺数 ⇒「数据不足，无法计算」, 不出数。全部走工具真实的 execute 入口。
 */
@DisplayName("ROE / ROA / 杜邦: 金额与比率和 web 财务报表同一判定; 缺数不出假值")
class FinanceRatioAmountVisibilityContractTest {

    private static final long USER_ID = 7L;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 财务读写 + 价格查看: web /reports/finance 上营收 / 成本 / 利润都看得到。 */
    private static final Set<String> FINANCE_MANAGER = Set.of("finance:read_write", "procurement:price:view", "analytics:read");
    /** 财务查看 + 价格查看 (过得了工具门槛), 但没有财务读写: web 上营收 / 成本 / 利润被遮 (Rule 2b)。 */
    private static final Set<String> FINANCE_READ_ONLY = Set.of("finance:read", "procurement:price:view", "analytics:read");

    enum Ratio {
        ROE(FinanceRoeTool::new, "roe", "净资产收益率(ROE)"),
        ROA(FinanceRoaTool::new, "roa", "资产收益率(ROA)"),
        DUPONT(FinanceDupontTool::new, "dupontROE", "ROE");

        final Supplier<AbstractBusinessTool> factory;
        final String ratioKey;
        final String label;

        Ratio(Supplier<AbstractBusinessTool> factory, String ratioKey, String label) {
            this.factory = factory;
            this.ratioKey = ratioKey;
            this.label = label;
        }
    }

    @AfterEach
    void clearPriceContext() {
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
        u.setRoleCode("role_name_is_not_the_criterion");
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", new ObjectMapper().findAndRegisterModules());
        return advice;
    }

    /** 收入 1000, 成本 650 ⇒ 利润 350, 利润率 35%; 总资产 5000, 股东权益 1750。 */
    private static Map<String, Object> completeReport() {
        Map<String, Object> m = realShapeReport();
        m.put("totalAssets", new BigDecimal("5000"));
        m.put("totalEquity", new BigDecimal("1750"));
        return m;
    }

    /** ProductionReportServiceImpl#getFinanceReport 线上的样子: 没有 totalAssets / totalEquity。 */
    private static Map<String, Object> realShapeReport() {
        Map<String, Object> m = new HashMap<>();
        m.put("totalRevenue", new BigDecimal("1000"));
        m.put("totalCost", new BigDecimal("650"));
        m.put("totalProfit", new BigDecimal("350"));
        m.put("profitMargin", 35.0);
        m.put("accountsReceivable", new BigDecimal("80"));
        return m;
    }

    private record Run(String message, Map<String, Object> data, ReportService reports) { }

    @SuppressWarnings("unchecked")
    private Run run(Ratio ratio, Set<String> held, Map<String, Object> report) throws Exception {
        AbstractBusinessTool tool = ratio.factory.get();
        ReportService reports = mock(ReportService.class);
        when(reports.getFinanceReport(eq("F1"), any(), any())).thenReturn(report);
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceViewFor(held));
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(), "{}"),
                Map.of("factoryId", "F1", "userId", USER_ID)));
        assertTrue(r.get("success").asBoolean(), r.toString());
        Map<String, Object> data = mapper.convertValue(r.get("data"), Map.class);
        return new Run((String) data.get("message"), data, reports);
    }

    /** web 对照: 同一个 advice 遮 GET /reports/finance 那一份 Map 后, 营收 / 利润还在不在。 */
    @SuppressWarnings("unchecked")
    private static boolean webShowsProfit(Set<String> held) {
        Map<String, Object> view = (Map<String, Object>) priceViewFor(held).maskedViewFor(realShapeReport(), USER_ID);
        assertEquals(view.get("totalRevenue") != null, view.get("totalProfit") != null, "web 上营收与利润应同进同出: " + view);
        return view.get("totalProfit") != null;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("有财务读写: 数据齐 ⇒ 比率与金额都给, 与 web 一致 (阳性对照)")
    void financeManagerSeesRatioAndAmounts(Ratio ratio) throws Exception {
        assertTrue(webShowsProfit(FINANCE_MANAGER));
        Run run = run(ratio, FINANCE_MANAGER, completeReport());
        assertEquals(Boolean.TRUE, run.data().get("dataSufficient"), run.data().toString());
        assertTrue(run.data().get(ratio.ratioKey) instanceof Number, run.data().toString());
        // ROE = 350 / 1750 = 20%; ROA = 350 / 5000 = 7%; 杜邦 = 35% × 0.2 × 2.857 = 20%
        String expected = switch (ratio) {
            case ROE, DUPONT -> "20.00%";
            case ROA -> "7.00%";
        };
        assertEquals(Double.parseDouble(expected.replace("%", "")),
                ((Number) run.data().get(ratio.ratioKey)).doubleValue(), 1e-6, run.data().toString());
        assertTrue(run.message().contains(expected), run.message());
        if (ratio != Ratio.DUPONT) {
            assertTrue(run.message().contains("350元"), run.message());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("只有财务查看 (web 上利润被遮): 比率与金额都不给 —— 字段不存在、不是 0, 文字里没有数, 也不查库")
    void financeReadOnlyGetsNoRatioNoAmounts(Ratio ratio) throws Exception {
        assertFalse(webShowsProfit(FINANCE_READ_ONLY), "前提: web 对这个账号遮利润");
        Run run = run(ratio, FINANCE_READ_ONLY, completeReport());
        assertEquals(Boolean.TRUE, run.data().get("amountsHidden"), run.data().toString());
        for (String key : List.of("roe", "roa", "dupontROE", "profitMargin", "assetTurnover", "equityMultiplier", "financeData")) {
            assertFalse(run.data().containsKey(key), key + " 不该出现 (也不是 0): " + run.data());
        }
        String m = run.message();
        assertTrue(m.contains("按当前账号的权限配置不显示"), m);
        assertFalse(m.matches("(?s).*\\d.*"), "文字里不该有任何数字: " + m);
        verify(run.reports(), never()).getFinanceReport(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("线上真实形状 (没有总资产 / 股东权益): 说「数据不足，无法计算」, 不出比率 (以前按 1 算出假值)")
    void missingBalanceSheetFiguresMeansNoRatio(Ratio ratio) throws Exception {
        Run run = run(ratio, FINANCE_MANAGER, realShapeReport());
        assertEquals(Boolean.FALSE, run.data().get("dataSufficient"), run.data().toString());
        assertFalse(run.data().containsKey(ratio.ratioKey), "缺数时比率键不该出现 (也不是 0): " + run.data());
        assertTrue(run.message().contains(ratio.label + ": 数据不足，无法计算"), run.message());
        // 旧代码: 权益 / 资产缺 ⇒ 按 1 算 ⇒ 350 / 1 × 100 = 35000%
        assertFalse(run.message().contains("35000"), run.message());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) run.data().get("missingFields");
        assertTrue(missing.contains(ratio == Ratio.ROE ? "股东权益" : "总资产"), String.valueOf(missing));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("成本没录 (报表利润为 null): 不把成本当 0 算成「全额都是利润」, 说数据不足")
    void unknownCostMeansNoProfitAndNoRatio(Ratio ratio) throws Exception {
        Map<String, Object> report = completeReport();
        report.put("totalCost", null);
        report.put("totalProfit", null);
        report.put("profitMargin", null);
        Run run = run(ratio, FINANCE_MANAGER, report);
        assertFalse(run.data().containsKey(ratio.ratioKey), run.data().toString());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) run.data().get("missingFields");
        assertTrue(missing.contains("净利润"), String.valueOf(missing));
        // 旧代码: 成本按 0 ⇒ 利润 = 营收 1000 ⇒ ROE 57.14% / ROA 20.00%
        assertFalse(run.message().contains("57.14") || run.message().contains("1000元"), run.message());
    }

    @Test
    @DisplayName("杜邦只缺权益: 销售净利率与资产周转率照给, 权益乘数与 ROE 说数据不足")
    void dupontGivesFactorsWhoseInputsExist() throws Exception {
        Map<String, Object> report = completeReport();
        report.remove("totalEquity");
        Run run = run(Ratio.DUPONT, FINANCE_MANAGER, report);
        assertEquals(35.0, ((Number) run.data().get("profitMargin")).doubleValue(), 1e-6);
        assertEquals(0.2, ((Number) run.data().get("assetTurnover")).doubleValue(), 1e-6);
        assertFalse(run.data().containsKey("equityMultiplier"), run.data().toString());
        assertFalse(run.data().containsKey("dupontROE"), run.data().toString());
        assertTrue(run.message().contains("权益乘数: 数据不足，无法计算"), run.message());
    }

    @Test
    @DisplayName("三个工具问的是同一组金额键, 且就是 web /reports/finance 会遮的那几个利润 / 营收键")
    void sameAmountKeysAsWebFinanceReport() {
        assertEquals(List.of("totalRevenue", "totalCost", "totalProfit", "profitMargin", "totalAssets", "totalEquity"),
                List.of(FinanceRoeTool.FINANCE_AMOUNT_KEYS));
        PriceFieldResponseAdvice readOnly = priceViewFor(FINANCE_READ_ONLY);
        for (String key : List.of("totalRevenue", "totalCost", "totalProfit", "profitMargin")) {
            assertFalse(readOnly.showsKey(USER_ID, key), key);
            assertTrue(priceViewFor(FINANCE_MANAGER).showsKey(USER_ID, key), key);
        }
    }
}

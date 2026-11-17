package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.report.ReportFinanceRatioTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
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
 * 财务比率工具 (finance_roe / finance_roa / finance_dupont / finance_liquidity / finance_solvency 与同意图的
 * report_finance_ratio): 以前无条件写出净利润、股东权益、总资产、流动资产等金额和由它们算出的比率; 缺数时收入 / 成本当 0、
 * 资产 / 权益 / 负债当 1, 算出假的比率 (getFinanceReport 根本不给 totalAssets / totalEquity ⇒ 线上 ROE = 净利润 / 1 × 100%)。
 *
 * <p>现在 (与 #1801 利润表、指标中心同口径, Steve 11-17): 金额与比率只看价格查看权限 ({@link FinanceFigures#AMOUNT_SPEC},
 * 经真实 {@link PriceFieldResponseAdvice#showsSpec}, 权限只来自一组权限码, 不看角色名), 不另要 finance:read_write;
 * 过门后出口 (maskedViewFor) 也不按键名剥。缺数 ⇒「数据不足，无法计算」, 不出数。全部走工具真实的 execute 入口,
 * 与 ToolDispatchService 直调分支一样记录按口径可见的键再经出口遮挡。
 */
@DisplayName("财务比率: 金额与比率按价格查看口径 (不另要财务读写); 缺数不出假值")
class FinanceRatioAmountVisibilityContractTest {

    private static final long USER_ID = 7L;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 价格查看 + 财务读写。 */
    private static final Set<String> FINANCE_MANAGER = Set.of("finance:read_write", "procurement:price:view", "analytics:read");
    /** 价格查看 + 财务查看, 没有财务读写: 按 #1801 口径看得到 (以前本 PR 把它钉成看不到)。 */
    private static final Set<String> PRICE_VIEW_NO_RW = Set.of("finance:read", "procurement:price:view", "analytics:read");
    /** 财务读写, 没有价格查看: 看不到 (以前 Rule 2b 判定下看得到, 与指标侧不一致)。 */
    private static final Set<String> RW_NO_PRICE_VIEW = Set.of("finance:read_write", "finance:read", "analytics:read");

    enum Ratio {
        ROE(FinanceRoeTool::new, "{}", "roe", "净资产收益率(ROE)", "20.00%", "350元"),
        ROA(FinanceRoaTool::new, "{}", "roa", "资产收益率(ROA)", "7.00%", "350元"),
        DUPONT(FinanceDupontTool::new, "{}", "dupontROE", "ROE", "20.00%", null),
        LIQUIDITY(FinanceLiquidityTool::new, "{}", "currentRatio", "流动比率", "2.50", "3000元"),
        SOLVENCY(FinanceSolvencyTool::new, "{}", "debtRatio", "资产负债率", "65.00%", "3250元"),
        REPORT_ROE(ReportFinanceRatioTool::new, "{\"ratioType\":\"ROE\"}", "roe", "净资产收益率", null, null),
        REPORT_LIQUIDITY(ReportFinanceRatioTool::new, "{\"ratioType\":\"LIQUIDITY\"}", "currentRatio", "流动比率", null, null),
        REPORT_SOLVENCY(ReportFinanceRatioTool::new, "{\"ratioType\":\"SOLVENCY\"}", "debtRatio", "偿债能力", null, null);

        final Supplier<AbstractBusinessTool> factory;
        final String args;
        final String ratioKey;
        final String label;
        /** 文字里的比率 (null = 该工具文字不写数, 只在字段里)。 */
        final String ratioText;
        /** 文字里的一个金额 (null = 不写金额)。 */
        final String amountText;

        Ratio(Supplier<AbstractBusinessTool> factory, String args, String ratioKey, String label,
              String ratioText, String amountText) {
            this.factory = factory;
            this.args = args;
            this.ratioKey = ratioKey;
            this.label = label;
            this.ratioText = ratioText;
            this.amountText = amountText;
        }

        /** 本比率实际读的报表键 (completeReport 有 totalProfit ⇒ 净利润只读它)。 */
        Set<String> usedInputs() {
            return switch (this) {
                case ROE, REPORT_ROE -> Set.of("totalProfit", "totalEquity");
                case ROA -> Set.of("totalProfit", "totalAssets");
                // profitMargin 是杜邦的输出 (= 净利润 / 营收, 由上面两项算出), 与报表同名键同值
                case DUPONT -> Set.of("totalProfit", "totalRevenue", "totalAssets", "totalEquity", "profitMargin");
                case LIQUIDITY, REPORT_LIQUIDITY -> Set.of("currentAssets", "currentLiabilities", "inventory");
                case SOLVENCY, REPORT_SOLVENCY -> Set.of("totalAssets", "totalLiabilities", "totalEquity");
            };
        }

        /** 完整报表下的比率值: ROE 350/1750, ROA 350/5000, 杜邦 = ROE, 流动 3000/1200, 资产负债 3250/5000。 */
        double expected() {
            return switch (this) {
                case ROE, DUPONT, REPORT_ROE -> 20.0;
                case ROA -> 7.0;
                case LIQUIDITY, REPORT_LIQUIDITY -> 2.5;
                case SOLVENCY, REPORT_SOLVENCY -> 65.0;
            };
        }
    }

    @AfterEach
    void clearPriceContext() {
        PriceSensitiveContext.clear();
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

    /** 收入 1000, 成本 650 ⇒ 利润 350; 总资产 5000, 股东权益 1750, 总负债 3250; 流动资产 3000, 流动负债 1200, 存货 600。 */
    private static Map<String, Object> completeReport() {
        Map<String, Object> m = realShapeReport();
        m.put("totalAssets", new BigDecimal("5000"));
        m.put("totalEquity", new BigDecimal("1750"));
        m.put("totalLiabilities", new BigDecimal("3250"));
        m.put("currentAssets", new BigDecimal("3000"));
        m.put("currentLiabilities", new BigDecimal("1200"));
        m.put("inventory", new BigDecimal("600"));
        return m;
    }

    /** ProductionReportServiceImpl#getFinanceReport 线上的样子: 没有资产 / 权益 / 负债。 */
    private static Map<String, Object> realShapeReport() {
        Map<String, Object> m = new HashMap<>();
        m.put("totalRevenue", new BigDecimal("1000"));
        m.put("totalCost", new BigDecimal("650"));
        m.put("totalProfit", new BigDecimal("350"));
        m.put("profitMargin", 35.0);
        m.put("accountsReceivable", new BigDecimal("80"));
        // 线上报表还有这些键, 不参与任何比率: 分项成本 / 应付 / 预收 (数值取得独特, 便于在出口 JSON 里搜)
        m.put("materialCost", new BigDecimal("411.11"));
        m.put("laborCost", new BigDecimal("122.22"));
        m.put("equipmentCost", new BigDecimal("83.33"));
        m.put("otherCost", new BigDecimal("33.34"));
        m.put("accountsPayable", new BigDecimal("277.77"));
        m.put("customerPrepayments", new BigDecimal("66.66"));
        return m;
    }

    /** 分项成本: web GET /reports/finance 对「有价格查看、无财务读写」按键名规则遮掉, 比率工具也不该给。 */
    private static final Map<String, String> COST_BREAKDOWN = Map.of(
            "materialCost", "411.11", "laborCost", "122.22", "equipmentCost", "83.33", "otherCost", "33.34");

    /** raw = 工具原始结果; exit = 经出口 maskedViewFor (生产直调分支同样先记录按口径可见的键再遮)。 */
    private record Run(String message, Map<String, Object> raw, Map<String, Object> exit, ReportService reports,
                       PriceFieldResponseAdvice priceView) { }

    @SuppressWarnings("unchecked")
    private Run run(Ratio ratio, Set<String> held, Map<String, Object> report) throws Exception {
        AbstractBusinessTool tool = ratio.factory.get();
        ReportService reports = mock(ReportService.class);
        when(reports.getFinanceReport(eq("F1"), any(), any())).thenReturn(report);
        PriceFieldResponseAdvice priceView = priceViewFor(held);
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceView);
        PriceSensitiveContext.startRecordingVisible();
        String json;
        Set<String> visibleKeys;
        try {
            json = tool.execute(ToolCall.of("t1", tool.getToolName(), ratio.args), Map.of("factoryId", "F1", "userId", USER_ID));
        } finally {
            visibleKeys = PriceSensitiveContext.stopRecordingVisible();
        }
        JsonNode r = mapper.readTree(json);
        assertTrue(r.get("success").asBoolean(), r.toString());
        Map<String, Object> data = mapper.convertValue(r.get("data"), Map.class);
        PriceSensitiveContext.approveKeys(data, visibleKeys);
        Map<String, Object> exit = (Map<String, Object>) priceView.maskedViewFor(data, USER_ID);
        return new Run((String) data.get("message"), data, exit, reports, priceView);
    }

    private static void assertSees(Ratio ratio, Run run) {
        assertEquals(Boolean.TRUE, run.raw().get("dataSufficient"), run.raw().toString());
        for (Map<String, Object> view : List.of(run.raw(), run.exit())) {
            assertTrue(view.get(ratio.ratioKey) instanceof Number, ratio.ratioKey + " 应给: " + view);
            assertEquals(ratio.expected(), ((Number) view.get(ratio.ratioKey)).doubleValue(), 1e-6, view.toString());
        }
        // 出口不按键名 (Rule 2b) 剥掉本比率用到的报表金额: 有价格查看就给 (与 #1801 利润表同)
        @SuppressWarnings("unchecked")
        Map<String, Object> financeData = (Map<String, Object>) run.exit().get("financeData");
        for (String used : ratio.usedInputs()) {
            assertNotNull(financeData.get(used), "出口剥掉了本比率用到的 " + used + ": " + financeData);
        }
        if (ratio.ratioText != null) {
            assertTrue(run.message().contains(ratio.ratioText), run.message());
        }
        if (ratio.amountText != null) {
            assertTrue(run.message().contains(ratio.amountText), run.message());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("价格查看 + 财务读写: 数据齐 ⇒ 比率与金额都给 (阳性对照)")
    void financeManagerSeesRatioAndAmounts(Ratio ratio) throws Exception {
        assertSees(ratio, run(ratio, FINANCE_MANAGER, completeReport()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("有价格查看、没有财务读写: 看得到 (口径只看价格查看), 出口也不剥")
    void priceViewWithoutReadWriteSees(Ratio ratio) throws Exception {
        assertSees(ratio, run(ratio, PRICE_VIEW_NO_RW, completeReport()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("有价格查看、没有财务读写: 报表里没参与本比率的键与 web GET /reports/finance 同一判定 —— 分项成本遮掉, 不因比率工具整份放行 (#1806 复审「中」)")
    void priceViewWithoutReadWriteUnusedKeysFollowWebJudgment(Ratio ratio) throws Exception {
        Map<String, Object> report = completeReport();
        Run run = run(ratio, PRICE_VIEW_NO_RW, report);
        // web 那一侧: ReportController#getFinanceReport 原样返回报表 Map, 由同一个 PriceFieldResponseAdvice 按同一个判定遮
        // (没有任何按口径放行的键) —— maskedViewFor 与响应层共用 decide + stripPriceFields
        @SuppressWarnings("unchecked")
        Map<String, Object> web = (Map<String, Object>) run.priceView().maskedViewFor(new HashMap<>(report), USER_ID);
        // 阳性对照: web 对这个账号确实遮分项成本 (否则下面的阴性断言没意义)
        for (String k : COST_BREAKDOWN.keySet()) {
            assertNull(web.get(k), "web 判定应遮 " + k + ": " + web);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> exitFinance = (Map<String, Object>) run.exit().get("financeData");
        assertNotNull(exitFinance, run.exit().toString());
        int compared = 0;
        for (String key : report.keySet()) {
            if (ratio.usedInputs().contains(key)) continue;
            assertSameFigure(web.get(key), exitFinance.get(key), key + " 与 web 判定不一致: web=" + web + " ai=" + exitFinance);
            compared++;
        }
        assertTrue(compared >= COST_BREAKDOWN.size() + 3, "参与比对的未用键太少: " + compared);
        // 阴性: 分项成本的键在出口是 null, 数值在出口 JSON 与文字里都搜不到
        String exitJson = mapper.writeValueAsString(run.exit());
        for (Map.Entry<String, String> e : COST_BREAKDOWN.entrySet()) {
            assertNull(exitFinance.get(e.getKey()), e.getKey() + " 不该给: " + exitFinance);
            assertFalse(exitJson.contains(e.getValue()), e.getKey() + "=" + e.getValue() + " 出现在出口: " + exitJson);
            assertFalse(run.message().contains(e.getValue()), run.message());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("价格查看 + 财务读写: 报表其余键 (分项成本 / 应收 / 应付 / 预收) 照常可见")
    void financeManagerStillSeesUnusedReportKeys(Ratio ratio) throws Exception {
        Run run = run(ratio, FINANCE_MANAGER, completeReport());
        @SuppressWarnings("unchecked")
        Map<String, Object> exitFinance = (Map<String, Object>) run.exit().get("financeData");
        for (Map.Entry<String, String> e : COST_BREAKDOWN.entrySet()) {
            assertSameFigure(new BigDecimal(e.getValue()), exitFinance.get(e.getKey()), e.getKey() + ": " + exitFinance);
        }
        assertSameFigure(new BigDecimal("80"), exitFinance.get("accountsReceivable"), exitFinance.toString());
        assertSameFigure(new BigDecimal("277.77"), exitFinance.get("accountsPayable"), exitFinance.toString());
        assertSameFigure(new BigDecimal("66.66"), exitFinance.get("customerPrepayments"), exitFinance.toString());
    }

    private static void assertSameFigure(Object expected, Object actual, String msg) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, msg);
            return;
        }
        assertTrue(expected instanceof Number && actual instanceof Number, msg);
        assertEquals(((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 1e-9, msg);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("有财务读写、没有价格查看: 比率与金额都不给 —— 字段不存在、不是 0, 文字里没有数, 也不查库")
    void readWriteWithoutPriceViewSeesNothing(Ratio ratio) throws Exception {
        Run run = run(ratio, RW_NO_PRICE_VIEW, completeReport());
        assertEquals(Boolean.TRUE, run.raw().get("amountsHidden"), run.raw().toString());
        for (String key : List.of("roe", "roa", "dupontROE", "profitMargin", "assetTurnover", "equityMultiplier",
                "currentRatio", "quickRatio", "debtRatio", "equityRatio", "netProfit", "totalAssets", "totalEquity",
                "totalLiabilities", "currentAssets", "currentLiabilities", "financeData")) {
            assertFalse(run.raw().containsKey(key), key + " 不该出现 (也不是 0): " + run.raw());
        }
        String m = run.message();
        assertTrue(m.contains("按当前账号的权限配置不显示"), m);
        assertFalse(m.matches("(?s).*\\d.*"), "文字里不该有任何数字: " + m);
        verify(run.reports(), never()).getFinanceReport(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ratio.class)
    @DisplayName("线上真实形状 (没有资产 / 权益 / 负债): 说「数据不足，无法计算」, 不出比率 (以前按 1 算出假值)")
    void missingBalanceSheetFiguresMeansNoRatio(Ratio ratio) throws Exception {
        Run run = run(ratio, FINANCE_MANAGER, realShapeReport());
        assertEquals(Boolean.FALSE, run.raw().get("dataSufficient"), run.raw().toString());
        assertFalse(run.raw().containsKey(ratio.ratioKey), "缺数时比率键不该出现 (也不是 0): " + run.raw());
        assertTrue(run.message().contains(FinanceFigures.INSUFFICIENT), run.message());
        // 旧代码: 权益 / 资产缺 ⇒ 按 1 算 ⇒ 350 / 1 × 100 = 35000%; 流动 / 负债按 1 ⇒ 1.00 / 100.00%
        assertFalse(run.message().contains("35000") || run.message().contains("100.00%")
                || run.message().contains("流动比率: 1.00"), run.message());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) run.raw().get("missingFields");
        assertFalse(missing == null || missing.isEmpty(), run.raw().toString());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Ratio.class, names = {"ROE", "ROA", "DUPONT", "REPORT_ROE"})
    @DisplayName("成本没录 (报表利润为 null): 不把成本当 0 算成「全额都是利润」, 说数据不足")
    void unknownCostMeansNoProfitAndNoRatio(Ratio ratio) throws Exception {
        Map<String, Object> report = completeReport();
        report.put("totalCost", null);
        report.put("totalProfit", null);
        report.put("profitMargin", null);
        Run run = run(ratio, FINANCE_MANAGER, report);
        assertFalse(run.raw().containsKey(ratio.ratioKey), run.raw().toString());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) run.raw().get("missingFields");
        assertTrue(missing.contains("净利润"), String.valueOf(missing));
        // 旧代码: 成本按 0 ⇒ 利润 = 营收 1000 ⇒ ROE 57.14% / ROA 20.00%
        assertFalse(run.message().contains("57.14") || run.message().contains("1000元"), run.message());
    }

    @Test
    @DisplayName("流动比率缺存货: 流动比率照给, 速动比率说数据不足 (以前按流动资产 × 0.7 估)")
    void quickRatioNeedsInventory() throws Exception {
        Map<String, Object> report = completeReport();
        report.remove("inventory");
        Run run = run(Ratio.LIQUIDITY, FINANCE_MANAGER, report);
        assertEquals(2.5, ((Number) run.raw().get("currentRatio")).doubleValue(), 1e-6);
        assertFalse(run.raw().containsKey("quickRatio"), run.raw().toString());
        assertTrue(run.message().contains("速动比率: " + FinanceFigures.INSUFFICIENT), run.message());
        assertFalse(run.message().contains("1.75"), "3000 × 0.7 / 1200 的估算不该出现: " + run.message());
    }

    @Test
    @DisplayName("杜邦只缺权益: 销售净利率与资产周转率照给, 权益乘数与 ROE 说数据不足")
    void dupontGivesFactorsWhoseInputsExist() throws Exception {
        Map<String, Object> report = completeReport();
        report.remove("totalEquity");
        Run run = run(Ratio.DUPONT, FINANCE_MANAGER, report);
        assertEquals(35.0, ((Number) run.raw().get("profitMargin")).doubleValue(), 1e-6);
        assertEquals(0.2, ((Number) run.raw().get("assetTurnover")).doubleValue(), 1e-6);
        assertFalse(run.raw().containsKey("equityMultiplier"), run.raw().toString());
        assertFalse(run.raw().containsKey("dupontROE"), run.raw().toString());
        assertTrue(run.message().contains("权益乘数: 数据不足，无法计算"), run.message());
    }

    @Test
    @DisplayName("口径就是价格查看, 与指标中心 / #1801 利润表同一个常量")
    void amountSpecIsPriceView() {
        assertEquals(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION, FinanceFigures.AMOUNT_SPEC);
        assertEquals(com.cretas.aims.service.indicator.IndicatorValueVisibility.VALUE_SPEC, FinanceFigures.AMOUNT_SPEC);
    }
}

package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import com.cretas.aims.service.yield.ThreePriceComparisonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-16 复跑: 只读评测号问「各 SKU 毛利率排序」(SKU_GROSS_MARGIN) 拿到毛利率, 问「本月销售额和毛利」
 * (REPORT_SALES_MARGIN_SUMMARY) 被告知不显示。#1788 独立审查 H1: 正确方向是向 web 对齐、只收紧 ——
 * web shipment-sku-margin 的 {@code ShipmentSkuMarginDTO.marginRate} 标了 @PriceSensitive (价格查看口径),
 * 只读号没有价格查看 ⇒ web 上毛利率是 null。现在两个工具的毛利率都按同一个口径 ({@link SkuGrossMarginTool#MARGIN_RATE_SPEC})
 * 经同一个判定点判, 金额按 {@link SkuGrossMarginTool#MARGIN_AMOUNT_KEYS}。
 *
 * <p>每个结论都在<b>生产出口</b>上取: 工具结果经真实 {@link PriceFieldResponseAdvice#maskedViewFor} (IntentTurnSharedSteps /
 * SSE 推结果前走的那一步) 遮挡后看字段, 并要求回复文字与遮挡后的字段同进同出 (审查 M2)。web 一侧用同一个 advice
 * 遮 web 的 DTO 做对照。权限只来自一组权限码 (配置), 不看角色名。
 */
@DisplayName("毛利可见性: SKU 毛利率 / 本月销售额毛利 / web SKU 毛利 同一判定, 同一账号三处结论一致")
class GrossMarginVisibilityCrossToolContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final long USER_ID = 1L;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 权限配置形态 (不是角色名)。VIEWER = 平台默认 viewer (finance=r, 不在价格查看名单);
     * PRICE_VIEW_FINANCE_READ = 平台默认 sales_manager 的形态 (finance=r + 价格查看) —— 别的工厂的销售主管就是这样;
     * F006_SALES_MANAGER = F006 工厂级配置里的 sales_manager (没有财务模块, 复跑实测两处都拒)。
     */
    enum Account {
        VIEWER_FINANCE_READ_NO_PRICE_VIEW(Set.of("finance:read", "production:read"), true, false, false),
        PRICE_VIEW_FINANCE_READ(Set.of("finance:read", "procurement:price:view", "sales:read"), true, true, false),
        FINANCE_MANAGER(Set.of("finance:read_write", "procurement:price:view"), true, true, true),
        F006_SALES_MANAGER_NO_FINANCE(Set.of("procurement:price:view", "sales:read", "analytics:read"), false, false, false);

        final Set<String> held;
        final boolean gate;
        final boolean rate;
        final boolean amounts;

        Account(Set<String> held, boolean gate, boolean rate, boolean amounts) {
            this.held = held;
            this.gate = gate;
            this.rate = rate;
            this.amounts = amounts;
        }
    }

    @AfterEach
    void priceContextMustNotLeak() {
        assertFalse(AiBiToolTestSupport.clearPriceContextReportingLeak(), "测试后价格可见性上下文仍是隐藏态 = 泄漏");
    }

    /** 真实的字段 / 文字遮挡判定, 权限只来自这一组配置的权限码。 */
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

    /** 一个产品 收入 190 (全部已回填成本) 成本 120 ⇒ 毛利 70, 毛利率 36.84%, 成本覆盖 100%。 */
    private static OperatingReportGrossMarginQuery query() {
        Map<String, Object> row = new HashMap<>();
        row.put("product_type_id", "A");
        row.put("product_name", "卤牛肉");
        row.put("unit", "kg");
        row.put("total_qty", new BigDecimal("10"));
        row.put("order_count", 1);
        row.put("line_count", 2);
        row.put("costed_line_count", 2);
        row.put("zero_cost_line_count", 0);
        row.put("missing_price_line_count", 0);
        row.put("revenue", new BigDecimal("190"));
        row.put("costed_revenue", new BigDecimal("190"));
        row.put("cost", new BigDecimal("120"));
        OperatingReportGrossMarginQuery q = mock(OperatingReportGrossMarginQuery.class);
        when(q.fetch(eq("F1"), any(), any()))
                .thenAnswer(inv -> OperatingReportGrossMarginQuery.payload(new ArrayList<>(List.of(row)), false));
        return q;
    }

    private SkuGrossMarginTool skuTool(PriceFieldResponseAdvice priceView, OperatingReportGrossMarginQuery q) {
        SkuGrossMarginTool t = new SkuGrossMarginTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ProductTypeRepository types = mock(ProductTypeRepository.class);
        when(types.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(List.of());
        ReflectionTestUtils.setField(t, "realizedQuery", q);
        ReflectionTestUtils.setField(t, "catalogService", new ThreePriceComparisonService(types,
                mock(ProductionBatchRepository.class), mock(StandardCostService.class), mock(CostVarianceService.class)));
        ReflectionTestUtils.setField(t, "productTypeRepository", types);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        ReflectionTestUtils.setField(t, "priceView", priceView);
        return t;
    }

    private ReportSalesMarginSummaryTool summaryTool(PriceFieldResponseAdvice priceView, OperatingReportGrossMarginQuery q) {
        ReportSalesMarginSummaryTool t = new ReportSalesMarginSummaryTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(t, "marginQuery", q);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        ReflectionTestUtils.setField(t, "priceView", priceView);
        ReflectionTestUtils.setField(t, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        return t;
    }

    /** 生产出口上的样子: 回复文字 + 经 maskedViewFor 遮挡后的结果数据。 */
    private record Exit(String message, Map<String, Object> data) { }

    @SuppressWarnings("unchecked")
    private Exit run(ToolExecutor tool, PriceFieldResponseAdvice priceView) throws Exception {
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(), "{}"),
                Map.of("factoryId", "F1", "userId", USER_ID)));
        Map<String, Object> raw = mapper.convertValue(r.get("data").get("data"), Map.class);
        return new Exit(r.get("data").get("message").asText(), (Map<String, Object>) priceView.maskedViewFor(raw, USER_ID));
    }

    @SuppressWarnings("unchecked")
    private static Object path(Object node, Object... keys) {
        Object cur = node;
        for (Object k : keys) {
            if (cur == null) return null;
            if (k instanceof Integer i) {
                List<Object> list = (List<Object>) cur;
                cur = list.size() > i ? list.get(i) : null;
            } else {
                cur = ((Map<String, Object>) cur).get(k);
            }
        }
        return cur;
    }

    private record Conclusion(boolean gate, boolean rate, boolean amounts) { }

    private enum Tool { SKU, SUMMARY }

    /**
     * 这个工具在生产出口对这个账号的结论, 同时断言文字与遮挡后的字段一致:
     * 文字里有毛利率 ⇔ 字段 marginRate 在; 文字里有金额 ⇔ 字段 revenue 在; 文字里有成本覆盖 ⇒ 字段 costCoverage 在。
     */
    private Conclusion conclude(Tool which, Account account) throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(account.held);
        ToolExecutor tool = which == Tool.SKU ? skuTool(priceView, query()) : summaryTool(priceView, query());
        boolean gate = AiBiToolTestSupport.enforcerAllows(tool, account.held);
        if (!gate) {
            return new Conclusion(false, false, false);
        }
        Exit exit = run(tool, priceView);
        String m = exit.message();
        Object[] rate = which == Tool.SKU ? new Object[]{"items", 0, "marginRate"} : new Object[]{"marginRate"};
        Object[] revenue = which == Tool.SKU ? new Object[]{"items", 0, "realized", "revenue"} : new Object[]{"revenue"};
        Object[] coverage = which == Tool.SKU ? new Object[]{"items", 0, "realized", "costCoverage"} : new Object[]{"costCoverage"};
        boolean textRate = m.contains("36.84");
        boolean textAmounts = m.contains("190") || m.contains("120") || m.contains("¥70");
        String who = which + "/" + account + ": " + m + " | " + exit.data();
        assertEquals(textRate, path(exit.data(), rate) != null, "毛利率 文字 vs 遮挡后字段: " + who);
        assertEquals(textAmounts, path(exit.data(), revenue) != null, "金额 文字 vs 遮挡后字段: " + who);
        if (m.contains("成本覆盖")) {
            assertNotNull(path(exit.data(), coverage), "文字写了成本覆盖, 遮挡后字段却没有: " + who);
        }
        return new Conclusion(true, textRate, textAmounts);
    }

    /** web 对照: 同一个 advice 遮 web SKU 毛利视图的 DTO 后, 毛利率还在不在。 */
    @SuppressWarnings("unchecked")
    private static boolean webShowsMarginRate(Account account) {
        ShipmentSkuMarginDTO dto = ShipmentSkuMarginDTO.builder()
                .marginRate(new BigDecimal("36.84")).totalRevenue(new BigDecimal("190"))
                .rows(List.of(ShipmentSkuMarginDTO.Row.builder()
                        .productTypeId("A").productName("卤牛肉").marginRate(new BigDecimal("36.84")).build()))
                .build();
        Map<String, Object> view = (Map<String, Object>) priceViewFor(account.held).maskedViewFor(dto, USER_ID);
        boolean top = view.get("marginRate") != null;
        assertEquals(top, path(view, "rows", 0, "marginRate") != null, "web 视图顶层与行的毛利率口径应相同: " + view);
        return top;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("同一账号: 两个工具 能不能用 / 有没有毛利率 / 有没有金额 相同, 毛利率与 web 一致")
    void sameAccountSameMarginConclusionInBothToolsAndOnWeb(Account account) throws Exception {
        Conclusion sku = conclude(Tool.SKU, account);
        Conclusion summary = conclude(Tool.SUMMARY, account);

        assertEquals(sku, summary, account + ": 两个工具结论不一致 sku=" + sku + " summary=" + summary);
        assertEquals(new Conclusion(account.gate, account.rate, account.amounts), sku, account.name());
        if (account.gate) {
            // web SKU 毛利页的类门同为 finance:read|read_write; 过了门, 毛利率与 web 同一个判定
            assertEquals(webShowsMarginRate(account), sku.rate(), account + ": AI 毛利率与 web 不一致");
        }
    }

    @Test
    @DisplayName("只读号 (财务查看, 无价格查看): 两个工具都不给毛利率且不查库; 有价格查看的号两处都给 (阳性对照)")
    void financeReadWithoutPriceViewGetsNoRateInEitherTool_priceViewControlGetsIt() throws Exception {
        Account viewer = Account.VIEWER_FINANCE_READ_NO_PRICE_VIEW;
        PriceFieldResponseAdvice vView = priceViewFor(viewer.held);
        OperatingReportGrossMarginQuery q1 = query();
        OperatingReportGrossMarginQuery q2 = query();
        Exit s = run(summaryTool(vView, q1), vView);
        Exit k = run(skuTool(vView, q2), vView);
        for (Exit e : List.of(s, k)) {
            assertTrue(e.message().contains("毛利率按当前账号的权限配置不显示"), e.message());
            assertFalse(e.message().contains("36.84") || e.message().contains("190"), e.message());
        }
        assertNull(s.data().get("marginRate"));
        assertTrue(((List<?>) k.data().get("items")).isEmpty(), k.data().toString());
        verify(q1, never()).fetch(any(), any(), any());
        verify(q2, never()).fetch(any(), any(), any());

        Account control = Account.PRICE_VIEW_FINANCE_READ;
        PriceFieldResponseAdvice cView = priceViewFor(control.held);
        Exit sc = run(summaryTool(cView, query()), cView);
        Exit kc = run(skuTool(cView, query()), cView);
        assertTrue(sc.message().contains("毛利率 36.84%"), sc.message());
        assertTrue(kc.message().contains("36.84%"), kc.message());
        // 金额与成本覆盖率 (Rule 2b, 要财务管理) 两处字段都被遮, 文字也都不写
        assertNull(sc.data().get("revenue"));
        assertNull(sc.data().get("costCoverage"));
        assertFalse(sc.message().contains("成本覆盖") || kc.message().contains("成本覆盖"), sc.message() + " / " + kc.message());
    }

    @Test
    @DisplayName("两个工具同一个门、同一组金额键, 毛利率口径就是 web DTO 上的那个注解值")
    void bothToolsAskTheSameGateTheSameAmountKeysAndTheWebMarginRateSpec() throws Exception {
        assertEquals(new SkuGrossMarginTool().getRequiredPermissions(), new ReportSalesMarginSummaryTool().getRequiredPermissions());
        assertEquals(List.of("revenue", "cost", "grossProfit", "price", "unitCost"), List.of(SkuGrossMarginTool.MARGIN_AMOUNT_KEYS));
        String web = ShipmentSkuMarginDTO.class.getDeclaredField("marginRate").getAnnotation(PriceSensitive.class).permission();
        assertEquals(web, SkuGrossMarginTool.MARGIN_RATE_SPEC);
        assertEquals(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION, SkuGrossMarginTool.MARGIN_RATE_SPEC);
    }
}

package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
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
import static org.mockito.Mockito.when;

/**
 * 2026-11-16 复跑: 同一个只读评测号问「各 SKU 毛利率排序」(SKU_GROSS_MARGIN) 拿到毛利率, 问「本月销售额和毛利」
 * (REPORT_SALES_MARGIN_SUMMARY) 却被告知毛利按权限不显示。两处现在按同一个判据:
 * 工具门 finance:read | finance:read_write (中央 ToolRbacEnforcer) 决定能不能用, 过了门毛利率照给;
 * 金额 (销售额 / 成本 / 毛利) 是否写出 = {@link SkuGrossMarginTool#MARGIN_AMOUNT_KEYS} 经真实
 * {@link PriceFieldResponseAdvice} (按权限配置判) 决定。
 *
 * <p>同一个账号 (同一组权限配置) 跑两个真实工具入口 {@code execute(ToolCall, context)}, 结论必须一致;
 * 三种权限形态里两种是阴性对照 (金额看不到 / 整个被拒)。
 */
@DisplayName("毛利可见性: SKU 毛利率与本月销售额毛利同一判据, 同一账号两处结论一致")
class GrossMarginVisibilityCrossToolContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final long USER_ID = 1L;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 权限配置 (不是角色名): 只读评测号实测有财务查看、无财务管理; 销售主管在 F006 配置里没有财务模块。 */
    enum Account {
        VIEWER_FINANCE_READ_ONLY(Set.of("finance:read", "production:read"), true, true, false),
        FINANCE_MANAGER(Set.of("finance:read_write", "procurement:price:view"), true, true, true),
        SALES_MANAGER_NO_FINANCE(Set.of("procurement:price:view", "sales:read", "analytics:read"), false, false, false);

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
        return advice;
    }

    /** 一个产品 收入 190 (全部已回填成本) 成本 120 ⇒ 毛利 70, 毛利率 36.84%。 */
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

    private SkuGrossMarginTool skuTool(PriceFieldResponseAdvice priceView) {
        SkuGrossMarginTool t = new SkuGrossMarginTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ProductTypeRepository types = mock(ProductTypeRepository.class);
        when(types.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(List.of());
        ReflectionTestUtils.setField(t, "realizedQuery", query());
        ReflectionTestUtils.setField(t, "catalogService", new ThreePriceComparisonService(types,
                mock(ProductionBatchRepository.class), mock(StandardCostService.class), mock(CostVarianceService.class)));
        ReflectionTestUtils.setField(t, "productTypeRepository", types);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        ReflectionTestUtils.setField(t, "priceView", priceView);
        return t;
    }

    private ReportSalesMarginSummaryTool summaryTool(PriceFieldResponseAdvice priceView) {
        ReportSalesMarginSummaryTool t = new ReportSalesMarginSummaryTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(t, "marginQuery", query());
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        ReflectionTestUtils.setField(t, "priceView", priceView);
        ReflectionTestUtils.setField(t, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        return t;
    }

    private JsonNode run(com.cretas.aims.ai.tool.ToolExecutor tool) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(), "{}"),
                Map.of("factoryId", "F1", "userId", USER_ID)));
    }

    private record Conclusion(boolean gate, boolean rate, boolean amounts) { }

    /** 这个工具对这个账号给出的结论: 能不能用 / 回复里有没有毛利率 / 回复里有没有金额。 */
    private Conclusion conclude(com.cretas.aims.ai.tool.ToolExecutor tool, Account account) throws Exception {
        boolean gate = AiBiToolTestSupport.enforcerAllows(tool, account.held);
        if (!gate) {
            return new Conclusion(false, false, false);
        }
        String message = run(tool).get("data").get("message").asText();
        boolean rate = message.contains("36.84");
        // 收入 190 / 成本 120 / 毛利 70 任何一个金额出现都算写了金额
        boolean amounts = message.contains("190") || message.contains("120") || message.contains("¥70");
        return new Conclusion(true, rate, amounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("同一账号: SKU 毛利率与本月销售额毛利 能不能用 / 有没有毛利率 / 有没有金额 三项结论相同")
    void sameAccountSameMarginConclusionInBothTools(Account account) throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(account.held);
        Conclusion sku = conclude(skuTool(priceView), account);
        Conclusion summary = conclude(summaryTool(priceView), account);

        assertEquals(sku, summary, account + ": 两个工具结论不一致 sku=" + sku + " summary=" + summary);
        // 钉住每种权限形态的期望 (阴性对照: 只读号金额两处都不出; 无财务模块两处都拒)
        assertEquals(new Conclusion(account.gate, account.rate, account.amounts), sku, account.name());
    }

    @Test
    @DisplayName("只读号 (财务查看, 无财务管理): 本月汇总给毛利率, 金额字段与金额图都不出")
    void financeReadOnlyGetsRateButNoAmountFieldsOrChart() throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(Account.VIEWER_FINANCE_READ_ONLY.held);
        JsonNode r = run(summaryTool(priceView));
        JsonNode d = r.get("data").get("data");
        assertEquals(36.84, d.get("marginRate").asDouble(), 0.001);
        assertEquals(100.0, d.get("costCoverage").asDouble(), 0.001);
        assertFalse(d.has("revenue") || d.has("cost") || d.has("grossProfit") || d.has("costedRevenue") || d.has("charts"), d.toString());
        assertFalse(d.get("products").get(0).has("revenue") || d.get("products").get(0).has("grossProfit"), d.toString());
        assertTrue(r.get("data").get("message").asText().contains("金额按当前账号的权限配置不显示"));
        // 对照: 有财务管理的账号同一份数据金额都在
        JsonNode full = run(summaryTool(priceViewFor(Account.FINANCE_MANAGER.held))).get("data").get("data");
        assertEquals(190.0, full.get("revenue").asDouble(), 0.001);
        assertEquals(70.0, full.get("grossProfit").asDouble(), 0.001);
    }

    @Test
    @DisplayName("两个工具问金额可见性用的是同一组键, 且工具门是同一组权限码")
    void bothToolsAskTheSameGateAndTheSameAmountKeys() {
        assertEquals(new SkuGrossMarginTool().getRequiredPermissions(), new ReportSalesMarginSummaryTool().getRequiredPermissions());
        assertEquals(List.of("revenue", "cost", "grossProfit", "price", "unitCost"), List.of(SkuGrossMarginTool.MARGIN_AMOUNT_KEYS));
    }
}

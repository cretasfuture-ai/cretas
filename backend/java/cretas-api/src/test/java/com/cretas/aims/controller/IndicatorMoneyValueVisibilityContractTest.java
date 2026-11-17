package com.cretas.aims.controller;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.indicator.IndicatorAlertTool;
import com.cretas.aims.ai.tool.impl.indicator.IndicatorComparisonTool;
import com.cretas.aims.ai.tool.impl.indicator.IndicatorQueryTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 指标中心以前用写死的角色名单 {factory_super_admin, permission_admin, operator} 放行, 放行后数值一律给 ——
 * operator 看得到菜品毛利率 (RESTAURANT_DISH_MARGIN)、客单价、库存价值的当前值与趋势。
 *
 * <p>现在: 金额 / 毛利 / 财务类指标 ({@link IndicatorQueryTool#carriesMoneyFigure}) 的数值, web 指标中心与
 * AI 的 indicator_query / indicator_comparison 都按价格查看口径 ({@link IndicatorQueryTool#VALUE_SPEC}) 经同一个
 * {@link PriceFieldResponseAdvice#showsSpec} 判 (权限只来自一组权限码, 不看角色名); 看不到 ⇒ 数值键不存在 (不是 0)。
 * 良品率这类非金额指标保持原样。AI 两个工具走真实 execute 入口, web 走 MockMvc 真实请求。
 */
@DisplayName("指标数值可见性: 金额 / 毛利类按价格查看口径, web 与 AI 同一判定")
class IndicatorMoneyValueVisibilityContractTest {

    private static final long USER_ID = 11L;
    private static final String F = "F006";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    enum Account {
        /** 价格查看在配置里: 看得到。 */
        PRICE_VIEW(Set.of("procurement:price:view", "production:read"), true),
        /** 操作员式配置 (没有价格查看): 金额 / 毛利类数值看不到。 */
        NO_PRICE_VIEW(Set.of("production:read", "quality:read"), false);

        final Set<String> held;
        final boolean sees;

        Account(Set<String> held, boolean sees) {
            this.held = held;
            this.sees = sees;
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
        u.setFactoryId(F);
        u.setRoleCode("operator");
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        return advice;
    }

    private static Indicator indicator(String id, String code, String name, String category, String unit, String last) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setFactoryId(F);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit(unit);
        i.setIsActive(true);
        i.setComputeStrategy("CACHED");
        i.setLastValue(new BigDecimal(last));
        i.setConfig(new HashMap<>());
        return i;
    }

    private static final Indicator MARGIN = indicator("ind-rdm", "RESTAURANT_DISH_MARGIN", "菜品毛利率", "RESTAURANT", "%", "62.5");
    private static final Indicator YIELD = indicator("ind-fyr", "FACTORY_YIELD_RATE", "综合良品率", "FACTORY", "%", "96.5");

    private static IndicatorVersion version(Indicator i, BigDecimal value) {
        IndicatorVersion v = new IndicatorVersion();
        v.setId("v-" + i.getId());
        v.setIndicatorId(i.getId());
        v.setFactoryId(F);
        v.setValue(value);
        v.setPeriodStart(LocalDate.of(2026, 11, 16));
        v.setPeriodEnd(LocalDate.of(2026, 11, 16));
        v.setComputedAt(LocalDateTime.of(2026, 11, 16, 8, 0));
        v.setComputeSource("REAL_BUSINESS:" + i.getCode());
        v.setAlertLevel("GREEN");
        return v;
    }

    /** 种子里 RESTAURANT_DISH_MARGIN 的 GREEN ≥ 60: 看到 GREEN 就知道毛利率 ≥ 60% (复审 M3)。 */
    private static IndicatorThreshold greenAtLeast(Indicator i, String value) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setId("thr-" + i.getId());
        t.setFactoryId(F);
        t.setIndicatorId(i.getId());
        t.setAlertLevel("GREEN");
        t.setOperator("GTE");
        t.setThresholdValue(new BigDecimal(value));
        t.setIsActive(true);
        return t;
    }

    private final IndicatorRepository indicators = mock(IndicatorRepository.class);
    private final IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
    private final IndicatorThresholdRepository thresholds = mock(IndicatorThresholdRepository.class);

    {
        for (Indicator i : List.of(MARGIN, YIELD)) {
            when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull(i.getCode(), F)).thenReturn(Optional.of(i));
            when(indicators.findById(i.getId())).thenReturn(Optional.of(i));
            IndicatorVersion v = version(i, i.getLastValue());
            when(versions.findFirstByIndicatorIdOrderByComputedAtDesc(i.getId())).thenReturn(Optional.of(v));
            when(versions.findInWindow(eq(i.getId()), any(), any())).thenReturn(List.of(v));
            when(versions.findByIndicatorIdOrderByComputedAtDesc(eq(i.getId()), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(v)));
            when(thresholds.findByIndicatorIdAndIsActiveTrue(i.getId())).thenReturn(List.of(greenAtLeast(i, "60")));
        }
        when(indicators.findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(F)).thenReturn(List.of(MARGIN, YIELD));
        when(indicators.findActiveByFactoryId(F)).thenReturn(List.of(MARGIN, YIELD));
    }

    private <T extends AbstractBusinessTool> T wire(T tool, PriceFieldResponseAdvice priceView) {
        ReflectionTestUtils.setField(tool, "indicatorRepository", indicators);
        ReflectionTestUtils.setField(tool, "versionRepository", versions);
        ReflectionTestUtils.setField(tool, "thresholdRepository", thresholds);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceView);
        return tool;
    }

    private JsonNode execute(AbstractBusinessTool tool, String args) throws Exception {
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(), args),
                Map.of("factoryId", F, "userId", USER_ID)));
        assertTrue(r.get("success").asBoolean(), r.toString());
        return r.get("data");
    }

    private MockMvc web(PriceFieldResponseAdvice priceView) {
        CanvasIndicatorsController controller = new CanvasIndicatorsController(indicators, versions, thresholds,
                mock(IndicatorComputationRepository.class), mock(IndicatorTreeNodeRepository.class),
                mock(BatchLineageEdgeRepository.class), mock(BatchLineageClosureRepository.class),
                new SandboxedSpelEvaluator());
        ReflectionTestUtils.setField(controller, "priceView", priceView);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private JsonNode webGet(MockMvc mvc, String path) throws Exception {
        String body = mvc.perform(get("/api/mobile/" + F + "/canvas-indicators" + path).requestAttr("userId", USER_ID))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = mapper.readTree(body);
        assertTrue(r.get("success").asBoolean(), body);
        return r.get("data");
    }

    private static JsonNode byCode(JsonNode array, String codeField, String code) {
        for (JsonNode n : array) {
            if (code.equals(n.path(codeField).asText())) return n;
        }
        fail(code + " 不在 " + array);
        return null;
    }

    /** 毛利率的数值在这个节点上: 看得到 ⇒ 等于 62.5; 看不到 ⇒ 键不存在 (不是 0、不是 null 占位) 且标了 valueHidden。 */
    private static void assertMarginValue(boolean sees, JsonNode node, String key, String where) {
        if (sees) {
            assertTrue(node.has(key), where + ": " + node);
            assertEquals(0, new BigDecimal("62.5").compareTo(node.get(key).decimalValue()), where + ": " + node);
        } else {
            assertFalse(node.has(key), where + " 不该有 " + key + " (也不是 0): " + node);
            assertTrue(node.path("valueHidden").asBoolean(), where + ": " + node);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("AI indicator_query: 毛利率当前值与趋势按价格查看口径; 文字里也没有")
    void indicatorQueryGatesMarginValue(Account account) throws Exception {
        JsonNode out = execute(wire(new IndicatorQueryTool(), priceViewFor(account.held)),
                "{\"indicator_code\":\"RESTAURANT_DISH_MARGIN\"}");
        JsonNode data = out.get("data");
        assertMarginValue(account.sees, data, "currentValue", "currentValue");
        assertMarginValue(account.sees, data.get("trend").get(0), "value", "trend[0]");
        assertEquals(account.sees, out.get("message").asText().contains("62.5"), out.get("message").asText());
        if (!account.sees) {
            assertEquals("HIDDEN", data.get("valueStatus").asText());
            assertTrue(out.get("message").asText().contains("按当前账号的权限配置不显示"), out.get("message").asText());
        }
        // 告警级别与阈值会夹出数值区间: 与数值同进同出
        assertEquals(account.sees, data.has("breachLevel"), "breachLevel: " + data);
        assertEquals(account.sees, data.has("latestAlertLevel"), "latestAlertLevel: " + data);
        assertEquals(account.sees, data.has("thresholds"), "thresholds: " + data);
        assertEquals(account.sees, data.get("trend").get(0).has("alertLevel"), "trend[0].alertLevel: " + data);
        if (account.sees) {
            assertEquals("GREEN", data.get("breachLevel").asText());
            assertEquals(0, new BigDecimal("60").compareTo(data.get("thresholds").get(0).get("value").decimalValue()));
        } else {
            assertFalse(data.toString().contains("GREEN"), "任何位置都不该带出告警级别: " + data);
            assertFalse(out.get("message").asText().contains("GREEN"), out.get("message").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("AI indicator_comparison: 毛利率按口径, 良品率照常")
    void indicatorComparisonGatesOnlyMoneyRows(Account account) throws Exception {
        JsonNode out = execute(wire(new IndicatorComparisonTool(), priceViewFor(account.held)),
                "{\"indicator_codes\":[\"RESTAURANT_DISH_MARGIN\",\"FACTORY_YIELD_RATE\"]}");
        JsonNode rows = out.get("data").get("rows");
        assertMarginValue(account.sees, byCode(rows, "code", "RESTAURANT_DISH_MARGIN"), "currentValue", "margin row");
        JsonNode yield = byCode(rows, "code", "FACTORY_YIELD_RATE");
        assertEquals(0, new BigDecimal("96.5").compareTo(yield.get("currentValue").decimalValue()), yield.toString());
        // 告警级别与数值同进同出; 被藏的行不参与「最严重」(否则 worstIndicator 把级别带出去)
        JsonNode margin = byCode(rows, "code", "RESTAURANT_DISH_MARGIN");
        assertEquals(account.sees, margin.has("breachLevel"), margin.toString());
        assertEquals(account.sees, margin.has("thresholdsCount"), margin.toString());
        assertEquals("GREEN", yield.get("breachLevel").asText(), "良品率级别照常: " + yield);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("AI indicator_alert: 毛利率当前值与级别按口径; 看不到就不进告警名单, 只报有几个不显示")
    void indicatorAlertGatesMoneyRows(Account account) throws Exception {
        JsonNode out = execute(wire(new IndicatorAlertTool(), priceViewFor(account.held)), "{\"min_severity\":\"ALL\"}");
        JsonNode data = out.get("data");
        JsonNode breached = data.get("breached");
        boolean marginListed = false;
        for (JsonNode row : breached) {
            if ("RESTAURANT_DISH_MARGIN".equals(row.path("code").asText())) {
                marginListed = true;
                assertEquals(0, new BigDecimal("62.5").compareTo(row.get("currentValue").decimalValue()), row.toString());
            }
        }
        assertEquals(account.sees, marginListed, "毛利率在不在告警名单: " + data);
        byCode(breached, "code", "FACTORY_YIELD_RATE");
        String message = out.get("message").asText();
        assertFalse(message.contains("62.5"), message);
        if (account.sees) {
            assertFalse(data.has("valueHidden"), data.toString());
        } else {
            assertEquals(1, data.get("valueHiddenCount").asInt(), data.toString());
            assertEquals("RESTAURANT_DISH_MARGIN", data.get("valueHidden").get(0).asText());
            assertTrue(message.contains("按当前账号的权限配置不显示"), message);
            assertFalse(data.toString().contains("62.5"), data.toString());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("web 指标中心 (MockMvc): 列表 / 详情 / 趋势 / 指标树 上毛利率数值按口径, 良品率照常")
    void webIndicatorCenterGatesMarginValue(Account account) throws Exception {
        MockMvc mvc = web(priceViewFor(account.held));
        JsonNode list = webGet(mvc, "");
        assertMarginValue(account.sees, byCode(list, "code", "RESTAURANT_DISH_MARGIN"), "lastValue", "list");
        assertEquals(0, new BigDecimal("96.5").compareTo(byCode(list, "code", "FACTORY_YIELD_RATE").get("lastValue").decimalValue()));

        JsonNode detail = webGet(mvc, "/ind-rdm");
        assertMarginValue(account.sees, detail, "lastValue", "detail");
        assertMarginValue(account.sees, detail.get("latestVersion"), "value", "detail.latestVersion");
        // 阈值与版本告警级别会夹出数值区间, 与数值同进同出 (复审 M3)
        assertEquals(account.sees, detail.has("thresholds"), "detail.thresholds: " + detail);
        assertEquals(account.sees, detail.get("latestVersion").has("alertLevel"), "latestVersion.alertLevel: " + detail);
        assertEquals(account.sees, webGet(mvc, "/ind-rdm/versions").get("content").get(0).has("alertLevel"), "versions alertLevel");
        assertEquals(account.sees, webGet(mvc, "/ind-rdm/measurements").get(0).has("alertLevel"), "measurements alertLevel");
        JsonNode yieldDetail = webGet(mvc, "/ind-fyr");
        assertEquals(1, yieldDetail.get("thresholds").size(), "良品率阈值照常: " + yieldDetail);
        assertEquals("GREEN", yieldDetail.get("latestVersion").get("alertLevel").asText());

        assertMarginValue(account.sees, webGet(mvc, "/ind-rdm/measurements").get(0), "value", "measurements[0]");
        assertTrue(webGet(mvc, "/ind-fyr/measurements").get(0).has("value"), "良品率趋势照常给");

        JsonNode tree = webGet(mvc, "/tree").get("nodes");
        assertMarginValue(account.sees, byCode(tree, "code", "RESTAURANT_DISH_MARGIN"), "lastValue", "tree");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("同一账号: AI 有没有毛利率数值 ⇔ web 有没有")
    void sameAccountSameConclusionOnWebAndAi(Account account) throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(account.held);
        boolean ai = execute(wire(new IndicatorQueryTool(), priceView), "{\"indicator_code\":\"RESTAURANT_DISH_MARGIN\"}")
                .get("data").has("currentValue");
        boolean web = webGet(web(priceView), "/ind-rdm").has("lastValue");
        assertEquals(web, ai, account.name());
        assertEquals(account.sees, ai, account.name());
    }

    @Test
    @DisplayName("没带登录用户 / 判定器没装配的请求: 毛利率数值不给 (fail closed)")
    void webFailsClosedWithoutCaller() throws Exception {
        MockMvc noAdvice = web(null);
        assertFalse(webGet(noAdvice, "/ind-rdm").has("lastValue"));
        String body = web(priceViewFor(Account.PRICE_VIEW.held))
                .perform(get("/api/mobile/" + F + "/canvas-indicators/ind-rdm"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(mapper.readTree(body).get("data").has("lastValue"), body);
    }

    @Test
    @DisplayName("哪些指标算金额 / 毛利 / 财务类: 只看指标已有的分类 / 单位 / 编码 / 名称")
    void classifiesSeededIndicators() {
        record Row(String code, String name, String category, String unit, boolean money) { }
        assertTrue(IndicatorQueryTool.carriesMoneyFigure(null), "认不出 ⇒ 当金额 (fail closed)");
        List<Row> rows = List.of(
                new Row("RESTAURANT_DISH_MARGIN", "菜品毛利率", "RESTAURANT", "%", true),
                new Row("RESTAURANT_AVG_ORDER_VALUE", "平均客单价", "RESTAURANT", "元", true),
                new Row("FINANCE_INVENTORY_VALUE", "库存总价值", "FINANCE", "元", true),
                new Row("FACTORY_LU_UNIT_COST", "卤味单位成本", "FINANCE", "元/单位", true),
                new Row("B2B_TOTAL_REVENUE_MTD", "本月销售额", "FACTORY", "元", true),
                new Row("FACTORY_YIELD_RATE", "综合良品率", "FACTORY", "%", false),
                new Row("RESTAURANT_TABLE_TURNOVER", "翻台率", "RESTAURANT", "次/天", false),
                new Row("B2B_ORDER_COUNT_MTD", "本月订单数", "FACTORY", "单", false),
                new Row("QUALITY_REJECT_RATE", "质检不合格率", "QUALITY", "%", false),
                new Row("FOOD_SAFETY_HACCP_VIOLATIONS", "HACCP 违规次数", "FOOD_SAFETY", "次", false),
                new Row("RESTAURANT_WASTAGE_RATE", "食材损耗率", "RESTAURANT", "%", false),
                new Row("INVENTORY_TURNOVER_RATE", "库存周转率", "INVENTORY", "次/月", false),
                new Row("FACTORY_PLAN_ACHIEVE_RATE", "生产计划达成率", "FACTORY", "%", false),
                new Row("RESTAURANT_FOOD_SAFETY_PASS", "食品安全检查通过率", "RESTAURANT", "%", false),
                // 管理员自建 (POST /canvas-indicators 编码 / 名称 / 单位自由填): 复审 M4 列出的漏判形状
                new Row("AVG_TICKET_PRICE", "客单价", "RESTAURANT", null, true),
                new Row("CUSTOM_KPI_1", "客单价", "RESTAURANT", "", true),
                new Row("CUSTOM_KPI_2", "本周退款金额", "SALES", null, true),
                new Row("ORDER_AMOUNT_TOTAL", "订单汇总", "FACTORY", null, true),
                new Row("STORE_GMV", "门店流水", "RESTAURANT", null, true),
                new Row("CUSTOM_KPI_3", "出口额", "SALES", "USD", true),
                new Row("CUSTOM_KPI_4", "出口额", "SALES", "$", true),
                new Row("CUSTOM_KPI_5", "月度流水", "FACTORY", "万", true),
                new Row("CUSTOM_KPI_6", "人均工资", "FACTORY", null, true),
                new Row("CUSTOM_KPI_7", "应收账款余额", "FACTORY", null, true),
                new Row("CUSTOM_KPI_8", "原料单价", "FACTORY", "元/kg", true));
        for (Row r : rows) {
            Indicator i = indicator("x", r.code(), r.name(), r.category(), r.unit(), "1");
            assertEquals(r.money(), IndicatorQueryTool.carriesMoneyFigure(i), r.toString());
        }
        assertEquals(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION, IndicatorQueryTool.VALUE_SPEC);
    }
}

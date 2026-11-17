package com.cretas.aims.controller;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.indicator.IndicatorQueryTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.indicator.FactoryIndicatorApplicabilityService;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * web 指标中心真实在用的入口 {@code /api/mobile/{factoryId}/indicators} (IndicatorCenterDashboard 调 listIndicators /
 * getIndicatorValue, 复审 H2): 以前列表 lastValue、/{code}/value、/{code}/versions (直接返回实体)、recompute、详情与
 * /thresholds 对任何有 analytics:read 的账号都出金额类指标的数值、告警级别与阈值。
 *
 * <p>现在全部接 {@link com.cretas.aims.service.indicator.IndicatorValueVisibility} —— 与 /canvas-indicators、AI 指标工具
 * 同一个判定 (价格查看口径, 真实 {@link PriceFieldResponseAdvice}, 权限只来自一组权限码, 不看角色名), fail closed。
 * MockMvc 真实请求, 不经 Spring 上下文 (standalone, 不新建 ApplicationContext)。
 */
@DisplayName("/indicators: 金额类指标的数值 / 告警级别 / 阈值按价格查看口径, 与 AI 同一判定")
class IndicatorCenterValueVisibilityContractTest {

    private static final long USER_ID = 21L;
    private static final String F = "F006";
    private static final String BASE = "/api/mobile/" + F + "/indicators";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    enum Account {
        /** 价格查看在配置里 (没有 finance:read_write 也看得到)。 */
        PRICE_VIEW(Set.of("procurement:price:view", "analytics:read"), true),
        /** 有 analytics:read 与财务读写、没有价格查看 (复审 H2 失败场景): 看不到。 */
        NO_PRICE_VIEW(Set.of("analytics:read", "analytics:read_write", "finance:read_write"), false);

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
        u.setRoleCode("dispatcher");
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

    /** 管理员自建的客单价 (单位留空): 只能靠名称认出是金额 (复审 M4)。 */
    private final Indicator ticket = indicator("ind-tkt", "CUSTOM_KPI_9", "客单价", "RESTAURANT", null, "88.8");
    private final Indicator revenue = indicator("ind-srm", "SALES_MONTHLY_REVENUE", "月度销售额", "SALES", "元", "123456.78");
    private final Indicator yield = indicator("ind-fyr", "FACTORY_YIELD_RATE", "综合良品率", "FACTORY", "%", "96.5");

    private final IndicatorRepository indicators = mock(IndicatorRepository.class);
    private final IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
    private final IndicatorThresholdRepository thresholds = mock(IndicatorThresholdRepository.class);
    private final IndicatorQueryService queryService = mock(IndicatorQueryService.class);
    private final IndicatorTreeNodeRepository treeNodes = mock(IndicatorTreeNodeRepository.class);

    {
        java.util.List<IndicatorVersion> recent = new java.util.ArrayList<>();
        for (Indicator i : List.of(ticket, revenue, yield)) {
            when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull(i.getCode(), F)).thenReturn(Optional.of(i));
            IndicatorThreshold t = new IndicatorThreshold();
            t.setId("thr-" + i.getId());
            t.setFactoryId(F);
            t.setIndicatorId(i.getId());
            t.setAlertLevel("GREEN");
            t.setOperator("GTE");
            t.setThresholdValue(new BigDecimal("80"));
            t.setIsActive(true);
            when(thresholds.findActiveByIndicatorIdAndFactoryId(i.getId(), F)).thenReturn(List.of(t));
            IndicatorVersion v = new IndicatorVersion();
            v.setId("v-" + i.getId());
            v.setFactoryId(F);
            v.setIndicatorId(i.getId());
            v.setValue(i.getLastValue());
            v.setAlertLevel("GREEN");
            v.setPeriodStart(LocalDate.of(2026, 11, 1));
            v.setPeriodEnd(LocalDate.of(2026, 11, 16));
            v.setComputedAt(LocalDateTime.of(2026, 11, 16, 8, 0));
            recent.add(v);
            when(queryService.computeForCode(eq(i.getCode()), eq(F), any(), any())).thenReturn(
                    new IndicatorValueResult(i.getLastValue(), LocalDateTime.of(2026, 11, 16, 8, 0), false, "cache"));
        }
        when(indicators.findActiveByFactoryId(F)).thenReturn(List.of(ticket, revenue, yield));
        when(versions.findRecentByFactoryId(F)).thenReturn(recent);
        List<com.cretas.aims.entity.indicator.IndicatorTreeNode> roots = new java.util.ArrayList<>();
        for (Indicator i : List.of(revenue, yield)) {
            com.cretas.aims.entity.indicator.IndicatorTreeNode n = new com.cretas.aims.entity.indicator.IndicatorTreeNode();
            n.setId("itn-" + i.getId());
            n.setFactoryId(F);
            n.setIndicatorId(i.getId());
            n.setDepth(0);
            roots.add(n);
        }
        when(treeNodes.findRootsByFactoryId(F)).thenReturn(roots);
    }

    private MockMvc web(PriceFieldResponseAdvice priceView) {
        IndicatorController controller = new IndicatorController(queryService, indicators,
                treeNodes, thresholds, versions,
                mock(FactoryIndicatorApplicabilityService.class), mock(DataScopeResolver.class));
        ReflectionTestUtils.setField(controller, "priceView", priceView);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private JsonNode call(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("userId", USER_ID)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode r = mapper.readTree(body);
        assertTrue(r.get("success").asBoolean(), body);
        return r;
    }

    private static JsonNode byCode(JsonNode array, String code) {
        for (JsonNode n : array) {
            if (code.equals(n.path("code").asText())) return n;
        }
        fail(code + " 不在 " + array);
        return null;
    }

    /** 看得到 ⇒ 等于原值; 看不到 ⇒ null (不是 0) 且标 valueHidden。 */
    private static void assertValue(boolean sees, JsonNode node, String key, String expected, String where) {
        if (sees) {
            assertEquals(0, new BigDecimal(expected).compareTo(node.get(key).decimalValue()), where + ": " + node);
            assertFalse(node.has("valueHidden"), where + ": " + node);
        } else {
            assertTrue(!node.has(key) || node.get(key).isNull(), where + " 不该有 " + key + " (也不是 0): " + node);
            assertTrue(node.path("valueHidden").asBoolean(), where + ": " + node);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("列表 lastValue / {code}/value / versions / recompute: 金额类按口径, 良品率照常")
    void valuesFollowPriceView(Account account) throws Exception {
        MockMvc mvc = web(priceViewFor(account.held));

        JsonNode list = call(mvc, get(BASE)).get("data");
        assertValue(account.sees, byCode(list, "CUSTOM_KPI_9"), "lastValue", "88.8", "list 客单价");
        assertValue(account.sees, byCode(list, "SALES_MONTHLY_REVENUE"), "lastValue", "123456.78", "list 销售额");
        assertValue(true, byCode(list, "FACTORY_YIELD_RATE"), "lastValue", "96.5", "list 良品率");

        assertValue(account.sees, call(mvc, get(BASE + "/SALES_MONTHLY_REVENUE/value")).get("data"),
                "value", "123456.78", "value");
        assertValue(true, call(mvc, get(BASE + "/FACTORY_YIELD_RATE/value")).get("data"), "value", "96.5", "value 良品率");

        JsonNode version = call(mvc, get(BASE + "/SALES_MONTHLY_REVENUE/versions")).get("data").get("content").get(0);
        assertValue(account.sees, version, "value", "123456.78", "versions");
        assertEquals(account.sees, version.hasNonNull("alertLevel"), "versions alertLevel: " + version);
        assertEquals("v-ind-srm", version.get("id").asText(), "转 DTO 后其余字段照旧");
        JsonNode yieldVersion = call(mvc, get(BASE + "/FACTORY_YIELD_RATE/versions")).get("data").get("content").get(0);
        assertEquals("GREEN", yieldVersion.get("alertLevel").asText());

        assertValue(account.sees, call(mvc, post(BASE + "/SALES_MONTHLY_REVENUE/recompute")).get("data"),
                "value", "123456.78", "recompute");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("详情 lastValue + thresholds 与 /thresholds/{code}: 阈值会夹出数值区间, 与数值同进同出")
    void thresholdsFollowPriceView(Account account) throws Exception {
        MockMvc mvc = web(priceViewFor(account.held));
        JsonNode detail = call(mvc, get(BASE + "/CUSTOM_KPI_9")).get("data");
        assertValue(account.sees, detail, "lastValue", "88.8", "detail");
        assertEquals(account.sees ? 1 : 0, detail.get("thresholds").size(), "detail.thresholds: " + detail);

        JsonNode r = call(mvc, get(BASE + "/thresholds/CUSTOM_KPI_9"));
        assertEquals(account.sees ? 1 : 0, r.get("data").size(), r.toString());
        if (!account.sees) {
            assertTrue(r.get("message").asText().contains("按当前账号的权限配置不显示"), "如实说, 不说没配置: " + r);
        }
        assertFalse(!account.sees && r.toString().contains("80"), r.toString());

        assertEquals(1, call(mvc, get(BASE + "/thresholds/FACTORY_YIELD_RATE")).get("data").size(), "良品率阈值照常");
        assertEquals(1, call(mvc, get(BASE + "/FACTORY_YIELD_RATE")).get("data").get("thresholds").size());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("指标树: 树节点不带数值; 金额类且看不到的节点标 valueHidden (前端说「无权限查看」, 不说「待配置」)")
    void treeMarksHiddenNodes(Account account) throws Exception {
        JsonNode tree = call(web(priceViewFor(account.held)), get(BASE + "/tree")).get("data");
        JsonNode revenueNode = null;
        JsonNode yieldNode = null;
        for (JsonNode n : tree) {
            if ("ind-srm".equals(n.get("indicatorId").asText())) revenueNode = n;
            if ("ind-fyr".equals(n.get("indicatorId").asText())) yieldNode = n;
        }
        assertNotNull(revenueNode, tree.toString());
        assertNotNull(yieldNode, tree.toString());
        assertEquals(!account.sees, revenueNode.path("valueHidden").asBoolean(), revenueNode.toString());
        assertFalse(yieldNode.has("valueHidden"), yieldNode.toString());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Account.class)
    @DisplayName("同一账号: /indicators 有没有销售额数值 ⇔ AI indicator_query 有没有")
    void sameConclusionAsAi(Account account) throws Exception {
        PriceFieldResponseAdvice priceView = priceViewFor(account.held);
        boolean web = call(web(priceView), get(BASE + "/SALES_MONTHLY_REVENUE/value")).get("data").hasNonNull("value");

        IndicatorQueryTool tool = new IndicatorQueryTool();
        IndicatorVersionRepository toolVersions = mock(IndicatorVersionRepository.class);
        when(toolVersions.findInWindow(any(), any(), any())).thenReturn(List.of());
        when(toolVersions.findFirstByIndicatorIdOrderByComputedAtDesc(any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(tool, "indicatorRepository", indicators);
        ReflectionTestUtils.setField(tool, "versionRepository", toolVersions);
        ReflectionTestUtils.setField(tool, "thresholdRepository", thresholds);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceView);
        JsonNode out = mapper.readTree(tool.execute(ToolCall.of("t1", "indicator_query",
                "{\"indicator_code\":\"SALES_MONTHLY_REVENUE\"}"), Map.of("factoryId", F, "userId", USER_ID)));
        boolean ai = out.get("data").get("data").has("currentValue");
        assertEquals(web, ai, account.name());
        assertEquals(account.sees, web, account.name());
    }

    @Test
    @DisplayName("没带登录用户 / 判定器没装配: 金额类数值与阈值不给 (fail closed)")
    void failsClosed() throws Exception {
        assertTrue(call(web(null), get(BASE + "/SALES_MONTHLY_REVENUE/value")).get("data").path("valueHidden").asBoolean());
        String body = web(priceViewFor(Account.PRICE_VIEW.held)).perform(get(BASE + "/SALES_MONTHLY_REVENUE/value"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(body).get("data");
        assertTrue(data.get("value").isNull() && data.path("valueHidden").asBoolean(), body);
    }
}

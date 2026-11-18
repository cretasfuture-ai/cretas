package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.workdesk.PriceHistoryQueryTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.security.PriceVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-18 Steve 实测两句答非所问:
 * 「哪些原料价格涨得最多？」→ 价格预警「未发现异常」; 「本月销售额是多少？」→ KPI 报告 (生产效率/OEE)。
 * 路由断言跑在真实短语表; 排名断言跑在真实工具入口 (含采购价权限)。
 */
class PurchasePriceRiseAndSalesAmountRoutingContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private IntentKnowledgeBase kb;

    @BeforeEach
    void init() {
        kb = new IntentKnowledgeBase();
        kb.initDefaults();
    }

    private String route(String q) {
        return kb.matchPhrase(q, "FACTORY").orElse(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"哪些原料价格涨得最多", "哪些原料价格涨得最多？", "哪些原料涨价最多", "原料价格涨幅排名", "哪个原料价格涨得最多"})
    void riseRankingQuestionsGoToPriceHistory_notThePriceAlert(String q) {
        assertEquals("PRICE_HISTORY_QUERY", route(q), q);
    }

    @ParameterizedTest
    @ValueSource(strings = {"本月销售额是多少", "本月销售额是多少？", "这个月销售额", "这个月销售额是多少", "本月销售额多少", "本月卖了多少钱"})
    void monthlySalesAmountGoesToSalesMarginSummary_notKpi(String q) {
        assertEquals("REPORT_SALES_MARGIN_SUMMARY", route(q), q);
    }

    @Test
    void negativeControls_neighbourIntentsKeepTheirRoutes() {
        // 找异常仍是价格预警 (不靠短语表抢): 不得被排名短语截走
        assertNotEquals("PRICE_HISTORY_QUERY", route("哪个供应商进价异常"));
        assertNotEquals("PRICE_HISTORY_QUERY", route("哪个供应商涨价了"));
        // KPI / 销售排名照旧
        assertEquals("REPORT_KPI", route("看下KPI"));
        assertEquals("REPORT_KPI", route("销售冠军"));
        assertEquals("REPORT_SALES_MARGIN_SUMMARY", route("本月销售额和毛利"));
    }

    // ---- 排名工具 (真实入口 execute) ----
    private PriceHistoryQueryTool tool(List<PurchaseOrder> orders, Map<String, List<PurchaseOrderItem>> items, boolean mayView) {
        PurchaseOrderRepository por = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository ir = mock(PurchaseOrderItemRepository.class);
        UserRepository ur = mock(UserRepository.class);
        PriceVisibility pv = mock(PriceVisibility.class);
        when(por.findByFactoryIdAndDateRange(eq("F1"), any(), any())).thenReturn(orders);
        for (var e : items.entrySet()) when(ir.findByPurchaseOrderId(e.getKey())).thenReturn(e.getValue());
        User u = new User();
        when(ur.findById(1L)).thenReturn(Optional.of(u));
        when(pv.canViewPurchasePrices(u)).thenReturn(mayView);
        PriceHistoryQueryTool t = new PriceHistoryQueryTool();
        ReflectionTestUtils.setField(t, "purchaseOrderRepository", por);
        ReflectionTestUtils.setField(t, "purchaseOrderItemRepository", ir);
        ReflectionTestUtils.setField(t, "userRepository", ur);
        ReflectionTestUtils.setField(t, "priceVisibility", pv);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        return t;
    }

    private static PurchaseOrder po(String id, int daysAgo) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id);
        p.setFactoryId("F1");
        p.setOrderDate(LocalDate.now().minusDays(daysAgo));
        return p;
    }

    private static PurchaseOrderItem item(String poId, String mat, String name, String price) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setPurchaseOrderId(poId);
        i.setMaterialTypeId(mat);
        i.setMaterialName(name);
        i.setUnit("kg");
        i.setQuantity(new BigDecimal("10"));
        i.setUnitPrice(new BigDecimal(price));
        return i;
    }

    private String run(PriceHistoryQueryTool t) throws Exception {
        JsonNode r = mapper.readTree(t.execute(ToolCall.of("t1", "price_history_query", "{}"),
                Map.of("factoryId", "F1", "userId", 1L)));
        return r.toString();
    }

    @Test
    void noMaterialGiven_ranksByRisePct_withBeforeAfterAndWindow() throws Exception {
        List<PurchaseOrder> orders = List.of(po("a", 60), po("b", 5), po("c", 55), po("d", 3));
        Map<String, List<PurchaseOrderItem>> items = Map.of(
                "a", List.of(item("a", "M1", "牛前腱", "10.00")),
                "b", List.of(item("b", "M1", "牛前腱", "12.00")),
                "c", List.of(item("c", "M2", "洋葱", "4.00")),
                "d", List.of(item("d", "M2", "洋葱", "5.00")));
        String out = run(tool(orders, items, true));
        assertTrue(out.contains("RISE_RANKING"), out);
        // 洋葱 4→5 = +25.0%, 牛前腱 10→12 = +20.0%, 洋葱排前
        assertTrue(out.indexOf("洋葱") < out.indexOf("牛前腱"), out);
        assertTrue(out.contains("25.0") && out.contains("20.0"), out);
        assertTrue(out.contains("近 30 天") && out.contains("此前 60 天"), out);
    }

    @Test
    void noComparableRecords_saysSo_noFakeRanking() throws Exception {
        // 只有近期一边有记录: 没法比, 不编
        String out = run(tool(List.of(po("b", 5)), Map.of("b", List.of(item("b", "M1", "牛前腱", "12.00"))), true));
        assertTrue(out.contains("没有足够的进价记录"), out);
        assertFalse(out.contains("risePct"), out);
    }

    @Test
    void callerWithoutPurchasePricePermission_getsNoPrices() throws Exception {
        List<PurchaseOrder> orders = List.of(po("a", 60), po("b", 5));
        Map<String, List<PurchaseOrderItem>> items = Map.of(
                "a", List.of(item("a", "M1", "牛前腱", "10.00")), "b", List.of(item("b", "M1", "牛前腱", "12.00")));
        String out = run(tool(orders, items, false));
        assertFalse(out.contains("RISE_RANKING") || out.contains("12.00"), out);
    }

    @Test
    void singleMaterialModeStillRequiresNothingExtra_schemaNoLongerForcesMaterialId() {
        PriceHistoryQueryTool t = new PriceHistoryQueryTool();
        assertTrue(((List<?>) t.getParametersSchema().get("required")).isEmpty());
    }

    // ---- LSMDEMO 追加 ----
    @ParameterizedTest
    @ValueSource(strings = {"哪个客户欠款最多", "哪个客户欠款最多？", "客户欠款排名", "谁欠款最多"})
    void ownedRankingGoesToReceivable(String q) {
        assertEquals("ACCOUNTS_RECEIVABLE_AGING", route(q), q);
    }

    @ParameterizedTest
    @ValueSource(strings = {"最近有哪些批次出成率异常", "哪些批次出成率异常", "出成率异常的批次"})
    void yieldAnomalyGoesToYieldRank(String q) {
        assertEquals("REPORT_BATCH_YIELD_RANK", route(q), q);
    }

    @ParameterizedTest
    @ValueSource(strings = {"11月1日到18日销售额是多少", "11月1日至11月18日销售额是多少", "从11月1号到18号销售额多少"})
    void datedSalesAmountPassesTheEarlyGate_toSalesMargin(String q) {
        var hit = kb.matchPhraseHit(q, "FACTORY");
        assertTrue(hit.isPresent(), q);
        assertEquals("REPORT_SALES_MARGIN_SUMMARY", hit.get().getValue(), q);
        assertTrue(com.cretas.aims.service.execution.PhraseShortcutEligibility
                .isUnconditionedSimple(q, hit.get().getKey(), hit.get().getValue()), q);
    }

    @Test
    void dateAllowanceIsNotAGeneralLoophole_otherConditionsStillBlock() {
        // 阴性对照: 带第二个子问仍交给决策层; 其它意图带日期也不放行
        String compound = "11月1日到18日销售额是多少，哪个客户买得最多";
        var hit = kb.matchPhraseHit(compound, "FACTORY").orElseThrow();
        assertFalse(com.cretas.aims.service.execution.PhraseShortcutEligibility
                .isUnconditionedSimple(compound, hit.getKey(), hit.getValue()));
        assertFalse(com.cretas.aims.service.execution.PhraseShortcutEligibility
                .isUnconditionedSimple("11月1日到18日库存查询", "库存查询", "INVENTORY_QUERY"));
    }

    @Test
    void missingIndicatorCodeQuestionDoesNotLeakInternalFieldNames() throws Exception {
        var m = com.cretas.aims.service.skill.impl.SkillExecutorImpl.class.getDeclaredMethod("clarificationQuestion", String.class);
        m.setAccessible(true);
        var exec = org.mockito.Mockito.mock(com.cretas.aims.service.skill.impl.SkillExecutorImpl.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        for (String p : new String[]{"indicator_code", "indicator_codes", "some_other_field"}) {
            String q = (String) m.invoke(exec, p);
            assertFalse(q.contains("indicator") || q.contains("_"), q);
        }
    }

    // ---- 经营分析页「往来余额」AI 解读: 按钮发出的原问句 (ChartInsights.vue 的拼法), 不得判成写意图 ----
    private static final String BALANCE_INTERPRET_QUERY =
            "[往来余额数据] 应收账款余额 2.23万；应付账款余额 288.87万；客户预收款 1.14万\n\n"
                    + "请用不超过3条bullet要点解读这张「往来余额」图的数据含义，并给出可执行的营销/运营建议。";

    @Test
    void balanceCardInterpretQuery_isNotTakenAsPaymentRecordWrite() {
        assertNotEquals("FINANCE_PAYMENT_RECORD", route(BALANCE_INTERPRET_QUERY), BALANCE_INTERPRET_QUERY);
        // 同一句不带数据块、只剩「客户预收款」名词语境, 也不命中收款写意图
        assertNotEquals("FINANCE_PAYMENT_RECORD", route("客户预收款是多少"));
        assertNotEquals("FINANCE_PAYMENT_RECORD", route("待收款有多少"));
    }

    @Test
    void realPaymentRecordRequests_stillWrite() {
        assertEquals("FINANCE_PAYMENT_RECORD", route("登记一笔收款"));
        assertEquals("FINANCE_PAYMENT_RECORD", route("录入收款"));
        // 审查 M2: 带「已 / 收到」的登记说法仍判写意图
        assertEquals("FINANCE_PAYMENT_RECORD", route("张三已收款 3000 元记一下"));
        assertEquals("FINANCE_PAYMENT_RECORD", route("收到预收款 1 万"));
    }
}

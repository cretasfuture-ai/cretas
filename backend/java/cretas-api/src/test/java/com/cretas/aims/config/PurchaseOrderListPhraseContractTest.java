package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采购订单的问题必须走采购订单查询, 不许落到销售订单列表 (S6 验收 2026-11-03, F006:
 * 「查询采购订单」「查询采购单」答成「538 个销售订单」)。
 */
class PurchaseOrderListPhraseContractTest {
    private static IntentKnowledgeBase knowledge;

    @BeforeAll
    static void initializeRealKnowledgeBase() {
        knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "查询采购订单", "查询采购单", "查看采购订单", "采购订单列表", "采购单查询",
            "查询收货中的采购订单", "查询已取消状态的采购单", "上个月的采购订单", "未审批的采购"})
    void purchaseOrderQuestionsRouteToThePurchaseOrderList(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("PURCHASE_ORDER_LIST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询销售订单", "订单列表"})
    void salesOrderQuestionsKeepTheSalesOrderList(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("ORDER_LIST");
    }

    /**
     * 不是「列采购单」的采购问题 (采购价格 / 采购了多少 / 需要采购 / 采购的猪肉到了没) 以前也短路到 ORDER_LIST,
     * 答的是销售订单 —— 错数据。现在不短路: 交给决策层或澄清出口, 只要求不再落到销售订单列表 (organizer 裁定 2026-11-03)。
     */
    @ParameterizedTest
    @ValueSource(strings = {"采购价格是多少", "更新采购价格", "这个月采购了多少", "还需要采购哪些原料", "采购的猪肉到了没"})
    void otherPurchaseQuestionsNoLongerShortCircuitToSalesOrders(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).isNotEqualTo(java.util.Optional.of("ORDER_LIST"));
    }

    /** 同因扫描: 短语表里不许再有任何指向 ORDER_LIST (只查销售订单) 的「采购」短语。 */
    @Test
    void noPurchaseOrderPhraseStillPointsAtTheSalesOrderList() {
        Map<String, String> phrases = knowledge.getPhraseToIntentMapping();
        assertThat(phrases.entrySet())
                .filteredOn(e -> "ORDER_LIST".equals(e.getValue()))
                .extracting(Map.Entry::getKey)
                .noneMatch(p -> p.contains("采购") || p.toLowerCase().contains("purchase"));
    }
}

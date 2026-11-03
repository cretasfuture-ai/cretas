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

    /** 同因扫描: 短语表里不许再有「指向 ORDER_LIST 的采购订单 / 采购单」短语。 */
    @Test
    void noPurchaseOrderPhraseStillPointsAtTheSalesOrderList() {
        Map<String, String> phrases = knowledge.getPhraseToIntentMapping();
        assertThat(phrases.entrySet())
                .filteredOn(e -> "ORDER_LIST".equals(e.getValue()))
                .extracting(Map.Entry::getKey)
                .noneMatch(p -> p.contains("采购订单") || p.contains("采购单") || p.toLowerCase().contains("purchase"));
    }
}

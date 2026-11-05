package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentKnowledgeBaseFactoryPhraseContractTest {
    private IntentKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new IntentKnowledgeBase();
        knowledgeBase.initDefaults();
    }

    @Test
    void preservesFactoryPhraseRouting() {
        assertEquals(Optional.of("REPORT_TRENDS"),
                knowledgeBase.matchPhrase("这周产量环比怎么样", "FACTORY"));
        assertEquals(Optional.of("COST_TREND_ANALYSIS"),
                knowledgeBase.matchPhrase("成本分析", "FACTORY"));
    }

    @Test
    void retiredRestaurantPhraseMappingDoesNotEnterMatcher() {
        assertFalse(knowledgeBase.matchPhrase("爆款", "RESTAURANT").isPresent());
        assertFalse(knowledgeBase.matchPhrase("菜品列表", "RESTAURANT").isPresent());
        assertFalse(knowledgeBase.getPhraseToIntentMapping().containsKey("餐饮管理"));
        assertFalse(knowledgeBase.getPhraseToIntentMapping().containsKey("后厨几个人"));
        for (String phrase : java.util.List.of("点菜", "加菜", "退菜")) {
            assertEquals(IntentKnowledgeBase.ActionType.UNKNOWN, knowledgeBase.detectActionType(phrase));
        }
        assertEquals(Optional.of("FOOD_KNOWLEDGE_QUERY"), knowledgeBase.matchPhrase("中央厨房", "FACTORY"));
    }
}

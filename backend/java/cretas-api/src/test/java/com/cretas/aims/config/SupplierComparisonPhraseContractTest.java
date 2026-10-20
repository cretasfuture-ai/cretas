package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierComparisonPhraseContractTest {
    private static IntentKnowledgeBase knowledge;

    @BeforeAll
    static void initializeRealKnowledgeBase() {
        knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "供应商价格比较：测试原料哪家供应商最低价？请注明报价来源；没有真实报价请明确不可比价。",
            "各供应商价格对比",
            "供应商报价比较",
            "请比较现有供应商的报价；没有物料报价时说明不能比价"
    })
    void priceQuestionsSelectComparisonRatherThanAdministratorRanking(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY"))
                .contains("SUPPLIER_PRICE_COMPARISON");
        assertThat(knowledge.matchSpecificPhrase(input, "FACTORY"))
                .contains("SUPPLIER_PRICE_COMPARISON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"供应商排名", "供应商评分", "哪个供应商交货最准时"})
    void supplierPerformanceQuestionsKeepOriginalRankingRoute(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY"))
                .isEqualTo(Optional.of("SUPPLIER_RANKING"));
    }
}

package com.cretas.aims.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionTrendRouteContractTest {
    @Test
    void explicitProductionComparisonsUseTrendReportAndVagueQueryKeepsProductionRoute() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        for (String query : new String[]{
                "这周产量环比怎么样", "这个月产量同比",
                "这周产量比上周怎么样", "这个月产量比去年"}) {
            assertThat(kb.matchPhrase(query, "FACTORY")).as(query).contains("REPORT_TRENDS");
        }
        assertThat(kb.matchPhrase("产量怎么样", "FACTORY")).contains("REPORT_PRODUCTION");
    }
}

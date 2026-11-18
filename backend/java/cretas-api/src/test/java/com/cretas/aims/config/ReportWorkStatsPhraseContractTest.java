package com.cretas.aims.config;

import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 线上 probe report_work_stats: 「报工产量统计」类整句是只读统计, 不能落到决策层被判成写意图 WORK_PROCESS_TASK_COMPLETE。 */
class ReportWorkStatsPhraseContractTest {

    private final IntentKnowledgeBase kb = new IntentKnowledgeBase();

    ReportWorkStatsPhraseContractTest() {
        kb.initDefaults();
    }

    @Test
    void wholeSentenceStatsPhrasesGoToReadOnlyProductionReportAndPassEligibility() {
        for (String text : new String[]{"报工产量统计", "报工统计", "报工汇总"}) {
            Optional<Map.Entry<String, String>> hit = kb.matchPhraseHit(text, null);
            assertThat(hit).as(text).isPresent();
            assertThat(hit.get().getValue()).as(text).isEqualTo("REPORT_PRODUCTION");
            assertThat(PhraseShortcutEligibility.isUnconditionedSimple(text, hit.get().getKey()))
                    .as("整句短语应放行快捷: " + text).isTrue();
        }
    }

    @Test
    void reportingWorkWriteSentencesStayWriteIntent() {
        // 阴性对照: 真的要报工仍是写意图, 不能被修成全进查询
        assertThat(kb.matchPhrase("我要报工", null)).contains("WORK_PROCESS_TASK_COMPLETE");
        assertThat(kb.matchPhrase("报工产量", null)).contains("WORK_PROCESS_TASK_COMPLETE");
        assertThat(kb.matchPhrase("报工 100 件", null)).doesNotContain("REPORT_PRODUCTION");
    }
}

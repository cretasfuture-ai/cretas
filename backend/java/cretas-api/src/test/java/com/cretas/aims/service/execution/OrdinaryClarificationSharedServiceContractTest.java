package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Caller forwarding and fail-closed presentation, not real-model quality. */
class OrdinaryClarificationSharedServiceContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualEntryForwardsTheSameCallerAndModeToTheSharedService(boolean streaming) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var entry=new SalesRankingEntryParityContractTest.Entry(h,streaming);
            var match=IntentMatchResult.builder().bestMatch(h.intent).confidence(.55).requiresConfirmation(true)
                    .matchMethod(IntentMatchResult.MatchMethod.LLM).build();
            when(entry.intents.recognizeIntentWithConfidence(anyString(),eq("F006"),anyInt(),any(),anyString(),any(),any(),any(),anyList(),anyMap()))
                    .thenReturn(match);
            var service=mock(ClarificationCandidateService.class);
            when(service.buildCandidateActions(eq(match),eq("F006"),eq(7L),eq("sales_manager"),eq("READ")))
                    .thenReturn(List.of(IntentExecuteResponse.SuggestedAction.builder().actionCode("REPHRASE").actionName("重新描述").build()));
            ReflectionTestUtils.setField(entry.ordinary,"clarificationCandidateService",service);
            ReflectionTestUtils.setField(entry.sse,"clarificationCandidateService",service);
            var response=entry.run("查询当前业务目标",false);
            assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
            verify(service).buildCandidateActions(match,"F006",7L,"sales_manager","READ");
        }
    }

    @Test void missingSharedServiceCannotFallBackToLegacyBusinessSuggestions() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var entry=new SalesRankingEntryParityContractTest.Entry(h,false);
            var match=IntentMatchResult.builder().bestMatch(h.intent).confidence(.55)
                    .clarificationQuestion("请重新描述查询目标").requiresConfirmation(true).build();
            var response=entry.ordinary.buildClarificationResponse(match,"F006",7L,"sales_manager","READ");
            assertThat(response.getSuggestedActions()).extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                    .containsExactly("REPHRASE");
        }
    }
}

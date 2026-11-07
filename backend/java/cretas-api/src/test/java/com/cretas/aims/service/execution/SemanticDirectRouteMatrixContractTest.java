package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** State/ownership admission matrix, not simulated-model accuracy or direct-route efficacy. */
class SemanticDirectRouteMatrixContractTest {
    static Stream<Arguments> statesAndInputs() {
        return Stream.of("NEW", "ACTIVE", "COMPLETED", "EXPIRED", "UNKNOWN", "PAGE", "FOREIGN_USER", "FOREIGN_FACTORY")
                .flatMap(state -> Stream.of("查看详情", "上个月呢", "第一名库存呢", "取消").map(input -> Arguments.of(state,input)));
    }

    @ParameterizedTest @MethodSource("statesAndInputs")
    void sameTextCannotTurnRelatedOrUnavailableStateIntoStandalone(String state, String input) {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var request = IntentExecuteRequest.builder().sessionId("matrix-session").userInput(input).build();
            var owner = ConversationContext.builder().factoryId("F006").userId(7L).sessionId("matrix-session")
                    .lastActiveAt(LocalDateTime.now(SalesRankingSemanticContractTest.CLOCK)).build();
            if ("ACTIVE".equals(state) || "COMPLETED".equals(state)) owner.setSlot(EntitySlot.SlotType.READ_QUERY,
                    EntitySlot.builder().id("ORDER_LIST").metadata(Map.of("taskStatus",state,"status","CONFIRMED")).build());
            if ("EXPIRED".equals(state)) owner.setLastActiveAt(LocalDateTime.now(SalesRankingSemanticContractTest.CLOCK).minusMinutes(61));
            if ("FOREIGN_USER".equals(state)) owner.setUserId(99L);
            if ("FOREIGN_FACTORY".equals(state)) owner.setFactoryId("F999");
            if ("PAGE".equals(state)) { request.setEntityType("ORDER"); request.setEntityId("ORDER-PRIVATE"); }
            if ("UNKNOWN".equals(state)) when(h.memory.getContext("matrix-session")).thenThrow(new IllegalStateException("unavailable"));
            else when(h.memory.getContext("matrix-session")).thenReturn(owner);
            new SemanticConversationContextService(h.memory,SalesRankingSemanticContractTest.CLOCK).prepare("F006",7L,request);
            // NEW only passes the state gate; reviewed route suitability is a separate registry gate.
            assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isEqualTo("NEW".equals(state));
            var denial = SemanticConversationContextService.admissionFailure(request);
            if (state.startsWith("FOREIGN_")) assertThat(denial.getStatus()).isEqualTo("NO_PERMISSION");
            else if ("EXPIRED".equals(state) || "UNKNOWN".equals(state)) assertThat(denial.getStatus()).isEqualTo("NEED_MORE_INFO");
            else assertThat(denial).isNull();
            assertThat(request.getSemanticContext().toString()).doesNotContain("ORDER-PRIVATE");
            verifyNoInteractions(h.ranking);
        }
    }
}

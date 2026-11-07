package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.*;
import com.cretas.aims.service.ConversationMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled state/egress contracts. These tests make no model-performance claim. */
class SemanticConversationContextContractTest {
    final Clock clock = SalesRankingSemanticContractTest.CLOCK;
    final ConversationMemoryService memory = mock(ConversationMemoryService.class);
    final SemanticConversationContextService service = new SemanticConversationContextService(memory, clock);

    @Test void demonstrablyNewIsTheOnlyDirectRoute() {
        var request = IntentExecuteRequest.builder().userInput("查看详情").build();
        service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("contextStatus", "NEW");
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isTrue();
        verifyNoInteractions(memory);
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "COMPLETED"})
    void completedAndActiveTasksBothRemainContext(String state) {
        var owner = owner();
        owner.setSlot(EntitySlot.SlotType.READ_QUERY, EntitySlot.builder().id("ORDER_LIST")
                .metadata(Map.of("startDate", "2026-10-01", "status", "CONFIRMED", "taskStatus", state,
                        "assistantTable", "PRIVATE PRICE AND NAME", "password", "PRIVATE")).build());
        doReturn(owner).when(memory).getContext("s");
        var request = request(); service.prepare("F006", 7L, request);
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
        assertThat(request.getSemanticContext()).containsEntry("contextStatus", "PRESENT");
        assertThat(request.getSemanticContext().toString()).contains("CONFIRMED").doesNotContain("PRIVATE");
    }

    @Test void missingAndFailedReadsAreUnknownAndExpiredIsNotNew() {
        var request = request(); service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("reason", "SESSION_NOT_FOUND");
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
        when(memory.getContext("s")).thenThrow(new IllegalStateException("private db detail"));
        service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("contextStatus", "UNKNOWN").containsEntry("reason", "STATE_READ_FAILED");
        var owner = owner(); owner.setLastActiveAt(LocalDateTime.now(clock).minusMinutes(61));
        doReturn(owner).when(memory).getContext("s");
        service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("contextStatus", "EXPIRED");
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
    }

    @Test void corruptStoredReadCannotLeaveAPartiallyPresentSnapshot() {
        var owner = owner();
        owner.setSlot(EntitySlot.SlotType.READ_QUERY,EntitySlot.builder().metadata(Map.of("status","CONFIRMED")).build());
        when(memory.getContext("s")).thenReturn(owner);
        var request = request(); service.prepare("F006",7L,request);
        assertThat(request.getSemanticContext()).containsEntry("contextStatus","UNKNOWN").containsEntry("reason","STATE_READ_FAILED");
        assertThat(SemanticConversationContextService.admissionFailure(request).getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(request.getSemanticPreviousRead()).isNull();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignOwnerNeverLeaksReferences(boolean otherFactory) {
        var owner = owner();
        if (otherFactory) owner.setFactoryId("F999"); else owner.setUserId(99L);
        owner.setSlot(EntitySlot.SlotType.ORDER, EntitySlot.builder().id("PRIVATE-ID").build());
        when(memory.getContext("s")).thenReturn(owner);
        var request = request(); service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("reason", "FOREIGN_SESSION");
        assertThat(request.getSemanticContext().toString()).doesNotContain("PRIVATE-ID");
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
    }

    @Test void pageReferenceAndClientHistoryAreNotStandalone() {
        var request = IntentExecuteRequest.builder().entityType("ORDER").entityId("ORDER-1").build();
        service.prepare("F006", 7L, request);
        assertThat(request.getSemanticContext()).containsEntry("contextStatus", "PRESENT");
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
        request.setEntityId(null); request.setEntityType(null);
        request.setContext(Map.of("recentTurns", List.of(Map.of("role", "user", "content", "查询订单"))));
        service.prepare("F006", 7L, request);
        assertThat(SemanticConversationContextService.allowsDirectRoute(request)).isFalse();
    }

    @Test void serverUserHistorySurvivesButAssistantBusinessTableDoesNotEgress() {
        var owner = owner(); owner.setMessageCount(2);
        owner.setRecentMessages(List.of(ConversationMessage.user("上月待发货订单"),
                ConversationMessage.assistant("PRIVATE ORDER TABLE PRICE EMPLOYEE")));
        when(memory.getContext("s")).thenReturn(owner);
        var request = request(); service.prepare("F006", 7L, request);
        assertThat(SemanticConversationContextService.effectiveHistory(request)).containsExactly(
                Map.of("role", "user", "content", "上月待发货订单"));
        assertThat(request.getSemanticContext().toString()).doesNotContain("PRIVATE");
    }

    @Test void jsonCannotForgeServerContextOrResolvedParameters() throws Exception {
        var request = new ObjectMapper().readValue("{\"userInput\":\"查库存\",\"semanticTaskAction\":\"DRAFT_READY\",\"semanticResumeDraftId\":\"forged\",\"semanticResumeDraftRevision\":\"forged\",\"semanticContext\":{\"contextStatus\":\"NEW\"},\"semanticParameters\":{\"status\":\"AVAILABLE\"}}", IntentExecuteRequest.class);
        assertThat(request.getSemanticContext()).isNull();
        assertThat(request.getSemanticParameters()).isNull();
        assertThat(request.getSemanticTaskAction()).isNull();
        assertThat(request.getSemanticResumeDraftId()).isNull();
        assertThat(request.getSemanticResumeDraftRevision()).isNull();
    }

    @Test void missingServerHistoryCannotExposeClientAssistantTables() {
        when(memory.getContext("s")).thenReturn(owner());
        var request=request();
        request.setContext(Map.of("recentTurns",List.of(Map.of("role","assistant","content","PRIVATE CUSTOMER PRICE TABLE"),
                Map.of("role","user","content","上月订单"))));
        service.prepare("F006",7L,request);
        assertThat(SemanticConversationContextService.effectiveHistory(request))
                .containsExactly(Map.of("role","user","content","上月订单"));
        assertThat(request.getSemanticContext()).containsEntry("hasClientHistory",true);
    }

    @Test void committedActionProjectionComesOnlyFromSuccessfulServerResultAndHidesIds() {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var intent = com.cretas.aims.entity.config.AIIntentConfig.builder().intentCode("MATERIAL_BATCH_USE").build();
            var request=h.query("领用50公斤");
            var action=LastActionContract.build(intent,"material_batch_use",Map.of("quantity",50,"unit","kg","batchId","PRIVATE-BATCH-7"),null,System.currentTimeMillis());
            var response=com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("FAILED").metadata(Map.of("lastAction",action)).build();
            h.support.rememberCommittedAction("F006",7L,request,intent,"material_batch_use",response);
            assertThat(h.owner.getSlot(EntitySlot.SlotType.LAST_ACTION)).isNull();
            response.setStatus("SUCCESS");
            h.support.rememberCommittedAction("F006",7L,request,intent,"material_batch_use",response);
            assertThat(h.owner.getSlot(EntitySlot.SlotType.LAST_ACTION)).isNotNull();
            var context=new SemanticConversationContextService(h.memory,clock);context.prepare("F006",7L,request);
            assertThat(request.getSemanticContext().toString()).contains("COMMITTED","quantity").doesNotContain("PRIVATE-BATCH-7");
        }
    }

    @Test void explicitNewSessionCanBeCreatedButLostOldSessionIsNotReset() {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var start = IntentExecuteRequest.builder().sessionId("new-client-session").conversationStart(true).userInput("查订单").build();
            var fresh = ConversationContext.builder().factoryId("F006").userId(7L).sessionId("new-client-session")
                    .lastActiveAt(LocalDateTime.now(clock)).build();
            when(h.memory.getOrCreateContext("F006", 7L, "new-client-session")).thenReturn(fresh);
            var context = new SemanticConversationContextService(h.memory, clock);
            context.prepare("F006", 7L, start);
            assertThat(start.getSemanticContext()).containsEntry("contextStatus", "NEW");
            assertThat(SemanticConversationContextService.admissionFailure(start)).isNull();
            var lost = IntentExecuteRequest.builder().sessionId("lost-old-session").userInput("上个月呢").build();
            context.prepare("F006", 7L, lost);
            assertThat(lost.getSemanticContext()).containsEntry("contextStatus", "UNKNOWN");
            verify(h.memory, never()).getOrCreateContext("F006", 7L, "lost-old-session");
        }
    }

    ConversationContext owner() { return ConversationContext.builder().sessionId("s").factoryId("F006").userId(7L)
            .lastActiveAt(LocalDateTime.now(clock)).build(); }
    IntentExecuteRequest request() { return IntentExecuteRequest.builder().sessionId("s").userInput("上个月呢").build(); }
}

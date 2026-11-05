package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Entry wiring proof only: a terminal repair must precede recognition and Tool execution. */
class SemanticTaskEntryRepairContractTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,CANCEL","true,CANCEL","false,CORRECT","true,CORRECT"})
    void currentReadSurvivesRepairAndBothEntryContextRefreshes(boolean streaming,String action) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var entry=new SalesRankingEntryParityContractTest.Entry(h,streaming);
            var slot=com.cretas.aims.dto.conversation.EntitySlot.builder().id("ORDER_LIST")
                    .mentionedAt(java.time.LocalDateTime.now(SalesRankingSemanticContractTest.CLOCK))
                    .metadata(java.util.Map.of("status","CONFIRMED","startDate","2026-10-01","endDate","2026-10-31",
                            "taskStatus","COMPLETED",SalesRankingConversationSupport.TURN_KEY,"read-before")).build();
            h.owner.setSlot(com.cretas.aims.dto.conversation.EntitySlot.SlotType.READ_QUERY,slot);
            if("CANCEL".equals(action)) {
                var paused=com.cretas.aims.entity.conversation.ConversationSession.builder()
                        .sessionId("unrelated-paused-draft").factoryId("F006").userId(7L).knownIntentCode("MATERIAL_BATCH_USE")
                        .sessionMode(com.cretas.aims.entity.conversation.ConversationSession.SessionMode.PARAMETER_COLLECTION)
                        .status(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.PAUSED)
                        .lastActiveAt(java.time.LocalDateTime.now()).timeoutMinutes(10).build();
                when(h.conversations.getPausedSessions("F006",7L)).thenReturn(java.util.List.of(paused));
            } else {
                h.owner.setSlot(com.cretas.aims.dto.conversation.EntitySlot.SlotType.LAST_ACTION,
                        com.cretas.aims.dto.conversation.EntitySlot.builder().id("MATERIAL_BATCH_USE")
                                .metadata(java.util.Map.of("status","COMMITTED","toolName","material_batch_use")).build());
            }
            var llm=mock(com.cretas.aims.ai.client.DashScopeClient.class);
            var args=h.mapper.writeValueAsString(java.util.Map.of("taskAction",action,"taskReference","current-read"));
            var answer=h.mapper.convertValue(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("message",java.util.Map.of(
                    "tool_calls",java.util.List.of(java.util.Map.of("id","controlled-repair","type","function","function",
                            java.util.Map.of("name","conversation_task_repair","arguments",args))))))),
                    com.cretas.aims.ai.dto.ChatCompletionResponse.class);
            when(llm.isAvailable()).thenReturn(true);when(llm.chatWithTools(anyString(),anyString(),anyList())).thenReturn(answer);
            when(llm.hasToolCalls(answer)).thenReturn(true);
            when(llm.getFirstToolCall(answer)).thenReturn(answer.getChoices().get(0).getMessage().getToolCalls().get(0));
            var repair=new ConversationTaskRepairSupport(h.conversations,llm,mock(com.cretas.aims.service.PreviewTokenService.class));
            ReflectionTestUtils.setField(entry.ordinary,"conversationTaskRepairSupport",repair);
            ReflectionTestUtils.setField(entry.sse,"conversationTaskRepairSupport",repair);
            var intent="CORRECT".equals(action)?entry.read("order_list","ORDER_LIST"):null;
            var response=entry.semantic("CORRECT".equals(action)?"状态改成PROCESSING，日期不变":"不用查了",intent,action,
                    "CORRECT".equals(action)?java.util.Map.of("status","PROCESSING"):java.util.Map.of());
            assertThat(entry.lastRequest.getSessionId()).isEqualTo("sales-session");
            assertThat(entry.lastRequest.getSemanticPreviousRead()).containsEntry("id","ORDER_LIST")
                    .containsEntry(SalesRankingConversationSupport.TURN_KEY,"read-before");
            assertThat(((java.util.Map<?,?>)entry.lastRequest.getSemanticPreviousRead().get("parameters")).get("status"))
                    .isEqualTo("CONFIRMED");
            if("CANCEL".equals(action)) {
                assertThat(response.getStatus()).isEqualTo("CANCELLED");
                assertThat(h.owner.getSlot(com.cretas.aims.dto.conversation.EntitySlot.SlotType.READ_QUERY).getId()).isEqualTo("CLEARED");
                verify(h.conversations,never()).cancelConversation(eq("F006"),eq(7L),eq("unrelated-paused-draft"),anyBoolean());
            } else {
                assertThat(response.getStatus()).isEqualTo("SUCCESS");
                assertThat(entry.lastRequest.getSemanticParameters()).containsEntry("status","PROCESSING")
                        .containsEntry("startDate","2026-10-01").containsEntry("endDate","2026-10-31");
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true","true,true","false,false","true,false"})
    void staleSlotReplyCannotFallThroughToFreshRecognition(boolean streaming, boolean selectedContinue) throws Exception {
        try (var h=new SalesRankingSemanticContractTest.Harness()) {
            var entry=new SalesRankingEntryParityContractTest.Entry(h,streaming);
            var repair=mock(ConversationTaskRepairSupport.class);
            when(repair.prepare(eq("F006"),eq(7L),any())).thenAnswer(c->{
                if(selectedContinue) ((com.cretas.aims.dto.ai.IntentExecuteRequest)c.getArgument(2)).setSemanticTaskAction("CONTINUE");return null;
            });
            ReflectionTestUtils.setField(entry.ordinary,"conversationTaskRepairSupport",repair);
            ReflectionTestUtils.setField(entry.sse,"conversationTaskRepairSupport",repair);
            h.owner.setSlot(com.cretas.aims.dto.conversation.EntitySlot.SlotType.READ_QUERY,
                    com.cretas.aims.dto.conversation.EntitySlot.builder().id("ORDER_LIST").metadata(java.util.Map.of("taskStatus","ACTIVE")).build());
            var pending=com.cretas.aims.entity.conversation.ConversationSession.builder().sessionId("sales-session")
                    .factoryId("F006").userId(7L).knownIntentCode(h.intent.getIntentCode())
                    .sessionMode(com.cretas.aims.entity.conversation.ConversationSession.SessionMode.PARAMETER_COLLECTION)
                    .status(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE)
                    .lastActiveAt(java.time.LocalDateTime.now()).timeoutMinutes(10).build();
            pending.setRequiredParameters(java.util.List.of(
                    com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter.builder()
                            .name("limit").type("INTEGER").collected(false).build()));
            when(h.conversations.getSession("F006",7L,"sales-session")).thenReturn(java.util.Optional.of(pending));
            when(h.conversations.getActiveSession("F006",7L)).thenReturn(java.util.Optional.of(pending));
            when(h.conversations.continueConversation("F006",7L,"sales-session","20"))
                    .thenReturn(com.cretas.aims.service.ConversationService.ConversationResponse.builder()
                            .taskChanged(true).status(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE).build());
            var response=entry.run("20",false);
            assertThat(response.getStatus()).isEqualTo("TASK_CHANGED");
            verifyNoInteractions(h.ranking);
            verify(entry.intents,never()).recognizeIntentWithConfidence(anyString(),anyString(),anyInt(),any(),anyString(),
                    any(),any(),any(),anyList(),anyMap());
        }
    }

    @org.junit.jupiter.api.Test
    void lateDraftReadyCannotFallBackToAnUnboundPreviewTicket() {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var tickets=mock(com.cretas.aims.service.PreviewTokenService.class);
            ReflectionTestUtils.setField(h.dispatch,"previewTokenService",tickets);
            ReflectionTestUtils.setField(h.dispatch,"conversationService",h.conversations);
            var request=h.query("核对草稿");request.setSemanticTaskAction("DRAFT_READY");
            var response=h.dispatch.executeToolPreview(h.tool,"F006",request,h.intent,7L,"sales_manager");
            assertThat(response.getStatus()).isEqualTo("TASK_CHANGED");
            verifyNoInteractions(tickets,h.ranking);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @org.junit.jupiter.api.Test
    void activeDraftCannotMintAConfirmationFromCallerParameters() {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var tickets=mock(com.cretas.aims.service.PreviewTokenService.class);
            ReflectionTestUtils.setField(h.dispatch,"previewTokenService",tickets);
            ReflectionTestUtils.setField(h.dispatch,"conversationService",h.conversations);
            var draft=com.cretas.aims.entity.conversation.ConversationSession.builder().sessionId("sales-session")
                    .factoryId("F006").userId(7L).knownIntentCode(h.intent.getIntentCode())
                    .status(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE).build();
            when(h.conversations.getSession("F006",7L,"sales-session")).thenReturn(java.util.Optional.of(draft));
            var response=h.dispatch.executeToolPreview(h.tool,"F006",h.query("核对草稿"),h.intent,7L,"sales_manager");
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
            verifyNoInteractions(tickets,h.ranking);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void terminalRepairIsIdenticalForOrdinaryAndStreaming(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new SalesRankingEntryParityContractTest.Entry(h,streaming);
            var repair = mock(ConversationTaskRepairSupport.class);
            when(repair.prepare(eq("F006"),eq(7L),any())).thenReturn(IntentExecuteResponse.builder()
                    .status("NEED_MORE_INFO").message("请明确恢复哪个暂停任务").build());
            ReflectionTestUtils.setField(entry.ordinary,"conversationTaskRepairSupport",repair);
            ReflectionTestUtils.setField(entry.sse,"conversationTaskRepairSupport",repair);
            clearInvocations(entry.intents,h.ranking);
            var response = entry.run("继续",false);
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
            verify(repair).prepare(eq("F006"),eq(7L),same(entry.lastRequest));
            verifyNoInteractions(h.ranking);
            verify(entry.intents,never()).recognizeIntentWithConfidence(anyString(),anyString(),anyInt(),
                    any(),anyString(),any(),any(),any(),anyList(),anyMap());
        }
    }
}

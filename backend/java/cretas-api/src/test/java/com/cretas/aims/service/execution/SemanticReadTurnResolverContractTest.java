package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.dto.conversation.*;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual parameter/state merge with simulated transport output; NOT real LLM acceptance. */
class SemanticReadTurnResolverContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void resolverAdvertisesOnlyReferencesSupportedByTheCurrentTool(boolean detail) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            previous(h,"ORDER_LIST",Map.of("toolName","order_list","taskStatus","COMPLETED",
                    "resultReferences",List.of(Map.of("reference","order-result-1","entityType","ORDER","id","actual-order-1",
                            "ordinal",1,"ranked",false))));
            var request=request(h,"查看刚才结果");
            var client=model(h,"CLARIFY",Map.of());
            ToolExecutor tool=detail ? new com.cretas.aims.ai.tool.impl.crm.OrderQueryTool(mock(SalesService.class),
                    mock(com.cretas.aims.repository.inventory.SalesOrderRepository.class)) : new OrderListTool(mock(SalesService.class));
            h.dispatch.resolveReadTurn("F006",7L,request,bind(h,tool,detail ? "ORDER_STATUS" : "ORDER_LIST"));
            var capture=org.mockito.ArgumentCaptor.forClass(List.class);
            verify(client).chatWithTools(anyString(),anyString(),capture.capture());
            var function=(com.cretas.aims.ai.dto.Tool)capture.getValue().get(0);
            var fields=(Map<?,?>)function.getFunction().getParameters().get("properties");
            if(detail) assertThat(((Map<?,?>)fields.get("objectReference")).get("enum"))
                    .isEqualTo(List.of("order-result-1"));
            else assertThat(fields.containsKey("objectReference")).isFalse();
            assertThat(fields.containsKey("selectionOrdinal")).isFalse();
            assertThat(fields.containsKey("selectionEvidence")).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings={"CONTINUE","CORRECT"})
    void copiedStringDefaultCannotReplaceConfirmedQueryScope(String action) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            previous(h,"ORDER_STATUS",Map.of("queryType","ORDER_TODAY","toolName","order_query"));
            var request=request(h,"其他不变");model(h,action,Map.of("queryType","ORDER_STATUS","page",1,"size",20));
            var tool=new com.cretas.aims.ai.tool.impl.crm.OrderQueryTool(mock(SalesService.class),
                    mock(com.cretas.aims.repository.inventory.SalesOrderRepository.class));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,bind(h,tool,"ORDER_STATUS")).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
            assertThat(h.owner.getSlot(EntitySlot.SlotType.READ_QUERY).getMetadata()).containsEntry("queryType","ORDER_TODAY");
        }
    }

    @Test void explicitStringDefaultCorrectionStillWins() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            previous(h,"ORDER_STATUS",Map.of("queryType","ORDER_TODAY","toolName","order_query"));
            var request=request(h,"queryType改成ORDER_STATUS");model(h,"CORRECT",Map.of("queryType","ORDER_STATUS","page",1,"size",20));
            var tool=new com.cretas.aims.ai.tool.impl.crm.OrderQueryTool(mock(SalesService.class),
                    mock(com.cretas.aims.repository.inventory.SalesOrderRepository.class));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,bind(h,tool,"ORDER_STATUS"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("queryType","ORDER_STATUS");
            assertThat(request.getSemanticParameterSources()).containsEntry("queryType","CURRENT_EXPLICIT");
        }
    }

    @Test void schemaPaginationDefaultsRemainOwnedByTheActualOrderTool() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var sales=mock(SalesService.class);
            when(sales.getSalesOrders("F006",1,20)).thenReturn(com.cretas.aims.dto.common.PageResponse.of(
                    List.<com.cretas.aims.entity.inventory.SalesOrder>of(),1,20,0L));
            var tool=new OrderListTool(sales);ReflectionTestUtils.setField(tool,"objectMapper",h.mapper);
            var request=request(h,"列出所有销售订单");model(h,"NEW",Map.of("page",1,"size",20));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,bind(h,tool,"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).isEmpty();
            var result=tool.execute(com.cretas.aims.ai.dto.ToolCall.of("defaults","order_list",
                    h.mapper.writeValueAsString(request.getSemanticParameters())),Map.of("factoryId","F006","userId",7L));
            assertThat(h.mapper.readTree(result).path("success").asBoolean()).isTrue();
            verify(sales).getSalesOrders("F006",1,20);
        }
    }

    @Test void modelDefaultDatesAreNotMistakenForAnExplicitUnchangedDateCorrection() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=request(h,"看看销售排行");
            model(h,"NEW",Map.of("startDate","2026-11-01","endDate","2026-11-05","limit",20),"UNCHANGED");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build())).isNull();
            assertThat(request.getSemanticParameters()).isEmpty();
            Map<String,Object> actual=new LinkedHashMap<>(request.getSemanticParameters());
            h.support.applyCurrentParameters(request,actual);
            assertThat(actual).containsEntry("startDate","2026-11-01").containsEntry("endDate","2026-11-05")
                    .containsEntry("limit",20).containsEntry("metric","amount");
        }
    }

    @Test void unknownPaginationGuessIsRejectedRatherThanTreatedAsADefault() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=request(h,"列出所有销售订单");model(h,"NEW",Map.of("page",4));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @Test void repeatedUngroundedNumericDefaultIsAppliedOnlyAfterSemanticMerge() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var request = request(h, "看看销售排行");
            model(h, "NEW", Map.of("limit", 20));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request,
                    IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build())).isNull();
            assertThat(request.getSemanticParameters()).doesNotContainKey("limit");
            assertThat(request.getSemanticParameterSources()).doesNotContainKey("limit");
            Map<String,Object> execution = new LinkedHashMap<>(request.getSemanticParameters());
            h.support.applyCurrentParameters(request, execution);
            assertThat(execution).containsEntry("limit",20).containsEntry("metric","amount")
                    .containsEntry("startDate","2026-11-01").containsEntry("endDate","2026-11-05");
        }
    }

    @Test void ungroundedDefaultCannotReplacePreviouslyConfirmedLimit() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, SalesRankingParameters.INTENT, Map.of("limit",7,"metric","amount"));
            var request = request(h, "时间改成本月，其他不变");
            model(h, "CORRECT", Map.of("startDate","2026-11-01","endDate","2026-11-30","limit",20));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request,
                    IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build())).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("limit",7);
            assertThat(request.getSemanticParameterSources()).containsEntry("limit","PREVIOUS_VALUE");
        }
    }

    @Test void filledFieldsCanBeCorrectedAndUnchangedCompatibleFiltersSurvive() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "CONFIRMED"));
            var request = request(h, "时间改成本月，状态不变");
            var match = bind(h, new OrderListTool(mock(SalesService.class)), "ORDER_LIST");
            model(h, "CORRECT", Map.of("startDate", "2026-11-01", "endDate", "2026-11-30"));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, match)).isNull();
            assertThat(request.getSemanticParameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "startDate", "2026-11-01", "endDate", "2026-11-30", "status", "CONFIRMED"));
            assertThat(request.getSemanticParameterSources()).containsEntry("startDate", "CURRENT_EXPLICIT").containsEntry("status", "PREVIOUS_VALUE");
            verifyNoInteractions(h.ranking);
        }
    }

    @Test void switchToMaterialQueryDoesNotInheritOrderDatesOrStatus() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "CONFIRMED"));
            var request = request(h, "查今天入库的原料");
            var match = bind(h, new MaterialBatchQueryTool(), "MATERIAL_BATCH_QUERY");
            model(h, "SWITCH", Map.of("startDate", "2026-11-05", "endDate", "2026-11-05"));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, match)).isNull();
            assertThat(request.getSemanticParameters()).containsExactlyInAnyOrderEntriesOf(Map.of("startDate", "2026-11-05", "endDate", "2026-11-05"));
        }
    }

    @Test void cancellationUsesExistingOwnedConversationService() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("status", "CONFIRMED"));
            var request = request(h, "不用查了"); model(h, "CANCEL", Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, null).getStatus()).isEqualTo("CANCELLED");
            verify(h.conversations).cancelConversation("F006", 7L, "sales-session");
            assertThat(h.owner.getSlot(EntitySlot.SlotType.READ_QUERY).getId()).isEqualTo("CLEARED");
            verifyNoInteractions(h.ranking);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"startDate", "endDate"})
    void closedSingleDayCompletesMissingBoundFromExistingCalendarEvidence(String providedBound) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("endDate", "2026-10-31", "status", "CONFIRMED"));
            var request = request(h, "查今天入库的原料");
            model(h, "SWITCH", Map.of(providedBound, "2026-11-05"), "CLOSED");
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new MaterialBatchQueryTool(), "MATERIAL_BATCH_QUERY"))).isNull();
            assertThat(request.getSemanticParameters()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("startDate", "2026-11-05", "endDate", "2026-11-05"));
            assertThat(request.getSemanticParameterSources()).containsEntry("startDate", "CURRENT_EXPLICIT").containsEntry("endDate", "CURRENT_EXPLICIT");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"FROM", "UNTIL"})
    void openEndedCorrectionRemovesOldOppositeBoundWithoutClosingToday(String scope) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "CONFIRMED"));
            String kept = "FROM".equals(scope) ? "startDate" : "endDate";
            String removed = "FROM".equals(scope) ? "endDate" : "startDate";
            var request = request(h, "FROM".equals(scope) ? "时间改为从今天起，状态不变" : "时间改为截至今天，状态不变");
            model(h, "CORRECT", Map.of(kept, "2026-11-05"), scope);
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new OrderListTool(mock(SalesService.class)), "ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(kept, "2026-11-05", "status", "CONFIRMED"));
            assertThat(request.getSemanticParameterSources()).doesNotContainKey(removed);
        }
    }

    @Test void nonTimeCorrectionRetainsBothPreviousDateBounds() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "CONFIRMED"));
            var request = request(h, "状态改为PROCESSING，日期不变");
            model(h, "CORRECT", Map.of("status", "PROCESSING"), "UNCHANGED");
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new OrderListTool(mock(SalesService.class)), "ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "startDate", "2026-10-01", "endDate", "2026-10-31", "status", "PROCESSING"));
            assertThat(request.getSemanticParameterSources()).containsEntry("startDate", "PREVIOUS_VALUE").containsEntry("endDate", "PREVIOUS_VALUE");
        }
    }

    @Test void reversedClosedDateRangeCannotExecuteAsAnEmptyQuery() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var request = request(h, "查2026-11-30到2026-11-01的订单");
            model(h, "NEW", Map.of("startDate", "2026-11-30", "endDate", "2026-11-01"), "CLOSED");
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new OrderListTool(mock(SalesService.class)), "ORDER_LIST")).getStatus())
                    .isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @Test void incompleteUnresolvedDateCannotRunAnUnboundedQuery() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var request = request(h, "查今天入库的原料");
            model(h, "NEW", Map.of("startDate", "2026-11-05"), "UNRESOLVED");
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new MaterialBatchQueryTool(), "MATERIAL_BATCH_QUERY")).getStatus())
                    .isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @Test void scopedNegationDoesNotCancelWhenSemanticActionIsNewQuery() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("status", "CONFIRMED"));
            var request = request(h, "不用的原料还有多少");
            model(h, "SWITCH", Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new MaterialBatchQueryTool(), "MATERIAL_BATCH_QUERY"))).isNull();
            verifyNoInteractions(h.conversations);
            assertThat(request.getSemanticParameters()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "UNKNOWN"})
    void staleOrUnknownCannotSupplyOldParameters(String status) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("status", "CONFIRMED"));
            var request = request(h, "上个月呢");
            var snapshot = new LinkedHashMap<>(request.getSemanticContext()); snapshot.put("contextStatus", status); request.setSemanticContext(snapshot);
            model(h, "CONTINUE", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31"));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new OrderListTool(mock(SalesService.class)), "ORDER_LIST")).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @Test void ungroundedNumberAndPrincipalParametersAreRejected() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var request = request(h, "看看销售排行"); model(h, "NEW", Map.of("limit", 50));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build()).getStatus()).isEqualTo("NEED_MORE_INFO");
            request = request(h, "看看销售排行"); model(h, "NEW", Map.of("factoryId", "F999"));
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build()).getStatus()).isEqualTo("NEED_MORE_INFO");
            verifyNoInteractions(h.ranking);
        }
    }

    @Test void unsupportedQuantityNeverRunsAmountRanking() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, SalesRankingParameters.INTENT, Map.of("metric", "amount"));
            var request = request(h, "不是金额，是数量"); model(h, "UNSUPPORTED", Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006", 7L, request, IntentMatchResult.builder().bestMatch(h.intent).confidence(1.0).build()).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            verifyNoInteractions(h.ranking);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tentativeNewGoalDoesNotClearPreviousTaskOnClarificationOrTransportFailure(boolean failed) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            previous(h, "ORDER_LIST", Map.of("startDate", "2026-10-01", "status", "CONFIRMED", "taskStatus", "COMPLETED"));
            var request = request(h, "换个看法呢");
            var client = model(h, "CLARIFY", Map.of());
            if (failed) when(client.chatWithTools(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("controlled transport failure"));
            var response = h.dispatch.resolveReadTurn("F006", 7L, request, bind(h, new MaterialBatchQueryTool(), "MATERIAL_BATCH_QUERY"));
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
            var retained = h.owner.getSlot(EntitySlot.SlotType.READ_QUERY);
            assertThat(retained.getId()).isEqualTo("ORDER_LIST");
            assertThat(retained.getMetadata()).containsEntry("startDate", "2026-10-01").containsEntry("status", "CONFIRMED")
                    .containsEntry("taskStatus", "COMPLETED");
        }
    }

    static IntentExecuteRequest request(SalesRankingSemanticContractTest.Harness h, String text) {
        var request = h.query(text); request.setIntentCode(null);
        new SemanticConversationContextService(h.memory, SalesRankingSemanticContractTest.CLOCK).prepare("F006", 7L, request);
        return request;
    }
    static void previous(SalesRankingSemanticContractTest.Harness h, String code, Map<String,Object> values) {
        h.owner.setSlot(EntitySlot.SlotType.READ_QUERY, EntitySlot.builder().id(code).metadata(values).build());
    }
    static IntentMatchResult bind(SalesRankingSemanticContractTest.Harness h, ToolExecutor tool, String code) {
        when(h.registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        return IntentMatchResult.builder().bestMatch(AIIntentConfig.builder().intentCode(code).toolName(tool.getToolName()).build()).confidence(1.0).build();
    }
    static DashScopeClient model(SalesRankingSemanticContractTest.Harness h, String action, Map<String,Object> delta) throws Exception {
        String scope = delta.containsKey("startDate") && delta.containsKey("endDate") ? "CLOSED"
                : delta.containsKey("startDate") ? "FROM" : delta.containsKey("endDate") ? "UNTIL" : "UNCHANGED";
        return model(h, action, delta, scope);
    }
    static DashScopeClient model(SalesRankingSemanticContractTest.Harness h, String action, Map<String,Object> delta, String scope) throws Exception {
        var client = mock(DashScopeClient.class);
        String arguments = h.mapper.writeValueAsString(Map.of("action", action, "dateScope", scope, "parameters", delta, "message", "请明确支持的查询口径。"));
        var response = h.mapper.convertValue(Map.of("choices", List.of(Map.of("message", Map.of("tool_calls", List.of(Map.of(
                "id", "fixture", "type", "function", "function", Map.of("name", "resolve_read_turn", "arguments", arguments))))))), ChatCompletionResponse.class);
        when(client.chatWithTools(anyString(), anyString(), anyList())).thenReturn(response);
        when(client.hasToolCalls(response)).thenReturn(true);
        ReflectionTestUtils.setField(h.dispatch, "dashScopeClient", client);
        return client;
    }
}

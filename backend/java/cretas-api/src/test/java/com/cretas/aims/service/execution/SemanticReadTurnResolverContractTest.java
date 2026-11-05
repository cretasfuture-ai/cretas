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
        var client = mock(DashScopeClient.class);
        String arguments = h.mapper.writeValueAsString(Map.of("action", action, "parameters", delta, "message", "请明确支持的查询口径。"));
        var response = h.mapper.convertValue(Map.of("choices", List.of(Map.of("message", Map.of("tool_calls", List.of(Map.of(
                "id", "fixture", "type", "function", "function", Map.of("name", "resolve_read_turn", "arguments", arguments))))))), ChatCompletionResponse.class);
        when(client.chatWithTools(anyString(), anyString(), anyList())).thenReturn(response);
        when(client.hasToolCalls(response)).thenReturn(true);
        ReflectionTestUtils.setField(h.dispatch, "dashScopeClient", client);
        return client;
    }
}

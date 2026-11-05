package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.factorycatalog.ProductSalesRankingTool;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.dto.conversation.*;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.*;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Public development scenarios, NOT independent gold or a live-model/production evaluation. */
class SalesRankingSemanticContractTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test void sharedPolicyPreservesDateAndUpdatesOnlyMetricAndLimit() {
        try (var h = new Harness()) {
            var first = h.query("看看销售排行");
            assertThat(h.run(first).getStatus()).isEqualTo("SUCCESS");
            assertThat(first.getContext()).containsEntry("startDate", "2026-11-01")
                    .containsEntry("endDate", "2026-11-05").containsEntry("limit", 20);
            var month = h.query("上个月呢"); month.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, month)).isNull();
            assertThat(h.run(month).getStatus()).isEqualTo("SUCCESS");
            assertThat(month.getContext()).containsEntry("startDate", "2026-10-01")
                    .containsEntry("endDate", "2026-10-31").containsEntry("limit", 20);
            var qty = h.query("不是金额，按销量，前五个"); qty.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, qty)).isNull();
            clearInvocations(h.ranking);
            IntentExecuteResponse rejected = h.run(qty);
            assertThat(rejected.getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            assertThat(rejected.getMessage()).contains("不支持销量");
            assertThat(qty.getContext()).containsEntry("metric", "quantity").containsEntry("limit", 5)
                    .containsEntry("startDate", "2026-10-01");
            verifyNoInteractions(h.ranking); // no amount query disguised as quantity
            var amount = h.query("按金额，前五个"); amount.setIntentCode(null);
            h.support.prepare("F006", 7L, amount);
            assertThat(h.run(amount).getStatus()).isEqualTo("SUCCESS");
            assertThat(amount.getContext()).containsEntry("metric", "amount").containsEntry("limit", 5)
                    .containsEntry("startDate", "2026-10-01");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"不用的原料还有多少", "取消销售订单SO-123", "算了，先查原料库存", "查询上个月待发货订单"})
    void newGoalNeverReceivesRankingParameters(String text) {
        try (var h = new Harness()) {
            h.run(h.query("看看销售排行"));
            var next = h.query(text); next.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, next)).isNull();
            assertThat(next.getContext()).isNull();
            assertThat(next.getIntentCode()).isNull();
            var date = h.query("上个月呢"); date.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, date)).isNull();
            assertThat(date.getContext()).isNull();
            verify(h.conversations, never()).cancelConversation(anyString(), anyLong(), anyString());
        }
    }

    @Test void scopedNegationDoesNotCancel() {
        try (var h = new Harness()) {
            h.run(h.query("看看销售排行"));
            var correction = h.query("不是不查，先看上个月"); correction.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, correction)).isNull();
            assertThat(h.run(correction).getStatus()).isEqualTo("SUCCESS");
            verify(h.conversations, never()).cancelConversation(anyString(), anyLong(), anyString());
        }
    }

    @Test void cancelledReadCannotBeRestoredByLateExtractionOrResult() {
        try (var h = new Harness()) {
            h.run(h.query("看看销售排行"));
            var old = h.query("上个月呢"); old.setIntentCode(null);
            h.support.prepare("F006", 7L, old);
            doAnswer(call -> {
                var cancel = h.query("不用查了"); cancel.setIntentCode(null);
                assertThat(h.support.prepare("F006", 7L, cancel).getStatus()).isEqualTo("CANCELLED");
                return Map.of();
            }).when(h.dispatch).extractParametersWithLLM(eq("F006"), anyString(), any(), anyMap());
            clearInvocations(h.ranking);
            assertThat(h.run(old).getStatus()).isEqualTo("CANCELLED");
            verifyNoInteractions(h.ranking);
            var later = h.query("上个月呢"); later.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, later).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
    }

    @Test void foreignAndExpiredMemoryCannotSupplyReadParameters() {
        try (var h = new Harness()) {
            h.run(h.query("看看销售排行"));
            h.owner.setUserId(8L);
            var r = h.query("上个月呢"); r.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, r).getStatus()).isEqualTo("NO_PERMISSION");
            assertThat(r.getContext()).isNull();
            h.owner.setUserId(7L);
            h.owner.getSlot(EntitySlot.SlotType.READ_QUERY).setMentionedAt(LocalDateTime.now(CLOCK).minusMinutes(11));
            assertThat(h.support.prepare("F006", 7L, r).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
    }

    @Test void realToolReportsAmountBasisAndDeterministicRows() {
        try (var h = new Harness()) {
            var response = h.run(h.query("上个月销售排行前五个"));
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getResultData().toString()).contains("SKU-A", "SKU-B", "2026-10-01", "前5", "非实收收入", "按单位分桶");
            verify(h.ranking).rank("F006", LocalDate.of(2026,10,1), LocalDate.of(2026,10,31));
        }
    }

    @Test void lateToolResultIsDiscardedAfterCancellation() {
        try (var h = new Harness()) {
            var rows = List.of(Harness.row("SKU-OLD", "kg", "5", "100"));
            when(h.ranking.rank(anyString(), any(), any())).thenAnswer(c -> {
                var cancel = h.query("取消当前查询"); cancel.setIntentCode(null);
                assertThat(h.support.prepare("F006", 7L, cancel).getStatus()).isEqualTo("CANCELLED");
                return rows;
            });
            var result = h.run(h.query("看看销售排行"));
            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            assertThat(result.getResultData()).isNull();
            assertThat(h.owner.getSlot(EntitySlot.SlotType.READ_QUERY).getId()).isEqualTo("CLEARED");
        }
    }

    @Test void metricNegationDoesNotSelectNegatedMetric() {
        var params = new HashMap<String, Object>();
        SalesRankingParameters.apply("不是按销量，按金额，本月前五个", params, LocalDate.now(CLOCK));
        assertThat(params).containsEntry("metric", "amount").containsEntry("limit", 5);
        SalesRankingParameters.apply("不是上个月，这个月", params, LocalDate.now(CLOCK));
        assertThat(params).containsEntry("startDate", "2026-11-01");
    }

    @Test void unrelatedPendingSlotKeepsItsExistingContinuation() {
        try (var h = new Harness()) {
            var date = h.query("上个月呢"); date.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, date)).isNull();
            h.run(h.query("看看销售排行"));
            var writeGoal = h.query("开始工序任务"); writeGoal.setIntentCode(null);
            assertThat(h.support.prepare("F006", 7L, writeGoal)).isNull();
            assertThat(h.support.prepare("F006", 7L, date)).isNull();
            assertThat(date.getContext()).isNull();
            verify(h.conversations, never()).cancelConversation(anyString(), anyLong(), anyString());
        }
    }

    @Test void directToolQuantityAndInvalidLimitNeverQueryTheService() throws Exception {
        try (var h = new Harness()) {
            for (var extra : List.of(Map.<String,Object>of("metric", "quantity"), Map.<String,Object>of("limit", 101))) {
                var params = new HashMap<String,Object>(Map.of("startDate", "2026-10-01", "endDate", "2026-10-31"));
                params.putAll(extra);
                String json = h.tool.execute(com.cretas.aims.ai.dto.ToolCall.of("guard", SalesRankingParameters.TOOL,
                        h.mapper.writeValueAsString(params)), Map.of("factoryId", "F006", "userId", 7L));
                assertThat(h.mapper.readTree(json).path("success").asBoolean()).isFalse();
            }
            verifyNoInteractions(h.ranking);
        }
    }

    static class Harness implements AutoCloseable {
        final AutoCloseable actor = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 7L, "F006", "sales_manager"));
        final ConversationMemoryService memory = mock(ConversationMemoryService.class);
        final ConversationService conversations = mock(ConversationService.class);
        final ConversationContext owner = ConversationContext.builder().sessionId("sales-session")
                .factoryId("F006").userId(7L).build();
        final SalesRankingConversationSupport support = new SalesRankingConversationSupport(memory, conversations, CLOCK);
        final ProductSalesRankingService ranking = mock(ProductSalesRankingService.class);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final ProductSalesRankingTool tool = new ProductSalesRankingTool(ranking);
        final ToolRegistry registry = mock(ToolRegistry.class);
        final AIIntentConfig intent = AIIntentConfig.builder().intentCode(SalesRankingParameters.INTENT)
                .intentName("产品销售排行").intentCategory("SALES").businessType("FACTORY")
                .toolName(SalesRankingParameters.TOOL).isActive(true).build();
        final ToolDispatchService dispatch;
        Harness() {
            when(memory.getContext("sales-session")).thenReturn(owner);
            when(memory.getOrCreateContext("F006", 7L, "sales-session")).thenReturn(owner);
            doAnswer(c -> { owner.setSlot(c.getArgument(1), c.getArgument(2)); return null; })
                    .when(memory).updateEntitySlot(eq("sales-session"), any(), any());
            when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
            when(registry.getExecutor(SalesRankingParameters.TOOL)).thenReturn(Optional.of(tool));
            ReflectionTestUtils.setField(tool, "objectMapper", mapper);
            var rows = List.of(row("SKU-A", "kg", "8", "300"), row("SKU-B", "箱", "99", "100"));
            when(ranking.rank(anyString(), any(), any())).thenReturn(rows);
            var redundancy = mock(ToolCallRedundancyService.class);
            when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
            dispatch = spy(new ToolDispatchService(registry, mapper, null, redundancy,
                    mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                    mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                    mock(ParameterExtractionLearningService.class)));
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            var rbac = mock(ToolRbacEnforcer.class);
            when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
            when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
            ReflectionTestUtils.setField(dispatch, "salesRankingConversationSupport", support);
            doReturn(Map.of()).when(dispatch).extractParametersWithLLM(eq("F006"), anyString(), any(), anyMap());
        }
        IntentExecuteRequest query(String text) { return IntentExecuteRequest.builder().userInput(text)
                .intentCode(SalesRankingParameters.INTENT).sessionId("sales-session").mode("READ").build(); }
        IntentExecuteResponse run(IntentExecuteRequest request) {
            return dispatch.executeWithTool(tool, "F006", request, intent, 7L, "sales_manager", null);
        }
        static SalesOrderItemRepository.SalesRankingRow row(String id, String unit, String qty, String amount) {
            var row = mock(SalesOrderItemRepository.SalesRankingRow.class);
            when(row.getProductTypeId()).thenReturn(id); when(row.getProductName()).thenReturn(id);
            when(row.getUnit()).thenReturn(unit); when(row.getQuantity()).thenReturn(new BigDecimal(qty));
            when(row.getAmount()).thenReturn(new BigDecimal(amount));
            when(row.getQuantitiesByUnit()).thenReturn(Map.of(unit, new BigDecimal(qty)));
            return row;
        }
        public void close() { try { actor.close(); } catch (Exception e) { throw new IllegalStateException(e); } }
    }
}

package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioral contract for server-owned SSE pagination.
 *
 * The fixture mirrors MaterialBatchQueryTool's real result shape:
 * currentPage/totalPages/hasMore/content/queryConditions. The dispatch mock
 * returns distinct rows per page so a source-only test cannot satisfy this gate.
 */
class SseConversationPaginationContractTest {

    @Test
    void successfulReadPageTwoReplaysServerIntentFiltersAndOriginalPageSize() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        IntentExecuteRequest first = IntentExecuteRequest.builder()
                .userInput("查询可用原料批次")
                .intentCode("MATERIAL_BATCH_QUERY")
                .context(new LinkedHashMap<>(Map.of(
                        "page", 1,
                        "size", 2,
                        "status", "AVAILABLE")))
                .build();

        h.execute(first, 7L);
        h.execute(IntentExecuteRequest.builder()
                .userInput("下一页")
                .intentCode("MATERIAL_BATCH_CONSUME")
                .sessionId(Harness.SESSION)
                .build(), 7L);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY),
                requests.capture(), eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        IntentExecuteRequest replay = requests.getAllValues().get(1);
        assertThat(replay.getIntentCode()).isEqualTo("MATERIAL_BATCH_QUERY");
        assertThat(replay.getUserInput()).isEqualTo("查询可用原料批次");
        assertThat(replay.getContext()).containsEntry("page", 2)
                .containsEntry("size", 2)
                .containsEntry("status", "AVAILABLE")
                .containsEntry("paginationReplay", true);

        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse pageTwo = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(pageTwo.getResultData()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) pageTwo.getResultData()).get("content"))
                .isEqualTo(List.of("MATERIAL_BATCH_PAGE_2"));
    }

    @Test
    void deniedStoredIntentDoesNotDispatchOrAppendMemory() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        when(h.permissionGate.check(eq(h.intent), eq(Harness.USER), eq(Harness.ROLE)))
                .thenReturn(IntentPermissionGate.PermissionCheck.allowed())
                .thenReturn(IntentPermissionGate.PermissionCheck.denied("warehouse:read"));

        h.execute(h.firstPageRequest(), Harness.USER);
        h.execute(IntentExecuteRequest.builder().userInput("下一页").sessionId(Harness.SESSION).build(), Harness.USER);

        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(h.memory, times(2)).addMessage(eq(Harness.SESSION), any(ConversationMessage.class));
    }

    @Test
    void forgedWriteIntentUsesStoredReadIntent() throws Exception {
        Harness h = new Harness();
        h.readIntent();

        h.execute(h.firstPageRequest(), Harness.USER);
        h.execute(IntentExecuteRequest.builder()
                .userInput("下一页")
                .intentCode("MATERIAL_BATCH_CONSUME")
                .sessionId(Harness.SESSION)
                .build(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().get(1).getIntentCode()).isEqualTo("MATERIAL_BATCH_QUERY");
    }

    @Test
    void currentExecutorWriteRebindOrWriteGuardRefusesWithoutReplay() throws Exception {
        Harness rebound = new Harness();
        rebound.readIntent();
        rebound.execute(rebound.firstPageRequest(), Harness.USER);
        when(rebound.executor.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        rebound.execute(rebound.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(rebound, "只读列表", "PAGINATION_NEXT");
        verify(rebound.memory, times(2)).addMessage(eq(Harness.SESSION), any(ConversationMessage.class));

        Harness guarded = new Harness();
        guarded.readIntent();
        guarded.execute(guarded.firstPageRequest(), Harness.USER);
        when(guarded.writeGuardService.isWriteTool(guarded.executor)).thenReturn(true);
        guarded.execute(guarded.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(guarded, "只读列表", "PAGINATION_NEXT");
        verify(guarded.memory, times(2)).addMessage(eq(Harness.SESSION), any(ConversationMessage.class));
    }

    @Test
    void foreignSessionAndIncompletePrincipalCannotDispatchOrWriteMemory() throws Exception {
        Harness foreign = new Harness();
        foreign.readIntent();
        foreign.execute(hRequestWithSession("foreign-session"), Harness.USER);
        verify(foreign.toolDispatchService, never()).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(foreign.memory, never()).addMessage(anyString(), any(ConversationMessage.class));

        Harness incomplete = new Harness();
        incomplete.readIntent();
        incomplete.execute(hRequestWithSession(Harness.SESSION), null);
        verify(incomplete.toolDispatchService, never()).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(incomplete.memory, never()).addMessage(anyString(), any(ConversationMessage.class));
    }

    @Test
    void foreignStoredOwnerCannotDispatchOrWriteMemory() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        h.execute(h.firstPageRequest(), Harness.USER);
        ConversationContext foreign = ConversationContext.builder()
                .sessionId(Harness.SESSION).factoryId("F999").userId(999L)
                .recentMessages(new ArrayList<>(h.context.getRecentMessages())).build();
        when(h.memory.getContext(Harness.SESSION)).thenReturn(foreign);

        h.execute(IntentExecuteRequest.builder().userInput("下一页").sessionId(Harness.SESSION).build(), Harness.USER);

        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(h.memory, times(2)).addMessage(eq(Harness.SESSION), any(ConversationMessage.class));
    }

    @Test
    void lastPageRefusesWithoutDispatch() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        h.onlyOnePage = true;
        h.execute(h.firstPageRequest(), Harness.USER);
        h.execute(IntentExecuteRequest.builder().userInput("下一页").sessionId(Harness.SESSION).build(), Harness.USER);

        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        assertThat(((IntentExecuteResponse) results.getAllValues().get(1)).getStatus()).isEqualTo("PAGINATION_REFUSED");
    }

    @Test
    void previousFromFirstPageRefusesWithoutDispatch() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        h.execute(h.firstPageRequest(), Harness.USER);
        h.execute(IntentExecuteRequest.builder().userInput("上一页").sessionId(Harness.SESSION).build(), Harness.USER);
        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse refusal = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(refusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(refusal.getIntentCode()).isEqualTo("PAGINATION_PREVIOUS");
        assertThat(refusal.getMessage()).contains("第一页");
    }

    @Test
    void previousFromTailReplaysPageTwoAndPreservesFiltersAndSize() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        h.execute(h.firstPageRequest(), Harness.USER);
        h.execute(IntentExecuteRequest.builder().userInput("下一页").sessionId(Harness.SESSION).build(), Harness.USER);
        h.execute(IntentExecuteRequest.builder().userInput("上一页").sessionId(Harness.SESSION).build(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(3)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().get(2).getContext())
                .containsEntry("page", 1).containsEntry("size", 2)
                .containsEntry("status", "AVAILABLE").containsEntry("paginationReplay", true);
    }

    @Test
    void schemaOwnedOneBasedPaginationMovesTailBackAndForward() throws Exception {
        Harness h = new Harness();
        h.totalPages = 3;
        h.readIntent();
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(5)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2, 3, 2, 3);
    }

    @Test
    void knownSchemaTailTrueAndInteriorFalseAreRejectedAsInconsistent() throws Exception {
        Harness tailTrue = new Harness();
        tailTrue.totalPages = 3;
        tailTrue.hasMoreOverride = true;
        tailTrue.readIntent();
        tailTrue.execute(tailTrue.pageRequest(3), Harness.USER);
        tailTrue.execute(tailTrue.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(tailTrue, "不一致", "PAGINATION_NEXT");

        Harness interiorFalse = new Harness();
        interiorFalse.totalPages = 3;
        interiorFalse.hasMoreOverride = false;
        interiorFalse.readIntent();
        interiorFalse.execute(interiorFalse.pageRequest(1), Harness.USER);
        interiorFalse.execute(interiorFalse.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(interiorFalse, "不一致", "PAGINATION_NEXT");
    }

    @Test
    void knownSchemaMissingHasMoreUsesTotalPagesForTail() throws Exception {
        Harness h = new Harness();
        h.totalPages = 3;
        h.omitHasMore = true;
        h.readIntent();
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(4)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2, 3, 2);
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(5)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        assertThat(((Map<?, ?>) ((IntentExecuteResponse) results.getAllValues().get(2)).getResultData())
                .get("currentPage")).isEqualTo(3);
        assertThat(((IntentExecuteResponse) results.getAllValues().get(3)).getStatus())
                .isEqualTo("PAGINATION_REFUSED");
        assertThat(((Map<?, ?>) ((IntentExecuteResponse) results.getAllValues().get(4)).getResultData())
                .get("currentPage")).isEqualTo(2);
    }

    @Test
    void knownSchemaWithoutTotalPagesUsesReliableHasMore() throws Exception {
        Harness h = new Harness();
        h.omitTotalPages = true;
        h.readIntent();
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(3)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2, 1);
    }

    @Test
    void schemaOwnedZeroBasedPaginationAllowsTailPreviousAndRejectsFirstPrevious() throws Exception {
        Harness h = new Harness();
        h.schemaPageBase = 0;
        h.schemaDefault = 1;
        h.totalPages = 3;
        h.readIntent();
        h.execute(h.pageRequest(0), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> firstResults = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), firstResults.capture());
        IntentExecuteResponse firstRefusal = (IntentExecuteResponse) firstResults.getAllValues().get(1);
        assertThat(firstRefusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(firstRefusal.getIntentCode()).isEqualTo("PAGINATION_PREVIOUS");
        assertThat(firstRefusal.getMessage()).contains("第一页");

        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(4)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(0, 1, 2, 1);
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(5)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse zeroBasedTail = (IntentExecuteResponse) results.getAllValues().get(3);
        assertThat(((Map<?, ?>) zeroBasedTail.getResultData()).get("currentPage")).isEqualTo(2);
        assertThat(((Map<?, ?>) zeroBasedTail.getResultData()).get("hasMore")).isEqualTo(false);
        IntentExecuteResponse zeroBasedPrevious = (IntentExecuteResponse) results.getAllValues().get(4);
        assertThat(((Map<?, ?>) zeroBasedPrevious.getResultData()).get("currentPage")).isEqualTo(1);
    }

    @Test
    void unknownSchemaBaseRefusesPreviousButKeepsNext() throws Exception {
        Harness h = new Harness();
        h.schemaPageBase = null;
        h.readIntent();
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2);
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(4)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        assertThat(((IntentExecuteResponse) results.getAllValues().get(1)).getMessage()).contains("基准未知");
        assertThat(((IntentExecuteResponse) results.getAllValues().get(3)).getMessage()).contains("基准未知");
    }

    @Test
    void incompleteSchemaBaseRefusesPreviousButKeepsNext() throws Exception {
        Harness mismatch = new Harness();
        mismatch.schemaPageBase = null;
        mismatch.schemaMinimum = 1;
        mismatch.schemaDefault = 0;
        mismatch.readIntent();
        assertUnknownBasePreviousAndNext(mismatch);

        Harness defaultOnly = new Harness();
        defaultOnly.schemaPageBase = null;
        defaultOnly.schemaDefault = 1;
        defaultOnly.readIntent();
        assertUnknownBasePreviousAndNext(defaultOnly);
    }

    @Test
    void minimumOneDefaultTwoStillUsesOneBasedPrevious() throws Exception {
        Harness h = new Harness();
        h.schemaPageBase = null;
        h.schemaMinimum = 1;
        h.schemaDefault = 2;
        h.totalPages = 3;
        h.readIntent();
        h.execute(h.pageRequest(3), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(3, 2);
    }

    @Test
    void defaultBelowMinimumKeepsUnknownBaseAndNextCompatibility() throws Exception {
        Harness h = new Harness();
        h.schemaPageBase = null;
        h.schemaMinimum = 1;
        h.schemaDefault = 0;
        h.readIntent();
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2);
    }

    @Test
    void unknownSchemaMissingOrInvalidHasMoreRefusesNext() throws Exception {
        Harness missing = new Harness();
        missing.schemaPageBase = null;
        missing.omitHasMore = true;
        missing.readIntent();
        missing.execute(missing.pageRequest(1), Harness.USER);
        missing.execute(missing.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(missing, "缺少 hasMore", "PAGINATION_NEXT");

        Harness invalid = new Harness();
        invalid.schemaPageBase = null;
        invalid.hasMoreOverride = "true";
        invalid.readIntent();
        invalid.execute(invalid.pageRequest(1), Harness.USER);
        invalid.execute(invalid.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(invalid, "hasMore 无效", "PAGINATION_NEXT");
    }

    @Test
    void knownSchemaRejectsPageOutsideRangeAndInvalidTotalPages() throws Exception {
        Harness outside = new Harness();
        outside.readIntent();
        outside.execute(outside.pageRequest(0), Harness.USER);
        outside.execute(outside.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(outside, "分页范围", "PAGINATION_NEXT");

        Harness invalidTotal = new Harness();
        invalidTotal.responseTotalPages = "3.5";
        invalidTotal.readIntent();
        invalidTotal.execute(invalidTotal.pageRequest(1), Harness.USER);
        invalidTotal.execute(invalidTotal.nextPageRequest(), Harness.USER);
        assertPaginationRefusal(invalidTotal, "总页数无效", "PAGINATION_NEXT");
    }

    private static void assertUnknownBasePreviousAndNext(Harness h) throws Exception {
        h.execute(h.pageRequest(1), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);

        ArgumentCaptor<IntentExecuteRequest> requests = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService, times(2)).executeWithTool(any(), eq(Harness.FACTORY), requests.capture(),
                eq(h.intent), eq(Harness.USER), eq(Harness.ROLE), any());
        assertThat(requests.getAllValues().stream().map(r -> r.getContext().get("page")).toList())
                .containsExactly(1, 2);
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(3)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse refusal = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(refusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(refusal.getIntentCode()).isEqualTo("PAGINATION_PREVIOUS");
        assertThat(refusal.getMessage()).contains("基准未知");
    }

    private static void assertPaginationRefusal(Harness h, String message, String intentCode) throws Exception {
        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse refusal = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(refusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(refusal.getIntentCode()).isEqualTo(intentCode);
        assertThat(refusal.getMessage()).contains(message);
    }

    @Test
    void clientPageHintsAndCurrentPageOneDoNotDefinePreviousBase() throws Exception {
        Harness h = new Harness();
        h.schemaPageBase = null;
        h.readIntent();
        Map<String, Object> context = new LinkedHashMap<>(Map.of(
                "page", 1, "size", 2, "status", "AVAILABLE",
                "pageBase", 0, "paginationReplay", true));
        h.execute(IntentExecuteRequest.builder().userInput("查询可用原料批次")
                .intentCode("MATERIAL_BATCH_QUERY").context(context).build(), Harness.USER);
        h.execute(h.previousPageRequest(), Harness.USER);

        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        assertThat(((IntentExecuteResponse) results.getAllValues().get(1)).getMessage()).contains("基准未知");
    }

    @Test
    void negativePageDoesNotCreateReplayableMetadata() throws Exception {
        Harness h = new Harness();
        h.readIntent();
        h.execute(h.pageRequest(-1), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);
        assertInvalidPageRefusedWithoutReplay(h);

        Harness nonInteger = new Harness();
        nonInteger.responseCurrentPage = "1.5";
        nonInteger.readIntent();
        nonInteger.execute(nonInteger.pageRequest(1), Harness.USER);
        nonInteger.execute(nonInteger.nextPageRequest(), Harness.USER);
        assertInvalidPageRefusedWithoutReplay(nonInteger);
    }

    private static void assertInvalidPageRefusedWithoutReplay(Harness h) throws Exception {
        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse refusal = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(refusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(refusal.getMessage()).isNotBlank();
    }

    @Test
    void maxPageNextRefusesWithoutOverflowReplay() throws Exception {
        Harness h = new Harness();
        h.totalPages = (long) Integer.MAX_VALUE + 1L;
        h.readIntent();
        h.execute(h.pageRequest(Integer.MAX_VALUE), Harness.USER);
        h.execute(h.nextPageRequest(), Harness.USER);

        verify(h.toolDispatchService, times(1)).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
        ArgumentCaptor<Object> results = ArgumentCaptor.forClass(Object.class);
        verify(h.service, times(2)).sendSseEvent(eq(h.emitter), eq("result"), results.capture());
        IntentExecuteResponse refusal = (IntentExecuteResponse) results.getAllValues().get(1);
        assertThat(refusal.getStatus()).isEqualTo("PAGINATION_REFUSED");
        assertThat(refusal.getMessage()).contains("超出");
    }

    private static IntentExecuteRequest hRequestWithSession(String sessionId) {
        return IntentExecuteRequest.builder()
                .userInput("查询可用原料批次")
                .intentCode("MATERIAL_BATCH_QUERY")
                .sessionId(sessionId)
                .context(new LinkedHashMap<>(Map.of("page", 1, "size", 2, "status", "AVAILABLE")))
                .build();
    }

    private static final class Harness {
        private static final String FACTORY = "F001";
        private static final long USER = 7L;
        private static final String ROLE = "factory_super_admin";
        private static final String SESSION = "sse-session-1";

        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        private final WriteGuardService writeGuardService = mock(WriteGuardService.class);
        private final BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        private final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        private final ConversationMemoryService memory = mock(ConversationMemoryService.class);
        private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
        private final SseStreamingService service;
        private final SseEmitter emitter = mock(SseEmitter.class);
        private final ConversationContext context = ConversationContext.builder()
                .sessionId(SESSION).factoryId(FACTORY).userId(USER)
                .recentMessages(new ArrayList<>()).build();
        private AIIntentConfig intent;
        private boolean onlyOnePage;
        private boolean forceHasMore;
        private boolean omitHasMore;
        private boolean omitTotalPages;
        private Object hasMoreOverride;
        private Integer schemaPageBase = 1;
        private Integer schemaMinimum;
        private Integer schemaDefault;
        private Object responseCurrentPage;
        private Object responseTotalPages;
        private ToolExecutor executor;
        private long totalPages = 2;

        private Harness() throws Exception {
            Factory factory = new Factory();
            factory.setId(FACTORY);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository,
                    new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                    false);
            service = org.mockito.Mockito.spy(new SseStreamingService(
                    aiIntentService, mock(SemanticCacheService.class), mock(IntentKnowledgeBase.class),
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, toolDispatchService,
                    mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", policy);
            ReflectionTestUtils.setField(service, "writeGuardService", writeGuardService);
            ReflectionTestUtils.setField(service, "businessTypeGate", businessTypeGate);
            ReflectionTestUtils.setField(service, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(service, "conversationMemoryService", memory);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
            when(memory.getOrCreateContext(eq(FACTORY), eq(USER), isNull())).thenReturn(context);
            when(memory.getOrCreateContext(eq(FACTORY), eq(USER), eq(SESSION))).thenReturn(context);
            when(memory.getContext(SESSION)).thenReturn(context);
            doAnswer(invocation -> {
                context.getRecentMessages().add(invocation.getArgument(1));
                return null;
            }).when(memory).addMessage(eq(SESSION), any(ConversationMessage.class));
            doAnswer(invocation -> {
                IntentExecuteRequest request = invocation.getArgument(2);
                int page = ((Number) request.getContext().getOrDefault("page", 1)).intValue();
                return pageResponse(page, onlyOnePage, totalPages, forceHasMore,
                        omitHasMore, omitTotalPages, hasMoreOverride, schemaPageBase, responseCurrentPage,
                        responseTotalPages);
            }).when(toolDispatchService).executeWithTool(any(), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), any());
        }

        private void readIntent() {
            intent = AIIntentConfig.builder()
                    .intentCode("MATERIAL_BATCH_QUERY").intentName("原料批次查询")
                    .intentCategory("QUERY").businessType("FACTORY").sensitivityLevel("LOW")
                    .toolName("material_batch_query").requiredPermission("warehouse:read").build();
            when(aiIntentService.getIntentByCode(FACTORY, intent.getIntentCode())).thenReturn(Optional.of(intent));
            when(aiIntentService.hasPermission(intent.getIntentCode(), ROLE)).thenReturn(true);
            executor = mock(ToolExecutor.class);
            when(executor.getToolName()).thenReturn("material_batch_query");
            when(executor.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
            when(executor.getParametersSchema()).thenAnswer(invocation -> pageSchema(
                    schemaPageBase, schemaMinimum, schemaDefault));
            when(toolRegistry.getExecutor("material_batch_query")).thenReturn(Optional.of(executor));
            when(writeGuardService.isWriteTool(executor)).thenReturn(false);
            when(writeGuardService.isWriteIntent(intent)).thenReturn(false);
            when(permissionGate.check(eq(intent), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        }

        private IntentExecuteRequest firstPageRequest() {
            return pageRequest(1);
        }

        private IntentExecuteRequest pageRequest(int page) {
            return pageRequestValue(page);
        }

        private IntentExecuteRequest pageRequestValue(Object page) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("page", page);
            context.put("size", 2);
            context.put("status", "AVAILABLE");
            return IntentExecuteRequest.builder()
                    .userInput("查询可用原料批次").intentCode(intent.getIntentCode())
                    .context(context)
                    .build();
        }

        private IntentExecuteRequest nextPageRequest() {
            return IntentExecuteRequest.builder().userInput("下一页").sessionId(SESSION).build();
        }

        private IntentExecuteRequest previousPageRequest() {
            return IntentExecuteRequest.builder().userInput("上一页").sessionId(SESSION).build();
        }

        private void execute(IntentExecuteRequest request, Long userId) throws Exception {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, FACTORY, request,
                    userId, ROLE);
        }

        private static IntentExecuteResponse pageResponse(int page, boolean onlyOnePage,
                                                          long totalPages, boolean forceHasMore,
                                                          boolean omitHasMore, boolean omitTotalPages,
                                                          Object hasMoreOverride,
                                                          Integer schemaPageBase, Object responseCurrentPage,
                                                          Object responseTotalPages) {
            totalPages = onlyOnePage ? 1 : totalPages;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of("MATERIAL_BATCH_PAGE_" + page));
            result.put("totalElements", onlyOnePage ? 1L : 2L);
            if (!omitTotalPages) {
                result.put("totalPages", responseTotalPages == null ? totalPages : responseTotalPages);
            }
            result.put("currentPage", responseCurrentPage == null ? page : responseCurrentPage);
            long nextPage = (long) page + 1L;
            boolean schemaHasMore = schemaPageBase != null && schemaPageBase == 0
                    ? nextPage < totalPages : page < totalPages;
            if (!omitHasMore) {
                result.put("hasMore", hasMoreOverride == null
                        ? forceHasMore || schemaHasMore : hasMoreOverride);
            }
            result.put("queryConditions", Map.of("status", "AVAILABLE"));
            return IntentExecuteResponse.builder().intentRecognized(true)
                    .intentCode("MATERIAL_BATCH_QUERY").status("COMPLETED")
                    .message("原料批次查询完成").resultData(result).build();
        }

        private static Map<String, Object> pageSchema(Integer pageBase,
                                                       Integer minimumOverride,
                                                       Integer defaultOverride) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("type", "integer");
            if (pageBase != null) {
                page.put("default", pageBase);
                page.put("minimum", pageBase);
            }
            if (minimumOverride != null) page.put("minimum", minimumOverride);
            if (defaultOverride != null) page.put("default", defaultOverride);
            return Map.of("type", "object", "properties", Map.of("page", page));
        }
    }
}

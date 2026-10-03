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
                return pageResponse(page, onlyOnePage);
            }).when(toolDispatchService).executeWithTool(any(), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), any());
        }

        private void readIntent() {
            intent = AIIntentConfig.builder()
                    .intentCode("MATERIAL_BATCH_QUERY").intentName("原料批次查询")
                    .intentCategory("QUERY").businessType("FACTORY").sensitivityLevel("LOW")
                    .toolName("material_batch_query").requiredPermission("warehouse:read").build();
            when(aiIntentService.getIntentByCode(FACTORY, intent.getIntentCode())).thenReturn(Optional.of(intent));
            when(aiIntentService.hasPermission(intent.getIntentCode(), ROLE)).thenReturn(true);
            ToolExecutor executor = mock(ToolExecutor.class);
            when(executor.getToolName()).thenReturn("material_batch_query");
            when(executor.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
            when(toolRegistry.getExecutor("material_batch_query")).thenReturn(Optional.of(executor));
            when(writeGuardService.isWriteTool(executor)).thenReturn(false);
            when(writeGuardService.isWriteIntent(intent)).thenReturn(false);
            when(permissionGate.check(eq(intent), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        }

        private IntentExecuteRequest firstPageRequest() {
            return IntentExecuteRequest.builder()
                    .userInput("查询可用原料批次").intentCode(intent.getIntentCode())
                    .context(new LinkedHashMap<>(Map.of("page", 1, "size", 2, "status", "AVAILABLE")))
                    .build();
        }

        private void execute(IntentExecuteRequest request, Long userId) throws Exception {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, FACTORY, request,
                    userId, ROLE);
        }

        private static IntentExecuteResponse pageResponse(int page, boolean onlyOnePage) {
            int totalPages = onlyOnePage ? 1 : 2;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of("MATERIAL_BATCH_PAGE_" + page));
            result.put("totalElements", onlyOnePage ? 1L : 2L);
            result.put("totalPages", totalPages);
            result.put("currentPage", page);
            result.put("hasMore", page < totalPages);
            result.put("queryConditions", Map.of("status", "AVAILABLE"));
            return IntentExecuteResponse.builder().intentRecognized(true)
                    .intentCode("MATERIAL_BATCH_QUERY").status("COMPLETED")
                    .message("原料批次查询完成").resultData(result).build();
        }
    }
}

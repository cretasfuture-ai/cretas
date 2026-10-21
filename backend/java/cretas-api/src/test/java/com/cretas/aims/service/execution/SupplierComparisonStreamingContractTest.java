package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.SlotFillingService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.SlotFillingServiceImpl;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupplierComparisonStreamingContractTest {
    private static final String FACTORY = "F-SUP";
    private static final long USER_ID = 42L;
    private static final String ROLE = "procurement_manager";
    private static final String INPUT = "请比较供应商价格";

    @ParameterizedTest
    @ValueSource(strings = {"explicit", "natural"})
    void trustedAsyncActorReachesRealToolDispatchAndPreservesUnavailable(String route) throws Exception {
        StreamingFixture fixture = StreamingFixture.actualDispatch();
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.execute(route, null, false);

            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getMessage()).contains("未接入真实报价");
            assertThat(response.getResultData()).isInstanceOf(Map.class);
            Map<?, ?> resultData = (Map<?, ?>) response.getResultData();
            assertThat(resultData.get("status")).isEqualTo("UNAVAILABLE");
            assertThat(resultData.get("code")).isEqualTo("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
            assertThat(resultData.get("source")).isEqualTo("NO_QUOTE_SERVICE");
            assertThat(fixture.workerThreadName).startsWith("intent-sse");
            assertThat(fixture.workerActor).isEqualTo(factoryActor());
            verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @MethodSource("deniedCases")
    void asyncSupplierStreamRejectsUntrustedActorBeforePermissionGate(
            String route, String actorCase) throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        AuthenticatedActor current = switch (actorCase) {
            case "platform" -> new AuthenticatedActor(
                    AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, null, "platform_admin");
            case "platform-scoped" -> new AuthenticatedActor(
                    AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin");
            case "foreign-factory" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, USER_ID, "OTHER", ROLE);
            case "different-id" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, 99L, FACTORY, ROLE);
            default -> null;
        };
        try (ActorRequest ignored = actor(current)) {
            IntentExecuteResponse response = fixture.execute(
                    route, "client-spoof".equals(actorCase) || "missing-actor".equals(actorCase));

            assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
            assertThat(response.getMessage()).contains("绑定的工厂用户身份");
            assertThat(response.getMessage()).doesNotContain("需要权限:");
            verifyNoInteractions(fixture.toolDispatch, fixture.conversationMemoryService, fixture.conversationService);
            verify(fixture.intentPermissionGate, never()).check(any(), any(), anyString());
            verify(fixture.semanticCacheService, never()).cacheIntentResult(anyString(), anyString(), any());
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @MethodSource("sessionAdmissionCases")
    void allSessionEntrypointsRejectUnboundActorsBeforeFirstStateRead(String route, String kind) throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        if ("paging".equals(route)) fixture.configurePaginationContinuation();
        else fixture.configureSlotContinuation();
        AuthenticatedActor current = switch (kind) {
            case "platform" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, null, "platform_admin");
            case "platform-scoped" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin");
            case "foreign" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, "OTHER", ROLE);
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, FACTORY, ROLE);
            default -> null;
        };
        Map<String,Object> context = new HashMap<>();
        if ("spoof".equals(kind)) context.putAll(Map.of("userId",USER_ID,"factoryId",FACTORY,"userRole",ROLE));
        if ("selection".equals(route)) context.put("clarificationSelection",true);
        try (ActorRequest ignored = actor(current)) {
            var response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput("cancel".equals(route) ? "取消" : "paging".equals(route) ? "下一页" : INPUT)
                    .intentCode(List.of("explicit","selection","cancel").contains(route) ? SupplierComparisonReadIntent.CODE : null)
                    .sessionId("unknown".equals(route) ? "UNKNOWN-SESSION" : StreamingFixture.SESSION)
                    .context(context).build());
            assertThat(response.getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(fixture.conversationMemoryService,fixture.conversationService,fixture.toolDispatch);
        } finally { fixture.close(); }
    }

    static Stream<Arguments> sessionAdmissionCases() {
        return Stream.of("explicit","natural","paging","cancel","selection","unknown")
                .flatMap(route -> Stream.of("missing","platform","platform-scoped","foreign","different-id","spoof")
                        .map(kind -> Arguments.of(route,kind)));
    }

    static Stream<Arguments> deniedCases() {
        return Stream.of("missing-actor", "platform", "platform-scoped", "foreign-factory", "different-id", "client-spoof")
                .flatMap(actorCase -> Stream.of(
                        Arguments.of("explicit", actorCase), Arguments.of("natural", actorCase)));
    }

    @Test
    void explicitSupplierActorDenialHasNoSelectionSessionOrExecutionSideEffects() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configureSlotContinuation();
        Map<String, Object> context = new HashMap<>();
        context.put("clarificationSelection", true);
        context.put("clarificationCandidates", List.of(SupplierComparisonReadIntent.CODE));
        try (ActorRequest ignored = actor(null)) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).intentCode(SupplierComparisonReadIntent.CODE)
                    .sessionId(StreamingFixture.SESSION).context(context).build());

            assertThat(response.getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
            verify(fixture.pythonAiMatcherClient, after(250).never()).recordSelection(
                    anyString(), any(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(fixture.toolDispatch, fixture.dynamicToolSelectionService);
            verify(fixture.semanticCacheService, never()).queryCache(anyString(), anyString());
            verify(fixture.semanticCacheService, never()).cacheIntentResult(anyString(), anyString(), any());
            verify(fixture.intentPermissionGate, never()).check(any(), any(), anyString());
        } finally {
            fixture.close();
        }
    }

    @Test
    void explicitSupplierPermissionDenialHasNoSelectionSessionOrExecutionSideEffects() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configureSlotContinuation();
        fixture.configurePermissionDenied();
        Map<String, Object> context = new HashMap<>();
        context.put("clarificationSelection", true);
        context.put("clarificationCandidates", List.of(SupplierComparisonReadIntent.CODE));
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).intentCode(SupplierComparisonReadIntent.CODE)
                    .context(context).build());

            assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
            assertThat(response.getMessage()).contains("需要权限:");
            verifyNoInteractions(fixture.conversationService);
            verify(fixture.pythonAiMatcherClient, after(250).never()).recordSelection(
                    anyString(), any(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(fixture.toolDispatch);
            verify(fixture.semanticCacheService, never()).queryCache(anyString(), anyString());
            verify(fixture.semanticCacheService, never()).cacheIntentResult(anyString(), anyString(), any());
            verify(fixture.intentPermissionGate).check(any(), eq(USER_ID), eq(ROLE));
        } finally {
            fixture.close();
        }
    }

    @Test
    void naturalSupplierActorDenialPrecedesSkillAndCacheAdmission() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        Map<String, Object> context = new HashMap<>();
        context.put("recentTurns", List.of(Map.of("role", "user", "content", "上一句")));
        try (ActorRequest ignored = actor(null)) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).context(context).build());

            assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
            verifyNoInteractions(fixture.toolDispatch);
            verify(fixture.dynamicToolSelectionService, never()).trySkillRoute(
                    anyString(), anyString(), anyLong(), anyString());
            verify(fixture.dynamicToolSelectionService, never()).tryExplicitSkillRouteForIntent(
                    any(), anyString(), anyString(), anyLong(), anyString());
            verify(fixture.semanticCacheService, never()).queryCache(anyString(), anyString());
            verify(fixture.semanticCacheService, never()).cacheIntentResult(anyString(), anyString(), any());
            verify(fixture.intentPermissionGate, never()).check(any(), any(), anyString());
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof"})
    void untrustedFreshSseSkipsRealSlotFillingAdmission(String kind) throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        AIIntentConfig unrelated = AIIntentConfig.builder().id("slot-other")
                .intentCode("OTHER_SLOT").intentName("other slot").intentCategory("CRM")
                .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                .toolName("other_slot_tool").factoryId(FACTORY).build();
        ToolExecutor slotTool = mock(ToolExecutor.class);
        when(slotTool.getToolName()).thenReturn("other_slot_tool");
        when(slotTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(slotTool.getParametersSchema()).thenReturn(Map.of(
                "type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                "required", List.of("batchId")));
        when(fixture.toolRegistry.getExecutor("other_slot_tool")).thenReturn(Optional.of(slotTool));
        fixture.attachRealSlotFilling();
        fixture.recognizedIntent = unrelated;
        when(fixture.aiIntentService.hasPermission("OTHER_SLOT", ROLE)).thenReturn(true);
        when(fixture.aiIntentService.recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3),
                any(), eq(ROLE), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(unrelated).confidence(0.99d)
                        .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        AuthenticatedActor current = switch (kind) {
            case "platform", "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin");
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, FACTORY, ROLE);
            case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, "OTHER", ROLE);
            default -> null;
        };
        try (ActorRequest ignored = actor(current)) {
            if ("wrongtype".equals(kind)) ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                    .getRequest().setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                            Map.of("type", "FACTORY_USER", "id", USER_ID, "factoryId", FACTORY));
            IntentExecuteResponse response = fixture.execute("natural", null, "client-spoof".equals(kind));
            assertThat(response.getStatus()).isEqualTo("UNEXPECTED_DISPATCH");
            assertThat(response.getResultData()).isEqualTo(Map.of("source", "UNEXPECTED_DISPATCH"));
        } finally {
            fixture.close();
        }
        verify(fixture.aiIntentService).recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), isNull(), eq(ROLE), isNull(), any(), any(), any());
        verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
        verifyNoInteractions(fixture.realSessionRepository);
    }

    @Test
    void trustedFreshSseUsesRealSlotFillingAndCreatesOwnedParameterSession() throws Exception {
        StreamingFixture fixture = StreamingFixture.actualDispatch();
        ToolDispatchService observedDispatch = spy(fixture.toolDispatch);
        ReflectionTestUtils.setField(fixture.service, "toolDispatchService", observedDispatch);
        AIIntentConfig unrelated = AIIntentConfig.builder().id("slot-real")
                .intentCode("OTHER_SLOT").intentName("other slot").intentCategory("CRM")
                .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                .toolName("other_slot_tool").factoryId(FACTORY).build();
        ToolExecutor slotTool = mock(ToolExecutor.class);
        when(slotTool.getToolName()).thenReturn("other_slot_tool");
        when(slotTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(slotTool.getParametersSchema()).thenReturn(Map.of(
                "type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                "required", List.of("batchId")));
        when(fixture.toolRegistry.getExecutor("other_slot_tool")).thenReturn(Optional.of(slotTool));
        fixture.attachRealSlotFilling();
        fixture.recognizedIntent = unrelated;
        when(fixture.aiIntentService.hasPermission("OTHER_SLOT", ROLE)).thenReturn(true);
        when(fixture.aiIntentService.recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), eq(USER_ID), eq(ROLE), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(unrelated).confidence(0.99d)
                        .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.execute("natural", null, false);
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        } finally {
            fixture.close();
        }
        ArgumentCaptor<ConversationSession> savedCaptor = ArgumentCaptor.forClass(ConversationSession.class);
        verify(fixture.realSessionRepository).save(savedCaptor.capture());
        ConversationSession saved = savedCaptor.getValue();
        assertThat(saved.getFactoryId()).isEqualTo(FACTORY);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        verify(observedDispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof"})
    void sseRecognizerReceivesNoStateIdentityForEveryUntrustedActor(String kind) throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.attachUnrelatedReadTool();
        AIIntentConfig unrelated = AIIntentConfig.builder().id("sse-other")
                .intentCode("OTHER_READ").intentName("other read").intentCategory("CRM")
                .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                .toolName("other_read_tool").factoryId(FACTORY).build();
        fixture.recognizedIntent = unrelated;
        when(fixture.aiIntentService.hasPermission("OTHER_READ", ROLE)).thenReturn(true);
        when(fixture.aiIntentService.recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), any(), eq(ROLE), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(unrelated).confidence(0.99d)
                        .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        AuthenticatedActor current = switch (kind) {
            case "platform" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin");
            case "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin");
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, FACTORY, ROLE);
            case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, "OTHER", ROLE);
            default -> null;
        };
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(INPUT)
                .context("client-spoof".equals(kind)
                        ? new HashMap<>(Map.of("userId", USER_ID, "factoryId", FACTORY, "userRole", ROLE))
                        : new HashMap<>()).build();
        try (ActorRequest ignored = actor(current)) {
            if ("wrongtype".equals(kind)) {
                ((org.springframework.web.context.request.ServletRequestAttributes)
                        RequestContextHolder.currentRequestAttributes()).getRequest()
                        .setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                Map.of("type", "FACTORY_USER", "id", USER_ID, "factoryId", FACTORY));
            }
            IntentExecuteResponse response = fixture.executeRequest(request);
            assertThat(response.getStatus()).isEqualTo("UNEXPECTED_DISPATCH");
        } finally {
            fixture.close();
        }
        verify(fixture.aiIntentService).recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), isNull(), eq(ROLE), isNull(), any(), any(), any());
        verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
    }

    @Test
    void sseRecognizerRetainsExactFactoryUserSessionIdentity() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.attachUnrelatedReadTool();
        AIIntentConfig unrelated = AIIntentConfig.builder().id("sse-factory")
                .intentCode("OTHER_READ").intentName("other read").intentCategory("CRM")
                .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                .toolName("other_read_tool").factoryId(FACTORY).build();
        fixture.recognizedIntent = unrelated;
        when(fixture.aiIntentService.hasPermission("OTHER_READ", ROLE)).thenReturn(true);
        when(fixture.aiIntentService.recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), any(), eq(ROLE), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(unrelated).confidence(0.99d)
                        .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).sessionId(StreamingFixture.SESSION).context(new HashMap<>()).build());
            assertThat(response.getStatus()).isEqualTo("UNEXPECTED_DISPATCH");
        } finally {
            fixture.close();
        }
        verify(fixture.aiIntentService).recognizeIntentWithConfidence(
                eq(INPUT), eq(FACTORY), eq(3), eq(USER_ID), eq(ROLE), eq(StreamingFixture.SESSION), any(), any(), any());
    }

    @Test
    void paginationSupplierActorDenialPrecedesContinuationDispatchAndMemoryWrite() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configurePaginationContinuation();
        try (ActorRequest ignored = actor(null)) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("下一页").intentCode("PAGINATION_NEXT")
                            .sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
            verifyNoInteractions(fixture.toolDispatch);
            verify(fixture.conversationMemoryService, never()).addMessage(
                    anyString(), any(ConversationMessage.class));
            verify(fixture.conversationMemoryService, never()).updateLastIntent(anyString(), anyString());
            verify(fixture.intentPermissionGate, never()).check(any(), any(), anyString());
        } finally {
            fixture.close();
        }
    }

    @Test
    void slotSupplierActorDenialPrecedesAnswerCheckAndSessionMutation() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configureSlotContinuation();
        try (ActorRequest ignored = actor(null)) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("猪肉")
                            .sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
            verifyNoInteractions(fixture.toolDispatch, fixture.conversationMemoryService, fixture.conversationService);
            verify(fixture.intentPermissionGate, never()).check(any(), any(), anyString());
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"explicit", "natural"})
    void sameCodeWrongToolIsCanonicalizedToTheRegisteredSupplierTool(String route) throws Exception {
        StreamingFixture fixture = StreamingFixture.actualDispatch();
        fixture.recognizedIntent = config("wrong-tool", true, "supplier_wrong_tool");
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.execute(route, null, false);
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getResultData()).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("NO_QUOTE_SERVICE");
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurationCases")
    void inactiveOrForeignSupplierAuthorityFailsClosed(String route, String configuration) throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configureInvalidAuthority(configuration);
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.execute(route, null, false);
            assertThat(response.getStatus()).isEqualTo("NO_MATCH");
            assertThat(response.getMessage()).contains("当前不可用");
            verifyNoInteractions(fixture.toolDispatch);
        } finally {
            fixture.close();
        }
    }

    static Stream<Arguments> invalidConfigurationCases() {
        return Stream.of("inactive", "foreign-authority")
                .flatMap(configuration -> Stream.of(
                        Arguments.of("explicit", configuration), Arguments.of("natural", configuration)));
    }

    @Test
    void publicPaginationContinuationCanonicalizesSupplierAuthorityBeforeDispatch() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configurePaginationContinuation();
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("下一页").intentCode("PAGINATION_NEXT")
                            .sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("NO_QUOTE_SERVICE");
            org.mockito.ArgumentCaptor<IntentExecuteRequest> request =
                    org.mockito.ArgumentCaptor.forClass(IntentExecuteRequest.class);
            verify(fixture.toolDispatch).executeWithTool(any(), eq(FACTORY), request.capture(), any(),
                    eq(USER_ID), eq(ROLE), any());
            assertThat(request.getValue().getContext()).containsEntry("page", 2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void publicPaginationContinuationRejectsInactiveSupplierAuthorityBeforeDispatch() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configurePaginationContinuation();
        fixture.configureInvalidAuthority("inactive");
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("下一页").intentCode("PAGINATION_NEXT")
                            .sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("PAGINATION_REFUSED");
            assertThat(response.getMessage()).contains("不可用");
            verifyNoInteractions(fixture.toolDispatch);
        } finally {
            fixture.close();
        }
    }

    @Test
    void publicSlotContinuationCanonicalizesSupplierAuthorityBeforeRealToolDispatch() throws Exception {
        StreamingFixture fixture = StreamingFixture.actualDispatch();
        fixture.configureSlotContinuation();
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("猪肉").sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getMessage()).contains("未接入真实报价");
            assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("NO_QUOTE_SERVICE");
            verify(fixture.conversationService).continueConversation(
                    FACTORY, USER_ID, StreamingFixture.SESSION, "猪肉");
        } finally {
            fixture.close();
        }
    }

    @Test
    void publicSlotContinuationRejectsForeignSupplierAuthorityBeforeCanonicalTool() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        fixture.configureSlotContinuation();
        fixture.configureInvalidAuthority("foreign-authority");
        fixture.disableNaturalRecognition();
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeContinuation(
                    IntentExecuteRequest.builder().userInput("猪肉").sessionId(StreamingFixture.SESSION).build());

            assertThat(response.getStatus()).isEqualTo("NO_MATCH");
            verify(fixture.conversationService).cancelConversation(
                    FACTORY, USER_ID, StreamingFixture.SESSION);
            verifyNoInteractions(fixture.toolDispatch);
        } finally {
            fixture.close();
        }
    }

    @Test
    void platformStatelessUnrelatedIntentDoesNotReadConversationMemory() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        AIIntentConfig unrelated = AIIntentConfig.builder().id("other")
                .intentCode("OTHER_READ").intentName("other read").intentCategory("CRM")
                .businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                .toolName("other_read_tool").factoryId(FACTORY).requiredPermission("other:read")
                .isActive(true).build();
        ToolExecutor otherTool = mock(ToolExecutor.class);
        when(otherTool.getToolName()).thenReturn("other_read_tool");
        when(otherTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        when(fixture.toolRegistry.getExecutor("other_read_tool")).thenReturn(Optional.of(otherTool));
        when(fixture.toolRegistry.isToolEnabledForFactory(FACTORY, "other_read_tool")).thenReturn(true);
        fixture.recognizedIntent = unrelated;
        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin"))) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).context(Map.of()).build());

            assertThat(response.getStatus()).isEqualTo("UNEXPECTED_DISPATCH");
            verify(fixture.toolDispatch).executeWithTool(eq(otherTool), eq(FACTORY), any(), eq(unrelated),
                    eq(USER_ID), eq(ROLE), any());
            verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
        } finally {
            fixture.close();
        }
    }

    @Test
    void unboundUnknownSessionIsRejectedBeforeConversationLookup() throws Exception {
        StreamingFixture fixture = StreamingFixture.mockDispatch();
        try (ActorRequest ignored = actor(null)) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput("继续").intentCode("UNKNOWN_CONTINUATION")
                    .sessionId("unknown-session").build());

            assertThat(response.getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(fixture.conversationMemoryService, fixture.conversationService);
            verifyNoInteractions(fixture.toolDispatch);
        } finally {
            fixture.close();
        }
    }

    @Test
    void boundFactoryExplicitSupplierCanCancelOwnedSessionBeforeRealTool() throws Exception {
        StreamingFixture fixture = StreamingFixture.actualDispatch();
        fixture.configureSlotContinuation();
        Map<String, Object> context = new HashMap<>();
        context.put("clarificationSelection", true);
        context.put("clarificationCandidates", List.of(SupplierComparisonReadIntent.CODE));
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.executeRequest(IntentExecuteRequest.builder()
                    .userInput(INPUT).intentCode(SupplierComparisonReadIntent.CODE)
                    .sessionId(StreamingFixture.SESSION).context(context).build());

            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getMessage()).contains("未接入真实报价");
            verify(fixture.conversationService).cancelConversation(
                    FACTORY, USER_ID, StreamingFixture.SESSION);
        } finally {
            fixture.close();
        }
    }

    private static AIIntentConfig config(String id, boolean active, String toolName) {
        return AIIntentConfig.builder().id(id).intentCode(SupplierComparisonReadIntent.CODE)
                .intentName("supplier comparison").intentCategory("CRM").businessType("FACTORY")
                .sensitivityLevel("LOW").requiresApproval(false).toolName(toolName).factoryId(FACTORY)
                .requiredPermission("procurement:read_write,finance:read_write")
                .isActive(active).build();
    }

    private static AuthenticatedActor factoryActor() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, FACTORY, ROLE);
    }

    private static ActorRequest actor(AuthenticatedActor actor) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) {
            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        }
        RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        return new ActorRequest(previous);
    }

    private static final class ActorRequest implements AutoCloseable {
        private final RequestAttributes previous;

        private ActorRequest(RequestAttributes previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                RequestContextHolder.resetRequestAttributes();
            } else {
                RequestContextHolder.setRequestAttributes(previous);
            }
        }
    }

    private static final class StreamingFixture implements AutoCloseable {
        private static final String SESSION = "supplier-stream-session";
        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);
        private final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        private final ToolRegistry toolRegistry;
        private final AIIntentConfigRepository intentConfigs = mock(AIIntentConfigRepository.class);
        private final IntentPermissionGate intentPermissionGate = mock(IntentPermissionGate.class);
        private final BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        private final ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        private final ConversationService conversationService = mock(ConversationService.class);
        private ConversationSessionRepository realSessionRepository;
        private final DynamicToolSelectionService dynamicToolSelectionService = mock(DynamicToolSelectionService.class);
        private final PythonAiMatcherClient pythonAiMatcherClient = mock(PythonAiMatcherClient.class);
        private final SupplierService supplierService = mock(SupplierService.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final SupplierPriceComparisonTool supplierTool = new SupplierPriceComparisonTool();
        private final ToolDispatchService toolDispatch;
        private final ToolCallRedundancyService redundancy;
        private final SseStreamingService service;
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private volatile String workerThreadName;
        private volatile AuthenticatedActor workerActor;
        private AIIntentConfig recognizedIntent = config("recognized", true, SupplierComparisonReadIntent.TOOL);

        private StreamingFixture(ToolDispatchService toolDispatch, ToolRegistry toolRegistry,
                                 ToolCallRedundancyService redundancy) throws java.io.IOException {
            this.toolDispatch = toolDispatch;
            this.toolRegistry = toolRegistry;
            this.redundancy = redundancy;
            ReflectionTestUtils.setField(supplierTool, "supplierService", supplierService);
            ReflectionTestUtils.setField(supplierTool, "objectMapper", objectMapper);
            when(toolRegistry.getExecutor(SupplierComparisonReadIntent.TOOL)).thenReturn(Optional.of(supplierTool));
            when(toolRegistry.isToolEnabledForFactory(FACTORY, SupplierComparisonReadIntent.TOOL)).thenReturn(true);
            when(intentConfigs.findSupplierComparisonAuthority(SupplierComparisonReadIntent.CODE)).thenReturn(List.of());
            when(intentPermissionGate.check(any(), any(), anyString()))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            when(semanticCacheService.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            ConversationContext defaultOwner = ConversationContext.builder()
                    .sessionId(SESSION).factoryId(FACTORY).userId(USER_ID)
                    .recentMessages(new ArrayList<>()).build();
            when(conversationMemoryService.getOrCreateContext(eq(FACTORY), eq(USER_ID), isNull()))
                    .thenReturn(defaultOwner);
            when(conversationMemoryService.getOrCreateContext(eq(FACTORY), eq(USER_ID), eq(SESSION)))
                    .thenReturn(defaultOwner);
            when(conversationMemoryService.getContext(SESSION)).thenReturn(defaultOwner);
            when(aiIntentService.recognizeIntentWithConfidence(
                    eq(INPUT), eq(FACTORY), eq(3), any(), eq(ROLE), any(), any(), any(), any()))
                    .thenAnswer(invocation -> IntentMatchResult.builder()
                            .bestMatch(recognizedIntent).confidence(0.99d)
                            .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                            .requiresConfirmation(false)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                            .build());
            when(aiIntentService.getIntentByCode(FACTORY, SupplierComparisonReadIntent.CODE))
                    .thenAnswer(invocation -> Optional.of(recognizedIntent));

            service = org.mockito.Mockito.spy(new SseStreamingService(
                    aiIntentService, semanticCacheService, knowledgeBase,
                    mock(AnalysisRouterService.class), objectMapper, toolRegistry, toolDispatch,
                    dynamicToolSelectionService, mock(DashScopeClient.class),
                    mock(DashScopeConfig.class)));
            if (ReflectionUtils.findField(SseStreamingService.class, "intentConfigRepository") != null) {
                ReflectionTestUtils.setField(service, "intentConfigRepository", intentConfigs);
            }
            ReflectionTestUtils.setField(service, "intentPermissionGate", intentPermissionGate);
            ReflectionTestUtils.setField(service, "businessTypeGate", businessTypeGate);
            ReflectionTestUtils.setField(service, "conversationMemoryService", conversationMemoryService);
            ReflectionTestUtils.setField(service, "conversationService", conversationService);
            ReflectionTestUtils.setField(service, "pythonAiMatcherClient", pythonAiMatcherClient);
            doAnswer(invocation -> {
                String name = invocation.getArgument(1, String.class);
                events.add(new Event(name, invocation.getArgument(2)));
                if ("result".equals(name) || "complete".equals(name)) {
                    workerThreadName = Thread.currentThread().getName();
                    workerActor = AuthenticatedActor.fromCurrentExecution().orElse(null);
                }
                if ("complete".equals(name) || "error".equals(name)) {
                    terminal.countDown();
                }
                return null;
            }).when(service).sendSseEvent(any(), anyString(), any());
        }

        static StreamingFixture mockDispatch() throws java.io.IOException {
            ToolDispatchService dispatch = mock(ToolDispatchService.class);
            when(dispatch.executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(IntentExecuteResponse.builder().intentRecognized(true)
                            .intentCode(SupplierComparisonReadIntent.CODE).status("UNEXPECTED_DISPATCH")
                            .message("unauthorized path reached ToolDispatch")
                            .resultData(Map.of("source", "UNEXPECTED_DISPATCH")).build());
            return new StreamingFixture(dispatch, mock(ToolRegistry.class), mock(ToolCallRedundancyService.class));
        }

        static StreamingFixture actualDispatch() throws java.io.IOException {
            ToolRegistry registry = mock(ToolRegistry.class);
            ObjectMapper mapper = new ObjectMapper();
            ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
            when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
            when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
            ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
            when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
            ToolDispatchService dispatch = new ToolDispatchService(
                    registry, mapper, mock(DashScopeClient.class), redundancy,
                    mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                    mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                    mock(com.cretas.aims.service.ParameterExtractionLearningService.class));
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
            return new StreamingFixture(dispatch, registry, redundancy);
        }

        void configureInvalidAuthority(String configuration) {
            AIIntentConfig row = config("configured", !"inactive".equals(configuration), SupplierComparisonReadIntent.TOOL);
            AIIntentConfigRepository.SupplierComparisonAuthority authority =
                    mock(AIIntentConfigRepository.SupplierComparisonAuthority.class);
            when(authority.getId()).thenReturn(row.getId());
            when(authority.getFactoryId()).thenReturn(
                    "foreign-authority".equals(configuration) ? "OTHER" : FACTORY);
            when(authority.getActive()).thenReturn(row.getIsActive());
            when(authority.getDeleted()).thenReturn(false);
            when(intentConfigs.findSupplierComparisonAuthority(SupplierComparisonReadIntent.CODE))
                    .thenReturn(List.of(authority));
            when(intentConfigs.findByFactoryIdAndIntentCode(anyString(), eq(SupplierComparisonReadIntent.CODE)))
                    .thenReturn(Optional.of(row));
        }

        void configurePermissionDenied() {
            when(intentPermissionGate.check(any(), eq(USER_ID), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.denied("procurement:read_write"));
        }

        void configurePaginationContinuation() {
            SupplierPriceComparisonTool paginatedTool = spy(supplierTool);
            doReturn(Map.of("type", "object", "properties", Map.of(
                    "page", Map.of("type", "integer", "minimum", 1, "default", 1)),
                    "required", List.of())).when(paginatedTool).getParametersSchema();
            when(toolRegistry.getExecutor(SupplierComparisonReadIntent.TOOL))
                    .thenReturn(Optional.of(paginatedTool));
            Map<String, Object> pagination = new HashMap<>();
            pagination.put("intentCode", SupplierComparisonReadIntent.CODE);
            pagination.put("toolName", SupplierComparisonReadIntent.TOOL);
            pagination.put("queryInput", INPUT);
            pagination.put("page", 1);
            pagination.put("pageBase", 1);
            pagination.put("size", 20);
            pagination.put("hasMore", true);
            pagination.put("totalPages", 2);
            ConversationMessage assistant = ConversationMessage.builder()
                    .role(ConversationMessage.Role.ASSISTANT)
                    .metadata(Map.of("pagination", pagination)).build();
            ConversationContext owner = ConversationContext.builder()
                    .sessionId(SESSION).factoryId(FACTORY).userId(USER_ID)
                    .recentMessages(new ArrayList<>(List.of(assistant))).build();
            when(conversationMemoryService.getOrCreateContext(FACTORY, USER_ID, SESSION)).thenReturn(owner);
            when(conversationMemoryService.getContext(SESSION)).thenReturn(owner);
            ReflectionTestUtils.setField(service, "conversationMemoryService", conversationMemoryService);
            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(unavailableResponse());
        }

        void configureSlotContinuation() {
            ConversationSession open = ConversationSession.createForParameterCollection(
                    FACTORY, USER_ID, SupplierComparisonReadIntent.CODE,
                    List.of(ConversationSession.RequiredParameter.builder()
                            .name("materialType").label("原料类型").type("string").collected(false).build()),
                    "请输入原料类型");
            open.setSessionId(SESSION);
            ConversationSession completed = ConversationSession.createForParameterCollection(
                    FACTORY, USER_ID, SupplierComparisonReadIntent.CODE,
                    List.of(ConversationSession.RequiredParameter.builder()
                            .name("materialType").label("原料类型").type("string").collected(false).build()),
                    "请输入原料类型");
            completed.setSessionId(SESSION);
            completed.addCollectedParameter("materialType", "猪肉");
            when(conversationService.getSession(FACTORY, USER_ID, SESSION))
                    .thenReturn(Optional.of(open), Optional.of(completed));
            when(conversationService.continueConversation(FACTORY, USER_ID, SESSION, "猪肉"))
                    .thenReturn(ConversationService.ConversationResponse.builder()
                            .sessionId(SESSION).status(ConversationSession.SessionStatus.COMPLETED)
                            .completed(true).message("参数已补齐").build());
            ReflectionTestUtils.setField(service, "conversationService", conversationService);
        }

        void attachUnrelatedReadTool() {
            ToolExecutor read = mock(ToolExecutor.class);
            when(read.getToolName()).thenReturn("other_read_tool");
            when(read.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
            when(read.getParametersSchema()).thenReturn(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            when(toolRegistry.getExecutor("other_read_tool")).thenReturn(Optional.of(read));
        }

        void attachRealSlotFilling() {
            realSessionRepository = mock(ConversationSessionRepository.class);
            Map<String, ConversationSession> stored = new HashMap<>();
            when(realSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
                ConversationSession session = invocation.getArgument(0);
                if (session.getSessionId() == null) session.setSessionId(UUID.randomUUID().toString());
                if (session.getLastActiveAt() == null) session.setLastActiveAt(LocalDateTime.now());
                stored.put(session.getSessionId(), session);
                return session;
            });
            when(realSessionRepository.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                    .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0)))
                            .filter(session -> invocation.getArgument(1, String.class).equals(session.getFactoryId()))
                            .filter(session -> invocation.getArgument(2, Long.class).equals(session.getUserId())));
            ConversationServiceImpl conversations = new ConversationServiceImpl(
                    realSessionRepository, mock(AIIntentConfigRepository.class), null, mock(DashScopeClient.class));
            ReflectionTestUtils.setField(conversations, "maxRounds", 5);
            ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
            IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
            slotConfiguration.init();
            SlotFillingServiceImpl slotFilling = new SlotFillingServiceImpl(slotConfiguration, toolRegistry,
                    conversations, mock(com.cretas.aims.service.ParameterExtractionLearningService.class));
            ReflectionTestUtils.setField(service, "slotFillingService", slotFilling);
            ReflectionTestUtils.setField(service, "conversationService", conversations);
        }

        void disableNaturalRecognition() {
            when(aiIntentService.recognizeIntentWithConfidence(
                    anyString(), eq(FACTORY), eq(3), any(), eq(ROLE), any(), any(), any(), any()))
                    .thenReturn(IntentMatchResult.builder().userInput("猪肉").confidence(0.0d)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        }

        private static IntentExecuteResponse unavailableResponse() {
            return IntentExecuteResponse.builder().intentRecognized(true)
                    .intentCode(SupplierComparisonReadIntent.CODE).status("FAILED")
                    .message("供应商价格比较暂不可用：未接入真实报价")
                    .resultData(Map.of("status", "UNAVAILABLE",
                            "code", "SUPPLIER_PRICE_COMPARISON_UNAVAILABLE",
                            "source", "NO_QUOTE_SERVICE")).build();
        }

        IntentExecuteResponse execute(String route, boolean clientSpoof) throws InterruptedException {
            return execute(route, null, clientSpoof);
        }

        IntentExecuteResponse execute(String route, String ignored, boolean clientSpoof) throws InterruptedException {
            Map<String, Object> context = new HashMap<>();
            if (clientSpoof) {
                context.put("userId", USER_ID);
                context.put("userRole", ROLE);
                context.put("factoryId", FACTORY);
            }
            IntentExecuteRequest request = IntentExecuteRequest.builder()
                    .userInput(INPUT)
                    .intentCode("explicit".equals(route) ? SupplierComparisonReadIntent.CODE : null)
                    .context(context)
                    .build();
            service.executeStream(FACTORY, request, USER_ID, ROLE);
            assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
            return events.stream().filter(event -> "result".equals(event.name()))
                    .map(Event::data).filter(IntentExecuteResponse.class::isInstance)
                    .map(IntentExecuteResponse.class::cast).reduce((first, last) -> last).orElseThrow();
        }

        IntentExecuteResponse executeContinuation(IntentExecuteRequest request) throws InterruptedException {
            return executeRequest(request);
        }

        IntentExecuteResponse executeRequest(IntentExecuteRequest request) throws InterruptedException {
            service.executeStream(FACTORY, request, USER_ID, ROLE);
            assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
            return events.stream().filter(event -> "result".equals(event.name()))
                    .map(Event::data).filter(IntentExecuteResponse.class::isInstance)
                    .map(IntentExecuteResponse.class::cast).reduce((first, last) -> last).orElseThrow();
        }

        @Override
        public void close() {
            service.destroy();
        }
    }

    private record Event(String name, Object data) { }
}

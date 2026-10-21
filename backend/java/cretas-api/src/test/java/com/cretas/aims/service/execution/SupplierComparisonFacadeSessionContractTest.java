package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierComparisonFacadeSessionContractTest {
    private static final String FACTORY = "F006";
    private static final long USER = 42L;
    private static final String SESSION = "supplier-session-1";

    @Test
    void executeAndPreviewReadRoundOnlyForExactTrustedFactoryContext() {
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        IntentExecutorServiceImpl facade = facade(orchestrator, memory);
        when(orchestrator.execute(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> IntentExecuteResponse.builder().status("FAILED").build());
        when(memory.getContext(SESSION)).thenReturn(context(FACTORY, USER, SESSION, 3));

        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(USER, FACTORY))) {
            IntentExecuteResponse execute = facade.execute(FACTORY, request(), USER, "procurement_manager");
            IntentExecuteResponse preview = facade.preview(FACTORY, request(), USER, "procurement_manager");
            assertThat(execute.getConversationRound()).isEqualTo(2);
            assertThat(preview.getConversationRound()).isEqualTo(2);
        }
        verify(memory, times(2)).getContext(SESSION);
    }

    @Test
    void missingPlatformWrongUserWrongTenantAndSpoofCannotReadMemory() {
        for (AuthenticatedActor current : new AuthenticatedActor[] {
                null,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER, FACTORY, "platform_admin"),
                actor(99L, FACTORY),
                actor(USER, "OTHER")
        }) {
            ConversationMemoryService memory = mock(ConversationMemoryService.class);
            IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
            when(orchestrator.execute(anyString(), any(), anyLong(), anyString()))
                    .thenAnswer(invocation -> IntentExecuteResponse.builder().status("FAILED").build());
            IntentExecutorServiceImpl facade = facade(orchestrator, memory);
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(current)) {
                IntentExecuteResponse response = facade.execute(
                        FACTORY, request(), USER, "procurement_manager");
                assertThat(response.getConversationRound()).isNull();
                IntentExecuteResponse preview = facade.preview(
                        FACTORY, request(), USER, "procurement_manager");
                assertThat(preview.getConversationRound()).isNull();
            }
            verifyNoInteractions(memory);
        }

        ConversationMemoryService spoofMemory = mock(ConversationMemoryService.class);
        IntentExecutionOrchestrator spoofOrchestrator = mock(IntentExecutionOrchestrator.class);
        when(spoofOrchestrator.execute(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> IntentExecuteResponse.builder().status("FAILED").build());
        IntentExecutorServiceImpl spoofFacade = facade(spoofOrchestrator, spoofMemory);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(null)) {
            IntentExecuteResponse response = spoofFacade.execute(
                    FACTORY, spoofRequest(), USER, "procurement_manager");
            assertThat(response.getConversationRound()).isNull();
            IntentExecuteResponse preview = spoofFacade.preview(
                    FACTORY, spoofRequest(), USER, "procurement_manager");
            assertThat(preview.getConversationRound()).isNull();
        }
        verifyNoInteractions(spoofMemory);
    }

    @Test
    void foreignSessionOwnerAndSessionlessPlatformStayWithoutRound() {
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        IntentExecutorServiceImpl facade = facade(orchestrator, memory);
        when(orchestrator.execute(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> IntentExecuteResponse.builder().status("FAILED").build());
        for (ConversationContext foreign : new ConversationContext[] {
                context("OTHER", USER, SESSION, 3),
                context(FACTORY, 99L, SESSION, 3),
                context(FACTORY, USER, "other-session", 3)
        }) {
            when(memory.getContext(SESSION)).thenReturn(foreign);
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(USER, FACTORY))) {
                assertThat(facade.execute(FACTORY, request(), USER, "procurement_manager")
                        .getConversationRound()).isNull();
                assertThat(facade.preview(FACTORY, request(), USER, "procurement_manager")
                        .getConversationRound()).isNull();
            }
        }
        verify(memory, times(6)).getContext(SESSION);

        ConversationMemoryService platformMemory = mock(ConversationMemoryService.class);
        IntentExecutionOrchestrator platformOrchestrator = mock(IntentExecutionOrchestrator.class);
        when(platformOrchestrator.execute(anyString(), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> IntentExecuteResponse.builder().status("FAILED").build());
        IntentExecutorServiceImpl platformFacade = facade(platformOrchestrator, platformMemory);
        IntentExecuteRequest sessionless = IntentExecuteRequest.builder()
                .userInput("供应商价格比较").context(new java.util.HashMap<>()).build();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER, null, "platform_admin"))) {
            assertThat(platformFacade.execute(FACTORY, sessionless, USER, "platform_admin")
                    .getConversationRound()).isNull();
        }
        verifyNoInteractions(platformMemory);
    }

    @Test
    void signedJwtRunsActualInterceptorControllerFacadeOrchestratorAndSupplierTool() throws Exception {
        SupplierComparisonDispatchContractTest dispatchTest = new SupplierComparisonDispatchContractTest();
        SupplierPriceComparisonTool tool = ReflectionTestUtils.invokeMethod(dispatchTest, "realSupplierTool");
        Object dispatchFixture = ReflectionTestUtils.invokeMethod(
                dispatchTest, "newDispatch", false, java.util.Optional.empty());
        ToolDispatchService dispatch = (ToolDispatchService) ReflectionTestUtils.getField(dispatchFixture, "dispatch");
        Object orchestratorFixture = ReflectionTestUtils.invokeMethod(
                dispatchTest, "newOrchestrator", tool, dispatchFixture,
                IntentPermissionGate.PermissionCheck.allowed(), java.util.Optional.empty());
        IntentExecutionOrchestrator orchestrator = (IntentExecutionOrchestrator) ReflectionTestUtils.getField(orchestratorFixture, "orchestrator");
        SupplierPriceComparisonTool observedTool = spy(tool);
        ToolRegistry orchestratorRegistry = (ToolRegistry) ReflectionTestUtils.getField(orchestrator, "toolRegistry");
        when(orchestratorRegistry.getExecutor(observedTool.getToolName()))
                .thenReturn(java.util.Optional.of(observedTool));
        IntentPermissionGate actorGate = mock(IntentPermissionGate.class);
        when(actorGate.check(any(), any(), any())).thenAnswer(invocation -> {
            AuthenticatedActor current = AuthenticatedActor.fromCurrentExecution().orElse(null);
            return current != null && current.type() == AuthenticatedActorType.FACTORY_USER
                    && USER == current.id() && FACTORY.equals(current.factoryId())
                    ? IntentPermissionGate.PermissionCheck.allowed()
                    : IntentPermissionGate.PermissionCheck.denied("procurement:read_write");
        });
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", actorGate);

        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.getContext(SESSION)).thenReturn(context(FACTORY, USER, SESSION, 3));
        IntentExecutorServiceImpl facade = facade(orchestrator, memory);
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "nonproduction-contract-secret-for-typed-identity-20261016");
        ReflectionTestUtils.setField(jwt, "expiration", 3600000L);
        UserRepository users = mock(UserRepository.class);
        User active = new User();
        active.setId(USER);
        active.setFactoryId(FACTORY);
        active.setIsActive(true);
        active.setRoleCode("procurement_manager");
        when(users.findById(USER)).thenReturn(java.util.Optional.of(active));
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "userRepository", users);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", mock(FactoryRepository.class));
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        AIIntentConfigController controller = new AIIntentConfigController(
                null, facade, null, null, null, jwt, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
        String factoryToken = jwt.generateToken(USER, FACTORY, "buyer", "procurement_manager");
        IntentExecuteRequest httpRequest = request();
        httpRequest.setIntentCode(SupplierComparisonReadIntent.CODE);
        String body = new ObjectMapper().writeValueAsString(httpRequest);

        mvc.perform(post("/api/mobile/F006/ai-intents/execute")
                        .header("Authorization", "Bearer " + factoryToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE", "NO_QUOTE_SERVICE"));
        verify(observedTool).execute(any(), anyMap());
        verify(memory).getContext(SESSION);

        clearInvocations(observedTool, memory);
        String platformToken = jwt.generatePlatformToken(USER, "platform-collision", "platform_admin");
        mvc.perform(post("/api/mobile/F006/ai-intents/execute")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("NO_PERMISSION"));
        verify(observedTool, never()).execute(any(), anyMap());
        verifyNoInteractions(memory);

        User foreign = new User();
        foreign.setId(USER);
        foreign.setFactoryId("OTHER");
        foreign.setIsActive(true);
        when(users.findById(USER)).thenReturn(java.util.Optional.of(foreign));
        String foreignToken = jwt.generateToken(USER, "OTHER", "buyer", "procurement_manager");
        mvc.perform(post("/api/mobile/F006/ai-intents/execute")
                        .header("Authorization", "Bearer " + foreignToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/mobile/F006/ai-intents/execute")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedJwtRunsActualControllerFacadeAndRealStreamingSupplierWorker() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String factory = "F-SUP";
        long user = 42L;
        String requestBody = mapper.writeValueAsString(IntentExecuteRequest.builder()
                .userInput("请比较供应商价格")
                .intentCode("SUPPLIER_PRICE_COMPARISON")
                .context(new java.util.HashMap<>()).build());

        Object positiveFixture = actualStreamingFixture();
        try {
            SseStreamingService realSse = (SseStreamingService) ReflectionTestUtils.getField(positiveFixture, "service");
            configureStreamingActorGate(positiveFixture, user, factory);
            ConversationMemoryService memory = mock(ConversationMemoryService.class);
            IntentExecutorServiceImpl realFacade = new IntentExecutorServiceImpl(
                    mock(IntentExecutionOrchestrator.class), realSse,
                    mock(MultiIntentExecutionService.class), memory);
            JwtUtil jwt = streamingJwt();
            UserRepository users = activeUserRepository(user, factory);
            MockMvc mvc = streamingMvc(realFacade, jwt, users);

            org.springframework.test.web.servlet.MvcResult initial = mvc.perform(post(
                            "/api/mobile/F-SUP/ai-intents/execute/stream")
                            .header("Authorization", "Bearer " + jwt.generateToken(
                                    user, factory, "buyer", "procurement_manager"))
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                            .asyncStarted()).andReturn();
            assertThat(((java.util.concurrent.CountDownLatch)
                    ReflectionTestUtils.getField(positiveFixture, "terminal"))
                    .await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(asyncDispatch(initial));
            assertStreamingUnavailable(positiveFixture, factory, user);
            verifyNoInteractions(ReflectionTestUtils.getField(positiveFixture, "supplierService"));
            verifyNoInteractions(memory);
        } finally {
            ((AutoCloseable) positiveFixture).close();
        }

        Object platformFixture = actualStreamingFixture();
        try {
            SseStreamingService realSse = (SseStreamingService) ReflectionTestUtils.getField(platformFixture, "service");
            configureStreamingActorGate(platformFixture, user, factory);
            ConversationMemoryService memory = mock(ConversationMemoryService.class);
            IntentExecutorServiceImpl realFacade = new IntentExecutorServiceImpl(
                    mock(IntentExecutionOrchestrator.class), realSse,
                    mock(MultiIntentExecutionService.class), memory);
            JwtUtil jwt = streamingJwt();
            MockMvc mvc = streamingMvc(realFacade, jwt, activeUserRepository(user, factory));
            org.springframework.test.web.servlet.MvcResult initial = mvc.perform(post(
                            "/api/mobile/F-SUP/ai-intents/execute/stream")
                            .header("Authorization", "Bearer " + jwt.generatePlatformToken(
                                    user, "platform-collision", "platform_admin"))
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                            .asyncStarted()).andReturn();
            assertThat(((java.util.concurrent.CountDownLatch)
                    ReflectionTestUtils.getField(platformFixture, "terminal"))
                    .await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(asyncDispatch(initial));
            boolean platformDenied = false;
            for (Object event : streamingEvents(platformFixture)) {
                if (!"result".equals(ReflectionTestUtils.getField(event, "name"))) continue;
                Object data = ReflectionTestUtils.getField(event, "data");
                if (data instanceof IntentExecuteResponse response) {
                    assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
                    platformDenied = true;
                }
            }
            verifyNoInteractions(memory);
            assertThat(platformDenied).isTrue();
            verifyNoInteractions(ReflectionTestUtils.getField(platformFixture, "conversationMemoryService"),
                    ReflectionTestUtils.getField(platformFixture, "conversationService"));
            verifyNoInteractions(ReflectionTestUtils.getField(platformFixture, "redundancy"));
            verifyNoInteractions(ReflectionTestUtils.getField(platformFixture, "supplierService"));
        } finally {
            ((AutoCloseable) platformFixture).close();
        }
    }

    private Object actualStreamingFixture() throws Exception {
        Class<?> fixtureClass = Class.forName(
                "com.cretas.aims.service.execution.SupplierComparisonStreamingContractTest$StreamingFixture");
        return ReflectionTestUtils.invokeMethod(fixtureClass, "actualDispatch");
    }

    private void configureStreamingActorGate(Object fixture, long user, String factory) {
        IntentPermissionGate gate = (IntentPermissionGate) ReflectionTestUtils.getField(fixture, "intentPermissionGate");
        when(gate.check(any(), any(), anyString())).thenAnswer(invocation -> {
            AuthenticatedActor current = AuthenticatedActor.fromCurrentExecution().orElse(null);
            return current != null && current.type() == AuthenticatedActorType.FACTORY_USER
                    && user == current.id() && factory.equals(current.factoryId())
                    ? IntentPermissionGate.PermissionCheck.allowed()
                    : IntentPermissionGate.PermissionCheck.denied("procurement:read_write");
        });
    }

    private JwtUtil streamingJwt() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "nonproduction-contract-secret-for-typed-identity-20261016");
        ReflectionTestUtils.setField(jwt, "expiration", 3600000L);
        return jwt;
    }

    private UserRepository activeUserRepository(long user, String factory) {
        UserRepository users = mock(UserRepository.class);
        User row = new User();
        row.setId(user);
        row.setFactoryId(factory);
        row.setIsActive(true);
        row.setRoleCode("procurement_manager");
        when(users.findById(user)).thenReturn(java.util.Optional.of(row));
        return users;
    }

    private MockMvc streamingMvc(IntentExecutorServiceImpl facade, JwtUtil jwt, UserRepository users) {
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "userRepository", users);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", mock(FactoryRepository.class));
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        AIIntentConfigController controller = new AIIntentConfigController(
                null, facade, null, null, null, jwt, null);
        return MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
    }

    private void assertStreamingUnavailable(Object fixture, String factory, long user) {
        assertThat(ReflectionTestUtils.getField(fixture, "workerThreadName"))
                .asString().startsWith("intent-sse");
        assertThat(ReflectionTestUtils.getField(fixture, "workerActor"))
                .isEqualTo(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,
                        user, factory, "procurement_manager"));
        boolean found = false;
        for (Object event : streamingEvents(fixture)) {
            if (!"result".equals(ReflectionTestUtils.getField(event, "name"))) continue;
            Object data = ReflectionTestUtils.getField(event, "data");
            if (!(data instanceof IntentExecuteResponse response)) continue;
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getResultData()).isInstanceOf(Map.class);
            Map<?, ?> result = (Map<?, ?>) response.getResultData();
            assertThat(result.get("status")).isEqualTo("UNAVAILABLE");
            assertThat(result.get("code")).isEqualTo("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
            assertThat(result.get("source")).isEqualTo("NO_QUOTE_SERVICE");
            found = true;
        }
        assertThat(found).isTrue();
    }

    private java.util.List<?> streamingEvents(Object fixture) {
        return (java.util.List<?>) ReflectionTestUtils.getField(fixture, "events");
    }

    private IntentExecutorServiceImpl facade(IntentExecutionOrchestrator orchestrator,
                                             ConversationMemoryService memory) {
        return new IntentExecutorServiceImpl(orchestrator, mock(SseStreamingService.class),
                mock(MultiIntentExecutionService.class), memory);
    }

    private IntentExecuteRequest request() {
        return IntentExecuteRequest.builder().userInput("供应商价格比较")
                .sessionId(SESSION).context(new java.util.HashMap<>()).build();
    }

    private IntentExecuteRequest spoofRequest() {
        return IntentExecuteRequest.builder().userInput("供应商价格比较")
                .sessionId(SESSION)
                .context(java.util.Map.of("userId", USER, "factoryId", FACTORY, "sessionId", SESSION))
                .build();
    }

    private ConversationContext context(String factory, long user, String session, int messages) {
        return ConversationContext.builder().factoryId(factory).userId(user).sessionId(session)
                .messageCount(messages).build();
    }

    private AuthenticatedActor actor(long id, String factory) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, factory, "procurement_manager");
    }
}

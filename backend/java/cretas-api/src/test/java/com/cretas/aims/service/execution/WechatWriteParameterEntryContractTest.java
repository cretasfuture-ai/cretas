package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workdesk.WechatRecordCreateTool;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.dto.ai.IntentConfirmationRequest;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WechatRecordRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.WechatRecordService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.service.impl.SlotFillingServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.testsupport.ToolRegistryStubs;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatWriteParameterEntryContractTest {
    private static final String FACTORY = "F006";
    private static final long USER = 71001L;
    private static final String ROLE = "factory_super_admin";
    private static final String INTENT = "WECHAT_CREATE";
    private static final String CUSTOMER = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    private static final String CONTENT = "合成测试内部备注";
    private static final String INPUT = "补录一条微信内部备注，客户编号" + CUSTOMER + "，消息内容：" + CONTENT;
    private static final String FREE_FORM = "给客户 " + CUSTOMER + " 写一条微信内部备注：" + CONTENT;
    private static final Map<String, Object> PARAMETERS = Map.of(
            "customerId", CUSTOMER, "direction", "INTERNAL", "messageContent", CONTENT);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DashScopeClient model = mock(DashScopeClient.class);
    private final AIIntentService intents = mock(AIIntentService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WechatRecordRepository recordRepository = mock(WechatRecordRepository.class);
    private final WechatRecordService records = new WechatRecordService(recordRepository);
    private final List<WechatRecord> savedRecords = new ArrayList<>();
    private final AtomicReference<IntentPreviewToken> tokenRow = new AtomicReference<>();
    private final IntentPreviewTokenRepository tokenRepository = mock(IntentPreviewTokenRepository.class);
    private final PreviewTokenServiceImpl tokens = new PreviewTokenServiceImpl(tokenRepository, mapper);
    private final WechatRecordCreateTool tool = new WechatRecordCreateTool();
    private final AIIntentConfig intent = AIIntentConfig.builder().intentCode(INTENT).intentName("补录微信记录")
            .intentCategory("DATA_OP").sensitivityLevel("MEDIUM").businessType("COMMON")
            .toolName(tool.getToolName()).requiredPermission("sales:read_write").isActive(true).build();
    private final AtomicReference<IntentExecuteResponse> result = new AtomicReference<>();
    private final CountDownLatch complete = new CountDownLatch(1);
    private SseStreamingService stream;
    private ToolDispatchService dispatch;
    private SlotFillingServiceImpl slots;
    private AIIntentConfigController controller;
    private Map<String, Object> grounded = Map.of();

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry registry = ToolRegistryStubs.allToolsEnabled();
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        when(intents.getIntentByCode(FACTORY, INTENT)).thenReturn(Optional.of(intent));
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "wechatRecordService", records);
        ReflectionTestUtils.setField(tool, "wechatRecordRepository", recordRepository);
        ReflectionTestUtils.setField(tool, "customerRepository", mock(CustomerRepository.class));
        when(recordRepository.save(any(WechatRecord.class))).thenAnswer(call -> {
            WechatRecord row = call.getArgument(0);
            row.setId(1L + savedRecords.size());
            savedRecords.add(row);
            return row;
        });
        when(recordRepository.countByFactoryIdAndCustomerIdAndDeletedAtIsNull(FACTORY, CUSTOMER))
                .thenAnswer(call -> savedRecords.stream()
                        .filter(r -> FACTORY.equals(r.getFactoryId()) && CUSTOMER.equals(r.getCustomerId())).count());

        var learning = mock(ParameterExtractionLearningService.class);
        dispatch = spy(new ToolDispatchService(registry, mapper, model, mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class), learning));
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> accepted = (Map<String, Object>) call.callRealMethod();
            grounded = Map.copyOf(accepted);
            return accepted;
        }).when(dispatch).extractParametersWithLLM(eq(FACTORY), anyString(), eq(tool), anyMap());
        var guard = new WriteGuardService();
        ReflectionTestUtils.setField(dispatch, "writeGuardService", guard);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", tokens);
        var owner = new User();
        owner.setId(USER);
        owner.setFactoryId(FACTORY);
        owner.setRoleCode(ROLE);
        var users = mock(UserRepository.class);
        when(users.findById(USER)).thenReturn(Optional.of(owner));
        when(permissions.hasAnyPermission(eq(owner), eq("sales:read_write"))).thenReturn(true);
        var rbac = new ToolRbacGuard();
        ReflectionTestUtils.setField(rbac, "permissionService", permissions);
        ReflectionTestUtils.setField(rbac, "userRepository", users);
        var enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", rbac);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
        var intentGate = new IntentPermissionGate();
        ReflectionTestUtils.setField(intentGate, "rbacGuard", rbac);

        var configuration = new IntentSlotConfiguration();
        configuration.init();
        var conversations = mock(ConversationService.class);
        slots = spy(new SlotFillingServiceImpl(configuration, registry, conversations, learning));
        var cache = mock(SemanticCacheService.class);
        when(cache.queryCache(eq(FACTORY), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        var knowledge = mock(IntentKnowledgeBase.class);
        when(knowledge.detectQuestionType(anyString())).thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        stream = spy(new SseStreamingService(intents, cache, knowledge, mock(AnalysisRouterService.class),
                mapper, registry, dispatch, mock(DynamicToolSelectionService.class), model, new DashScopeConfig()));
        var domain = mock(BusinessTypeGate.class);
        when(domain.resolveDomain(anyString())).thenReturn("FACTORY");
        when(domain.check(anyString(), eq(intent))).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(stream, "businessTypeGate", domain);
        ReflectionTestUtils.setField(stream, "intentPermissionGate", intentGate);
        ReflectionTestUtils.setField(stream, "writeGuardService", guard);
        ReflectionTestUtils.setField(stream, "slotFillingService", slots);
        ReflectionTestUtils.setField(stream, "conversationService", conversations);
        doAnswer(call -> {
            String event = call.getArgument(1);
            if ("result".equals(event)) {
                result.set(call.getArgument(2));
            }
            if ("complete".equals(event) || "error".equals(event)) {
                complete.countDown();
            }
            return null;
        }).when(stream).sendSseEvent(any(), anyString(), any());

        var orchestrator = OrdinaryEntryPipeline.attachTurnSteps(stream);
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", tokens);
        var configs = mock(IntentConfigManagementService.class);
        when(configs.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", configs);
        var executor = new IntentExecutorServiceImpl(orchestrator, stream, null, null);
        var jwt = mock(JwtUtil.class);
        when(jwt.getUserIdFromToken("synthetic-owner")).thenReturn(USER);
        when(jwt.getUserIdFromToken("synthetic-other")).thenReturn(USER + 1);
        when(jwt.getRoleFromToken(anyString())).thenReturn(ROLE);
        controller = new AIIntentConfigController(intents, executor, null, null, learning, jwt, null);
        stubTokenStorage();
    }

    @AfterEach
    void stopWorker() {
        if (stream != null) {
            stream.destroy();
        }
    }

    @Test
    void recognitionClarificationReturnsBeforeAnyParameterExtraction() throws Exception {
        recognize(true);

        String ambiguous = "给那个客户补录一条微信内部备注，消息内容：" + CONTENT;
        assertThat(execute(ambiguous).getStatus()).isEqualTo("NEED_CLARIFICATION");
        verify(intents).recognizeIntentWithConfidence(eq(ambiguous), eq(FACTORY), eq(3),
                eq(USER), eq(ROLE), isNull(), any(), any(), anyList());
        verifyNoInteractions(model);
        verifyNoInteractions(slots, recordRepository);
        verify(dispatch, never()).extractParametersWithLLM(any(), any(), any(), anyMap());
    }

    @Test
    void completeInternalNoteReachesWriteConfirmationWithUncertainRecognition() throws Exception {
        recognize(true);

        var response = execute(INPUT);
        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(metadata(response, "previewParams")).isEqualTo(mapper.valueToTree(PARAMETERS));
        verify(slots).checkAndStartSlotFilling(eq(FACTORY), eq(USER), same(intent), any(), any());
        verifyNoInteractions(model, recordRepository);
    }

    @Test
    void conflictingCustomerContextRequiresClarificationEvenWithConfidentRecognition() throws Exception {
        recognize(false);

        var response = execute(IntentExecuteRequest.builder().userInput(INPUT)
                .context(Map.of("customerId", "fedcbafe-dcba-4fed-8cba-fedcbafedcba")).build());
        assertPreflightStop(response, "NEED_CLARIFICATION");
    }

    @Test
    void readModeStillBlocksTheCompleteWriteCommand() throws Exception {
        recognize(true);

        assertPreflightStop(execute(IntentExecuteRequest.builder().userInput(INPUT).mode("READ").build()),
                "READ_MODE_WRITE_BLOCKED");
    }

    @Test
    void intentPermissionStillBlocksTheCompleteWriteCommand() throws Exception {
        recognize(true);
        when(permissions.hasAnyPermission(any(User.class), eq("sales:read_write"))).thenReturn(false);

        assertPreflightStop(execute(INPUT), "NO_PERMISSION");
    }

    @Test
    void approvalStillBlocksTheCompleteWriteCommand() throws Exception {
        recognize(true);
        intent.setRequiresApproval(true);

        assertPreflightStop(execute(INPUT), "PENDING_APPROVAL");
    }

    @Test
    void missingModelOutputReachesSlotFilling() throws Exception {
        recognize(false);
        when(model.chatWithTools(anyString(), anyString(), anyList())).thenReturn(mock(ChatCompletionResponse.class));

        assertMissing(execute(FREE_FORM), "customerId", "direction", "messageContent");
        verify(model).chatWithTools(anyString(), anyString(), anyList());
        assertThat(grounded).isEmpty();
    }

    @Test
    void ungroundedNumericArgumentIsDroppedBeforeSlotFilling() throws Exception {
        recognize(false);
        modelReturns(Map.of("customerId", 94752691, "direction", "INTERNAL", "messageContent", CONTENT));

        assertMissing(execute(FREE_FORM), "customerId");
        assertThat(grounded).containsOnlyKeys("direction", "messageContent");
    }

    @Test
    void presentButBlankArgumentIsStillMissingAtSlotFilling() throws Exception {
        recognize(false);
        modelReturns(Map.of("customerId", CUSTOMER, "direction", "INTERNAL", "messageContent", "   "));

        assertMissing(execute(FREE_FORM), "messageContent");
        assertThat(grounded).containsOnlyKeys(PARAMETERS.keySet());
    }

    @Test
    void completeGroundedParametersReachWriteConfirmationWithoutWriting() throws Exception {
        recognize(false);
        modelReturns(PARAMETERS);

        var response = execute(FREE_FORM);
        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(grounded).containsExactlyInAnyOrderEntriesOf(PARAMETERS);
        assertThat(metadata(response, "previewParams")).isEqualTo(mapper.valueToTree(PARAMETERS));
        verify(slots).checkAndStartSlotFilling(eq(FACTORY), eq(USER), same(intent), any(), any());
        verifyNoInteractions(recordRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"建微信客户", "补录一条微信内部备注，客户编号有两个，消息内容：合成测试内部备注"})
    void ambiguousRecognitionDoesNotGuessARecordTarget(String input) throws Exception {
        recognize(true);

        var response = execute(input);
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(metadata(response, "previewParams").isMissingNode()).isTrue();
        verifyNoInteractions(model, recordRepository);
    }

    @Test
    void shortCustomerCreationPhraseCannotPopulateWriteParameters() throws Exception {
        recognize(false);

        assertMissing(execute("建微信客户"), "customerId", "direction", "messageContent");
    }

    @Test
    void explicitlyMissingContentStillAsksForIt() throws Exception {
        recognize(false);
        modelReturns(Map.of("customerId", CUSTOMER, "direction", "INTERNAL"));

        assertMissing(execute("补录一条微信内部备注，客户编号" + CUSTOMER), "messageContent");
    }

    @Test
    void explicitlyMissingCustomerStillAsksForIt() throws Exception {
        recognize(false);
        modelReturns(Map.of("direction", "INTERNAL", "messageContent", CONTENT));

        assertMissing(execute("补录一条微信内部备注，消息内容：" + CONTENT), "customerId");
    }

    @Test
    void legitimateConfirmationWritesOnceAndReplayReadsConfirmedState() throws Exception {
        var action = previewFromStream();
        assertThat(confirm(action, FACTORY, USER, action.getCommandDigest(), action.getExpiresAt()))
                .isIn("SUCCESS", "COMPLETED");
        assertThat(tokenRow.get().getStatus()).isEqualTo(TokenStatus.CONFIRMED);
        assertThat(records.count(FACTORY, CUSTOMER)).isOne();
        assertThat(savedRecords).singleElement().satisfies(row -> {
            assertThat(row.getFactoryId()).isEqualTo(FACTORY);
            assertThat(row.getCustomerId()).isEqualTo(CUSTOMER);
            assertThat(row.getDirection()).isEqualTo(WechatDirection.INTERNAL);
            assertThat(row.getMessageContent()).isEqualTo(CONTENT);
            assertThat(row.getCreatedBy()).isEqualTo(USER);
        });
        assertThat(confirm(action, FACTORY, USER, action.getCommandDigest(), action.getExpiresAt()))
                .isEqualTo("ALREADY_EXECUTED");
        assertThat(records.count(FACTORY, CUSTOMER)).isOne();
        verify(recordRepository, times(1)).save(any(WechatRecord.class));
    }

    @Test
    void foreignPrincipalExpiredProofAndWrongDigestLeavePendingPreviewUnwritten() throws Exception {
        var action = previewFromStream();
        assertThat(confirm(action, FACTORY, USER + 1, action.getCommandDigest(), action.getExpiresAt()))
                .isEqualTo("FAILED");
        assertThat(confirm(action, "F007", USER, action.getCommandDigest(), action.getExpiresAt()))
                .isEqualTo("FAILED");
        assertThat(confirm(action, FACTORY, USER, action.getCommandDigest(), Instant.now().minusSeconds(1)))
                .isEqualTo("FAILED");
        assertThat(confirm(action, FACTORY, USER, "a".repeat(64), action.getExpiresAt()))
                .isEqualTo("FAILED");
        assertThat(tokenRow.get().getStatus()).isEqualTo(TokenStatus.PENDING);
        assertThat(records.count(FACTORY, CUSTOMER)).isZero();
        verify(recordRepository, never()).save(any(WechatRecord.class));
    }

    @Test
    void cancelledPreviewCannotConfirmAndWritesNothing() throws Exception {
        var action = previewFromStream();
        assertThat(tokens.cancelToken(action.getConfirmToken(), FACTORY, USER, "synthetic cancel")).isTrue();
        assertThat(tokenRow.get().getStatus()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(confirm(action, FACTORY, USER, action.getCommandDigest(), action.getExpiresAt()))
                .isEqualTo("FAILED");
        assertThat(records.count(FACTORY, CUSTOMER)).isZero();
        verify(recordRepository, never()).save(any(WechatRecord.class));
    }

    @Test
    void permissionRevokedAfterPreviewRejectsConfirmation() throws Exception {
        var action = previewFromStream();
        when(permissions.hasAnyPermission(any(User.class), eq("sales:read_write"))).thenReturn(false);

        assertThat(confirm(action, FACTORY, USER, action.getCommandDigest(), action.getExpiresAt()))
                .isEqualTo("NO_PERMISSION");
        assertThat(tokenRow.get().getStatus()).isEqualTo(TokenStatus.FAILED);
        assertThat(records.count(FACTORY, CUSTOMER)).isZero();
        verify(recordRepository, never()).save(any(WechatRecord.class));
    }

    private IntentExecuteResponse.ConfirmableAction previewFromStream() throws Exception {
        recognize(true);
        var response = execute(INPUT);
        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(records.count(FACTORY, CUSTOMER)).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) response.getMetadata().get("previewParams");
        var preview = controller.previewIntent(FACTORY,
                IntentExecuteRequest.builder().intentCode(INTENT).userInput(INPUT).context(params).build(),
                actorRequest(FACTORY, USER)).getBody().getData();
        assertThat(preview.getStatus()).isEqualTo("PREVIEW");
        assertThat(preview.getConfirmableAction()).isNotNull();
        assertThat(tokenRow.get().getStatus()).isEqualTo(TokenStatus.PENDING);
        assertThat(records.count(FACTORY, CUSTOMER)).isZero();
        return preview.getConfirmableAction();
    }

    private String confirm(IntentExecuteResponse.ConfirmableAction action, String factory, long user,
                           String digest, Instant expiry) {
        var body = new IntentConfirmationRequest(digest, expiry, "synthetic-request", "synthetic-attempt");
        return controller.confirmIntent(factory, action.getConfirmToken(), body, actorRequest(factory, user))
                .getBody().getData().getStatus();
    }

    private void stubTokenStorage() {
        when(tokenRepository.save(any(IntentPreviewToken.class))).thenAnswer(call -> {
            IntentPreviewToken token = call.getArgument(0);
            tokenRow.set(token);
            return token;
        });
        when(tokenRepository.findByToken(anyString())).thenAnswer(call -> Optional.ofNullable(tokenRow.get())
                .filter(token -> token.getToken().equals(call.getArgument(0))));
        when(tokenRepository.claimForExecution(anyString(), eq(FACTORY), eq(FACTORY), eq(USER),
                anyString(), anyString(), eq(tool.getToolName()), eq(tool.getVersion()), any(), anyString(),
                anyString(), anyString(), any(), any(), eq(TokenStatus.PENDING), eq(TokenStatus.EXECUTING)))
                .thenAnswer(call -> changeTokenStatus(TokenStatus.PENDING, TokenStatus.EXECUTING, call.getArgument(11)));
        when(tokenRepository.findByTokenAndClaimIdAndStatus(anyString(), anyString(), eq(TokenStatus.EXECUTING)))
                .thenAnswer(call -> Optional.ofNullable(tokenRow.get())
                        .filter(token -> token.getToken().equals(call.getArgument(0))
                                && token.getClaimId().equals(call.getArgument(1))
                                && token.getStatus() == TokenStatus.EXECUTING));
        when(tokenRepository.resolveClaim(anyString(), anyString(), eq(TokenStatus.EXECUTING),
                eq(TokenStatus.OUTCOME_UNKNOWN), any(), any(), any()))
                .thenAnswer(call -> changeTokenStatus(TokenStatus.EXECUTING, call.getArgument(4), call.getArgument(1)));
        when(tokenRepository.cancelPendingToken(anyString(), eq(FACTORY), eq(USER), anyString(),
                any(), eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED)))
                .thenAnswer(call -> changeTokenStatus(TokenStatus.PENDING, TokenStatus.CANCELLED, null));
    }

    private int changeTokenStatus(TokenStatus expected, TokenStatus next, String claimId) {
        var token = tokenRow.get();
        if (token.getStatus() != expected || (expected == TokenStatus.EXECUTING && !claimId.equals(token.getClaimId()))) {
            return 0;
        }
        token.setClaimId(claimId);
        token.setStatus(next);
        return 1;
    }

    private void recognize(boolean needsClarification) {
        var match = IntentMatchResult.builder().bestMatch(intent)
                .confidence(needsClarification ? 0.72 : 0.96)
                .requiresConfirmation(needsClarification)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build();
        when(intents.recognizeIntentWithConfidence(anyString(), eq(FACTORY), eq(3), eq(USER), eq(ROLE),
                isNull(), any(), any(), anyList())).thenReturn(match);
    }

    private void modelReturns(Map<String, Object> arguments) throws Exception {
        var reply = mock(ChatCompletionResponse.class);
        when(model.chatWithTools(anyString(), anyString(), anyList())).thenReturn(reply);
        when(model.hasToolCalls(reply)).thenReturn(true);
        when(model.getFirstToolCall(reply)).thenReturn(
                ToolCall.of("synthetic-extraction", "extract_parameters", mapper.writeValueAsString(arguments)));
    }

    private IntentExecuteResponse execute(String input) throws InterruptedException {
        return execute(IntentExecuteRequest.builder().userInput(input).build());
    }

    private IntentExecuteResponse execute(IntentExecuteRequest payload) throws InterruptedException {
        var request = actorRequest(FACTORY, USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            controller.executeIntentStream(FACTORY, payload, request);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(complete.await(10, TimeUnit.SECONDS)).as("SSE terminal event").isTrue();
        assertThat(result.get()).as("SSE result event").isNotNull();
        assertThat(result.get().getIntentCode()).isEqualTo(INTENT);
        return result.get();
    }

    private MockHttpServletRequest actorRequest(String factory, long user) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", user == USER ? "Bearer synthetic-owner" : "Bearer synthetic-other");
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, user, factory, ROLE));
        return request;
    }

    private void assertPreflightStop(IntentExecuteResponse response, String status) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(metadata(response, "previewParams").isMissingNode()).isTrue();
        verifyNoInteractions(model, slots, tokenRepository, recordRepository);
    }

    private void assertMissing(IntentExecuteResponse response, String... fields) {
        assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(metadata(response, "missingSlots")).extracting(slot -> slot.path("name").asText())
                .containsExactlyInAnyOrder(fields);
        assertThat(metadata(response, "previewParams").isMissingNode()).isTrue();
        verify(slots).checkAndStartSlotFilling(eq(FACTORY), eq(USER), same(intent), any(), any());
        verifyNoInteractions(recordRepository);
    }

    private JsonNode metadata(IntentExecuteResponse response, String field) {
        return mapper.valueToTree(response.getMetadata()).path(field);
    }
}

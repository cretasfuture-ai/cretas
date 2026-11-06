package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProductCreateControllerGatewayTest {

    private static final String FACTORY_ID = "F006";
    private static final long USER_ID = 42L;

    @Mock
    private ToolExecutionGateway gateway;
    @Mock
    private AuthenticatedToolPrincipalFactory principalFactory;
    @Mock
    private PreviewTokenService previewTokenService;
    @Mock
    private ProductCreateCommandReceiptService commandReceiptService;
    @Mock
    private HttpServletRequest request;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiProductCreateController controller;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new AiProductCreateController(
                gateway, principalFactory, previewTokenService, commandReceiptService, objectMapper);
        lenient().when(commandReceiptService.classifyTerminal(any()))
                .thenReturn(new ProductCreateCommandReceiptService.ReplayDecision(
                        ProductCreateCommandReceiptService.ReplayDecision.Kind.NEW_EXECUTION,
                        null, null, null));
        lenient().when(commandReceiptService.readCurrent())
                .thenReturn(Optional.of(new ProductCreateCommandReceiptService.ReceiptSnapshot(
                        "receipt-1",
                        com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                        ToolExecutionStatus.SUCCEEDED,
                        ProductCreateCommandReceiptService.ENTITY_TYPE,
                        "P-1",
                        ProductCreateCommandReceiptService.READBACK_VERIFIED,
                        null)));
        principal = new ExecutionPrincipal(
                FACTORY_ID,
                "FACTORY",
                Long.toString(USER_ID),
                PrincipalType.USER,
                Set.of("dispatcher"),
                Set.of(),
                Set.of());
    }

    @Test
    void cookieBackedTrustedAttributesPreviewThroughGatewayAndIssueOpaqueProof() {
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        when(gateway.execute(any())).thenAnswer(invocation -> {
            ProductCreateTool.capturePreviewBindingJson(
                    FACTORY_ID, writeJson(bindingFor(Map.of("productName", "SKU-A"))));
            return gatewaySuccess(ToolExecutionMode.PREVIEW, previewData("SKU-A"));
        });
        IntentPreviewToken token = boundToken(
                Map.of("productName", "SKU-A"), "proof-product");
        when(previewTokenService.createBoundToken(any())).thenReturn(token);

        Map<?, ?> data = (Map<?, ?>) controller.preview(
                FACTORY_ID, Map.of("productName", "SKU-A"), request).getData();

        assertThat(data.get("confirmationToken")).isEqualTo("proof-product");
        assertThat(data.get("confirmationExpiresAt")).isEqualTo(token.getExpiresAt().toString());
        assertThat(data.containsKey(ProductCreateTool.SERVER_BINDING_KEY)).isFalse();
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().mode()).isEqualTo(ToolExecutionMode.PREVIEW);
        assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.HTTP_CONTROLLER);
        assertThat(command.getValue().principal()).isSameAs(principal);
        assertThat(command.getValue().confirmationProof()).isEmpty();
        ArgumentCaptor<PreviewTokenService.BoundTokenRequest> tokenRequest =
                ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokenService).createBoundToken(tokenRequest.capture());
        assertThat(tokenRequest.getValue().parameters()).containsEntry("productName", "SKU-A");
        assertThat(tokenRequest.getValue().parameters())
                .containsKey(ProductCreateTool.SERVER_BINDING_KEY);
        assertThat(tokenRequest.getValue().parameters().get(ProductCreateTool.SERVER_BINDING_KEY))
                .isInstanceOf(String.class);
        assertThat(tokenRequest.getValue().executionMode()).isEqualTo(ToolExecutionMode.EXECUTE);
    }

    @Test
    void executeRequiresExactBoundProofAndPassesConfirmationAndStableIdempotencyToGateway() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        when(previewTokenService.readBoundExecution("proof-product", FACTORY_ID, USER_ID))
                .thenReturn(Optional.of(boundExecution(token, body)));
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.EXECUTE, Map.of("productId", "P-1", "productName", "SKU-A")));

        Map<?, ?> data = (Map<?, ?>) controller.create(
                FACTORY_ID, "proof-product", body, request).getData();

        assertThat(data.get("productId")).isEqualTo("P-1");
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        ToolExecutionCommand actual = command.getValue();
        assertThat(actual.mode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(actual.confirmationProof()).get().satisfies(proof -> {
            assertThat(proof.proofToken()).isEqualTo("proof-product");
            assertThat(proof.commandDigest()).isEqualTo(token.getCommandDigest());
        });
        assertThat(actual.idempotencyKey()).contains(
                "fixed-http-" + ToolCommandDigest.persistentSecretFingerprint("proof-product"));
    }

    @Test
    void parameterDriftAndReplayFailClosedBeforeGateway() {
        Map<String, Object> previewBody = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(previewBody, "proof-product");
        when(previewTokenService.readBoundExecution("proof-product", FACTORY_ID, USER_ID))
                .thenReturn(Optional.of(boundExecution(token, previewBody)), Optional.empty());

        assertThatThrownBy(() -> controller.create(
                FACTORY_ID,
                "proof-product",
                Map.of("productName", "SKU-B"),
                request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        assertThatThrownBy(() -> controller.create(
                FACTORY_ID, "proof-product", previewBody, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        verify(gateway, never()).execute(any());
    }

    @Test
    void pathFactoryMismatchRejectsEvenWhenAuthorizationHeaderClaimsAnotherTenant() {
        trustedAttributes("F007", USER_ID, "dispatcher");

        assertThatThrownBy(() -> controller.preview(
                FACTORY_ID, Map.of("productName", "SKU-A"), request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(403);
        verify(principalFactory, never()).create(any(), any(), any());
        verify(gateway, never()).execute(any());
    }

    @Test
    void terminalReplayStillRunsGatewayAndDeniedDoesNotDiscloseReceiptId() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        bindToken(token, body);
        when(commandReceiptService.classifyTerminal(any())).thenReturn(new ProductCreateCommandReceiptService.ReplayDecision(
                ProductCreateCommandReceiptService.ReplayDecision.Kind.REPLAY, null, null, null));
        when(gateway.execute(any())).thenReturn(gatewayResult(ToolExecutionStatus.DENIED,
                Map.of("message", "当前权限已撤销", "productId", "P-1")));

        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(403);
                    assertThat(business.getMessage()).doesNotContain("P-1");
                });
        verify(gateway).execute(any());
        verify(commandReceiptService, never()).readCurrent();
    }

    @Test
    void failedTerminalStillUsesGatewayAndReturnsStableFailureWithoutToolReplay() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        bindToken(token, body);
        when(gateway.execute(any())).thenReturn(gatewayResult(ToolExecutionStatus.IDEMPOTENT_REPLAY,
                Map.of("originalStatus", "FAILED", "message", "原命令失败")));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(new ProductCreateCommandReceiptService.ReceiptSnapshot(
                "receipt-failed", com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.FAILED,
                ToolExecutionStatus.FAILED, ProductCreateCommandReceiptService.ENTITY_TYPE,
                null, null, null)));

        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));
        verify(gateway).execute(any());
    }

    @Test
    void receiptReadFailureIsAnExplicitUnknownOutcome() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        bindToken(token, body);
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.EXECUTE, Map.of("productId", "P-1", "productName", "SKU-A")));
        when(commandReceiptService.readCurrent()).thenThrow(new IllegalStateException("database secret"));

        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(502);
                    assertThat(business.getErrorCode()).isEqualTo("PRODUCT_CREATE_OUTCOME_UNKNOWN");
                    assertThat(business.getMessage()).doesNotContain("database secret");
                });
    }

    @Test
    void verifiedNewPreviewRejectionKeepsHttp409() {
        assertGatewayFailureHttp(ToolExecutionStatus.FAILED,
                Map.of("message", "源产品已变化，请重新预览", "status", "NEED_MORE_INFO",
                        "requiresNewPreview", true, "errorCode", 409), 409);
    }

    @Test
    void numeric409WithoutNewPreviewProofRemainsOrdinaryFailure() {
        assertGatewayFailureHttp(ToolExecutionStatus.FAILED, Map.of("errorCode", 409), 400);
    }

    @Test
    void string409CannotClaimTheNewPreviewHttpContract() {
        assertGatewayFailureHttp(ToolExecutionStatus.FAILED,
                Map.of("status", "NEED_MORE_INFO", "requiresNewPreview", true, "errorCode", "409"), 400);
    }

    @Test
    void denialCannotBeOverriddenByAConflictPayload() {
        assertGatewayFailureHttp(ToolExecutionStatus.DENIED,
                Map.of("status", "NEED_MORE_INFO", "requiresNewPreview", true, "errorCode", 409), 403);
    }

    @Test
    void unknownCannotBeOverriddenByAConflictPayload() {
        assertGatewayFailureHttp(ToolExecutionStatus.OUTCOME_UNKNOWN,
                Map.of("status", "NEED_MORE_INFO", "requiresNewPreview", true, "errorCode", 409), 502);
    }

    private void assertGatewayFailureHttp(ToolExecutionStatus status, Map<String, Object> payload, int code) {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        bindToken(boundToken(body, "proof-product"), body);
        if (status == ToolExecutionStatus.FAILED) {
            // A verified prewrite failure has no product reference. The shared success
            // fixture deliberately has P-1 and therefore must not be reused here.
            when(commandReceiptService.readCurrent()).thenReturn(Optional.of(
                    new ProductCreateCommandReceiptService.ReceiptSnapshot("receipt-failed",
                            com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.FAILED,
                            ToolExecutionStatus.FAILED, null, null, null, null)));
        }
        when(gateway.execute(any())).thenReturn(gatewayResult(status, payload));
        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(code));
    }

    @Test
    void productCreatedWithPreparedChainMapsUnknownToCopyFailure() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundTokenWithChain(body, "proof-product");
        bindToken(token, body, true);
        when(gateway.execute(any())).thenReturn(gatewayResult(ToolExecutionStatus.OUTCOME_UNKNOWN,
                Map.of("message", "工具结果未知")));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(new ProductCreateCommandReceiptService.ReceiptSnapshot(
                "receipt-created", com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionStatus.OUTCOME_UNKNOWN, ProductCreateCommandReceiptService.ENTITY_TYPE,
                "P-1", ProductCreateCommandReceiptService.PRODUCT_CREATED, null)));

        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getErrorCode()).isEqualTo("PRODUCT_CHAIN_COPY_FAILED");
                    assertThat(business.getMessage()).contains("P-1");
                });
    }

    @Test
    void copyCommittedWithUnknownMapsToReadbackUnknown() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        bindToken(token, body);
        when(gateway.execute(any())).thenReturn(gatewayResult(ToolExecutionStatus.OUTCOME_UNKNOWN,
                Map.of("message", "工具结果未知")));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(new ProductCreateCommandReceiptService.ReceiptSnapshot(
                "receipt-copy", com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionStatus.OUTCOME_UNKNOWN, ProductCreateCommandReceiptService.ENTITY_TYPE,
                "P-1", ProductCreateCommandReceiptService.COPY_COMMITTED, null)));

        assertThatThrownBy(() -> controller.create(FACTORY_ID, "proof-product", body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("PRODUCT_CHAIN_READBACK_UNKNOWN"));
    }

    @Test
    void preUnknownStillReplaysVerifiedGatewayResultWithoutSecondExecution() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        bindToken(token, body);
        when(commandReceiptService.classifyTerminal(any())).thenReturn(new ProductCreateCommandReceiptService.ReplayDecision(
                ProductCreateCommandReceiptService.ReplayDecision.Kind.UNKNOWN, null,
                "需核对", "PRODUCT_CREATE_OUTCOME_UNKNOWN"));
        when(gateway.execute(any())).thenReturn(gatewayResult(ToolExecutionStatus.IDEMPOTENT_REPLAY,
                Map.of("originalStatus", "SUCCEEDED")));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(new ProductCreateCommandReceiptService.ReceiptSnapshot(
                "receipt-ok", com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                ToolExecutionStatus.SUCCEEDED, ProductCreateCommandReceiptService.ENTITY_TYPE,
                "P-1", ProductCreateCommandReceiptService.READBACK_VERIFIED, null)));

        Map<?, ?> data = (Map<?, ?>) controller.create(FACTORY_ID, "proof-product", body, request).getData();

        assertThat(data.get("productId")).isEqualTo("P-1");
        assertThat(data.get("replayed")).isEqualTo(true);
        verify(gateway).execute(any());
    }

    private void trustedAttributes(String factoryId, long userId, String role) {
        lenient().when(request.getAttribute("factoryId")).thenReturn(factoryId);
        lenient().when(request.getAttribute("userId")).thenReturn(userId);
        lenient().when(request.getAttribute("role")).thenReturn(role);
        lenient().when(request.getAttribute("username")).thenReturn("trusted-user");
        lenient().when(principalFactory.create(eq(factoryId), eq(userId), eq(role)))
                .thenReturn(principal);
    }

    private IntentPreviewToken boundToken(Map<String, Object> body, String proofToken) {
        return boundToken(body, proofToken, false);
    }

    private IntentPreviewToken boundTokenWithChain(Map<String, Object> body, String proofToken) {
        return boundToken(body, proofToken, true);
    }

    private IntentPreviewToken boundToken(Map<String, Object> body, String proofToken, boolean withChain) {
        Map<String, Object> persisted = new LinkedHashMap<>(body);
        Map<String, Object> binding = bindingFor(body);
        if (withChain) {
            binding.put("chain", Map.of("processes", java.util.List.of(Map.of("workProcessId", "wp-1"))));
        }
        persisted.put(ProductCreateTool.SERVER_BINDING_KEY, writeJson(binding));
        String digest = ToolCommandDigest.commandDigest(
                FACTORY_ID,
                USER_ID,
                "product_create",
                "2.0.0",
                ToolExecutionMode.EXECUTE,
                objectMapper.valueToTree(persisted));
        return IntentPreviewToken.builder()
                .token(proofToken)
                .factoryId(FACTORY_ID)
                .tenantId(FACTORY_ID)
                .userId(USER_ID)
                .toolName("product_create")
                .descriptorVersion("2.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash("b".repeat(64))
                .commandDigest(digest)
                .previewData(writeJson(persisted))
                .status(IntentPreviewToken.TokenStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    private void bindToken(IntentPreviewToken token, Map<String, Object> body) {
        bindToken(token, body, false);
    }

    private void bindToken(IntentPreviewToken token, Map<String, Object> body, boolean withChain) {
        Map<String, Object> persisted = new LinkedHashMap<>(body);
        Map<String, Object> binding = bindingFor(body);
        if (withChain) {
            binding.put("chain", Map.of("processes", java.util.List.of(Map.of("workProcessId", "wp-1"))));
        }
        persisted.put(ProductCreateTool.SERVER_BINDING_KEY, writeJson(binding));
        when(previewTokenService.readBoundExecution("proof-product", FACTORY_ID, USER_ID))
                .thenReturn(Optional.of(new PreviewTokenService.BoundTokenExecution(
                        token.getToken(), token.getFactoryId(), token.getTenantId(), token.getUserId(),
                        token.getToolName(), token.getDescriptorVersion(), token.getExecutionMode(),
                        token.getParametersHash(), token.getCommandDigest(), token.getPreviewData(),
                        token.getStatus(), token.getExpiresAt(), persisted)));
    }

    private PreviewTokenService.BoundTokenExecution boundExecution(
            IntentPreviewToken token, Map<String, Object> body) {
        Map<String, Object> persisted = new LinkedHashMap<>(body);
        persisted.put(ProductCreateTool.SERVER_BINDING_KEY, writeJson(bindingFor(body)));
        return new PreviewTokenService.BoundTokenExecution(
                token.getToken(), token.getFactoryId(), token.getTenantId(), token.getUserId(),
                token.getToolName(), token.getDescriptorVersion(), token.getExecutionMode(),
                token.getParametersHash(), token.getCommandDigest(), token.getPreviewData(),
                token.getStatus(), token.getExpiresAt(), persisted);
    }

    private Map<String, Object> previewData(String productName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "PREVIEW");
        data.put("productName", productName);
        return data;
    }

    private Map<String, Object> bindingFor(Map<String, Object> body) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("version", 1);
        binding.put("productName", body.get("productName"));
        binding.put("productCategory", "FINISHED_PRODUCT");
        binding.put("defaults", Map.of());
        binding.put("sourceName", null);
        binding.put("sourceProductId", null);
        binding.put("processNames", java.util.List.of());
        return binding;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ToolExecutionResult gatewaySuccess(
            ToolExecutionMode mode,
            Map<String, Object> data) {
        return new ToolExecutionResult(
                "request-" + mode,
                "product_create",
                "2.0.0",
                "audit-" + mode,
                "trace-" + mode,
                ToolExecutionStatus.SUCCEEDED,
                objectMapper.valueToTree(Map.of("success", true, "data", data)),
                "Tool succeeded",
                false);
    }

    private ToolExecutionResult gatewayResult(
            ToolExecutionStatus status, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", status == ToolExecutionStatus.SUCCEEDED);
        envelope.put("data", data);
        if (data.containsKey("originalStatus")) {
            envelope.put("originalStatus", data.get("originalStatus"));
        }
        if (data.containsKey("message")) {
            envelope.put("message", data.get("message"));
        }
        for (String key : java.util.List.of("errorCode", "status", "requiresNewPreview")) {
            if (data.containsKey(key)) envelope.put(key, data.get(key));
        }
        return new ToolExecutionResult(
                "request-" + status,
                "product_create",
                "2.0.0",
                "audit-" + status,
                "trace-" + status,
                status,
                objectMapper.valueToTree(envelope),
                status.name(),
                status == ToolExecutionStatus.IDEMPOTENT_REPLAY);
    }
}

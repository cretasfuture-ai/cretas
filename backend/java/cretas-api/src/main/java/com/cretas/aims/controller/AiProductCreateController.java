package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fixed HTTP entry for governed {@code product_create} preview and execution. */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai-product-create")
@Tag(name = "AI智能建产品", description = "数据飞轮建产品的显式预览与确认入口")
@RequiredArgsConstructor
public class AiProductCreateController {

    private static final String TOOL_NAME = "product_create";
    private static final String DESCRIPTOR_VERSION = "2.0.0";
    private static final int CONFIRMATION_TTL_SECONDS = 300;
    private static final Pattern SAFE_FACTORY_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SAFE_ROLE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final ToolExecutionGateway toolExecutionGateway;
    private final AuthenticatedToolPrincipalFactory principalFactory;
    private final PreviewTokenService previewTokenService;
    private final ProductCreateCommandReceiptService commandReceiptService;
    private final ObjectMapper objectMapper;

    @PostMapping("/preview")
    @Operation(summary = "预览建产品计划，不写入业务数据")
    public ApiResponse<Object> preview(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> publicBody = publicBody(body);
        AuthenticatedIdentity identity = authenticatedIdentity(factoryId, request);
        String bindingJson;
        ToolExecutionResult result;
        Map<String, Object> data;
        try (ProductCreateTool.PreviewBindingCapture capture =
                     ProductCreateTool.openPreviewBindingCapture(factoryId)) {
            result = executeGateway(
                    publicBody, ToolExecutionMode.PREVIEW, identity, Optional.empty());
            data = successfulPayload(result, "AI product preview failed");
            bindingJson = capture.requireJson();
        }
        Map<String, Object> persistedParameters = new LinkedHashMap<>(publicBody);
        persistedParameters.put(ProductCreateTool.SERVER_BINDING_KEY, bindingJson);

        IntentPreviewToken token = previewTokenService.createBoundToken(
                new PreviewTokenService.BoundTokenRequest(
                        factoryId,
                        identity.userId(),
                        identity.username(),
                        "AI_PRODUCT_CREATE",
                        "AI product creation",
                        TOOL_NAME,
                        DESCRIPTOR_VERSION,
                        ToolExecutionMode.EXECUTE,
                        "PRODUCT_TYPE",
                        text(publicBody.get("productName")),
                        "CREATE",
                        persistedParameters,
                        Map.of(),
                        persistedParameters,
                        CONFIRMATION_TTL_SECONDS));
        data.put("confirmationToken", token.getToken());
        data.put("confirmationExpiresAt", token.getExpiresAt().toString());
        return ApiResponse.success(data);
    }

    @RequirePermission({"production:read_write", "system:read_write"})
    @PostMapping
    @Operation(summary = "确认并执行建产品计划")
    public ApiResponse<Object> create(
            @PathVariable String factoryId,
            @RequestHeader("X-Cretas-Confirmation-Token") String confirmationToken,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        AuthenticatedIdentity identity = authenticatedIdentity(factoryId, request);
        BoundExecution boundExecution = requireBoundToken(
                confirmationToken, factoryId, body, identity.userId());
        IntentPreviewToken token = boundExecution.token();
        Map<String, Object> persistedParameters = boundExecution.parameters();
        ConfirmationProof proof = new ConfirmationProof(
                token.getToken(),
                token.getCommandDigest(),
                token.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
        String idempotencyKey = "fixed-http-"
                + ToolCommandDigest.persistentSecretFingerprint(token.getToken());
        String idempotencyHash = ToolCommandDigest.persistentSecretFingerprint(idempotencyKey);
        String confirmationFingerprint = ToolCommandDigest.persistentSecretFingerprint(token.getToken());
        try (ProductCreateCommandReceiptService.CommandScope ignored =
                     ProductCreateCommandReceiptService.openScope(
                             identity.principal().tenantId(), identity.principal().businessType(),
                             identity.principal().principalType(), identity.principal().principalId(),
                             TOOL_NAME, DESCRIPTOR_VERSION, idempotencyHash,
                             token.getCommandDigest(), confirmationFingerprint)) {
            ProductCreateCommandReceiptService.ReplayDecision replay =
                    commandReceiptService.classifyTerminal(token);
            if (replay.kind() == ProductCreateCommandReceiptService.ReplayDecision.Kind.REJECT) {
                throw new BusinessException(409, replay.message())
                        .withCode(replay.code())
                        .withHint("请根据产品ID核对结果，或重新发起预览");
            }

            ToolExecutionResult result = executeGateway(
                    persistedParameters,
                    ToolExecutionMode.EXECUTE,
                    identity,
                    Optional.of(new ExecutionEvidence(idempotencyKey, proof)));

            // Authorization/proof failures are returned before reading the durable
            // product reference.  A revoked terminal command must remain a plain
            // 403/409 and can never disclose an old product ID.
            if (result.status() == ToolExecutionStatus.DENIED
                    || result.status() == ToolExecutionStatus.CONFIRMATION_REQUIRED
                    || result.status() == ToolExecutionStatus.APPROVAL_REQUIRED) {
                throw gatewayFailure(result);
            }

            ProductCreateCommandReceiptService.ReceiptSnapshot freshReceipt;
            try {
                freshReceipt = commandReceiptService.readCurrent().orElse(null);
            } catch (Exception readFailure) {
                throw outcomeUnknown(null);
            }
            if (result.status() == ToolExecutionStatus.IDEMPOTENT_REPLAY) {
                String originalStatus = result.payload().path("originalStatus").asText("UNKNOWN");
                if ("FAILED".equals(originalStatus) && freshReceipt != null
                        && freshReceipt.state()
                                == com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.FAILED
                        && freshReceipt.entityId() == null) {
                    throw gatewayFailure(result);
                }
                if (!"SUCCEEDED".equals(originalStatus) || !isVerifiedSuccess(freshReceipt)) {
                    throw receiptOutcomeFailure(freshReceipt, persistedParameters);
                }
                return ApiResponse.success(replayPayload(freshReceipt, persistedParameters));
            }
            if (result.status() == ToolExecutionStatus.OUTCOME_UNKNOWN
                    || (freshReceipt != null && freshReceipt.entityId() != null
                    && result.status() != ToolExecutionStatus.SUCCEEDED)) {
                throw receiptOutcomeFailure(freshReceipt, persistedParameters);
            }
            if (result.status() != ToolExecutionStatus.SUCCEEDED) {
                throw gatewayFailure(result);
            }

            Map<String, Object> payload = successfulPayload(result, "AI product creation failed");
            if (!isVerifiedSuccess(freshReceipt)
                    || !Objects.equals(text(payload.get("productId")), freshReceipt.entityId())) {
                throw receiptOutcomeFailure(freshReceipt, persistedParameters);
            }
            return ApiResponse.success(payload);
        }
    }

    private ToolExecutionResult executeGateway(
            Map<String, Object> body,
            ToolExecutionMode mode,
            AuthenticatedIdentity identity,
            Optional<ExecutionEvidence> evidence) {
        try {
            String callId = "ai-create-" + UUID.randomUUID();
            ToolExecutionCommand command = new ToolExecutionCommand(
                    callId,
                    callId,
                    callId + "-trace",
                    TOOL_NAME,
                    DESCRIPTOR_VERSION,
                    objectMapper.valueToTree(body),
                    identity.principal(),
                    ToolExecutionSource.HTTP_CONTROLLER,
                    mode,
                    evidence.map(ExecutionEvidence::idempotencyKey),
                    evidence.map(ExecutionEvidence::confirmationProof),
                    Optional.empty(),
                    Instant.now().plusSeconds(30));
            return toolExecutionGateway.execute(command);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[AI-PRODUCT-CREATE] Gateway {} failed: {}",
                    mode, exception.getMessage(), exception);
            throw new BusinessException(500, "AI 建产品失败，请稍后重试");
        }
    }

    private AuthenticatedIdentity authenticatedIdentity(
            String factoryId,
            HttpServletRequest request) {
        String trustedFactoryId = stringAttribute(request, "factoryId");
        String trustedUserId = stringAttribute(request, "userId");
        String trustedRole = stringAttribute(request, "role");
        if (trustedFactoryId == null || trustedUserId == null || trustedRole == null) {
            throw new BusinessException(401, "Trusted authentication context is required");
        }
        if (!factoryId.equals(trustedFactoryId)) {
            throw new BusinessException(403,
                    "Authenticated tenant does not match request tenant");
        }
        String normalizedRole = trustedRole.toLowerCase(Locale.ROOT);
        if (!SAFE_FACTORY_ID.matcher(factoryId).matches()
                || !SAFE_USER_ID.matcher(trustedUserId).matches()
                || !SAFE_ROLE.matcher(normalizedRole).matches()) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        long parsedUserId;
        try {
            parsedUserId = Long.parseLong(trustedUserId);
        } catch (NumberFormatException invalidUserId) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        if (parsedUserId <= 0) {
            throw new BusinessException(403, "Trusted authentication context is invalid");
        }
        ExecutionPrincipal principal = principalFactory.create(
                factoryId, parsedUserId, normalizedRole);
        return new AuthenticatedIdentity(
                parsedUserId, boundedUsername(stringAttribute(request, "username")), principal);
    }

    private BoundExecution requireBoundToken(
            String proofToken,
            String factoryId,
            Map<String, Object> body,
            Long userId) {
        if (proofToken == null || proofToken.isBlank() || proofToken.length() > 2048) {
            throw confirmationRejected();
        }
        PreviewTokenService.BoundTokenExecution bound = previewTokenService
                .readBoundExecution(proofToken, factoryId, userId)
                .orElseThrow(this::confirmationRejected);
        IntentPreviewToken token = bound.toToken();
        if (!token.isBoundForExecution()
                || !userId.equals(bound.userId())
                || !factoryId.equals(bound.factoryId())
                || !factoryId.equals(bound.tenantId())
                || !TOOL_NAME.equals(bound.toolName())
                || !DESCRIPTOR_VERSION.equals(bound.descriptorVersion())
                || bound.executionMode() != ToolExecutionMode.EXECUTE) {
            throw confirmationRejected();
        }
        Map<String, Object> persistedParameters = bound.parameters();
        if (body.containsKey(ProductCreateTool.SERVER_BINDING_KEY)) {
            throw confirmationRejected();
        }
        Map<String, Object> publicParameters = new LinkedHashMap<>(persistedParameters);
        Object binding = publicParameters.remove(ProductCreateTool.SERVER_BINDING_KEY);
        if (!(binding instanceof String)
                || !objectMapper.valueToTree(publicParameters).equals(objectMapper.valueToTree(body))) {
            throw confirmationRejected();
        }
        String expectedDigest = ToolCommandDigest.commandDigest(
                factoryId,
                userId,
                TOOL_NAME,
                DESCRIPTOR_VERSION,
                ToolExecutionMode.EXECUTE,
                objectMapper.valueToTree(persistedParameters));
        if (!expectedDigest.equals(token.getCommandDigest())) {
            throw confirmationRejected();
        }
        return new BoundExecution(token, persistedParameters);
    }

    private Map<String, Object> publicBody(Map<String, Object> body) {
        if (body == null || body.containsKey(ProductCreateTool.SERVER_BINDING_KEY)) {
            throw confirmationRejected();
        }
        return new LinkedHashMap<>(body);
    }

    private BusinessException confirmationRejected() {
        return new BusinessException(
                409, "确认凭证缺失、已过期，或与预览内容不一致")
                .withCode("INVALID_CONFIRMATION_PROOF")
                .withHint("请重新发起 AI 建产品预览后再确认")
                .withSeverity("BLOCKING");
    }

    private BusinessException outcomeUnknown(ProductCreateCommandReceiptService.ReceiptSnapshot receipt) {
        String reference = receipt == null || receipt.entityId() == null
                ? "产品ID未知"
                : "产品ID: " + receipt.entityId();
        String stage = receipt == null || receipt.stage() == null ? "状态未知" : receipt.stage();
        return new BusinessException(502,
                reference + "，结果阶段为 " + stage + "，需人工核对；系统不会自动重试。")
                .withCode("PRODUCT_CREATE_OUTCOME_UNKNOWN")
                .withHint("请根据产品ID核对创建结果和工序链，不要重复提交");
    }

    private BusinessException receiptOutcomeFailure(
            ProductCreateCommandReceiptService.ReceiptSnapshot receipt,
            Map<String, Object> persistedParameters) {
        if (receipt != null && receipt.entityId() != null
                && ProductCreateCommandReceiptService.PRODUCT_CREATED.equals(receipt.stage())) {
            String productId = receipt.entityId();
            String binding = text(persistedParameters.get(ProductCreateTool.SERVER_BINDING_KEY));
            if (ProductCreateTool.hasNonEmptyPreparedChain(binding)) {
                return new BusinessException(502,
                        "产品已创建（ID: " + productId + "），工序链复制未完成，需人工核对；系统不会自动重试。")
                        .withCode("PRODUCT_CHAIN_COPY_FAILED")
                        .withHint("请根据产品ID核对源产品工序链，不要重复提交创建请求");
            }
            return new BusinessException(502,
                    "产品已创建（ID: " + productId + "），空工序链结果无法核对，需人工检查；系统不会自动重试。")
                    .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                    .withHint("请根据产品ID核对产品工序链，不要重复提交创建请求");
        }
        if (receipt != null && receipt.entityId() != null
                && ProductCreateCommandReceiptService.COPY_COMMITTED.equals(receipt.stage())) {
            return new BusinessException(502,
                    "产品已创建（ID: " + receipt.entityId()
                            + "），工序链复制结果无法核对，需人工检查；系统不会自动重试。")
                    .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                    .withHint("请根据产品ID核对产品工序链，不要重复提交创建请求");
        }
        return outcomeUnknown(receipt);
    }

    private boolean isVerifiedSuccess(
            ProductCreateCommandReceiptService.ReceiptSnapshot receipt) {
        return receipt != null
                && receipt.state() == com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.SUCCEEDED
                && receipt.outcomeStatus() == ToolExecutionStatus.SUCCEEDED
                && ProductCreateCommandReceiptService.READBACK_VERIFIED.equals(receipt.stage())
                && receipt.entityId() != null;
    }

    private BusinessException gatewayFailure(ToolExecutionResult result) {
        String message = result.payload().path("message").asText(result.message());
        JsonNode errorCode = result.payload().path("errorCode");
        boolean requiresNewPreview = result.status() == ToolExecutionStatus.FAILED
                && "NEED_MORE_INFO".equals(result.payload().path("status").asText())
                && result.payload().path("requiresNewPreview").isBoolean()
                && result.payload().path("requiresNewPreview").booleanValue()
                && errorCode.isIntegralNumber() && errorCode.canConvertToInt() && errorCode.intValue() == 409;
        int code = result.status() == ToolExecutionStatus.DENIED ? 403 : requiresNewPreview ? 409 : 400;
        return new BusinessException(code, message == null || message.isBlank()
                ? "AI 建产品未执行"
                : message)
                .withCode("PRODUCT_CREATE_GATEWAY_" + result.status().name())
                .withHint("请根据当前确认状态处理，不要重复提交创建请求");
    }

    private Map<String, Object> replayPayload(
            ProductCreateCommandReceiptService.ReceiptSnapshot receipt,
            Map<String, Object> persistedParameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", receipt.entityId());
        payload.put("productName", text(persistedParameters.get("productName")));
        payload.put("replayed", true);
        payload.put("message", "已回放已核实的产品创建结果，未再次执行工具");
        return payload;
    }

    private Map<String, Object> successfulPayload(
            ToolExecutionResult result,
            String fallbackMessage) {
        if (result.status() != ToolExecutionStatus.SUCCEEDED || !result.payload().isObject()) {
            String message = result.payload().path("message").asText(result.message());
            int code = result.status() == ToolExecutionStatus.DENIED ? 403 : 400;
            throw new BusinessException(code, message.isBlank() ? fallbackMessage : message);
        }
        Map<String, Object> payload = objectMapper.convertValue(
                result.payload(), new TypeReference<Map<String, Object>>() {});
        Object inner = payload.get("data");
        if (inner instanceof Map<?, ?> innerMap) {
            return new LinkedHashMap<>(objectMapper.convertValue(
                    innerMap, new TypeReference<Map<String, Object>>() {}));
        }
        return new LinkedHashMap<>(payload);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String stringAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    private String boundedUsername(String username) {
        return username != null && username.length() <= 100 ? username : null;
    }

    private record AuthenticatedIdentity(
            Long userId,
            String username,
            ExecutionPrincipal principal) {
    }

    private record ExecutionEvidence(
            String idempotencyKey,
            ConfirmationProof confirmationProof) {
    }

    private record BoundExecution(
            IntentPreviewToken token,
            Map<String, Object> parameters) {
    }
}

package com.cretas.aims.service.config.aiconfig;

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
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deliberately test-only until the configuration assistant has an approved caller. */
@Service
@Conditional(FactoryShortageConfigActivation.class)
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class FactoryShortageConfigService {
    public static final String ENABLED_PROPERTY = "cretas.ai.config.shortage-contract-enabled";
    public static final String NAME = "factory_shortage_config_update";
    public static final String VERSION = "1.0.0";
    public static final String SETTING = "blockStartOnOpenShortage";
    public static final Set<String> ROLES = Set.of("factory_super_admin", "permission_admin");
    static final String ENTITY_TYPE = "FACTORY_SHORTAGE_CONFIG";
    private static final String INTENT_CODE = "AI_FACTORY_SHORTAGE_CONFIG";
    private static final int CONFIRMATION_SECONDS = 300;
    private static final TypeReference<Map<String, Object>> PARAMETERS = new TypeReference<>() {};
    private static final ThreadLocal<Invocation> ACTIVE = new ThreadLocal<>();

    private final ToolExecutionGateway gateway;
    private final AuthenticatedToolPrincipalFactory principals;
    private final ToolPrincipalPolicy principalPolicy;
    private final PreviewTokenService tokens;
    private final FactoryShortageConfigStore store;
    private final ObjectMapper mapper;

    /**
     * Captures the original utterance at the protected entry. Confirm only takes the opaque
     * credential, so the requester cannot replace the command or the audited text at confirmation.
     */
    public Preview preview(String factoryId, JsonNode requestedValue, String originalUtterance) {
        ExecutionPrincipal principal = authorize(factoryId);
        if (requestedValue == null || !requestedValue.isBoolean()) {
            throw new BusinessException(400, "缺料开工拦截值必须为布尔值");
        }
        return prepare(principal, new Request(requestedValue.booleanValue(), null), originalUtterance);
    }

    public Preview previewUndo(String factoryId, UUID operationId, String originalUtterance) {
        ExecutionPrincipal principal = authorize(factoryId);
        if (operationId == null) {
            throw new BusinessException(400, "缺少要撤销的操作编号");
        }
        return prepare(principal, new Request(null, operationId.toString()), originalUtterance);
    }

    public FactoryShortageConfigStore.Receipt confirm(String factoryId, String confirmationToken) {
        ExecutionPrincipal principal = authorize(factoryId);
        var token = boundToken(principal, confirmationToken);
        Map<String, Object> parameters = token.parameters();
        Binding binding = mapper.convertValue(parameters, Binding.class);
        var proof = new ConfirmationProof(confirmationToken, token.commandDigest(),
                token.expiresAt().atZone(ZoneId.systemDefault()).toInstant(),
                ENTITY_TYPE, binding.operationId());
        String key = NAME + ":" + ToolCommandDigest.persistentSecretFingerprint(confirmationToken);
        ToolExecutionResult result = execute(principal, binding.request(), binding, parameters,
                ToolExecutionMode.EXECUTE, Optional.of(key), Optional.of(proof));
        boolean mayHaveCommitted = switch (result.status()) {
            case SUCCEEDED, IDEMPOTENT_REPLAY, OUTCOME_UNKNOWN -> true;
            default -> false;
        };
        if (mayHaveCommitted) {
            var persisted = store.readReceipt(factoryId, binding.operationId(), userId(principal));
            if (persisted.isPresent()) {
                return persisted.get();
            }
            if (result.status() == ToolExecutionStatus.SUCCEEDED
                    || result.status() == ToolExecutionStatus.OUTCOME_UNKNOWN) {
                throw new BusinessException(409, "暂时无法核实提交结果，请按操作编号查询回执")
                        .withHint("请先按操作编号查询回执，核实结果后再决定是否发起新预览")
                        .withHintTarget(binding.operationId());
            }
        }
        throw rejected(result);
    }

    public boolean cancel(String factoryId, String confirmationToken) {
        ExecutionPrincipal principal = authorize(factoryId);
        boundToken(principal, confirmationToken);
        return tokens.cancelToken(confirmationToken, factoryId, userId(principal), "用户取消配置预览");
    }

    public Optional<FactoryShortageConfigStore.Receipt> receipt(String factoryId, UUID operationId) {
        ExecutionPrincipal principal = authorize(factoryId);
        if (operationId == null) {
            throw new BusinessException(400, "缺少操作编号");
        }
        return store.readReceipt(factoryId, operationId.toString(), userId(principal));
    }

    private Preview prepare(ExecutionPrincipal principal, Request request, String originalUtterance) {
        String utterance = RequestAuditSupport.sanitizeText(originalUtterance, 1000);
        if (utterance == null || utterance.isBlank()) {
            throw new BusinessException(400, "缺少本次配置请求的原话");
        }
        ToolExecutionResult result = execute(principal, request, null, mapper.convertValue(request, PARAMETERS),
                ToolExecutionMode.PREVIEW, Optional.empty(), Optional.empty());
        if (result.status() != ToolExecutionStatus.SUCCEEDED) {
            throw rejected(result);
        }
        JsonNode data = result.payload().required("data");
        var plan = mapper.convertValue(data.required("plan"), FactoryShortageConfigStore.Plan.class);
        if (!plan.canDo()) {
            return new Preview(plan, null, null, null);
        }
        String operationId = UUID.randomUUID().toString();
        var binding = new Binding(request, plan.current(), plan.newValue(), operationId, utterance);
        var token = tokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(
                principal.tenantId(), userId(principal), null, INTENT_CODE, "缺料时拦开工",
                NAME, VERSION, ToolExecutionMode.EXECUTE, ENTITY_TYPE, operationId,
                request.undoOf() == null ? "UPDATE" : "UNDO", mapper.convertValue(binding, PARAMETERS),
                plan.current().asValues(), Map.of(SETTING, plan.newValue()), CONFIRMATION_SECONDS));
        return new Preview(plan, UUID.fromString(operationId), token.getToken(),
                token.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    private PreviewTokenService.BoundTokenExecution boundToken(ExecutionPrincipal principal, String token) {
        if (token == null || token.length() != 36) {
            throw invalidToken();
        }
        var snapshot = tokens.readBoundSnapshot(token, principal.tenantId(), userId(principal))
                .filter(meta -> INTENT_CODE.equals(meta.intentCode()) && ENTITY_TYPE.equals(meta.entityType()))
                .orElseThrow(FactoryShortageConfigService::invalidToken);
        var execution = tokens.readBoundExecution(token, principal.tenantId(), userId(principal))
                .filter(bound -> NAME.equals(bound.toolName()) && VERSION.equals(bound.descriptorVersion())
                        && bound.executionMode() == ToolExecutionMode.EXECUTE
                        && principal.tenantId().equals(bound.tenantId()))
                .orElseThrow(FactoryShortageConfigService::invalidToken);
        if (snapshot.entityId() == null || !snapshot.entityId().equals(execution.parameters().get("operationId"))) {
            throw invalidToken();
        }
        return execution;
    }

    private ExecutionPrincipal authorize(String factoryId) {
        var actor = AuthenticatedActor.fromCurrentRequest()
                .filter(candidate -> candidate.type() == AuthenticatedActorType.FACTORY_USER)
                .orElseThrow(FactoryShortageConfigService::forbidden);
        if (factoryId == null || factoryId.isBlank() || !factoryId.equals(actor.factoryId())
                || Set.of("GLOBAL", "PLATFORM", "SHARED").contains(factoryId.toUpperCase(Locale.ROOT))
                || actor.role() == null || !ROLES.contains(actor.role())) {
            throw forbidden();
        }
        ExecutionPrincipal asserted;
        try {
            asserted = principals.create(factoryId, actor.id(), actor.role());
        } catch (SecurityException error) {
            throw forbidden();
        }
        return principalPolicy.rehydrate(asserted)
                .map(ToolPrincipalPolicy.RehydratedPrincipal::principal)
                .filter(current -> current.roles().stream().anyMatch(ROLES::contains))
                .filter(current -> FactoryType.valueOf(current.businessType()).hasProductionCapability())
                .orElseThrow(FactoryShortageConfigService::forbidden);
    }

    private ToolExecutionResult execute(ExecutionPrincipal principal, Request request, Binding binding,
            Map<String, Object> parameters, ToolExecutionMode mode,
            Optional<String> key, Optional<ConfirmationProof> proof) {
        if (ACTIVE.get() != null) {
            throw forbidden();
        }
        JsonNode json = mapper.valueToTree(parameters);
        String id = UUID.randomUUID().toString();
        var command = new ToolExecutionCommand(id, id, id, NAME, VERSION, json, principal,
                ToolExecutionSource.INTERNAL_SERVICE, mode, key, proof, Optional.empty(), Instant.now().plusSeconds(30));
        ACTIVE.set(new Invocation(principal, request, binding, ToolCommandDigest.parametersHash(json)));
        try {
            return gateway.execute(command);
        } finally {
            ACTIVE.remove();
        }
    }

    static Invocation requireInvocation(Map<String, Object> context, JsonNode parameters) {
        Invocation active = ACTIVE.get();
        if (active == null || !active.principal().tenantId().equals(context.get("factoryId"))
                || !Long.valueOf(userId(active.principal())).equals(context.get("userId"))
                || !active.principal().roles().contains(context.get("userRole"))
                || !active.parametersHash().equals(ToolCommandDigest.parametersHash(parameters))) {
            throw forbidden();
        }
        return active;
    }

    private static long userId(ExecutionPrincipal principal) {
        return Long.parseLong(principal.principalId());
    }

    private static BusinessException rejected(ToolExecutionResult result) {
        if (result.status() == ToolExecutionStatus.DENIED) {
            return forbidden();
        }
        if (result.status() == ToolExecutionStatus.CONFIRMATION_REQUIRED) {
            return invalidToken();
        }
        if ("PREVIEW_STALE".equals(result.payload().path("status").asText())) {
            return new BusinessException(409, "工厂设置已变化，请重新预览")
                    .withCode("PREVIEW_STALE")
                    .withHint("请重新预览当前工厂设置，再确认所需变更");
        }
        return new BusinessException(409, "配置操作未通过，请查询回执或重新预览")
                .withHint("若已取得操作编号，请先查询对应回执，再按当前设置重新预览");
    }

    static BusinessException forbidden() {
        return new BusinessException(403, "仅限本厂获授权的管理员操作此设置");
    }

    private static BusinessException invalidToken() {
        return new BusinessException(409, "确认凭证无效、已过期或与本次操作不符")
                .withHint("请在当前工厂重新生成预览，并使用该预览的新凭证确认");
    }

    public record Preview(FactoryShortageConfigStore.Plan plan, UUID operationId,
                          String confirmationToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Preview[operationId=" + operationId + ", expiresAt=" + expiresAt + "]";
        }
    }

    record Request(Boolean requestedValue, String undoOf) {}
    record Binding(Request request, FactoryShortageConfigStore.Snapshot expected,
                   boolean newValue, String operationId, String sanitizedUtterance) {}
    record Invocation(ExecutionPrincipal principal, Request request, Binding binding, String parametersHash) {
        long userId() { return FactoryShortageConfigService.userId(principal); }
    }
}

package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic, command-bound implementation of the preview confirmation lifecycle. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewTokenServiceImpl implements PreviewTokenService {

    private static final int MAX_RESOLUTION_MESSAGE_LENGTH = 500;
    private static final Set<String> DRAFT_TECHNICAL_PARAMETER_KEYS = Set.of(
            "factoryId", "factory_id", "tenantId", "tenant_id",
            "userId", "user_id", "actorUserId", "actor_user_id",
            "userRole", "user_role", "role", "userInput", "intentCode");

    private final IntentPreviewTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /** Injected separately so the existing two-argument compatibility constructor remains stable. */
    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Override
    @Transactional
    public IntentPreviewToken createBoundToken(BoundTokenRequest request) {
        validateBoundRequest(request);
        LocalDateTime now = LocalDateTime.now();
        JsonNode parametersNode = objectMapper.valueToTree(request.parameters());
        String parametersHash = ToolCommandDigest.parametersHash(parametersNode);
        String commandDigest = ToolCommandDigest.commandDigest(
                request.factoryId(), request.userId(), request.toolName(),
                request.descriptorVersion(), request.executionMode(), parametersNode);

        tokenRepository.cancelPreviousTokens(
                request.factoryId(), request.userId(), request.intentCode(), now);

        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .tenantId(request.factoryId())
                .userId(request.userId())
                .username(request.username())
                .intentCode(request.intentCode())
                .intentName(request.intentName())
                .toolName(request.toolName())
                .descriptorVersion(request.descriptorVersion())
                .executionMode(request.executionMode())
                .parametersHash(parametersHash)
                .commandDigest(commandDigest)
                .entityType(request.entityType())
                .entityId(request.entityId())
                .operation(request.operation())
                .status(TokenStatus.PENDING)
                .expiresAt(now.plusSeconds(request.expiresInSeconds()))
                .build();

        try {
            token.setPreviewData(objectMapper.writeValueAsString(request.parameters()));
            token.setCurrentValues(objectMapper.writeValueAsString(request.currentValues()));
            token.setNewValues(objectMapper.writeValueAsString(request.newValues()));
        } catch (Exception e) {
            throw new IllegalArgumentException("创建绑定预览令牌失败: 数据序列化错误", e);
        }

        IntentPreviewToken saved = tokenRepository.save(token);
        log.info("创建绑定预览令牌: tokenFingerprint={}, factory={}, user={}, intent={}, tool={}, version={}, expires={}s",
                fingerprint(saved.getToken()), request.factoryId(), request.userId(),
                request.intentCode(), request.toolName(), request.descriptorVersion(),
                request.expiresInSeconds());
        return saved;
    }

    @Override
    @Transactional
    public IntentPreviewToken createDraftBoundToken(BoundTokenRequest request,
                                                    String sessionId,
                                                    String expectedFingerprint) {
        validateBoundRequest(request);
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(expectedFingerprint, "expectedFingerprint");
        requireFactoryActor(request.factoryId(), request.userId());
        ConversationSession session = lockDraftSession(
                request.factoryId(), request.userId(), sessionId);
        if (!isValidDraftSession(session, expectedFingerprint)) {
            throw new IllegalStateException("conversation draft is stale or no longer active");
        }
        if (!draftParametersMatch(session, request.parameters())) {
            throw new IllegalStateException("draft collected parameters do not match preview parameters");
        }
        Map<String, Object> currentValues = new LinkedHashMap<>(request.currentValues());
        currentValues.put("_conversationDraft", Map.of(
                "sessionId", sessionId,
                "revision", expectedFingerprint));
        BoundTokenRequest boundRequest = new BoundTokenRequest(
                request.factoryId(), request.userId(), request.username(), request.intentCode(),
                request.intentName(), request.toolName(), request.descriptorVersion(),
                request.executionMode(), request.entityType(), request.entityId(), request.operation(),
                request.parameters(), currentValues, request.newValues(), request.expiresInSeconds());
        return createBoundToken(boundRequest);
    }

    /**
     * Compatibility-only issuance. It deliberately omits command bindings, so the new claim path
     * will reject it fail-closed. No production caller currently uses this method.
     */
    @Override
    @Deprecated(forRemoval = false)
    @Transactional
    public IntentPreviewToken createToken(String factoryId, Long userId, String username,
                                           String intentCode, String intentName,
                                           String entityType, String entityId, String operation,
                                           Map<String, Object> previewData,
                                           Map<String, Object> currentValues,
                                           Map<String, Object> newValues,
                                           int expiresInSeconds) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.cancelPreviousTokens(factoryId, userId, intentCode, now);
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .userId(userId)
                .username(username)
                .intentCode(intentCode)
                .intentName(intentName)
                .entityType(entityType)
                .entityId(entityId)
                .operation(operation)
                .status(TokenStatus.PENDING)
                .expiresAt(now.plusSeconds(expiresInSeconds))
                .build();
        serializeLegacyPayload(token, previewData, currentValues, newValues);
        IntentPreviewToken saved = tokenRepository.save(token);
        log.warn("创建旧版不可执行预览令牌: tokenFingerprint={}, factory={}, user={}, intent={}",
                fingerprint(saved.getToken()), factoryId, userId, intentCode);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntentPreviewToken> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<IntentPreviewToken> tokenOpt = tokenRepository.findByTokenAndStatus(token, TokenStatus.PENDING);
        if (tokenOpt.isEmpty() || tokenOpt.get().isExpired()) {
            log.debug("令牌不可用: tokenFingerprint={}", fingerprint(token));
            return Optional.empty();
        }
        return tokenOpt;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntentPreviewToken> validateTokenForUser(String token, Long userId) {
        return validateToken(token)
                .filter(previewToken -> previewToken.getUserId().equals(userId));
    }

    @Override
    @Deprecated(forRemoval = false)
    public ConfirmResult confirmToken(String token, Long userId) {
        log.warn("拒绝旧版非原子确认入口: tokenFingerprint={}, user={}", fingerprint(token), userId);
        return ConfirmResult.failure("旧版确认入口已停用，请重新发起预览");
    }

    @Override
    @Transactional
    public ClaimResult claimToken(String token, String factoryId, Long userId) {
        return claimTokenInternal(token, factoryId, userId, null);
    }

    @Override
    @Transactional
    public ClaimResult claimToken(String token, String factoryId, Long userId,
                                  String proofCommandDigest) {
        return claimTokenInternal(token, factoryId, userId, proofCommandDigest);
    }

    private ClaimResult claimTokenInternal(String token, String factoryId, Long userId,
                                           String proofCommandDigest) {
        String tokenFingerprint = fingerprint(token);
        if (token == null || token.isBlank() || factoryId == null || factoryId.isBlank() || userId == null) {
            return ClaimResult.failure("令牌无效或已过期");
        }

        // Every production claim shares the conversation worker's owner mutex. Typed factory
        // actor proof is intentionally deferred until the token is known to be draft-bound, so
        // legacy/system confirmation tickets retain their existing principal contract.
        if (conversationSessionRepository != null) {
            lockActor(factoryId, userId);
        }

        Optional<IntentPreviewToken> existingOpt = tokenRepository.findByToken(token);
        if (existingOpt.isEmpty()) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=not-found", tokenFingerprint);
            return ClaimResult.failure("令牌无效或已过期");
        }

        IntentPreviewToken existing = existingOpt.get();
        if (!factoryId.equals(existing.getFactoryId())
                || !factoryId.equals(existing.getTenantId())
                || !userId.equals(existing.getUserId())) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=principal-mismatch", tokenFingerprint);
            return ClaimResult.failure("令牌与当前工厂或用户不匹配");
        }
        if (existing.getStatus() != TokenStatus.PENDING) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=status-{}",
                    tokenFingerprint, existing.getStatus());
            return ClaimResult.failure("令牌已被处理: " + existing.getStatus());
        }

        Optional<DraftBinding> draftBinding = readDraftBinding(existing.getCurrentValues());
        if (hasDraftBindingMarker(existing.getCurrentValues())) {
            if (conversationSessionRepository == null) {
                throw new IllegalStateException("draft-bound claim requires ConversationSessionRepository");
            }
            if (!isCurrentFactoryActor(factoryId, userId)) {
                return ClaimResult.failure("草稿预览需要当前工厂用户主体");
            }
        }
        if (hasDraftBindingMarker(existing.getCurrentValues())
                && (draftBinding.isEmpty()
                || !isValidDraftBinding(factoryId, userId, draftBinding.get()))) {
            int cancelled = tokenRepository.cancelPendingToken(
                    token, factoryId, userId, "会话草稿已变化，预览已失效",
                    LocalDateTime.now(), TokenStatus.PENDING, TokenStatus.CANCELLED);
            if (cancelled != 1) {
                throw new IllegalStateException("draft-bound preview invalidation lost its conditional update");
            }
            return ClaimResult.failure("会话草稿已变化，请重新发起预览");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(existing.getExpiresAt())) {
            tokenRepository.expirePendingToken(token, factoryId, userId, now,
                    TokenStatus.PENDING, TokenStatus.EXPIRED);
            return ClaimResult.failure("令牌已过期，请重新发起预览");
        }
        if (!existing.isBoundForExecution() || existing.getExecutionMode() != ToolExecutionMode.EXECUTE) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=unbound-or-mode", tokenFingerprint);
            return ClaimResult.failure("预览令牌未绑定可执行命令，请重新发起预览");
        }

        JsonNode parametersNode;
        try {
            parametersNode = objectMapper.readTree(existing.getPreviewData());
        } catch (Exception e) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=invalid-parameters", tokenFingerprint);
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        if (parametersNode == null || !parametersNode.isObject()) {
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }

        String expectedParametersHash = ToolCommandDigest.parametersHash(parametersNode);
        String expectedCommandDigest = ToolCommandDigest.commandDigest(
                existing.getFactoryId(), existing.getUserId(), existing.getToolName(),
                existing.getDescriptorVersion(), existing.getExecutionMode(), parametersNode);
        if (!constantTimeEquals(expectedParametersHash, existing.getParametersHash())
                || !constantTimeEquals(expectedCommandDigest, existing.getCommandDigest())) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=digest-mismatch", tokenFingerprint);
            return ClaimResult.failure("预览命令完整性校验失败，请重新发起预览");
        }
        if (proofCommandDigest != null) {
            if (proofCommandDigest.isBlank()
                    || !constantTimeEquals(expectedCommandDigest, proofCommandDigest)) {
                log.warn("确认认领失败: tokenFingerprint={}, reason=proof-digest-mismatch", tokenFingerprint);
                return ClaimResult.failure("确认凭据与预览命令不匹配");
            }
        }

        String claimId = UUID.randomUUID().toString();
        int claimed = tokenRepository.claimForExecution(
                token, factoryId, factoryId, userId, expectedCommandDigest,
                expectedParametersHash, existing.getToolName(), existing.getDescriptorVersion(),
                existing.getExecutionMode(), claimId, now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING);
        if (claimed != 1) {
            log.warn("确认认领失败: tokenFingerprint={}, reason=concurrent-or-stale", tokenFingerprint);
            return ClaimResult.failure("令牌已被其他确认请求处理");
        }

        Optional<IntentPreviewToken> claimedTokenOpt = tokenRepository.findByTokenAndClaimIdAndStatus(
                token, claimId, TokenStatus.EXECUTING);
        if (claimedTokenOpt.isEmpty()) {
            resolveClaim(token, claimId, false, "认领后确认状态读取失败");
            return ClaimResult.failure("确认状态读取失败，请勿重复提交");
        }
        IntentPreviewToken claimedToken = claimedTokenOpt.get();
        JsonNode claimedParametersNode;
        try {
            claimedParametersNode = objectMapper.readTree(claimedToken.getPreviewData());
        } catch (Exception e) {
            resolveClaim(token, claimId, false, "认领后参数解析失败");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        if (claimedParametersNode == null || !claimedParametersNode.isObject()) {
            resolveClaim(token, claimId, false, "认领后参数不是JSON对象");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }
        String claimedDigest = ToolCommandDigest.commandDigest(
                claimedToken.getFactoryId(), claimedToken.getUserId(), claimedToken.getToolName(),
                claimedToken.getDescriptorVersion(), claimedToken.getExecutionMode(), claimedParametersNode);
        if (!constantTimeEquals(claimedDigest, claimedToken.getCommandDigest())) {
            resolveClaim(token, claimId, false, "认领后命令完整性校验失败");
            return ClaimResult.failure("预览命令完整性校验失败，请重新发起预览");
        }
        Map<String, Object> parameters;
        try {
            parameters = parseParameters(claimedToken.getPreviewData());
        } catch (IllegalArgumentException e) {
            resolveClaim(token, claimId, false, "认领后执行参数解析失败");
            return ClaimResult.failure("预览参数无效，请重新发起预览");
        }

        log.info("确认令牌已原子认领: tokenFingerprint={}, claimFingerprint={}, factory={}, user={}, tool={}",
                tokenFingerprint, fingerprint(claimId), factoryId, userId, claimedToken.getToolName());
        return ClaimResult.success(claimedToken, claimId, parameters);
    }

    @Override
    @Transactional
    public boolean resolveClaim(String token, String claimId, boolean success, String message) {
        if (token == null || token.isBlank() || claimId == null || claimId.isBlank()) {
            return false;
        }
        TokenStatus resolvedStatus = success ? TokenStatus.CONFIRMED : TokenStatus.FAILED;
        int updated = tokenRepository.resolveClaim(
                token, claimId, TokenStatus.EXECUTING, resolvedStatus,
                LocalDateTime.now(), truncateMessage(message));
        log.info("确认令牌完成: tokenFingerprint={}, claimFingerprint={}, status={}, updated={}",
                fingerprint(token), fingerprint(claimId), resolvedStatus, updated);
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPendingDraftPreview(String factoryId, Long userId,
                                           String sessionId, String revision) {
        if (factoryId == null || factoryId.isBlank() || userId == null
                || sessionId == null || sessionId.isBlank()
                || revision == null || revision.isBlank()
                || !isCurrentFactoryActor(factoryId, userId)
                || conversationSessionRepository == null) {
            return false;
        }
        return hasPendingDraftPreviewInternal(factoryId, userId, sessionId, revision);
    }

    @Override
    @Transactional
    public boolean cancelToken(String token, String factoryId, Long userId, String reason) {
        int cancelled = tokenRepository.cancelPendingToken(
                token, factoryId, userId, truncateMessage(reason != null ? reason : "用户取消"),
                LocalDateTime.now(), TokenStatus.PENDING, TokenStatus.CANCELLED);
        log.info("取消预览令牌: tokenFingerprint={}, cancelled={}", fingerprint(token), cancelled);
        return cancelled == 1;
    }

    @Override
    @Transactional
    public boolean invalidateForRevision(String factoryId, Long userId, String intentCode) {
        if (factoryId == null || factoryId.isBlank() || userId == null
                || intentCode == null || intentCode.isBlank()) {
            return false;
        }
        if (AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER
                        && userId.equals(actor.id())
                        && factoryId.equals(actor.factoryId()))
                .isEmpty()) {
            log.warn("拒绝预览修订失效: reason=missing-or-mismatched-factory-actor, factory={}, user={}, intent={}",
                    factoryId, userId, intentCode);
            return false;
        }
        if (conversationSessionRepository == null) {
            throw new IllegalStateException("预览修订失效缺少ConversationSessionRepository");
        }
        lockActor(factoryId, userId);
        if (entityManager == null) {
            throw new IllegalStateException("预览修订失效缺少JPA EntityManager");
        }

        List<IntentPreviewToken> activeTokens = tokenRepository
                .findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                        factoryId, userId, intentCode, TokenStatus.PENDING, TokenStatus.EXECUTING);

        // A repository query can return a previously managed instance. Refresh under the same
        // pessimistic lock so a concurrent claim cannot be hidden by a stale PENDING snapshot.
        activeTokens.forEach(token -> entityManager.refresh(token, LockModeType.PESSIMISTIC_WRITE));

        // A ticket without the trusted tenant binding is not owned by this factory actor.
        // Do not cancel it or report a successful invalidation in that case.
        for (IntentPreviewToken token : activeTokens) {
            if (!factoryId.equals(token.getFactoryId())
                    || !factoryId.equals(token.getTenantId())
                    || !userId.equals(token.getUserId())
                    || !intentCode.equals(token.getIntentCode())) {
                log.warn("拒绝预览修订失效: reason=owner-scope-mismatch, factory={}, user={}, intent={}",
                        factoryId, userId, intentCode);
                return false;
            }
            if (token.getStatus() == TokenStatus.EXECUTING) {
                log.warn("拒绝预览修订失效: reason=executing-ticket, factory={}, user={}, intent={}",
                        factoryId, userId, intentCode);
                return false;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (IntentPreviewToken token : activeTokens) {
            if (token.getStatus() == TokenStatus.PENDING) {
                int cancelled = tokenRepository.cancelPendingToken(
                        token.getToken(), factoryId, userId, "被修订前的预览失效操作替代",
                        now, TokenStatus.PENDING, TokenStatus.CANCELLED);
                if (cancelled != 1) {
                    // The row lock normally makes this impossible. Throw so Spring rolls back
                    // every cancellation already issued in this transaction instead of leaving
                    // a partially invalidated revision state behind.
                    log.warn("预览修订失效条件更新未命中: factory={}, user={}, intent={}",
                            factoryId, userId, intentCode);
                    throw new IllegalStateException("预览修订失效条件更新未命中");
                }
            }
        }
        return true;
    }

    @Override
    @Transactional
    public boolean invalidateDraftForRevision(String factoryId, Long userId, String intentCode,
                                              String sessionId, String expectedFingerprint) {
        if (factoryId == null || factoryId.isBlank() || userId == null
                || intentCode == null || intentCode.isBlank()
                || sessionId == null || sessionId.isBlank()
                || expectedFingerprint == null || expectedFingerprint.isBlank()) {
            return false;
        }
        if (!isCurrentFactoryActor(factoryId, userId)
                || conversationSessionRepository == null || entityManager == null) {
            return false;
        }
        lockActor(factoryId, userId);
        ConversationSession session = conversationSessionRepository
                .findBySessionIdAndFactoryIdAndUserIdForUpdate(sessionId, factoryId, userId)
                .orElse(null);
        if (session == null || !isValidDraftInvalidationSession(session, expectedFingerprint)) {
            return false;
        }

        List<IntentPreviewToken> activeTokens = tokenRepository
                .findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                        factoryId, userId, intentCode, TokenStatus.PENDING, TokenStatus.EXECUTING);
        activeTokens.forEach(token -> entityManager.refresh(token, LockModeType.PESSIMISTIC_WRITE));
        List<IntentPreviewToken> matching = activeTokens.stream()
                .filter(token -> factoryId.equals(token.getFactoryId())
                        && factoryId.equals(token.getTenantId())
                        && userId.equals(token.getUserId())
                        && intentCode.equals(token.getIntentCode()))
                .filter(token -> readDraftBinding(token.getCurrentValues())
                        .filter(binding -> sessionId.equals(binding.sessionId())
                                && expectedFingerprint.equals(binding.revision()))
                        .isPresent())
                .toList();
        if (matching.stream().anyMatch(token -> token.getStatus() == TokenStatus.EXECUTING)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        List<IntentPreviewToken> livePending = matching.stream()
                .filter(token -> token.getStatus() == TokenStatus.PENDING
                        && token.getExpiresAt() != null
                        && token.getExpiresAt().isAfter(now))
                .toList();
        if (session.getStatus() == ConversationSession.SessionStatus.COMPLETED
                && livePending.isEmpty()) {
            return false;
        }
        for (IntentPreviewToken token : matching) {
            if (livePending.contains(token)
                    && tokenRepository.cancelPendingToken(
                    token.getToken(), factoryId, userId, "指定会话草稿修订已失效", now,
                    TokenStatus.PENDING, TokenStatus.CANCELLED) != 1) {
                throw new IllegalStateException("draft revision invalidation conditional update lost");
            }
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getPreviewData(String token) {
        return validateToken(token).flatMap(previewToken -> {
            try {
                return Optional.of(parseParameters(previewToken.getPreviewData()));
            } catch (IllegalArgumentException e) {
                log.warn("解析预览数据失败: tokenFingerprint={}", fingerprint(token));
                return Optional.empty();
            }
        });
    }

    @Override
    @Transactional
    @Scheduled(fixedRate = 60000)
    public int processExpiredTokens() {
        int expired = tokenRepository.expireOldTokens(LocalDateTime.now());
        if (expired > 0) {
            log.info("处理过期令牌: count={}", expired);
        }
        return expired;
    }

    @Override
    @Transactional
    public int cleanupOldTokens(int daysToKeep) {
        int deleted = tokenRepository.deleteOldResolvedTokens(LocalDateTime.now().minusDays(daysToKeep));
        if (deleted > 0) {
            log.info("清理历史令牌: deleted={}, keepDays={}", deleted, daysToKeep);
        }
        return deleted;
    }

    private void requireFactoryActor(String factoryId, Long userId) {
        if (!isCurrentFactoryActor(factoryId, userId)) {
            throw new IllegalStateException("draft-bound preview requires the current factory actor");
        }
    }

    private boolean isCurrentFactoryActor(String factoryId, Long userId) {
        return AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER
                        && userId != null && userId.equals(actor.id())
                        && factoryId != null && factoryId.equals(actor.factoryId()))
                .isPresent();
    }

    /** Same lock order as ConversationServiceImpl: owner advisory, then owned mutable rows. */
    private void lockActor(String factoryId, Long userId) {
        conversationSessionRepository.lockActorAdvisory(ownerKey(factoryId, userId));
        conversationSessionRepository.findByFactoryIdAndUserIdForUpdate(factoryId, userId);
    }

    private String ownerKey(String factoryId, Long userId) {
        String factory = factoryId == null ? "" : factoryId;
        return factory.length() + ":" + factory + ":" + String.valueOf(userId);
    }

    private ConversationSession lockDraftSession(String factoryId, Long userId, String sessionId) {
        if (conversationSessionRepository == null) {
            throw new IllegalStateException("draft-bound preview requires ConversationSessionRepository");
        }
        lockActor(factoryId, userId);
        return conversationSessionRepository.findBySessionIdAndFactoryIdAndUserIdForUpdate(
                        sessionId, factoryId, userId)
                .orElseThrow(() -> new IllegalStateException("conversation draft session not found"));
    }

    private boolean isValidDraftSession(ConversationSession session, String expectedFingerprint) {
        if (session == null || session.getStatus() == null || session.isExpired()) {
            return false;
        }
        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE
                && session.getStatus() != ConversationSession.SessionStatus.COMPLETED) {
            return false;
        }
        if (entityManager == null) {
            throw new IllegalStateException("draft-bound preview requires JPA EntityManager");
        }
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        return (session.getStatus() == ConversationSession.SessionStatus.ACTIVE
                || session.getStatus() == ConversationSession.SessionStatus.COMPLETED)
                && !session.isExpired()
                && expectedFingerprint.equals(ConversationService.draftRevisionFingerprint(session));
    }

    private boolean isValidDraftBinding(String factoryId, Long userId, DraftBinding binding) {
        if (conversationSessionRepository == null || entityManager == null) {
            throw new IllegalStateException("draft-bound claim requires JPA conversation state");
        }
        Optional<ConversationSession> session = conversationSessionRepository
                .findBySessionIdAndFactoryIdAndUserIdForUpdate(binding.sessionId(), factoryId, userId);
        if (session.isEmpty()) {
            return false;
        }
        ConversationSession locked = session.get();
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        return isValidDraftSession(locked, binding.revision())
                && binding.revision().equals(ConversationService.draftRevisionFingerprint(locked));
    }

    private boolean isValidDraftInvalidationSession(ConversationSession session,
                                                    String expectedFingerprint) {
        if (session == null || session.getStatus() == null || session.isExpired()) {
            return false;
        }
        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE
                && session.getStatus() != ConversationSession.SessionStatus.PAUSED
                && session.getStatus() != ConversationSession.SessionStatus.COMPLETED) {
            return false;
        }
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        return (session.getStatus() == ConversationSession.SessionStatus.ACTIVE
                || session.getStatus() == ConversationSession.SessionStatus.PAUSED
                || session.getStatus() == ConversationSession.SessionStatus.COMPLETED)
                && !session.isExpired()
                && expectedFingerprint.equals(ConversationService.draftRevisionFingerprint(session));
    }

    private boolean draftParametersMatch(ConversationSession session, Map<String, Object> parameters) {
        if (session == null || parameters == null) {
            return false;
        }
        Map<String, String> collected = session.getCollectedParameters();
        for (String key : parameters.keySet()) {
            if (!collected.containsKey(key) && !DRAFT_TECHNICAL_PARAMETER_KEYS.contains(key)) {
                return false;
            }
        }
        for (Map.Entry<String, String> entry : collected.entrySet()) {
            if (!parameters.containsKey(entry.getKey())
                    || !equivalentDraftValue(entry.getValue(), parameters.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean equivalentDraftValue(String expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        try {
            return new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))) == 0;
        } catch (NumberFormatException ignored) {
            return expected.equals(String.valueOf(actual));
        }
    }

    private boolean hasPendingDraftPreviewInternal(String factoryId, Long userId,
                                                    String sessionId, String revision) {
        List<IntentPreviewToken> pending = tokenRepository
                .findByFactoryIdAndTenantIdAndUserIdAndStatus(
                        factoryId, factoryId, userId, TokenStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        return pending.stream()
                .filter(token -> token.getExpiresAt() != null && token.getExpiresAt().isAfter(now))
                .map(token -> readDraftBinding(token.getCurrentValues()).orElse(null))
                .anyMatch(binding -> binding != null
                        && sessionId.equals(binding.sessionId())
                        && revision.equals(binding.revision()));
    }

    private Optional<DraftBinding> readDraftBinding(String currentValuesJson) {
        if (currentValuesJson == null || currentValuesJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(currentValuesJson);
            JsonNode binding = root == null ? null : root.get("_conversationDraft");
            if (binding == null || !binding.isObject()
                    || binding.get("sessionId") == null || binding.get("revision") == null
                    || !binding.get("sessionId").isTextual() || !binding.get("revision").isTextual()
                    || binding.get("sessionId").asText().isBlank()
                    || binding.get("revision").asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DraftBinding(
                    binding.get("sessionId").asText(), binding.get("revision").asText()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean hasDraftBindingMarker(String currentValuesJson) {
        if (currentValuesJson == null || currentValuesJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(currentValuesJson);
            return root != null && root.isObject() && root.has("_conversationDraft");
        } catch (Exception e) {
            return false;
        }
    }

    private record DraftBinding(String sessionId, String revision) {
    }

    private void validateBoundRequest(BoundTokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("bound token request is required");
        }
        requireNonBlank(request.factoryId(), "factoryId");
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        requireNonBlank(request.intentCode(), "intentCode");
        requireNonBlank(request.toolName(), "toolName");
        requireNonBlank(request.descriptorVersion(), "descriptorVersion");
        if (request.executionMode() != ToolExecutionMode.EXECUTE) {
            throw new IllegalArgumentException("confirmation tokens require EXECUTE mode");
        }
        if (request.expiresInSeconds() <= 0) {
            throw new IllegalArgumentException("expiresInSeconds must be positive");
        }
    }

    private void serializeLegacyPayload(IntentPreviewToken token,
                                        Map<String, Object> previewData,
                                        Map<String, Object> currentValues,
                                        Map<String, Object> newValues) {
        try {
            if (previewData != null) {
                token.setPreviewData(objectMapper.writeValueAsString(previewData));
            }
            if (currentValues != null) {
                token.setCurrentValues(objectMapper.writeValueAsString(currentValues));
            }
            if (newValues != null) {
                token.setNewValues(objectMapper.writeValueAsString(newValues));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("创建预览令牌失败: 数据序列化错误", e);
        }
    }

    private Map<String, Object> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid preview parameters", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String truncateMessage(String message) {
        String safe = message == null || message.isBlank() ? "未提供结果说明" : message;
        return safe.length() <= MAX_RESOLUTION_MESSAGE_LENGTH
                ? safe
                : safe.substring(0, MAX_RESOLUTION_MESSAGE_LENGTH);
    }

    private static String fingerprint(String value) {
        return ToolCommandDigest.tokenFingerprint(value);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}

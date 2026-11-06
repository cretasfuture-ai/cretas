package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless coordinator for repairing or resuming an existing conversation task.
 * It never creates a task store and never executes a business tool.
 */
@Slf4j
@Service
public class ConversationTaskRepairSupport {
    private static final String EGRES_SITE = "conversation-task-repair";
    private static final Set<String> ACTIONS = Set.of(
            "CONTINUE", "CORRECT", "INTERRUPT", "SWITCH", "CANCEL", "RESUME", "CLARIFY", "UNSUPPORTED");
    private static final Set<String> BLOCKED_FIELD_PARTS = Set.of(
            "factory", "tenant", "userid", "actor", "session", "intent", "tool", "permission", "role", "auth");
    private static final Set<String> SAFE_VALUE_FIELDS = Set.of(
            "quantity", "actualquantity", "qty", "unit", "quantityunit", "weight",
            "date", "startdate", "enddate", "time", "starttime", "endtime", "day");
    private static final Set<String> UNIT_VALUE_FIELDS = Set.of(
            "quantity", "actualquantity", "qty", "unit", "quantityunit", "weight");
    private static final Pattern NUMERIC_UNIT = Pattern.compile(
            "(?<![\\p{N}])[-+]?\\d+(?:[.,]\\d+)?\\s*([\\p{L}%]+)");

    private final ConversationService conversations;
    private final DashScopeClient dashScopeClient;
    private final PreviewTokenService previewTokenService;
    @Autowired
    private ConversationDraftTransitionBoundary transitionBoundary;

    @Autowired(required = false)
    private LlmEgressGuard llmEgressGuard = LlmEgressGuard.standalone();
    @Autowired(required = false)
    private IntentConfigManagementService intentConfigManagementService;
    @Autowired(required = false)
    private ToolRegistry toolRegistry;
    @Autowired(required = false)
    private UnitContractService unitContractService;
    @Autowired(required = false)
    private TimeNormalizationRules timeNormalizationRules;
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public ConversationTaskRepairSupport(ConversationService conversations,
                                         DashScopeClient dashScopeClient,
                                         PreviewTokenService previewTokenService) {
        this.conversations = conversations;
        this.dashScopeClient = dashScopeClient;
        this.previewTokenService = previewTokenService;
    }

    /**
     * Prepare one state-safe repair decision. A null result means the normal
     * intent chain should continue with the request's server-only mutations.
     */
    public IntentExecuteResponse prepare(String factoryId, Long userId, IntentExecuteRequest request) {
        if (!isTrustedFactoryActor(factoryId, userId) || request == null || factoryId == null || userId == null) {
            return null;
        }

        List<Draft> drafts = findDrafts(factoryId, userId, request);
        Map<String, Object> committed = committedSummary(request.getSemanticContext());
        if (drafts.isEmpty() && committed.isEmpty()) {
            return null;
        }
        putSafeDraftContext(request, drafts, committed);
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            request.setSemanticTaskAction("CLARIFY");
            return reply("NEED_MORE_INFO", "当前对话状态无法确认，请重新说明要继续、修改还是取消。", null,
                    Map.of("taskAction", "CLARIFY"));
        }

        Decision decision = askModel(factoryId, request, drafts, committed);
        if (decision == null || !ACTIONS.contains(decision.action())) {
            request.setSemanticTaskAction("UNSUPPORTED");
            return reply("UNSUPPORTED", "无法确认当前对话任务，未修改任何业务状态。", null,
                    Map.of("taskAction", "UNSUPPORTED"));
        }
        request.setSemanticTaskAction(decision.action());

        boolean currentReadPresent = hasCurrentRead(request.getSemanticContext());
        if ("current-read".equalsIgnoreCase(Optional.ofNullable(decision.taskReference()).orElse(""))) {
            if (!currentReadPresent && drafts.isEmpty() && !committed.isEmpty()) {
                if ("SWITCH".equals(decision.action())) {
                    request.setSessionId(null);
                    return null;
                }
                return reply("UNSUPPORTED", "上一项业务已经提交，当前没有可修订的对话草稿。", null,
                        metadata(decision, null, Map.of()));
            }
            if (currentReadPresent
                    && Set.of("CONTINUE", "CORRECT", "CANCEL", "SWITCH", "INTERRUPT").contains(decision.action())) {
                request.setSemanticTaskAction("READ_CONTINUE");
                // This is the existing Memory read, not a new draft. The caller
                // refreshes context immediately; retain its owner/session/version.
                // READ_CONTINUE already excludes the old slot-collection branch.
                return null;
            }
            request.setSemanticTaskAction("CLARIFY");
            return reply("NEED_MORE_INFO", "当前只读任务状态不足，请明确要继续当前查询还是处理暂停任务。", null,
                    Map.of("taskAction", "CLARIFY"));
        }

        if ("RESUME".equals(decision.action())) {
            Draft paused = choosePaused(decision.taskReference(), request, drafts);
            if (paused == null) {
                return resumeClarification(drafts);
            }
            String pausedRevision = ConversationService.draftRevisionFingerprint(paused.session());
            if (!conversations.resumeConversation(factoryId, userId, paused.session().getSessionId())) {
                return reply("TASK_CHANGED", "暂停任务已变化，未恢复旧任务。", null,
                        metadata(decision, paused, Map.of()));
            }
            request.setSessionId(paused.session().getSessionId());
            if (decision.resumeAfterRead() && hasResumeEvidence(request, decision.resumeEvidence())) {
                request.setSemanticResumeDraftId(paused.session().getSessionId());
                request.setSemanticResumeDraftRevision(pausedRevision);
            }
            return reply("NEED_MORE_INFO", safeDraftMessage(paused), paused.session().getSessionId(),
                    metadata(decision, paused, Map.of()));
        }

        Draft selected = chooseDraft(decision.taskReference(), request, drafts);
        if (selected == null && !drafts.isEmpty()) {
            return reply("NEED_MORE_INFO", "请明确要继续哪一个对话任务。", null,
                    Map.of("taskAction", "CLARIFY", "drafts", safeDraftList(drafts)));
        }
        if (selected == null) {
            // A committed server result is evidence of a past action, not a draft
            // that can be corrected or replayed from client-provided values.  An
            // explicit SWITCH is nevertheless a new target, so let the normal
            // chain recognize it instead of making the old commit a permanent
            // gate on subsequent queries.
            if ("SWITCH".equals(decision.action())) {
                request.setSessionId(null);
                return null;
            }
            return reply("UNSUPPORTED", "上一项业务已经提交，当前没有可修订的对话草稿。", null,
                    metadata(decision, null, Map.of()));
        }
        if (currentReadPresent && "CANCEL".equals(decision.action())
                && !explicitDraftSelection(request, selected)) {
            return reply("NEED_MORE_INFO", "当前同时存在只读任务和对话草稿，请明确要取消哪一个。", null,
                    Map.of("taskAction", "CLARIFY", "currentRead", "current-read",
                            "taskReference", selected.opaqueId()));
        }

        if (decision.resumeAfterRead() && hasResumeEvidence(request, decision.resumeEvidence())
                && selected.session().getStatus() == ConversationSession.SessionStatus.PAUSED) {
            request.setSemanticResumeDraftId(selected.session().getSessionId());
        }
        return applyDecision(factoryId, userId, request, decision, selected);
    }

    /** Re-project the current owned drafts without invoking the model or mutating state. */
    public void refreshContext(String factoryId, Long userId, IntentExecuteRequest request) {
        if (!isTrustedFactoryActor(factoryId, userId) || request == null) return;
        putSafeDraftContext(request, findDrafts(factoryId, userId, request),
                committedSummary(request.getSemanticContext()));
    }

    /** Root may continue the old slot chain only before an explicit repair action. */
    public static boolean shouldContinueExistingTask(IntentExecuteRequest request) {
        if (request == null || request.getSemanticTaskAction() == null
                || request.getSemanticTaskAction().isBlank()) return true;
        return "CONTINUE".equalsIgnoreCase(request.getSemanticTaskAction());
    }

    private IntentExecuteResponse applyDecision(String factoryId, Long userId, IntentExecuteRequest request,
                                                Decision decision, Draft selected) {
        ConversationSession session = selected.session();
        boolean pendingPreviewProof = session.getStatus() == ConversationSession.SessionStatus.COMPLETED
                && hasPendingPreview(factoryId, userId, session);
        String intentCode = session.getKnownIntentCode() != null
                ? session.getKnownIntentCode() : request.getIntentCode();
        switch (decision.action()) {
            case "CONTINUE" -> {
                if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
                    return reply("NEED_MORE_INFO", "该任务已暂停，请明确恢复后再继续。", session.getSessionId(),
                            metadata(decision, selected, Map.of()));
                }
                request.setSessionId(session.getSessionId());
                return null;
            }
            case "CORRECT" -> {
                Map<String, String> delta = validateDelta(factoryId, userId, request, session, decision);
                if (delta == null || delta.isEmpty()) {
                    return reply("NEED_MORE_INFO", "请提供可核对的字段值或单位，未修改草稿。", session.getSessionId(),
                            metadata(decision, selected, Map.of()));
                }
                String expected = ConversationService.draftRevisionFingerprint(session);
                try {
                    transitionBoundary.run(() -> {
                        requireTransition(previewTokenService.invalidateDraftForRevision(
                                factoryId, userId, intentCode, session.getSessionId(), expected));
                        requireTransition(conversations.reviseDraft(factoryId, userId, session.getSessionId(), delta,
                                expected, pendingPreviewProof));
                        return null;
                    });
                } catch (TransitionRejected rejected) {
                    return reply("TASK_CHANGED", "草稿已变化，未应用本轮修订。", null,
                            metadata(decision, selected, delta));
                }
                request.setSessionId(session.getSessionId());
                request.setSemanticTaskAction("CONTINUE");
                return null;
            }
            case "INTERRUPT" -> {
                String expected = ConversationService.draftRevisionFingerprint(session);
                try {
                    transitionBoundary.run(() -> {
                        requireTransition(previewTokenService.invalidateDraftForRevision(
                                factoryId, userId, intentCode, session.getSessionId(), expected));
                        requireTransition(conversations.pauseConversation(factoryId, userId, session.getSessionId(),
                                pendingPreviewProof));
                        return null;
                    });
                } catch (TransitionRejected rejected) {
                    return reply("TASK_CHANGED", "当前任务已变化，未暂停旧任务。", null,
                            metadata(decision, selected, Map.of()));
                }
                if (decision.resumeAfterRead() && hasResumeEvidence(request, decision.resumeEvidence())) {
                    rememberResumeRevision(factoryId, userId, request, session.getSessionId());
                }
                request.setSessionId(null);
                // The caller must continue the current request as a new target;
                // returning a response here would swallow that target.
                return null;
            }

            case "CANCEL" -> {
                String expected = ConversationService.draftRevisionFingerprint(session);
                try {
                    transitionBoundary.run(() -> {
                        requireTransition(previewTokenService.invalidateDraftForRevision(
                                factoryId, userId, intentCode, session.getSessionId(), expected));
                        requireTransition(conversations.cancelConversation(factoryId, userId, session.getSessionId(),
                                pendingPreviewProof));
                        return null;
                    });
                } catch (TransitionRejected rejected) {
                    return reply("TASK_CHANGED", "当前任务已变化，未取消旧任务。", null,
                            metadata(decision, selected, Map.of()));
                }
                return reply("CANCELLED", "已取消当前对话任务；已提交的业务不会被撤销。", session.getSessionId(),
                        metadata(decision, selected, Map.of()));
            }
            case "SWITCH" -> {
                String expected = ConversationService.draftRevisionFingerprint(session);
                try {
                    transitionBoundary.run(() -> {
                        requireTransition(previewTokenService.invalidateDraftForRevision(
                                factoryId, userId, intentCode, session.getSessionId(), expected));
                        requireTransition(conversations.cancelConversation(factoryId, userId, session.getSessionId(),
                                pendingPreviewProof));
                        return null;
                    });
                } catch (TransitionRejected rejected) {
                    return reply("TASK_CHANGED", "当前任务已变化，未切换旧任务。", null,
                            metadata(decision, selected, Map.of()));
                }
                request.setSessionId(null);
                return null;
            }
            case "CLARIFY", "UNSUPPORTED" -> {
                return reply("CLARIFY".equals(decision.action()) ? "NEED_MORE_INFO" : "UNSUPPORTED",
                        safeMessage(decision.message()), session.getSessionId(), metadata(decision, selected, Map.of()));
            }
            default -> {
                return reply("UNSUPPORTED", "当前任务动作不受支持，未修改状态。", null,
                        metadata(decision, selected, Map.of()));
            }
        }
    }

    /** Resume a paused draft after a successful read, guarded by the observed revision. */
    public IntentExecuteResponse afterRead(String factoryId, Long userId, IntentExecuteRequest request,
                                           IntentExecuteResponse response) {
        if (!isTrustedFactoryActor(factoryId, userId) || request == null || response == null
                || !readCompleted(response.getStatus())) return response;
        String sessionId = request.getSemanticResumeDraftId();
        String revision = request.getSemanticResumeDraftRevision();
        if (sessionId == null || sessionId.isBlank() || revision == null || revision.isBlank()) return response;
        boolean resumed = conversations.resumeConversation(factoryId, userId, sessionId, revision);
        Map<String, Object> metadata = response.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata());
        metadata.put("resumeAfterRead", resumed ? "RESUMED" : "NOT_RESUMED_STALE");
        response.setMetadata(metadata);
        if (resumed) request.setSemanticTaskAction("RESUME");
        return response;
    }

    private boolean readCompleted(String status) {
        return status != null && ("SUCCESS".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status));
    }

    private void rememberResumeRevision(String factoryId, Long userId, IntentExecuteRequest request,
                                        String sessionId) {
        conversations.getSession(factoryId, userId, sessionId)
                .filter(s -> s.getStatus() == ConversationSession.SessionStatus.PAUSED)
                .ifPresent(s -> {
                    request.setSemanticResumeDraftId(sessionId);
                    request.setSemanticResumeDraftRevision(ConversationService.draftRevisionFingerprint(s));
                });
    }

    private Map<String, String> validateDelta(String factoryId, Long userId, IntentExecuteRequest request,
                                              ConversationSession session, Decision decision) {
        if (decision.changes().isEmpty()) return Map.of();
        Map<String, Object> schema = schemaFor(factoryId, session.getKnownIntentCode());
        if (schema.isEmpty()) return null;
        Map<String, Object> existing = new LinkedHashMap<>();
        session.getCollectedParameters().forEach(existing::put);
        Map<String, Object> grounded = LlmParameterGrounding.ground(
                new LinkedHashMap<>(decision.changes()), existing,
                Optional.ofNullable(request.getUserInput()).orElse(""), schema).accepted();
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : grounded.entrySet()) {
            if (!schemaProperties(schema).containsKey(entry.getKey()) || !safeField(entry.getKey())) continue;
            String value = String.valueOf(entry.getValue());
            if (entry.getKey().toLowerCase(Locale.ROOT).contains("id")
                    && !Optional.ofNullable(request.getUserInput()).orElse("").contains(value)) continue;
            accepted.put(entry.getKey(), value);
        }
        // Identifier fields are not free-text slots in LlmParameterGrounding;
        // accept them only when the exact value is present in this turn.
        String utterance = Optional.ofNullable(request.getUserInput()).orElse("");
        for (Map.Entry<String, String> entry : decision.changes().entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).contains("id")
                    && schemaProperties(schema).containsKey(entry.getKey()) && safeField(entry.getKey())
                    && entry.getValue() != null && utterance.contains(entry.getValue())) {
                accepted.put(entry.getKey(), entry.getValue());
            }
        }
        String invalid = ToolDispatchService.validateReadParameters(schema,
                new LinkedHashMap<>(accepted), factoryId, false);
        if (invalid != null) return null;
        if (!validateUnitEvidence(factoryId, session, accepted, decision.spokenUnit(), utterance)) return null;
        if (!dateEvidence(request, accepted)) return null;
        return accepted;
    }

    private boolean validateUnitEvidence(String factoryId, ConversationSession session,
                                         Map<String, String> accepted, String spokenUnit, String utterance) {
        boolean unitSensitive = accepted.keySet().stream()
                .map(k -> k.toLowerCase(Locale.ROOT)).anyMatch(UNIT_VALUE_FIELDS::contains);
        boolean hasSpokenUnit = spokenUnit != null && !spokenUnit.isBlank();
        if (!unitSensitive && !hasSpokenUnit) return true;

        String knownUnit = knownUnit(session);
        if (knownUnit == null || unitContractService == null
                || !unitContractService.areEquivalent(factoryId, knownUnit, knownUnit)) return false;
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if ((key.equals("unit") || key.equals("quantityunit"))
                    && !unitCompatible(factoryId, session, entry.getValue())) return false;
        }
        if (hasSpokenUnit && (!utterance.contains(spokenUnit)
                || !unitCompatible(factoryId, session, spokenUnit))) return false;

        Matcher matcher = NUMERIC_UNIT.matcher(utterance);
        while (matcher.find()) {
            if (!unitCompatible(factoryId, session, matcher.group(1))) return false;
        }
        return true;
    }

    private boolean unitCompatible(String factoryId, ConversationSession session, String spokenUnit) {
        if (spokenUnit == null || spokenUnit.isBlank()) return true;
        String knownUnit = knownUnit(session);
        return knownUnit != null && unitContractService != null
                && unitContractService.areEquivalent(factoryId, knownUnit, spokenUnit);
    }

    private String knownUnit(ConversationSession session) {
        for (Map.Entry<String, String> e : session.getCollectedParameters().entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.equals("unit") || key.endsWith("unit") || key.contains("quantityunit")) {
                return e.getValue();
            }
        }
        return null;
    }

    private boolean dateEvidence(IntentExecuteRequest request, Map<String, String> accepted) {
        boolean hasDate = accepted.keySet().stream().anyMatch(k -> {
            String n = k.toLowerCase(Locale.ROOT);
            return n.contains("date") || n.contains("time") || n.equals("day");
        });
        if (!hasDate) return true;
        String input = Optional.ofNullable(request.getUserInput()).orElse("");
        if (accepted.entrySet().stream().filter(e -> e.getKey().toLowerCase(Locale.ROOT).contains("date"))
                .allMatch(e -> input.contains(e.getValue()))) return true;
        return timeNormalizationRules != null
                && timeNormalizationRules.normalize(input, SemanticConversationContextService.referenceTime(request))
                .map(TimeNormalizationRules.TimeRange::isValid)
                .orElse(false);
    }

    private Map<String, Object> schemaFor(String factoryId, String intentCode) {
        if (intentCode == null || intentConfigManagementService == null || toolRegistry == null) return Map.of();
        return intentConfigManagementService.getIntentByCode(factoryId, intentCode)
                .map(c -> c.getToolName() == null ? null : toolRegistry.getExecutor(c.getToolName()).orElse(null))
                .map(e -> e == null || e.getParametersSchema() == null ? Map.<String, Object>of() : e.getParametersSchema())
                .orElse(Map.of());
    }

    private Map<String, Object> schemaProperties(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> { if (k instanceof String s) out.put(s, v); });
        return out;
    }

    private boolean safeField(String key) {
        String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && BLOCKED_FIELD_PARTS.stream().noneMatch(normalized::contains)
                && !normalized.equals("status");
    }

    private void requireTransition(boolean success) {
        if (!success) throw new TransitionRejected();
    }

    private Decision askModel(String factoryId, IntentExecuteRequest request, List<Draft> drafts,
                              Map<String, Object> committed) {
        String system = """
                你是现有对话任务的状态修复协调器，只返回一次 conversation_task_repair 工具调用，不执行任何业务。
                taskAction只能是 CONTINUE、CORRECT、INTERRUPT、SWITCH、CANCEL、RESUME、CLARIFY、UNSUPPORTED。
                taskReference只能使用给出的 draft-1 等不透明引用，或 current-read 表示当前 READ_QUERY；changes只能是当前真实工具 schema 字段的本轮明确修改。
                只有受控语义上下文明确给出 currentRead.present=true 时，才允许选择 current-read；否则不得凭空制造当前只读目标。
                不猜业务ID、手机号、客户名、价格、权限、单位换算或未给出的参数；取消必须针对当前任务，不要按孤立否定词取消。
                已提交摘要只代表历史动作；用户明确开启新目标时返回 SWITCH，让主链继续新查询，不要把新目标当作 CORRECT。
                INTERRUPT表示保留当前草稿、临时处理其他只读目标；用户要求读完恢复时用INTERRUPT并设置resumeAfterRead，不得用丢弃草稿的SWITCH。
                SWITCH仅表示明确更换目标且不保留当前草稿。恢复对象不唯一时澄清，不能任意选择暂停任务。
                spokenUnit只表示用户本轮明确说出的单位；resumeAfterRead仅表示读操作成功后是否恢复已选暂停草稿。
                只有用户本轮明确要求读完继续时才设置resumeAfterRead=true，并把resumeEvidence填写为本轮原话中不超过80字符的连续原文；不能自行推断恢复。
                本轮原话没有单位时spokenUnit必须为空字符串，不能从旧参数补入单位；未修改的值由程序保留，不应重复填写changes。
                """;
        String user = "用户本轮输入: " + Optional.ofNullable(request.getUserInput()).orElse("")
                + "\n任务草稿: " + toJson(safeDraftList(drafts))
                + "\n已提交服务器动作摘要: " + toJson(committed)
                + "\n受控语义上下文: " + toJson(safeSemanticContext(request.getSemanticContext()))
                + "\n请求参考时点: " + SemanticConversationContextService.referenceTime(request);
        LlmEgressGuard guard = llmEgressGuard == null ? LlmEgressGuard.standalone() : llmEgressGuard;
        var redacted = guard.texts(EGRES_SITE, factoryId, system, user);
        if (redacted.refused()) return null;
        try {
            ChatCompletionResponse response = dashScopeClient.chatWithTools(redacted.text(0), redacted.text(1),
                    List.of(repairTool(drafts, hasCurrentRead(request.getSemanticContext()))));
            if (response == null || response.hasError() || !dashScopeClient.hasToolCalls(response)) return null;
            ToolCall call = dashScopeClient.getFirstToolCall(response);
            if (call == null || call.getFunction() == null || call.getFunction().getArguments() == null) return null;
            JsonNode node = mapper().readTree(call.getFunction().getArguments());
            Map<String, String> changes = new LinkedHashMap<>();
            JsonNode changeNode = node.get("changes");
            if (changeNode != null && changeNode.isObject()) {
                changeNode.fields().forEachRemaining(e -> {
                    if (e.getValue().isValueNode() && !e.getValue().isNull()) changes.put(e.getKey(), e.getValue().asText());
                });
            }
            String action = parseAction(node);
            if (action == null) return null;
            return new Decision(action,
                    text(node, "taskReference"), changes, text(node, "spokenUnit"),
                    node.path("resumeAfterRead").asBoolean(false), text(node, "resumeEvidence"),
                    text(node, "message"));
        } catch (Exception e) {
            log.warn("conversation task repair model response rejected: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Accept the old field only as a strict, non-conflicting compatibility alias. */
    private String parseAction(JsonNode node) {
        boolean hasTaskAction = node.has("taskAction");
        boolean hasActionAlias = node.has("action");
        String taskAction = strictText(node, "taskAction");
        String actionAlias = strictText(node, "action");
        if ((hasTaskAction && taskAction == null) || (hasActionAlias && actionAlias == null)) return null;
        String normalizedTask = taskAction == null ? null : taskAction.toUpperCase(Locale.ROOT);
        String normalizedAlias = actionAlias == null ? null : actionAlias.toUpperCase(Locale.ROOT);
        if (normalizedTask != null && !ACTIONS.contains(normalizedTask)) return null;
        if (normalizedAlias != null && !ACTIONS.contains(normalizedAlias)) return null;
        if (normalizedTask != null && normalizedAlias != null && !normalizedTask.equals(normalizedAlias)) return null;
        return normalizedTask != null ? normalizedTask : normalizedAlias;
    }

    private String strictText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private Tool repairTool(List<Draft> drafts, boolean currentReadPresent) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskAction", Map.of("type", "string", "enum", new ArrayList<>(ACTIONS)));
        List<String> references = new ArrayList<>();
        drafts.forEach(draft -> references.add(draft.opaqueId()));
        if (currentReadPresent) references.add("current-read");
        Map<String, Object> referenceSchema = new LinkedHashMap<>();
        referenceSchema.put("type", "string");
        if (!references.isEmpty()) referenceSchema.put("enum", references);
        properties.put("taskReference", referenceSchema);
        properties.put("changes", Map.of("type", "object", "additionalProperties", Map.of("type", "string")));
        properties.put("spokenUnit", Map.of("type", "string"));
        properties.put("resumeAfterRead", Map.of("type", "boolean"));
        properties.put("resumeEvidence", Map.of("type", "string", "maxLength", 80));
        properties.put("message", Map.of("type", "string"));
        return Tool.of("conversation_task_repair", "选择当前对话任务动作，不执行任何业务。",
                Map.of("type", "object", "properties", properties,
                        "required", List.of("taskAction"), "additionalProperties", false));
    }

    private List<Draft> findDrafts(String factoryId, Long userId, IntentExecuteRequest request) {
        LinkedHashMap<String, ConversationSession> sessions = new LinkedHashMap<>();
        if (request.getSessionId() != null) {
            conversations.getSession(factoryId, userId, request.getSessionId()).ifPresent(s -> sessions.put(s.getSessionId(), s));
        }
        conversations.getActiveSession(factoryId, userId).ifPresent(s -> sessions.putIfAbsent(s.getSessionId(), s));
        List<ConversationSession> paused = conversations.getPausedSessions(factoryId, userId);
        if (paused != null) paused.forEach(s -> sessions.putIfAbsent(s.getSessionId(), s));
        List<Draft> result = new ArrayList<>();
        int i = 1;
        for (ConversationSession session : sessions.values()) {
            if (session == null || !Objects.equals(factoryId, session.getFactoryId())
                    || !Objects.equals(userId, session.getUserId())
                    || !session.isParameterCollectionMode()
                    || session.getStatus() == ConversationSession.SessionStatus.CANCELLED
                    || session.getStatus() == ConversationSession.SessionStatus.TIMEOUT) continue;
            if (session.getStatus() == ConversationSession.SessionStatus.COMPLETED
                    && !hasPendingPreview(factoryId, userId, session)) continue;
            result.add(new Draft("draft-" + i++, session));
        }
        return result;
    }

    private boolean hasPendingPreview(String factoryId, Long userId, ConversationSession session) {
        if (previewTokenService == null) return false;
        return previewTokenService.hasPendingDraftPreview(factoryId, userId, session.getSessionId(),
                ConversationService.draftRevisionFingerprint(session));
    }

    private Draft chooseDraft(String reference, IntentExecuteRequest request, List<Draft> drafts) {
        if (reference != null && !reference.isBlank()) return drafts.stream()
                .filter(d -> reference.equals(d.opaqueId())).findFirst().orElse(null);
        if (request.getSessionId() != null) return drafts.stream()
                .filter(d -> request.getSessionId().equals(d.session().getSessionId())).findFirst().orElse(null);
        return drafts.size() == 1 ? drafts.get(0) : null;
    }

    private boolean explicitDraftSelection(IntentExecuteRequest request, Draft draft) {
        // The conversation transport ID may be carried from an interrupted draft;
        // it is not a fresh UI choice of that draft as the cancellation target.
        String selected = stringContext(request, "resumeSessionId");
        return selected != null && selected.equals(draft.session().getSessionId());
    }

    private Draft choosePaused(String reference, IntentExecuteRequest request, List<Draft> drafts) {
        String explicit = stringContext(request, "resumeSessionId");
        if (explicit != null) return drafts.stream().filter(d -> d.session().getSessionId().equals(explicit)
                && d.session().getStatus() == ConversationSession.SessionStatus.PAUSED).findFirst().orElse(null);
        List<Draft> paused = drafts.stream().filter(d -> d.session().getStatus() == ConversationSession.SessionStatus.PAUSED).toList();
        if (paused.size() > 1) return null;
        if (reference != null && !reference.isBlank()) return paused.stream().filter(d -> reference.equals(d.opaqueId())).findFirst().orElse(null);
        return paused.size() == 1 ? paused.get(0) : null;
    }

    private IntentExecuteResponse resumeClarification(List<Draft> drafts) {
        List<Map<String, Object>> paused = drafts.stream()
                .filter(d -> d.session().getStatus() == ConversationSession.SessionStatus.PAUSED)
                .map(this::safeDraft).toList();
        List<Map<String, Object>> choices = drafts.stream()
                .filter(d -> d.session().getStatus() == ConversationSession.SessionStatus.PAUSED)
                .map(this::resumeChoice).toList();
        return reply("NEED_MORE_INFO", "存在多个暂停任务，请明确要恢复哪一个。", null,
                Map.of("taskAction", "CLARIFY", "pausedDrafts", paused, "resumeChoices", choices));
    }

    /** Owner-scoped response metadata for an existing UI selector; never sent to the model. */
    private Map<String, Object> resumeChoice(Draft draft) {
        ConversationSession s = draft.session();
        List<String> pending = s.getRequiredParameters().stream().filter(p -> !p.isCollected())
                .map(ConversationSession.RequiredParameter::getName).filter(Objects::nonNull).toList();
        return Map.of("draftReference", draft.opaqueId(), "sessionId", s.getSessionId(),
                "intentCode", Objects.toString(s.getKnownIntentCode(), ""), "pendingFields", pending);
    }

    private Map<String, Object> safeDraft(Draft draft) {
        ConversationSession s = draft.session();
        List<String> pending = s.getRequiredParameters().stream().filter(p -> !p.isCollected())
                .map(ConversationSession.RequiredParameter::getName).filter(Objects::nonNull).toList();
        List<String> filled = new ArrayList<>(s.getCollectedParameters().keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", draft.opaqueId());
        out.put("status", s.getStatus().name());
        out.put("mode", s.getSessionMode().name());
        out.put("intent", s.getKnownIntentCode());
        if (s.getOriginalInput() != null && !s.getOriginalInput().isBlank())
            out.put("goalText",s.getOriginalInput().substring(0,Math.min(200,s.getOriginalInput().length())));
        out.put("filledFields", filled);
        out.put("pendingFields", pending);
        Map<String, String> safeValues = new LinkedHashMap<>();
        s.getCollectedParameters().forEach((key, value) -> {
            if (safeValueField(key) && value != null && !value.isBlank() && value.length() <= 64) {
                safeValues.put(key, value);
            }
        });
        if (!safeValues.isEmpty()) out.put("safeValues", safeValues);
        Map<String, Object> schema = safeSchema(schemaFor(s.getFactoryId(), s.getKnownIntentCode()));
        if (!schema.isEmpty()) out.put("toolSchema", schema);
        return out;
    }

    private List<Map<String, Object>> safeDraftList(List<Draft> drafts) {
        return drafts.stream().map(this::safeDraft).toList();
    }

    private String safeDraftMessage(Draft draft) {
        Map<String, Object> safe = safeDraft(draft);
        return "已恢复 " + safe.get("draftId") + "，待补字段：" + safe.get("pendingFields");
    }

    private void putSafeDraftContext(IntentExecuteRequest request, List<Draft> drafts,
                                     Map<String, Object> committed) {
        Map<String, Object> copy = request.getSemanticContext() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getSemanticContext());
        copy.remove("draftTask");
        copy.remove("draftTaskCandidates");
        copy.put("draftTasks", safeDraftList(drafts));
        copy.remove("lastActionCommitted");
        if (!committed.isEmpty()) copy.put("lastActionCommitted", committed);
        request.setSemanticContext(copy);
    }

    private Map<String, Object> safeSemanticContext(Map<String, Object> context) {
        if (context == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        Object drafts = context.get("draftTasks");
        if (drafts instanceof List<?> list) safe.put("draftTasks", list);
        Object references = context.get("references");
        if (references instanceof List<?> list) safe.put("references", list);
        if (hasCurrentRead(context)) safe.put("currentRead", Map.of("present", true, "reference", "current-read"));
        Object slotTask = context.get("slotTask");
        if (slotTask instanceof Map<?, ?> map) safe.put("slotTask", map);
        Object contextStatus = context.get("contextStatus");
        if (contextStatus != null) safe.put("contextStatus", String.valueOf(contextStatus));
        Object committed = context.get("lastActionCommitted");
        if (committed instanceof Map<?, ?>) safe.put("lastActionCommitted", committedSummary(context));
        Object referenceTime = context.get("referenceTime");
        if (referenceTime != null) safe.put("referenceTime", String.valueOf(referenceTime));
        return safe;
    }

    private boolean hasCurrentRead(Map<String, Object> context) {
        if (context == null || !"PRESENT".equals(String.valueOf(context.get("contextStatus")))) return false;
        Object refs = context.get("references");
        if (!(refs instanceof List<?> list)) return false;
        return list.stream().anyMatch(value -> value instanceof Map<?, ?> ref
                && "READ_QUERY".equals(String.valueOf(ref.get("type")))
                && ref.get("id") instanceof String id && !id.isBlank()
                && !"CLEARED".equals(id)
                && !Set.of("CANCELLED", "CLEARED", "EXPIRED").contains(String.valueOf(ref.get("taskStatus")))
                && ref.get("reference") instanceof String);
    }

    private boolean hasResumeEvidence(IntentExecuteRequest request, String evidence) {
        if (evidence == null || evidence.isBlank() || evidence.length() > 80) return false;
        String input = Optional.ofNullable(request.getUserInput()).orElse("");
        return input.contains(evidence);
    }

    private boolean safeValueField(String key) {
        if (key == null || !safeField(key)) return false;
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SAFE_VALUE_FIELDS.contains(normalized);
    }

    private Map<String, Object> safeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return Map.of();
        Map<String, Object> properties = schemaProperties(schema);
        Map<String, Object> safeProperties = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            if (!safeValueField(name) || !(value instanceof Map<?, ?> raw)) return;
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String key : List.of("type", "format", "minimum", "maximum")) {
                if (raw.get(key) != null) safe.put(key, raw.get(key));
            }
            if (!safe.isEmpty()) safeProperties.put(name, safe);
        });
        if (safeProperties.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", safeProperties);
        return out;
    }

    private Map<String, Object> committedSummary(Map<String, Object> context) {
        if (context == null || !(context.get("lastActionCommitted") instanceof Map<?, ?> raw)) return Map.of();
        if (!"COMMITTED".equals(String.valueOf(raw.get("status")))) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("intent", "intentCode", "tool", "toolName", "status")) {
            if (raw.get(key) != null) safe.put(key, raw.get(key));
        }
        return safe.containsKey("status") && (safe.containsKey("intent") || safe.containsKey("intentCode"))
                ? safe : Map.of();
    }

    private Map<String, Object> metadata(Decision decision, Draft draft, Map<String, String> changes) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskAction", decision.action());
        if (draft != null) meta.put("taskReference", draft.opaqueId());
        if (!changes.isEmpty()) meta.put("changes", changes);
        if (decision.spokenUnit() != null && !decision.spokenUnit().isBlank()) meta.put("spokenUnit", decision.spokenUnit());
        meta.put("resumeAfterRead", decision.resumeAfterRead());
        return meta;
    }

    private IntentExecuteResponse reply(String status, String message, String sessionId, Map<String, Object> metadata) {
        return IntentExecuteResponse.builder().status(status).message(message).formattedText(message)
                .sessionId(sessionId).metadata(metadata).build();
    }

    private String stringContext(IntentExecuteRequest request, String key) {
        Object value = request.getContext() == null ? null : request.getContext().get(key);
        if (value == null && request.getSemanticContext() != null) {
            value = request.getSemanticContext().get(key);
        }
        return value == null ? null : String.valueOf(value);
    }

    private boolean isTrustedFactoryActor(String factoryId, Long userId) {
        return AuthenticatedActor.fromCurrentExecution().map(actor ->
                actor.type() == AuthenticatedActorType.FACTORY_USER
                        && Objects.equals(actor.factoryId(), factoryId)
                        && Objects.equals(actor.id(), userId)).orElse(false);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private ObjectMapper mapper() {
        if (objectMapper == null) objectMapper = new ObjectMapper().findAndRegisterModules();
        return objectMapper;
    }

    private String toJson(Object value) {
        try { return mapper().writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "请补充当前任务需要的信息。" : message;
    }

    private record Draft(String opaqueId, ConversationSession session) { }

    private record Decision(String action, String taskReference, Map<String, String> changes,
                            String spokenUnit, boolean resumeAfterRead, String resumeEvidence, String message) { }

    private static final class TransitionRejected extends RuntimeException { }
}

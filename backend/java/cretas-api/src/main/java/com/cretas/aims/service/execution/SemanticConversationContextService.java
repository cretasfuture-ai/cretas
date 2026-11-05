package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.service.ConversationMemoryService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** A request snapshot of existing conversation memory, never a second state store or authority. */
@Service
public class SemanticConversationContextService {
    private final ConversationMemoryService memory;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.ConversationService conversations;
    @org.springframework.beans.factory.annotation.Value("${cretas.ai.memory.expire-minutes:60}")
    private int expireMinutes = 60;
    private static final Set<String> PARAMS = Set.of("startDate", "endDate", "status", "page", "size",
            "limit", "metric", "quantity", "unit", "materialTypeId", "productTypeId", "orderId", "batchId", "batchNumber");
    @org.springframework.beans.factory.annotation.Autowired
    public SemanticConversationContextService(ConversationMemoryService memory) {
        this(memory, Clock.systemDefaultZone());
    }
    SemanticConversationContextService(ConversationMemoryService memory, Clock clock) {
        this.memory = memory; this.clock = clock;
    }

    public void prepare(String factoryId, Long userId, IntentExecuteRequest request) {
        request.setSemanticHistory(SseStreamingService.recentTurns(request));
        request.setSemanticParameters(null);
        request.setSemanticParameterSources(null);
        request.setSemanticPreviousRead(null);
        request.setSemanticReadVersion(null);
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("contextStatus", "UNKNOWN");
        snapshot.put("provenance", "SERVER_CONVERSATION_MEMORY");
        boolean boundActor = com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .filter(a -> a.type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER
                        && Objects.equals(factoryId, a.factoryId()) && Objects.equals(userId, a.id())).isPresent();
        if (boundActor && (request.getSessionId() == null || request.getSessionId().isBlank())) {
            // Explicit new request uses a new server ID, never getOrCreate(null)'s latest-task lookup.
            request.setSessionId(UUID.randomUUID().toString());
            request.setConversationStart(true);
        }
        boolean page = request.getEntityId() != null || request.getEntityType() != null;
        boolean history = !SseStreamingService.recentTurns(request).isEmpty();
        snapshot.put("hasPageReference", page);
        snapshot.put("hasClientHistory", history);
        if (request.getIntentCode() != null && request.getUserInput() != null && !request.getUserInput().isBlank()
                && !Boolean.TRUE.equals(request.getForceExecute())) {
            snapshot.put("selectedIntentCandidate", request.getIntentCode());
            request.setIntentCode(null);
        }
        // Page input is a reference to resolve under current permissions, never an authorization.
        if (page) {
            Map<String,Object> ref = new LinkedHashMap<>();
            try { ref.put("type", EntitySlot.SlotType.valueOf(String.valueOf(request.getEntityType())).name()); }
            catch (IllegalArgumentException unknownType) { ref.put("type", "UNRESOLVED_ENTITY"); }
            ref.put("reference", "page-object");
            ref.put("source", "CLIENT_REFERENCE_REQUIRES_RESOLUTION");
            snapshot.put("pageReference", ref);
        }
        if (factoryId == null || userId == null) {
            snapshot.put("reason", "MISSING_PRINCIPAL");
        } else if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            snapshot.put("contextStatus", page || history ? "PRESENT" : "NEW");
        } else {
            try {
                ConversationContext ctx = memory.getContext(request.getSessionId());
                var slotSession = conversations == null ? Optional.<com.cretas.aims.entity.conversation.ConversationSession>empty()
                        : conversations.getSession(factoryId, userId, request.getSessionId());
                boolean ownedSlotSession = slotSession.filter(s -> Objects.equals(s.getFactoryId(), factoryId)
                        && Objects.equals(s.getUserId(), userId) && Objects.equals(s.getSessionId(), request.getSessionId())).isPresent();
                if (ctx == null && boundActor && Boolean.TRUE.equals(request.getConversationStart()) && !history) {
                    ctx = memory.getOrCreateContext(factoryId, userId, request.getSessionId());
                }
                if (ctx == null && boundActor && ownedSlotSession) {
                    ctx = memory.getOrCreateContext(factoryId, userId, request.getSessionId());
                }
                if (ctx == null) snapshot.put("reason", "SESSION_NOT_FOUND");
                else if (!Objects.equals(factoryId, ctx.getFactoryId()) || !Objects.equals(userId, ctx.getUserId())
                        || !Objects.equals(request.getSessionId(), ctx.getSessionId())) {
                    snapshot.put("reason", "FOREIGN_SESSION");
                } else if (ctx.getLastActiveAt() != null && Duration.between(ctx.getLastActiveAt(),
                        LocalDateTime.now(clock)).toMinutes() >= expireMinutes) {
                    snapshot.put("contextStatus", "EXPIRED");
                    snapshot.put("reason", "SESSION_EXPIRED");
                } else if (ctx.getLastActiveAt() == null) {
                    snapshot.put("reason", "SESSION_TIMESTAMP_UNAVAILABLE");
                } else {
                    boolean related = history || page || ownedSlotSession || ctx.getLastIntentCode() != null
                            || ctx.getMessageCount() > 0 || (ctx.getEntitySlots() != null && !ctx.getEntitySlots().isEmpty());
                    snapshot.put("contextStatus", related ? "PRESENT" : "NEW");
                    if (ownedSlotSession) {
                        var pending = slotSession.get();
                        Map<String,Object> task = new LinkedHashMap<>();
                        task.put("status", pending.getStatus().name());
                        task.put("mode", pending.getSessionMode().name());
                        task.put("round", pending.getCurrentRound());
                        if (pending.getKnownIntentCode() != null) task.put("intentCode", pending.getKnownIntentCode());
                        task.put("pendingFields", pending.getRequiredParameters().stream().filter(p -> !p.isCollected())
                                .map(p -> p.getName()).limit(20).toList());
                        Map<String,String> safeValues = new LinkedHashMap<>();
                        pending.getCollectedParameters().forEach((k,v) -> {
                            if (PARAMS.contains(k) && !k.endsWith("Id") && !"batchNumber".equals(k)
                                    && v != null && v.length() <= 128) safeValues.put(k,v);
                        });
                        task.put("parameters", safeValues); task.put("parameterSource", "PREVIOUS_COLLECTED_UNCONFIRMED_PROVENANCE");
                        snapshot.put("slotTask", task);
                        if (pending.isExpired() && pending.getStatus() == com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE) {
                            snapshot.put("contextStatus", "EXPIRED"); snapshot.put("reason", "SLOT_SESSION_EXPIRED");
                        }
                    }
                    snapshot.put("messageVersion", ctx.getMessageCount());
                    if (ctx.getRecentMessages() != null && !ctx.getRecentMessages().isEmpty()) {
                        List<Map<String,String>> turns = ctx.getRecentMessages().stream()
                                .filter(m -> m != null && m.getRole() == com.cretas.aims.dto.conversation.ConversationMessage.Role.USER
                                        && m.getContent() != null && !m.getContent().isBlank())
                                .map(m -> Map.of("role", "user", "content", m.getContent().substring(0, Math.min(200, m.getContent().length()))))
                                .toList();
                        if (!turns.isEmpty()) request.setSemanticHistory(turns.subList(Math.max(0, turns.size() - 4), turns.size()));
                    }
                    if (ctx.getLastIntentCode() != null) snapshot.put("lastIntentCode", ctx.getLastIntentCode());
                    if (ctx.getLastActiveAt() != null) snapshot.put("lastActiveAt", ctx.getLastActiveAt().toString());
                    // No assistant tables, names, prices, role grants, or arbitrary metadata egress.
                    List<Map<String,Object>> refs = new ArrayList<>();
                    if (ctx.getEntitySlots() != null) ctx.getEntitySlots().forEach((type, slot) -> {
                        if (slot == null) return;
                        Map<String,Object> ref = new LinkedHashMap<>();
                        ref.put("type", type.name());
                        ref.put("reference", "entity-" + refs.size());
                        if (type == EntitySlot.SlotType.READ_QUERY && slot.getMetadata() != null) {
                            if (Set.of("ORDER_LIST", "MATERIAL_BATCH_QUERY", SalesRankingParameters.INTENT, "CLEARED").contains(slot.getId()))
                                ref.put("id", slot.getId());
                            Map<String,Object> params = new LinkedHashMap<>();
                            slot.getMetadata().forEach((k,v) -> {
                                if (PARAMS.contains(k) && (v instanceof Number || v instanceof Boolean
                                        || v instanceof String s && s.length() <= 128)) params.put(k,v);
                            });
                            Map<String,Object> local = new LinkedHashMap<>();
                            local.put("id", slot.getId()); local.put("parameters", Map.copyOf(params));
                            for (String key : List.of("taskStatus", "reason")) {
                                if (slot.getMetadata().get(key) instanceof String value) local.put(key, value);
                            }
                            if (slot.getMetadata().get(SalesRankingConversationSupport.TURN_KEY) != null)
                                local.put(SalesRankingConversationSupport.TURN_KEY, slot.getMetadata().get(SalesRankingConversationSupport.TURN_KEY));
                            request.setSemanticPreviousRead(Collections.unmodifiableMap(local));
                            Map<String,Object> egressParams = new LinkedHashMap<>(params);
                            egressParams.keySet().removeIf(k -> k.endsWith("Id") || "batchNumber".equals(k));
                            ref.put("parameters", egressParams);
                            ref.put("parameterSource", "PREVIOUS_EXECUTION_UNCONFIRMED_PROVENANCE");
                            for (String key : List.of("taskStatus", "reason")) {
                                if (slot.getMetadata().get(key) instanceof String s) ref.put(key, s);
                            }
                            if (slot.getMetadata().get("parameterSources") instanceof Map<?,?> sources) {
                                Map<String,String> safeSources = new LinkedHashMap<>();
                                sources.forEach((k,v) -> {
                                    if (k instanceof String key && PARAMS.contains(key) && v instanceof String value
                                            && Set.of("CURRENT_EXPLICIT", "PREVIOUS_VALUE", "SYSTEM_DEFAULT_OR_RESOLVER").contains(value)) safeSources.put(key, value);
                                });
                                ref.put("parameterSources", safeSources);
                            }
                        }
                        refs.add(ref);
                    });
                    snapshot.put("references", refs);
                }
            } catch (RuntimeException failure) {
                snapshot.put("reason", "STATE_READ_FAILED");
            }
        }
        request.setSemanticContext(Collections.unmodifiableMap(snapshot));
    }

    static com.cretas.aims.dto.ai.IntentExecuteResponse admissionFailure(IntentExecuteRequest request) {
        Map<String,Object> context = request.getSemanticContext();
        if (context == null) return null; // Bare fixture/legacy callers do not assert server evidence.
        String reason = String.valueOf(context.get("reason"));
        if ("FOREIGN_SESSION".equals(reason))
            return com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("NO_PERMISSION")
                    .message("当前会话不属于当前用户或工厂。").build();
        if (Set.of("UNKNOWN", "EXPIRED").contains(String.valueOf(context.get("contextStatus"))))
            return com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("NEED_MORE_INFO")
                    .message("当前会话已过期或暂时无法读取，请重试或新建会话后说明目标和条件；尚未执行业务。").build();
        return null;
    }

    public static boolean allowsDirectRoute(IntentExecuteRequest request) {
        return request.getSemanticContext() != null && "NEW".equals(request.getSemanticContext().get("contextStatus"))
                && !Boolean.TRUE.equals(request.getSemanticContext().get("hasPageReference"))
                && !Boolean.TRUE.equals(request.getSemanticContext().get("hasClientHistory"))
                && !request.getSemanticContext().containsKey("selectedIntentCandidate");
    }

    static List<Map<String,String>> effectiveHistory(IntentExecuteRequest request) {
        return request.getSemanticHistory() == null ? SseStreamingService.recentTurns(request) : request.getSemanticHistory();
    }

    static boolean hasReadContext(IntentExecuteRequest request) {
        return request.getSemanticPreviousRead() != null && !"CLEARED".equals(request.getSemanticPreviousRead().get("id"));
    }

    static String previousReadIntent(IntentExecuteRequest request) {
        return hasReadContext(request) ? String.valueOf(request.getSemanticPreviousRead().get("id")) : null;
    }
}

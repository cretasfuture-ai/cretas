package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared normal/SSE continuation policy, stored in the existing conversation entity slots.
 * Only read parameters are inherited; actor, permissions and Tool resolution stay downstream.
 */
@Service
public class SalesRankingConversationSupport {
    static final String TURN_KEY = ConversationMemoryService.READ_QUERY_VERSION_KEY;
    private static final Set<String> KEYS = Set.of("startDate", "endDate", "limit", "metric");
    private static final Map<String, String> OTHER_READS = Map.of(
            "material_batch_query", "MATERIAL_BATCH_QUERY", "order_list", "ORDER_LIST");
    private final ConversationMemoryService memory;
    private final ConversationService conversations;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SalesRankingConversationSupport(ConversationMemoryService memory, ConversationService conversations) {
        this(memory, conversations, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    SalesRankingConversationSupport(ConversationMemoryService memory, ConversationService conversations, Clock clock) {
        this.memory = memory;
        this.conversations = conversations;
        this.clock = clock;
    }

    /** Called after existing identity/factory admission, before shortcuts or slot collection. */
    IntentExecuteResponse prepare(String factoryId, Long userId, IntentExecuteRequest request) {
        if (!boundActor(factoryId, userId) || request == null || request.getSessionId() == null
                || request.getSessionId().isBlank()) return null;
        ConversationContext owner = memory.getContext(request.getSessionId());
        if (owner != null && !owned(owner, factoryId, userId, request.getSessionId())) {
            return reply("NO_PERMISSION", "当前会话不属于当前用户或工厂。", request.getSessionId());
        }
        String text = request.getUserInput() == null ? "" : request.getUserInput().replaceAll("[\\s，,。.!！?？]", "");
        EntitySlot slot = owner == null ? null : owner.getSlot(EntitySlot.SlotType.READ_QUERY);
        boolean active = slot != null && (SalesRankingParameters.INTENT.equals(slot.getId())
                || OTHER_READS.containsValue(slot.getId()))
                && slot.getMentionedAt() != null
                && slot.getMentionedAt().plusMinutes(10).isAfter(LocalDateTime.now(clock));
        boolean cancel = text.matches("(?:算了|不用查了|不查了|取消查询|停止查询|取消当前查询)");
        if (active && cancel) {
            invalidate(request.getSessionId(), "CANCELLED");
            conversations.cancelConversation(factoryId, userId, request.getSessionId());
            return reply("CANCELLED", "已取消当前查询。", request.getSessionId());
        }
        boolean explicitOther = request.getIntentCode() != null && !request.getIntentCode().isBlank()
                && !SalesRankingParameters.INTENT.equals(request.getIntentCode());
        // Anchored grammar: new inventory/orders, business cancellation and scoped negatives
        // cannot be mistaken for a date/metric/limit answer to the ranking query.
        boolean delta = !explicitOther && text.matches("(?:不是金额|不是销量|不是不查|先看|改成|改为|改看|看|按|销售|金额|数量|销量|上个月|上月|本月|这个月|前[0-9一二两三四五六七八九十百]{1,3}(?:个|名|条)?|呢|吧|的|排行|排名|公斤|千克)+");
        if (delta && !SalesRankingParameters.INTENT.equals(request.getIntentCode())) {
            if (!active || !SalesRankingParameters.INTENT.equals(slot.getId())) {
                // Leave unrelated read/write slot answers with their existing continuation.
                boolean cancelledRead = slot != null && slot.getMetadata() != null
                        && "CANCELLED".equals(slot.getMetadata().get("reason"));
                if (!cancelledRead && (slot == null || !SalesRankingParameters.INTENT.equals(slot.getId()))) return null;
                return reply("NEED_MORE_INFO", "当前没有可继续的产品排行查询，请说明要查询的内容。", request.getSessionId());
            }
            Map<String, Object> context = request.getContext() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(request.getContext());
            if (slot.getMetadata() != null) slot.getMetadata().forEach((key, value) -> {
                if (KEYS.contains(key)) context.put(key, value);
            });
            SalesRankingParameters.apply(request.getUserInput(), context, LocalDate.now(clock));
            request.setContext(context);
            request.setIntentCode(SalesRankingParameters.INTENT);
        } else if (active && !SalesRankingParameters.INTENT.equals(request.getIntentCode())) {
            // A new goal stops inheritance; existing cancellation/slot-switch rules still own
            // their states. Never carry ranking dates/limits into inventory or order queries.
            invalidate(request.getSessionId(), "SWITCHED");
        }
        return null;
    }

    /** Called only for the actually bound Tool, after extraction; explicit current text wins. */
    void parameters(String factoryId, Long userId, IntentExecuteRequest request, Map<String, Object> params) {
        applyCurrentParameters(request, params);
        if (!boundActor(factoryId, userId) || request.getSessionId() == null || request.getSessionId().isBlank()) return;
        ConversationContext owner = memory.getOrCreateContext(factoryId, userId, request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId())) {
            throw new com.cretas.aims.exception.BusinessException(403, "当前会话不属于当前用户或工厂。");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        KEYS.forEach(key -> { if (params.get(key) != null) fields.put(key, params.get(key)); });
        String turnId = java.util.UUID.randomUUID().toString();
        fields.put(TURN_KEY, turnId);
        memory.updateEntitySlot(request.getSessionId(), EntitySlot.SlotType.READ_QUERY, EntitySlot.builder()
                .type(EntitySlot.SlotType.READ_QUERY).id(SalesRankingParameters.INTENT)
                .metadata(fields).mentionedAt(LocalDateTime.now(clock)).build());
        Map<String, Object> context = request.getContext() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getContext());
        context.putAll(fields);
        request.setContext(context);
    }

    void applyCurrentParameters(IntentExecuteRequest request, Map<String, Object> params) {
        if (request.getSemanticContext() == null) SalesRankingParameters.apply(request.getUserInput(), params, LocalDate.now(clock));
        else SalesRankingParameters.defaults(params, LocalDate.now(clock));
    }

    @org.springframework.transaction.annotation.Transactional
    public IntentExecuteResponse cancelRead(String factoryId, Long userId, IntentExecuteRequest request) {
        if (!boundActor(factoryId, userId) || request.getSessionId() == null)
            return reply("NO_PERMISSION", "无法确认会话归属。", String.valueOf(request.getSessionId()));
        ConversationContext owner = memory.getContext(request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId()))
            return reply("NO_PERMISSION", "当前会话不属于当前用户或工厂。", request.getSessionId());
        String observed = request.getSemanticReadVersion() != null ? request.getSemanticReadVersion()
                : request.getSemanticPreviousRead() == null ? null : (String) request.getSemanticPreviousRead().get(TURN_KEY);
        boolean changed = memory.compareAndSetReadQuery(factoryId, userId, request.getSessionId(), observed,
                EntitySlot.builder().type(EntitySlot.SlotType.READ_QUERY).id("CLEARED")
                        .metadata(Map.of("reason", "CANCELLED", "taskStatus", "CANCELLED", TURN_KEY, UUID.randomUUID().toString()))
                        .mentionedAt(LocalDateTime.now(clock)).build());
        if (!changed) return reply("TASK_CHANGED", "当前任务已变化，未取消新任务。", request.getSessionId());
        var slotSession = conversations.getSession(factoryId, userId, request.getSessionId());
        // A read interruption must not cancel an unrelated write draft held by Conversation.
        String readIntent = request.getSemanticPreviousRead() == null ? null
                : String.valueOf(request.getSemanticPreviousRead().get("id"));
        if (slotSession.isEmpty() || Objects.equals(readIntent, slotSession.get().getKnownIntentCode())
                || Objects.equals(readIntent, slotSession.get().getFinalIntentCode())) {
            boolean cancelledSlotSession = conversations.cancelConversation(factoryId, userId, request.getSessionId());
            if (slotSession.isPresent() && !cancelledSlotSession) throw new IllegalStateException("Conversation cancellation not confirmed");
        }
        return reply("CANCELLED", "已停止当前查询；已经提交的业务不会被撤销。", request.getSessionId());
    }

    boolean reserveRead(String intent, String factoryId, Long userId, IntentExecuteRequest request) {
        if (!boundActor(factoryId, userId) || request.getSessionId() == null) return false;
        String observed = request.getSemanticPreviousRead() == null ? null : (String) request.getSemanticPreviousRead().get(TURN_KEY);
        Map<String,Object> metadata = new LinkedHashMap<>();
        if (request.getSemanticPreviousRead() != null
                && request.getSemanticPreviousRead().get("parameters") instanceof Map<?,?> previous)
            previous.forEach((k,v) -> { if (k instanceof String key) metadata.put(key,v); });
        String previousIntent = request.getSemanticPreviousRead() == null ? null : String.valueOf(request.getSemanticPreviousRead().get("id"));
        String reservedGoal = previousIntent != null && (SalesRankingParameters.INTENT.equals(previousIntent) || OTHER_READS.containsValue(previousIntent))
                ? previousIntent : "UNRESOLVED_READ";
        metadata.put(TURN_KEY, UUID.randomUUID().toString()); metadata.put("resolutionStatus", "RESOLVING");
        metadata.put("taskStatus", request.getSemanticPreviousRead() == null ? "UNRESOLVED"
                : request.getSemanticPreviousRead().getOrDefault("taskStatus", "UNRESOLVED"));
        if (request.getSemanticPreviousRead() != null && request.getSemanticPreviousRead().get("reason") instanceof String reason)
            metadata.put("reason", reason);
        boolean reserved = memory.compareAndSetReadQuery(factoryId, userId, request.getSessionId(), observed,
                EntitySlot.builder().type(EntitySlot.SlotType.READ_QUERY).id(reservedGoal).metadata(metadata).mentionedAt(LocalDateTime.now(clock)).build());
        if (reserved) {
            Map<String,Object> context = new LinkedHashMap<>(request.getContext() == null ? Map.of() : request.getContext());
            context.put(TURN_KEY, metadata.get(TURN_KEY)); request.setContext(context);
            request.setSemanticReadVersion((String) metadata.get(TURN_KEY));
        }
        return reserved;
    }

    boolean rememberResolvedRead(String toolName, String factoryId, Long userId, IntentExecuteRequest request,
                              Map<String,Object> params) {
        String intent = SalesRankingParameters.TOOL.equals(toolName) ? SalesRankingParameters.INTENT : OTHER_READS.get(toolName);
        if (intent == null || !boundActor(factoryId, userId) || request.getSessionId() == null) return false;
        ConversationContext owner = memory.getOrCreateContext(factoryId, userId, request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId()))
            throw new com.cretas.aims.exception.BusinessException(403, "会话归属不符。");
        Map<String,Object> values = new LinkedHashMap<>();
        Map<String,Object> sources = new LinkedHashMap<>();
        Set<String> fields = Set.of("startDate", "endDate", "status", "page", "size", "limit", "metric", "materialTypeId", "batchNumber");
        fields.forEach(k -> {
            if (params.get(k) != null) {
                values.put(k, params.get(k));
                sources.put(k, request.getSemanticParameterSources() != null
                        ? request.getSemanticParameterSources().getOrDefault(k, "SYSTEM_DEFAULT_OR_RESOLVER")
                        : "SYSTEM_DEFAULT_OR_RESOLVER");
            }
        });
        values.put("parameterSources", sources);
        values.put("taskStatus", "RUNNING");
        String reserved = request.getContext() == null ? null : (String) request.getContext().get(TURN_KEY);
        if (reserved == null) return false;
        values.put(TURN_KEY, reserved);
        boolean stored = memory.compareAndSetReadQuery(factoryId, userId, request.getSessionId(), reserved, EntitySlot.builder()
                .type(EntitySlot.SlotType.READ_QUERY).id(intent).metadata(values).mentionedAt(LocalDateTime.now(clock)).build());
        Map<String,Object> context = new LinkedHashMap<>(request.getContext() == null ? Map.of() : request.getContext());
        context.put(TURN_KEY, values.get(TURN_KEY));
        request.setContext(context);
        return stored;
    }

    public boolean finishRead(String factoryId, Long userId, IntentExecuteRequest request, String status) {
        if (request.getSemanticReadVersion() == null) return true;
        ConversationContext owner = memory.getContext(request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId()) || !isCurrent(factoryId, userId, request)) return false;
        EntitySlot slot = owner.getSlot(EntitySlot.SlotType.READ_QUERY);
        Map<String,Object> values = new LinkedHashMap<>(slot.getMetadata());
        if (request.getSemanticParameters() != null)
            values.put("taskStatus", Set.of("SUCCESS", "COMPLETED").contains(String.valueOf(status)) ? "COMPLETED" : "BLOCKED");
        values.put("resolutionStatus", request.getSemanticParameters() != null ? "RESOLVED" : "NOT_ACCEPTED");
        if ("UNSUPPORTED_CAPABILITY".equals(status)) values.put("reason", status);
        if ("UNSUPPORTED_CAPABILITY".equals(values.get("reason"))) values.put("taskStatus", "BLOCKED");
        String previousVersion = (String) values.get(TURN_KEY);
        String terminalVersion = UUID.randomUUID().toString(); values.put(TURN_KEY, terminalVersion);
        boolean completed = memory.compareAndSetReadQuery(factoryId, userId, request.getSessionId(), previousVersion,
                EntitySlot.builder().type(EntitySlot.SlotType.READ_QUERY).id(slot.getId()).metadata(values).mentionedAt(LocalDateTime.now(clock)).build());
        if (completed) {
            Map<String,Object> context = new LinkedHashMap<>(request.getContext());
            context.put(TURN_KEY, terminalVersion); request.setContext(context); request.setSemanticReadVersion(terminalVersion);
        }
        return completed;
    }

    /** Only lifecycle/cancellation is extended to these two existing READ Tools. Their
     * filters and entity resolution remain in their existing Tool/slot mechanisms. */
    void rememberOtherRead(String toolName, String factoryId, Long userId, IntentExecuteRequest request) {
        String intent = OTHER_READS.get(toolName);
        if (intent == null || !boundActor(factoryId, userId) || request.getSessionId() == null
                || request.getSessionId().isBlank()) return;
        ConversationContext owner = memory.getOrCreateContext(factoryId, userId, request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId())) return;
        memory.updateEntitySlot(request.getSessionId(), EntitySlot.SlotType.READ_QUERY, EntitySlot.builder()
                .type(EntitySlot.SlotType.READ_QUERY).id(intent).metadata(Map.of())
                .mentionedAt(LocalDateTime.now(clock)).build());
    }

    boolean isCurrent(String factoryId, Long userId, IntentExecuteRequest request) {
        if (request.getContext() == null || !request.getContext().containsKey(TURN_KEY)) return true;
        if (!boundActor(factoryId, userId)) return false;
        ConversationContext owner = memory.getContext(request.getSessionId());
        if (!owned(owner, factoryId, userId, request.getSessionId())) return false;
        EntitySlot slot = owner.getSlot(EntitySlot.SlotType.READ_QUERY);
        return slot != null && (SalesRankingParameters.INTENT.equals(slot.getId()) || OTHER_READS.containsValue(slot.getId())
                || "UNRESOLVED_READ".equals(slot.getId())) && slot.getMetadata() != null
                && Objects.equals(request.getContext().get(TURN_KEY), slot.getMetadata().get(TURN_KEY));
    }

    private void invalidate(String sessionId, String reason) {
        memory.updateEntitySlot(sessionId, EntitySlot.SlotType.READ_QUERY, EntitySlot.builder()
                .type(EntitySlot.SlotType.READ_QUERY).id("CLEARED").metadata(Map.of("reason", reason))
                .mentionedAt(LocalDateTime.now(clock)).build());
    }

    private static boolean boundActor(String factoryId, Long userId) {
        return AuthenticatedActor.fromCurrentExecution().filter(a -> a.type() == AuthenticatedActorType.FACTORY_USER
                && Objects.equals(factoryId, a.factoryId()) && Objects.equals(userId, a.id())).isPresent();
    }

    private static boolean owned(ConversationContext owner, String factoryId, Long userId, String sessionId) {
        return owner != null && Objects.equals(factoryId, owner.getFactoryId())
                && Objects.equals(userId, owner.getUserId()) && Objects.equals(sessionId, owner.getSessionId());
    }

    private static IntentExecuteResponse reply(String status, String message, String sessionId) {
        return IntentExecuteResponse.builder().status(status).message(message).formattedText(message)
                .metadata(Map.of("sessionId", sessionId)).build();
    }
}

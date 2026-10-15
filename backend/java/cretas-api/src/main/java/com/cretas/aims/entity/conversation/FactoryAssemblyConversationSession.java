package com.cretas.aims.entity.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.security.AuthenticatedActorType;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多轮对话会话实体
 *
 * 当 Layer 1-4 置信度 < 30% 且无明确匹配时触发 Layer 5 多轮对话模式。
 * 支持最多 5 轮对话来澄清用户意图。
 *
 * 生命周期:
 * 1. ACTIVE - 对话进行中
 * 2. COMPLETED - 成功识别意图
 * 3. TIMEOUT - 超时未响应
 * 4. CANCELLED - 用户取消
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Entity
@Table(name = "factory_assembly_conversation_sessions", indexes = {
    @Index(name = "idx_assembly_cs_factory_user", columnList = "factory_id, user_id"),
    @Index(name = "idx_assembly_cs_factory_actor", columnList = "factory_id, actor_type, user_id"),
    @Index(name = "idx_assembly_cs_status", columnList = "status"),
    @Index(name = "idx_assembly_cs_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryAssemblyConversationSession {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * 会话ID (UUID)
     */
    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /** Namespace of {@link #userId}; numeric ids overlap across actor tables. */
    @Column(name = "actor_type", length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AuthenticatedActorType actorType = AuthenticatedActorType.FACTORY_USER;

    /**
     * 用户最初的输入
     */
    @Column(name = "original_input", columnDefinition = "TEXT", nullable = false)
    private String originalInput;

    /**
     * 最终识别的意图代码 (对话结束后填充)
     */
    @Column(name = "final_intent_code", length = 100)
    private String finalIntentCode;

    /**
     * 当前轮次 (最多5轮)
     */
    @Column(name = "current_round", nullable = false)
    @Builder.Default
    private Integer currentRound = 0;

    /**
     * 最大轮次限制
     */
    @Column(name = "max_rounds")
    @Builder.Default
    private Integer maxRounds = 5;

    /**
     * 会话状态
     */
    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 会话模式 (默认: 意图识别模式)
     */
    @Column(name = "session_mode", length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SessionMode sessionMode = SessionMode.INTENT_RECOGNITION;

    /**
     * 已知意图代码 (仅用于 PARAMETER_COLLECTION 模式)
     * 当模式为参数收集时，此字段存储已确定的意图代码
     */
    @Column(name = "known_intent_code", length = 100)
    private String knownIntentCode;

    /**
     * 必需参数列表 JSON (仅用于 PARAMETER_COLLECTION 模式)
     * 格式: [{"name": "batchId", "label": "批次ID", "type": "string", "collected": false, "value": null}]
     */
    @Column(name = "required_parameters_json", columnDefinition = "TEXT")
    private String requiredParametersJson;

    /**
     * 已收集参数 JSON
     * 格式: {"batchId": "BATCH-001", "materialTypeId": "RMT-001"}
     */
    @Column(name = "collected_parameters_json", columnDefinition = "TEXT")
    private String collectedParametersJson;

    /**
     * 对话历史 JSON
     * 格式: [{"role": "user/assistant", "content": "...", "timestamp": "..."}]
     */
    @Column(name = "messages_json", columnDefinition = "TEXT")
    private String messagesJson;

    /**
     * 候选意图列表 JSON
     * 格式: [{"intentCode": "...", "confidence": 0.5}]
     */
    @Column(name = "candidates_json", columnDefinition = "TEXT")
    private String candidatesJson;

    /**
     * 最后识别的置信度
     */
    @Column(name = "last_confidence")
    private Double lastConfidence;

    /**
     * 超时时间（分钟）
     */
    @Column(name = "timeout_minutes")
    @Builder.Default
    private Integer timeoutMinutes = 10;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (sessionId == null) {
            sessionId = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActiveAt == null) {
            lastActiveAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ========== 会话模式枚举 ==========

    /**
     * 会话模式：区分意图识别对话和参数收集对话
     */
    public enum SessionMode {
        /** 意图识别模式 - 帮助用户明确想做什么操作 */
        INTENT_RECOGNITION,
        /** 参数收集模式 - 意图已确定，收集缺失的必需参数 */
        PARAMETER_COLLECTION
    }

    // ========== 会话状态枚举 ==========

    public enum SessionStatus {
        /** 对话进行中 */
        ACTIVE,
        /** 成功识别意图 */
        COMPLETED,
        /** 超时未响应 */
        TIMEOUT,
        /** 用户取消 */
        CANCELLED,
        /** 达到最大轮次仍未识别 */
        MAX_ROUNDS_REACHED
    }

    // ========== 对话消息类 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role; // "user" or "assistant"
        private String content;
        private LocalDateTime timestamp;
        /** Client-supplied idempotency key for a reply. Optional for legacy rows. */
        private String requestId;
        /** SHA-256 of the normalized reply; never contains the reply itself. */
        private String requestFingerprint;
        /** Exact response snapshot used to replay a successful request without mutation. */
        private ReplyReceipt replyReceipt;

        public static Message user(String content) {
            return Message.builder()
                    .role("user")
                    .content(content)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public static Message user(String content, String requestId, String requestFingerprint) {
            return Message.builder()
                    .role("user")
                    .content(content)
                    .timestamp(LocalDateTime.now())
                    .requestId(requestId)
                    .requestFingerprint(requestFingerprint)
                    .build();
        }

        public static Message assistant(String content) {
            return Message.builder()
                    .role("assistant")
                    .content(content)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReplyReceipt {
        private String extractionOutcome;
        @com.fasterxml.jackson.annotation.JsonIgnore private int receiptRound;
        @com.fasterxml.jackson.annotation.JsonIgnore private int receiptMaxRounds;
        @com.fasterxml.jackson.annotation.JsonIgnore private String receiptStatus;
        public int getCurrentRound() { return receiptRound; }
        public void setCurrentRound(int value) { receiptRound = value; }
        public int getMaxRounds() { return receiptMaxRounds; }
        public void setMaxRounds(int value) { receiptMaxRounds = value; }
        public String getStatus() { return receiptStatus; }
        public void setStatus(String value) { receiptStatus = value; }
        public static class ReplyReceiptBuilder {
            public ReplyReceiptBuilder currentRound(int value) { receiptRound = value; return this; }
            public ReplyReceiptBuilder maxRounds(int value) { receiptMaxRounds = value; return this; }
            public ReplyReceiptBuilder status(String value) { receiptStatus = value; return this; }
        }
        private boolean completed;
        private String message;
        private String intentCode;
        private String intentName;
        private Double confidence;
        private boolean requiresConfirmation;
        private String requestId;
        private boolean progressMutated;
        private boolean correctionApplied;
        private ProviderAttemptReceipt providerAttempt;
        /** Stable per-field decisions preserved for idempotent replay. */
        private List<String> invalidFields;
        /** JSON-only response evidence; this does not add a database column. */
        private List<ReplyFieldReceipt> fieldReceipts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReplyFieldReceipt {
        private String field;
        private String resolution;
        private String canonicalValue;
        private String reasonCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderAttemptReceipt {
        private String attemptId;
        private String provider;
        private String model;
        private long latencyMs;
        private int attemptCount;
        private String failureCategory;
        private boolean retryable;
    }

    // ========== 候选意图类 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CandidateIntent {
        private String intentCode;
        private String intentName;
        private Double confidence;
        private String reasoning;
    }

    // ========== 便捷方法 ==========

    /**
     * 获取消息列表
     */
    public List<Message> getMessages() {
        if (messagesJson == null || messagesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(messagesJson, new TypeReference<List<Message>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("conversation messages JSON is unreadable", e);
        }
    }

    /**
     * 设置消息列表
     */
    public void setMessages(List<Message> messages) {
        try {
            this.messagesJson = OBJECT_MAPPER.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("conversation messages JSON cannot be serialized", e);
        }
    }

    /**
     * 添加消息
     */
    public void addMessage(Message message) {
        List<Message> messages = getMessages();
        messages.add(message);
        setMessages(messages);
        this.lastActiveAt = LocalDateTime.now();
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        addMessage(Message.user(content));
    }

    public void addUserMessage(String content, String requestId, String requestFingerprint) {
        addMessage(Message.user(content, requestId, requestFingerprint));
    }

    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        addMessage(Message.assistant(content));
    }

    public Optional<Message> findRequestMessage(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return getMessages().stream()
                .filter(message -> "user".equals(message.getRole()))
                .filter(message -> requestId.equals(message.getRequestId()))
                .findFirst();
    }

    public void attachReplyReceipt(String requestId, ReplyReceipt receipt) {
        List<Message> messages = getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("user".equals(message.getRole()) && requestId.equals(message.getRequestId())) {
                message.setReplyReceipt(receipt);
                setMessages(messages);
                return;
            }
        }
        throw new IllegalStateException("reply request message not found");
    }

    /**
     * 获取候选意图列表
     */
    public List<CandidateIntent> getCandidates() {
        if (candidatesJson == null || candidatesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(candidatesJson, new TypeReference<List<CandidateIntent>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 设置候选意图列表
     */
    public void setCandidates(List<CandidateIntent> candidates) {
        try {
            this.candidatesJson = OBJECT_MAPPER.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            this.candidatesJson = "[]";
        }
    }

    /**
     * 进入下一轮
     */
    public boolean nextRound() {
        if (currentRound >= maxRounds) {
            return false;
        }
        this.currentRound++;
        this.lastActiveAt = LocalDateTime.now();
        return true;
    }

    /**
     * 检查是否已超时
     */
    public boolean isExpired() {
        if (status != SessionStatus.ACTIVE) {
            return false;
        }
        if (lastActiveAt == null) {
            return false;
        }
        LocalDateTime expireTime = lastActiveAt.plusMinutes(timeoutMinutes);
        return LocalDateTime.now().isAfter(expireTime);
    }

    /**
     * 检查是否可以继续对话
     */
    public boolean canContinue() {
        return status == SessionStatus.ACTIVE && currentRound < maxRounds && !isExpired();
    }

    /**
     * 完成会话
     */
    public void complete(String intentCode, double confidence) {
        this.finalIntentCode = intentCode;
        this.lastConfidence = confidence;
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 超时会话
     */
    public void timeout() {
        this.status = SessionStatus.TIMEOUT;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 取消会话
     */
    public void cancel() {
        this.status = SessionStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 达到最大轮次
     */
    public void maxRoundsReached() {
        this.status = SessionStatus.MAX_ROUNDS_REACHED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 构建对话历史字符串 (用于发送给 LLM)
     */
    public String buildConversationHistory() {
        List<Message> messages = getMessages();
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 创建新会话 (意图识别模式)
     */
    public static FactoryAssemblyConversationSession create(String factoryId, Long userId, String originalInput) {
        return create(factoryId, userId, AuthenticatedActorType.FACTORY_USER, originalInput);
    }

    public static FactoryAssemblyConversationSession create(
            String factoryId,
            Long userId,
            AuthenticatedActorType actorType,
            String originalInput) {
        FactoryAssemblyConversationSession session = FactoryAssemblyConversationSession.builder()
                .factoryId(factoryId)
                .userId(userId)
                .actorType(actorType)
                .originalInput(originalInput)
                .currentRound(1)
                .maxRounds(5)
                .status(SessionStatus.ACTIVE)
                .sessionMode(SessionMode.INTENT_RECOGNITION)
                .timeoutMinutes(10)
                .build();

        // 添加初始用户消息
        session.addUserMessage(originalInput);

        return session;
    }

    /**
     * 创建参数收集会话
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param intentCode 已确定的意图代码
     * @param requiredParameters 需要收集的参数列表
     * @param initialQuestion 初始收集问题
     */
    public static FactoryAssemblyConversationSession createForParameterCollection(
            String factoryId,
            Long userId,
            String intentCode,
            List<RequiredParameter> requiredParameters,
            String initialQuestion) {
        // 默认轮次上限按参数条数算 —— 见下面重载的注释, 硬编码 5 会让 5 个参数收不齐。
        return createForParameterCollection(factoryId, userId, AuthenticatedActorType.FACTORY_USER, intentCode,
                requiredParameters, initialQuestion, defaultMaxRounds(requiredParameters));
    }

    public static FactoryAssemblyConversationSession createForParameterCollection(
            String factoryId,
            Long userId,
            AuthenticatedActorType actorType,
            String intentCode,
            List<RequiredParameter> requiredParameters,
            String initialQuestion) {
        return createForParameterCollection(factoryId, userId, actorType, intentCode,
                requiredParameters, initialQuestion, defaultMaxRounds(requiredParameters));
    }

    /**
     * 参数收集会话的默认轮次上限。
     *
     * ⚠️ 这里有个 off-by-one, 2026-08-10 被收敛闸实测抓到:
     *   会话从 `currentRound = 1` 开始(第 1 轮是开场提问, 不消费用户回答),
     *   而 `nextRound()` 的条件是 `currentRound >= maxRounds → false`。
     *   ⇒ maxRounds=5 时只有 4 次回复能进到参数抽取, 第 5 个参数永远收不齐。
     *   实测轨迹: [5 4 3 2 1 1] —— 单调下降但停在 1。
     *
     * 所以下限是 `参数条数 + 1`; 再加 2 轮余量给「用户答得含糊需要重问」的情况。
     * `max(5, ...)` 保证少参数场景与改动前逐字一致(不惊动既有调用方)。
     */
    private static int defaultMaxRounds(List<RequiredParameter> requiredParameters) {
        int n = requiredParameters == null ? 0 : requiredParameters.size();
        // 🔴 2026-08-16: 原来是 `n + 3` —— 固定 2 轮余量, 而余量必须**跟着题量走**。
        //    实测(全空工厂, 真实端点, 智谱): 45 题的访谈里约 20% 需要重问一次,
        //    固定 2 轮余量在第十来次重问时就见底, 后面的题**结构上到不了**,
        //    四次端到端没有一次走到落库(库里 0 行), 而每一步 HTTP 都是 200。
        //
        // ⚠️ 这个数与 {@link #MAX_ASK_ATTEMPTS} 是**同一件事的两半**:
        //    每题最多问 K 次 ⇒ 最坏 n*K 轮; 给 1.5n+3 覆盖「一半的题需要重问一次」,
        //    比最坏情况省, 但比固定余量稳得多。
        //    ⛔ 改 MAX_ASK_ATTEMPTS 时要回来看这里, 反之亦然。
        return Math.max(5, n + n / 2 + 3);
    }

    /**
     * 参数收集会话 —— 显式指定轮次上限。
     */
    public static FactoryAssemblyConversationSession createForParameterCollection(
            String factoryId,
            Long userId,
            String intentCode,
            List<RequiredParameter> requiredParameters,
            String initialQuestion,
            int maxRounds) {

        return createForParameterCollection(factoryId, userId, AuthenticatedActorType.FACTORY_USER,
                intentCode, requiredParameters, initialQuestion, maxRounds);
    }

    public static FactoryAssemblyConversationSession createForParameterCollection(
            String factoryId,
            Long userId,
            AuthenticatedActorType actorType,
            String intentCode,
            List<RequiredParameter> requiredParameters,
            String initialQuestion,
            int maxRounds) {

        FactoryAssemblyConversationSession session = FactoryAssemblyConversationSession.builder()
                .factoryId(factoryId)
                .userId(userId)
                .actorType(actorType)
                .originalInput("[参数收集] " + intentCode)
                .currentRound(1)
                .maxRounds(maxRounds)
                .status(SessionStatus.ACTIVE)
                .sessionMode(SessionMode.PARAMETER_COLLECTION)
                .knownIntentCode(intentCode)
                .timeoutMinutes(10)
                .build();

        // 设置必需参数
        session.setRequiredParameters(requiredParameters);

        // 添加初始助手消息
        session.addAssistantMessage(initialQuestion);

        return session;
    }

    // ========== 参数收集相关方法 ==========

    /**
     * 获取必需参数列表
     */
    public List<RequiredParameter> getRequiredParameters() {
        if (requiredParametersJson == null || requiredParametersJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(requiredParametersJson,
                    new TypeReference<List<RequiredParameter>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 设置必需参数列表
     */
    public void setRequiredParameters(List<RequiredParameter> parameters) {
        try {
            this.requiredParametersJson = OBJECT_MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            this.requiredParametersJson = "[]";
        }
    }

    /**
     * 获取已收集参数
     */
    public Map<String, String> getCollectedParameters() {
        if (collectedParametersJson == null || collectedParametersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(collectedParametersJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    /**
     * 设置已收集参数
     */
    public void setCollectedParameters(Map<String, String> parameters) {
        try {
            this.collectedParametersJson = OBJECT_MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            this.collectedParametersJson = "{}";
        }
    }

    /**
     * 添加收集到的参数
     */
    public void addCollectedParameter(String name, String value) {
        Map<String, String> params = getCollectedParameters();
        params.put(name, value);
        setCollectedParameters(params);

        // 更新参数状态
        List<RequiredParameter> required = getRequiredParameters();
        for (RequiredParameter param : required) {
            if (param.getName().equals(name)) {
                param.setCollected(true);
                param.setValue(value);
                // A later explicit answer resolves both a user-deferred decision and an older
                // system-abandoned item. The answer is now authoritative again.
                param.setUserDeferred(false);
                param.setSkipReason(null);
                param.setAskAttempts(0);
            }
        }
        setRequiredParameters(required);
        this.lastActiveAt = LocalDateTime.now();
    }

    /** Explicitly clears a collected answer so the same required parameter becomes askable again. */
    public boolean removeCollectedParameter(String name) {
        Map<String, String> params = getCollectedParameters();
        if (!params.containsKey(name)) {
            return false;
        }
        params.remove(name);
        setCollectedParameters(params);

        List<RequiredParameter> required = getRequiredParameters();
        for (RequiredParameter param : required) {
            if (param.getName().equals(name)) {
                param.setCollected(false);
                param.setValue(null);
                param.setAskAttempts(0);
                param.setReAskedOnce(false);
                param.setUserDeferred(false);
                param.setSkipReason(null);
            }
        }
        setRequiredParameters(required);
        this.lastActiveAt = LocalDateTime.now();
        return true;
    }

    /**
     * 获取下一个待收集的参数
     */
    public Optional<RequiredParameter> getNextPendingParameter() {
        return getRequiredParameters().stream()
                // ⛔ 已跳过的不再问 —— 否则「跳过」等于没跳(2026-08-16 实测:
                //    一题被反复问 16 轮, 后面的题结构上到不了)。
                .filter(p -> !p.isCollected() && p.getSkipReason() == null)
                .findFirst();
    }

    /**
     * 一个问项最多问几次(含第一次)。超过就跳过并留原因, ⛔ 不许无限追问。
     *
     * <p>⚠️ 2 = 第一次 + 重问一次, 与 {@code FactoryInterviewExpansion#rejectUnparsableCounts}
     * 对计数题定的「只退一次」是同一条纪律 —— ⛔ 两处别定两个数。
     *
     * <p>🔴 这个数和 {@link #defaultMaxRounds} 是**同一件事的两半**:
     * 定了「每题最多问 K 次」却只给 {@code n+3} 轮, 等于上限还没用到轮数就先没了。
     * 2026-08-16 实测: 把上限设成 3 之后确实没有任何一题被问超过 3 次(之前 16 次),
     * <b>但轮数照样用光</b> —— 45 题只给 48 轮, 十来次重问就把后面的题挤没了。
     */
    public static final int MAX_ASK_ATTEMPTS = 2;

    /**
     * 记一次「问了但没收上来」;到上限就跳过,并返回跳过理由(没到上限返回 null)。
     *
     * <p>⛔ 跳过 ≠ 收集到。{@code collected} 保持 false,只是不再阻塞完成 ——
     * 把拿不到的东西标成「拿到了」正是 2026-08-15 那个缺陷的形状。
     */
    public String noteAskFailed(String paramName) {
        List<RequiredParameter> params = getRequiredParameters();
        for (RequiredParameter p : params) {
            if (!p.getName().equals(paramName) || p.isCollected() || p.getSkipReason() != null) {
                continue;
            }
            p.setAskAttempts(p.getAskAttempts() + 1);
            if (p.getAskAttempts() >= MAX_ASK_ATTEMPTS) {
                p.setSkipReason("问了 " + p.getAskAttempts() + " 次没拿到确切答案");
                setRequiredParameters(params);
                return p.getSkipReason();
            }
            setRequiredParameters(params);
            return null;
        }
        return null;
    }

    /**
     * Records an explicit "temporarily unknown" decision without pretending it was answered.
     *
     * <p>The parameter is removed from the immediate question queue so the conversation can
     * continue, but {@link #allParametersCollected()} remains false until the user supplies a
     * real answer. This is different from a provider failure that reaches the retry ceiling.</p>
     */
    public boolean deferParameter(String paramName) {
        Map<String, String> collected = getCollectedParameters();
        collected.remove(paramName);
        setCollectedParameters(collected);

        List<RequiredParameter> params = getRequiredParameters();
        for (RequiredParameter parameter : params) {
            if (!parameter.getName().equals(paramName)) {
                continue;
            }
            parameter.setCollected(false);
            parameter.setValue(null);
            parameter.setUserDeferred(true);
            parameter.setSkipReason("你选择了“暂时不确定”，完整方案确认前需要补充");
            setRequiredParameters(params);
            this.lastActiveAt = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /**
     * <b>还问得动</b>的项 —— 「这场访谈还有没有题可问」的<b>唯一</b>判据。
     *
     * <p>⛔ 已放弃的({@code skipReason != null})不算。这与
     * {@link #getNextPendingParameter()} 是同一条纪律的两个粒度:
     * 一个回答「下一题问哪个」, 一个回答「还有没有下一题」。
     *
     * <h3>🔴 被治的那个缺陷(2026-08-16 本地端到端实测)</h3>
     *
     * #142 引入「跳过」时明确写着「跳过<b>不阻塞完成</b>」, 会话这一侧照做了
     * ({@link #allParametersCollected()}), 而<b>建厂那一侧从来没接上</b> ——
     * {@code FactoryBuildInterviewService#progress} 自己按 collected map
     * 数了一遍 pending, 把已放弃的项照样算成「还没答」:
     *
     * <pre>
     * 46 轮 status=COMPLETED   collected 40 项   pending 3 项(全是被跳过的那 3 项)
     * readyForGenerate=false   assembledDescription 长度 0
     * 生成 http=400「工厂描述不能为空」
     * </pre>
     *
     * 客户已经被当面告知「这一项先跳过」, 系统却因此再也不肯往下走。
     *
     * <p>⚠️ 判「有没有答案」看 {@link #getCollectedParameters()}(<b>被下游消费的那一份</b>),
     * ⛔ 不看实体的 {@code collected} 标志 —— 那是仓里已知的第二处口径,
     * 而 {@code progress} 的注释里写着「判据要贴着被消费的那份」。
     */
    public List<RequiredParameter> askableParameters() {
        List<RequiredParameter> params = getRequiredParameters();
        if (params == null) {
            return List.of();
        }
        Map<String, String> answers = getCollectedParameters();
        if (answers == null) {
            answers = Map.of();
        }
        final Map<String, String> finalAnswers = answers;
        return params.stream()
                .filter(p -> {
                    String v = finalAnswers.get(p.getName());
                    return (v == null || v.trim().isEmpty()) && p.getSkipReason() == null;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** 被跳过的问项(拿不到, 已放弃追问)—— ⛔ 必须让客户看见。 */
    public List<RequiredParameter> getSkippedParameters() {
        return getRequiredParameters().stream()
                .filter(p -> p.getSkipReason() != null && !p.isUserDeferred())
                .collect(java.util.stream.Collectors.toList());
    }

    /** Explicitly deferred decisions remain visible and block final confirmation. */
    public List<RequiredParameter> getDeferredParameters() {
        return getRequiredParameters().stream()
                .filter(RequiredParameter::isUserDeferred)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 检查是否所有参数都已收集
     */
    /**
     * 按数量把「重复段」展开成 N 份子项 (访谈规格 v2 的 repeat 模态)。
     *
     * 例: 客户答「4 道工序」→ 展开成 step1.name / step1.inputMaterial / … /
     *     step4.yieldRate, 每份带 repeatIndex。
     *
     * ## ⛔ 展开必须同时抬 maxRounds —— 这是同一件事的两半
     *
     * 2026-08-10 已经栽过一次同形状: maxRounds 硬编码 5, 而参数有 5 个,
     * 会话从 currentRound=1 起且 nextRound() 判 `currentRound >= maxRounds`,
     * 于是**只有 4 次回复能进到参数抽取, 第 5 个参数永远收不齐**。
     * 表现是 remaining 轨迹 [5 4 3 2 1 1] —— 单调下降却停在 1,
     * 而 status 仍是 ACTIVE, **从状态码上完全看不出来**。
     *
     * 展开会把参数从 M 个变成 M-1+N*K 个。若 maxRounds 不跟着涨,
     * 同一个缺陷会以更严重的形式复发(展开越多、截断越多)。
     * 所以本方法**自己负责**把 maxRounds 抬到 `参数总数 + 1` 以上。
     *
     * ## 幂等与更正
     *
     * 已存在的同序号子项不会重复展开；数量更正会原子地增补缺项或删除超出新范围的
     * 子项和答案。范围内已经确认的答案保持不变。
     *
     * @param templateKey 重复段的 key (如 "step")
     * @param count       展开份数
     * @param subItems    子项模板 (name/label/validationHint 会带上序号)
     * @return 实际新增或删除的子项数; 数量未变化或入参无效时为 0
     */
    public int expandRepeat(String templateKey, int count, List<RequiredParameter> subItems) {
        return reconcileRepeat(templateKey, count, subItems);
    }

    /**
     * Reconcile a repeat segment to an explicitly corrected count.
     *
     * <p>Existing in-range rows and answers are preserved. Growing adds only missing rows;
     * shrinking removes out-of-range rows and their collected answers in the same JSON mutation.
     * The caller snapshots the session before invoking this method, so a later failure restores
     * the driver, children and answers together.</p>
     *
     * @return number of required-parameter rows added or removed
     */
    public int reconcileRepeat(String templateKey, int count, List<RequiredParameter> subItems) {
        if (templateKey == null || templateKey.isEmpty() || count <= 0
                || subItems == null || subItems.isEmpty()) {
            return 0;
        }
        List<RequiredParameter> params = new ArrayList<>(getRequiredParameters());
        String prefix = templateKey;
        java.util.Set<String> removedNames = params.stream()
                .map(RequiredParameter::getName)
                .filter(java.util.Objects::nonNull)
                .filter(name -> repeatIndex(name, prefix) > count)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        params.removeIf(param -> removedNames.contains(param.getName()));

        Map<String, String> collected = new LinkedHashMap<>(getCollectedParameters());
        collected.keySet().removeIf(name -> repeatIndex(name, prefix) > count);

        java.util.Set<String> existing = params.stream()
                .map(RequiredParameter::getName)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        int added = 0;
        for (int i = 1; i <= count; i++) {
            for (RequiredParameter tpl : subItems) {
                String name = templateKey + i + "." + tpl.getName();
                if (existing.contains(name)) {
                    continue;
                }
                RequiredParameter one = RequiredParameter.builder()
                        .name(name)
                        .label("第" + i + "道·" + tpl.getLabel())
                        .type(tpl.getType())
                        .validationHint(tpl.getValidationHint() == null ? null
                                : tpl.getValidationHint().replace("{i}", String.valueOf(i)))
                        .collected(false)
                        .inputType(tpl.getInputType())
                        .options(tpl.getOptions())
                        .multiSelect(tpl.isMultiSelect())
                        .allowNone(tpl.isAllowNone())
                        .repeatIndex(i)
                        .build();
                params.add(one);
                existing.add(name);
                added++;
            }
        }
        setRequiredParameters(params);
        setCollectedParameters(collected);

        // ⛔ 与展开同时做, 不能分开 —— 分开就一定会有人只做一半。
        //
        // 🔴 2026-08-16 本地端到端实测抓到的缺陷: 这里原来写的是 `params.size() + 1`,
        //    而**初始**那份公式是 `defaultMaxRounds()` = `max(5, n + 3)` ——
        //    多出来的 2 轮是余量, 那份公式自己的注释写着
        //    「再加 2 轮余量给『用户答得含糊需要重问』的情况」。
        //    同一个东西两份口径, 展开一发生, 余量就悄悄没了(形态 D)。
        //
        //    实测长相: 43 个参数 → maxRounds 44 → 客户有**一次**答得含糊被重问
        //    (物料编码大类问了两遍)→ 轮数当场用光 → 最后 2 项永远问不到
        //    → readyForGenerate=false → 生成端点 400「工厂描述不能为空」
        //    → **库里一行都没写**。而每一步 HTTP 都是 200。
        //
        // ⚠️ 余量现在是**必需**的, 不是保险: FactoryInterviewExpansion
        //    #rejectUnparsableCounts 会在计数题答不出数时主动重问一次 ——
        //    重问从「偶尔」变成了「设计上必然」。
        //
        // ⇒ 收敛成一份: 调 defaultMaxRounds, 两条路同一个口径。
        int needed = defaultMaxRounds(params);
        if (this.maxRounds == null || this.maxRounds < needed) {
            this.maxRounds = needed;
        }
        this.lastActiveAt = LocalDateTime.now();
        return added + removedNames.size();
    }

    private static int repeatIndex(String name, String templateKey) {
        if (name == null || templateKey == null || !name.startsWith(templateKey)) {
            return -1;
        }
        int dot = name.indexOf('.', templateKey.length());
        if (dot <= templateKey.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(templateKey.length(), dot));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * 是否已经展开过某个重复段。
     */
    public boolean isRepeatExpanded(String templateKey) {
        String prefix = templateKey + "1.";
        for (RequiredParameter p : getRequiredParameters()) {
            if (p.getName() != null && p.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean allParametersCollected() {
        // ⛔ 已跳过的不阻塞完成, 但它**没有被当成收集到** —— collected 仍是 false,
        //    而且 getSkippedParameters() 会把它交给客户看(目标③:
        //    「跳过的必须说清为什么」)。
        //    把拿不到的东西标成「拿到了」正是 2026-08-15 那个缺陷的形状。
        return getRequiredParameters().stream()
                .allMatch(p -> p.isCollected()
                        || (p.getSkipReason() != null && !p.isUserDeferred()));
    }

    /**
     * 完成参数收集会话
     */
    public void completeParameterCollection() {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        // 对于参数收集模式，finalIntentCode 已经在创建时设置
        if (this.finalIntentCode == null) {
            this.finalIntentCode = this.knownIntentCode;
        }
        this.lastConfidence = 1.0; // 参数收集完成置信度为 100%
    }

    /**
     * 检查会话是否为参数收集模式
     */
    public boolean isParameterCollectionMode() {
        return sessionMode == SessionMode.PARAMETER_COLLECTION;
    }

    // ========== 参数实体类 ==========

    /**
     * 必需参数定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequiredParameter {
        /** 参数名称 (API 字段名) */
        private String name;
        /** 显示标签 */
        private String label;
        /** 参数类型 */
        private String type;
        /** 验证提示 */
        private String validationHint;
        /** 是否已收集 */
        private boolean collected;
        /** 收集到的值 */
        private String value;
        /**
         * 这一题<b>因为答案不合形状被退回重问过一次</b>。
         *
         * <p>🔴 2026-08-16 加。为什么必须是**独立字段**:
         * 我第一版想用「{@code !collected && value != null}」当这个标记,
         * 而 {@link FactoryAssemblyConversationSession#addCollectedParameter} 在**正常收集时就会
         * {@code setValue}** ⇒ 那个条件永远为真, 退回分支一次都没进过。
         * 实测日志里全是「第二次仍数不出数」, 一条「退回重问一次」都没有 ——
         * <b>修复到达了代码, 只是标记选错了</b>。
         *
         * <p>⚠️ 只退一次: {@link FactoryAssemblyConversationSession#getNextPendingParameter()} 取的是
         * 声明序第一个未收集项, 无条件退回会把访谈卡死在同一题上。
         *
         * <p>⛔ 可选字段, 不填等于旧行为 —— 老会话的 JSON 里没有它, 反序列化后为 false。
         */
        private boolean reAskedOnce;

        /**
         * 这一题<b>被问过几次</b>(只数「问了但这一轮没收上来」的那种)。
         *
         * <p>🔴 2026-08-16 本地端到端实测: 客户答「产量、良品率」被判定不完整,
         * 模型留 null, 于是<b>同一题一字不变地再问一遍</b> —— 实测连问 <b>16 轮</b>,
         * 「还需要 35 项」一动不动, 客户看不到任何解释。
         * 一题吃掉十几轮之后, 后面的题<b>结构上就到不了</b>。
         */
        private int askAttempts;

        /**
         * 问了 {@link #MAX_ASK_ATTEMPTS} 次仍然拿不到 ⇒ <b>跳过</b>,理由记在这里。
         *
         * <p>⛔ 跳过<b>不是</b>把它当成收集到了 —— 那正是 2026-08-15 那个缺陷
         * (把「厂名」当成工序道数收下, 然后报告「问齐了」)。
         * 跳过的项:{@code collected=false} 且不再阻塞完成,但<b>必须出现在
         * 给客户的交代里</b> —— 目标③ 的另一半是「跳过的必须说清为什么」。
         */
        private String skipReason;

        /**
         * The user explicitly chose "temporarily unknown". JSON-only and backward compatible:
         * older sessions deserialize to false. Unlike retry-ceiling skips, this blocks final
         * confirmation until a later answer clears it.
         */
        private boolean userDeferred;

        // ==== v2 题型扩展 (2026-08-10) ====
        // ⛔ 全部可选, 不填等于旧行为(自由文本) —— IntentExecutionOrchestrator /
        //    SlotFillingServiceImpl 两个既有调用方不受影响。
        //
        // 起因: 访谈规格 v2 指出「能枚举的不要用问答」——
        //   「有没有客供料」这种二选一, 勾选一秒钟, 用问答要来回三轮还可能答偏。
        //   而 LLM 抽取对枚举项是**负作用**: 它会把「差不多」的说法归一到某个选项,
        //   而那正是我们不想要的猜测。

        /** 题型: NARRATIVE(默认) | CHOICE | TABLE | MATRIX */
        private String inputType;

        /** CHOICE 的候选项; 非 CHOICE 时为空 */
        private java.util.List<String> options;

        /** CHOICE 是否可多选 */
        private boolean multiSelect;

        /** 是否允许「没有/不适用」—— 对应规格里的 allowNone */
        private boolean allowNone;

        /** repeat 展开时的序号(第几道工序); 非展开项为 null */
        private Integer repeatIndex;
    }
}

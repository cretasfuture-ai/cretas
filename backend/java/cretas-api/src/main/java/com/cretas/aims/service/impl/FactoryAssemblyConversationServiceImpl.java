package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession.CandidateIntent;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession.SessionStatus;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationActor;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.blueprint.interview.ChoiceAnswerMatcher;
import com.cretas.aims.blueprint.interview.FactoryInterviewExpansion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多轮对话服务实现
 *
 * 实现 Layer 5 的多轮对话流程:
 * 1. 当 Layer 1-4 置信度 < 30% 时触发
 * 2. 最多进行 5 轮对话
 * 3. 每轮生成澄清问题或确认结果
 * 4. 成功后学习表达和关键词
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryAssemblyConversationServiceImpl implements FactoryAssemblyConversationService {

    private final FactoryAssemblyConversationSessionRepository sessionRepository;
    private final AIIntentConfigRepository intentConfigRepository;
    private final ExpressionLearningService learningService;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    @Value("${cretas.ai.conversation.max-rounds:5}")
    private int maxRounds;

    @Value("${cretas.ai.conversation.timeout-minutes:10}")
    private int timeoutMinutes;

    @Value("${cretas.ai.conversation.confidence-threshold:0.7}")
    private double confidenceThreshold;

    @Autowired
    public FactoryAssemblyConversationServiceImpl(
            FactoryAssemblyConversationSessionRepository sessionRepository,
            AIIntentConfigRepository intentConfigRepository,
            @Autowired(required = false) ExpressionLearningService learningService,
            @Autowired(required = false) DashScopeClient dashScopeClient) {
        this.sessionRepository = sessionRepository;
        this.intentConfigRepository = intentConfigRepository;
        this.learningService = learningService;
        this.dashScopeClient = dashScopeClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @Transactional
    public ConversationResponse startConversation(
            String factoryId, ConversationActor actor, String userInput) {
        Long userId = actor.userId();
        log.info("开始多轮对话: factory={}, actorType={}, user={}, input={}",
                factoryId, actor.type(), userId, truncate(userInput, 50));

        // 取消之前的活跃会话
        sessionRepository.cancelActiveSessionsForActor(
                factoryId, userId, actor.type(), LocalDateTime.now());

        // 创建新会话
        FactoryAssemblyConversationSession session = FactoryAssemblyConversationSession.create(
                factoryId, userId, actor.type(), userInput);
        session.setMaxRounds(maxRounds);
        session.setTimeoutMinutes(timeoutMinutes);

        // 获取可用意图列表
        List<AIIntentConfig> availableIntents = getAvailableIntents(factoryId);

        // 调用 LLM 生成初始响应
        LlmConversationResult llmResult = callLlmForConversation(session, userInput, availableIntents);

        // 更新会话
        session.addAssistantMessage(llmResult.getMessage());
        session.setCandidates(llmResult.getCandidates());
        session.setLastConfidence(llmResult.getConfidence());

        // 检查是否已经可以确定意图
        if (llmResult.isIntentConfirmed() && llmResult.getConfidence() >= confidenceThreshold) {
            session.complete(llmResult.getIntentCode(), llmResult.getConfidence());
            log.info("首轮对话即确定意图: session={}, intent={}, confidence={}",
                    session.getSessionId(), llmResult.getIntentCode(), llmResult.getConfidence());
        }

        sessionRepository.save(session);

        return buildResponse(session, llmResult);
    }

    @Override
    @Transactional
    public ConversationResponse startParameterCollection(
            String factoryId,
            ConversationActor actor,
            String intentCode,
            String intentName,
            List<RequiredParameter> requiredParameters,
            List<String> clarificationQuestions) {

        Long userId = actor.userId();
        log.info("开始参数收集对话: factory={}, actorType={}, user={}, intent={}, params={}",
                factoryId, actor.type(), userId, intentCode, requiredParameters.size());

        // 取消之前的活跃会话
        sessionRepository.cancelActiveSessionsForActor(
                factoryId, userId, actor.type(), LocalDateTime.now());

        // 构建初始收集问题
        String initialQuestion = buildParameterCollectionMessage(
                intentName, requiredParameters, clarificationQuestions);

        // 转换参数类型
        // ⛔ 这里必须逐字段全搬。
        //
        // 2026-08-11 实测: 原先只搬了 name/label/type/validationHint,
        // 把 inputType / options / multiSelect / allowNone **在建会话那一步就丢了**。
        // 后果不是"少几个字段", 是**枚举题退化成自由问答**:
        //   · 界面画不出选项按钮, 客户只能打字;
        //   · 打的字再交给 LLM 去猜他选了哪个 —— 而「能枚举的不要用问答」正是 v2 的判据。
        // 定义端 choiceParam(...) 写得好好的, 会话里却是 NARRATIVE, 两处口径就这么漂了,
        // 而每一层都返回 200。
        List<FactoryAssemblyConversationSession.RequiredParameter> sessionParams = requiredParameters.stream()
                .map(p -> FactoryAssemblyConversationSession.RequiredParameter.builder()
                        .name(p.getName())
                        .label(p.getLabel())
                        .type(p.getType())
                        .validationHint(p.getValidationHint())
                        .inputType(p.getInputType())
                        .options(p.getOptions())
                        .multiSelect(p.isMultiSelect())
                        .allowNone(p.isAllowNone())
                        .collected(false)
                        .build())
                .collect(Collectors.toList());

        // 创建参数收集会话
        FactoryAssemblyConversationSession session = FactoryAssemblyConversationSession.createForParameterCollection(
                factoryId, userId, actor.type(), intentCode, sessionParams, initialQuestion);

        // 轮次上限: 配置值是给**意图澄清**用的(5 轮澄清合理), 但参数收集的需求不同 ——
        // 每个参数至少要有一轮才可能问齐。
        //
        // ⚠️ 2026-08-10 被收敛闸实测抓到的 off-by-one:
        //   会话从 currentRound=1 起(第 1 轮是开场提问, 不消费回答), 而
        //   nextRound() 的条件是 `currentRound >= maxRounds → false`,
        //   ⇒ maxRounds=5 只有 4 次回复能进到参数抽取, 第 5 个参数永远收不齐。
        //   实测轨迹 [5 4 3 2 1 1] —— 单调下降却停在 1, 且 status 仍是 ACTIVE,
        //   所以从状态码上完全看不出来。
        //
        // 取 max(配置值, 参数数+1): 少参数场景与改动前逐字一致(不惊动既有调用方),
        // 参数多时才抬高下限。⛔ 不能直接用配置值 —— 那等于让「能不能问齐」
        // 取决于一个与参数条数无关的常量。
        int effectiveMaxRounds = Math.max(maxRounds, sessionParams.size() + 1);
        if (effectiveMaxRounds != maxRounds) {
            log.info("参数收集轮次上限按参数条数抬高: {} -> {} (参数 {} 项)",
                    maxRounds, effectiveMaxRounds, sessionParams.size());
        }
        session.setMaxRounds(effectiveMaxRounds);
        session.setTimeoutMinutes(timeoutMinutes);

        sessionRepository.save(session);

        log.info("参数收集会话已创建: sessionId={}, intentCode={}", session.getSessionId(), intentCode);

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(false)
                .message(initialQuestion)
                .intentCode(intentCode)
                .intentName(intentName)
                .requiresConfirmation(true)
                .build();
    }

    @Override
    @Transactional
    public ConversationResponse continueConversation(
            String factoryId, ConversationActor actor, String sessionId, String userReply) {
        return continueConversation(
                factoryId, actor, sessionId, userReply, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public ConversationResponse continueConversation(
            String factoryId,
            ConversationActor actor,
            String sessionId,
            String userReply,
            String requestId) {
        Long userId = actor.userId();
        log.info("继续多轮对话: session={}, reply={}", sessionId, truncate(userReply, 50));

        Optional<FactoryAssemblyConversationSession> optSession =
                sessionRepository.findReplyTargetForUpdate(
                        sessionId, factoryId, userId, actor.type());
        if (optSession.isEmpty()) {
            log.warn("会话不存在: {}", sessionId);
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(SessionStatus.CANCELLED)
                    .completed(false)
                    .message("会话不存在或已过期")
                    .build();
        }

        FactoryAssemblyConversationSession session = optSession.get();
        String stableRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        if (stableRequestId.length() > 100) {
            return extractionFailureResponse(
                    session, stableRequestId, ExtractionOutcome.CONFLICT, null,
                    "请求标识过长，这句话没有写入。请刷新后重新发送。", List.of("requestId"));
        }
        if (userReply == null || userReply.isBlank() || userReply.length() > 10000) {
            return extractionFailureResponse(
                    session, stableRequestId, ExtractionOutcome.INVALID_EXTRACTION, null,
                    "回复必须包含内容且不能超过 10000 个字符，这句话没有写入。", List.of("userReply"));
        }
        String requestFingerprint = replyFingerprint(userReply);

        Optional<FactoryAssemblyConversationSession.Message> priorRequest = session.findRequestMessage(stableRequestId);
        if (priorRequest.isPresent()) {
            FactoryAssemblyConversationSession.Message prior = priorRequest.get();
            if (!Objects.equals(prior.getRequestFingerprint(), requestFingerprint)) {
                return extractionFailureResponse(
                        session, stableRequestId, ExtractionOutcome.CONFLICT, null,
                        "这个请求标识已经用于另一条回答。请刷新会话后重新发送。",
                        List.of("requestId"));
            }
            if (prior.getReplyReceipt() == null) {
                return extractionFailureResponse(
                        session, stableRequestId, ExtractionOutcome.CONFLICT, null,
                        "这条回答已有处理记录，但回执不完整。请刷新会话确认当前进度。",
                        List.of("requestId"));
            }
            return responseFromReceipt(session, prior.getReplyReceipt());
        }

        // 检查会话状态
        if (session.isExpired()) {
            session.timeout();
            sessionRepository.save(session);
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(SessionStatus.TIMEOUT)
                    .completed(false)
                    .message("会话已超时，请重新开始")
                    .build();
        }

        if (!session.canContinue()) {
            return ConversationResponse.builder()
                    .sessionId(sessionId)
                    .status(session.getStatus())
                    .completed(session.getStatus() == SessionStatus.COMPLETED)
                    .message(getStatusMessage(session.getStatus()))
                    .build();
        }

        // 根据会话模式分发处理
        if (session.isParameterCollectionMode()) {
            ConversationResponse response = continueParameterCollectionTyped(
                    session, userReply, stableRequestId, requestFingerprint);
            if (response.getExtractionOutcome() == ExtractionOutcome.SUCCESS
                    || response.getExtractionOutcome() == ExtractionOutcome.PARTIAL_SUCCESS) {
                session.attachReplyReceipt(stableRequestId, receiptFromResponse(response));
                sessionRepository.save(session);
            }
            return response;
        } else {
            return continueIntentRecognition(session, userReply);
        }
    }

    private static final int MAX_EXTRACTED_VALUE_LENGTH = 2000;

    /**
     * Parameter replies validate every candidate before mutation. Valid fields are committed as one
     * session transaction and invalid fields remain pending with explicit receipts. Structural JSON,
     * UNKNOWN, provider failure and request conflicts still fail closed without mutation.
     */
    private ConversationResponse continueParameterCollectionTyped(
            FactoryAssemblyConversationSession session,
            String userReply,
            String requestId,
            String requestFingerprint) {
        String askedThisRound = session.getNextPendingParameter()
                .map(FactoryAssemblyConversationSession.RequiredParameter::getName).orElse(null);

        if (session.getCurrentRound() >= session.getMaxRounds()) {
            return extractionFailureResponse(
                    session, requestId, ExtractionOutcome.CONFLICT, null,
                    "对话已达最大轮次，这句话没有写入。请重新开始访谈。", List.of());
        }
        if (isExplicitUnknownReply(userReply)) {
            return extractionFailureResponse(
                    session, requestId, ExtractionOutcome.UNKNOWN, null,
                    "好的，这一项仍保持未回答。你可以稍后补充，或使用页面提供的明确选项。",
                    askedThisRound == null ? List.of() : List.of(askedThisRound));
        }

        String exactChoice = ChoiceAnswerMatcher.exactOption(session, askedThisRound, userReply);
        if (exactChoice == null) {
            exactChoice = ChoiceAnswerMatcher.numericOption(session, askedThisRound, userReply);
        }

        boolean correctionRequested = looksLikeCorrection(userReply);
        List<FactoryAssemblyConversationSession.RequiredParameter> candidates = session.getRequiredParameters().stream()
                .filter(p -> correctionRequested || !p.isCollected())
                .collect(Collectors.toList());
        boolean standaloneChoice = exactChoice != null
                && isStandaloneChoiceReply(userReply, exactChoice);
        LlmParameterExtractionResult extraction = standaloneChoice
                ? LlmParameterExtractionResult.builder()
                        .outcome(ExtractionOutcome.SUCCESS)
                        .successful(true)
                        .extractedParams(new LinkedHashMap<>())
                        .clearParams(new ArrayList<>())
                        .invalidFields(new ArrayList<>())
                        .build()
                : extractParametersTyped(session, userReply, candidates);

        if (extraction.getOutcome() != ExtractionOutcome.SUCCESS) {
            List<String> failureFields = safeList(extraction.getInvalidFields());
            if (failureFields.isEmpty() && askedThisRound != null
                    && (extraction.getOutcome() == ExtractionOutcome.UNKNOWN
                        || extraction.getOutcome() == ExtractionOutcome.CONFLICT)) {
                failureFields = List.of(askedThisRound);
            }
            return extractionFailureResponse(
                    session,
                    requestId,
                    extraction.getOutcome(),
                    extraction.getProviderAttempt(),
                    extractionFailureMessage(extraction.getOutcome()),
                    failureFields);
        }

        Map<String, String> raw = new LinkedHashMap<>(
                extraction.getExtractedParams() == null ? Map.of() : extraction.getExtractedParams());
        if (exactChoice != null) {
            raw.put(askedThisRound, exactChoice);
        }
        extraction.setExtractedParams(raw);

        ExtractionValidation validation = validateExtraction(
                extraction, candidates, session, userReply, askedThisRound);
        if (!validation.valid()) {
            return extractionFailureResponse(
                    session, requestId, validation.outcome(), extraction.getProviderAttempt(),
                    extractionFailureMessage(validation.outcome()), validation.invalidFields());
        }

        SessionMutationSnapshot snapshot = snapshot(session);
        boolean correctionApplied = false;
        try {
            for (String clear : validation.clearFields()) {
                correctionApplied |= session.removeCollectedParameter(clear);
            }
            for (Map.Entry<String, String> entry : validation.values().entrySet()) {
                String old = session.getCollectedParameters().get(entry.getKey());
                correctionApplied |= old != null && !Objects.equals(old, entry.getValue());
                session.addCollectedParameter(entry.getKey(), entry.getValue());
            }

            FactoryInterviewExpansion.expandIfReady(session);
            FactoryInterviewExpansion.expandProductsIfReady(session);

            session.addUserMessage(userReply, requestId, requestFingerprint);
            if (!session.nextRound()) {
                restore(session, snapshot);
                return extractionFailureResponse(
                        session, requestId, ExtractionOutcome.CONFLICT, extraction.getProviderAttempt(),
                        "对话轮次已发生变化，请刷新后重试。", List.of());
            }

            List<String> reAsked = FactoryInterviewExpansion.rejectUnparsableCounts(session);
            String justSkipped = null;
            if (askedThisRound != null && !session.getCollectedParameters().containsKey(askedThisRound)) {
                justSkipped = session.noteAskFailed(askedThisRound);
            }

            String message;
            boolean completed;
            if (session.allParametersCollected()) {
                session.completeParameterCollection();
                completed = true;
                List<FactoryAssemblyConversationSession.RequiredParameter> skipped = session.getSkippedParameters();
                if (skipped.isEmpty()) {
                    message = String.format("好的，已收集到所有必需信息。正在为您执行「%s」...",
                            session.getKnownIntentCode());
                } else {
                    StringBuilder sb = new StringBuilder("好的，能问到的都问完了。\n\n⚠️ 有 ")
                            .append(skipped.size()).append(" 项没拿到，这部分会留空：\n");
                    for (FactoryAssemblyConversationSession.RequiredParameter p : skipped) {
                        sb.append("· ").append(p.getLabel() == null ? p.getName() : p.getLabel())
                                .append("（").append(p.getSkipReason()).append("）\n");
                    }
                    message = sb.append("\n这些可以之后在对应的配置页里补上。现在先按已有的信息往下走。")
                            .toString();
                }
            } else {
                completed = false;
                message = buildNextParameterQuestion(session);
                if (!reAsked.isEmpty()) {
                    message = "刚才那句我没读出个数来，这一项得是个数字（比如「3 道」）。\n\n" + message;
                }
                if (justSkipped != null) {
                    message = "这一项我没能从你的回答里拿到确切内容，先跳过（"
                            + justSkipped + "），最后会一并告诉你哪些留空了。\n\n" + message;
                }
            }
            if (validation.outcome() == ExtractionOutcome.PARTIAL_SUCCESS) {
                message = "已保存能确认的字段；未通过校验的字段仍保持待回答："
                        + validation.invalidFields().stream()
                                .map(field -> parameterLabel(session, field))
                                .collect(Collectors.joining("、"))
                        + "。\n\n" + message;
            }
            session.addAssistantMessage(message);

            return ConversationResponse.builder()
                    .sessionId(session.getSessionId())
                    .currentRound(session.getCurrentRound())
                    .maxRounds(session.getMaxRounds())
                    .status(session.getStatus())
                    .completed(completed)
                    .message(message)
                    .intentCode(session.getKnownIntentCode())
                    .confidence(completed ? 1.0 : null)
                    .requiresConfirmation(!completed)
                    .extractionOutcome(validation.outcome())
                    .providerAttempt(extraction.getProviderAttempt())
                    .requestId(requestId)
                    .progressMutated(true)
                    .correctionApplied(correctionApplied)
                    .invalidFields(validation.invalidFields())
                    .fieldReceipts(fieldReceipts(validation))
                    .build();
        } catch (RuntimeException e) {
            restore(session, snapshot);
            throw e;
        }
    }

    /**
     * 继续意图识别对话 (原有逻辑)
     */
    private ConversationResponse continueIntentRecognition(FactoryAssemblyConversationSession session, String userReply) {
        // 添加用户消息
        session.addUserMessage(userReply);

        // 检查是否是直接确认 (用户选择了候选意图)
        String confirmedIntent = checkDirectConfirmation(userReply, session.getCandidates());
        if (confirmedIntent != null) {
            session.complete(confirmedIntent, 0.95);
            sessionRepository.save(session);

            // 触发学习
            learnFromSession(session);

            return buildConfirmedResponse(session, confirmedIntent);
        }

        // 进入下一轮
        if (!session.nextRound()) {
            session.maxRoundsReached();
            sessionRepository.save(session);
            return ConversationResponse.builder()
                    .sessionId(session.getSessionId())
                    .currentRound(session.getCurrentRound())
                    .maxRounds(session.getMaxRounds())
                    .status(SessionStatus.MAX_ROUNDS_REACHED)
                    .completed(false)
                    .message("对话已达最大轮次，无法确定您的意图。请尝试更清晰地描述您的需求。")
                    .build();
        }

        // 获取可用意图
        List<AIIntentConfig> availableIntents = getAvailableIntents(session.getFactoryId());

        // 调用 LLM 继续对话
        LlmConversationResult llmResult = callLlmForConversation(session, userReply, availableIntents);

        // 更新会话
        session.addAssistantMessage(llmResult.getMessage());
        session.setCandidates(llmResult.getCandidates());
        session.setLastConfidence(llmResult.getConfidence());

        // 检查是否确定意图
        if (llmResult.isIntentConfirmed() && llmResult.getConfidence() >= confidenceThreshold) {
            session.complete(llmResult.getIntentCode(), llmResult.getConfidence());
            log.info("多轮对话确定意图: session={}, round={}, intent={}, confidence={}",
                    session.getSessionId(), session.getCurrentRound(),
                    llmResult.getIntentCode(), llmResult.getConfidence());

            // 触发学习
            learnFromSession(session);
        }

        sessionRepository.save(session);

        return buildResponse(session, llmResult);
    }

    @Override
    @Transactional
    public boolean endConversation(
            String factoryId, ConversationActor actor, String sessionId, String intentCode) {
        Long userId = actor.userId();
        log.info("结束对话: session={}, intent={}", sessionId, intentCode);

        Optional<FactoryAssemblyConversationSession> optSession =
                sessionRepository.findBySessionIdAndFactoryIdAndUserIdAndActorType(
                        sessionId, factoryId, userId, actor.type());
        if (optSession.isEmpty()) {
            return false;
        }

        FactoryAssemblyConversationSession session = optSession.get();
        session.complete(intentCode, 1.0); // 用户确认置信度为1
        sessionRepository.save(session);

        // 触发学习
        learnFromSession(session);

        return true;
    }

    @Override
    @Transactional
    public boolean cancelConversation(
            String factoryId, ConversationActor actor, String sessionId) {
        Long userId = actor.userId();
        log.info("取消对话: session={}", sessionId);

        Optional<FactoryAssemblyConversationSession> optSession =
                sessionRepository.findBySessionIdAndFactoryIdAndUserIdAndActorType(
                        sessionId, factoryId, userId, actor.type());
        if (optSession.isEmpty()) {
            return false;
        }

        FactoryAssemblyConversationSession session = optSession.get();
        session.cancel();
        sessionRepository.save(session);

        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FactoryAssemblyConversationSession> getActiveSession(
            String factoryId, ConversationActor actor) {
        return sessionRepository.findLatestActiveSession(
                factoryId, actor.userId(), actor.type());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FactoryAssemblyConversationSession> getSession(
            String factoryId, ConversationActor actor, String sessionId) {
        return sessionRepository.findBySessionIdAndFactoryIdAndUserIdAndActorType(
                sessionId, factoryId, actor.userId(), actor.type());
    }

    @Override
    @Transactional
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public int processExpiredSessions() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int count = sessionRepository.expireInactiveSessions(expireTime, LocalDateTime.now());
        if (count > 0) {
            log.info("处理过期会话: count={}", count);
        }
        return count;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationStatistics getStatistics(String factoryId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        ConversationStatistics stats = new ConversationStatistics();

        // 获取成功率数据
        List<Object[]> rateData = sessionRepository.getSuccessRate(factoryId, since);
        if (!rateData.isEmpty()) {
            Object[] row = rateData.get(0);
            long completed = ((Number) row[0]).longValue();
            long timeout = ((Number) row[1]).longValue();
            long cancelled = ((Number) row[2]).longValue();
            long maxRounds = ((Number) row[3]).longValue();
            long total = ((Number) row[4]).longValue();

            stats.setCompletedCount(completed);
            stats.setTimeoutCount(timeout);
            stats.setCancelledCount(cancelled);
            stats.setMaxRoundsCount(maxRounds);
            stats.setTotalSessions(total);
            stats.setSuccessRate(total > 0 ? (double) completed / total : 0);
        }

        // 获取平均轮次
        Double avgRounds = sessionRepository.getAverageRoundsForCompleted(factoryId, since);
        stats.setAverageRounds(avgRounds != null ? avgRounds : 0);

        // 活跃会话数
        stats.setActiveSessions(sessionRepository.countActiveByFactoryId(factoryId));

        return stats;
    }

    // ========== 私有方法 ==========

    /**
     * 获取可用意图列表
     */
    private List<AIIntentConfig> getAvailableIntents(String factoryId) {
        return intentConfigRepository.findActiveByFactoryIdWithPriority(factoryId);
    }

    /**
     * 调用 LLM 进行对话
     */
    private LlmConversationResult callLlmForConversation(FactoryAssemblyConversationSession session,
                                                          String userInput,
                                                          List<AIIntentConfig> availableIntents) {
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            log.warn("DashScope 不可用，使用默认响应");
            return generateDefaultResponse(availableIntents);
        }

        try {
            // R2: 按用户输入相关性过滤意图，减少 prompt 大小
            List<AIIntentConfig> relevantIntents = filterRelevantIntents(availableIntents, userInput, 25);

            // 构建系统提示词
            String systemPrompt = buildConversationSystemPrompt(relevantIntents, session);

            // 构建对话历史
            String conversationHistory = session.buildConversationHistory();
            String userMessage = conversationHistory.isEmpty()
                    ? userInput
                    : conversationHistory + "用户: " + userInput;

            // R2+R4: 使用 fastModel (flash) 并记录耗时
            long t0 = System.currentTimeMillis();
            String response = dashScopeClient.chatFast(systemPrompt, userMessage);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("Conv LLM 调用: model=flash, intents={}/{}, promptLen={}, elapsed={}ms",
                    relevantIntents.size(), availableIntents.size(),
                    systemPrompt.length(), elapsed);

            // 解析响应
            return parseLlmResponse(response, availableIntents);

        } catch (Exception e) {
            log.error("LLM 对话调用失败: {}", e.getMessage(), e);
            return generateDefaultResponse(availableIntents);
        }
    }

    /**
     * R2: 按关键词相关性过滤意图，减少 prompt token 数
     * 从全量意图中筛选与用户输入最相关的 top-N 个
     */
    private List<AIIntentConfig> filterRelevantIntents(List<AIIntentConfig> allIntents,
                                                        String userInput, int topN) {
        if (allIntents.size() <= topN) {
            return allIntents;
        }

        String inputLower = userInput.toLowerCase();

        // 按关键词命中数排序
        List<Map.Entry<AIIntentConfig, Integer>> scored = new ArrayList<>();
        for (AIIntentConfig intent : allIntents) {
            int score = 0;
            String code = intent.getIntentCode() != null ? intent.getIntentCode().toLowerCase() : "";
            String name = intent.getIntentName() != null ? intent.getIntentName() : "";
            String desc = intent.getDescription() != null ? intent.getDescription() : "";
            String keywords = intent.getKeywords() != null ? intent.getKeywords() : "";
            String combined = name + " " + desc + " " + keywords;

            // 意图名称中的每个字在用户输入中出现 → +2
            for (char c : name.toCharArray()) {
                if (c != ' ' && inputLower.indexOf(c) >= 0) {
                    score += 2;
                }
            }
            // 关键词匹配 → +3
            if (!keywords.isEmpty()) {
                for (String kw : keywords.split("[,，;；\\s]+")) {
                    if (!kw.isEmpty() && inputLower.contains(kw.toLowerCase())) {
                        score += 3;
                    }
                }
            }
            // 描述中的关键字匹配 → +1
            for (String word : inputLower.split("\\s+")) {
                if (word.length() >= 2 && combined.toLowerCase().contains(word)) {
                    score++;
                }
            }
            scored.add(new AbstractMap.SimpleEntry<>(intent, score));
        }

        // 按分数降序，取 top-N
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return scored.stream()
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 构建对话系统提示词
     */
    private String buildConversationSystemPrompt(List<AIIntentConfig> availableIntents,
                                                  FactoryAssemblyConversationSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别助手，正在进行多轮对话来澄清用户的真实意图。\n\n");

        sb.append("## 当前对话状态\n");
        sb.append("- 轮次: ").append(session.getCurrentRound()).append("/").append(session.getMaxRounds()).append("\n");
        sb.append("- 原始输入: ").append(session.getOriginalInput()).append("\n\n");

        sb.append("## 可用意图列表\n\n");
        for (AIIntentConfig intent : availableIntents) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    intent.getIntentCode(),
                    intent.getIntentName(),
                    intent.getDescription() != null ? intent.getDescription() : ""));
        }

        sb.append("\n## 你的任务\n\n");
        sb.append("1. 分析用户的输入和对话历史\n");
        sb.append("2. 如果能确定用户意图（置信度 >= 0.7），返回确认结果\n");
        sb.append("3. 如果不确定，生成一个澄清问题来帮助确定意图\n");
        sb.append("4. 澄清问题应该简洁明了，并提供选项供用户选择\n\n");

        sb.append("## 输出格式 (JSON)\n\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"is_confirmed\": true/false,\n");
        sb.append("  \"intent_code\": \"意图代码 (如果确定)\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"message\": \"给用户的回复 (澄清问题或确认消息)\",\n");
        sb.append("  \"candidates\": [\n");
        sb.append("    {\"intent_code\": \"...\", \"intent_name\": \"...\", \"confidence\": 0.5}\n");
        sb.append("  ],\n");
        sb.append("  \"reasoning\": \"判断理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。");

        return sb.toString();
    }

    /**
     * 解析 LLM 响应
     */
    private LlmConversationResult parseLlmResponse(String response, List<AIIntentConfig> availableIntents) {
        try {
            // 提取 JSON
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(response);

            if (!matcher.find()) {
                log.warn("无法从 LLM 响应中提取 JSON: {}", truncate(response, 100));
                return generateDefaultResponse(availableIntents);
            }

            JsonNode json = objectMapper.readTree(matcher.group());

            boolean isConfirmed = json.has("is_confirmed") && json.get("is_confirmed").asBoolean();
            String intentCode = json.has("intent_code") ? json.get("intent_code").asText() : null;
            double confidence = json.has("confidence") ? json.get("confidence").asDouble() : 0.5;
            String message = json.has("message") ? json.get("message").asText() : "请问您想要执行什么操作？";
            String reasoning = json.has("reasoning") ? json.get("reasoning").asText() : null;

            // Clamp confidence
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            // 解析候选
            List<CandidateIntent> candidates = new ArrayList<>();
            if (json.has("candidates") && json.get("candidates").isArray()) {
                for (JsonNode candNode : json.get("candidates")) {
                    String code = candNode.has("intent_code") ? candNode.get("intent_code").asText() : null;
                    String name = candNode.has("intent_name") ? candNode.get("intent_name").asText() : code;
                    double candConf = candNode.has("confidence") ? candNode.get("confidence").asDouble() : 0.3;

                    if (code != null && !code.isEmpty()) {
                        candidates.add(CandidateIntent.builder()
                                .intentCode(code)
                                .intentName(name)
                                .confidence(candConf)
                                .build());
                    }
                }
            }

            // 验证意图代码
            if (isConfirmed && intentCode != null) {
                boolean validIntent = availableIntents.stream()
                        .anyMatch(i -> intentCode.equalsIgnoreCase(i.getIntentCode()));
                if (!validIntent) {
                    log.warn("LLM 返回无效意图代码: {}", intentCode);
                    isConfirmed = false;
                }
            }

            return LlmConversationResult.builder()
                    .intentConfirmed(isConfirmed)
                    .intentCode(intentCode)
                    .confidence(confidence)
                    .message(message)
                    .candidates(candidates)
                    .reasoning(reasoning)
                    .build();

        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", e.getMessage(), e);
            return generateDefaultResponse(availableIntents);
        }
    }

    /**
     * 生成默认响应
     */
    private LlmConversationResult generateDefaultResponse(List<AIIntentConfig> availableIntents) {
        List<CandidateIntent> candidates = availableIntents.stream()
                .limit(5)
                .map(i -> CandidateIntent.builder()
                        .intentCode(i.getIntentCode())
                        .intentName(i.getIntentName())
                        .confidence(0.3)
                        .build())
                .collect(Collectors.toList());

        StringBuilder message = new StringBuilder("请问您想要执行以下哪个操作？\n");
        for (int i = 0; i < Math.min(candidates.size(), 5); i++) {
            CandidateIntent c = candidates.get(i);
            message.append(String.format("%d. %s\n", i + 1, c.getIntentName()));
        }
        message.append("\n请输入数字或描述您的需求。");

        return LlmConversationResult.builder()
                .intentConfirmed(false)
                .confidence(0.3)
                .message(message.toString())
                .candidates(candidates)
                .build();
    }

    /**
     * 检查用户是否直接确认了某个候选意图
     */
    private String checkDirectConfirmation(String userReply, List<CandidateIntent> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        String reply = userReply.trim().toLowerCase();

        // 检查数字选择 (1, 2, 3...)
        if (reply.matches("\\d+")) {
            int index = Integer.parseInt(reply) - 1;
            if (index >= 0 && index < candidates.size()) {
                return candidates.get(index).getIntentCode();
            }
        }

        // 检查意图名称匹配
        for (CandidateIntent candidate : candidates) {
            if (reply.contains(candidate.getIntentName().toLowerCase()) ||
                reply.contains(candidate.getIntentCode().toLowerCase())) {
                return candidate.getIntentCode();
            }
        }

        // 检查确认词
        if (reply.matches(".*(是的?|对|确认|好的?|没错|正确).*") && candidates.size() == 1) {
            return candidates.get(0).getIntentCode();
        }

        return null;
    }

    /**
     * 从会话中学习
     */
    private void learnFromSession(FactoryAssemblyConversationSession session) {
        if (learningService == null) {
            log.debug("学习服务不可用，跳过学习");
            return;
        }

        String intentCode = session.getFinalIntentCode();
        if (intentCode == null || intentCode.isEmpty()) {
            return;
        }

        try {
            // 学习原始表达
            String originalInput = session.getOriginalInput();
            learningService.learnExpression(
                    session.getFactoryId(),
                    intentCode,
                    originalInput,
                    session.getLastConfidence() != null ? session.getLastConfidence() : 0.8,
                    LearnedExpression.SourceType.USER_FEEDBACK
            );

            log.info("从多轮对话学习表达: session={}, intent={}, expr={}",
                    session.getSessionId(), intentCode, truncate(originalInput, 50));

            // 提取并学习关键词 (可选)
            // 这里可以添加更复杂的关键词提取逻辑

        } catch (Exception e) {
            log.error("从会话学习失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建响应
     */
    private ConversationResponse buildResponse(FactoryAssemblyConversationSession session, LlmConversationResult llmResult) {
        List<CandidateInfo> candidateInfos = llmResult.getCandidates().stream()
                .map(c -> CandidateInfo.builder()
                        .intentCode(c.getIntentCode())
                        .intentName(c.getIntentName())
                        .confidence(c.getConfidence())
                        .build())
                .collect(Collectors.toList());

        String intentName = null;
        if (llmResult.isIntentConfirmed() && llmResult.getIntentCode() != null) {
            intentName = intentConfigRepository.findByIntentCode(llmResult.getIntentCode())
                    .map(AIIntentConfig::getIntentName)
                    .orElse(llmResult.getIntentCode());
        }

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(session.getStatus() == SessionStatus.COMPLETED)
                .message(llmResult.getMessage())
                .intentCode(llmResult.isIntentConfirmed() ? llmResult.getIntentCode() : null)
                .intentName(intentName)
                .confidence(llmResult.getConfidence())
                .candidates(candidateInfos)
                .requiresConfirmation(!llmResult.isIntentConfirmed() ||
                        llmResult.getConfidence() < confidenceThreshold)
                .build();
    }

    /**
     * 构建确认响应
     */
    private ConversationResponse buildConfirmedResponse(FactoryAssemblyConversationSession session, String intentCode) {
        String intentName = intentConfigRepository.findByIntentCode(intentCode)
                .map(AIIntentConfig::getIntentName)
                .orElse(intentCode);

        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(SessionStatus.COMPLETED)
                .completed(true)
                .message(String.format("已确认您的意图: %s", intentName))
                .intentCode(intentCode)
                .intentName(intentName)
                .confidence(0.95)
                .requiresConfirmation(false)
                .build();
    }

    /**
     * 获取状态消息
     */
    private String getStatusMessage(SessionStatus status) {
        switch (status) {
            case COMPLETED:
                return "对话已完成";
            case TIMEOUT:
                return "对话已超时";
            case CANCELLED:
                return "对话已取消";
            case MAX_ROUNDS_REACHED:
                return "对话已达最大轮次";
            default:
                return "对话状态异常";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ========== 内部类 ==========

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class LlmConversationResult {
        private boolean intentConfirmed;
        private String intentCode;
        private double confidence;
        private String message;
        private List<CandidateIntent> candidates;
        private String reasoning;
    }

    /**
     * LLM 参数提取结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class LlmParameterExtractionResult {
        /** 提取到的参数 (参数名 -> 参数值) */
        private Map<String, String> extractedParams;
        /** LLM 推理过程 */
        private String reasoning;
        /** 是否成功提取 */
        private boolean successful;
        /** 错误消息 (如果失败) */
        private String errorMessage;
        /** Typed result consumed by the fail-closed reply path. */
        private ExtractionOutcome outcome;
        /** Explicit fields to clear for a correction. */
        private List<String> clearParams;
        /** Sanitized provider observability. */
        private ProviderAttemptMetadata providerAttempt;
        /** Contract field names that failed validation. */
        private List<String> invalidFields;
    }

    private record ExtractionValidation(
            boolean valid,
            ExtractionOutcome outcome,
            Map<String, String> values,
            List<String> clearFields,
            List<String> invalidFields) {
    }

    private record SessionMutationSnapshot(
            String requiredParametersJson,
            String collectedParametersJson,
            String messagesJson,
            Integer currentRound,
            Integer maxRounds,
            SessionStatus status,
            LocalDateTime completedAt,
            String finalIntentCode,
            Double lastConfidence,
            LocalDateTime lastActiveAt) {
    }

    // ========== 参数收集辅助方法 ==========

    /**
     * 构建参数收集初始消息
     *
     * @param intentName 意图名称
     * @param requiredParameters 需要收集的参数列表
     * @param clarificationQuestions Handler 提供的澄清问题
     * @return 初始收集消息
     */
    private String buildParameterCollectionMessage(
            String intentName,
            List<RequiredParameter> requiredParameters,
            List<String> clarificationQuestions) {

        StringBuilder sb = new StringBuilder();
        sb.append("好的，我来帮您执行「").append(intentName != null ? intentName : "操作").append("」。\n\n");

        // 如果 Handler 提供了澄清问题，直接使用
        if (clarificationQuestions != null && !clarificationQuestions.isEmpty()) {
            sb.append("需要您提供以下信息：\n");
            for (String question : clarificationQuestions) {
                sb.append("• ").append(question).append("\n");
            }
        } else if (requiredParameters != null && !requiredParameters.isEmpty()) {
            // 否则根据参数列表生成问题
            sb.append("请提供以下必需信息：\n");
            for (RequiredParameter param : requiredParameters) {
                String label = param.getLabel() != null ? param.getLabel() : param.getName();
                sb.append("• ").append(label);
                if (param.getValidationHint() != null && !param.getValidationHint().isEmpty()) {
                    sb.append(" (").append(param.getValidationHint()).append(")");
                }
                sb.append("\n");
            }
        }

        sb.append("\n请直接告诉我，我会帮您完成操作。");
        return sb.toString();
    }

    private LlmParameterExtractionResult extractParametersTyped(
            FactoryAssemblyConversationSession session,
            String userReply,
            List<FactoryAssemblyConversationSession.RequiredParameter> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return LlmParameterExtractionResult.builder()
                    .outcome(ExtractionOutcome.SUCCESS)
                    .successful(true)
                    .extractedParams(new LinkedHashMap<>())
                    .clearParams(new ArrayList<>())
                    .invalidFields(new ArrayList<>())
                    .build();
        }

        String attemptId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            return LlmParameterExtractionResult.builder()
                    .outcome(ExtractionOutcome.PROVIDER_UNAVAILABLE)
                    .successful(false)
                    .extractedParams(new LinkedHashMap<>())
                    .clearParams(new ArrayList<>())
                    .invalidFields(new ArrayList<>())
                    .providerAttempt(ProviderAttemptMetadata.builder()
                            .attemptId(attemptId)
                            .provider("python-llm-router")
                            .latencyMs(elapsedMillis(started))
                            .attemptCount(0)
                            .failureCategory("PROVIDER_UNAVAILABLE")
                            .retryable(true)
                            .build())
                    .build();
        }

        try {
            String prompt = buildParameterExtractionPrompt(candidates, session);
            var call = dashScopeClient.chatCompletion(com.cretas.aims.ai.dto.ChatCompletionRequest.builder()
                    .messages(List.of(com.cretas.aims.ai.dto.ChatMessage.system(prompt),
                            com.cretas.aims.ai.dto.ChatMessage.user("用户输入: " + userReply)))
                    .temperature(0.1).enableThinking(false).build());
            if (call == null || call.hasError() || call.getContent() == null || call.getContent().isBlank()) {
                throw new IllegalStateException("provider returned no extraction result");
            }
            ProviderAttemptMetadata metadata = ProviderAttemptMetadata.builder()
                    .attemptId(attemptId)
                    .provider("python-llm-router")
                    .model(call.getModel())
                    .latencyMs(elapsedMillis(started))
                    .attemptCount(1)
                    .retryable(false)
                    .build();
            LlmParameterExtractionResult parsed =
                    parseParameterExtractionResponseTyped(call.getContent(), candidates);
            parsed.setProviderAttempt(metadata);
            return parsed;
        } catch (RuntimeException e) {
            String failureCategory = providerFailureCategory(e);
            log.warn("LLM 参数提取失败闭合: session={}, attempt={}, category={}",
                    session.getSessionId(), attemptId, failureCategory);
            return LlmParameterExtractionResult.builder()
                    .outcome(ExtractionOutcome.PROVIDER_UNAVAILABLE)
                    .successful(false)
                    .extractedParams(new LinkedHashMap<>())
                    .clearParams(new ArrayList<>())
                    .invalidFields(new ArrayList<>())
                    .providerAttempt(ProviderAttemptMetadata.builder()
                            .attemptId(attemptId)
                            .provider("python-llm-router")
                            .latencyMs(elapsedMillis(started))
                            .attemptCount("TIMEOUT".equals(failureCategory) ? 2 : 1)
                            .failureCategory(failureCategory)
                            .retryable(true)
                            .build())
                    .build();
        }
    }

    private LlmParameterExtractionResult parseParameterExtractionResponseTyped(
            String response,
            List<FactoryAssemblyConversationSession.RequiredParameter> expectedParams) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> clears = new ArrayList<>();
        LinkedHashSet<String> invalid = new LinkedHashSet<>();
        Set<String> expectedNames = expectedParams.stream()
                .map(FactoryAssemblyConversationSession.RequiredParameter::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            if (response == null || response.isBlank()) {
                throw new IllegalArgumentException("empty provider response");
            }
            JsonNode root = objectMapper.readTree(normalizeProviderJsonResponse(response));
            if (!root.isObject()) {
                throw new IllegalArgumentException("provider response root must be an object");
            }
            Set<String> allowedRootFields = Set.of("outcome", "extracted", "clear", "reasoning");
            root.fieldNames().forEachRemaining(name -> {
                if (!allowedRootFields.contains(name)) {
                    invalid.add(name);
                }
            });
            JsonNode extracted = root.get("extracted");
            if (extracted == null || !extracted.isObject()) {
                throw new IllegalArgumentException("extracted must be an object");
            }
            extracted.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode node = entry.getValue();
                if (!expectedNames.contains(name)) {
                    invalid.add(name);
                } else if (!node.isNull()) {
                    if (!node.isValueNode()) {
                        invalid.add(name);
                    } else {
                        String value = node.asText();
                        if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                            values.put(name, value.trim());
                        }
                    }
                }
            });

            JsonNode clear = root.get("clear");
            if (clear != null && !clear.isNull()) {
                if (!clear.isArray()) {
                    invalid.add("clear");
                } else {
                    clear.forEach(node -> {
                        if (!node.isTextual() || !expectedNames.contains(node.asText())) {
                            invalid.add(node.isTextual() ? node.asText() : "clear");
                        } else {
                            clears.add(node.asText());
                        }
                    });
                }
            }
            JsonNode reasoning = root.get("reasoning");
            if (reasoning != null && !reasoning.isNull() && !reasoning.isTextual()) {
                invalid.add("reasoning");
            }

            JsonNode outcomeNode = root.get("outcome");
            if (outcomeNode == null || !outcomeNode.isTextual()) {
                throw new IllegalArgumentException("outcome must be a typed string");
            }
            String declared = outcomeNode.asText().trim().toUpperCase(Locale.ROOT);
            ExtractionOutcome outcome;
            if ("SUCCESS".equals(declared)) {
                outcome = ExtractionOutcome.SUCCESS;
            } else if ("UNKNOWN".equals(declared)) {
                outcome = ExtractionOutcome.UNKNOWN;
            } else if ("CONFLICT".equals(declared)) {
                outcome = ExtractionOutcome.CONFLICT;
            } else {
                outcome = ExtractionOutcome.INVALID_EXTRACTION;
            }
            if (outcome != ExtractionOutcome.SUCCESS && (!values.isEmpty() || !clears.isEmpty())) {
                outcome = ExtractionOutcome.CONFLICT;
                invalid.addAll(values.keySet());
                invalid.addAll(clears);
            }
            if (outcome == ExtractionOutcome.SUCCESS
                    && values.isEmpty() && clears.isEmpty() && !invalid.isEmpty()) {
                outcome = ExtractionOutcome.INVALID_EXTRACTION;
            }
            return LlmParameterExtractionResult.builder()
                    .outcome(outcome)
                    .successful(outcome == ExtractionOutcome.SUCCESS)
                    .extractedParams(values)
                    .clearParams(clears)
                    .invalidFields(new ArrayList<>(invalid))
                    .reasoning(root.path("reasoning").asText(null))
                    .build();
        } catch (Exception e) {
            log.warn("参数抽取响应不符合 JSON 契约: {}", e.getClass().getSimpleName());
            return LlmParameterExtractionResult.builder()
                    .outcome(ExtractionOutcome.INVALID_EXTRACTION)
                    .successful(false)
                    .extractedParams(values)
                    .clearParams(clears)
                    .invalidFields(new ArrayList<>(invalid))
                    .errorMessage("provider response violated extraction contract")
                    .build();
        }
    }

    private String normalizeProviderJsonResponse(String response) {
        String normalized = response.strip();
        if (!normalized.startsWith("```")) {
            return normalized;
        }

        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0 || !normalized.endsWith("```")) {
            throw new IllegalArgumentException("incomplete provider JSON fence");
        }
        String openingFence = normalized.substring(0, firstLineEnd).strip();
        if (!("```".equals(openingFence) || "```json".equalsIgnoreCase(openingFence))) {
            throw new IllegalArgumentException("unsupported provider JSON fence");
        }

        String fencedPayload = normalized.substring(firstLineEnd + 1, normalized.length() - 3).strip();
        if (fencedPayload.isBlank() || fencedPayload.contains("```")) {
            throw new IllegalArgumentException("provider response must contain exactly one JSON fence");
        }
        return fencedPayload;
    }

    private ExtractionValidation validateExtraction(
            LlmParameterExtractionResult extraction,
            List<FactoryAssemblyConversationSession.RequiredParameter> expectedParams,
            FactoryAssemblyConversationSession session,
            String userReply,
            String askedThisRound) {
        if (extraction.getOutcome() != ExtractionOutcome.SUCCESS) {
            return new ExtractionValidation(
                    false, extraction.getOutcome(), Map.of(), List.of(),
                    safeList(extraction.getInvalidFields()));
        }
        Map<String, FactoryAssemblyConversationSession.RequiredParameter> expected = expectedParams.stream()
                .filter(param -> param.getName() != null)
                .collect(Collectors.toMap(
                        FactoryAssemblyConversationSession.RequiredParameter::getName,
                        param -> param,
                        (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<String> invalid = new LinkedHashSet<>(safeList(extraction.getInvalidFields()));
        Map<String, String> raw = new LinkedHashMap<>(
                extraction.getExtractedParams() == null ? Map.of() : extraction.getExtractedParams());
        Map<String, String> filtered = dropCrossRoundProductNameDuplicates(
                dropSameRoundDuplicates(dropWholeReplyStuffing(raw, userReply), askedThisRound), session);
        boolean conflictDetected = false;
        if (!filtered.equals(raw)) {
            raw.keySet().stream().filter(key -> !filtered.containsKey(key)).forEach(invalid::add);
            conflictDetected = true;
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : filtered.entrySet()) {
            FactoryAssemblyConversationSession.RequiredParameter param = expected.get(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            String canonical = param == null ? null : canonicalParameterValue(param, value);
            if (param == null || value.isEmpty()
                    || value.length() > MAX_EXTRACTED_VALUE_LENGTH || canonical == null) {
                invalid.add(entry.getKey());
            } else {
                String oldValue = session.getCollectedParameters().get(entry.getKey());
                if (oldValue != null
                        && !Objects.equals(oldValue, canonical)
                        && isExpandedCountDriver(session, entry.getKey())
                        && !looksLikeCorrection(userReply)) {
                    invalid.add(entry.getKey());
                    conflictDetected = true;
                    continue;
                }
                normalized.put(entry.getKey(), canonical);
            }
        }

        List<String> clears = extraction.getClearParams() == null
                ? List.of() : extraction.getClearParams();
        List<String> validClears = new ArrayList<>();
        for (String clear : clears) {
            if (!looksLikeCorrection(userReply)
                    || !expected.containsKey(clear)
                    || normalized.containsKey(clear)
                    || !session.getCollectedParameters().containsKey(clear)
                    || isExpandedCountDriver(session, clear)) {
                invalid.add(clear);
                conflictDetected = true;
            } else {
                validClears.add(clear);
            }
        }
        if (normalized.isEmpty() && validClears.isEmpty()) {
            return new ExtractionValidation(
                    false,
                    conflictDetected ? ExtractionOutcome.CONFLICT : ExtractionOutcome.INVALID_EXTRACTION,
                    Map.of(), List.of(), new ArrayList<>(invalid));
        }
        ExtractionOutcome outcome = invalid.isEmpty()
                ? ExtractionOutcome.SUCCESS : ExtractionOutcome.PARTIAL_SUCCESS;
        return new ExtractionValidation(
                true, outcome, normalized, validClears, new ArrayList<>(invalid));
    }

    private String canonicalParameterValue(
            FactoryAssemblyConversationSession.RequiredParameter param, String value) {
        if (param == null || value == null || !isValidParameterValue(param, value)) {
            return null;
        }
        if (FactoryInterviewExpansion.STEP_COUNT_KEY.equals(param.getName())) {
            if (!value.matches("\\d+")) {
                return null;
            }
            try {
                int count = Integer.parseInt(value);
                if (count < 1 || count > FactoryInterviewExpansion.MAX_STEPS) {
                    return null;
                }
                return String.valueOf(count);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        List<String> options = param.getOptions();
        if (options == null || options.isEmpty()) {
            return value;
        }
        if (!param.isMultiSelect()) {
            String normalized = normalizeChoiceToken(value);
            return options.stream()
                    .filter(option -> normalizeChoiceToken(option).equals(normalized))
                    .findFirst()
                    .orElse(null);
        }
        List<String> selected = Arrays.stream(value.split("[、,，;；]"))
                .map(String::trim).filter(part -> !part.isEmpty()).toList();
        if (selected.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String token : selected) {
            String normalized = normalizeChoiceToken(token);
            String option = options.stream()
                    .filter(candidate -> normalizeChoiceToken(candidate).equals(normalized))
                    .findFirst().orElse(null);
            if (option == null) {
                return null;
            }
            if (!canonical.contains(option)) {
                canonical.add(option);
            }
        }
        return String.join("、", canonical);
    }

    private String normalizeChoiceToken(String value) {
        return value == null ? "" : value.trim()
                .replace('（', '(').replace('）', ')')
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isStandaloneChoiceReply(String reply, String exactChoice) {
        if (reply == null || exactChoice == null) {
            return false;
        }
        String normalized = reply.trim().replaceAll("[。.!！?？]+$", "");
        if (normalizeChoiceToken(normalized).equals(normalizeChoiceToken(exactChoice))) {
            return true;
        }
        return normalizeChoiceToken(normalized.replaceAll("[个道项种款条份]$", "").trim())
                .equals(normalizeChoiceToken(exactChoice));
    }

    private boolean isExpandedCountDriver(FactoryAssemblyConversationSession session, String name) {
        return ("stepCount".equals(name) && session.isRepeatExpanded("step"))
                || ("productCount".equals(name) && session.isRepeatExpanded("product"));
    }

    private boolean isExplicitUnknownReply(String reply) {
        if (reply == null) {
            return false;
        }
        String normalized = reply.trim().replaceAll("[。.!！?？]+$", "");
        return normalized.matches("(?i)不知道|不清楚|还没定|尚未确定|暂未确定|你看着办|not sure|unknown|i don't know");
    }

    private boolean looksLikeCorrection(String reply) {
        if (reply == null) {
            return false;
        }
        String lower = reply.toLowerCase(Locale.ROOT);
        return lower.contains("改成") || lower.contains("改为") || lower.contains("更正")
                || lower.contains("纠正") || lower.contains("清空") || lower.contains("删掉")
                || lower.contains("去掉") || lower.contains("不是") || lower.contains("change ")
                || lower.contains("correct ") || lower.contains("clear ") || lower.contains("remove ");
    }

    private String extractionFailureMessage(ExtractionOutcome outcome) {
        return switch (outcome == null ? ExtractionOutcome.INVALID_EXTRACTION : outcome) {
            case PROVIDER_UNAVAILABLE -> "AI 提取服务暂时不可用，这句话没有保存。请稍后重试。";
            case INVALID_EXTRACTION -> "我没能安全解析这句话，这句话没有保存。请换一种明确说法后重试。";
            case UNKNOWN -> "好的，这一项仍保持未回答。你可以稍后补充，或使用页面提供的明确选项。";
            case CONFLICT -> "这句话与当前答案或会话状态有冲突，没有写入。请核对后重试。";
            case SUCCESS, PARTIAL_SUCCESS -> "已处理。";
        };
    }

    private List<FieldReceipt> fieldReceipts(ExtractionValidation validation) {
        List<FieldReceipt> receipts = new ArrayList<>();
        validation.values().forEach((field, value) -> receipts.add(FieldReceipt.builder()
                .field(field)
                .resolution(FieldResolution.ACCEPTED)
                .canonicalValue(value)
                .reasonCode("VALIDATED")
                .build()));
        validation.clearFields().forEach(field -> receipts.add(FieldReceipt.builder()
                .field(field)
                .resolution(FieldResolution.ACCEPTED)
                .canonicalValue(null)
                .reasonCode("CLEARED")
                .build()));
        validation.invalidFields().forEach(field -> receipts.add(FieldReceipt.builder()
                .field(field)
                .resolution(FieldResolution.REJECTED)
                .reasonCode("FIELD_VALIDATION_FAILED")
                .build()));
        return receipts;
    }

    private String parameterLabel(FactoryAssemblyConversationSession session, String field) {
        if (session != null && session.getRequiredParameters() != null) {
            for (FactoryAssemblyConversationSession.RequiredParameter parameter : session.getRequiredParameters()) {
                if (Objects.equals(field, parameter.getName())
                        && parameter.getLabel() != null && !parameter.getLabel().isBlank()) {
                    return parameter.getLabel();
                }
            }
        }
        return "无法识别的输入字段";
    }

    private List<FieldReceipt> failureFieldReceipts(
            ExtractionOutcome outcome, List<String> fields) {
        FieldResolution resolution = switch (outcome) {
            case UNKNOWN -> FieldResolution.UNKNOWN;
            case CONFLICT -> FieldResolution.CONFLICT;
            default -> FieldResolution.REJECTED;
        };
        String reason = switch (resolution) {
            case UNKNOWN -> "USER_UNKNOWN";
            case CONFLICT -> "CONFLICT";
            default -> "FIELD_VALIDATION_FAILED";
        };
        return safeList(fields).stream().map(field -> FieldReceipt.builder()
                .field(field)
                .resolution(resolution)
                .reasonCode(reason)
                .build()).toList();
    }

    private ConversationResponse extractionFailureResponse(
            FactoryAssemblyConversationSession session,
            String requestId,
            ExtractionOutcome outcome,
            ProviderAttemptMetadata providerAttempt,
            String message,
            List<String> invalidFields) {
        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus())
                .completed(false)
                .message(message)
                .intentCode(session.getKnownIntentCode())
                .requiresConfirmation(true)
                .extractionOutcome(outcome)
                .providerAttempt(providerAttempt)
                .requestId(requestId)
                .progressMutated(false)
                .correctionApplied(false)
                .invalidFields(invalidFields == null ? List.of() : List.copyOf(invalidFields))
                .fieldReceipts(failureFieldReceipts(outcome, invalidFields))
                .build();
    }

    private SessionMutationSnapshot snapshot(FactoryAssemblyConversationSession session) {
        return new SessionMutationSnapshot(
                session.getRequiredParametersJson(),
                session.getCollectedParametersJson(),
                session.getMessagesJson(),
                session.getCurrentRound(),
                session.getMaxRounds(),
                session.getStatus(),
                session.getCompletedAt(),
                session.getFinalIntentCode(),
                session.getLastConfidence(),
                session.getLastActiveAt());
    }

    private void restore(FactoryAssemblyConversationSession session, SessionMutationSnapshot snapshot) {
        session.setRequiredParametersJson(snapshot.requiredParametersJson());
        session.setCollectedParametersJson(snapshot.collectedParametersJson());
        session.setMessagesJson(snapshot.messagesJson());
        session.setCurrentRound(snapshot.currentRound());
        session.setMaxRounds(snapshot.maxRounds());
        session.setStatus(snapshot.status());
        session.setCompletedAt(snapshot.completedAt());
        session.setFinalIntentCode(snapshot.finalIntentCode());
        session.setLastConfidence(snapshot.lastConfidence());
        session.setLastActiveAt(snapshot.lastActiveAt());
    }

    private String replyFingerprint(String userReply) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userReply == null ? "" : userReply.strip()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private String providerFailureCategory(RuntimeException error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out") || message.contains("超时")) {
            return "TIMEOUT";
        }
        if (message.matches(".*\\b(500|502|503|504)\\b.*") || message.contains("5xx")) {
            return "HTTP_5XX";
        }
        if (message.contains("quota") || message.contains("rate limit") || message.contains("429")) {
            return "PROVIDER_QUOTA";
        }
        return "PROVIDER_ERROR";
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private FactoryAssemblyConversationSession.ReplyReceipt receiptFromResponse(ConversationResponse response) {
        return FactoryAssemblyConversationSession.ReplyReceipt.builder()
                .extractionOutcome(response.getExtractionOutcome().name())
                .currentRound(response.getCurrentRound())
                .maxRounds(response.getMaxRounds())
                .status(response.getStatus().name())
                .completed(response.isCompleted())
                .message(response.getMessage())
                .intentCode(response.getIntentCode())
                .intentName(response.getIntentName())
                .confidence(response.getConfidence())
                .requiresConfirmation(response.isRequiresConfirmation())
                .requestId(response.getRequestId())
                .progressMutated(response.isProgressMutated())
                .correctionApplied(response.isCorrectionApplied())
                .providerAttempt(providerReceipt(response.getProviderAttempt()))
                .invalidFields(safeList(response.getInvalidFields()))
                .fieldReceipts(safeList(response.getFieldReceipts()).stream()
                        .map(field -> FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                                .field(field.getField())
                                .resolution(field.getResolution().name())
                                .canonicalValue(field.getCanonicalValue())
                                .reasonCode(field.getReasonCode())
                                .build())
                        .toList())
                .build();
    }

    private FactoryAssemblyConversationSession.ProviderAttemptReceipt providerReceipt(ProviderAttemptMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return FactoryAssemblyConversationSession.ProviderAttemptReceipt.builder()
                .attemptId(metadata.getAttemptId())
                .provider(metadata.getProvider())
                .model(metadata.getModel())
                .latencyMs(metadata.getLatencyMs())
                .attemptCount(metadata.getAttemptCount())
                .failureCategory(metadata.getFailureCategory())
                .retryable(metadata.isRetryable())
                .build();
    }

    private ConversationResponse responseFromReceipt(
            FactoryAssemblyConversationSession session, FactoryAssemblyConversationSession.ReplyReceipt receipt) {
        ProviderAttemptMetadata provider = receipt.getProviderAttempt() == null ? null
                : ProviderAttemptMetadata.builder()
                        .attemptId(receipt.getProviderAttempt().getAttemptId())
                        .provider(receipt.getProviderAttempt().getProvider())
                        .model(receipt.getProviderAttempt().getModel())
                        .latencyMs(receipt.getProviderAttempt().getLatencyMs())
                        .attemptCount(receipt.getProviderAttempt().getAttemptCount())
                        .failureCategory(receipt.getProviderAttempt().getFailureCategory())
                        .retryable(receipt.getProviderAttempt().isRetryable())
                        .build();
        return ConversationResponse.builder()
                .sessionId(session.getSessionId())
                .currentRound(receipt.getCurrentRound())
                .maxRounds(receipt.getMaxRounds())
                .status(SessionStatus.valueOf(receipt.getStatus()))
                .completed(receipt.isCompleted())
                .message(receipt.getMessage())
                .intentCode(receipt.getIntentCode())
                .intentName(receipt.getIntentName())
                .confidence(receipt.getConfidence())
                .requiresConfirmation(receipt.isRequiresConfirmation())
                .extractionOutcome(ExtractionOutcome.valueOf(receipt.getExtractionOutcome()))
                .providerAttempt(provider)
                .requestId(receipt.getRequestId())
                .idempotentReplay(true)
                .progressMutated(false)
                .correctionApplied(receipt.isCorrectionApplied())
                .invalidFields(safeList(receipt.getInvalidFields()))
                .fieldReceipts(safeList(receipt.getFieldReceipts()).stream()
                        .map(field -> FieldReceipt.builder()
                                .field(field.getField())
                                .resolution(FieldResolution.valueOf(field.getResolution()))
                                .canonicalValue(field.getCanonicalValue())
                                .reasonCode(field.getReasonCode())
                                .build())
                        .toList())
                .build();
    }

    /**
     * <b>同一轮</b>抽取里,同一个值不许写进多个参数 —— 客户这一句话只回答了一件事。
     *
     * <h2>被治的那个缺陷(2026-08-16 本地端到端实测)</h2>
     *
     * 一次真实建厂里,客户答「包材」那题说「铝箔袋、外箱」,模型把<b>同一个字符串</b>
     * 同时填给了 {@code step1.packMaterials} <b>和</b> {@code step1.name}。
     * 落库之后第一道工序的名字就叫「铝箔袋、外箱」——
     * ⚠️ 每一格单看都像话(那确实是客户说过的字),合起来是错的,而且一个报错都没有。
     *
     * <h2>⛔ 为什么不用「同一个值出现在多个槽」当判据</h2>
     *
     * 那是个<b>代理判据,两头都会错</b>。实测同一份会话里有 8 个槽共用「没有」、
     * 3 个槽共用「98%」—— 那些是<b>合法的</b>:客户对不同的题确实给了相同答案
     * (三道工序都不加辅料、得率都是 98%)。按同值判缺陷会把它们全判红。
     *
     * ⇒ 收窄成<b>确定性</b>的那一条:只看**这一轮**抽出来的结果。
     *   一轮里出现同值多槽 ⇒ 至多一个是对的 ⇒ 只留<b>本轮刚问的那一题</b>;
     *   刚问的那题不在其中就一个都不留(宁可重问, 也不要填错)。
     *
     * <h2>⚠️ 这条抓不住什么(说清楚)</h2>
     *
     * <b>跨轮</b>的串槽它看不见。同一次实测里 {@code metricWatches} 被填成了
     * 「库存不足、质检异常」(那是<b>告警</b>那题的答案), 而两题是在不同轮问的 ——
     * 这一条抓不到。那一类需要「答案形状对不对得上题目」的判断, 是另一件事。
     */
    /**
     * 丢掉「被塞了<b>整条原文</b>」的那个槽 —— 那不是抽取，是兜底。
     *
     * <h3>🔴 2026-08-17 实测(probe-interview-batch-answer.py, 真实端点)</h3>
     *
     * 一条 112 字的消息里说了 10 件事 —— <b>10 项全接住且值全对</b>(能力是有的)，
     * 而第 11 项是:
     *
     * <pre>
     * metricWatches = 「我一次说完：我们做宠物冻干零食，行业归类选其他。…」(整条原文)
     * </pre>
     *
     * 后果比「少接一项」严重: 那题从此标记为已收集、<b>再也不会问</b>，
     * 客户真正的指标答案永远收不到。而 {@code _metric_watches} 解析这段话得 0 条，
     * 于是它只会告诉客户「这几个要盯的指标我没有配上」—— 客户一头雾水。
     *
     * <h3>⛔ 为什么 dropSameRoundDuplicates 拦不住</h3>
     *
     * 那个值与别的槽都不同，只出现 1 次；同值去重要求 ≥2 次才拦。
     *
     * <h3>判据: 值 == 整条输入 **且** 这一轮抽出了 ≥2 个槽</h3>
     *
     * ⛔ 「≥2 个槽」这个条件不能省: 客户只答一句话、只填一个槽时，
     * 那一个槽的值<b>当然</b>就是整条原文 —— 丢了就等于收不到答案。
     * 只有在「同一轮还抽出了别的东西」时，一个等于全文的值才说明它是兜底塞的。
     *
     * ⚠️ 不针对某个槽: 历史日志里 {@code metricWatches} 被填成「产量、良品率」4 次、
     * 「不用」1 次，都正常。这次是它，下次可能是别的。
     */
    Map<String, String> dropWholeReplyStuffing(Map<String, String> extracted, String userReply) {
        if (extracted == null || extracted.isEmpty() || userReply == null) {
            return extracted == null ? new HashMap<>() : extracted;
        }
        String whole = userReply.replaceAll("[\\s　]+", "");
        if (whole.isEmpty()) {
            return extracted;
        }
        long filled = extracted.values().stream()
                .filter(v -> v != null && !v.trim().isEmpty()).count();
        if (filled < 2) {
            return extracted;          // ⛔ 只填了一个槽 —— 等于全文是正常的
        }
        Map<String, String> out = new HashMap<>(extracted);
        for (Map.Entry<String, String> e : extracted.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (e.getValue().replaceAll("[\\s　]+", "").equals(whole)) {
                out.remove(e.getKey());
                log.warn("「{}」被塞了整条原文({} 字) —— 那是兜底不是抽取, 丢掉; "
                                + "同一轮另抽出 {} 项",
                        e.getKey(), whole.length(), filled - 1);
            }
        }
        return out;
    }

    /**
     * 跨轮重名的成品名：<b>不收</b>，让它照常再问一次。
     *
     * <h3>被治的那个缺陷（2026-08-18 实测，同一套答案连跑 8 遍）</h3>
     *
     * 客户报了两个成品（卤鸭脖 / 卤牛腱），而会话里存下来的是：
     *
     * <pre>
     * 厂            成品个数   product1.name        product2.name
     * FOOD_3301_021    4       卤鸭脖               卤鸭脖        ← 两个槽同一个名字
     * FOOD_3301_022    2       卤鸭脖               卤鸭脖
     * FOOD_3301_025    4       卤鸭脖 200g/袋       卤牛腱 250g/袋 ← 只有这一遍是对的
     * </pre>
     *
     * 落库后 {@code product_types} 是 <b>1 / 2 / 1 / 2 / 4 / 4 / 1 / 2</b> ——
     * 重名的被去重成 1 个，而<b>八遍全部报 PASS</b>，客户一个字都没被告知。
     *
     * <h3>⛔ 为什么 {@code dropSameRoundDuplicates} 拦不住</h3>
     *
     * 那一道守的是<b>同一轮</b>抽取里一个值写进多个槽。而「第1个成品叫什么」和
     * 「第2个成品叫什么」是<b>两轮</b>问的 —— 第二轮的结果里只有一个键，
     * 同轮去重看不到它和上一轮撞了。
     *
     * <h3>⛔ 不是「自动改名」也不是「丢掉这个成品」</h3>
     *
     * 两者都是替客户做主。这里只是<b>不收下这个答案</b> ——
     * 于是既有的「追问有界 + 放弃必报」机制照常接手：再问一次，
     * 两次拿不到就跳过，并在「你说了但这几样没配上」里当面告诉他。
     *
     * <p>⚠️ 只管 {@code productN.name}：重名对它是<b>确定的错</b>
     * （一家厂不会有两个同名成品）。⛔ 别推广到别的槽 ——
     * 「第2道辅料」和「第3道辅料」是同一批料完全正常。
     */
    Map<String, String> dropCrossRoundProductNameDuplicates(
            Map<String, String> extracted, FactoryAssemblyConversationSession session) {
        if (extracted == null || extracted.isEmpty() || session == null) {
            return extracted == null ? new HashMap<>() : extracted;
        }
        Map<String, String> already = session.getCollectedParameters();
        if (already == null || already.isEmpty()) {
            return extracted;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : extracted.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            boolean clash = false;
            if (isProductNameKey(key) && val != null && !val.trim().isEmpty()) {
                for (Map.Entry<String, String> old : already.entrySet()) {
                    if (!old.getKey().equals(key) && isProductNameKey(old.getKey())
                            && val.trim().equals(String.valueOf(old.getValue()).trim())) {
                        clash = true;
                        log.warn("成品重名, 不收下这一答: {}={} 与 {} 撞名 —— 会再问一次",
                                key, val, old.getKey());
                        break;
                    }
                }
            }
            if (!clash) {
                out.put(key, val);
            }
        }
        return out;
    }

    /** {@code product1.name} / {@code product12.name} —— ⛔ 只认成品名这一个槽。 */
    private static boolean isProductNameKey(String key) {
        return key != null && key.startsWith("product") && key.endsWith(".name");
    }

    Map<String, String> dropSameRoundDuplicates(Map<String, String> extracted,
                                                        String askedThisRound) {
        if (extracted == null || extracted.size() < 2) {
            return extracted == null ? new HashMap<>() : extracted;
        }
        Map<String, List<String>> byValue = new HashMap<>();
        for (Map.Entry<String, String> e : extracted.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                byValue.computeIfAbsent(e.getValue().trim(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<String, String> out = new HashMap<>(extracted);
        for (Map.Entry<String, List<String>> e : byValue.entrySet()) {
            List<String> keys = e.getValue();
            if (keys.size() < 2) {
                continue;
            }
            String keep = keys.contains(askedThisRound) ? askedThisRound : null;
            for (String k : keys) {
                if (!k.equals(keep)) {
                    out.remove(k);
                }
            }
            log.warn("同一轮里「{}」被填进 {} 个槽 {} —— 只保留刚问的那个: {}",
                    truncate(e.getKey(), 30), keys.size(), keys,
                    keep == null ? "(都不留, 让它重问)" : keep);
        }
        return out;
    }

    /**
     * 构建参数提取提示词
     */
    String buildParameterExtractionPrompt(
            List<FactoryAssemblyConversationSession.RequiredParameter> params,
            FactoryAssemblyConversationSession session) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个参数提取助手。请从用户输入中提取以下参数值。\n\n");
        sb.append("## 意图: ").append(session.getKnownIntentCode()).append("\n\n");

        // 本轮刚问的那一项 —— 给出这个锚点, 抽取器才知道用户这句话**主要在回答什么**。
        //
        // ⛔ 必须问 session 要, **不能**用 params.get(0)。两者对「已跳过的项」看法相反:
        //      getNextPendingParameter  -> !collected && skipReason == null  (排除已跳过的)
        //      params(抽取候选)          -> !collected                       (**不**排除)
        //    形态 D:同一件事两份判据, 它一定会漂。
        //
        // 🔴 2026-08-16 本地端到端实测(F999, 真实端点, glm-4.5-air)逐字读数:
        //    只要有**任何一项**被跳过(noteAskFailed 到 MAX_ASK_ATTEMPTS), 它仍留在候选里
        //    且声明序靠前 ⇒ 锚点从此恒等于那个**已经放弃、界面上根本没在问**的题:
        //
        //      ## 本轮刚问的是: **metricWatches** (要盯哪些指标)   ← 界面问的是「第1道·工序名称」
        //      用户输入 "解冻"
        //      模型返回 {"extracted":{"metricWatches":null},
        //               "reasoning":"用户输入'解冻'是在提供一个工序名称,
        //                            而不是在回答关于要监控哪些生产指标的问题。"}
        //
        //    ⚠️ **模型判断得完全正确** —— 是我们把锚点指错了。后果是级联的:
        //    当前这题必然抽不到 -> 问第二次 -> 还是抽不到 -> 也被跳过 -> 又多一个跳过项。
        //    实测 79 轮撞上 maxRounds, collected 23 / pending 20 / readyForGenerate=false,
        //    assembledDescription 为空, 生成端 400。
        //
        // ⚠️ 只改锚点, **不改候选清单** —— 已跳过的项留在清单里是有用的
        //    (客户后面主动补答还能收下), 这样本次改动的行为差异只有这一处。
        session.getNextPendingParameter().ifPresent(asked ->
                sb.append("## 本轮刚问的是: **")
                  .append(asked.getName()).append("** (")
                  .append(asked.getLabel() != null ? asked.getLabel() : asked.getName())
                  .append(")\n\n"));

        sb.append("## 需要提取的参数:\n");
        for (FactoryAssemblyConversationSession.RequiredParameter param : params) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    param.getName(),
                    param.getLabel() != null ? param.getLabel() : param.getName(),
                    param.getType() != null ? param.getType() : "string"));
            if (param.getValidationHint() != null) {
                sb.append("  提示: ").append(param.getValidationHint()).append("\n");
            }
        }

        // 工厂装配不是逐格访谈：客户经常先讲一个代表性产品的完整流程，再由 Agent
        // 把明确事实映射到逐工序槽位。这个能力只属于建厂访谈，不能污染其他 intent。
        if (FactoryInterviewExpansion.INTENT.equals(session.getKnownIntentCode())) {
            sb.append("\n## 工厂代表性样例规则 (仅本意图):\n");
            sb.append("- 用户说『以某产品为例』并按先后顺序描述从原料到成品的流程时，"
                    + "这是对多个工序参数的有效回答，不要求用户逐句说『第1道/第2道』。\n");
            sb.append("- 当动作边界和先后顺序清楚时，按出现顺序把每个动作的业务名称填入 "
                    + "step1.name、step2.name……；若 stepCount 尚未收集，可用明确列出的动作数填写。\n");
            sb.append("- 用户说『以 X 为例』时，businessType 优先填明确的 X（如『香卤牛肉』），"
                    + "不要改写成『熟食』等更宽泛类别；明确说出的起始主原料可填 "
                    + "step1.inputMaterial。只有原文明确表达时才填。\n");
            sb.append("- 『其他产品大体复用、规格不同』只表示可复用关系，不能据此猜 productCount、"
                    + "产品名称、规格、配方或包装换算。\n");
            sb.append("- 没有明确说出的辅料、包材、工艺参数、出成率、副产物、HACCP 限值一律留 null，"
                    + "后续由 Agent 继续追问。\n");
            sb.append("- 清楚的顺序流程本身就是『明确逐项说了』，不受下面第2条的禁止拆分限制；"
                    + "含糊叙述仍不得强拆或猜测。\n");
        }

        // ⛔ 抽取判据 —— 2026-08-10 全程闸实测到的缺陷:
        //   用户第 1 句「做卤味，主打凤爪和鸭翅」被劈成两半, 后半句填进了 processChain(工序链),
        //   于是下一轮用户真正回答工序链的那句「解冻、腌制、卤煮、真空包装」**整句丢弃**
        //   (那一项已被标记为已收集, 不再进候选)。轨迹上表现为 [5 3 3 ...] 里那个不动的 3。
        //   下游因此拿不到工序链: 原料 0 条、schemas 少一张 —— 而每一步 HTTP 都是 200。
        //
        //   原提示只有一句「无法提取的参数值设为 null」, 太软: 模型认为自己**能**从
        //   「主打凤爪和鸭翅」提取出工序链。所以这里把判据写成硬的。
        sb.append("\n## 提取判据 (必须遵守):\n");
        sb.append("1. 只有当用户这句话**确实在回答某个参数对应的问题**时, 才填那个参数。\n");
        sb.append("2. ⛔ 不要把一句回答拆成几段, 分别填进多个参数 —— 除非用户明确地逐项都说了。\n");
        sb.append("3. ⛔ 宁可留 null, 也不要猜。填错比留空糟糕得多: 参数一旦被填上就不再追问, ");
        sb.append("用户后面真正的回答会被整句丢弃, 而且没有任何报错。\n");
        sb.append("4. 用户说「不知道 / 不清楚 / 还没定 / 你看着办」这类, 一律留 null。\n");
        // 🔴 2026-08-16 本地起服务实测: 「3 道：解冻、冻干、包装」这句里,
        //   stepCount 抽出来的是 "解冻、冻干、包装" —— **数字被丢掉了**, 而客户明明说了。
        //   后果不是少一个字: 下游从这个值里数不出数 ⇒ 逐道工序的 8 个子项一次都不会问,
        //   而访谈照样报告「问齐了」。
        //   ⛔ 判据按**声明的类型**写, 不在这里硬编码 stepCount/productCount ——
        //     那是访谈领域的知识, 通用会话服务不该知道它们叫什么。
        sb.append("5. 标为 **number / integer** 类型的参数, 值只能是阿拉伯数字(如 \"3\")。\n");
        sb.append("   用户说「3 道：解冻、冻干、包装」时, 该参数填 \"3\" —— ");
        sb.append("其余内容不属于它, ⛔ 不要把名字列表填进去。\n");
        sb.append("   数量说不出确切数字(「好几道」「看情况」)时留 null。\n");
        // 🔴 2026-08-17 实测(probe-interview-batch-answer.py, 一条 112 字、说了 10 件事的消息):
        //    三次里有两次, 模型把**整条原文**原样填进了一个参数 ——
        //      businessType = 「我一次说完：我们做宠物冻干零食，行业归类选其他。…」
        //    于是 10 件事只收下 1 项, 且那 1 项的值是错的, 而那题从此标记为已收集、
        //    **再也不会问**。(另一次是塞进 metricWatches, 同一个形状。)
        //
        // ⚠️ 这一条**只能在这里治**: 抽取之后的清洗判不了它 ——
        //    「客户只答了一句话」时值本来就等于整条原文(实测: reply=解冻 -> step1.name=解冻),
        //    与「模型兜底塞全文」在**结构上不可区分**。⇒ 只有模型自己知道它在干什么。
        sb.append("6. ⛔ **不要把用户的整段话原样填进任何一个参数** —— 那不是提取, 是复制。\n");
        sb.append("   一句话里说了好几件事时, 就**分别**填进各自的参数;\n");
        sb.append("   判断不了是哪几件, 就全部留 null 让系统再问一遍 —— ");
        sb.append("⛔ 千万不要\"先塞进一个再说\": 参数一旦被填上就不再追问。\n");
        sb.append("7. 用户明确说不知道/不清楚/还没定时，outcome=UNKNOWN，extracted 全部留空。\n");
        sb.append("8. 用户明确纠正旧答案时，outcome=SUCCESS；新值写入 extracted；"
                + "仅要求清空时把参数名写入 clear。不要同时清空并填写同一参数。\n");
        sb.append("9. 用户给出互相矛盾且无法唯一裁决的答案时，outcome=CONFLICT，"
                + "不得选择其中一个猜测。\n");

        sb.append("\n## 输出格式 (JSON):\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"outcome\": \"SUCCESS | UNKNOWN | CONFLICT\",\n");
        sb.append("  \"extracted\": {\n");
        sb.append("    \"参数名\": \"提取的值或null\"\n");
        sb.append("  },\n");
        sb.append("  \"clear\": [\"仅在明确纠正时要清空的参数名\"],\n");
        sb.append("  \"reasoning\": \"提取理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON。普通成功 outcome=SUCCESS；未知答案 outcome=UNKNOWN；"
                + "无法裁决的冲突 outcome=CONFLICT。对于无法提取的参数，值设为 null。");

        return sb.toString();
    }

    /**
     * 验证参数值是否符合类型要求
     */
    private boolean isValidParameterValue(FactoryAssemblyConversationSession.RequiredParameter param, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        String type = param.getType();
        if (type == null) {
            return true; // 无类型限制
        }

        switch (type.toLowerCase()) {
            case "number":
                return value.matches("-?\\d+(\\.\\d+)?");
            case "integer":
            case "int":
                return value.matches("-?\\d+");
            case "date":
                try {
                    if (value.matches("\\d{8}")) {
                        LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
                    } else if (value.contains("/")) {
                        LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu/MM/dd")
                                .withResolverStyle(ResolverStyle.STRICT));
                    } else {
                        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                    }
                    return true;
                } catch (DateTimeParseException ignored) {
                    return false;
                }
            case "boolean":
            case "bool":
                return value.matches("(?i)true|false|yes|no|是|否|1|0");
            case "uuid":
                if (!value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-"
                        + "[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
                    return false;
                }
                try {
                    UUID.fromString(value);
                    return true;
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
            default:
                return true; // string 或其他类型
        }
    }

    /**
     * 构建下一个参数收集问题
     *
     * @param session 当前会话
     * @return 下一个问题
     */
    private String buildNextParameterQuestion(FactoryAssemblyConversationSession session) {
        Optional<FactoryAssemblyConversationSession.RequiredParameter> nextParamOpt = session.getNextPendingParameter();

        if (nextParamOpt.isEmpty()) {
            return "好的，信息收集完成。正在处理您的请求...";
        }

        FactoryAssemblyConversationSession.RequiredParameter nextParam = nextParamOpt.get();

        // 获取已收集的参数用于上下文
        Map<String, String> collected = session.getCollectedParameters();
        int collectedCount = collected != null ? collected.size() : 0;
        List<FactoryAssemblyConversationSession.RequiredParameter> allParams = session.getRequiredParameters();
        int totalCount = allParams != null ? allParams.size() : 0;

        StringBuilder sb = new StringBuilder();

        // 如果已收集部分参数，先确认
        if (collectedCount > 0) {
            sb.append("好的，已记录。");
        }

        // 提示还需要的参数
        int remainingCount = totalCount - collectedCount - 1; // -1 是因为下面会问当前参数
        if (remainingCount > 0) {
            sb.append(String.format("还需要 %d 项信息。\n\n", remainingCount + 1));
        } else {
            sb.append("\n\n");
        }

        // 生成当前参数的问题
        //
        // ⛔ 不用 markdown 的 `**加粗**` 包题目名(owner 2026-08-17 定的硬要求:
        //    建厂过程里客户看到的任何文字都不许出现编程语言/字段名/JSON/markdown)。
        //    这条是**客户读得最多的一句** —— 实测一次建厂 46 轮, 每一轮都带着那两对星号。
        //    换成中文引号: 一样能把题目名衬出来, 而且在纯文本气泡里本来就是这么读的。
        // ⚠️ 有两个探针按 `请提供 **X**` 抠题目名(probe-agent-build-factory /
        //    probe-customer-facing-text), 改这里必须同一个 commit 改它们 ——
        //    否则探针抠不到题目, 每轮都回兜底句, 读数会长得像「产品退化了」。
        String label = nextParam.getLabel() != null ? nextParam.getLabel() : nextParam.getName();
        sb.append("请提供「").append(label).append("」");

        if (nextParam.getValidationHint() != null && !nextParam.getValidationHint().isEmpty()) {
            sb.append("\n").append("(").append(nextParam.getValidationHint()).append(")");
        }

        // ⛔ 明确告诉用户「可以一次说很多」——**能力早就在, 只是没人知道**。
        //
        // 2026-08-11 实测(真会话): 单条消息最多接住 14 个槽位, 5 轮把 45 项收齐。
        // 但每轮只提示一个问题, 真实用户看到一问一答的界面就会一问一答 —— 那是 41 轮。
        // 这句话几乎零成本, 却是「像在说话」与「填 41 个空」的分界。
        //
        // ⚠️ 只在还剩得多时说: 剩最后两三项还提示「可以一起说」是噪音。
        // 🔴 2026-08-17 实测(probe-interview-batch-answer.py, 每种形状多次):
        //    **真正的变量不是「一次说几件」, 是「一件事一句话」。**
        //
        //      消息形状                        一次说几件   成功率
        //      纯句号(一件事一句)              6            4/4
        //      纯句号(一件事一句)              10           **4/4**
        //      混合(有的句子里两件事用逗号连)  10           1/7
        //      纯逗号(全连成一句)              6            0/4
        //      纯逗号                          2            2/4
        //
        //    失败的方式不是「少接几项」, 而是**整条原文被塞进一个槽** ——
        //    那题从此标记为已收集、**再也不会问**, 其余几件事永久丢失。
        //
        // ⇒ 这句提示原先只说「可以一次多说几件事」, 而**没告诉客户怎么说**。
        //    照着它把十件事连成一长句 = 掉进 0/4 那一档。
        // ⚠️ 试过而**没用**的两条(都已实测): 抽取后清洗判不了(与「只答一句话」
        //    结构上不可区分); 给抽取 prompt 加「不要把整段话原样填进一个参数」
        //    加之前 1/3、加之后 1/4, 看不出差别。
        //    也写过一个按标点切分再逐句抽的方案, **丢弃了** —— 它刻意不切逗号
        //    (「100g/袋，按袋计」是一项答案的两半), 治不了真正崩的那一档。
        if (remainingCount + 1 >= 5) {
            // ⛔ 同上: 不用 markdown 加粗(客户看的是纯文本气泡, 星号只会照原样显示出来)
            sb.append("\n\n💡 可以一次多说几件事 —— 一件事一句话、用句号隔开, ")
              .append("像「一共 3 道工序。做 2 个成品。没有客供料。」这样。\n")
              .append("   ⚠️ 别把几件事用逗号连成一长句, 那样系统容易只听懂头一件。");
        }

        return sb.toString();
    }
}

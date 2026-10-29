package com.cretas.aims.blueprint.interview;

import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.FactoryBuildProgress;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationActor;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationHistoryMessage;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import java.util.*;
import java.util.LinkedHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话建厂访谈 —— 题目表、会话进度、结构化答题、以及喂给生成器的描述拼装。
 *
 * <h2>2026-08-12 从 controller 抽出</h2>
 *
 * 原文逐字搬运。鉴权(角色闸 / token 里有没有 userId)留在 controller ——
 * 那几步不碰库, 与拆分前发生在同一时刻; 事务跨度见
 * {@link #submitStructuredAnswers} 的注释。
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryBuildInterviewService {

    @Autowired
    private InterviewDefaults interviewDefaults;

    @Autowired
    private PlatformRecommendationLibrary platformRecommendationLibrary;

    @Autowired
    private FactoryAssemblyConversationService conversationService;

    @Autowired
    private FactoryAssemblyConversationSessionRepository sessionRepository;

    /** 已确认事实: 新访谈沿用它只问差异; 与它不同的新答案进入逐条冲突。 */
    @Autowired
    private FactoryConfirmedFactService confirmedFacts;

    /** 见 {@code packagingSection}: 单位是不是单位问权威表, ⛔ 不在这里自建词表。 */
    @Autowired
    private UnitContractService unitContractService;

    /** ⛔ 鉴权(角色 + userId 非空)留在 controller —— 它不碰库, 与拆分前同一时刻发生。 */
    public FactoryAssemblyConversationService.ConversationResponse startFactoryBuildConversation(
            String factoryId, Long userId) {
        return startFactoryBuildConversation(factoryId, ConversationActor.factoryUser(userId));
    }

    public FactoryAssemblyConversationService.ConversationResponse startFactoryBuildConversation(
            String factoryId, ConversationActor actor) {
        return startFactoryBuildConversation(factoryId, actor, false);
    }

    public FactoryAssemblyConversationService.ConversationResponse startFactoryBuildConversation(
            String factoryId, ConversationActor actor, boolean forceRestart) {

        log.info("开始对话建厂: factoryId={}, actorType={}, userId={}, 待问 {} 项",
                factoryId, actor.type(), actor.userId(), FACTORY_BUILD_PARAMS.size());

        if (!forceRestart) {
            Optional<FactoryAssemblyConversationSession> active = conversationService
                    .getActiveSession(factoryId, actor)
                    .filter(session -> session.getSessionMode()
                            == FactoryAssemblyConversationSession.SessionMode.PARAMETER_COLLECTION)
                    .filter(session -> FACTORY_BUILD_PARAMS_INTENT.equals(session.getKnownIntentCode()))
                    .filter(session -> !session.isExpired());
            if (active.isPresent()) {
                FactoryAssemblyConversationSession session = active.get();
                List<ConversationHistoryMessage> history = conversationHistory(session);
                String lastAssistantMessage = history.stream()
                        .filter(message -> "assistant".equals(message.getRole()))
                        .reduce((first, second) -> second)
                        .map(ConversationHistoryMessage::getContent)
                        .orElse("继续上次的建厂访谈。");
                log.info("恢复对话建厂: factoryId={}, actorType={}, userId={}, sessionId={}, round={}",
                        factoryId, actor.type(), actor.userId(), session.getSessionId(), session.getCurrentRound());
                return FactoryAssemblyConversationService.ConversationResponse.builder()
                        .sessionId(session.getSessionId())
                        .currentRound(session.getCurrentRound())
                        .maxRounds(session.getMaxRounds())
                        .status(session.getStatus())
                        .completed(false)
                        .message(lastAssistantMessage)
                        .intentCode(FACTORY_BUILD_PARAMS_INTENT)
                        .intentName("对话建厂")
                        .requiresConfirmation(true)
                        .resumed(true)
                        .history(history)
                        .build();
            }
        }

        FactoryAssemblyConversationService.ConversationResponse started = conversationService.startParameterCollection(
                factoryId, actor,
                FACTORY_BUILD_PARAMS_INTENT, "对话建厂",
                FACTORY_BUILD_PARAMS,
                List.of("我来问几个问题，把这家厂的情况摸清楚。"));
        if (started != null && started.getSessionId() != null) {
            sessionRepository.findBySessionIdAndFactoryIdAndUserIdAndActorType(
                            started.getSessionId(), factoryId, actor.userId(), actor.type())
                    .ifPresent(session -> reuseConfirmedFacts(factoryId, session));
        }
        return started;
    }

    /**
     * 本厂此前各版访谈里「用户确认过」的事实: 取该会话里最新一条 ACCEPTED 回执, 且当前值仍等于回执值
     * (被改过又没有新回执的不算)。越新的会话越优先; 提取候选、没有回执的值一律不采信。
     */
    List<FactoryConfirmedFactService.PriorFact> priorSessionFacts(
            String factoryId, FactoryAssemblyConversationSession current) {
        Map<String, FactoryConfirmedFactService.PriorFact> byField = new LinkedHashMap<>();
        for (FactoryAssemblyConversationSession prior
                : sessionRepository.findTop20ByFactoryIdOrderByCreatedAtDesc(factoryId)) {
            if (prior.getSessionId() == null || prior.getSessionId().equals(current.getSessionId())) continue;
            if (!FACTORY_BUILD_PARAMS_INTENT.equals(prior.getKnownIntentCode())) continue;
            Map<String, String> labels = new LinkedHashMap<>();
            prior.getRequiredParameters().forEach(rp -> labels.put(rp.getName(), rp.getLabel()));
            Map<String, String> accepted = new LinkedHashMap<>();
            for (FactoryAssemblyConversationSession.Message message : prior.getMessages()) {
                FactoryAssemblyConversationSession.ReplyReceipt receipt = message.getReplyReceipt();
                if (receipt == null || receipt.getFieldReceipts() == null) continue;
                for (FactoryAssemblyConversationSession.ReplyFieldReceipt field : receipt.getFieldReceipts()) {
                    if (field == null || isBlank(field.getField()) || !"ACCEPTED".equals(field.getResolution())) continue;
                    accepted.put(field.getField(), field.getCanonicalValue());
                }
            }
            Map<String, String> collected = prior.getCollectedParameters();
            accepted.forEach((field, value) -> {
                if (isBlank(value) || !Objects.equals(collected.get(field), value)) return;
                byField.putIfAbsent(field, new FactoryConfirmedFactService.PriorFact(
                        field, labels.getOrDefault(field, field), value, prior.getSessionId()));
            });
        }
        return new ArrayList<>(byField.values());
    }

    /**
     * 沿用本厂已确认事实: 只填「还没答」的题, 留下 REUSED_CONFIRMED_FACT 回执 (来源可追查),
     * 因此这些题不再被问; 工序道数被沿用后展开的子项也再沿用一轮。差异靠用户的新答案体现。
     */
    void reuseConfirmedFacts(String factoryId, FactoryAssemblyConversationSession session) {
        // 上一版访谈里用户确认过的事实 (自由文本回答也算, 只要有回执) 先进入历史, 再统一沿用。
        confirmedFacts.adoptPriorFacts(factoryId, priorSessionFacts(factoryId, session));
        Map<String, String> reused = new LinkedHashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            Map<String, String> round = confirmedFacts.reusableFor(factoryId, session);
            if (round.isEmpty()) break;
            round.forEach(session::addCollectedParameter);
            reused.putAll(round);
            FactoryInterviewExpansion.expandIfReady(session);
            FactoryInterviewExpansion.expandProductsIfReady(session);
        }
        if (reused.isEmpty()) return;
        List<FactoryAssemblyConversationSession.ReplyFieldReceipt> fields = new ArrayList<>();
        StringJoiner message = new StringJoiner("；");
        for (Map.Entry<String, String> entry : reused.entrySet()) {
            message.add(entry.getKey() + "：" + entry.getValue());
            fields.add(FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                    .field(entry.getKey())
                    .resolution("ACCEPTED")
                    .canonicalValue(entry.getValue())
                    .reasonCode(FactoryConfirmedFactService.REUSE_REASON_CODE)
                    .build());
        }
        FactoryAssemblyConversationSession.ReplyReceipt receipt = FactoryAssemblyConversationSession.ReplyReceipt.builder()
                .extractionOutcome("SUCCESS")
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus() == null ? null : session.getStatus().name())
                .completed(false)
                .message("沿用本厂已确认资料")
                .intentCode(session.getKnownIntentCode())
                .progressMutated(true)
                .correctionApplied(false)
                .fieldReceipts(fields)
                .build();
        FactoryAssemblyConversationSession.Message note =
                FactoryAssemblyConversationSession.Message.user("沿用本厂已确认资料（" + message + "）");
        note.setReplyReceipt(receipt);
        session.addMessage(note);
        if (session.allParametersCollected()) {
            session.completeParameterCollection();
        }
        sessionRepository.save(session);
        log.info("对话建厂沿用已确认事实 {} 项: factoryId={}, sessionId={}", reused.size(), factoryId, session.getSessionId());
    }

    public List<InterviewDefaults.Section> interviewDefaults(String factoryId) {
        return interviewDefaults.forFactory(factoryId);
    }

    /**
     * 会话进度 —— 收敛判据从这里读。
     *
     * <p>{@code remaining} 必须<b>单调不增且有界归零</b>: 每答一轮少一项, 答不上来就不动。
     * ⛔ 别拿模型的措辞当判据。
     *
     * <p>⚠️ 无事务(与拆分前一致)。被 {@link #submitStructuredAnswers} 内部调用时,
     * 那是<b>同类自调用</b> —— 不过代理, 因此仍跑在调用方的事务里, 与拆分前的
     * controller 自调用逐字同构。
     */
    public FactoryBuildProgress progress(String factoryId, String sessionId, Long userId) {
        return progress(factoryId, sessionId, ConversationActor.factoryUser(userId));
    }

    public FactoryBuildProgress progress(
            String factoryId, String sessionId, ConversationActor actor) {

        FactoryAssemblyConversationSession session = sessionRepository
                .findBySessionIdAndFactoryIdAndUserIdAndActorType(
                        sessionId, factoryId, actor.userId(), actor.type())
                .orElseThrow(() -> new BusinessException(404, "会话不存在或不属于当前用户: " + sessionId));

        return buildProgress(factoryId, session);
    }

    /**
     * Latest ACTIVE or COMPLETED factory interview that can be resumed or generated.
     * This is read-only and never creates, cancels or restarts a conversation.
     */
    public Optional<FactoryBuildProgress> recoverableProgress(
            String factoryId, ConversationActor actor) {
        List<FactoryAssemblyConversationSession> eligible = sessionRepository
                .findRecentByActor(factoryId, actor.userId(), actor.type()).stream()
                .filter(session -> session.getSessionMode()
                        == FactoryAssemblyConversationSession.SessionMode.PARAMETER_COLLECTION)
                .filter(session -> FACTORY_BUILD_PARAMS_INTENT.equals(session.getKnownIntentCode()))
                .filter(session -> session.getStatus() == FactoryAssemblyConversationSession.SessionStatus.COMPLETED
                        || (session.getStatus() == FactoryAssemblyConversationSession.SessionStatus.ACTIVE
                            && !session.isExpired()))
                .toList();
        Optional<FactoryAssemblyConversationSession> active = eligible.stream()
                .filter(session -> session.getStatus() == FactoryAssemblyConversationSession.SessionStatus.ACTIVE)
                .findFirst();
        return active.or(() -> eligible.stream()
                        .filter(session -> session.getStatus()
                                == FactoryAssemblyConversationSession.SessionStatus.COMPLETED)
                        .findFirst())
                .map(session -> buildProgress(factoryId, session));
    }

    private FactoryBuildProgress buildProgress(String factoryId, FactoryAssemblyConversationSession session) {
        String sessionId = session.getSessionId();

        Map<String, String> collected = session.getCollectedParameters();

        FactoryBuildProgress p = new FactoryBuildProgress();
        p.setSessionId(sessionId);
        p.setFactoryId(factoryId);
        p.setStatus(session.getStatus() == null ? null : session.getStatus().name());
        p.setCurrentRound(session.getCurrentRound());
        p.setMaxRounds(session.getMaxRounds());
        p.setHistory(conversationHistory(session));

        for (FactoryAssemblyConversationSession.RequiredParameter rp : session.getRequiredParameters()) {
            // 以 collected map 为准: 实体的 collected 标志与 map 是两处口径,
            // 而真正被下游消费的是 map —— 判据要贴着被消费的那份。
            String v = collected.get(rp.getName());
            if (v != null && !v.trim().isEmpty()) {
                p.getAnswered().add(rp.getName());
            } else {
                p.getPending().add(rp.getName());
                p.getPendingLabels().add(rp.getLabel());
            }
        }
        p.setRemaining(p.getPending().size());
        p.setCollected(collected);

        // 平台建议 —— 客户答完行业归类才有得给；不暴露任何其他客户或来源工厂。
        // ⛔ 只塞进 suggestions, **不碰 collected**: 建议一旦被当成答案,
        //    remaining 会归零、访谈显示"完成", 而客户没有真正确认。
        //    F 段之外唯一的判据就是 remaining 有没有因为建议而变小(闸盯着这一点)。
        String industry = collected.get("industryHint");
        if (industry != null && !industry.isBlank()) {
            PlatformRecommendationLibrary.PlatformRecommendation recommendation =
                    platformRecommendationLibrary.forIndustry(industry);
            if (recommendation == null) {
                p.setRecommendationNote("平台尚未为「" + industry + "」准备推荐流程；"
                        + "这次会根据你们当前工厂的实际情况逐项询问。");
            } else {
                p.setPlatformRecommendation(recommendation);
                // 只给**还没答**的题的建议 —— 已经答过的不再提示, 免得看起来像在质疑客户
                for (Map.Entry<String, String> e : recommendation.getSuggestions().entrySet()) {
                    if (p.getPending().contains(e.getKey())) {
                        p.getSuggestions().put(e.getKey(), e.getValue());
                    }
                }
                if (p.getPending().contains("stepCount") && recommendation.getStepCount() > 0) {
                    p.getSuggestions().put("stepCount", String.valueOf(recommendation.getStepCount()));
                }

                // ⛔ 道数对不上时必须说出来。
                //    建议是**按位置**对齐的(step3 → 案例第 3 条)。客户答的道数比案例少时,
                //    案例末尾那几道就没有对应的题 —— 实测: 卤味案例 6 道, 客户答 5 道,
                //    第 6 道「气调装盒」整个消失, 而客户看到的是一条从头到尾都有建议的链,
                //    完全看不出包装环节被丢了。
                //    静默截断读起来就是「全给了」, 所以这里如实报出差多少、丢了哪几道。
                String answeredCount = collected.get("stepCount");
                if (answeredCount != null && recommendation.getStepCount() > 0) {
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("\\d+").matcher(answeredCount);
                    if (m.find()) {
                        int said = Integer.parseInt(m.group());
                        if (said != recommendation.getStepCount()) {
                            StringBuilder note = new StringBuilder();
                            note.append("平台建议包含 ").append(recommendation.getStepCount())
                                .append(" 道, 你确认的是 ").append(said).append(" 道。");
                            if (said < recommendation.getStepCount()) {
                                note.append("下面这些建议步骤没有对应问题，请确认你们确实不需要：");
                                for (int i = said + 1; i <= recommendation.getStepCount(); i++) {
                                    String nm = recommendation.getSuggestions().get("step" + i + ".name");
                                    note.append(i > said + 1 ? "、" : "").append(nm == null ? ("第" + i + "道") : nm);
                                }
                            } else {
                                note.append("新增步骤不在平台建议中，需要按你们的实际情况填写。");
                            }
                            p.setRecommendationNote(note.toString());
                        }
                    }
                }
            }
        }

        // 待答题的渲染信息 —— 与 suggestions 同一轮算完, 免得两处口径漂
        //
        // 🔴 2026-08-18 真实浏览器里实测到的断法(接口探针**看不见**, 因为探针读的是
        //    会话给的 nextQuestion, 而界面读的是这里):
        //    一项问了两次没拿到被**放弃**之后, 它没有值 ⇒ 仍在 pending 里 ⇒
        //    `pendingItems[0]` 恒指着它, 而会话早已问到别的题去了。界面上
        //    「当前这一题」的标签/提示/选项**全是那道已经放弃的题**,
        //    客户照着标签答 → 答案被当成另一题的回答 → 又抽不出来 → 又放弃 → 级联。
        //    实测长相: 标签写着「第2道·投入主原料」, 对话里问的是「第4道·工序名称」。
        //
        // ⇒ 「下一题是哪一项」只认 askableParameters() 这一份(本仓治过同一形态,
        //    见 ReadyToGenerateIgnoresAbandonedItemsTest)。⛔ 界面不再自己数一遍。
        // ⚠️ pending/remaining 仍按原样如实计数(那是上一轮明确定下的口径),
        //    ⛔ 但已放弃的项不能就这么从界面上消失 —— 它们进 skippedItems, 带原因给客户看。
        List<FactoryAssemblyConversationSession.RequiredParameter> askableParameters = session.askableParameters();
        java.util.Set<String> askable = askableParameters.stream()
                .map(FactoryAssemblyConversationSession.RequiredParameter::getName)
                .collect(java.util.stream.Collectors.toSet());
        for (FactoryAssemblyConversationSession.RequiredParameter rp : session.getRequiredParameters()) {
            if (!p.getPending().contains(rp.getName())) {
                if (rp.getLabel() != null) {
                    p.getAnsweredLabels().put(rp.getName(), rp.getLabel());
                }
                continue;
            }
            if (!askable.contains(rp.getName())) {
                // 用户主动说“暂时不知道”不是系统放弃。它单独进入 unresolvedDecisions，
                // 不能同时出现在 skippedItems 里造成“已跳过/仍需回答”两种相反状态。
                if (rp.isUserDeferred()) {
                    continue;
                }
                FactoryBuildProgress.SkippedItem sk = new FactoryBuildProgress.SkippedItem();
                sk.setName(rp.getName());
                sk.setLabel(rp.getLabel());
                sk.setWhy(rp.getSkipReason());
                p.getSkippedItems().add(sk);
                continue;
            }
            FactoryBuildProgress.PendingItem it = new FactoryBuildProgress.PendingItem();
            it.setName(rp.getName());
            it.setLabel(rp.getLabel());
            it.setInputType(rp.getInputType() == null ? "NARRATIVE" : rp.getInputType());
            it.setOptions(rp.getOptions());
            it.setMultiSelect(rp.isMultiSelect());
            it.setAllowNone(rp.isAllowNone());
            it.setHint(rp.getValidationHint());
            it.setSuggestion(p.getSuggestions().get(rp.getName()));
            it.setRepeatIndex(rp.getRepeatIndex());
            p.getPendingItems().add(it);
        }

        List<FactoryAssemblyConversationSession.RequiredParameter> deferredParameters = session.getDeferredParameters();
        for (FactoryAssemblyConversationSession.RequiredParameter deferred : deferredParameters) {
            p.getUnresolvedDecisions().add(FactoryDecisionGuidanceCatalog.build(
                    deferred, p.getSuggestions().get(deferred.getName()), "UNRESOLVED"));
        }
        if (!askableParameters.isEmpty()) {
            FactoryAssemblyConversationSession.RequiredParameter current = askableParameters.get(0);
            p.setCurrentDecision(FactoryDecisionGuidanceCatalog.build(
                    current, p.getSuggestions().get(current.getName()), "PENDING"));
        } else if (!deferredParameters.isEmpty()) {
            FactoryAssemblyConversationSession.RequiredParameter current = deferredParameters.get(0);
            p.setCurrentDecision(FactoryDecisionGuidanceCatalog.build(
                    current, p.getSuggestions().get(current.getName()), "UNRESOLVED"));
        }

        p.setFactEvidence(buildFactEvidence(session, p));
        for (FactoryBuildProgress.FactEvidenceItem item : p.getFactEvidence()) {
            if (FactoryConfirmedFactService.REUSE_REASON_CODE.equals(item.getReasonCode())) {
                p.getReusedFacts().put(item.getField(), item.getValue());
            }
        }
        p.setFactConflicts(confirmedFacts.openConflicts(factoryId));
        p.setRoutePreviews(
                com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.compile(collected));

        // 没题可问了才给描述。⛔ 还有题可问时不拼一个「半成品描述」——
        // 那会让下游以为信息够了, 正是「AI 替客户编」的入口。
        //
        // ⛔ 判据是「还有没有**问得动**的题或用户主动保留的未决题」, 不是
        //    `remaining == 0`。系统达到重试上限后放弃的题不阻塞完成；用户明确选择
        //    “暂时不知道”的题必须保持 unresolved，直到真实答案替换它。
        //
        // 🔴 2026-08-16 本地端到端实测(F999, 真实端点):
        //      46 轮 status=COMPLETED   collected 40 项   pending 3 项(**全是被跳过的那 3 项**)
        //      readyForGenerate=false   assembledDescription 长度 0
        //      生成 http=400「工厂描述长度应在10-2000字符之间, 工厂描述不能为空」
        //    客户已经被当面告知「这一项先跳过」, 系统却因此再也不肯往下走。
        //
        // ⚠️ pending / remaining 本身**不改** —— 它如实列出「没有答案的项」给界面看,
        //    改的只是「给不给描述」这一个判据。
        if (askableParameters.isEmpty() && deferredParameters.isEmpty()) {
            p.setAssembledDescription(assembleDescription(session, collected));
            p.setReadyForGenerate(true);
        }
        return p;
    }

    /**
     * 从现有 collected + typed receipts 派生事实证据，不新增第二份事实存储。
     *
     * <p>值只认 collected；receipt 只回答“谁通过什么通道确认、何时确认”。如果历史会话
     * 没有逐字段 receipt，就明确标为 LEGACY_UNATTRIBUTED，禁止用 session 更新时间冒充
     * 字段采集时间。</p>
     */
    private List<FactoryBuildProgress.FactEvidenceItem> buildFactEvidence(
            FactoryAssemblyConversationSession session, FactoryBuildProgress progress) {
        record ReceiptEvidence(
                String canonicalValue, String reasonCode, java.time.LocalDateTime capturedAt,
                String evidenceRef) {}

        Map<String, ReceiptEvidence> latest = new LinkedHashMap<>();
        List<FactoryAssemblyConversationSession.Message> messages = session.getMessages();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            FactoryAssemblyConversationSession.Message message = messages.get(messageIndex);
            FactoryAssemblyConversationSession.ReplyReceipt receipt = message.getReplyReceipt();
            if (receipt == null || receipt.getFieldReceipts() == null) continue;
            for (FactoryAssemblyConversationSession.ReplyFieldReceipt field : receipt.getFieldReceipts()) {
                if (field == null || isBlank(field.getField())) continue;
                if (!"ACCEPTED".equals(field.getResolution())) continue;
                latest.put(field.getField(), new ReceiptEvidence(
                        field.getCanonicalValue(), field.getReasonCode(), message.getTimestamp(),
                        "conversation:" + session.getSessionId() + ":message:" + (messageIndex + 1)));
            }
        }

        Map<String, String> labels = session.getRequiredParameters().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FactoryAssemblyConversationSession.RequiredParameter::getName,
                        parameter -> Objects.toString(parameter.getLabel(), parameter.getName()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<FactoryBuildProgress.FactEvidenceItem> facts = new ArrayList<>();
        for (Map.Entry<String, String> entry : progress.getCollected().entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (isBlank(field) || isBlank(value)) continue;
            ReceiptEvidence evidence = latest.get(field);
            boolean exactReceipt = evidence != null
                    && Objects.equals(value, evidence.canonicalValue());
            FactoryBuildProgress.FactEvidenceItem fact =
                    new FactoryBuildProgress.FactEvidenceItem();
            fact.setField(field);
            fact.setLabel(labels.getOrDefault(field, field));
            fact.setValue(value);
            if (exactReceipt) {
                String reasonCode = evidence.reasonCode();
                fact.setSourceKind(reasonCode != null && reasonCode.startsWith("STRUCTURED_DECISION_")
                        ? "USER_STRUCTURED" : "USER_NARRATIVE");
                fact.setEvidenceStatus("CONFIRMED");
                fact.setReasonCode(reasonCode);
                fact.setCapturedAt(evidence.capturedAt());
                fact.setEvidenceRef(evidence.evidenceRef());
            } else {
                fact.setSourceKind("LEGACY_SESSION");
                fact.setEvidenceStatus("LEGACY_UNATTRIBUTED");
                fact.setEvidenceRef("conversation:" + session.getSessionId()
                        + ":collected:" + field);
            }
            fact.setReferenceMatched(Objects.equals(
                    value, referenceValue(progress, field)));
            facts.add(fact);
        }
        return facts;
    }

    private String referenceValue(FactoryBuildProgress progress, String field) {
        String pendingSuggestion = progress.getSuggestions().get(field);
        if (!isBlank(pendingSuggestion)) return pendingSuggestion;
        String industry = progress.getCollected().get("industryHint");
        if (isBlank(industry) || platformRecommendationLibrary == null) return null;
        PlatformRecommendationLibrary.PlatformRecommendation recommendation =
                platformRecommendationLibrary.forIndustry(industry);
        if (recommendation == null) return null;
        if ("stepCount".equals(field) && recommendation.getStepCount() > 0) {
            return String.valueOf(recommendation.getStepCount());
        }
        return recommendation.getSuggestions().get(field);
    }

    private List<ConversationHistoryMessage> conversationHistory(FactoryAssemblyConversationSession session) {
        return session.getMessages().stream()
                .filter(message -> message.getRole() != null && message.getContent() != null)
                .map(message -> {
                    FactoryAssemblyConversationSession.ReplyReceipt receipt = message.getReplyReceipt();
                    List<FactoryAssemblyConversationService.FieldReceipt> fields = receipt == null
                            || receipt.getFieldReceipts() == null
                            ? List.of()
                            : receipt.getFieldReceipts().stream()
                                    .map(field -> FactoryAssemblyConversationService.FieldReceipt.builder()
                                            .field(field.getField())
                                            .resolution(FactoryAssemblyConversationService.FieldResolution.valueOf(
                                                    field.getResolution()))
                                            .canonicalValue(field.getCanonicalValue())
                                            .reasonCode(field.getReasonCode())
                                            .build())
                                    .toList();
                    return ConversationHistoryMessage.builder()
                            .role(message.getRole())
                            .content(message.getContent())
                            .extractionOutcome(receipt == null ? null : receipt.getExtractionOutcome())
                            .correctionApplied(receipt != null && receipt.isCorrectionApplied())
                            .fieldReceipts(fields)
                            .build();
                })
                .toList();
    }

    /**
     * 结构化答题 —— 枚举类题目不走 LLM 抽取。
     *
     * <p>⛔ 全量校验先于任何写入: 任一项不合规 → 整个提交 400, 一个都不写。
     *
     * <h3>事务跨度与拆分前相同</h3>
     *
     * 拆分前 {@code @Transactional} 挂在 controller 方法上, controller 在它之前
     * 只做 token 解析(不碰库)。现在挂在本方法上, 起点终点没有移动。
     * 末尾对 {@link #progress} 的调用<b>仍是自调用</b>, 与拆分前同构。
     */
    @Transactional
    public FactoryBuildProgress submitStructuredAnswers(
            String factoryId, String sessionId, Long userId, Map<String, Object> answers) {
        return submitStructuredAnswers(
                factoryId, sessionId, ConversationActor.factoryUser(userId), answers);
    }

    @Transactional
    public FactoryBuildProgress submitStructuredAnswers(
            String factoryId,
            String sessionId,
            ConversationActor actor,
            Map<String, Object> answers) {

        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(400, "没有提交任何答案")
                    .withHint("至少勾选一项再提交").withSeverity("error");
        }

        FactoryAssemblyConversationSession session = sessionRepository
                .findBySessionIdAndFactoryIdAndUserIdAndActorType(
                        sessionId, factoryId, actor.userId(), actor.type())
                .orElseThrow(() -> new BusinessException(404, "会话不存在或不属于当前用户: " + sessionId));

        if (session.getStatus() != FactoryAssemblyConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException(409,
                    "会话已" + session.getStatus() + ", 不能继续答题")
                    .withHint("请重新开始一轮对话").withSeverity("warning");
        }

        // 按 name 索引本会话的必需参数 —— 校验的依据是**会话里真实存在的题目**,
        // 不是请求里说了什么。
        Map<String, FactoryAssemblyConversationSession.RequiredParameter> byName = new LinkedHashMap<>();
        for (FactoryAssemblyConversationSession.RequiredParameter rp : session.getRequiredParameters()) {
            byName.put(rp.getName(), rp);
        }
        Set<String> previouslyDeferred = byName.values().stream()
                .filter(FactoryAssemblyConversationSession.RequiredParameter::isUserDeferred)
                .map(FactoryAssemblyConversationSession.RequiredParameter::getName)
                .collect(java.util.stream.Collectors.toSet());

        // ---- 全量校验(先于任何写入) ----
        List<String> problems = new ArrayList<>();
        Map<String, String> before = new LinkedHashMap<>(session.getCollectedParameters());
        Map<String, String> accepted = new LinkedHashMap<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            String key = e.getKey();
            FactoryAssemblyConversationSession.RequiredParameter rp = byName.get(key);
            if (rp == null) {
                problems.add(key + ": 本会话没有这道题");
                continue;
            }
            String val = stringifyAnswer(e.getValue());
            if (val == null || val.trim().isEmpty()) {
                // ⛔ 空值不算已回答。让空值通过会让 remaining 归零而内容是空的,
                //    正是「问齐了但没问到」那种假绿。
                problems.add(key + ": 值为空(空值不算已回答)");
                continue;
            }
            String normalized = val.trim();
            if (FactoryDecisionPacket.UNKNOWN_ANSWER_VALUE.equals(normalized)) {
                deferred.add(key);
                continue;
            } else if (FactoryInterviewExpansion.STEP_COUNT_KEY.equals(key)) {
                OptionalInt count = boundedStepCount(normalized);
                if (count.isEmpty()) {
                    problems.add(key + ": 必须是 1-" + FactoryInterviewExpansion.MAX_STEPS
                            + " 的整数");
                } else {
                    normalized = String.valueOf(count.getAsInt());
                }
            } else {
                List<String> opts = rp.getOptions();
                if ("CHOICE".equalsIgnoreCase(rp.getInputType())
                        && opts != null && !opts.isEmpty()) {
                    for (String one : splitMulti(val, rp.isMultiSelect())) {
                        if (!opts.contains(one) && !(rp.isAllowNone() && isNoneAnswer(one))) {
                            problems.add(key + ": \"" + one + "\" 不在候选项内 " + opts);
                        }
                    }
                }
            }
            accepted.put(key, normalized);
        }

        validateProductRouteShapes(accepted, problems);

        Map<String, String> topologyCandidate = new LinkedHashMap<>(before);
        topologyCandidate.putAll(accepted);
        FactoryRouteIntentCompiler.validateTopologySubmission(topologyCandidate, accepted.keySet())
                .forEach(issue -> problems.add(issue.code() + ": " + issue.message()));

        if (!problems.isEmpty()) {
            log.warn("结构化答题被拒: session={}, 问题 {} 条: {}", sessionId, problems.size(), problems);
            throw new BusinessException(400,
                    "答案不合规, 未写入任何一项 (" + problems.size() + " 处): "
                    + String.join("; ", problems))
                    .withHint("请修正后重新提交").withSeverity("error");
        }

        // ---- 写入 ----
        deferred.forEach(session::deferParameter);
        accepted.forEach(session::addCollectedParameter);
        // stepCount 刚答上 → 展开 N 道工序的子项(含抬高 maxRounds)
        FactoryInterviewExpansion.expandIfReady(session);
        FactoryInterviewExpansion.expandProductsIfReady(session);
        if (session.allParametersCollected()) {
            session.completeParameterCollection();
        }
        appendStructuredDecisionReceipt(
                session, accepted, deferred, before, previouslyDeferred, byName);
        sessionRepository.save(session);
        Map<String, String> labelsByName = new LinkedHashMap<>();
        byName.forEach((name, rp) -> labelsByName.put(name, rp.getLabel()));
        confirmedFacts.recordAnswers(factoryId, sessionId, actor.type() + ":" + actor.userId(),
                accepted, labelsByName);
        log.info("结构化答题写入 {} 项、暂时未决 {} 项: session={}, keys={}",
                accepted.size(), deferred.size(), sessionId, answers.keySet());

        return progress(factoryId, sessionId, actor);
    }

    /**
     * 「仅顺序」表达没有并行、分支、合流或返工，不能和复杂路线形态同时成立。
     * 前端会帮用户做互斥选择，但这里仍是写入前的权威校验。
     */
    private void validateProductRouteShapes(
            Map<String, String> accepted, List<String> problems) {
        accepted.forEach((key, value) -> {
            if (!key.matches("product\\d+\\.routeShape")) {
                return;
            }
            List<String> shapes = splitMulti(value, true);
            if (shapes.contains("仅顺序") && shapes.size() > 1) {
                problems.add(key + ": \"仅顺序\"不能与并行、分支、合流、返工或质检处置同时选择");
            }
        });
    }

    private void appendStructuredDecisionReceipt(
            FactoryAssemblyConversationSession session,
            Map<String, String> accepted,
            Set<String> deferred,
            Map<String, String> before,
            Set<String> previouslyDeferred,
            Map<String, FactoryAssemblyConversationSession.RequiredParameter> byName) {
        List<FactoryAssemblyConversationSession.ReplyFieldReceipt> fields = new ArrayList<>();
        StringJoiner message = new StringJoiner("；");
        boolean correctionApplied = false;
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            String previous = before.get(entry.getKey());
            correctionApplied |= previouslyDeferred.contains(entry.getKey())
                    || (previous != null && !Objects.equals(previous, entry.getValue()));
            FactoryAssemblyConversationSession.RequiredParameter parameter = byName.get(entry.getKey());
            message.add((parameter == null ? entry.getKey() : parameter.getLabel()) + "：" + entry.getValue());
            fields.add(FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                    .field(entry.getKey())
                    .resolution("ACCEPTED")
                    .canonicalValue(entry.getValue())
                    .reasonCode(previous == null && !previouslyDeferred.contains(entry.getKey())
                            ? "STRUCTURED_DECISION_ACCEPTED" : "STRUCTURED_DECISION_REPLACED")
                    .build());
        }
        for (String field : deferred) {
            correctionApplied |= before.containsKey(field);
            FactoryAssemblyConversationSession.RequiredParameter parameter = byName.get(field);
            message.add((parameter == null ? field : parameter.getLabel()) + "：暂时不确定");
            fields.add(FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                    .field(field)
                    .resolution("UNKNOWN")
                    .reasonCode("USER_DEFERRED_DECISION")
                    .build());
        }
        if (fields.isEmpty()) return;

        String outcome = deferred.isEmpty() ? "SUCCESS"
                : (accepted.isEmpty() ? "UNKNOWN" : "PARTIAL_SUCCESS");
        FactoryAssemblyConversationSession.ReplyReceipt receipt = FactoryAssemblyConversationSession.ReplyReceipt.builder()
                .extractionOutcome(outcome)
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus() == null ? null : session.getStatus().name())
                .completed(session.getStatus() == FactoryAssemblyConversationSession.SessionStatus.COMPLETED)
                .message("结构化决策已保存")
                .intentCode(session.getKnownIntentCode())
                .progressMutated(true)
                .correctionApplied(correctionApplied)
                .fieldReceipts(fields)
                .build();
        FactoryAssemblyConversationSession.Message userMessage = FactoryAssemblyConversationSession.Message.user(message.toString());
        userMessage.setReplyReceipt(receipt);
        session.addMessage(userMessage);
    }

    private OptionalInt boundedStepCount(String raw) {
        if (raw == null || !raw.matches("\\d+")) {
            return OptionalInt.empty();
        }
        try {
            int count = Integer.parseInt(raw);
            return count >= 1 && count <= FactoryInterviewExpansion.MAX_STEPS
                    ? OptionalInt.of(count) : OptionalInt.empty();
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    /**
     * 建厂需要问齐的事项。
     *
     * 这张表是「agent 会不会问」这件事的**唯一定义处** —— 收敛判据(还剩几项)直接由它算,
     * 不靠解析模型的措辞。⛔ 判据不能是「模型下一句话里有没有出现某关键词」:
     * 「工序」「单位」这类词几乎出现在任何一句工厂问句里, 那种断言用
     * 「⛔忽略清单, 只问厂长办公室在几楼」也照样能通过。
     *
     * 与一次性 `ai-initialize` 的关系: 收齐后把这些答案拼成 factoryDescription,
     * 交给**已有的** /ai-initialize → /apply 那条链, 不另起炉灶。
     */
    /**
     * 开场必答项 (访谈规格 v2 的 business + process.stepCount 段)。
     *
     * ⚠️ v1 这里是 5 个平铺的自由文本问题, 其中「工序链」只拿到一句
     *    「解冻、腌制、卤煮、真空包装」—— 每道工序**投入什么原料、什么辅料、
     *    什么包材、产出什么、损耗多少**一个都没问, 而 BOM / 用量推算 / 成本归集
     *    全靠这些。
     *
     * v2 把「工序链」换成 stepCount(工序道数), 客户答出 N 之后**动态展开 N×7 个子项**
     * (见 FactoryInterviewExpansion#expandIfReady)。
     */
    private static final List<FactoryAssemblyConversationService.RequiredParameter> FACTORY_BUILD_PARAMS = List.of(
            reqParam("businessType", "做什么产品", "这家厂主要加工什么？说说主打的一两个品类就行。"),
            choiceParam("industryHint", "行业归类", "属于下面哪一类？",
                    List.of("食品加工", "水产加工", "预制菜", "卤味熟食", "烘焙", "饮料", "其他")),
            // ⛔ countParam 不是 choiceParam:这一题的答案要被数出一个数来
            //    (见 countParam 的注释与 FactoryInterviewExpansion#countIn)。
            countParam("stepCount", "工序道数",
                    "从原料到成品一共几道工序？支持 1-30 道；常见数量可直接选，更多请填写准确数字。"
                            + "（下一步会逐道问投入、辅料、包材、产出）",
                    List.of("3", "4", "5", "6", "7", "8", "更多（1-30）")),
            // ⚠️ 2026-08-12 端到端实测抓出来的缺口: 这道题原文是
            //    「卖出去的成品有哪几种规格？分别按什么单位计量？」——
            //    问了单位, **从不问层级之间的换算系数**。客户答「按袋卖 / 按箱走」
            //    完全切题, 于是 SkuPackagingSpecParser 一条都解析不出来,
            //    生成端 conversionRates 恒为 0, product_unit_conversions 恒为空表。
            //    下游包装换算的能力是齐的(还专门修过三个缺陷), 建厂链路却从不采集这个数。
            // ⛔ 例子里的「一箱装20袋」是**给解析器看的句式**, 不是随手举的例 ——
            //    改这句话之前先看 FactoryBuildInterviewServiceTest#hintExampleMustBeParseable,
            //    那道闸会拿这句话里 「」 中的原文真的去跑一遍 SkuPackagingSpecParser。
            reqParam("productSkus", "成品规格",
                    "卖出去的成品有哪几种规格？分别按什么单位计量？"
                            + "按箱、袋这类包装单位卖的，请再补一句换算，像「一箱装20袋」这样说。"
                            + "不说这一句，系统就配不出包装换算。"),
            choiceParam("customerSupplied", "客供料",
                    "有没有客户供料、你们只收加工费的情况？",
                    List.of("没有", "有(部分)", "有(主要是代加工)")),
        // ⚠️ 2026-08-10 对抗性审计补的两问 —— 之前 product_work_processes / factory_warehouses
        //    这两张表访谈**一行都产不出来**, 而真实工厂(F006)分别有 16 行和 6 行。
        countParam("productCount", "成品个数", "一共做几个成品(SKU)？每个的工序链可能不一样",
                List.of("1", "2", "3", "4", "5", "6", "更多(说明)")),
        // 🔴 2026-08-16 / 08-17 两轮实测同一个缺口: 客户答「原料冷库 W1、车间暂存 W2、成品仓 W3」
        //    —— 三个, 系统只建了「成品仓」一个。另外两个名字系统认不出**属于哪一类仓**,
        //    而仓库类型编不得(编一个就是整次落库被拒, 而且库存会算到错的阶段上)。
        // ⇒ 不猜, 改成**教他顺手说一句**: 类别由他给, 系统只查表。
        //    括号里那几个类别就是仓库类型自己的中文含义(FactoryWarehouse.WarehouseType 的
        //    javadoc), ⛔ 不是我另起的一套词。
        choiceParam("warehouseSetup", "存放区",
                "货放在哪几个区？没有存放区就没法入库出库。"
                        + "仓名跟下面这几个不一样时，请在名字后面用括号带一句它属于哪类，"
                        + "像「原料冷库（原料）、车间暂存（暂存）」这样说 —— "
                        + "可选的类别：原料 / 半成品 / 成品 / 线边 / 暂存 / 质检 / 退货 / 废料。",
                List.of("按标准来(原料仓/半成品仓/成品仓)", "再加生产仓和物流仓", "我们自己列")),
        // S4 (owner 2026-10-27): 产能单位与数值「看具体产线情况, 按对话建厂或工厂配置时设置」。
        //    插单可行性只认与订单同单位的产线配置, 不换算、不套默认 ⇒ 单位必须由客户说出来。
        // ⛔ 只认 件 / kg(公斤、千克) / 批 —— 「斤」「吨」要换算, 换算就是替客户做主, 认不出就进「没建」清单。
        //    设备不在这里问: 建厂不建设备档案, 所需设备在建完厂后的「产线产能与设备」页里勾。
        choiceParam("lineCapacity", "产线产能",
                "有几条生产线？每条线叫什么、每小时能做多少、按什么算（件 / kg / 批）？"
                        + "像「卤制线 每小时 200 kg、包装线 每小时 1500 件」这样说。"
                        + "某条线做不同产品差得多时，可以单独说一句「卤制线 做卤牛肉 每小时 50 kg」。"
                        + "不说的话，插单时系统没法判断产能够不够。",
                List.of("先不配，建完厂在「产线产能与设备」里补", "我来说")),
        // ⚠️ 审计里最重的一条: 配完的工厂**没有任何人能登录**(F006 有 28 个账号, 新厂 0 个)。
        // ⛔ 提示语里原来写着「编码是按位切的, L1 三位」——「按位切」「L1」是我们内部的说法,
        //    客户没有义务懂(owner 2026-08-17: 建厂过程里不许出现程序员的话)。
        //    换成他能照着答的说法, 括号里那句「细分以后再补」是他真正需要知道的。
        choiceParam("materialCoding", "物料编码大类",
                "物料先分成哪几个大类？(先分个大类就行，具体到每样东西的编号，以后建物料时再补)",
                List.of("原料/包材/辅料", "只要原料和包材", "我们自己定")),
        // ⚠️ alert_rules 2026-08-11 实测**全局 0 行**(449 张表的库, 含 F006), 而告警引擎
        //    本身是好的 —— AlertEngineServiceImpl#triggerAlert 开头就是
        //    `if (rules.isEmpty()) return emptyList()`, 于是「事件驱动」与「定时兜底」
        //    两条路一起短路, 一条告警都产生不了。这道题补的就是那个入口。
        // ⛔ 只列**不需要金额阈值**的 6 个。「大额采购单 / 大额销售单」故意不在选项里:
        //    它们不填阈值就开 = 每张单子一条告警(内置逻辑里没有金额比较), 校验器会 400。
        //    多选题里没法顺带问一个数字, 所以那两个留到建完厂在提醒设置里开 ——
        //    这一点写进了 hint, 不是悄悄少给。
        // ⛔ 严重级别 / 通知角色 / 通知渠道**不问** —— 那是产品定义, 在 InterviewAlertCatalog 里。
        //    问客户「这个算 LOW 还是 MID」只会得到随手一填的答案。
        // ⚠️ alert_thresholds 0 行时 AnomalyDetectionScheduler -> detectAnomalies
        //    开头就是 `if (thresholds.isEmpty()) return 0` —— **定时器每次跑都遍历空表**,
        //    一条异常都检测不出, 只有一行 debug。与 alert_rules 修之前同型。
        // ⛔ 这道题**只能是自由文本**: 阈值要一个数字, 而 InterviewAnomalyMetricCatalog
        //    里刻意没有默认阈值 —— 「良品率低于 X%」的 X 是每家厂自己的数,
        //    编一个就是把一家的配置发给另一家。多选题装不下数字。
        // ⛔ 例句里的写法是**给解析器看的**(一段一个指标 + 一个数字):
        //    ai_proxy.py#_metric_watches 按分隔符切段, 跟不准的整段跳过 ——
        //    宁漏不错, 因为解析错一个数字 = 误报或漏报告警, 比不做更糟。
        // ⚠️ 方向(高于/低于)**不问** —— 在目录里, 客户只给数字。
        // 🔴 2026-08-17 实测: 客户答「产量、良品率」—— 一条都没配上。
        //    两个成因: ① 没给阈值(阈值驱动告警, 解析不出数字就整条跳过, 宁漏不错);
        //    ② **「产量」不在可盯的清单里**(清单里写的是「实际产量」), 于是连名字都没认出。
        // ⛔ 不给它加同义词表 —— 那正是 ChoiceAnswerMatcher 顶部反对的那件事
        //    (把「差不多」的说法归一到某一项 = 替客户猜)。
        // ⇒ 改成**把认得的名字全列给他**, 让他用系统认得的词说。同一条纪律的第二例
        //    (第一例是存放区: 类别由他给, 系统只查表)。
        // ⚠️ 这十个名字与 InterviewAnomalyMetricCatalog 的 label 逐字一致 ——
        //    那边是权威, 这里是把它念给客户听。哪天那边加一项, 这句要跟着加。
        optionalParam("metricWatches", "要盯哪些指标",
                // ⚠️ 我写这句时顺手打了一对 markdown 星号 —— 而这一整轮就是在删它们。
                //    客户读到的是纯文本气泡, 星号会照原样显示出来。
                "要盯哪几个生产指标？每个都要带上那条线（没有数字的配不出来）。"
                        + "照这样一条一条说：「良品率低于95%、不良品数超过20件、单位成本超过30元」。"
                        + "能盯的是这十个：良品率 / 不良品数 / 单位成本 / 批次总成本 / 生产效率 / "
                        + "实际产量 / 工时 / 人工成本 / 设备成本 / 物料成本。不盯就说不用。"),
        // ⛔ 这是**唯一**的减法入口(Steve 2026-08-14: 「只做加法不做减法,
        //    在 agent 建厂的时候才做减法」)—— 目录/指标/模块定义都全量给,
        //    客户在这里说哪些不要。
        //
        // ⚠️ 答错的代价比别的题重: apply 会同时写**两层** ——
        //    factory_feature_config(RN 藏屏幕)与 factory_module_configs(后端 403)。
        //    所以预览里那条会写明「后端 X 的接口也会一并拦截(403)」让平台管理员确认
        //    (FactoryBuildPlanService, 2026-08-13 补的)。
        //
        // ⛔ 选项**逐字**取自 InterviewModuleConfigCatalog 的 label ——
        //    读者(RN SCREEN_TO_MODULE / web-admin moduleDescriptions)只认那 14 个 moduleId,
        //    这里写错一个字, 落库的行永远不生效且不报错。
        //    python 侧 ai_proxy.py#_module_configs 的标签表由
        //    tests/test_factory_init_modules.py 直接解析那个 .java 逐条比对, 防两处口径漂。
        // 与正式建厂共用四项实际生产指标目录；停用只影响展示，不删除历史。
        multiChoiceParam("indicatorOptOut", "哪些指标不用看",
                "制造新厂默认提供" + String.join("、", com.cretas.aims.service.indicator.FactoryIndicatorProvisioner.defaultNames())
                        + "。没有实际业务时显示暂无数据。哪些不用看？都要看就说都要。",
                java.util.stream.Stream.concat(
                        com.cretas.aims.service.indicator.FactoryIndicatorProvisioner.defaultNames().stream(),
                        java.util.stream.Stream.of("都要看")).toList()),
        multiChoiceParam("moduleOptOut", "哪些功能用不上",
                "这套系统有 14 块功能，哪些你们用不上？用不上的说出来，系统就不给你们开：生产、仓储、质检、调度、人事、设备、财务、智能BI、采购、销售、物流、溯源、系统、餐饮。都要就说都要。",
                List.of("生产", "仓储", "质检", "调度", "人事", "设备", "财务",
                        "智能BI", "采购", "销售", "物流", "溯源", "系统", "餐饮",
                        "都要")),
        multiChoiceParam("alerts", "要开哪些提醒",
                "出问题时想让系统提醒你哪些事？",
                List.of("库存不足", "库存临期", "质检异常",
                        "销售额下滑", "客户回款逾期", "供应商付款到期",
                        "都不用")),
        choiceParam("createAccounts", "登录账号",
                "要不要先建一套账号？(超管/车间主管/操作员各一个, 初始密码随机, 你重置后再交给客户)",
                List.of("建", "先不建, 我们自己加")),
        // ⚠️ 2026-08-15 段② 判据一实测补的:canvas 的「食品安全」板块存 haccp_checkpoints,
        //    而建厂访谈里**一个词都搜不到** —— 客户配完工厂,关键控制点一条都没有。
        //    owner 裁定这是真缺口(食品加工厂的核心经营配置,每家不同,访谈里问得出来)。
        multiChoiceParam("haccpPoints", "关键控制点",
                "哪几道工序是关键控制点(CCP)？比如杀菌、金检、冷却。",
                List.of("杀菌", "金属检测", "冷却", "解冻", "我们自己列", "暂不设")),
        // 🔴 措辞里那句「系统不会替你编一个限值」是**硬约束的用户可见面**,不是客套话:
        //    ① prompt 里有一条对应的「⛔ 临界限值不许编」,由
        //       scripts/tests/check-model-prompt-constraints.py 钉住(下界 7)
        //    ② haccp_checkpoints 的 critical_limit_min/max/unit 三列都是
        //       **nullable = false** —— 所以「留空」在库里根本做不到,
        //       没给限值的控制点是**整条不写**,并在预览与结果里逐条列出来。
        //    ⚠️ 我最初的措辞写的是「这一项留空」, 那是错的:留空插不进去。
        optionalParam("haccpLimits", "临界限值",
                "上面每个控制点的临界限值是多少？像「中心温度≥75℃」「金检 Fe≥2.0mm 剔除」这样说。"
                        + "没说限值的控制点系统不会写入 —— 它不会替你编一个限值。"));

    private static final String STEP_TEMPLATE_KEY = "step";

    private static final String STEP_COUNT_KEY = "stepCount";

    /**
     * 供闸读那道题的原文 —— 与 {@code FactoryInterviewExpansion#subItemCount()} 同一个用途。
     *
     * <p>⛔ 存在的理由: 「问什么」和「能解析出什么」是<b>两个不同的作者</b>写的
     * (一个是给人看的措辞, 一个是正则)。它们必须能被同一道闸对上,
     * 否则改措辞的人不会知道自己顺手废掉了包装换算 —— 2026-08-12 之前就是这个状态。
     */
    static String hintOf(String paramName) {
        FactoryAssemblyConversationService.RequiredParameter parameter = parameterOf(paramName);
        return parameter == null ? null : parameter.getValidationHint();
    }

    /** 只供同包契约测试读取题型；返回静态不可变题面中的对象，不给运行时会话写入。 */
    static FactoryAssemblyConversationService.RequiredParameter parameterOf(String paramName) {
        return FACTORY_BUILD_PARAMS.stream()
                .filter(p -> p.getName().equals(paramName))
                .findFirst()
                .orElse(null);
    }

    private static FactoryAssemblyConversationService.RequiredParameter reqParam(String name, String label, String hint) {
        return FactoryAssemblyConversationService.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("NARRATIVE").collected(false).build();
    }

    /** 可枚举的题 —— ⛔ 走结构化端点, 不经 LLM 抽取(抽取会把「差不多」归一到某个选项)。 */
    private static FactoryAssemblyConversationService.RequiredParameter choiceParam(
            String name, String label, String hint, List<String> options) {
        return FactoryAssemblyConversationService.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("CHOICE").options(options).collected(false).build();
    }

    /**
     * 计数题(工序道数 / 成品个数)—— {@code type="number"}。
     *
     * <h2>🔴 为什么这一格不能是 "string"(2026-08-16 本地起服务实测)</h2>
     *
     * 抽取提示词会把 {@code type} 原样打给模型。标成 {@code string} 时,模型对
     * 「3 道：解冻、冻干、包装」抽出来的是 <b>{@code "解冻、冻干、包装"}</b> ——
     * <b>数字被丢掉了</b>,而客户明明说了。
     *
     * <p>后果不是少一个字:{@code FactoryInterviewExpansion} 从这个值里数不出数
     * ⇒ 逐道工序的 8 个子项<b>一次都不会问</b>,而访谈照样报告「问齐了」。
     *
     * <p>⚠️ 标成 number 之后还有第二半:提示词里要有一条<b>按类型</b>的规则
     * (「number 类型只能填阿拉伯数字」),否则类型只是打印出来没人管。
     * 那一条在 {@code FactoryAssemblyConversationServiceImpl#buildParameterExtractionPrompt}。
     * ⛔ 两半缺一不可 —— 只改这里等于没改。
     *
     * <p>📌 前端不按 {@code type} 分支渲染(核过 factory-onboarding/index.vue),
     * 所以这一改不影响客户看到的题目形状。
     */
    private static FactoryAssemblyConversationService.RequiredParameter countParam(
            String name, String label, String hint, List<String> options) {
        return FactoryAssemblyConversationService.RequiredParameter.builder()
                .name(name).label(label).type("number").validationHint(hint)
                .inputType("CHOICE").options(options).collected(false).build();
    }

    /** 可多选的枚举题 —— 与 choiceParam 同一条纪律(能枚举的不要用问答), 只是允许勾多个。 */
    private static FactoryAssemblyConversationService.RequiredParameter multiChoiceParam(
            String name, String label, String hint, List<String> options) {
        return FactoryAssemblyConversationService.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("CHOICE").options(options).multiSelect(true).collected(false).build();
    }

    /** 允许「没有」的项 —— 辅料/包材/副产物这类, 答「没有」是有效答案。 */
    private static FactoryAssemblyConversationService.RequiredParameter optionalParam(
            String name, String label, String hint) {
        return FactoryAssemblyConversationService.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("NARRATIVE").allowNone(true).collected(false).build();
    }

    private static final String FACTORY_BUILD_PARAMS_INTENT = FactoryInterviewExpansion.INTENT;

    /**
     * 把收齐的答案拼成给生成器看的描述。
     *
     * ⛔ 必须遍历<b>会话里真实的参数表</b>, 不能遍历静态的 {@code FACTORY_BUILD_PARAMS} ——
     *    后者只有开场那 5 项, 逐工序展开出来的 N×7 条答案(辅料/包材/工艺参数/出成率)
     *    一条都不在里面。
     *
     *    2026-08-10 实测过这个形状: 访谈老老实实问齐了 19 项、库里 19 条答案都在,
     *    拼出来的描述却只有 65 字、只含最初 5 项 —— <b>问到了然后丢掉</b>。
     *    而且它不报错: 描述非空、readyForGenerate=true、闸原先的「长度 ≥20 字」也照过。
     *    判据: 拼装类代码要贴着<b>实际收集到的那份</b>遍历, 别贴着定义时的那份。
     *
     * <p>工序子项按道次分组, 让生成器一眼看出「第几道·投入什么·加什么辅料」,
     * 而不是收到 14 行扁平的 {@code step2.auxMaterials：…}。
     */
    private String assembleDescription(FactoryAssemblyConversationSession session, Map<String, String> collected) {
        StringBuilder base = new StringBuilder();
        // 道次 -> (子项标签 -> 值); TreeMap 保证第 1 道排在第 2 道前面
        TreeMap<Integer, LinkedHashMap<String, String>> steps = new TreeMap<>();
        // 第几个成品 -> (子项标签 -> 值); 与工序<b>各用各的 TreeMap</b>, 见下面注释
        TreeMap<Integer, LinkedHashMap<String, String>> products = new TreeMap<>();

        for (FactoryAssemblyConversationSession.RequiredParameter rp : session.getRequiredParameters()) {
            String v = collected.get(rp.getName());
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            Integer idx = rp.getRepeatIndex();
            if (idx != null && idx > 0) {
                // 展开项的 label 形如「第2道·辅料」, 分组后只留「辅料」
                String label = rp.getLabel() == null ? rp.getName() : rp.getLabel();
                int dot = label.indexOf('·');
                if (dot >= 0 && dot + 1 < label.length()) {
                    label = label.substring(dot + 1);
                }
                repeatBucket(rp.getName(), steps, products)
                        .computeIfAbsent(idx, k -> new LinkedHashMap<>()).put(label, v.trim());
            } else {
                base.append(rp.getLabel()).append("：").append(v.trim()).append("\n");
            }
        }

        StringBuilder sb = new StringBuilder(base);
        appendRepeatSection(sb, "工序清单", "道", "工序名称", steps);
        appendRepeatSection(sb, "成品清单", "个", "成品名称", products);
        sb.append(packagingSection(session.getFactoryId(), collected.get(PRODUCT_SKUS_PARAM)));
        return sb.toString().trim();
    }

    /**
     * 展开项归到哪个重复段 —— ⛔ 按<b>参数名的前缀</b>分, 不能只按 {@code repeatIndex} 分。
     *
     * <h3>被治的那个缺陷</h3>
     *
     * 两个重复段<b>各自从 1 开始编号</b>: {@code step1.*}(第 1 道工序) 与
     * {@code product1.*}(第 1 个成品) 的 {@code repeatIndex} 都是 {@code 1}
     * (见 {@link FactoryAssemblyConversationSession#expandRepeat})。原先只按 repeatIndex 分桶,
     * 于是它们落进<b>同一个桶</b>, 后果两条且都不报错:
     * <ul>
     *   <li>工序清单里出现 {@code 第1道 … 成品名称：带鱼段} —— 成品名成了这道工序的一个属性</li>
     *   <li>道数被撑成 {@code max(工序数, 成品数)}: 2 道工序 + 3 个成品 → 「共 3 道」,
     *       而多出来的那一「道」连工序名都没有</li>
     * </ul>
     * 而 Python 侧的提示词写着「描述里给了工序清单才输出 workProcesses」——
     * 模型读的正是这份被污染的清单。
     *
     * <p>⛔ 修法是<b>分段</b>不是<b>丢弃</b>: 成品答案仍在描述里(单独一段),
     * 「问到了然后丢掉」这个形状本仓已经栽过一次。
     *
     * <p>⚠️ 只把 {@code product<数字>.} 分出去, 其余带 repeatIndex 的一律仍归工序 ——
     * 与改动前逐字一致, 这样这次改动的行为差异<b>只有成品这一处</b>。
     */
    private static TreeMap<Integer, LinkedHashMap<String, String>> repeatBucket(
            String paramName,
            TreeMap<Integer, LinkedHashMap<String, String>> steps,
            TreeMap<Integer, LinkedHashMap<String, String>> products) {
        return paramName != null && PRODUCT_REPEAT_ITEM.matcher(paramName).find() ? products : steps;
    }

    /** {@code product1.name} 这一形 —— ⛔ 前缀取自展开器, 别在这里手写第二份。 */
    private static final java.util.regex.Pattern PRODUCT_REPEAT_ITEM =
            java.util.regex.Pattern.compile(
                    "^" + FactoryInterviewExpansion.PRODUCT_TEMPLATE_KEY + "\\d+\\.");

    /**
     * 渲染一个重复段。工序段的输出与改动前<b>逐字一致</b>
     * (title=工序清单 / unitWord=道 / nameLabel=工序名称), 成品段沿用同一形状。
     */
    private static void appendRepeatSection(
            StringBuilder sb, String title, String unitWord, String nameLabel,
            TreeMap<Integer, LinkedHashMap<String, String>> group) {
        if (group.isEmpty()) {
            return;
        }
        sb.append("\n").append(title).append("（共 ").append(group.size())
          .append(" ").append(unitWord).append("）：\n");
        for (Map.Entry<Integer, LinkedHashMap<String, String>> e : group.entrySet()) {
            LinkedHashMap<String, String> one = e.getValue();
            String name = one.remove(nameLabel);
            sb.append("  第").append(e.getKey()).append(unitWord);
            if (name != null) {
                sb.append(" ").append(name);
            }
            sb.append("\n");
            for (Map.Entry<String, String> f : one.entrySet()) {
                sb.append("    ").append(f.getKey()).append("：").append(f.getValue()).append("\n");
            }
        }
    }

    /** 访谈里问包装规格的那道题的参数名 —— 与 {@code reqParam("productSkus", "成品规格", …)} 同一个。 */
    private static final String PRODUCT_SKUS_PARAM = "productSkus";

    /**
     * 把「成品规格」这一答里的包装换算<b>摆成结构化的一段</b>再喂给模型。
     *
     * <p>⚠️ 在这之前这句话只以「成品规格：<原文>」的形式出现在自由文本里, 而生成端的
     * productTypes schema 里没有 level1Unit / boxConversionCoefficient 两个位置 ——
     * 客户说的「一箱20斤」从头到尾没有一层把它变成换算边(实测 product_unit_conversions 空表)。
     *
     * <p>⛔ 这一段<b>不替客户编</b>: 解析不出来(单位不在权威表里、系数 ≤0、两端同单位)就
     * 一个字都不加, 原文那行仍然在 —— 模型照样看得见, 只是没有被预处理过。
     */
    private String packagingSection(String factoryId, String productSkusAnswer) {
        return SkuPackagingSpecParser.renderSection(SkuPackagingSpecParser.parse(
                productSkusAnswer,
                raw -> {
                    UnitNormalizationResult r = unitContractService.normalize(factoryId, raw);
                    return r.recognized() ? r.code() : null;
                }));
    }

    /** 勾选题可能传数组; 统一成以「、」分隔的串, 与自由文本口径一致。 */
    private String stringifyAnswer(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            List<String> parts = new ArrayList<>();
            for (Object o : l) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) {
                    parts.add(String.valueOf(o).trim());
                }
            }
            return String.join("、", parts);
        }
        return String.valueOf(v);
    }

    private List<String> splitMulti(String val, boolean multi) {
        if (!multi) {
            return List.of(val.trim());
        }
        List<String> out = new ArrayList<>();
        for (String p : val.split("[、,，]")) {
            if (!p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out.isEmpty() ? List.of(val.trim()) : out;
    }

    private boolean isNoneAnswer(String s) {
        String t = s.trim();
        return t.equals("没有") || t.equals("无") || t.equals("不适用") || t.equalsIgnoreCase("none");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

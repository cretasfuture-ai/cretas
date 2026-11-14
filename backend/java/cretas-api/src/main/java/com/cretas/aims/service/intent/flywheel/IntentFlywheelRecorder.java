package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.Entry;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.InputKind;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.SecrecyMode;
import com.cretas.aims.repository.intent.IntentFlywheelTurnRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 意图飞轮 (INTENT-DISTILL-20261111): 线上 AI 对话每一轮写一条 {@code ai_intent_flywheel_turns},
 * 并用下一轮回填上一轮的用户反应 ({@link FlywheelReactionRules})。
 *
 * <p>唯一写入点: 普通入口 {@code IntentExecutorServiceImpl.execute} / 确认入口 {@code confirm},
 * 流式入口 {@code SseStreamingService.executeStream}。Python 决策层不写库, 它的决策痕迹随识别结果带回
 * ({@code IntentMatchResult.decisionTrace}), 统一在这里落 —— 一处写, 不两份漂。</p>
 *
 * <p>写入在单线程后台队列上做 (脱敏要查名单, 不占用户请求的时间), 同一进程内按到达顺序写, 反应回填看得到上一轮。
 * 写失败 / 队列满<b>不影响用户请求, 但不静默</b>: WARN 日志 + 计数 {@code cretas.ai.flywheel.turns{result}}
 * (recorded / failed / dropped)。保密厂原文没留 (没配密钥) 计 {@code cretas.ai.flywheel.raw_not_kept{reason}}。</p>
 */
@Slf4j
@Component
public class IntentFlywheelRecorder {

    static final String METRIC_TURNS = "cretas.ai.flywheel.turns";
    static final String METRIC_RAW_NOT_KEPT = "cretas.ai.flywheel.raw_not_kept";
    static final String METRIC_REACTIONS = "cretas.ai.flywheel.reactions";
    static final int MAX_TEXT = 2000;

    private final IntentFlywheelTurnRepository repository;
    private final FlywheelTextProtector protector;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), runnable -> {
                Thread thread = new Thread(runnable, "intent-flywheel");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    @Value("${cretas.ai.flywheel.enabled:true}")
    private boolean enabled = true;

    /** 测试用: 直接在调用线程上写 (生产为 false)。 */
    private boolean synchronous;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public IntentFlywheelRecorder(IntentFlywheelTurnRepository repository, FlywheelTextProtector protector,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.protector = protector;
        this.objectMapper = objectMapper;
        executor.allowCoreThreadTimeOut(true);
    }

    void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long recordedCount() { return recorded.get(); }

    public long failedCount() { return failed.get(); }

    public long droppedCount() { return dropped.get(); }

    /** 一轮在本线程上要记的东西 (入口线程上取快照, 后台线程上处理)。 */
    record Draft(Entry entry, String factoryId, Long userId, String sessionId, String userInput,
                 String requestedIntentCode, FlywheelDecisionAnalysis.Decision decision, String finalStatus,
                 String responseIntentCode, String confirmDigest, long latencyMs, LocalDateTime at) {
    }

    /** 入口调用; 从不抛。 */
    public void recordTurn(Entry entry, String factoryId, Long userId, IntentExecuteRequest request,
                           IntentExecuteResponse response, IntentFlywheelTurnContext.Turn turn, long latencyMs) {
        if (!enabled) {
            return;
        }
        Draft draft;
        try {
            if (factoryId == null || factoryId.isBlank() || request == null) {
                return;     // 没有工厂就没有可判的保密开关, 也不知道归谁: 不记 (不是失败)
            }
            String requested = blankToNull(request.getIntentCode());
            String sessionId = blankToNull(request.getSessionId());
            if (sessionId == null && response != null) sessionId = blankToNull(response.getSessionId());
            String digest = response == null || response.getConfirmableAction() == null ? null
                    : blankToNull(response.getConfirmableAction().getCommandDigest());
            draft = new Draft(entry, factoryId, userId, sessionId, request.getUserInput(), requested,
                    FlywheelDecisionAnalysis.analyse(turn, requested),
                    response == null || response.getStatus() == null ? "UNKNOWN" : response.getStatus(),
                    response == null ? null : blankToNull(response.getIntentCode()),
                    digest, Math.max(0, latencyMs), LocalDateTime.now());
        } catch (RuntimeException e) {
            count("failed");
            failed.incrementAndGet();
            log.warn("[flywheel] 准备记录失败 (不影响回答): factory={}, reason={}", factoryId, e.toString());
            return;
        }
        submit(() -> persist(draft), factoryId);
    }

    /** 确认入口: 用户点了确认卡 → 出这张卡的那一轮记 CONFIRMED。从不抛。 */
    public void recordConfirm(String factoryId, String commandDigest) {
        if (!enabled || factoryId == null || commandDigest == null || commandDigest.isBlank()) {
            return;
        }
        submit(() -> {
            repository.findFirstByFactoryIdAndConfirmDigestAndReactionIsNullOrderByIdDesc(factoryId, commandDigest)
                    .ifPresent(t -> {
                        t.setReaction(IntentFlywheelTurn.Reaction.CONFIRMED);
                        t.setReactedAt(LocalDateTime.now());
                        repository.save(t);
                        countReaction(IntentFlywheelTurn.Reaction.CONFIRMED);
                    });
        }, factoryId);
    }

    private void submit(Runnable work, String factoryId) {
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                count("failed");
                log.warn("[flywheel] 写记录失败 (不影响回答): factory={}, reason={}", factoryId, e.toString());
            }
        };
        if (synchronous) {
            guarded.run();
            return;
        }
        try {
            executor.execute(guarded);
        } catch (RejectedExecutionException e) {
            dropped.incrementAndGet();
            count("dropped");
            log.warn("[flywheel] 队列满, 本轮记录丢弃: factory={}", factoryId);
        }
    }

    void persist(Draft d) {
        List<IntentFlywheelTurn> recent = d.sessionId() == null || d.userId() == null ? List.of()
                : repository.findTop3ByFactoryIdAndUserIdAndSessionIdAndCreatedAtAfterOrderByIdDesc(
                        d.factoryId(), d.userId(), d.sessionId(), d.at().minus(FlywheelReactionRules.WINDOW));

        List<String> candidateNames = new ArrayList<>();
        for (Map<String, Object> c : d.decision().candidates()) {
            candidateNames.add(c.get("name") == null ? "" : String.valueOf(c.get("name")));
        }
        FlywheelTextProtector.Protected p = protector.protect(d.factoryId(), clip(d.userInput()), candidateNames);

        IntentFlywheelTurn t = new IntentFlywheelTurn();
        t.setTurnId(UUID.randomUUID().toString());
        t.setFactoryId(d.factoryId());
        t.setUserId(d.userId());
        t.setSessionId(d.sessionId());
        t.setEntry(d.entry());
        t.setInputKind(d.requestedIntentCode() == null ? InputKind.TEXT : InputKind.BUTTON);
        t.setCatalogVersion(FlywheelDecisionAnalysis.CATALOG_VERSION);
        t.setOptionSetHash(clip(d.decision().optionSetHash(), 80));
        t.setSecrecyMode(p.mode());
        t.setUserInput(p.rawInput());
        t.setUserInputExport(p.exportInput());
        t.setUserInputCipher(p.inputCipher());
        if (p.mode() == SecrecyMode.PLAIN) {
            t.setContextSummary(contextSummary(recent, false));
        }
        if (p.mode() != SecrecyMode.TEXT_DROPPED) {
            t.setContextExport(contextSummary(recent, true));
        }
        t.setRequestedIntentCode(clip(d.requestedIntentCode(), 100));
        t.setDecisionSource(d.decision().source());
        t.setDecisionPath(json(d.decision().path()));
        t.setDecisionAction(d.decision().action());
        t.setDecidedIntentCode(clip(d.decision().intentCode(), 100));
        t.setConfidence(d.decision().confidence() == null || d.decision().confidence().isNaN() ? null
                : BigDecimal.valueOf(Math.max(0, Math.min(1, d.decision().confidence()))).setScale(4, RoundingMode.HALF_UP));
        t.setCandidatesJson(candidatesJson(d.decision().candidates(), p));
        t.setFinalStatus(clip(d.finalStatus(), 40));
        t.setResponseIntentCode(clip(d.responseIntentCode(), 100));
        t.setConfirmDigest(clip(d.confirmDigest(), 128));
        t.setLatencyMs((int) Math.min(Integer.MAX_VALUE, d.latencyMs()));
        t.setCreatedAt(d.at());
        repository.save(t);
        recorded.incrementAndGet();
        count("recorded");
        if (p.rawNotKeptReason() != null && meterRegistry != null) {
            meterRegistry.counter(METRIC_RAW_NOT_KEPT, "reason", p.rawNotKeptReason()).increment();
        }

        // 回填上一轮的反应 (同会话、窗口内、还没判过的那一轮)
        if (!recent.isEmpty()) {
            IntentFlywheelTurn prev = recent.get(0);
            if (prev.getReaction() == null) {
                FlywheelReactionRules.Verdict v = FlywheelReactionRules.judge(prev, candidateCodes(prev), t);
                if (v != null) {
                    prev.setReaction(v.reaction());
                    prev.setReactionOptionRank(v.optionRank());
                    prev.setReactionTurnId(t.getTurnId());
                    prev.setReactedAt(d.at());
                    repository.save(prev);
                    countReaction(v.reaction());
                }
            }
        }
    }

    /** 最近两轮 (旧的在前): 「[意图|状态] 话」。导出版用各轮的导出文本, 原文版用原文。 */
    private static String contextSummary(List<IntentFlywheelTurn> recentNewestFirst, boolean export) {
        if (recentNewestFirst.isEmpty()) {
            return null;
        }
        List<IntentFlywheelTurn> two = new ArrayList<>(recentNewestFirst.subList(0, Math.min(2, recentNewestFirst.size())));
        Collections.reverse(two);
        StringBuilder sb = new StringBuilder();
        for (IntentFlywheelTurn prev : two) {
            String text = export ? prev.getUserInputExport() : prev.getUserInput();
            String intent = prev.getResponseIntentCode() != null ? prev.getResponseIntentCode() : prev.getDecidedIntentCode();
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(intent == null ? "-" : intent).append('|').append(prev.getFinalStatus()).append("] ")
                    .append(text == null ? "<无文本>" : text);
        }
        return clip(sb.toString());
    }

    private String candidatesJson(List<Map<String, Object>> candidates, FlywheelTextProtector.Protected p) {
        if (candidates.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(candidates.get(i));
            if (p.mode() != SecrecyMode.PLAIN) {
                m.put("name", i < p.exportCandidateNames().size() ? p.exportCandidateNames().get(i) : null);
            }
            out.add(m);
        }
        return json(out);
    }

    private List<String> candidateCodes(IntentFlywheelTurn prev) {
        List<String> codes = new ArrayList<>();
        if (prev.getCandidatesJson() == null) {
            return codes;
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(prev.getCandidatesJson(), new TypeReference<>() { });
            for (Map<String, Object> m : list) {
                if (m.get("code") != null) codes.add(String.valueOf(m.get("code")));
            }
        } catch (Exception e) {
            log.debug("[flywheel] candidates_json unreadable: {}", e.toString());
        }
        return codes;
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private void count(String result) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(METRIC_TURNS, "result", result).increment();
            } catch (RuntimeException ignored) {
                // 计数失败不影响记录
            }
        }
    }

    private void countReaction(IntentFlywheelTurn.Reaction r) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(METRIC_REACTIONS, "reaction", r.name()).increment();
            } catch (RuntimeException ignored) {
                // 计数失败不影响记录
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s) {
        return clip(s, MAX_TEXT);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

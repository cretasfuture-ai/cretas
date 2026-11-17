package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.service.execution.MultiIntentExecutionService;
import com.cretas.aims.service.execution.SseStreamingService;
import com.cretas.aims.service.execution.StreamTurnCollector;
import com.cretas.aims.service.execution.DemoConversationScope;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.agentops.FactoryAgentTraceRecorder;
import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import com.cretas.aims.service.intent.flywheel.IntentFlywheelRecorder;
import com.cretas.aims.service.intent.flywheel.IntentFlywheelTurnContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI意图执行服务实现 — Facade
 *
 * 保持 {@link IntentExecutorService} 接口完全不变，
 * 将实际工作委托给 5 个专注子服务：
 * <ul>
 *   <li>{@link IntentExecutionOrchestrator} — 核心 execute() 路由</li>
 *   <li>{@link com.cretas.aims.service.execution.ToolDispatchService} — 直接 Tool 执行</li>
 *   <li>{@link com.cretas.aims.service.execution.DynamicToolSelectionService} — 动态 Tool 选择 + Skill 路由</li>
 *   <li>{@link SseStreamingService} — SSE 流式执行</li>
 *   <li>{@link MultiIntentExecutionService} — 多意图拆分/合并</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-01-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentExecutorServiceImpl implements IntentExecutorService {

    private final IntentExecutionOrchestrator orchestrator;
    private final SseStreamingService sseStreamingService;
    private final MultiIntentExecutionService multiIntentExecutionService;
    private final ConversationMemoryService conversationMemoryService;

    /** Optional so partial contexts / unit constructions keep working; null means "do not trace". */
    @Autowired(required = false)
    private FactoryAgentTraceRecorder factoryTraceRecorder;

    /**
     * /execute 走流式管线的适配层 (PR-2b; PR-3 起识别 / 执行只有这一条管线, 回退杆已删)。
     * null 只出现在部分上下文 / 单测直接构造: 那时只有带意图码的显式路径可用 (见 {@link #runTurn})。
     */
    @Autowired(required = false)
    private StreamTurnCollector streamTurnCollector;

    /** 意图飞轮 (INTENT-DISTILL-20261111): 每轮一条记录; null = 不记 (部分上下文 / 单测直接构造)。 */
    @Autowired(required = false)
    private IntentFlywheelRecorder flywheelRecorder;

    @Override
    public IntentExecuteResponse execute(String factoryId, IntentExecuteRequest request,
                                         Long userId, String userRole) {
        long startedNanos = System.nanoTime();
        IntentExecuteResponse response;
        try (IntentFlywheelTurnContext.Scope flywheel = IntentFlywheelTurnContext.open()) {
            response = runTurn(factoryId, request, userId, userRole);
            // 飞轮 entry 仍记 NORMAL (两条路都是普通入口)
            recordFlywheel(factoryId, userId, request, response, flywheel.turn(), startedNanos);
        }
        populateConversationRound(factoryId, userId, request, response);
        recordFactoryTrace(factoryId, userId, userRole, response, startedNanos);
        return response;
    }

    /**
     * 一轮 = 流式管线 (同线程收集最后一个 result, 见 {@link StreamTurnCollector});
     * 只有带显式意图码的请求走编排器的显式意图路径 (预览 / 确认共用的写确认收敛点)。
     */
    private IntentExecuteResponse runTurn(String factoryId, IntentExecuteRequest request, Long userId, String userRole) {
        StreamTurnCollector.Route route = streamTurnCollector == null
                ? StreamTurnCollector.Route.EXPLICIT_INTENT
                : streamTurnCollector.route(request);
        log.debug("[execute] route={} factoryId={}", route, factoryId);
        return route.viaStream()
                ? streamTurnCollector.collect(factoryId, request, userId, userRole)
                : orchestrator.execute(factoryId, request, userId, userRole);
    }

    private void recordFlywheel(String factoryId, Long userId, IntentExecuteRequest request,
                                IntentExecuteResponse response, IntentFlywheelTurnContext.Turn turn,
                                long startedNanos) {
        if (flywheelRecorder == null || (request != null && Boolean.TRUE.equals(request.getPreviewOnly()))) {
            return;
        }
        flywheelRecorder.recordTurn(IntentFlywheelTurn.Entry.NORMAL, factoryId, userId, request, response, turn,
                (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** S6-07: every real factory run leaves a trace; failures never change the answer. */
    private void recordFactoryTrace(String factoryId, Long userId, String userRole,
                                    IntentExecuteResponse response, long startedNanos) {
        if (factoryTraceRecorder == null) {
            return;
        }
        factoryTraceRecorder.record(factoryId, userId, userRole, response,
                (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    @Override
    public IntentExecuteResponse preview(String factoryId, IntentExecuteRequest request,
                                         Long userId, String userRole) {
        request.setPreviewOnly(true);
        // 带意图码 (按钮 / 数字班组): 编排器显式意图路径的预览; 不带: 流式管线识别后按 previewOnly 只预览 (PR-3)
        IntentExecuteResponse response = runTurn(factoryId, request, userId, userRole);
        populateConversationRound(factoryId, userId, request, response);
        return response;
    }

    /**
     * X1 Part A — populate {@code conversationRound} on the response.
     *
     * <p>The DTO field {@link IntentExecuteResponse#getConversationRound()} existed but was never
     * populated, so every multi-turn response reported a null round. This single facade chokepoint
     * fills it for ALL execution paths (success / clarification / no-match / conversational) because
     * the orchestrator has many early-return sites; populating here avoids touching every one.
     *
     * <p>Round is derived from the persisted conversation message count
     * ({@code (messageCount + 1) / 2}, 1-based, clamped to ≥ 1 once a session exists). After the
     * orchestrator's success path persists the current turn's user+assistant messages, messageCount
     * is even and the formula yields the current round; on early-return paths (no persist yet) it
     * yields the prior round — both are non-null and sane.
     *
     * <p>Fail-open: never crashes the response if the memory store is unavailable; the round simply
     * stays null. An explicit non-null round already set upstream is preserved.
     */
    private void populateConversationRound(String factoryId, Long userId,
                                            IntentExecuteRequest request, IntentExecuteResponse response) {
        if (response == null || request == null) {
            return;
        }
        if (response.getConversationRound() != null) {
            return; // upstream already populated it — don't override
        }
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return; // standalone (sessionless) query — no round to report
        }
        if (AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> java.util.Objects.equals(actor.id(), userId))
                .filter(actor -> java.util.Objects.equals(actor.factoryId(), factoryId))
                .isEmpty()
                && !DemoConversationScope.allows(factoryId, userId, sessionId)) {
            return; // only a trusted factory actor may read a session round
        }
        try {
            ConversationContext context = conversationMemoryService.getContext(sessionId);
            if (context == null
                    || !java.util.Objects.equals(context.getSessionId(), sessionId)
                    || !java.util.Objects.equals(context.getFactoryId(), factoryId)
                    || !java.util.Objects.equals(context.getUserId(), userId)) {
                return;
            }
            int messageCount = context.getMessageCount();
            int round = Math.max(1, (messageCount + 1) / 2);
            response.setConversationRound(round);
        } catch (Exception e) {
            log.warn("populateConversationRound failed (fail-open, round left null): sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    @Override
    public IntentExecuteResponse confirm(String factoryId, ConfirmationProof confirmationProof,
                                          Long userId, String userRole) {
        long startedNanos = System.nanoTime();
        IntentExecuteResponse response = orchestrator.confirm(factoryId, confirmationProof, userId, userRole);
        // 只在确认后真的办成了才回填 CONFIRMED (失败 / 过期 / 被拦的不算)
        if (flywheelRecorder != null && confirmationProof != null && response != null
                && IntentFlywheelRecorder.CONFIRMED_STATUSES.contains(response.getStatus())) {
            flywheelRecorder.recordConfirm(factoryId, confirmationProof.commandDigest());
        }
        recordFactoryTrace(factoryId, userId, userRole, response, startedNanos);
        return response;
    }

    @Override
    public SseEmitter executeStream(String factoryId, IntentExecuteRequest request,
                                     Long userId, String userRole) {
        return sseStreamingService.executeStream(factoryId, request, userId, userRole);
    }

    @Override
    public IntentExecuteResponse executeMultiIntent(String factoryId, IntentExecuteRequest request,
                                                     Long userId, String userRole) {
        long startedNanos = System.nanoTime();
        try (IntentFlywheelTurnContext.Scope flywheel = IntentFlywheelTurnContext.open()) {
            IntentExecuteResponse response = multiIntentExecutionService.executeMultiIntent(factoryId, request, userId, userRole);
            recordFlywheel(factoryId, userId, request, response, flywheel.turn(), startedNanos);
            return response;
        }
    }
}

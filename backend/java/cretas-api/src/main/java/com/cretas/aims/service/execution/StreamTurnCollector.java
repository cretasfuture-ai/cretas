package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

/**
 * 普通入口 {@code /execute} 的薄适配层 (意图蒸馏第③步 PR-2b / PR-3): 在当前线程上跑一整轮流式管线
 * ({@link SseStreamingService}), 取最后一个 result 当作普通入口的回答。HTTP 层的信封
 * ({@code envelopeByInnerStatus}) 与遮价 ({@code PriceFieldResponseAdvice}) 照常作用在这个回答上;
 * 飞轮 / trace 仍由门面按 NORMAL 记 (本类不经流式的工作线程, 不记 STREAM)。
 *
 * <h3>哪些请求走流式管线 ({@link #route})</h3>
 * PR-3 起识别 / 执行只有流式这一条管线: 否定说法、平台账号 / 非本厂账号、早期通用问法 (分析流 / 复杂度路由 /
 * 多代理 / 食品知识 / RAG)、无 sessionId、预览 (无意图码) 都走流式, 原普通入口的识别分支已删。
 * 只有带显式意图码的请求 (按钮点选、参数确认后执行、公开演示) 仍走编排器的显式意图路径
 * ({@link IntentExecutionOrchestrator#executeWithExplicitIntent}) —— 那是预览 / 确认 (TCC)、数字班组预览、
 * 多意图子请求、公开演示共用的写确认收敛点 (SITE A), 与流式显式路径的合并留给后续 (写确认 / 审批顺序待定)。
 *
 * <p>入口契约上的唯一差别: /execute 不带 sessionId 是独立一问 ({@link #standaloneTurnOnCurrentThread()}),
 * 流式不建立、不接上本人最近一次会话 (#1782 终审 中-1); App 流式不带 sessionId 照旧接上最近会话。
 * 本类另补原普通入口外层的 AI 访问审计 {@code INTENT_EXECUTION} 条目与按工厂脱敏的外发作用域 (#12),
 * 取回后补未选中回答的 READ 标记。原普通入口独有的步骤 (Drools、动态选工具、补问、文字兜底、对话记忆)
 * 已在管线里对两个入口一律执行, 本类不再单独做。
 */
@Slf4j
@Component
public class StreamTurnCollector {

    /** 路由结果: 走流式管线, 或带显式意图码走编排器的显式意图路径。 */
    public enum Route {
        STREAM,
        EXPLICIT_INTENT;

        public boolean viaStream() {
            return this == STREAM;
        }
    }

    /** 普通入口经流式管线的这一轮的线程内状态。 */
    private static final class TurnState {
        boolean standalone;
    }

    private static final ThreadLocal<TurnState> COLLECTING = new ThreadLocal<>();

    /**
     * 本线程这一轮是普通入口的独立一问 (请求没带 sessionId): 流式管线不建立、不接上会话 (#1782 终审 中-1)。
     * 只由 {@link #collect} 设置, 客户端改不了。
     */
    static boolean standaloneTurnOnCurrentThread() {
        TurnState state = COLLECTING.get();
        return state != null && state.standalone;
    }

    private final SseStreamingService sseStreamingService;
    private final IntentExecutionOrchestrator orchestrator;

    /** AI 数据访问审计: 原普通入口外层的 INTENT_EXECUTION 条目, 走流式时由这里开。单测直接构造时不记录。 */
    @Autowired(required = false)
    private ProcessSecretAiAccessAudit aiAccessAudit = ProcessSecretAiAccessAudit.disabled();

    @Autowired
    public StreamTurnCollector(SseStreamingService sseStreamingService,
                               @Lazy IntentExecutionOrchestrator orchestrator) {
        this.sseStreamingService = sseStreamingService;
        this.orchestrator = orchestrator;
    }

    /** 这一轮走哪条路 (只读判定, 不调模型): 带显式意图码 → 编排器显式意图路径, 其余 → 流式管线。 */
    public Route route(IntentExecuteRequest request) {
        if (request != null && request.getIntentCode() != null && !request.getIntentCode().isBlank()) {
            return Route.EXPLICIT_INTENT;
        }
        return Route.STREAM;
    }

    /**
     * 跑一轮并取回回答。调用方 ({@code IntentExecutorServiceImpl.execute}) 先用 {@link #route} 判过。
     * 外发按本次工厂脱敏、AI 访问审计开 INTENT_EXECUTION 条目 —— 与原普通入口 {@code execute()} 外层相同;
     * 本轮内的工具调用 / 成本分析并进这一条 (同一线程)。
     */
    public IntentExecuteResponse collect(String factoryId, IntentExecuteRequest request, Long userId, String userRole) {
        CollectingEmitter emitter = new CollectingEmitter();
        TurnState previous = COLLECTING.get();
        TurnState state = new TurnState();
        state.standalone = request == null || request.getSessionId() == null || request.getSessionId().isBlank();
        try (var egressFactory = LlmEgressGuard.analysing(factoryId);
             var aiAccess = ProcessSecretAiAccessAudit.beginOrNoop(aiAccessAudit, factoryId,
                     ProcessSecretAiAccessAudit.Entry.INTENT_EXECUTION)) {
            COLLECTING.set(state);
            SseStreamingService.CollectedTurn turn =
                    sseStreamingService.runOnCurrentThread(emitter, factoryId, request, userId, userRole);
            IntentExecuteResponse response = turn.result();
            if (response == null || (turn.errorExit() && "FAILED".equals(response.getStatus())
                    && response.getMessage() == null)) {
                // 没发出 result 就结束 (error 出口 / 中途断): 如实失败, ⛔ 不兜成成功; 带上 error 事件里的说明 (已消毒)
                log.warn("[execute-via-stream] 流式管线未给出回答: factoryId={}, completeStatus={}, errorExit={}",
                        factoryId, turn.completeStatus(), turn.errorExit());
                String message = turn.errorMessage() == null || turn.errorMessage().isBlank()
                        ? null : com.cretas.aims.util.ErrorSanitizer.sanitize(turn.errorMessage());
                return IntentExecuteResponse.builder()
                        .intentRecognized(false)
                        .intentCode(turn.resolvedIntentCode())
                        .status("FAILED")
                        .message(message)
                        .formattedText(message)
                        .executedAt(LocalDateTime.now())
                        .build();
            }
            orchestrator.applyUnselectedAnswerReadStamp(response);
            return response;
        } finally {
            if (previous == null) COLLECTING.remove();
            else COLLECTING.set(previous);
        }
    }

    /** 只收不发的 emitter: 流式管线照常 send / complete, 内容由 {@link SseStreamingService} 记下的读数取回。 */
    static final class CollectingEmitter extends SseEmitter {
        CollectingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            // 普通入口不下发事件
        }
    }
}

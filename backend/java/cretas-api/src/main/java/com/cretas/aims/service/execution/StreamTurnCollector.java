package com.cretas.aims.service.execution;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 普通入口 {@code /execute} 的薄适配层 (意图蒸馏第③步 PR-2b): 在当前线程上跑一整轮流式管线
 * ({@link SseStreamingService}), 取最后一个 result 当作普通入口的回答。HTTP 层的信封
 * ({@code envelopeByInnerStatus}) 与遮价 ({@code PriceFieldResponseAdvice}) 照常作用在这个回答上;
 * 飞轮 / trace 仍由门面按 NORMAL 记 (本类不经流式的工作线程, 不记 STREAM)。
 *
 * <h3>哪些请求走流式管线 ({@link #route})</h3>
 * 只有两边都已对齐的那一类; 其余保留原普通入口分支 (PR-2b C 类逐项登记, PR-3 再收):
 * <ul>
 *   <li>开关 {@code cretas.ai.execute.via-stream=false} (一次发布的回退杆, PR-3 删) → 原分支;</li>
 *   <li>预览 → 原分支 (预览不走本类, 这里再兜一次);</li>
 *   <li>显式意图码 (按钮点选 / 参数确认后执行) → 原分支: 原普通入口显式路径先出写确认卡再判审批 (#17);</li>
 *   <li>调用者不是绑定本厂本人的工厂账号 (演示会话、平台账号、公开演示) → 原分支: 流式只认工厂账号 (#15/#18);</li>
 *   <li>没带 sessionId → 原分支: 流式会接上本人最近一次 (App) 会话, 把独立一问当成那一轮的续句 (#1782 终审 中-1);</li>
 *   <li>否定说法 (「别查库存了」「不要报工」) → 原分支: 原普通入口在续轮 / 恢复之前就拦 (#9);</li>
 *   <li>短语快捷不接、早期问题类型是通用咨询 / 闲聊 → 原分支: 分析流、复杂度路由、多代理、食品知识、
 *       RAG 路由只在原普通入口有 (#16)。</li>
 * </ul>
 * 走流式时, 原普通入口独有、流式没有的步骤由流式在同一位置只对这一轮调用 ({@link #collectingOnCurrentThread()}):
 * Drools 校验 (#10)、动态选工具与 NEED_MORE_INFO 补问 (#11)、对话记忆 (#12); 本类补上 AI 访问审计
 * {@code INTENT_EXECUTION} 条目 (#12)、按工厂脱敏的外发作用域, 并在取回后做原普通入口的文字兜底 /
 * workdesk 摘要 (#13) 与未选中回答的 READ 标记。
 */
@Slf4j
@Component
public class StreamTurnCollector {

    /** 路由结果: 走流式管线, 或留在原普通入口 (带原因, 便于日志与测试)。 */
    public enum Route {
        STREAM,
        LEGACY_SWITCH_OFF,
        LEGACY_PREVIEW,
        LEGACY_EXPLICIT_INTENT,
        LEGACY_NOT_FACTORY_ACTOR,
        LEGACY_SESSIONLESS,
        LEGACY_NEGATION,
        LEGACY_EARLY_GENERAL;

        public boolean viaStream() {
            return this == STREAM;
        }
    }

    /** 普通入口经流式管线的这一轮的线程内状态。 */
    private static final class TurnState {
        boolean textFallbackApplied;
    }

    private static final ThreadLocal<TurnState> COLLECTING = new ThreadLocal<>();

    /** 本线程上正在跑的这一轮是普通入口 (/execute) 经流式管线。 */
    public static boolean collectingOnCurrentThread() {
        return COLLECTING.get() != null;
    }

    /**
     * 流式管线在执行分支里已对这一轮做过原普通入口的文字兜底 (要在写对话记忆之前做, 记忆里记的才是用户看到的那句);
     * 取回后不再做第二遍 (workdesk 摘要会再调一次模型)。
     */
    static void markTextFallbackApplied() {
        TurnState state = COLLECTING.get();
        if (state != null) state.textFallbackApplied = true;
    }

    private final SseStreamingService sseStreamingService;
    private final IntentExecutionOrchestrator orchestrator;
    private final IntentKnowledgeBase knowledgeBase;

    @Value("${cretas.ai.execute.via-stream:true}")
    private boolean viaStream = true;

    /** 判否定说法 (与两个入口同一个服务); 缺席时 (裸构造) 按「判定不了」处理 → 留原分支。 */
    @Autowired(required = false)
    private QueryPreprocessorService queryPreprocessorService;

    /** AI 数据访问审计: 原普通入口外层的 INTENT_EXECUTION 条目, 走流式时由这里开。单测直接构造时不记录。 */
    @Autowired(required = false)
    private ProcessSecretAiAccessAudit aiAccessAudit = ProcessSecretAiAccessAudit.disabled();

    @Autowired
    public StreamTurnCollector(SseStreamingService sseStreamingService,
                               @Lazy IntentExecutionOrchestrator orchestrator,
                               IntentKnowledgeBase knowledgeBase) {
        this.sseStreamingService = sseStreamingService;
        this.orchestrator = orchestrator;
        this.knowledgeBase = knowledgeBase;
    }

    /** 这一轮该不该走流式管线 (只读判定, 不调模型)。 */
    public Route route(String factoryId, IntentExecuteRequest request, Long userId) {
        if (!viaStream) {
            return Route.LEGACY_SWITCH_OFF;
        }
        if (request == null || Boolean.TRUE.equals(request.getPreviewOnly())) {
            return Route.LEGACY_PREVIEW;
        }
        if (request.getIntentCode() != null && !request.getIntentCode().isBlank()) {
            return Route.LEGACY_EXPLICIT_INTENT;
        }
        boolean factoryActor = AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> Objects.equals(actor.id(), userId))
                .filter(actor -> Objects.equals(actor.factoryId(), factoryId))
                .isPresent();
        if (userId == null || !factoryActor) {
            return Route.LEGACY_NOT_FACTORY_ACTOR;
        }
        // 没带 sessionId: 流式会经 establishOwnedSession → getOrCreateContext(sessionId=null) 接上本人最近一次会话
        // (App 的那一个), 识别历史 / 缺参续轮 / 任务恢复 / 对话记忆都会落到那一轮上 —— 「100」会被当成 App 里报工那一问的回答。
        // 原普通入口无 sessionId 就是独立一问, 留原分支 (#1782 终审 中-1)。
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return Route.LEGACY_SESSIONLESS;
        }
        String input = request.getUserInput();
        if (input == null || input.isBlank()) {
            return Route.STREAM;
        }
        if (negationOrUnknown(input)) {
            return Route.LEGACY_NEGATION;
        }
        if (!sseStreamingService.phraseShortcutWouldTake(factoryId, input)) {
            QuestionType early = knowledgeBase.detectQuestionType(input);
            if (early == QuestionType.GENERAL_QUESTION || early == QuestionType.CONVERSATIONAL) {
                return Route.LEGACY_EARLY_GENERAL;
            }
        }
        return Route.STREAM;
    }

    private boolean negationOrUnknown(String input) {
        if (queryPreprocessorService == null) {
            return true;
        }
        try {
            return queryPreprocessorService.detectNegationVeto(input, knowledgeBase)
                    != QueryPreprocessorService.NegationKind.NONE;
        } catch (RuntimeException e) {
            log.warn("[execute-via-stream] 否定判定失败, 留原分支: {}", e.getClass().getSimpleName());
            return true;
        }
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
        // 收尾 (文字兜底里的 workdesk 摘要会调模型) 也在脱敏外发 + 审计作用域内 (#1782 终审 低-1)
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
            if (!state.textFallbackApplied) {
                orchestrator.applyOrdinaryEntryTextFallback(factoryId, response, userId);
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

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
        LEGACY_NEGATION,
        LEGACY_EARLY_GENERAL;

        public boolean viaStream() {
            return this == STREAM;
        }
    }

    private static final ThreadLocal<Boolean> COLLECTING = new ThreadLocal<>();

    /** 本线程上正在跑的这一轮是普通入口 (/execute) 经流式管线。 */
    public static boolean collectingOnCurrentThread() {
        return Boolean.TRUE.equals(COLLECTING.get());
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
        SseStreamingService.CollectedTurn turn;
        Boolean previous = COLLECTING.get();
        try (var egressFactory = LlmEgressGuard.analysing(factoryId);
             var aiAccess = ProcessSecretAiAccessAudit.beginOrNoop(aiAccessAudit, factoryId,
                     ProcessSecretAiAccessAudit.Entry.INTENT_EXECUTION)) {
            COLLECTING.set(Boolean.TRUE);
            turn = sseStreamingService.runOnCurrentThread(emitter, factoryId, request, userId, userRole);
        } finally {
            if (previous == null) COLLECTING.remove();
            else COLLECTING.set(previous);
        }
        IntentExecuteResponse response = turn.result();
        if (response == null || (turn.errorExit() && "FAILED".equals(response.getStatus())
                && response.getMessage() == null)) {
            // 没发出 result 就结束 (error 出口 / 中途断): 如实失败, ⛔ 不兜成成功
            log.warn("[execute-via-stream] 流式管线未给出回答: factoryId={}, completeStatus={}, errorExit={}",
                    factoryId, turn.completeStatus(), turn.errorExit());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .intentCode(turn.resolvedIntentCode())
                    .status("FAILED")
                    .executedAt(LocalDateTime.now())
                    .build();
        }
        orchestrator.applyOrdinaryEntryTextFallback(factoryId, response, userId);
        orchestrator.applyUnselectedAnswerReadStamp(response);
        return response;
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

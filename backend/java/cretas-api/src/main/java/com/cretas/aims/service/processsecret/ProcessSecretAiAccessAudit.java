package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.security.AuthenticatedActor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 数据访问审计 (Steve 2026-10-29 选 A): 每个 AI 请求一条记录 —— 调用者、访问档位、这次请求的数据是否牵涉
 * 工艺保密产品、工厂、入口。写进已有的工艺保密审计表 ({@code process_secret_access_events}, 动作
 * {@link ProcessSecretAction#AI_ACCESS}), 走与页面侧同一个 {@link ProcessSecretAccessService#audit} (独立事务)。
 *
 * <h3>一个请求一条</h3>
 * 入口 (见 {@link Entry}) 用 {@code try (var ai = audit.begin(factoryId, entry)) { ... }} 包住, 嵌套入口并进同一条:
 * <ul>
 *   <li>在 HTTP 请求线程里: 这一条挂在请求上, 请求结束时写一次 (同一请求里先后几个入口、几次工具调用都并进来);</li>
 *   <li>不在请求线程里 (流式分析的执行线程、定时任务): 最外层入口结束时写一次。</li>
 * </ul>
 * 合并规则: 工厂 / 调用者 / 档位 / 入口取第一个入口的; 「牵涉保密产品」「整次未发」任一入口标了就算。
 *
 * <h3>「牵涉保密产品」的口径</h3>
 * 这次请求经手的数据里出现了本厂保密产品 (或牵涉它们的计划) —— 不论最后是完整发给模型 (有权)、裁剪后发
 * (无权, 合计只算能看的行) 还是整次不发。看的是结构化数据里的产品 / 计划、单批分析的批次、AI 工具在本请求里
 * 读到的工艺内容; 只有合计、没有逐行产品的数据看不出来 —— 那种情况看档位: 有保密产品的工厂里 FULL 档的合计含保密产品。
 *
 * <h3>写失败怎么办 (与页面侧同一处理)</h3>
 * 与页面侧完全相同: 调 {@link ProcessSecretAccessService#audit}, 它捕获写失败、记 ERROR 日志、返回 false,
 * 「要不要因此拦下」由调用方决定。页面侧只在「放行完整工艺 (ALLOW)」时 fail-closed —— 那是授权凭证, 没有记录就不放内容。
 * 本审计不是授权: AI 请求能看到什么已经由 {@link ProcessSecretLlmCostScope} 按调用者裁剪 / 拦下, 这里只记录发生了什么,
 * 所以写失败<b>不拦业务请求</b>, 但<b>不静默</b>: 再记一条 ERROR (带入口 / 工厂 / 是否牵涉保密) 并累加
 * {@link #writeFailures()} 计数, 便于告警。
 */
@Slf4j
@Component
public class ProcessSecretAiAccessAudit {

    /** 已接上的入口 (审计记录的 resource_ref = {@code ai:<入口>})。 */
    public enum Entry {
        /** {@code AIAnalysisService.analyzeCost}: 全部非流式成本分析的汇合点 (批次 / 时间范围 / 对比 / 日周月报 / 加工服务)。 */
        COST_ANALYSIS("cost-analysis"),
        /** {@code AIEnterpriseService.analyzeCost} / {@code analyzeTimeRangeCost} / {@code compareBatchCosts}: 含不调模型的缓存命中。 */
        ENTERPRISE_ANALYSIS("enterprise-analysis"),
        /** {@code AIEnterpriseService.analyzeTimeRangeCostStream}: 流式分析不经过成本分析汇合点。 */
        STREAM_ANALYSIS("stream-analysis"),
        /** {@code ProcessSecretToolOutputAspect}: 全部 AI 工具调用 (对话 / 意图执行 / 工作台 / 技能)。 */
        AI_TOOL("ai-tool"),
        /** {@code IntentExecutionOrchestrator.execute / executeWithExplicitIntent}: AI 对话的意图执行。 */
        INTENT_EXECUTION("intent-execution"),
        /** {@code AIContextServiceImpl.buildProductionContextForAi / buildCostContextForCaller}: 给 Python AI 的上下文接口。 */
        AI_CONTEXT("ai-context");

        private final String ref;

        Entry(String ref) {
            this.ref = ref;
        }

        public String ref() {
            return ref;
        }
    }

    static final String REQUEST_ATTRIBUTE = ProcessSecretAiAccessAudit.class.getName() + ".pending";
    private static final ThreadLocal<Pending> OFF_REQUEST = new ThreadLocal<>();

    private final ProcessSecretAccessService accessService;
    private final ProcessSecretLlmCostScope scope;
    private final AtomicLong writeFailures = new AtomicLong();

    @Autowired
    public ProcessSecretAiAccessAudit(ProcessSecretAccessService accessService, ProcessSecretLlmCostScope scope) {
        this.accessService = accessService;
        this.scope = scope;
    }

    /** 单测直接 new 服务时的默认实例: 不记录 (要验审计的测试注入真实实例)。 */
    public static ProcessSecretAiAccessAudit disabled() {
        return new ProcessSecretAiAccessAudit(null, null);
    }

    /** 审计写失败的累计次数 (进程内; 写失败同时有 ERROR 日志)。 */
    public long writeFailures() {
        return writeFailures.get();
    }

    /** 进入一个 AI 入口。嵌套 / 同一请求里的后续入口并进同一条记录。 */
    public Access begin(String factoryId, Entry entry) {
        if (accessService == null) return new Access(null, false);
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object existing = request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof Pending pending) return new Access(pending, false);
            Pending pending = open(factoryId, entry);
            request.setAttribute(REQUEST_ATTRIBUTE, pending, RequestAttributes.SCOPE_REQUEST);
            request.registerDestructionCallback(REQUEST_ATTRIBUTE, () -> write(pending), RequestAttributes.SCOPE_REQUEST);
            return new Access(pending, false);
        }
        Pending current = OFF_REQUEST.get();
        if (current != null) return new Access(current, false);
        Pending pending = open(factoryId, entry);
        OFF_REQUEST.set(pending);
        return new Access(pending, true);
    }

    private Pending open(String factoryId, Entry entry) {
        Pending pending = new Pending();
        pending.factoryId = factoryId;
        pending.entry = entry;
        pending.actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        try {
            ProcessSecretLlmCostScope.ReaderView view = scope.readerView(factoryId);
            pending.tier = view.auditLabel() + (view.tier().isEmpty() ? "" : "(" + view.tier() + ")");
        } catch (RuntimeException e) {
            pending.tier = "UNKNOWN";
        }
        return pending;
    }

    void write(Pending pending) {
        if (pending.written) return;
        pending.written = true;
        String factoryId = pending.factoryId == null ? "UNKNOWN" : pending.factoryId;
        String reason = "tier=" + pending.tier + (pending.withheld ? "; withheld" : "");
        boolean recorded;
        try {
            recorded = accessService.audit(factoryId, null, pending.actor, ProcessSecretAction.AI_ACCESS,
                    pending.containsProtected ? "PROTECTED" : "NONE", reason, "ai:" + pending.entry.ref());
        } catch (RuntimeException e) {
            recorded = false;
        }
        if (!recorded) {
            writeFailures.incrementAndGet();
            log.error("[process-secret] AI access audit NOT written (request was not blocked): entry={}, factory={}, containsProtected={}, tier={}",
                    pending.entry.ref(), factoryId, pending.containsProtected, pending.tier);
        }
    }

    /**
     * 给裁剪处用 (例如 {@link ProcessSecretLlmCostScope#withoutProtectedBatches} 排除了保密行): 当前正在记录的那一条
     * 标为「牵涉保密产品」; 当前没有 AI 入口在记录时什么也不做。静态方法, 免得裁剪器反过来依赖审计 bean。
     */
    public static void markCurrentInvolvesProtected() {
        current().ifPresent(p -> p.containsProtected = true);
    }

    /** 当前线程上正在记录的那一条 (测试与诊断用)。 */
    static Optional<Pending> current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Pending p) {
            return Optional.of(p);
        }
        return Optional.ofNullable(OFF_REQUEST.get());
    }

    static final class Pending {
        String factoryId;
        Entry entry;
        AuthenticatedActor actor;
        String tier;
        boolean containsProtected;
        boolean withheld;
        boolean written;
    }

    /** 一个入口的句柄。只有最外层的线程内入口 (不在请求里时) 关闭时写记录。 */
    public final class Access implements AutoCloseable {
        private final Pending pending;
        private final boolean owner;

        private Access(Pending pending, boolean owner) {
            this.pending = pending;
            this.owner = owner;
        }

        /** 这个入口经手的数据牵涉 (true) / 不牵涉保密产品。只会把 false 改成 true。 */
        public Access involvesProtected(boolean involves) {
            if (pending != null && involves) pending.containsProtected = true;
            return this;
        }

        /**
         * AI 工具在本请求里读到的工艺内容 ({@link ProcessSecretReadTracker#reads()}) 里有本厂保密产品 /
         * 牵涉它们的计划 / 它们的批次 —— 牵涉 (与调用者无关; 工具输出随后由裁剪器按调用者处理)。
         */
        public Access involvesReads(java.util.Collection<ProcessSecretReadTracker.Read> reads) {
            if (pending == null || reads == null || reads.isEmpty()) return this;
            try {
                java.util.Map<String, ProcessSecretLlmCostScope.Excluded> byFactory = new java.util.HashMap<>();
                for (ProcessSecretReadTracker.Read read : reads) {
                    String factory = read.factoryId() != null ? read.factoryId() : pending.factoryId;
                    if (factory == null) continue;
                    ProcessSecretLlmCostScope.Excluded all = byFactory.computeIfAbsent(factory, scope::allProtected);
                    if (all.isEmpty()) continue;
                    boolean hit = switch (read.kind()) {
                        case PRODUCT -> all.coversProduct(read.id());
                        case PRODUCTION_PLAN -> all.coversPlan(read.id()) || all.coversProduct(read.member());
                        default -> all.coversProduct(read.member());
                    };
                    if (hit) return involvesProtected(true);
                }
                return this;
            } catch (RuntimeException e) {
                return involvementUnknown();
            }
        }

        /** 判定不了: 按牵涉记 (审计宁可多记)。 */
        public Access involvementUnknown() {
            return involvesProtected(true);
        }

        /** 保密原因整次没发给模型。 */
        public Access withheld() {
            if (pending != null) {
                pending.withheld = true;
                pending.containsProtected = true;
            }
            return this;
        }

        @Override
        public void close() {
            if (!owner) return;
            OFF_REQUEST.remove();
            write(pending);
        }
    }
}

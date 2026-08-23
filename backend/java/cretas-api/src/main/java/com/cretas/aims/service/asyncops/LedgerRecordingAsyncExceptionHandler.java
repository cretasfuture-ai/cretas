package com.cretas.aims.service.asyncops;

import com.cretas.aims.service.asyncops.AsyncFailureEnrollment.AsyncFailureContext;
import com.cretas.aims.service.asyncops.AsyncFailureEnrollment.EnrolledAsyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@code @Async} void 方法逃逸异常的出口 —— 已登记的落台账, 其余照旧只打日志。
 *
 * <h3>生产上谁保证它被调用</h3>
 * <p>Spring 自己。{@code AsyncExecutionAspectSupport.handleError(...)} 在
 * {@code @Async} 方法返回类型为 {@code void} 且抛出异常时, 无条件调用当前
 * {@link AsyncUncaughtExceptionHandler}; 而「当前是哪一个」由
 * {@code AsyncConfigurer#getAsyncUncaughtExceptionHandler()} 决定 ——
 * {@code com.cretas.aims.config.AsyncConfig} 实现了它并返回本类。
 * <p>这条链路由 {@code AsyncFailureExitWiringContractTest} 在<b>真的 Spring 容器</b>里
 * 端到端验证 (起 {@code @EnableAsync} 上下文 → 调一个会抛的 {@code @Async} 方法 →
 * 断言本类收到了那个异常), ⛔ 不是直接调本类的方法自证。
 *
 * <h3>🔴 这道出口看不见什么 (代理判据的局限, 写在它自己身上)</h3>
 * <ol>
 *   <li><b>只看得见 {@code void} 的 {@code @Async} 方法。</b> 返回
 *       {@code Future}/{@code CompletableFuture} 的, Spring 把异常塞进 Future 交给调用方,
 *       <b>永远不会走到本处理器</b>。这类失败本出口一条都记不到。</li>
 *   <li><b>只有登记表里的方法落台账。</b> 见 {@link AsyncFailureEnrollment} —— 这是
 *       刻意的 (窄而可信)。未登记的方法只打日志, 与今天行为完全一致, 日志里带
 *       {@code ledger=SKIPPED_NOT_ENROLLED} 让这块盲区可以被 grep 出来。</li>
 *   <li><b>只看得见「抛出来」的失败, 不是「业务失败」。</b> 监听器自己 catch 掉并写了
 *       FAILED 状态的, 不抛 ⇒ 本出口无感。反过来说: 本出口记到一条,
 *       意味着连那个 catch 都没兜住。</li>
 *   <li><b>台账写不进去时只剩日志。</b> DB 不可达 ⇒ 这一行丢失, 只留
 *       {@code ASYNC_FAILURE_LEDGER_WRITE_FAILED}。这正是定时跑批需要
 *       「没量到」独立一态的原因 —— 台账空着有两种含义。</li>
 *   <li><b>提取不到工厂就是提取不到。</b> prod 实测 211 条逃逸的 MDC 槽位
 *       {@code [u:] [f:]} 全空, 异步线程不带上下文; 只有登记表里配了提取器的参数类型
 *       才拿得到 factoryId, 其余记 NULL。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerRecordingAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

    private final AsyncTaskFailureLedger ledger;

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        String signature = method == null ? "(unknown method)" : AsyncFailureEnrollment.signatureOf(method);
        EnrolledAsyncTask enrolled = AsyncFailureEnrollment.lookup(method);

        if (enrolled == null) {
            // 未登记 —— 保持与 Spring 默认 SimpleAsyncUncaughtExceptionHandler 一样的行为:
            // 打一条带完整栈的 ERROR。⛔ 刻意不落台账, 见类头「看不见什么」第 2 条。
            log.error("Unexpected exception occurred invoking async method: {} ledger=SKIPPED_NOT_ENROLLED",
                    signature, ex);
            return;
        }

        AsyncFailureContext context = extractContext(enrolled, params);
        boolean recorded = recordQuietly(enrolled, signature, context, ex);

        // 日志一条不少 —— 台账是新增的出口, 不是日志的替代品。
        log.error("Unexpected exception occurred invoking async method: {} "
                        + "ledger={} factory={} businessKey={} 补偿={}",
                signature,
                recorded ? "RECORDED" : "WRITE_FAILED",
                context.factoryId(),
                context.businessKey(),
                enrolled.remediationHint(),
                ex);
    }

    /**
     * 提取器自己抛了不能连累出口 —— 那会把「记一次失败」变成「丢一次失败」。
     * 退化成 UNKNOWN, 台账里那两列留空, 至少这条失败还在。
     */
    private AsyncFailureContext extractContext(EnrolledAsyncTask enrolled, Object[] params) {
        try {
            AsyncFailureContext context = enrolled.contextExtractor().apply(params);
            return context == null ? AsyncFailureContext.UNKNOWN : context;
        } catch (Exception extractorFailure) {
            log.warn("ASYNC_FAILURE_CONTEXT_EXTRACTOR_FAILED method={} —— 本条按未知工厂记账",
                    enrolled.methodSignature(), extractorFailure);
            return AsyncFailureContext.UNKNOWN;
        }
    }

    /**
     * 台账写入不能抛。抛出去会被 Spring 的 {@code handleError} 再吞一次,
     * 净效果是「一次丢失的失败」变成「一次丢失的失败 + 一条噪音」。
     * {@link AsyncTaskFailureLedger#record} 内部已经 catch 并返回 false;
     * 这里再兜一层, 防的是它抛在进入 try 之前 (例如事务代理拿不到连接)。
     */
    private boolean recordQuietly(EnrolledAsyncTask enrolled, String signature,
                                  AsyncFailureContext context, Throwable ex) {
        try {
            return ledger.record(signature, context.factoryId(), context.businessKey(),
                    ex, enrolled.remediationHint());
        } catch (Exception ledgerFailure) {
            log.error("ASYNC_FAILURE_LEDGER_WRITE_FAILED method={} —— 台账入口抛出, 本次失败只剩日志",
                    signature, ledgerFailure);
            return false;
        }
    }
}

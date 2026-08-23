package com.cretas.aims.config;

import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.service.asyncops.AsyncTaskFailureLedger;
import com.cretas.aims.service.asyncops.LedgerRecordingAsyncExceptionHandler;
import com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「生产上谁保证它被调用」的答案, 由本测试<b>证明</b>而不是声称。
 *
 * <p>本仓形态 B (机制在、没接上) 的典型死法: 处理器写得好好的、单测也绿,
 * 但生产上没人调它 —— 而普通单测对这一类是盲的, 因为它直接调被测函数,
 * 绕过了「谁调它」。所以这里<b>一次都不直接调</b>
 * {@link LedgerRecordingAsyncExceptionHandler}。
 *
 * <p>被测的整条链, 每一环都是生产件:
 * <pre>
 *   真的 Spring 容器 (@EnableAsync 来自生产的 AsyncConfig)
 *     -> 真的 @Async 代理
 *       -> 真的监听器 SalesFinanceApproveVoucherListener (prod 上逃逸 86 次的那个)
 *         -> Spring 的 AsyncExecutionAspectSupport.handleError
 *           -> AsyncConfig.getAsyncUncaughtExceptionHandler()
 *             -> LedgerRecordingAsyncExceptionHandler
 *               -> AsyncFailureEnrollment 登记表查得到
 *                 -> 台账
 * </pre>
 * ⛔ 刻意<b>不</b>在测试里另写一份 AsyncConfig 或另造一个 @Async 类 ——
 * 本仓有过实测教训: 源码闸绿是因为它在断言里<b>自己模拟</b>了被测行为。
 * 用真的监听器还有一个好处: 它同时钉住了「那个方法名/类名没被改过」——
 * 改了任何一个, 登记表就查不到, 本测试当场红。
 *
 * <p>怎么让真监听器抛: 两个协作者传 {@code null}, 方法第一行
 * {@code salesOrderRepository.findById(...)} 就 NPE。
 * ⚠️ 这是<b>真实上游给得出的形状</b>吗? 不是 —— 生产上抛的是
 * {@code ObjectOptimisticLockingFailureException} 等。但本测试量的是<b>接线</b>
 * (异常有没有被送到台账), 与异常的种类无关; 异常种类的处理由
 * {@code AsyncTaskFailureLedgerContractTest} 单独钉。
 */
class AsyncFailureExitWiringContractTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Spring 把已登记 @Async 方法逃逸的异常送到台账 —— 端到端, 不是直调")
    void springRoutesEscapedAsyncExceptionsToTheLedger() throws Exception {
        RecordingLedger ledger = new RecordingLedger();
        context = newContext(ledger);

        SalesFinanceApproveVoucherListener listener =
                context.getBean(SalesFinanceApproveVoucherListener.class);

        // 直接调用代理上的 @Async 方法。协作者是 null -> 方法体第一行就 NPE。
        listener.onFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, "F006", "SO-20260819-0001", 42L));

        assertTrue(ledger.latch.await(10, TimeUnit.SECONDS),
                "台账没有收到这次失败。可能的断点: @Async 没生效(那样异常会直接抛给本测试) / "
                        + "AsyncConfig 没把处理器接上 / 该方法不在 AsyncFailureEnrollment 登记表里");

        assertEquals("com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener.onFinanceApproved",
                ledger.methodSignature,
                "方法签名拼法必须与 AsyncFailureEnrollment.signatureOf 一致, 否则登记表查不到");

        // prod 实测 211 条逃逸的 MDC 槽位 [u:] [f:] 全是空的 —— 异步线程不带上下文,
        // 工厂/业务键只能从事件参数取。这两条钉的就是那个提取器真的接上了。
        assertEquals("F006", ledger.factoryId,
                "factoryId 没从事件参数提取出来 —— 台账会记成归属不明");
        assertEquals("SO-20260819-0001", ledger.businessKey,
                "businessKey 没从事件参数提取出来 —— 运维拿不到是哪一单");

        assertNotNull(ledger.error, "异常本身没传下来");
        assertNotNull(ledger.remediationHint,
                "登记表的补偿指引没传下来 —— 告警会变成「出事了但不知道该干嘛」");
        assertTrue(ledger.remediationHint.contains("vouchers/generate"),
                "凭证监听器的补偿指引必须指向已有的人工补单入口, 实际: " + ledger.remediationHint);
    }

    @Test
    @DisplayName("未登记的 @Async 方法只打日志, 不落台账 (阴性对照: 窄是刻意的)")
    void unenrolledAsyncMethodsAreNotLedgered() throws Exception {
        RecordingLedger ledger = new RecordingLedger();
        context = newContext(ledger);

        LedgerRecordingAsyncExceptionHandler handler =
                context.getBean(LedgerRecordingAsyncExceptionHandler.class);

        // 一个确实存在、但没有登记的方法。
        handler.handleUncaughtException(
                new IllegalStateException("not enrolled"),
                String.class.getMethod("trim"),
                new Object[0]);

        assertFalse(ledger.latch.await(1, TimeUnit.SECONDS),
                "未登记的方法竟然写进了台账 —— 白名单失效, 台账会被 40 个从没失败过的 @Async 类淹掉");
        assertNull(ledger.methodSignature, "未登记的方法不该碰台账");
    }

    @Test
    @DisplayName("⛔ getAsyncExecutor() 必须仍然是 null —— 本次改动不许动线程池")
    void asyncExecutorStaysUntouched() {
        context = newContext(new RecordingLedger());

        AsyncConfig config = context.getBean(AsyncConfig.class);
        Executor executor = config.getAsyncExecutor();

        assertNull(executor,
                "AsyncConfig.getAsyncExecutor() 返回了非 null —— 这会改变 42 处不带限定名的 @Async "
                        + "的并发行为 (排队/拒绝策略/并行度上限)。给 @Async 配有界线程池是另一件事, "
                        + "已单独登记交 owner, ⛔ 不要顺手在这里改。");
    }

    @Test
    @DisplayName("处理器不是 Spring 的默认实现")
    void handlerIsTheLedgerBackedOne() {
        context = newContext(new RecordingLedger());

        AsyncConfig config = context.getBean(AsyncConfig.class);
        assertNotNull(config.getAsyncUncaughtExceptionHandler(),
                "返回 null 会让 Spring 退回 SimpleAsyncUncaughtExceptionHandler —— "
                        + "那正是今天丢掉 211 次失败的那个");
    }

    /**
     * ⚠️ 装的是<b>生产的</b> {@link AsyncConfig}。它自带 {@code @EnableAsync},
     * 也自带那 4 个 {@code Executor} bean —— 于是这个容器里的 @Async 和 prod 一样
     * 落到兜底的 {@code SimpleAsyncTaskExecutor} (没有一个 bean 叫 taskExecutor,
     * 类型上又不唯一)。执行器条件也是真的。
     */
    private AnnotationConfigApplicationContext newContext(RecordingLedger ledger) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(AsyncTaskFailureLedger.class, () -> ledger);
        ctx.registerBean(LedgerRecordingAsyncExceptionHandler.class,
                () -> new LedgerRecordingAsyncExceptionHandler(ledger));
        // 生产监听器, 协作者留空 —— 让它在第一行就抛。
        ctx.registerBean(SalesFinanceApproveVoucherListener.class,
                () -> new SalesFinanceApproveVoucherListener(null, null));
        ctx.register(AsyncConfig.class);
        ctx.refresh();
        return ctx;
    }

    /** 记下台账收到了什么。⛔ 不连库 —— 本测试量的是接线, 不是 SQL。 */
    static class RecordingLedger extends AsyncTaskFailureLedger {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String methodSignature;
        volatile String factoryId;
        volatile String businessKey;
        volatile Throwable error;
        volatile String remediationHint;

        RecordingLedger() {
            super(null);
        }

        @Override
        public boolean record(String methodSignature, String factoryId, String businessKey,
                              Throwable error, String remediationHint) {
            this.methodSignature = methodSignature;
            this.factoryId = factoryId;
            this.businessKey = businessKey;
            this.error = error;
            this.remediationHint = remediationHint;
            latch.countDown();
            return true;
        }
    }
}

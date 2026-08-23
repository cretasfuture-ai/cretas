package com.cretas.aims.service.asyncops;

import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.service.asyncops.AsyncFailureEnrollment.AsyncFailureContext;
import com.cretas.aims.service.asyncops.AsyncFailureEnrollment.EnrolledAsyncTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记表不许悄悄失效。
 *
 * <p>白名单用字符串当键, 天然有一个死法: 有人重命名了方法, 键就再也匹配不上,
 * <b>出口静默变哑</b> —— 没有报错、没有变红、只是从此不再记录任何东西。
 * 那正是本仓形态 B 的样子, 而且比一般的 B 更阴, 因为它是<b>回归</b>: 今天有、明天没有。
 *
 * <p>类名那一半已经由 {@code Class} 字面量在编译期钉死了 (改类名编译不过)。
 * 剩下方法名这一半, 由本测试用反射钉住。
 */
class AsyncFailureEnrollmentContractTest {

    @Test
    @DisplayName("每个登记项都指向一个真实存在、且真的是 @Async void 的方法")
    void everyEnrolledSignatureResolvesToARealAsyncVoidMethod() throws Exception {
        Map<String, EnrolledAsyncTask> enrolled = AsyncFailureEnrollment.all();

        // 阳性对照: 登记表不是空的。空表会让下面所有断言变成恒真式。
        assertFalse(enrolled.isEmpty(),
                "登记表是空的 —— 下面每一条断言都会空转通过, 整个测试没有意义");
        assertEquals(3, enrolled.size(),
                "登记项数量变了。prod 2026-06-19~08-19 观测到的异步逃逸恰好来自 3 个方法; "
                        + "加/减项目本身没问题, 但要连同这条断言一起改, 并说明新增那条的补偿动作。");

        List<String> problems = new ArrayList<>();
        for (EnrolledAsyncTask task : enrolled.values()) {
            String signature = task.methodSignature();
            int split = signature.lastIndexOf('.');
            String className = signature.substring(0, split);
            String methodName = signature.substring(split + 1);

            Class<?> declaring = Class.forName(className);
            Method found = null;
            for (Method m : declaring.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                problems.add(signature + " —— 方法不存在 (被改名了? 出口对它已经静默失效)");
                continue;
            }
            if (!found.getReturnType().equals(Void.TYPE)) {
                // 非 void 的 @Async 方法, Spring 把异常塞进 Future, 永远不会走处理器。
                problems.add(signature + " —— 返回类型是 " + found.getReturnType().getName()
                        + ", 不是 void。Spring 只对 void 的 @Async 方法调 "
                        + "AsyncUncaughtExceptionHandler, 登记它没有任何作用。");
            }
            if (!found.isAnnotationPresent(Async.class)) {
                problems.add(signature + " —— 没有 @Async。它是同步执行的, "
                        + "异常会正常抛给调用方, 不该登记在这里。");
            }
            // 拼法必须与生产代码同一处 —— 两处拼法一定会漂。
            assertEquals(signature, AsyncFailureEnrollment.signatureOf(found),
                    "signatureOf() 与登记表的拼法不一致");
            assertNotNull(AsyncFailureEnrollment.lookup(found),
                    "用真实 Method 反查登记表落空: " + signature);
        }
        assertTrue(problems.isEmpty(), "登记表已失效:\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("每条登记都带得走的补偿动作 —— 说不出该干嘛的告警第二次就没人看了")
    void everyEnrolledTaskCarriesAnActionableHint() {
        for (EnrolledAsyncTask task : AsyncFailureEnrollment.all().values()) {
            String hint = task.remediationHint();
            assertNotNull(hint, task.methodSignature() + " 没有补偿指引");
            assertFalse(hint.isBlank(), task.methodSignature() + " 的补偿指引是空的");
            assertTrue(hint.length() > 20,
                    task.methodSignature() + " 的补偿指引太短, 大概率不是一个能执行的动作: " + hint);
        }
    }

    @Test
    @DisplayName("提取器从事件参数里取出工厂/业务键")
    void extractorPullsContextFromTheEventArgument() {
        EnrolledAsyncTask task = anyEnrolledTask();
        SalesOrderFinanceApprovedEvent event =
                new SalesOrderFinanceApprovedEvent(this, "F006", "SO-1", 7L);

        AsyncFailureContext context = task.contextExtractor().apply(new Object[]{event});

        assertEquals("F006", context.factoryId());
        assertEquals("SO-1", context.businessKey());
    }

    @Test
    @DisplayName("按类型找而不是按下标 —— 参数顺序变了也不会取到错的东西")
    void extractorFindsTheEventByTypeNotByPosition() {
        EnrolledAsyncTask task = anyEnrolledTask();
        SalesOrderFinanceApprovedEvent event =
                new SalesOrderFinanceApprovedEvent(this, "F006", "SO-2", 7L);

        // 事件被挤到第二位 —— params[0] 取法会拿到 "noise"。
        AsyncFailureContext context = task.contextExtractor().apply(new Object[]{"noise", event});

        assertEquals("F006", context.factoryId(),
                "提取器按下标取参数了 —— 有人给方法加一个前置参数就会静默取到错的工厂");
        assertEquals("SO-2", context.businessKey());
    }

    @Test
    @DisplayName("⛔ 取不到就是 null, 不许兜底成一个能被下游当真的值")
    void extractorReportsUnknownHonestly() {
        EnrolledAsyncTask task = anyEnrolledTask();

        for (Object[] params : List.of(new Object[0], new Object[]{"unrelated"}, new Object[]{null})) {
            AsyncFailureContext context = task.contextExtractor().apply(params);
            assertNull(context.factoryId(),
                    "取不到工厂时返回了非 null —— 「我不知道」被翻译成了一个读数, 下游会当真");
            assertNull(context.businessKey(), "取不到业务键时返回了非 null");
        }
        AsyncFailureContext nullParams = task.contextExtractor().apply(null);
        assertNull(nullParams.factoryId());
        assertNull(nullParams.businessKey());
    }

    @Test
    @DisplayName("阴性对照: 没登记的方法查不到 —— 白名单真的在限制范围")
    void unenrolledMethodsAreNotFound() throws Exception {
        assertNull(AsyncFailureEnrollment.lookup(String.class.getMethod("trim")),
                "一个明显不该登记的方法竟然查得到 —— 白名单形同虚设");
        assertNull(AsyncFailureEnrollment.lookup(null), "null 方法不该炸, 也不该查到东西");
    }

    @Test
    @DisplayName("prod 上逃逸过的那三个方法, 一个都不能漏")
    void theThreeMethodsThatActuallyFailedInProdAreAllEnrolled() {
        // 口径: prod error 流 2026-06-19 ~ 2026-08-19, SimpleAsyncUncaughtExceptionHandler
        // 共 211 条, 按方法拆开是 86 / 82 / 43, 合计正好 211 —— 这三个覆盖了 100%。
        List<String> mustBeEnrolled = List.of(
                "com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener.onFinanceApproved",
                "com.cretas.aims.service.shortage.SalesOrderShortageReportListener"
                        + ".onSalesOrderFinanceApproved",
                "com.cretas.aims.service.orchestration.SupplyChainOrchestrator"
                        + ".onSalesOrderFinanceApproved");

        for (String signature : mustBeEnrolled) {
            assertTrue(AsyncFailureEnrollment.all().containsKey(signature),
                    "prod 实测会失败的方法没有登记: " + signature);
        }
    }

    private EnrolledAsyncTask anyEnrolledTask() {
        return AsyncFailureEnrollment.all().values().iterator().next();
    }
}

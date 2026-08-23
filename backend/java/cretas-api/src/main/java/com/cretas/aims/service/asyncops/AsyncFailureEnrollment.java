package com.cretas.aims.service.asyncops;

import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener;
import com.cretas.aims.service.orchestration.SupplyChainOrchestrator;
import com.cretas.aims.service.shortage.SalesOrderShortageReportListener;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 哪些 {@code @Async} 方法的逃逸异常要落台账 —— <b>显式登记制, 不是全局开关</b>。
 *
 * <h3>为什么是白名单而不是「全都记」</h3>
 * <p>Spring 的 {@code AsyncUncaughtExceptionHandler} 是<b>全局</b>的, 我关不掉它对某个方法的
 * 调用。所以「窄」只能在处理器<b>内部</b>做: 登记表里的方法写台账, 其余的照旧只打日志
 * (与今天的行为完全一致, 零回归)。
 *
 * <p>这不是保守, 是有读数支撑的:
 * <pre>
 *   prod error 流 2026-06-19 ~ 2026-08-19 (5.5MB / 296,448 行) 里
 *   SimpleAsyncUncaughtExceptionHandler 共 211 条, 逐个方法拆开:
 *       86  SalesFinanceApproveVoucherListener.onFinanceApproved
 *       82  SalesOrderShortageReportListener.onSalesOrderFinanceApproved
 *       43  SupplyChainOrchestrator.onSalesOrderFinanceApproved
 *       ---
 *      211  合计  ==  全部 211 条
 * </pre>
 * ⇒ 这三个方法覆盖了<b>两个月内观测到的 100% 的异步逃逸</b>。
 * 全树另有 76 处 {@code @Async} / 43 个类, 一次都没逃逸过 —— 把它们一起接进来,
 * 覆盖率一点不涨, 只多出 40 个类的误报面。
 *
 * <p>本仓硬教训 (形态 E): <b>一道会误报的闸最终会被加 noqa、注掉、或默认忽略, 那时它的
 * 覆盖率归零。</b> 宁可窄而可信。要扩面就在下面这张表里加一行 —— 一行的成本, 但要带着
 * 「这个方法失败了运维该做什么」一起加, 答不上来就说明它还不该进来。
 *
 * <h3>⚠️ 登记表怎么防漂</h3>
 * <p>类用 {@code Class} 字面量拼, <b>改类名会当场编译不过</b>;
 * 方法名仍是字符串, 由 {@code AsyncFailureEnrollmentContractTest} 用反射钉住
 * (方法必须存在、必须 void、必须带 {@code @Async})。两层合起来, 重命名不会让出口静默失效。
 */
public final class AsyncFailureEnrollment {

    private AsyncFailureEnrollment() {
    }

    /**
     * 从 {@code @Async} 方法的实参里取出「这次失败落在谁头上」。
     *
     * <p>⚠️ 取不到就是 {@code null} —— ⛔ 不许兜底成 "UNKNOWN"/"" 之类。
     * prod 实测 211 条逃逸的 MDC 槽位 {@code [u:] [f:]} 全是空的: 异步线程不带用户/工厂
     * 上下文, 所以只能从事件参数取; 取不到时把「我不知道」记成一个能被下游当真的值,
     * 比留空更糟。
     */
    public record AsyncFailureContext(String factoryId, String businessKey) {
        public static final AsyncFailureContext UNKNOWN = new AsyncFailureContext(null, null);
    }

    /**
     * @param methodSignature  {@code 全限定类名.方法名}, 与 {@link #signatureOf(Method)} 同一拼法
     * @param remediationHint  失败之后运维该做什么 —— 台账和告警都会原样打出来
     * @param contextExtractor 从实参里取 factoryId / businessKey
     */
    public record EnrolledAsyncTask(String methodSignature,
                                    String remediationHint,
                                    Function<Object[], AsyncFailureContext> contextExtractor) {
    }

    /**
     * 三个已登记的方法。
     *
     * <p>⚠️ {@code remediationHint} 是给人看的, 每条都必须是<b>一个真的能执行的动作</b>。
     * 写不出具体动作的条目不要进这张表 —— 那样的告警等于「出事了, 但我不知道该干嘛」,
     * 第二次就没人看了。
     */
    private static final Map<String, EnrolledAsyncTask> ENROLLED = buildEnrollment();

    private static Map<String, EnrolledAsyncTask> buildEnrollment() {
        Map<String, EnrolledAsyncTask> map = new LinkedHashMap<>();

        register(map, SalesFinanceApproveVoucherListener.class, "onFinanceApproved",
                "销售凭证没生成, 该单 vflag 会一直停在 PENDING。补: "
                        + "POST /api/mobile/{factoryId}/vouchers/generate {\"businessType\":\"SALES_ORDER\"} "
                        + "(VoucherServiceImpl.batchCreateForFactory —— 每单独立 REQUIRES_NEW 事务, "
                        + "且 isRetryableFlag 已含 PENDING, 幂等, 重复调用安全)。",
                AsyncFailureEnrollment::fromSalesOrderFinanceApprovedEvent);

        register(map, SupplyChainOrchestrator.class, "onSalesOrderFinanceApproved",
                "供应链联动没跑完: 成品库存没预留 / 生产计划(PP-AUTO-*)没建出来。"
                        + "需人工核对该销售单的库存占用与生产计划, 缺什么补什么 —— "
                        + "⚠️ 没有幂等的自动补偿入口, 重放会重复预留库存, 必须人工判断。",
                AsyncFailureEnrollment::fromSalesOrderFinanceApprovedEvent);

        register(map, SalesOrderShortageReportListener.class, "onSalesOrderFinanceApproved",
                "缺料报告快照没生成, 该销售单在缺料看板上会缺一行。"
                        + "报告是只读派生物, 不影响资金和库存 —— 重新触发一次财审事件即可重建, "
                        + "或等下一次分析覆盖。优先级低于上面两条。",
                AsyncFailureEnrollment::fromSalesOrderFinanceApprovedEvent);

        return Map.copyOf(map);
    }

    private static void register(Map<String, EnrolledAsyncTask> map,
                                 Class<?> declaringClass,
                                 String methodName,
                                 String remediationHint,
                                 Function<Object[], AsyncFailureContext> extractor) {
        String signature = declaringClass.getName() + "." + methodName;
        map.put(signature, new EnrolledAsyncTask(signature, remediationHint, extractor));
    }

    /**
     * 三个登记方法都只吃 {@link SalesOrderFinanceApprovedEvent}。
     *
     * <p>按<b>类型</b>找而不是按下标取 {@code params[0]} —— 下标会在有人给方法加参数时
     * 静默取到错的东西, 而类型匹配只会取不到 (退化成 UNKNOWN, 看得见)。
     */
    private static AsyncFailureContext fromSalesOrderFinanceApprovedEvent(Object[] params) {
        if (params == null) {
            return AsyncFailureContext.UNKNOWN;
        }
        for (Object param : params) {
            if (param instanceof SalesOrderFinanceApprovedEvent event) {
                return new AsyncFailureContext(event.getFactoryId(), event.getSalesOrderId());
            }
        }
        return AsyncFailureContext.UNKNOWN;
    }

    /** 与登记表同一拼法 —— ⛔ 别在别处再拼一遍, 两份一定会漂。 */
    public static String signatureOf(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    /** @return 该方法的登记项; {@code null} = 未登记 (只打日志, 不落台账)。 */
    public static EnrolledAsyncTask lookup(Method method) {
        return method == null ? null : ENROLLED.get(signatureOf(method));
    }

    /** 供闸/测试遍历 —— 不可变。 */
    public static Map<String, EnrolledAsyncTask> all() {
        return ENROLLED;
    }
}

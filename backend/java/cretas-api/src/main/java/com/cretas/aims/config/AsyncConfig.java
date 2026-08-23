package com.cretas.aims.config;

import com.cretas.aims.service.asyncops.LedgerRecordingAsyncExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步处理配置
 *
 * 配置线程池用于:
 * - 报表异步生成
 * - AI分析异步处理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig implements AsyncConfigurer {

    /**
     * {@code @Async} void 方法逃逸异常的出口。
     *
     * <p>⛔ <b>必须是 {@link ObjectProvider} (懒), 不能直接注入实例。</b>
     * 本类是 {@code @EnableAsync} 的宿主, Spring 为了装配 async 基础设施会<b>很早</b>
     * 实例化它。直接注入 {@code LedgerRecordingAsyncExceptionHandler} 会把它连同
     * {@code AsyncTaskFailureLedger} 一起拖进那个早期阶段 —— 早于事务的
     * {@code BeanPostProcessor}, 于是 {@code @Transactional(REQUIRES_NEW)} <b>不会被织入</b>,
     * 台账写入会静默退回「跟着调用方的事务走」。那恰好就是本次要修的病
     * (persistFailedPlaceholder 跟着 doomed 事务一起回滚, prod 76/77 次写不进去),
     * 而且它不报错, 只是不生效。
     *
     * <p>⇒ 用 {@code ObjectProvider} 把解析推迟到<b>第一次真的有异常</b>的时候,
     * 那时容器早已启动完毕, 代理一定在。
     */
    private final ObjectProvider<LedgerRecordingAsyncExceptionHandler> asyncExceptionHandler;

    /**
     * 逃逸异常交给台账处理器, 而不是 Spring 默认的
     * {@code SimpleAsyncUncaughtExceptionHandler} (打一条 ERROR 就丢)。
     *
     * <p>prod 实测 2026-06-19 ~ 08-19 共 211 次逃逸走的就是那个默认实现。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                asyncExceptionHandler.getObject().handleUncaughtException(ex, method, params);
    }

    /**
     * ⛔ <b>刻意返回 null —— 不要改成返回下面任何一个线程池。</b>
     *
     * <p>返回 null = 沿用 Spring 今天的默认解析结果, 本次改动<b>零并发行为变化</b>。
     *
     * <p>今天的实际情况 (已核实, 不是推测): 本类定义了 4 个 {@code Executor} bean,
     * 于是 Boot 的 {@code TaskExecutionAutoConfiguration}
     * ({@code @ConditionalOnMissingBean(Executor.class)}) 整个退让, 不再提供
     * {@code applicationTaskExecutor}; 而这 4 个里没有一个叫 {@code taskExecutor},
     * 类型上又不唯一 ⇒ 不带限定名的 {@code @Async} 落到兜底的
     * {@code SimpleAsyncTaskExecutor} (<b>每个任务开一条新线程, 不复用、无上限</b>)。
     * prod 日志里那 211 条逃逸的线程名逐一核对过: {@code SimpleAsyncTaskExecutor-1}
     * 一直到 {@code SimpleAsyncTaskExecutor-146}, 93 个不同序号 —— 与上述推导一致。
     *
     * <p>口径 (2026-08-19 在本分支上数的, 只数注解本身、不含 javadoc 提及):
     * 全树 {@code @Async} 共 <b>52</b> 处 / 38 个文件, 其中
     * <b>42 处不带限定名</b> (落到 SimpleAsyncTaskExecutor), 10 处带限定名。
     *
     * <p>🔴 <b>这是一个真实缺陷, 但不在本次改动范围内</b>: 给 {@code @Async} 配上有界线程池
     * 会改变那 <b>42 处</b>的并发行为 (排队、拒绝策略、并行度上限),
     * 影响面远大于「给失败建一个出口」。已单独登记, 交 owner 决定。
     * 在那之前, 本方法返回 null 是<b>刻意保持现状</b>, 由
     * {@code AsyncFailureExitWiringContractTest} 钉住, 防止有人顺手改掉。
     */
    @Override
    public Executor getAsyncExecutor() {
        return null;
    }

    /**
     * 报表生成线程池
     *
     * 用于异步生成各类报表（生产报表、质量报表、成本报表等）
     *
     * @return 线程池执行器
     */
    @Bean(name = "reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ReportAsync-");
        executor.initialize();
        return executor;
    }

    /**
     * AI分析线程池
     *
     * 用于异步执行AI分析任务（成本分析、质量分析、预测分析等）
     *
     * @return 线程池执行器
     */
    @Bean(name = "aiAnalysisExecutor")
    public Executor aiAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("AIAnalysis-");
        executor.initialize();
        return executor;
    }

    /** Single lane for expensive embedding warmups after core startup. */
    @Bean(name = "startupWarmupExecutor")
    public Executor startupWarmupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("StartupWarmup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * SmartBI Sheet 并行处理线程池
     *
     * AUDIT-087: Replaces static ExecutorService in SmartBIUploadFlowServiceImpl.
     * 5 核心线程，避免 DashScope API 限流；8 最大线程应对突发。
     * Spring 管理生命周期，应用关闭时自动 graceful shutdown。
     *
     * @return 线程池执行器
     */
    @Bean(name = "smartbiExecutor")
    public Executor smartbiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("SmartBI-Sheet-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(180);
        executor.initialize();
        return executor;
    }
}

package com.cretas.aims.testsupport;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试 fork 的「静默看门狗」(2026-11-13, Java Gate fast-contracts 分片偶发卡死到 job 超时)。
 *
 * <p>现象: 分片在某个 {@code @DataJpaTest} 类的 Spring 上下文启动阶段之后再无任何输出, 一直挂到 job 被取消,
 * 没有线程转储, 无法定位。JUnit 的 {@code junit.jupiter.execution.timeout.*} 盖不住上下文启动,
 * surefire 的 {@code forkedProcessTimeoutInSeconds} 约束的是整个 fork 而不是单个类。
 *
 * <p>做法: 通过 ServiceLoader 自动注册 (META-INF/services), fork 里起一个守护线程; 任何测试/容器的
 * 开始、结束、报告事件都算「有动静」。静默超过阈值 ⇒ 把全部线程 (含锁持有者、死锁检测) 打到 stderr 和
 * {@code target/surefire-reports/idle-watchdog-threads.txt}, 再 {@code Runtime.halt(1)}:
 * surefire 会报 "The forked VM terminated without properly saying goodbye" ⇒ 构建<b>失败</b> (不是跳过)。
 *
 * <p>阈值: 系统属性 {@code cretas.test.idleWatchdogSeconds}, 默认 420 秒 (实测正常运行里相邻两个类之间
 * 最长 78 秒); 设为 0 或负数关闭。
 */
public class IdleWatchdogTestExecutionListener implements TestExecutionListener {

    static final String PROP = "cretas.test.idleWatchdogSeconds";
    static final String HALT_PROP = "cretas.test.idleWatchdogNoHalt";
    private static final long DEFAULT_SECONDS = 420;

    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private volatile Thread watchdog;
    private volatile String lastEvent = "(none)";

    private static long thresholdSeconds() {
        String v = System.getProperty(PROP);
        if (v == null || v.isBlank()) {
            return DEFAULT_SECONDS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SECONDS;
        }
    }

    private void touch(String event) {
        lastEvent = event;
        lastActivityNanos.set(System.nanoTime());
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        long threshold = thresholdSeconds();
        if (threshold <= 0 || watchdog != null) {
            return;
        }
        touch("testPlanExecutionStarted");
        Thread t = new Thread(() -> run(threshold), "cretas-idle-watchdog");
        t.setDaemon(true);
        watchdog = t;
        t.start();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        Thread t = watchdog;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        touch("started " + id.getUniqueId());
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        touch("finished " + id.getUniqueId());
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        touch("skipped " + id.getUniqueId());
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier id) {
        touch("dynamic " + id.getUniqueId());
    }

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        touch("report " + id.getUniqueId());
    }

    private void run(long thresholdSeconds) {
        long thresholdNanos = thresholdSeconds * 1_000_000_000L;
        long pollMillis = Math.max(200L, Math.min(5_000L, thresholdSeconds * 1000L / 10));
        try {
            while (true) {
                Thread.sleep(pollMillis);
                long idle = System.nanoTime() - lastActivityNanos.get();
                if (idle >= thresholdNanos) {
                    fire(idle / 1_000_000_000L, thresholdSeconds);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // 测试计划正常结束
        }
    }

    private void fire(long idleSeconds, long thresholdSeconds) {
        String dump = threadDump();
        String header = "CRETAS_IDLE_WATCHDOG: no JUnit activity for " + idleSeconds + "s (threshold "
                + thresholdSeconds + "s). Last event: " + lastEvent
                + ". Dumping all threads, then halting this fork so surefire reports a FAILURE.";
        PrintStream err = System.err;
        err.println();
        err.println("=== " + header + " ===");
        err.println(dump);
        err.println("=== CRETAS_IDLE_WATCHDOG: end of thread dump ===");
        err.flush();
        try {
            Path dir = Paths.get("target", "surefire-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("idle-watchdog-threads.txt"), header + System.lineSeparator() + dump);
        } catch (Exception ignored) {
            // 转储已在 stderr, 文件只是附带
        }
        if (Boolean.getBoolean(HALT_PROP)) {
            return; // 只给本地演示/自测用: 打印但不杀 fork
        }
        Runtime.getRuntime().halt(1);
    }

    private static String threadDump() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        long[] dead = mx.findDeadlockedThreads();
        sb.append("deadlocked threads: ");
        if (dead == null) {
            sb.append("none");
        } else {
            for (long id : dead) {
                sb.append(id).append(' ');
            }
        }
        sb.append(System.lineSeparator());
        Runtime rt = Runtime.getRuntime();
        sb.append("heap used MB=").append((rt.totalMemory() - rt.freeMemory()) >> 20)
                .append(" total MB=").append(rt.totalMemory() >> 20)
                .append(" max MB=").append(rt.maxMemory() >> 20).append(System.lineSeparator());
        for (ThreadInfo ti : mx.dumpAllThreads(true, true, 200)) {
            sb.append(ti.toString().replace("\n", System.lineSeparator()));
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}

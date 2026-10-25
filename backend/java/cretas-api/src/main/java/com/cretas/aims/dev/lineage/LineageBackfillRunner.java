package com.cretas.aims.dev.lineage;

import com.cretas.aims.service.lineage.LineageBackfillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 历史血缘回填的命令入口 —— 一个<b>跑完就退出</b>的一次性进程。
 *
 * <h2>怎么跑（在生产机上，用线上<b>同一个</b> jar）</h2>
 *
 * <pre>{@code
 * # 预演（一条边都不写，只报「扫到多少行 / 其中多少行构成边」）
 * SPRING_PROFILES_ACTIVE=pg-prod,lineage-backfill \
 *   LINEAGE_BACKFILL_FACTORIES=F006,LIUSHANMEN \
 *   LINEAGE_BACKFILL_DRY_RUN=true \
 *   java -jar aims-0.0.1-SNAPSHOT.jar > lineage-backfill-dryrun.log 2>&1; echo "rc=$?"
 *
 * # 真写（只写不删；重跑安全，已写过的落 ALREADY_EXISTS）
 * ... LINEAGE_BACKFILL_DRY_RUN=false ...
 * }</pre>
 *
 * <p>⚠️ {@code lineage-backfill} 必须排在 {@code pg-prod} <b>之后</b>（后面的 profile 覆盖前面的）。
 * 数据库凭证与线上实例一样从环境变量来（{@code DB_PASSWORD} 等）。
 *
 * <h2>退出码（结论看它，再看日志最后一行的 SUMMARY）</h2>
 *
 * <table>
 *   <tr><td>0</td><td>每个工厂都跑完，且没有任何一行「失败」/「延后待定」</td></tr>
 *   <tr><td>1</td><td>有工厂抛异常，或有行「失败」，或出现「延后待定」（读数作废）—— 读数仍有效，逐行看日志</td></tr>
 *   <tr><td>2</td><td>什么都没做：{@code LINEAGE_BACKFILL_FACTORIES} 为空（⛔ 刻意不默认全库跑）</td></tr>
 *   <tr><td>3</td><td>没量到：上下文起不来（含「库里有未执行迁移」），或运行时自检不通过（开着 web 端口 /
 *       调度 / 启动期副作用组件被实例化，见 {@link LineageBackfillPreflight}）—— 一条都没执行</td></tr>
 * </table>
 * ⛔ 非 0 也非 1 的值一律当「没量到」，不要当成功。
 *
 * <h2>这个进程不会做的事（与线上实例隔离）</h2>
 *
 * <p>它和线上实例用同一个 jar、同一个库，所以必须保证它<b>只</b>跑回填：
 * <ul>
 *   <li>不开 web 端口：{@code spring.main.web-application-type=none}（线上蓝绿槽 10010/10020、管理端口 10012 不会被占）；</li>
 *   <li>不开调度：{@code @EnableScheduling} 只在 {@code config.SchedulingConfig} 上且带
 *       {@code @Profile("!lineage-backfill")} —— 全部 {@code @Scheduled}（含没有 {@code @SchedulerLock} 的那些，
 *       如库存低告警、ISAPI 心跳重连）与 Canvas-Cron 都不注册；</li>
 *   <li>不连外部、不在启动时写库：启动监听器带 {@link LineageBackfillMode#NOT_ACTIVE_CONDITION}，
 *       MQTT / WebSocket / 种子 runner 带 {@code @Profile}，其余 {@code @PostConstruct} 副作用靠懒加载不发生；
 *       清单见 {@link LineageBackfillPreflight#STARTUP_SIDE_EFFECT_BEANS}；</li>
 *   <li>不迁移 schema：Flyway 只校验，库里有 pending 迁移就拒绝启动（{@code FlywayConfig}）；</li>
 *   <li>跑完退出：{@code CretasBackendApplication.main} 识别到本 profile 就走 {@link #runAndExit}，
 *       关上下文后 {@code System.exit(退出码)}。</li>
 * </ul>
 *
 * <h2>为什么是 profile 而不是一个 HTTP 端点</h2>
 *
 * <p>回填是一次性运维动作，不该在活着的服务上挂一个谁都能点的按钮。
 * {@code @Profile("lineage-backfill")} 保证它在 {@code prod} / {@code pg-prod}
 * 正常启动时<b>根本不会被实例化</b>。
 *
 * <p>⛔ <b>不在 prod 上自行执行</b>。本 runner 只提供入口，prod 执行由 organizer 安排（🔒）。
 *
 * <h2>读数是五个数</h2>
 *
 * <p>日志里逐段打「扫描 / 新增 / 已存在 / 跳过 / 对不齐 / 失败」。
 * ⛔ 不要只看「新增 N」：N=0 在「已经全在库里」和「一条都没量到」上长得一模一样。
 *
 * <p>⚠️ 预演（{@code dryRun=true}）<b>会查库</b>：三段都走 {@code recorder.preview*}，
 * 与真跑共用同一份查重与计划对齐判据（只读，零写），所以预演报出来的
 * 「新增 / 已存在 / 对不齐」就是真跑会发生的事。
 * ⛔ 早先那版预演对 PRODUCTION_TO_FINISHED 无条件报「跳过」，会被读成
 * 「这一段不会新增边」而真跑插了一堆行 —— 那是一个会误导决策的假读数。
 * 「对不齐」那一列专门用来暴露「一个计划多个生产批次，分不出成品是哪个产的」有多少行 ——
 * 那些边我们<b>故意不写</b>（禁止降级，不猜）。
 */
@Slf4j
@Component
@Profile(LineageBackfillMode.PROFILE)
public class LineageBackfillRunner implements ApplicationRunner, ExitCodeGenerator {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_NOTHING_TO_DO = 2;
    public static final int EXIT_NOT_MEASURED = 3;

    private final LineageBackfillService backfillService;
    private final ConfigurableApplicationContext context;

    /** 逗号分隔的工厂 id；留空则什么都不做（⛔ 不默认全库跑）。 */
    @Value("${LINEAGE_BACKFILL_FACTORIES:}")
    private String factories;

    /** 默认 true —— 默认预演，真写必须显式关掉。 */
    @Value("${LINEAGE_BACKFILL_DRY_RUN:true}")
    private boolean dryRun;

    /** 还没跑过 = 没量到；run() 一定会改写它。 */
    private volatile int exitCode = EXIT_NOT_MEASURED;
    private volatile String summary = "[lineage-backfill] SUMMARY exit=3 runner 没有执行 (上下文在 runner 之前就停了?)";

    public LineageBackfillRunner(LineageBackfillService backfillService,
                                 ConfigurableApplicationContext context) {
        this.backfillService = backfillService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = LineageBackfillPreflight.violations(context);
        if (!violations.isEmpty()) {
            violations.forEach(v -> log.error("[lineage-backfill] 自检不通过: {}", v));
            finish(EXIT_NOT_MEASURED, "自检不通过, 一条都没执行: " + violations);
            return;
        }

        List<String> factoryIds = Arrays.stream(factories.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (factoryIds.isEmpty()) {
            log.warn("[lineage-backfill] LINEAGE_BACKFILL_FACTORIES 为空, 不执行任何回填。"
                    + "⛔ 这里刻意不默认全库跑 —— 回填要按工厂逐个对账。");
            finish(EXIT_NOTHING_TO_DO, "LINEAGE_BACKFILL_FACTORIES 为空, 什么都没做");
            return;
        }

        log.warn("[lineage-backfill] 开始: factories={} dryRun={}", factoryIds, dryRun);
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int failedRows = 0;
        int scheduledRows = 0;
        for (String factoryId : factoryIds) {
            try {
                LineageBackfillService.Report report = backfillService.backfillFactory(factoryId, dryRun);
                log.warn("{}", report.render());
                int f = report.segments().stream().mapToInt(LineageBackfillService.Counters::getFailed).sum();
                int s = report.segments().stream().mapToInt(LineageBackfillService.Counters::getScheduled).sum();
                failedRows += f;
                scheduledRows += s;
                if (f > 0 || s > 0) {
                    failed.add(factoryId + "(失败行=" + f + ", 延后待定=" + s + ")");
                } else {
                    ok.add(factoryId);
                }
            } catch (RuntimeException e) {
                // 一个工厂炸了不连坐后面的工厂；但整个进程必须以非 0 结束。
                log.error("[lineage-backfill] factory={} 回填抛异常, 继续下一个工厂", factoryId, e);
                failed.add(factoryId + "(异常: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        log.warn("[lineage-backfill] 结束。dryRun={} —— 预演走的是与真跑**同一份**查重/对齐判据(只读), "
                        + "三个数与真跑逐条一致; 唯一偏差来自预演与真跑之间数据发生了变化。",
                dryRun);
        finish(failed.isEmpty() ? EXIT_OK : EXIT_FAILED,
                "dryRun=" + dryRun + " 工厂 " + factoryIds.size() + " 个, 成功=" + ok + " 失败=" + failed
                        + " 失败行合计=" + failedRows + " 延后待定合计=" + scheduledRows);
    }

    private void finish(int code, String detail) {
        this.exitCode = code;
        this.summary = "[lineage-backfill] SUMMARY exit=" + code + " " + detail;
        log.warn("{}", summary);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public String summaryLine() {
        return summary;
    }

    /**
     * 回填进程的完整生命周期：起上下文（runner 在其中执行）→ 关上下文 → 返回退出码。
     * 调用方（{@code main}）拿返回值 {@code System.exit}。
     *
     * <p>上下文起不来时返回 {@link #EXIT_NOT_MEASURED}：不能把异常抛出 {@code main} 了事 ——
     * 残留的非守护线程会让进程挂着不退，而挂着的进程看起来像「还在跑」。
     */
    public static int runAndExit(Class<?> source, String[] args) {
        ConfigurableApplicationContext context;
        try {
            context = SpringApplication.run(source, args);
        } catch (Throwable startupFailure) {
            String line = "[lineage-backfill] SUMMARY exit=" + EXIT_NOT_MEASURED
                    + " 上下文没起来, 一条都没执行: " + startupFailure;
            log.error(line, startupFailure);
            System.out.println(line);
            return EXIT_NOT_MEASURED;
        }
        return closeAndReport(context);
    }

    /**
     * 关上下文并返回退出码；SUMMARY 是进程输出的<b>最后一行</b>。
     *
     * <p>关上下文时 Spring Boot 会把日志系统一起收掉，所以关之后那一行走 stdout
     * （runner 收尾时已经用日志打过同一行，两处内容一致）。
     */
    public static int closeAndReport(ConfigurableApplicationContext context) {
        LineageBackfillRunner runner = context.getBeanProvider(LineageBackfillRunner.class).getIfAvailable();
        String line = runner == null
                ? "[lineage-backfill] SUMMARY exit=" + EXIT_NOT_MEASURED
                        + " 上下文里没有 LineageBackfillRunner (profile 没生效?), 一条都没执行"
                : runner.summaryLine();
        int code = SpringApplication.exit(context);
        if (runner == null) {
            code = EXIT_NOT_MEASURED;
        }
        System.out.println(line);
        return code;
    }
}

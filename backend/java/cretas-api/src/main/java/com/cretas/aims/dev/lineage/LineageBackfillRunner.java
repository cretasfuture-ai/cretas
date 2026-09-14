package com.cretas.aims.dev.lineage;

import com.cretas.aims.service.lineage.LineageBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 历史血缘回填的命令入口。
 *
 * <h2>怎么跑</h2>
 *
 * <pre>{@code
 * # 预演（一条边都不写，只报「扫到多少行 / 其中多少行构成边」）
 * SPRING_PROFILES_ACTIVE=pg-prod,lineage-backfill \
 *   LINEAGE_BACKFILL_FACTORIES=F006,LIUSHANMEN \
 *   LINEAGE_BACKFILL_DRY_RUN=true \
 *   java -jar aims-0.0.1-SNAPSHOT.jar
 *
 * # 真写（只写不删；重跑安全，已写过的落 ALREADY_EXISTS）
 * ... LINEAGE_BACKFILL_DRY_RUN=false ...
 * }</pre>
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
@Profile("lineage-backfill")
@RequiredArgsConstructor
public class LineageBackfillRunner implements ApplicationRunner {

    private final LineageBackfillService backfillService;

    /** 逗号分隔的工厂 id；留空则什么都不做（⛔ 不默认全库跑）。 */
    @Value("${LINEAGE_BACKFILL_FACTORIES:}")
    private String factories;

    /** 默认 true —— 默认预演，真写必须显式关掉。 */
    @Value("${LINEAGE_BACKFILL_DRY_RUN:true}")
    private boolean dryRun;

    @Override
    public void run(ApplicationArguments args) {
        List<String> factoryIds = Arrays.stream(factories.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (factoryIds.isEmpty()) {
            log.warn("[lineage-backfill] LINEAGE_BACKFILL_FACTORIES 为空, 不执行任何回填。"
                    + "⛔ 这里刻意不默认全库跑 —— 回填要按工厂逐个对账。");
            return;
        }
        log.warn("[lineage-backfill] 开始: factories={} dryRun={}", factoryIds, dryRun);
        for (String factoryId : factoryIds) {
            LineageBackfillService.Report report = backfillService.backfillFactory(factoryId, dryRun);
            log.warn("{}", report.render());
        }
        log.warn("[lineage-backfill] 结束。dryRun={} —— 预演走的是与真跑**同一份**查重/对齐判据(只读), "
                        + "三个数与真跑逐条一致; 唯一偏差来自预演与真跑之间数据发生了变化。",
                dryRun);
    }
}

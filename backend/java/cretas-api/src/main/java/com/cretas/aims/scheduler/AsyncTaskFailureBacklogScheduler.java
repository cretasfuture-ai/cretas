package com.cretas.aims.scheduler;

import com.cretas.aims.service.asyncops.AsyncTaskFailureLedger;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 每天问一次台账: {@code @Async} 失败还有没有没处理完的。
 *
 * <h3>⛔ 三态, 不是两态</h3>
 * <pre>
 *   QUIET            没问题        —— 查出来是 0
 *   BACKLOG          有问题        —— 查出来 &gt; 0, 读数有效且指向缺陷
 *   INSTRUMENT_DEAD  这次没量到    —— 根本没查出来 (库不可达 / 表不存在 / 读数无效)
 * </pre>
 * <p>第三态<b>必须</b>用与第二态不同的措辞单独喊。把它折进「没问题」, 会让一个连不上库的
 * 跑批<b>安静地天天绿</b>, 而它一条样本都没看过 —— 本仓已有先例 (52 断言的回归电池
 * 随租户收敛一起死了 4 天, 天天报绿没人处理)。
 *
 * <p>⚠️ 这也是为什么 {@link AsyncTaskFailureLedger#countOpen()} 故意会抛:
 * 如果它把异常吞掉返回 0, 「查不出来」和「查出来是 0」在这里就长成同一个样子,
 * 三态从物理上就退化回两态了。
 *
 * <h3>每次必留一条心跳</h3>
 * <p>不管哪一态, 结束前都打一条 {@code ASYNC_FAILURE_BACKLOG_SWEEP}。
 * 「没有告警」有两种含义 —— 真没事 / 根本没跑; 心跳把它们分开:
 * 有心跳无告警 = 真没事, 无心跳 = 这个跑批自己没跑起来。
 * (本仓硬约束: 验证「X 没发生」时必须同时有一个「Y 一定会发生」的读数。)
 *
 * <h3>⚠️ 为什么不按「今天新增了几条」告警</h3>
 * <p>prod 实测这些失败是<b>阵发</b>的 (2026-07-03 一天 41 条, 多数日子 0 ~ 5 条)。
 * 按增量告警会在爆发日刷屏、在平峰日什么都不说, 而真正要紧的是
 * 「有没有还没人处理的」—— 那是个存量问题, 所以量存量。
 * 存量可以被运维 resolve 掉归零, 这一点很重要: <b>一个永远静不下来的告警等于没有告警。</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskFailureBacklogScheduler {

    /** 三态的显式表达 —— 让「没量到」有名字, 而不是靠一个 0 蒙混过去。 */
    public enum SweepOutcome {
        /** 台账里没有未处理条目。 */
        QUIET,
        /** 有未处理条目, 已告警。 */
        BACKLOG,
        /** 这次没量到 —— 读数作废, 不代表没问题。 */
        INSTRUMENT_DEAD
    }

    private final AsyncTaskFailureLedger ledger;

    /**
     * 站内通知 —— 与「缺料告警」走同一条路 (SalesOrderShortageReportListener)。
     *
     * <p>🔴 2026-08-28: 在此之前这套东西<b>只有一条 WARN 日志</b>, 没有界面、没有通知。
     * 实测: 2026-08-24 的三条失败, 告警每天 09:05 照响, <b>连响 5 天没有人看</b> ——
     * 期间六膳门(真实客户)两张 ¥92,260 的单没有生产计划, 而他们那边接口返回的是 200。
     * ⇒ 一个只写进日志的告警, 在产品里等于不存在。
     */
    private final NotificationService notificationService;

    @Value("${cretas.async-failure-backlog.enabled:true}")
    private boolean enabled;

    /** 每日 09:05 —— 排在上班之后, 让看到告警的人当天就能处理。 */
    @Scheduled(cron = "${cretas.async-failure-backlog.cron:0 5 9 * * ?}")
    @SchedulerLock(
            name = "AsyncTaskFailureBacklogScheduler.sweep",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT1M")
    public void scheduledSweep() {
        if (!enabled) {
            log.debug("[AsyncFailureBacklog] 已禁用 (enabled=false)");
            return;
        }
        sweep();
    }

    /**
     * 真正的判定。独立于 {@code @Scheduled} 方法, 让测试能直接驱动它并断言三态。
     *
     * @return 本次落在哪一态
     */
    public SweepOutcome sweep() {
        int openCount;
        try {
            openCount = ledger.countOpen();
        } catch (Exception instrumentFailure) {
            // ⛔ 这一支绝不能并进下面的「没问题」。措辞与 BACKLOG 刻意不同,
            //    让告警规则能分开匹配。
            log.error("ASYNC_FAILURE_BACKLOG_INSTRUMENT_DEAD —— 查不到台账, 本次读数作废; "
                    + "「没有告警」这次不代表「没有失败」", instrumentFailure);
            log.info("ASYNC_FAILURE_BACKLOG_SWEEP outcome=INSTRUMENT_DEAD open=n/a");
            return SweepOutcome.INSTRUMENT_DEAD;
        }

        if (openCount == 0) {
            log.debug("[AsyncFailureBacklog] 没有未处理的异步失败");
            log.info("ASYNC_FAILURE_BACKLOG_SWEEP outcome=QUIET open=0");
            return SweepOutcome.QUIET;
        }

        // 明细取不到不影响「有积压」这个结论 —— 结论已经成立, 明细只是让它更好用。
        String breakdown;
        try {
            breakdown = renderBreakdown(ledger.openBreakdownByMethod());
        } catch (Exception detailFailure) {
            log.warn("[AsyncFailureBacklog] 明细查询失败, 只报总数", detailFailure);
            breakdown = "(明细查询失败)";
        }

        log.warn("ASYNC_FAILURE_BACKLOG open={} —— 有 {} 条异步失败没人处理。"
                        + "这些失败在用户侧是无感的 (接口当时返回了 200)。明细: {} "
                        + "处理完请调 PUT /api/mobile/{{factoryId}}/async-failures/{{id}}/resolve 让它闭嘴。",
                openCount, openCount, breakdown);
        log.info("ASYNC_FAILURE_BACKLOG_SWEEP outcome=BACKLOG open={}", openCount);

        // 把结论送到人眼前。⚠️ 通知失败不许改变本次的三态结论 ——
        // 「告警发不出去」是另一个问题, 不能让它把「有积压」这个已经成立的读数吃掉。
        try {
            notifyFactoryAdmins();
        } catch (Exception notifyFailure) {
            log.error("[AsyncFailureBacklog] 站内通知发送失败(不影响本次判定 BACKLOG)", notifyFailure);
        }
        return SweepOutcome.BACKLOG;
    }

    /** 通知目标角色 —— 与缺料告警一致, 工厂管理员能在站内看到。 */
    private static final String NOTIFY_ROLE = "FACTORY_ADMIN";

    /**
     * 按工厂把积压发成站内通知。
     *
     * <p>⛔ {@code factory_id} 为 NULL 的行<b>没有人能被通知到</b> —— 单独打一条 WARN 说出来,
     * 不许静默跳过。否则「通知发了」会被读成「所有积压都有人知道了」。
     */
    private void notifyFactoryAdmins() {
        List<Map<String, Object>> byFactory = ledger.openBreakdownByFactory();
        if (byFactory == null || byFactory.isEmpty()) {
            log.warn("[AsyncFailureBacklog] 总数 > 0 但按工厂分组为空, 读数自相矛盾, 本次不发通知");
            return;
        }
        int notified = 0;
        long orphanRows = 0;
        for (Map<String, Object> row : byFactory) {
            Object factoryId = row.get("factory_id");
            long rowsOpen = row.get("rows_open") == null ? 0L : ((Number) row.get("rows_open")).longValue();
            if (factoryId == null || factoryId.toString().isBlank()) {
                orphanRows += rowsOpen;
                continue;
            }
            String title = "有 " + rowsOpen + " 条后台任务失败没人处理";
            String body = "这些失败在用户侧是无感的(接口当时返回了 200), 但业务动作没有真的完成。"
                    + " 最近一次: " + row.get("latest")
                    + "。请到异步失败台账逐条核对, 处理完标记完成。";
            notificationService.notifyRole(factoryId.toString(), NOTIFY_ROLE, title, body);
            notified++;
        }
        log.info("ASYNC_FAILURE_BACKLOG_NOTIFIED factories={} orphanRows={}", notified, orphanRows);
        if (orphanRows > 0) {
            log.warn("[AsyncFailureBacklog] 有 {} 条未处理失败取不到 factory_id, "
                    + "站内通知发不出去 —— 这些只能靠日志/台账接口发现", orphanRows);
        }
    }

    /** 把按方法归类的行渲染成一行人话。 */
    private String renderBreakdown(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            // 总数 > 0 却查不到分组, 说明两条查询看到的东西不一致 —— 说出来, 不要粉饰。
            return "(总数 > 0 但分组为空, 读数自相矛盾)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(row.get("method_signature"))
                    .append(" 未处理 ").append(row.get("rows_open"))
                    .append(" 条/累计发生 ").append(row.get("occurrences"))
                    .append(" 次/最近 ").append(row.get("latest"));
        }
        return sb.toString();
    }
}

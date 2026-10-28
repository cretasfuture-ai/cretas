package com.cretas.aims.config;

import com.cretas.aims.dev.lineage.LineageBackfillMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全应用唯一的 {@code @EnableScheduling} 宿主。
 *
 * <p>原先挂在 {@code CretasBackendApplication} 上（另有 {@code AIReportScheduler} 重复标了一次），
 * 无条件开启 —— 于是任何用同一个 jar 另起的进程都会把全部 {@code @Scheduled}（其中相当一部分没有
 * {@code @SchedulerLock}）和 {@code SchedulingConfigurer}（Canvas-Cron 从库里加载的任务）再跑一份。
 *
 * <p>挪到这里只为加一个 {@code @Profile}：一次性回填进程（{@link LineageBackfillMode#PROFILE}）
 * 不开调度。其余 profile（prod / pg-prod / pg / test / ...）下行为与之前完全一致 ——
 * {@code LineageBackfillProcessIsolationTest} 的反向用例钉着「普通 profile 下调度仍注册了任务」。
 *
 * <p>⛔ 不要再在别的类上加 {@code @EnableScheduling}：任何一处无条件的 {@code @EnableScheduling}
 * 都会让回填进程重新开起全部定时任务。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(LineageBackfillMode.NOT_ACTIVE)
public class SchedulingConfig {
}

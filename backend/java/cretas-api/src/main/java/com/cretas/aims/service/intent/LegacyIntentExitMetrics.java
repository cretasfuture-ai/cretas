package com.cretas.aims.service.intent;

import com.cretas.aims.entity.config.AIIntentConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 旧出口计数与回落告警 (INTENT-DISTILL-20261111, Fable 独立审阅建议 6 前半: 先量再关)。
 *
 * <p>「向量和关键词只给候选、从不授权」在下面这些 Java 出口上不成立 —— 它们不经决策模型直接定意图。
 * 这里<b>只计数、只打日志</b>, ⛔ 不改任何出口的行为; 计数本身出任何错都吞掉。
 *
 * <ul>
 *   <li>{@value #SITE_EXECUTE_PHRASE}: {@code IntentExecutionOrchestrator} 的短语直达 (子串匹配, 命中即按显式意图执行),
 *       出口名 {@code java_phrase_shortcut}</li>
 *   <li>{@value #SITE_RECOGNIZE_EARLY_PHRASE}: {@code AIIntentServiceImpl} 指定两个意图 (QUALITY_CHECK_QUERY /
 *       REPORT_TRENDS) 的早期短语, 出口名 {@code java_early_phrase}</li>
 *   <li>{@value #SITE_RECOGNIZE_PIPELINE}: Python 没给结果 (关闭 / 空 / 异常 / 无调用者) 时的 Java 旧识别管线
 *       (精确 / 短语 / 正则 / 关键词 / 语义 / LLM), 出口名 {@code java_legacy_<MatchMethod>}</li>
 * </ul>
 *
 * <p>读数 (Micrometer → {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@code intent_legacy_exit_hit_total{site, exit, factory, rw, reason}}</li>
 *   <li>{@code intent_legacy_exit_site_requests_total{site}} —— 分母: 到达这个出口判断点的请求数</li>
 * </ul>
 *
 * <p>告警: 仓库里没有现成的告警渠道 (无 webhook / 钉钉 / 企微接线), 所以窗口内故障命中率超阈值时打一条
 * 固定前缀的 WARN 结构化日志 {@value #ALERT_LOG_PREFIX}, 由日志 grep 捕获。阈值是配置
 * ({@code cretas.ai.legacy-exit.alert.*}, 也可用环境变量 {@code CRETAS_AI_LEGACY_EXIT_ALERT_*} 覆盖)。
 */
@Slf4j
@Component
public class LegacyIntentExitMetrics {

    public static final String SITE_EXECUTE_PHRASE = "java_execute_phrase";
    public static final String SITE_RECOGNIZE_EARLY_PHRASE = "java_recognize_early_phrase";
    public static final String SITE_RECOGNIZE_PIPELINE = "java_recognize_pipeline";
    public static final String ALERT_LOG_PREFIX = "INTENT_LEGACY_EXIT_ALERT";
    static final String HIT_METER = "intent.legacy.exit.hit";
    static final String SITE_METER = "intent.legacy.exit.site.requests";

    /** 配置使然的主路径, 不是故障: 照样计数, 但不进告警分子。 */
    static final Set<String> NON_FAULT_REASONS = Set.of("python_disabled", "restaurant_context");

    private final MeterRegistry registry;
    private final long windowMillis;
    private final int minSamples;
    private final Map<String, Double> thresholds;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 默认阈值的理由:
     * <ul>
     *   <li>旧管线回落 5%: Python 决策层开着时它应答住几乎全部请求; 故障类回落 (Python 空 / 异常 / 无调用者)
     *       超过 1/20 就说明决策链在降级, 关键词与正则正在替它定意图。</li>
     *   <li>两处短语直达 20%: 它们按设计排在决策层前面, 现在没有基线 (11-05 审计「实际占比未知」)。
     *       超过 1/5 意味着决策层的评测读数已不能代表线上; 先放宽, 拿到第一周读数后再收。</li>
     *   <li>窗口 15 分钟、至少 20 次: 目前没有真实客户, 流量很低, 太小的分母一次失败就是 100%。</li>
     * </ul>
     */
    @Autowired
    public LegacyIntentExitMetrics(
            ObjectProvider<MeterRegistry> registry,
            @Value("${cretas.ai.legacy-exit.alert.window-seconds:900}") long windowSeconds,
            @Value("${cretas.ai.legacy-exit.alert.min-samples:20}") int minSamples,
            @Value("${cretas.ai.legacy-exit.alert.pipeline-fallback-rate:0.05}") double pipelineRate,
            @Value("${cretas.ai.legacy-exit.alert.phrase-shortcut-rate:0.20}") double phraseRate) {
        this(registry.getIfAvailable(), windowSeconds, minSamples, pipelineRate, phraseRate);
    }

    public LegacyIntentExitMetrics(MeterRegistry registry, long windowSeconds, int minSamples,
                                   double pipelineRate, double phraseRate) {
        this.registry = registry;
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
        this.minSamples = Math.max(1, minSamples);
        this.thresholds = Map.of(
                SITE_RECOGNIZE_PIPELINE, pipelineRate,
                SITE_EXECUTE_PHRASE, phraseRate,
                SITE_RECOGNIZE_EARLY_PHRASE, phraseRate);
    }

    /** 请求到达了判断点, 但没有走旧出口 (决策层 / Python 答了)。 */
    public void recordPass(String site) {
        try {
            incrementSite(site);
            observe(site, false, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("legacy exit pass metric failed: {}", e.toString());
        }
    }

    /** 旧出口定了意图 (或旧出口也没答出来, exit 以 NONE 结尾)。 */
    public void recordExit(String site, String exit, String factoryId, String rw, String reason) {
        com.cretas.aims.service.intent.flywheel.IntentFlywheelTurnContext.noteExit(exit);   // 意图飞轮痕迹 (只记)
        try {
            incrementSite(site);
            if (registry != null) {
                registry.counter(HIT_METER,
                        "site", nz(site), "exit", nz(exit), "factory", nz(factoryId),
                        "rw", nz(rw), "reason", nz(reason)).increment();
            }
            log.info("intent legacy exit: site={} exit={} factory={} rw={} reason={}", site, exit, factoryId, rw, reason);
            observe(site, !NON_FAULT_REASONS.contains(reason), System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("legacy exit hit metric failed: {}", e.toString());
        }
    }

    /** 读写标签: 判定出错不影响任何东西, 记 unknown。 */
    public static String rw(AIIntentConfig cfg, BooleanSupplier isWrite) {
        if (cfg == null) {
            return "none";
        }
        try {
            return isWrite.getAsBoolean() ? "write" : "read";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 异常归类: 只留类名 (标签基数有界)。 */
    public static String exceptionReason(String prefix, Throwable t) {
        return prefix + "_" + (t == null ? "unknown" : t.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------- 告警窗口

    /** 返回 true = 这一次触发了告警 (仅测试用)。 */
    boolean observe(String site, boolean faultHit, long nowMillis) {
        Double threshold = thresholds.get(site);
        if (threshold == null) {
            return false;
        }
        Window w = windows.computeIfAbsent(site, k -> new Window(nowMillis));
        String payload;
        synchronized (w) {
            if (nowMillis - w.start >= windowMillis || nowMillis < w.start) {
                w.reset(nowMillis);
            }
            w.requests++;
            if (faultHit) {
                w.hits++;
            }
            if (w.alerted || w.requests < minSamples) {
                return false;
            }
            double rate = (double) w.hits / w.requests;
            if (rate <= threshold) {
                return false;
            }
            w.alerted = true;
            payload = String.format(java.util.Locale.ROOT,
                    "{\"site\":\"%s\",\"rate\":%.4f,\"threshold\":%s,\"hits\":%d,\"requests\":%d,"
                            + "\"window_s\":%d,\"window_start\":%d}",
                    site, rate, threshold, w.hits, w.requests, windowMillis / 1000, w.start / 1000);
        }
        log.warn("{} {}", ALERT_LOG_PREFIX, payload);
        return true;
    }

    private void incrementSite(String site) {
        if (registry != null) {
            registry.counter(SITE_METER, "site", nz(site)).increment();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static final class Window {
        long start;
        long requests;
        long hits;
        boolean alerted;

        Window(long start) {
            reset(start);
        }

        void reset(long now) {
            start = now;
            requests = 0;
            hits = 0;
            alerted = false;
        }
    }
}

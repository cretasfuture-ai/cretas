package com.cretas.aims.service.intent;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 本厂 AI 助手「先问口径」档位 (INTENT-DISTILL-20261111, Steve 2026-11-14 拍板: 按工厂选, 不是全局只留一种)。
 *
 * <p>配置放在现有的 factory_thresholds ({@link ThresholdKeys#AI_INTENT_ASK_POLICY}, 工厂行 &gt; 全局 '*' 行),
 * 与其它工厂开关同一套 (机制归系统、策略按厂配), ⛔ 不另建配置表。读出来的档位随识别请求的
 * {@code askPolicy} 字段交给 Python 决策层 (backend/python/ai/ask_policy.py), 由它选判断题规则与先问门槛:
 * <ul>
 *   <li>{@code CONSERVATIVE} 保守 = 多问 (史浩禾 39 题口径, #1711 的 v3)</li>
 *   <li>{@code AGGRESSIVE} 激进 = 少问 (Steve 自答 300 题口径), 默认档</li>
 * </ul>
 * 两档都不放宽写操作: 写意图照旧由 Java 先出确认卡、逐笔确认。
 *
 * <p>取不到 (没有行 / 读库异常) 或认不出的值 ⇒ 默认档, 并计数 {@value #DEFAULT_METER}{reason} 与打 WARN, ⛔ 不静默。
 * Python 侧收不到档位时同样按默认档并计数 (intent_ask_policy_total{source="default_missing"})。
 * 全局回滚开关 AI_DECISION_LUNA_POLICY (Python 环境变量) 优先于这里的按厂档位。
 */
@Slf4j
@Component
public class IntentAskPolicyResolver {

    public static final String CONSERVATIVE = "CONSERVATIVE";
    public static final String AGGRESSIVE = "AGGRESSIVE";
    public static final String DEFAULT_POLICY = AGGRESSIVE;
    static final String DEFAULT_METER = "intent.ask_policy.defaulted";

    private final ThresholdResolverService thresholds;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public IntentAskPolicyResolver(ThresholdResolverService thresholds) {
        this.thresholds = thresholds;
    }

    /** 本厂档位: CONSERVATIVE / AGGRESSIVE。取不到或认不出 ⇒ {@link #DEFAULT_POLICY} 并计数。 */
    public String resolve(String factoryId) {
        Optional<FactoryThreshold> row;
        try {
            row = thresholds.resolve(factoryId, ThresholdKeys.AI_INTENT_ASK_POLICY);
        } catch (Exception e) {
            return defaulted(factoryId, "error", e.getClass().getSimpleName());
        }
        if (row.isEmpty() || row.get().getThresholdValue() == null || row.get().getThresholdValue().isBlank()) {
            return defaulted(factoryId, "missing", null);
        }
        String raw = row.get().getThresholdValue().trim();
        String upper = raw.toUpperCase(Locale.ROOT);
        if (CONSERVATIVE.equals(upper) || "保守".equals(raw)) {
            return CONSERVATIVE;
        }
        if (AGGRESSIVE.equals(upper) || "激进".equals(raw)) {
            return AGGRESSIVE;
        }
        return defaulted(factoryId, "unknown", raw.length() > 40 ? raw.substring(0, 40) : raw);
    }

    private String defaulted(String factoryId, String reason, String detail) {
        log.warn("AI 先问档位取不到, 按默认档 {}: factoryId={} reason={} detail={}",
                DEFAULT_POLICY, factoryId, reason, detail);
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(DEFAULT_METER, "reason", reason).increment();
            } catch (Exception ignored) {
                // 计数绝不能改变识别结果
            }
        }
        return DEFAULT_POLICY;
    }
}

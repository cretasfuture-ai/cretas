package com.cretas.aims.service.intent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** INTENT-DISTILL-20261111: 旧出口计数器与阈值告警本身。接线由 *WiringContractTest 在真实入口上守。 */
@DisplayName("旧出口计数 — 计数器标签与阈值告警")
class LegacyIntentExitMetricsContractTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LegacyIntentExitMetrics metrics = new LegacyIntentExitMetrics(registry, 900, 20, 0.05, 0.20);

    private double hit(String site, String exit, String rw, String reason) {
        Counter c = registry.find(LegacyIntentExitMetrics.HIT_METER)
                .tags("site", site, "exit", exit, "factory", "F1", "rw", rw, "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private double site(String site) {
        Counter c = registry.find(LegacyIntentExitMetrics.SITE_METER).tags("site", site).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void exitIncrementsHitAndDenominator_passOnlyDenominator() {
        String s = LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE;
        metrics.recordExit(s, "java_legacy_KEYWORD", "F1", "read", "python_empty");
        metrics.recordPass(s);
        assertThat(hit(s, "java_legacy_KEYWORD", "read", "python_empty")).isEqualTo(1.0);
        assertThat(site(s)).isEqualTo(2.0);
    }

    @Test
    void alarmFiresOncePerWindowOnlyAboveThresholdWithEnoughSamples() {
        String s = LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE;
        for (int i = 0; i < 19; i++) {
            assertThat(metrics.observe(s, true, 1_000L + i)).isFalse(); // 分母不够
        }
        assertThat(metrics.observe(s, true, 1_020L)).isTrue();
        assertThat(metrics.observe(s, true, 1_021L)).isFalse(); // 同一窗只喊一次
        // 新窗口: 1/20 = 5% 不超过 5%
        long next = 1_000L + 900_000L;
        for (int i = 0; i < 19; i++) {
            metrics.observe(s, false, next + i);
        }
        assertThat(metrics.observe(s, true, next + 20)).isFalse();
    }

    @Test
    void nonFaultReasonsAreCountedButNeverAlarm() {
        String s = LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE;
        for (int i = 0; i < 30; i++) {
            metrics.recordExit(s, "java_legacy_PHRASE_MATCH", "F1", "read", "python_disabled");
        }
        assertThat(hit(s, "java_legacy_PHRASE_MATCH", "read", "python_disabled")).isEqualTo(30.0);
        // 30 次都是非故障: 再来一次故障也只有 1/31, 不过 5%
        assertThat(metrics.observe(s, true, System.currentTimeMillis())).isFalse();
    }

    @Test
    void phraseSitesUseTheirOwnThreshold() {
        String s = LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE;
        for (int i = 0; i < 16; i++) {
            metrics.observe(s, false, 10L + i);
        }
        for (int i = 0; i < 3; i++) {
            metrics.observe(s, true, 30L + i);
        }
        assertThat(metrics.observe(s, true, 40L)).isFalse(); // 4/20 = 20%, 不超过
        assertThat(metrics.observe(s, true, 41L)).isTrue();  // 5/21 > 20%
    }

    @Test
    void rwLabelNeverThrows() {
        assertThat(LegacyIntentExitMetrics.rw(null, () -> true)).isEqualTo("none");
        com.cretas.aims.entity.config.AIIntentConfig cfg = new com.cretas.aims.entity.config.AIIntentConfig();
        assertThat(LegacyIntentExitMetrics.rw(cfg, () -> true)).isEqualTo("write");
        assertThat(LegacyIntentExitMetrics.rw(cfg, () -> false)).isEqualTo("read");
        assertThat(LegacyIntentExitMetrics.rw(cfg, () -> { throw new IllegalStateException("x"); }))
                .isEqualTo("unknown");
    }

    @Test
    void nullRegistryIsANoOp() {
        LegacyIntentExitMetrics none = new LegacyIntentExitMetrics((io.micrometer.core.instrument.MeterRegistry) null, 900, 20, 0.05, 0.20);
        none.recordExit(LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE, "java_phrase_shortcut", "F1", "read", "x");
        none.recordPass(LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE);
    }
}

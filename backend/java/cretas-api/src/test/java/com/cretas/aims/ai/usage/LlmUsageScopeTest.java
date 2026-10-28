package com.cretas.aims.ai.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLM usage 作用域")
class LlmUsageScopeTest {

    @Test
    @DisplayName("没有 LLM 调用：NONE，token 为空而不是 0")
    void noCalls() {
        try (LlmUsageScope s = LlmUsageScope.open()) {
            LlmUsageScope.Usage u = s.usage();
            assertEquals(LlmUsageScope.State.NONE, u.state());
            assertNull(u.totalTokens());
        }
    }

    @Test
    @DisplayName("provider 没给 usage 的调用让合计变为 UNKNOWN，而不是按 0 累加")
    void missingUsageMakesTotalUnknown() {
        try (LlmUsageScope s = LlmUsageScope.open()) {
            LlmUsageScope.report(10, 2, 12);
            LlmUsageScope.report(null, null, null);
            LlmUsageScope.Usage u = s.usage();
            assertEquals(LlmUsageScope.State.UNKNOWN, u.state());
            assertEquals(2, u.calls());
            assertNull(u.totalTokens());
        }
    }

    @Test
    @DisplayName("作用域外的调用不计入；嵌套时算在最内层，关闭后回到外层")
    void scopingAndNesting() {
        LlmUsageScope.report(1, 1, 2); // no open scope: ignored, no exception
        try (LlmUsageScope outer = LlmUsageScope.open()) {
            LlmUsageScope.report(5, 5, 10);
            try (LlmUsageScope inner = LlmUsageScope.open()) {
                LlmUsageScope.report(1, 1, 2);
                assertEquals(2, inner.usage().totalTokens());
            }
            LlmUsageScope.report(3, 3, 6);
            assertEquals(16, outer.usage().totalTokens());
        }
        try (LlmUsageScope fresh = LlmUsageScope.open()) {
            assertEquals(LlmUsageScope.State.NONE, fresh.usage().state());
        }
    }

    @Test
    @DisplayName("只有 total 的 usage 也算已知")
    void totalOnlyIsKnown() {
        try (LlmUsageScope s = LlmUsageScope.open()) {
            LlmUsageScope.report(null, null, 99);
            assertEquals(LlmUsageScope.State.KNOWN, s.usage().state());
            assertEquals(99, s.usage().totalTokens());
        }
    }
}

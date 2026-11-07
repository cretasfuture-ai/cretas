package com.cretas.aims.service.execution;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression contract for follow-ups after a successful material write.
 * The resolver only creates a confirmation suggestion; it must not turn a
 * correction/reversal phrase into another delta write.
 */
class FollowUpSubmittedWriteSafetyContractTest {

    private static final long NOW = 1_800_000_000_000L;
    private final FollowUpResolver resolver = new FollowUpResolver();

    private static Map<String, Object> last(Map<String, Object> params) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("intentCode", "MATERIAL_BATCH_USE");
        action.put("params", params);
        action.put("at", NOW - 60_000L);
        return action;
    }

    @Test
    void submittedSetAndSubtractDoNotSuggestASecondBusinessCall() {
        Map<String, Object> action = last(Map.of("batchId", "MB-7", "quantity", 50, "unit", "kg"));

        assertThat(resolver.resolve("改成80公斤", action, NOW)).isEmpty();
        assertThat(resolver.resolve("少十公斤", action, NOW)).isEmpty();
    }

    @Test
    void spokenUnitIsRejectedWhenLastActionHasNoKnownUnit() {
        Map<String, Object> action = last(Map.of("batchId", "MB-7", "quantity", 50));

        assertThat(resolver.resolve("再加80箱", action, NOW)).isEmpty();
    }

    @Test
    void addWithKnownUnitRemainsAvailable() {
        Map<String, Object> action = last(Map.of("batchId", "MB-7", "quantity", 50, "unit", "kg"));

        assertThat(resolver.resolve("再加十公斤", action, NOW)).isPresent();
    }
}

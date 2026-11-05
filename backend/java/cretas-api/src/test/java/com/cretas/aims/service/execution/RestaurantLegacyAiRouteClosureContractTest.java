package com.cretas.aims.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-level guard for the retired restaurant execution lanes.
 *
 * <p>This is deliberately a contract test: the old routes are compatibility
 * seams only and must not silently become active again by reintroducing a
 * client call in one of the three execution front doors.</p>
 */
class RestaurantLegacyAiRouteClosureContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");

    @Test
    void retiredRoutesAreDisabledAtEveryJavaExecutionFrontDoor() throws Exception {
        String orchestrator = read(MAIN.resolve(
                "service/execution/IntentExecutionOrchestrator.java"));
        String dynamic = read(MAIN.resolve(
                "service/execution/DynamicToolSelectionService.java"));
        String sse = read(MAIN.resolve(
                "service/execution/SseStreamingService.java"));
        Path retiredRestaurantToolPackage = MAIN.resolve("ai/tool/impl/restaurant");

        // The production restaurant Agent/Tool package was removed entirely. Keep this
        // as a filesystem assertion so a deleted class cannot be resurrected silently.
        assertThat(Files.exists(retiredRestaurantToolPackage))
                .as("restaurant Agent/Tool package must remain retired")
                .isFalse();
        assertThat(orchestrator).doesNotContain(
                "restaurantGrossMarginChatRouteSelector.select(",
                "tieredIntentDelegate.tryDelegate(");
        assertThat(dynamic).doesNotContain("tieredIntentDelegate.tryDelegate(");
        assertThat(sse).doesNotContain(
                "restaurantGrossMarginChatRouteSelector.select(",
                "sseTieredIntentDelegate.tryDelegate(");
        assertThat(orchestrator + dynamic + sse).doesNotContain(
                "GoldFinanceClient", "fetchTieredIntentAnswer",
                "sseTieredIntentDelegate.tryDelegate(");
    }

    @Test
    void defaultSuggestionsDoNotAdvertiseRetiredRestaurantIntents() throws Exception {
        String source = read(MAIN.resolve(
                "service/execution/IntentExecutionOrchestrator.java"));
        int start = source.indexOf("buildDefaultSuggestions(String factoryId)");
        int end = source.indexOf("\n    /**", start + 1);
        assertThat(start).isGreaterThan(0);
        assertThat(end).isGreaterThan(start);
        String method = source.substring(start, end);
        assertThat(method).doesNotContain(
                "RESTAURANT_BESTSELLER_QUERY",
                "RESTAURANT_STORE_REVENUE_RANK",
                "RESTAURANT_ORDER_STATISTICS");
    }

    private static String read(Path relative) throws Exception {
        return Files.readString(relative, StandardCharsets.UTF_8);
    }
}

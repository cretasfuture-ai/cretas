package com.cretas.aims.ai.tool.impl.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Contract for the retired restaurant tiered delegate compatibility seam. */
class TieredIntentDelegateTest {

    @Test
    void retiredDelegateAlwaysFallsThroughWithoutAClient() {
        TieredIntentDelegate delegate = new TieredIntentDelegate();

        assertThat(delegate.tryDelegate(
                "DEMO_REST",
                Map.of("userInput", "米饭销量"),
                Map.of(),
                "restaurant_dish_sales_ranking")).isNull();
    }
}

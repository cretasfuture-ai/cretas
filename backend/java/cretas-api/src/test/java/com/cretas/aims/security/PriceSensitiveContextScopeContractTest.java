package com.cretas.aims.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class PriceSensitiveContextScopeContractTest {

    @AfterEach
    void cleanup() {
        PriceSensitiveContext.clear();
    }

    @Test
    void scopedPriceDecisionRestoresNestedAndPriorThreadState() throws Exception {
        Method scoped = PriceSensitiveContext.class.getMethod("scoped", boolean.class);
        try (AutoCloseable outer = (AutoCloseable) scoped.invoke(null, true)) {
            assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isTrue();
            try (AutoCloseable inner = (AutoCloseable) scoped.invoke(null, false)) {
                assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
            }
            assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isTrue();
        }
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }

    @Test
    void scopedPriceDecisionRestoresAfterExceptionAndAcrossPooledWorkerReuse() throws Exception {
        Method scoped = PriceSensitiveContext.class.getMethod("scoped", boolean.class);
        try {
            try (AutoCloseable ignored = (AutoCloseable) scoped.invoke(null, true)) {
                throw new IllegalStateException("scope failure");
            }
        } catch (IllegalStateException expected) {
            // The scope must restore even when the body fails.
        }
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = worker.submit(() -> {
                try (AutoCloseable ignored = (AutoCloseable) scoped.invoke(null, true)) {
                    return PriceSensitiveContext.shouldHide("procurement:price:view");
                }
            });
            assertThat(first.get()).isTrue();
            assertThat(worker.submit(() ->
                    PriceSensitiveContext.shouldHide("procurement:price:view")).get()).isFalse();

            try (AutoCloseable ignored = (AutoCloseable) scoped.invoke(null, true)) {
                assertThat(worker.submit(() ->
                        PriceSensitiveContext.shouldHide("procurement:price:view")).get()).isFalse();
            }
        } finally {
            worker.shutdownNow();
        }
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }
}

package com.cretas.aims.security;

/**
 * Thread-local context that signals to Jackson serialization layer
 * ({@link PriceSensitiveSerializerModifier}) whether the current request
 * lacks the {@code procurement:price:view} permission, so {@code @PriceSensitive}
 * properties should be hidden (emit null) without invoking getters.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Set by {@link PriceFieldResponseAdvice#beforeBodyWrite} when the current
 * user lacks the price-view permission. Cleared by
 * {@link PriceFieldResponseAdvice} in a {@code finally} block at the end of
 * the same call. Always cleared — no leaks across requests.
 *
 * <h2>Thread model</h2>
 *
 * <p>Spring MVC serializes the response body on the same thread that runs the
 * controller advice's {@code beforeBodyWrite}. Jackson invokes the serializer
 * chain (including our {@link PriceSensitiveSerializerModifier}) synchronously
 * from that thread, so the ThreadLocal set in {@code beforeBodyWrite} is
 * visible. The body is fully serialized before {@code beforeBodyWrite} returns
 * control (technically the body is queued; the actual write happens in the
 * {@link org.springframework.http.converter.HttpMessageConverter#write}
 * invocation that follows). Spring's
 * {@code ResponseBodyAdvice} contract is that {@code beforeBodyWrite} is
 * invoked just before the converter writes the body — same thread.
 *
 * <p>Async / WebFlux are out of scope (this project uses Servlet stack).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-12 (P0 hotfix)
 */
public final class PriceSensitiveContext {

    /**
     * {@code true} when the current request lacks the price-view permission,
     * meaning {@code @PriceSensitive} properties should be hidden.
     */
    private static final ThreadLocal<Boolean> HIDE_PRICES = new ThreadLocal<>();

    private PriceSensitiveContext() {
        // Utility class
    }

    /**
     * Mark the current thread as requiring price hiding. Always pair with
     * {@link #clear()} in a {@code finally} block.
     */
    public static void hide() {
        HIDE_PRICES.set(Boolean.TRUE);
    }

    /**
     * Installs a price visibility decision for a synchronous tool invocation and restores the
     * previous decision when the scope closes. This is safe for nested invocations and pooled
     * worker threads.
     */
    public static Scope scoped(boolean hidePrices) {
        Boolean previous = HIDE_PRICES.get();
        HIDE_PRICES.set(hidePrices);
        return () -> {
            if (previous == null) {
                HIDE_PRICES.remove();
            } else {
                HIDE_PRICES.set(previous);
            }
        };
    }

    /**
     * 只隐藏列出的口径的工具调用作用域 (S5-12)。结束时还原两个 ThreadLocal, 嵌套调用安全。
     */
    public static Scope scopedSpecs(java.util.Set<String> hiddenSpecs) {
        Boolean previousAll = HIDE_PRICES.get();
        java.util.Set<String> previousSpecs = HIDDEN_SPECS.get();
        HIDE_PRICES.set(Boolean.FALSE);
        HIDDEN_SPECS.set(java.util.Set.copyOf(hiddenSpecs));
        return () -> {
            if (previousAll == null) HIDE_PRICES.remove(); else HIDE_PRICES.set(previousAll);
            if (previousSpecs == null) HIDDEN_SPECS.remove(); else HIDDEN_SPECS.set(previousSpecs);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Returns {@code true} when the property gated by {@code permission}
     * should be hidden in the current serialization. The Jackson modifier
     * uses this to short-circuit getter invocation.
     *
     * <p>{@link #hide()} / {@code scoped(true)} 隐藏全部口径; {@link #hideOnly} 只隐藏列出的口径
     * (S5-12: 采购价按配置的 procurement 读权限判, 与默认口径分开)。
     */
    public static boolean shouldHide(String permission) {
        if (Boolean.TRUE.equals(HIDE_PRICES.get())) {
            return true;
        }
        java.util.Set<String> hidden = HIDDEN_SPECS.get();
        return hidden != null && hidden.contains(permission);
    }

    /** 只隐藏这些口径 ({@link PriceSensitive#permission()} 的取值)。与 {@link #hide()} 一样须在 finally 里 {@link #clear()}。 */
    public static void hideOnly(java.util.Set<String> hiddenSpecs) {
        HIDDEN_SPECS.set(java.util.Set.copyOf(hiddenSpecs));
    }

    private static final ThreadLocal<java.util.Set<String>> HIDDEN_SPECS = new ThreadLocal<>();

    /**
     * Clear the ThreadLocal. <b>MUST</b> be called in a {@code finally}
     * block at the end of every request that called {@link #hide}.
     */
    public static void clear() {
        HIDE_PRICES.remove();
        HIDDEN_SPECS.remove();
    }
}

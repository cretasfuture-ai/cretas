package com.cretas.aims.ai.usage;

/**
 * Collects the token usage of every LLM call made on the current thread while the scope is open
 * (one scheduled run, one task). The LLM client reports each response's usage here; a call whose
 * response carries no usage makes the total UNKNOWN. It is never counted as zero tokens.
 *
 * <p>Scopes nest: a call is reported to the innermost open scope only. Calls made on other threads
 * are not seen, and a total that cannot be trusted must be shown as unknown by the reader.</p>
 */
public final class LlmUsageScope implements AutoCloseable {

    public enum State { NONE, KNOWN, UNKNOWN }

    public record Usage(State state, int calls, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        public static Usage none() { return new Usage(State.NONE, 0, null, null, null); }
    }

    private static final ThreadLocal<LlmUsageScope> CURRENT = new ThreadLocal<>();

    private final LlmUsageScope parent;
    private int calls;
    private int totalUnknown;
    private int promptUnknown;
    private int completionUnknown;
    private long prompt;
    private long completion;
    private long total;

    private LlmUsageScope(LlmUsageScope parent) {
        this.parent = parent;
    }

    public static LlmUsageScope open() {
        LlmUsageScope scope = new LlmUsageScope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** Called by the LLM client for every response; a null counter means the provider did not say. */
    public static void report(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        LlmUsageScope scope = CURRENT.get();
        if (scope == null) return;
        scope.record(promptTokens, completionTokens, totalTokens);
    }

    private synchronized void record(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        calls++;
        if (promptTokens == null) promptUnknown++; else prompt += promptTokens;
        if (completionTokens == null) completionUnknown++; else completion += completionTokens;
        if (totalTokens != null) {
            total += totalTokens;
        } else if (promptTokens != null && completionTokens != null) {
            total += (long) promptTokens + completionTokens;
        } else {
            totalUnknown++; // no total and no way to compute it: this call's cost is unknown
        }
    }

    public synchronized Usage usage() {
        if (calls == 0) return Usage.none();
        if (totalUnknown > 0) return new Usage(State.UNKNOWN, calls, null, null, null);
        // A split the provider did not give (e.g. streaming reports only a total) stays null, never 0.
        return new Usage(State.KNOWN, calls, promptUnknown == 0 ? clamp(prompt) : null,
                completionUnknown == 0 ? clamp(completion) : null, clamp(total));
    }

    private static int clamp(long v) { return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v; }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}

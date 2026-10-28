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
    private int missing;
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

    /** Called by the LLM client for every response; a null usage (or all-null counters) is "no usage". */
    public static void report(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        LlmUsageScope scope = CURRENT.get();
        if (scope == null) return;
        scope.record(promptTokens, completionTokens, totalTokens);
    }

    private synchronized void record(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        calls++;
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            missing++;
            return;
        }
        int p = promptTokens == null ? 0 : promptTokens;
        int c = completionTokens == null ? 0 : completionTokens;
        prompt += p;
        completion += c;
        total += totalTokens != null ? totalTokens : (long) p + c;
    }

    public synchronized Usage usage() {
        if (calls == 0) return Usage.none();
        if (missing > 0) return new Usage(State.UNKNOWN, calls, null, null, null);
        return new Usage(State.KNOWN, calls, clamp(prompt), clamp(completion), clamp(total));
    }

    private static int clamp(long v) { return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v; }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}

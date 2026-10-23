package com.cretas.aims.service.execution;

import java.util.Objects;

/**
 * Server-created, narrowly scoped identity for the public demo conversation.
 * This is conversation provenance only; it does not grant factory or tool permissions.
 */
public final class DemoConversationScope implements AutoCloseable {
    public static final String FACTORY_ID = "F_DEMO";

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private final Binding previous;

    private DemoConversationScope(Binding previous) {
        this.previous = previous;
    }

    public static DemoConversationScope openTrusted(Long userId, String sessionId) {
        if (userId == null || userId >= 0 || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Demo conversation scope requires a server-owned identity");
        }
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(FACTORY_ID, userId, sessionId));
        return new DemoConversationScope(previous);
    }

    public static boolean allows(String factoryId, Long userId, String sessionId) {
        Binding binding = CURRENT.get();
        return binding != null
                && Objects.equals(binding.factoryId(), factoryId)
                && Objects.equals(binding.userId(), userId)
                && Objects.equals(binding.sessionId(), sessionId);
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    private record Binding(String factoryId, Long userId, String sessionId) { }
}

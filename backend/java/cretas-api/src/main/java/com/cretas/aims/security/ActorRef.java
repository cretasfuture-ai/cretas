package com.cretas.aims.security;

import java.util.Objects;

/** Persisted human identity. Numeric IDs are unique only within their actor namespace. */
public record ActorRef(AuthenticatedActorType type, long id) {
    public ActorRef {
        Objects.requireNonNull(type, "Actor type is required");
        if (id <= 0) throw new IllegalArgumentException("Actor id must be positive");
    }

    /** Compatibility value for columns referencing users. Platform IDs must never enter them. */
    public Long factoryUserId() {
        return type == AuthenticatedActorType.FACTORY_USER ? id : null;
    }
}

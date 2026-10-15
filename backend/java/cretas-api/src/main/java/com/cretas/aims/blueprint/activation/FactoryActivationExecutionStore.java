package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationCenterReceipt;

import java.util.Optional;

/**
 * Durable-store SPI for activation executions.
 *
 * <p>Idempotency and concurrency are the store's job, not the orchestrator's. A read-then-write
 * check in Java cannot stop two concurrent executions of the same key from both running the
 * modules — by the time the second one reads, the first has not written its receipt yet. So the
 * store must reserve the key atomically ({@link #claim}) before any module command is invoked, and
 * an implementation backed by a database must do that with a uniqueness constraint rather than a
 * second read.</p>
 *
 * <p>B26 registers no public activation endpoint; a production implementation existing here does
 * not by itself make activation reachable.</p>
 */
public interface FactoryActivationExecutionStore {

    Optional<ActivationCenterReceipt> find(String factoryId, String idempotencyKey);

    /**
     * Atomically reserve {@code (factoryId, idempotencyKey)} for this execution.
     *
     * @return {@code true} if this caller now owns the key and may invoke module commands;
     *         {@code false} if another execution already holds or completed it — the caller must
     *         then re-read and either replay the finished receipt or refuse as in-flight. An
     *         implementation must never return {@code true} to two concurrent callers.
     */
    boolean claim(String factoryId, String idempotencyKey, String requestDigest, long executedBy);

    /** A legacy factory-only store must never flatten a platform identity into users.id. */
    default boolean claim(String factoryId, String idempotencyKey, String requestDigest,
                          com.cretas.aims.security.ActorRef executedActor) {
        if (executedActor.type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER) {
            throw new IllegalStateException("Activation store does not support typed platform actors");
        }
        return claim(factoryId, idempotencyKey, requestDigest, executedActor.id());
    }

    default boolean claim(String factoryId, String idempotencyKey, String requestDigest,
                          com.cretas.aims.security.ActorRef requestedActor,
                          com.cretas.aims.security.ActorRef approvedActor,
                          com.cretas.aims.security.ActorRef executedActor) {
        return claim(factoryId, idempotencyKey, requestDigest, executedActor);
    }

    ActivationCenterReceipt save(ActivationCenterReceipt receipt);
}

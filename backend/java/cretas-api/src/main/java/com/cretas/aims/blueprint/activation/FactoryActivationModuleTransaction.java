package com.cretas.aims.blueprint.activation;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A module's writes and verified runtime readback commit or roll back together. */
public final class FactoryActivationModuleTransaction {
    private final TransactionTemplate transaction;

    public FactoryActivationModuleTransaction(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public <T> T execute(Supplier<T> action) {
        // Exceptions escape the inner transaction before the center records a FAILED receipt.
        return transaction.execute(status -> action.get());
    }
}
